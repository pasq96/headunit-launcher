package com.pasq96.headunit.ui

import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasq96.headunit.R
import com.pasq96.headunit.data.accentArgb
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
fun HomeScreen(vm: LauncherHub) = HeadUnitTheme(vm) { HomeContent(vm) }

@Composable
private fun HomeContent(vm: LauncherHub) {
    val p by vm.prefs.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val accent = Color(p.accentArgb)

    Box(Modifier.fillMaxSize().background(Bg)) {
        // With the dock in the overlay service the in-activity dock stays a blank spacer of the same size (same panel geometry).
        val dock = @Composable { if (vm.dockInOverlay) Box(dockSize(p)) else Dock(vm, p, apps, accent) }
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
private fun Split(vm: LauncherHub, p: Prefs, accent: Color, modifier: Modifier) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(-1f) }          // live ratio of the LEFT panel while/after dragging
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
        if (dragging || !p.embedWindows || !Embedder.canEmbed(ctx)) return@LaunchedEffect
        delay(450)
        if (showNav) vm.navRect?.let { r -> p.navPkg?.let { Embedder.launch(ctx, it, r) } }
        if (showMedia && embedMedia) vm.mediaRect?.let { r -> Embedder.launch(ctx, p.mediaPkg, r) }
    }

    val navSlot = @Composable { m: Modifier ->
        AppPanel(
            str(R.string.panel_nav), PickerKind.NAV, p.navPkg, vm, accent,
            m.onGloballyPositioned { vm.navRect = screenRect(it) },
        ) { vm.navRect }
    }
    val mediaSlot = @Composable { m: Modifier ->
        val mm = m.onGloballyPositioned { vm.mediaRect = screenRect(it) }
        when {
            embedMedia -> AppPanel(str(R.string.panel_player), PickerKind.PLAYER, p.mediaPkg, vm, accent, mm) { vm.mediaRect }
            p.mediaMode == MediaMode.RADIO -> RadioPanel(vm, accent, mm)
            else -> MediaSwitch(vm, p, accent, mm)
        }
    }
    val slots = (if (p.swapPanels) listOf(false to mediaSlot, true to navSlot) else listOf(true to navSlot, false to mediaSlot))
        .filter { (isNav, _) -> if (isNav) showNav else showMedia }

    // The ratio is read in the measure pass only: dragging relayouts the panels without recomposing them.
    Layout(
        content = {
            slots.forEachIndexed { i, (_, slot) ->
                slot(Modifier)
                if (slots.size == 2 && i == 0) Handle(p.handleDp, accent,
                    onStart = { dragging = true; if (drag < 0f) drag = p.ratio },
                    onDrag = { dx -> drag = (drag + dx / width).coerceIn(SettingsStore.MIN_RATIO, SettingsStore.MAX_RATIO) },
                    onEnd = { dragging = false; vm.set(K.ratio, drag) })
            }
        },
        modifier = modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }.padding(8.dp),
    ) { ms, c ->
        val w = c.maxWidth; val h = c.maxHeight
        if (ms.size < 3) {
            val only = ms.firstOrNull()?.measure(Constraints.fixed(w, h))
            layout(w, h) { only?.place(0, 0) }
        } else {
            val hw = p.handleDp.dp.roundToPx()
            val hit = maxOf(hw, 44.dp.roundToPx())
            val lw = ((w - hw) * (if (drag >= 0f) drag else p.ratio)).roundToInt()
            val a = ms[0].measure(Constraints.fixed(lw, h))
            val hd = ms[1].measure(Constraints.fixed(hit, h))
            val b = ms[2].measure(Constraints.fixed(w - hw - lw, h))
            layout(w, h) { a.place(0, 0); b.place(lw + hw, 0); hd.place(lw + hw / 2 - hit / 2, 0) }
        }
    }
}

@Composable
private fun Handle(widthDp: Int, accent: Color, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    // callbacks capture the split ratio: always call the latest ones from the long-lived pointer coroutine
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { start() }, onDragEnd = { end() }, onDragCancel = { end() }) { c, dx -> c.consume(); drag(dx) }
        },
        Alignment.Center,
    ) { Box(Modifier.width(6.dp).height(72.dp).clip(RoundedCornerShape(3.dp)).background(accent)) }
}

/** Placeholder window for an app shown through the Embedder (the real app is drawn above it by the system). */
@Composable
private fun AppPanel(title: String, kind: PickerKind, pkg: String?, vm: LauncherHub, accent: Color, modifier: Modifier, rect: () -> Rect?) {
    val ctx = LocalContext.current
    val apps by vm.apps.collectAsStateWithLifecycle()
    val shots by vm.previews.collectAsStateWithLifecycle()
    val p by vm.prefs.collectAsStateWithLifecycle()
    val label = apps.firstOrNull { it.pkg == pkg }?.label
    val shot = pkg?.let { shots[it] }
    val embed = p.embedWindows && Embedder.canEmbed(ctx)
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(Surface)
            .clickable { if (pkg == null) vm.picker = kind else Embedder.launch(ctx, pkg, if (embed) rect() else null) },
        Alignment.Center,
    ) {
        if (shot != null) {
            Image(shot, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Bg.copy(alpha = 0.72f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Txt(label ?: title, 15, TextHi, bold = true)
            }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Txt(title, 22, bold = true)
            Txt(
                when {
                    pkg == null -> str(R.string.panel_pick_app)
                    !embed -> str(R.string.panel_open_fullscreen, label ?: pkg)
                    else -> str(R.string.panel_reopen, label ?: pkg)
                },
                15, TextLo, modifier = Modifier.padding(24.dp), maxLines = 3, align = TextAlign.Center,
            )
            if (pkg != null) Box(Modifier.size(width = 56.dp, height = 4.dp).background(accent))
        }
    }
}

/** Overlay over a hosted app window: covers the system title bar with the panel's own header and turns the panel into a preview. */
@Composable
internal fun ShieldTile(hub: LauncherHub, pkg: String) = HeadUnitTheme(hub) {
    val ctx = LocalContext.current
    val apps by hub.apps.collectAsStateWithLifecycle()
    val label = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
    Column(Modifier.fillMaxSize().clickable { Embedder.launch(ctx, pkg, null) }) {
        Box(Modifier.fillMaxWidth().height(36.dp).background(Surface).padding(horizontal = 16.dp), Alignment.CenterStart) {
            Txt(label, 15, TextHi, bold = true)
        }
    }
}

// ---- native media widget ----

/** Swipeable sources: music (any active media session) and radio; follows the radio while it is the active source. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaSwitch(vm: LauncherHub, p: Prefs, accent: Color, modifier: Modifier) {
    val r by vm.radio.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = if (r.live) 1 else 0) { 2 }
    LaunchedEffect(r.live) { pager.animateScrollToPage(if (r.live) 1 else 0) }
    Column(modifier, Arrangement.spacedBy(6.dp)) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 8.dp) { page ->
            if (page == 0) MediaCard(vm, p, accent, Modifier.fillMaxSize()) else RadioPanel(vm, accent, Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
            repeat(2) { i ->
                val on = pager.currentPage == i
                Box(Modifier.padding(4.dp).size(if (on) 10.dp else 8.dp).clip(CircleShape).background(if (on) accent else Surface2))
            }
        }
    }
}

@Composable
private fun MediaCard(vm: LauncherHub, p: Prefs, accent: Color, modifier: Modifier) {
    val ctx = LocalContext.current
    val np by vm.media.state.collectAsStateWithLifecycle()
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Surface).clickable { (np.pkg ?: p.mediaPkg)?.let { Embedder.launch(ctx, it) } }.padding(16.dp),
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        if (!np.hasAccess) {
            Txt(str(R.string.media_need_access), 16, TextLo, align = TextAlign.Center, maxLines = 3)
            Box(Modifier.padding(top = 12.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(accent).clickable { openNotificationAccess(ctx) }.padding(horizontal = 24.dp), Alignment.Center) { Txt(str(R.string.media_enable), 16, onAccent(accent), bold = true) }
            return@Column
        }
        Box(Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)).background(Surface2), Alignment.Center) {
            np.art?.let { Image(it, null, Modifier.fillMaxSize()) }
        }
        Txt(np.title ?: str(R.string.media_no_track), 22, bold = true, modifier = Modifier.padding(top = 12.dp), align = TextAlign.Center)
        Txt(np.artist ?: "", 16, TextLo, align = TextAlign.Center)
        Row(Modifier.padding(top = 12.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            GlyphButton(G.PREV, TextHi, { vm.media.previous() }, 64.dp, 36.dp)
            GlyphButton(if (np.playing) G.PAUSE else G.PLAY, accent, { vm.media.playPause() }, 80.dp, 48.dp)
            GlyphButton(G.NEXT, TextHi, { vm.media.next() }, 64.dp, 36.dp)
        }
        (np.pkg ?: p.mediaPkg)?.let { src ->
            Txt(str(R.string.media_open_source), 14, accent, modifier = Modifier.padding(top = 8.dp).clickable { Embedder.launch(ctx, src) }.padding(12.dp))
        }
    }
}

// ---- dock ----

@Composable
internal fun DockLayer(hub: LauncherHub) = HeadUnitTheme(hub) {
    val p by hub.prefs.collectAsStateWithLifecycle()
    val apps by hub.apps.collectAsStateWithLifecycle()
    Dock(hub, p, apps, Color(p.accentArgb))
}

/** Tab left on the dock edge while the dock is hidden: a tap brings it back for a few seconds. */
@Composable
internal fun DockHandle(vertical: Boolean, accent: Color, onTap: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(onClick = onTap), if (vertical) Alignment.CenterStart else Alignment.BottomCenter) {
        Box(Modifier.then(if (vertical) Modifier.size(6.dp, 88.dp) else Modifier.size(88.dp, 6.dp)).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.85f)))
    }
}

internal fun dockSize(p: Prefs): Modifier =
    if (p.dock != DockPos.BOTTOM) Modifier.width(p.dockDp.dp).fillMaxHeight() else Modifier.height(p.dockDp.dp).fillMaxWidth()

@Composable
private fun Dock(vm: LauncherHub, p: Prefs, apps: List<AppInfo>, accent: Color) {
    val vertical = p.dock != DockPos.BOTTOM
    val pinned = remember(apps, p.pinned) { p.pinned.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } } }
    val mod = dockSize(p).background(Surface)
    val icon: @Composable (AppInfo) -> Unit = { a -> DockIcon(a, a.pkg == p.navPkg || a.pkg == p.mediaPkg, p.iconDp, p.dockLabels && (p.dock != com.pasq96.headunit.data.DockPos.BOTTOM || p.dockDp >= p.iconDp + 40), vm, accent) }
    val buttons: @Composable () -> Unit = {
        GlyphButton(when (p.panels) { Panels.BOTH -> G.SPLIT; Panels.NAV -> G.SOLO_L; Panels.MEDIA -> G.SOLO_R }, TextHi, { vm.cyclePanels() })
        GlyphButton(G.GEAR, TextHi, { vm.settings = !vm.settings; vm.drawer = false })
        GlyphButton(G.GRID, accent, { vm.drawer = !vm.drawer; vm.tools = false }, glyph = 36.dp)
    }
    if (vertical) {
        Column(mod.padding(vertical = 8.dp), Arrangement.spacedBy(6.dp), Alignment.CenterHorizontally) {
            ClockBlock(p, vertical = true)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { items(pinned, key = { it.pkg }) { icon(it) } }
            buttons()
        }
    } else {
        Row(mod.padding(horizontal = 12.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { items(pinned, key = { it.pkg }) { icon(it) } }
            buttons()
            ClockBlock(p, vertical = false)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun DockIcon(a: AppInfo, role: Boolean, iconDp: Int, labels: Boolean, vm: LauncherHub, accent: Color) {
    val ctx = LocalContext.current
    Column(
        Modifier.clip(RoundedCornerShape(14.dp))
            .androidxCombined(onClick = { vm.recordUse(a.pkg); Embedder.launch(ctx, a.pkg, vm.boundsFor(a.pkg)) }, onLong = { vm.menuApp = a })
            .then(if (role) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(a.icon, a.label, Modifier.size(iconDp.dp))
        if (labels) Txt(a.label, 12, TextLo, modifier = Modifier.width(iconDp.dp + 8.dp), align = TextAlign.Center)
    }
}

@Composable
private fun ClockBlock(p: Prefs, vertical: Boolean) {
    val ctx = LocalContext.current
    val time by remember(p.clock24h) { CarTools.clock(ctx, p.clock24h) }.collectAsStateWithLifecycle("")
    // time ticks every minute, which is also when the date can change
    val date = remember(time, p.lang) { SimpleDateFormat("EEE d MMM", Locale.forLanguageTag(p.lang.tag)).format(Date()) }
    val icons: @Composable () -> Unit = {
        val l by remember { CarTools.links(ctx) }.collectAsStateWithLifecycle(Links(false, false, false))
        val off = TextLo.copy(alpha = 0.5f)
        if (p.showWifi) Glyph(G.WIFI, if (l.wifi) Color(0xFF3DDC84) else off, 20.dp)
        if (p.showWifi && l.hotspot) Glyph(G.HOTSPOT, Color(0xFFFFB300), 20.dp)
        if (p.showBt) Glyph(G.BT, if (l.bluetooth) Color(0xFF2B7FFF) else off, 20.dp)
    }
    val clock: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(time, 26, bold = true)
            if (p.showDate) Txt(date, 13, TextLo)
        }
    }
    if (vertical) {
        Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            clock()
            if (p.showWifi || p.showBt) Row(Modifier.padding(top = 4.dp), Arrangement.spacedBy(6.dp)) { icons() }
        }
    } else {
        Row(Modifier.padding(horizontal = 8.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
            if (p.showWifi || p.showBt) Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) { icons() }
            clock()
        }
    }
}
