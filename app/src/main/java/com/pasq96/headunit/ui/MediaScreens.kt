package com.pasq96.headunit.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Environment
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasq96.headunit.R
import com.pasq96.headunit.media.AUDIO_EXT
import com.pasq96.headunit.media.REPEAT_ONE
import com.pasq96.headunit.media.REPEAT_OFF
import com.pasq96.headunit.media.Track
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Common shell of the built-in app screens: full screen, title row with optional actions and close. */
@Composable
internal fun NativeFrame(vm: LauncherHub, title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 28.dp, vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Txt(title, 26, bold = true) }
            actions()
            GlyphButton(G.CLOSE, TextHi, { vm.screen = null })
        }
        content()
    }
}

private fun fmtTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Cover, titles, seek bar and a slot for the transport buttons; [compact] stacks the cover above the text. */
@Composable
private fun PlayerCard(
    art: ImageBitmap?, title: String, sub: String, pos: Long, dur: Long, accent: Color, compact: Boolean,
    modifier: Modifier, onSeek: (Long) -> Unit, controls: @Composable () -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val cover = @Composable {
        Box(Modifier.size(if (compact) 210.dp else 300.dp).clip(RoundedCornerShape(20.dp)).background(Surface2), Alignment.Center) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Glyph(G.MUSIC, TextLo, 90.dp)
        }
    }
    val info = @Composable { m: Modifier ->
        Column(m, Arrangement.Center, if (compact) Alignment.CenterHorizontally else Alignment.Start) {
            Txt(title, 28, bold = true, maxLines = 2, align = if (compact) TextAlign.Center else TextAlign.Start)
            if (sub.isNotBlank()) Txt(sub, 18, TextLo, maxLines = 2, align = if (compact) TextAlign.Center else TextAlign.Start, modifier = Modifier.padding(top = 4.dp))
            val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), Arrangement.SpaceBetween) {
                Txt(fmtTime(if (drag != null) (frac * dur).toLong() else pos), 14, TextLo); Txt(if (dur > 0) fmtTime(dur) else "", 14, TextLo)
            }
            SimpleSlider(frac, accent, { drag = it }, { drag?.let { f -> if (dur > 0) onSeek((f * dur).toLong()) }; drag = null })
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) { controls() }
        }
    }
    if (compact) Column(modifier, Arrangement.Center, Alignment.CenterHorizontally) { cover(); info(Modifier.fillMaxWidth().padding(top = 14.dp)) }
    else Row(modifier, Arrangement.spacedBy(28.dp), Alignment.CenterVertically) { cover(); info(Modifier.weight(1f)) }
}

@Composable
private fun PlayButton(playing: Boolean, accent: Color, onClick: () -> Unit) =
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(42.dp)).background(accent).clickable(onClick = onClick), Alignment.Center) {
        Glyph(if (playing) G.PAUSE else G.PLAY, onAccent(accent), 44.dp)
    }

/** Storage access prompts: the runtime read permission, and the all-files switch in system settings. */
@Composable
private fun StorageAccess(vm: LauncherHub, accent: Color) {
    val ctx = LocalContext.current
    val all = remember(vm.storageTick) { android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager() }
    var tried by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(str(R.string.perm_storage_read), false, accent) { vm.requestStorage() }
            if (all) Chip(str(R.string.perm_storage_all_on), true, accent) { }
            else Chip(str(R.string.perm_storage_all), false, accent) {
                tried = true
                val pkg = Uri.parse("package:${ctx.packageName}")
                val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { ctx.startActivity(app) }.isFailure) runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        // this firmware's settings screen closes at once: the adb command is the way
        if (!all && tried) Txt(str(R.string.perm_storage_adb), 13, TextLo, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
    }
}

// ---- local music: MediaStore library + the built-in player ----

@Composable
internal fun MusicScreen(vm: LauncherHub, accent: Color) {
    val mp = vm.music
    val s by mp.state.collectAsStateWithLifecycle()
    val lib by mp.library.collectAsStateWithLifecycle()
    var scanned by remember { mutableStateOf<Boolean?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { scanned = mp.scan() }
    LaunchedEffect(Unit) { while (true) { pos = mp.position(); dur = mp.duration(); delay(500) } }
    NativeFrame(vm, str(R.string.app_music), actions = { GlyphButton(G.EQ, accent, { vm.screen = NativeApp.EQ }) }) {
        Row(Modifier.fillMaxSize().padding(top = 10.dp), Arrangement.spacedBy(24.dp)) {
            val t = s.track
            PlayerCard(
                s.art, t?.title ?: str(R.string.music_idle), listOfNotNull(t?.artist?.takeIf { it.isNotBlank() }, t?.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
                pos, dur, accent, true, Modifier.weight(0.9f).fillMaxHeight(), { mp.seekTo(it) },
            ) {
                GlyphButton(G.SHUFFLE, if (s.shuffle) accent else TextLo, { mp.toggleShuffle() })
                GlyphButton(G.PREV, TextHi, { mp.previous() }, glyph = 38.dp)
                PlayButton(s.playing, accent) { mp.toggle() }
                GlyphButton(G.NEXT, TextHi, { mp.next() }, glyph = 38.dp)
                Box(contentAlignment = Alignment.Center) {
                    GlyphButton(G.REPEAT, if (s.repeat != REPEAT_OFF) accent else TextLo, { mp.cycleRepeat() })
                    if (s.repeat == REPEAT_ONE) Txt("1", 13, accent, bold = true)
                }
            }
            Column(Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Surface2).padding(12.dp), Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Txt(str(R.string.music_tracks, lib.size), 16, TextLo, bold = true, modifier = Modifier.padding(start = 4.dp))
                    GlyphButton(G.REFRESH, TextHi, { scanned = null }, size = 44.dp, glyph = 26.dp)
                }
                LaunchedEffect(scanned) { if (scanned == null) scanned = mp.scan() }
                if (lib.isEmpty()) Txt(str(if (scanned == false) R.string.music_no_access else R.string.music_empty), 15, TextLo, maxLines = 4, modifier = Modifier.padding(6.dp))
                if (lib.isEmpty() && scanned == false) StorageAccess(vm, accent)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(lib, key = { _, tr -> tr.uri.toString() }) { i, tr ->
                        val on = t?.uri == tr.uri
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) accent else Color.Transparent).clickable { mp.play(lib, i) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Txt(tr.title, 18, if (on) onAccent(accent) else TextHi, bold = on)
                            Txt(listOf(tr.artist, tr.album).filter { it.isNotBlank() }.joinToString(" · "), 13, if (on) onAccent(accent) else TextLo)
                        }
                    }
                }
            }
        }
    }
}

// ---- Bluetooth music: remote control of the phone's media session ----

@Composable
internal fun BtMusicScreen(vm: LauncherHub, accent: Color) {
    val ctx = LocalContext.current
    val np by vm.media.state.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(500) } }
    val source = remember(np.pkg) { np.pkg?.let { runCatching { ctx.packageManager.run { getApplicationLabel(getApplicationInfo(it, 0)).toString() } }.getOrNull() } }
    NativeFrame(vm, str(R.string.app_btmusic)) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            when {
                !np.hasAccess -> Txt(str(R.string.bt_no_access), 18, TextLo, maxLines = 3, align = TextAlign.Center)
                np.title == null && np.pkg == null -> Txt(str(R.string.bt_idle), 20, TextLo, align = TextAlign.Center)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    tick.let { }  // recompute the extrapolated position twice a second
                    PlayerCard(
                        np.art, np.title ?: "", listOfNotNull(np.artist, np.album).filter { it.isNotBlank() }.joinToString(" · "),
                        np.position(), np.dur, accent, false, Modifier.fillMaxWidth(0.9f), { vm.media.seekTo(it) },
                    ) {
                        GlyphButton(G.PREV, TextHi, { vm.media.previous() }, glyph = 42.dp)
                        PlayButton(np.playing, accent) { vm.media.playPause() }
                        GlyphButton(G.NEXT, TextHi, { vm.media.next() }, glyph = 42.dp)
                    }
                    if (source != null) Txt(str(R.string.bt_source, source), 14, TextLo, modifier = Modifier.padding(top = 18.dp))
                }
            }
        }
    }
}

// ---- file manager: internal storage + USB, audio files play in the built-in player ----

private fun File.isAudio() = isFile && extension.lowercase() in AUDIO_EXT

@Composable
internal fun FilesScreen(vm: LauncherHub, accent: Color) {
    val ctx = LocalContext.current
    val mp = vm.music
    val ms by mp.state.collectAsStateWithLifecycle()
    var dir by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var denied by remember { mutableStateOf(false) }
    val roots = remember(dir) {
        val usb = File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }.orEmpty()
        listOf(Environment.getExternalStorageDirectory()) + usb
    }
    LaunchedEffect(dir) {
        val d = dir
        val l = d?.let { withContext(Dispatchers.IO) { it.listFiles() } }
        denied = d != null && l == null
        entries = l?.filter { !it.name.startsWith('.') }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
    }
    val title = dir?.path?.removePrefix(Environment.getExternalStorageDirectory().path)?.ifEmpty { "/" } ?: str(R.string.app_files)
    NativeFrame(vm, str(R.string.app_files), actions = {
        if (dir != null) GlyphButton(G.UP, TextHi, { dir = if (dir in roots) null else dir?.parentFile })
    }) {
        Txt(title, 15, TextLo, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val rows: List<Pair<String, File>> = if (dir == null) roots.mapIndexed { i, f -> (if (i == 0) str(R.string.files_internal) else str(R.string.files_usb, i)) to f } else entries.map { it.name to it }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(rows, key = { _, r -> r.second.path }) { _, (name, f) ->
                    val audio = f.isAudio()
                    val playing = audio && ms.track?.uri == Uri.fromFile(f)
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).background(if (playing) accent else Surface2)
                            .clickable {
                                if (f.isDirectory) dir = f
                                else if (audio) {
                                    val q = entries.filter { it.isAudio() }
                                    mp.play(q.map { Track(Uri.fromFile(it), it.nameWithoutExtension, "", it.parentFile?.name.orEmpty()) }, q.indexOf(f))
                                }
                            }.padding(horizontal = 14.dp),
                        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
                    ) {
                        val tint = if (playing) onAccent(accent) else if (f.isDirectory) accent else TextLo
                        Glyph(if (f.isDirectory) G.FOLDER else if (audio) G.MUSIC else G.DATA, tint, 28.dp)
                        Box(Modifier.weight(1f)) { Txt(name, 18, if (playing) onAccent(accent) else TextHi) }
                        if (f.isFile) Txt(Formatter.formatShortFileSize(ctx, f.length()), 13, if (playing) onAccent(accent) else TextLo)
                    }
                }
            }
            if (denied) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(str(R.string.files_denied), 16, TextLo, maxLines = 4, align = TextAlign.Center)
                StorageAccess(vm, accent)
            }
            else if (dir != null && entries.isEmpty()) Txt(str(R.string.files_empty), 16, TextLo, modifier = Modifier.align(Alignment.Center))
        }
        ms.track?.let { t ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(Surface2).padding(horizontal = 14.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                Glyph(G.MUSIC, accent, 26.dp)
                Box(Modifier.weight(1f).clickable { vm.screen = NativeApp.MUSIC }) { Txt(t.title, 17) }
                GlyphButton(G.PREV, TextHi, { mp.previous() }, size = 48.dp, glyph = 26.dp)
                GlyphButton(if (ms.playing) G.PAUSE else G.PLAY, accent, { mp.toggle() }, size = 48.dp, glyph = 26.dp)
                GlyphButton(G.NEXT, TextHi, { mp.next() }, size = 48.dp, glyph = 26.dp)
            }
        }
    }
}
