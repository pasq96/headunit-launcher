package com.pasq96.headunit.ui

import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasq96.headunit.data.ACCENTS
import com.pasq96.headunit.data.AppInfo
import com.pasq96.headunit.data.DockPos
import com.pasq96.headunit.data.K
import com.pasq96.headunit.data.MediaMode
import com.pasq96.headunit.data.Panels
import com.pasq96.headunit.data.Prefs
import com.pasq96.headunit.data.SettingsStore
import com.pasq96.headunit.embed.Embedder
import com.pasq96.headunit.tools.CarTools
import com.pasq96.headunit.tools.Links
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val p by vm.prefs.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val accent = Color(ACCENTS[p.accent])

    Box(Modifier.fillMaxSize().background(Bg)) {
        val dock = @Composable { Dock(vm, p, apps, accent) }
        when (p.dock) {
            DockPos.BOTTOM -> Column(Modifier.fillMaxSize()) { Split(vm, p, accent, Modifier.weight(1f)); dock() }
            DockPos.LEFT -> Row(Modifier.fillMaxSize()) { dock(); Split(vm, p, accent, Modifier.weight(1f)) }
            DockPos.RIGHT -> Row(Modifier.fillMaxSize()) { Split(vm, p, accent, Modifier.weight(1f)); dock() }
        }
        if (!vm.overlayInWindow) OverlayLayer(vm)
    }
}

// ---- split view: two panels + draggable handle ----

@Composable
private fun Split(vm: HomeViewModel, p: Prefs, accent: Color, modifier: Modifier) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(-1f) }          // live ratio of the LEFT panel while/after dragging
    val left = if (drag >= 0f) drag else p.ratio
    LaunchedEffect(p.ratio) { if (!dragging) drag = -1f }       // stored value caught up: drop the override

    val showNav = p.panels != Panels.MEDIA
    val showMedia = p.panels != Panels.NAV
    val embedMedia = p.mediaMode == MediaMode.EMBED && p.mediaPkg != null

    fun screenRect(c: LayoutCoordinates): Rect {
        val b = c.boundsInWindow(); val loc = IntArray(2); view.getLocationOnScreen(loc)
        return Rect(b.left.roundToInt() + loc[0], b.top.roundToInt() + loc[1], b.right.roundToInt() + loc[0], b.bottom.roundToInt() + loc[1])
    }

    // Debounced: apps are (re)placed only once the layout has settled, never while dragging.
    LaunchedEffect(p.navPkg, p.mediaPkg, p.mediaMode, p.panels, p.swapPanels, p.dock, p.dockDp, p.handleDp, p.ratio, dragging, vm.restoreTick) {
        if (dragging || !Embedder.canEmbed(ctx)) return@LaunchedEffect
        delay(450)
        if (showNav) vm.navRect?.let { r -> p.navPkg?.let { Embedder.launch(ctx, it, r) } }
        if (showMedia && embedMedia) vm.mediaRect?.let { r -> Embedder.launch(ctx, p.mediaPkg!!, r) }
    }

    val navSlot = @Composable { m: Modifier ->
        AppPanel(
            "Navigazione", p.navPkg, vm, accent,
            m.onGloballyPositioned { vm.navRect = screenRect(it) },
        ) { vm.navRect }
    }
    val mediaSlot = @Composable { m: Modifier ->
        val mm = m.onGloballyPositioned { vm.mediaRect = screenRect(it) }
        if (embedMedia) AppPanel("Player", p.mediaPkg, vm, accent, mm) { vm.mediaRect } else MediaCard(vm, p, accent, mm)
    }
    val slots = (if (p.swapPanels) listOf(false to mediaSlot, true to navSlot) else listOf(true to navSlot, false to mediaSlot))
        .filter { (isNav, _) -> if (isNav) showNav else showMedia }

    Row(modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }.padding(8.dp)) {
        slots.forEachIndexed { i, (_, slot) ->
            val w = if (slots.size == 1) 1f else if (i == 0) left else 1f - left
            slot(Modifier.weight(w).fillMaxHeight())
            if (slots.size == 2 && i == 0) Handle(p.handleDp, accent,
                onStart = { dragging = true; drag = left },
                onDrag = { dx -> drag = (drag + dx / width).coerceIn(SettingsStore.MIN_RATIO, SettingsStore.MAX_RATIO) },
                onEnd = { dragging = false; vm.set(K.ratio, drag) })
        }
    }
}

@Composable
private fun Handle(widthDp: Int, accent: Color, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) =
    Box(
        Modifier.width(widthDp.dp).fillMaxHeight().pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { onStart() }, onDragEnd = onEnd, onDragCancel = onEnd) { c, dx -> c.consume(); onDrag(dx) }
        },
        Alignment.Center,
    ) { Box(Modifier.width(6.dp).height(72.dp).clip(RoundedCornerShape(3.dp)).background(accent)) }

/** Placeholder window for an app shown through the Embedder (the real app is drawn above it by the system). */
@Composable
private fun AppPanel(title: String, pkg: String?, vm: HomeViewModel, accent: Color, modifier: Modifier, rect: () -> Rect?) {
    val ctx = LocalContext.current
    val apps by vm.apps.collectAsStateWithLifecycle()
    val label = apps.firstOrNull { it.pkg == pkg }?.label
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Surface)
            .clickable(enabled = pkg != null) { Embedder.launch(ctx, pkg!!, if (Embedder.canEmbed(ctx)) rect() else null) },
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Txt(title, 22, bold = true)
        Txt(
            when {
                pkg == null -> "Tieni premuta un'app nel drawer per sceglierla"
                !Embedder.canEmbed(ctx) -> "${label ?: pkg}: tocca per aprire a schermo intero (freeform non attivo, vedi README)"
                else -> "${label ?: pkg}: tocca per riaprire"
            },
            15, TextLo, modifier = Modifier.padding(24.dp), maxLines = 3, align = TextAlign.Center,
        )
        if (pkg != null) Box(Modifier.size(width = 56.dp, height = 4.dp).background(accent))
    }
}

// ---- native media widget ----

@Composable
private fun MediaCard(vm: HomeViewModel, p: Prefs, accent: Color, modifier: Modifier) {
    val ctx = LocalContext.current
    val np by vm.media.state.collectAsStateWithLifecycle()
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Surface).padding(16.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        if (!np.hasAccess) {
            Txt("Accesso alle notifiche richiesto per leggere il brano", 16, TextLo, align = TextAlign.Center, maxLines = 3)
            Box(Modifier.padding(top = 12.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(accent).clickable { openNotificationAccess(ctx) }.padding(horizontal = 24.dp), Alignment.Center) { Txt("Abilita", 16, Bg, bold = true) }
            return@Column
        }
        Box(Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)).background(Surface2), Alignment.Center) {
            np.art?.let { Image(it, null, Modifier.fillMaxSize()) }
        }
        Txt(np.title ?: "Nessun brano", 22, bold = true, modifier = Modifier.padding(top = 12.dp), align = TextAlign.Center)
        Txt(np.artist ?: "", 16, TextLo, align = TextAlign.Center)
        Row(Modifier.padding(top = 12.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            GlyphButton(G.PREV, TextHi, { vm.media.previous() }, 64.dp, 36.dp)
            GlyphButton(if (np.playing) G.PAUSE else G.PLAY, accent, { vm.media.playPause() }, 80.dp, 48.dp)
            GlyphButton(G.NEXT, TextHi, { vm.media.next() }, 64.dp, 36.dp)
        }
        (np.pkg ?: p.mediaPkg)?.let { src ->
            Txt("Apri app sorgente", 14, accent, modifier = Modifier.padding(top = 8.dp).clickable { Embedder.launch(ctx, src) }.padding(12.dp))
        }
    }
}

// ---- dock ----

@Composable
private fun Dock(vm: HomeViewModel, p: Prefs, apps: List<AppInfo>, accent: Color) {
    val vertical = p.dock != DockPos.BOTTOM
    val pinned = remember(apps, p.pinned) { p.pinned.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } } }
    val mod = (if (vertical) Modifier.width(p.dockDp.dp).fillMaxHeight() else Modifier.height(p.dockDp.dp).fillMaxWidth()).background(Surface)
    val icon: @Composable (AppInfo) -> Unit = { a -> DockIcon(a, p, vm, accent) }
    val buttons: @Composable () -> Unit = {
        GlyphButton(when (p.panels) { Panels.BOTH -> G.SPLIT; Panels.NAV -> G.SOLO_L; Panels.MEDIA -> G.SOLO_R }, TextHi, { vm.cyclePanels() })
        GlyphButton(G.GAUGE, TextHi, { vm.tools = !vm.tools; vm.drawer = false })
        GlyphButton(G.GEAR, TextHi, { vm.settings = !vm.settings; vm.drawer = false })
        GlyphButton(G.GRID, accent, { vm.drawer = !vm.drawer; vm.tools = false }, glyph = 36.dp)
    }
    if (vertical) {
        Column(mod.padding(vertical = 8.dp), Arrangement.spacedBy(6.dp), Alignment.CenterHorizontally) {
            ClockBlock(p)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { items(pinned, key = { it.pkg }) { icon(it) } }
            buttons()
        }
    } else {
        Row(mod.padding(horizontal = 12.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { items(pinned, key = { it.pkg }) { icon(it) } }
            buttons()
            ClockBlock(p)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun DockIcon(a: AppInfo, p: Prefs, vm: HomeViewModel, accent: Color) {
    val ctx = LocalContext.current
    val role = a.pkg == p.navPkg || a.pkg == p.mediaPkg
    Column(
        Modifier.clip(RoundedCornerShape(14.dp))
            .androidxCombined(onClick = { Embedder.launch(ctx, a.pkg, vm.boundsFor(a.pkg)) }, onLong = { vm.menuApp = a })
            .then(if (role) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(a.icon, a.label, Modifier.size(p.iconDp.dp))
        if (p.dockLabels) Txt(a.label, 12, TextLo, modifier = Modifier.width(p.iconDp.dp + 8.dp), align = TextAlign.Center)
    }
}

@Composable
private fun ClockBlock(p: Prefs) {
    val ctx = LocalContext.current
    val time by remember(p.clock24h) { CarTools.clock(p.clock24h) }.collectAsStateWithLifecycle("")
    Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(time, 26, bold = true)
        if (p.showLinks) {
            val l by remember { CarTools.links(ctx) }.collectAsStateWithLifecycle(Links(false, false, false))
            Row(Modifier.padding(top = 2.dp), Arrangement.spacedBy(6.dp)) {
                Txt("WiFi", 12, if (l.wifi) Color(0xFF3DDC84) else TextLo)
                if (l.hotspot) Txt("AP", 12, Color(0xFFFFB300))
                Txt("BT", 12, if (l.bluetooth) Color(0xFF2B7FFF) else TextLo)
            }
        }
    }
}
