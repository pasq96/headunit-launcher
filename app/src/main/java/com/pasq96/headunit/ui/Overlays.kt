package com.pasq96.headunit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasq96.headunit.R
import com.pasq96.headunit.data.ACCENTS
import com.pasq96.headunit.data.accentArgb
import com.pasq96.headunit.data.AppActions
import com.pasq96.headunit.data.AppInfo
import androidx.compose.ui.draw.alpha
import com.pasq96.headunit.data.orderedApps
import com.pasq96.headunit.data.AppSort
import com.pasq96.headunit.data.DimMode
import com.pasq96.headunit.data.DockPos
import com.pasq96.headunit.data.K
import com.pasq96.headunit.data.Lang
import com.pasq96.headunit.data.LedSource
import com.pasq96.headunit.data.MediaMode
import com.pasq96.headunit.data.Panels
import com.pasq96.headunit.data.Prefs
import com.pasq96.headunit.data.ThemeMode
import com.pasq96.headunit.embed.Embedder
import com.pasq96.headunit.system.InternalAdb
import com.pasq96.headunit.system.PermissionGranter
import com.pasq96.headunit.tools.CarTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Scrim = Color(0xCC000000)
private val Danger: Color @Composable @ReadOnlyComposable get() = Warn

// ---- system shortcuts (also the safe-mode exit) ----

private fun Context.go(i: Intent): Boolean = runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
internal fun openNotificationAccess(ctx: Context) { ctx.go(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
private fun openAndroidSettings(ctx: Context) { ctx.go(Intent(Settings.ACTION_SETTINGS)) }
private fun openDefaultApps(ctx: Context) { if (!ctx.go(Intent(Settings.ACTION_HOME_SETTINGS))) openAndroidSettings(ctx) }
private fun openOverlayAccess(ctx: Context) { ctx.go(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
private fun openWriteSettings(ctx: Context) { ctx.go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
private fun openUsageAccess(ctx: Context) { ctx.go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

@Composable
private fun EmergencyBar(vm: LauncherHub, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(modifier, Arrangement.spacedBy(8.dp)) {
        EmergencyButton(str(R.string.emg_android_settings), G.GEAR, Modifier.weight(1f)) { vm.closeOverlays(); openAndroidSettings(ctx) }
        EmergencyButton(str(R.string.emg_default_apps), G.GRID, Modifier.weight(1f)) { vm.closeOverlays(); openDefaultApps(ctx) }
    }
}

@Composable
private fun EmergencyButton(text: String, icon: G, modifier: Modifier, onClick: () -> Unit) =
    Row(modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).clickable(onClick = onClick).padding(horizontal = 10.dp), Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
        Glyph(icon, TextLo, 22.dp); Txt(text, 14, TextHi)
    }

// ---- layer ----

/** Drawer / quick tools / settings / app menu / app picker. Inline in the activity, or in an overlay window while apps are embedded. */
@Composable
fun OverlayLayer(vm: LauncherHub) = HeadUnitTheme(vm) {
    val p by vm.prefs.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val accent = Color(p.accentArgb)
    if (vm.drawer) Drawer(vm, p, apps, accent)
    if (vm.tools) ToolsPanel(vm, p, accent)
    if (vm.radioScreen) RadioScreen(vm, accent)
    if (vm.settings) SettingsPanel(vm, p, apps, accent)
    vm.menuApp?.let { AppMenu(vm, p, it, accent) }
    vm.picker?.let { AppPicker(vm, p, apps, it, accent) }
    if (vm.colorPicker) ColorPicker(vm, p, accent)
    if (vm.manager) DrawerManager(vm, p, apps, accent)
}

@Composable
private fun Dim(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.fillMaxSize().background(Scrim).pointerInput(Unit) { detectTapGestures { onDismiss() } }) { content() }

// ---- app drawer: vertical or horizontal grid, fixed span or auto ----

private sealed interface DrawerCell {
    val key: String
    data object Tools : DrawerCell { override val key = "#tools" }
    data object Dev : DrawerCell { override val key = "#dev" }
    class App(val info: AppInfo) : DrawerCell { override val key get() = info.pkg }
}

@Composable
private fun Drawer(vm: LauncherHub, p: Prefs, apps: List<AppInfo>, accent: Color) {
    val ctx = LocalContext.current
    val cell: @Composable (DrawerCell, Dp) -> Unit = { c, size ->
        val label = when (c) { DrawerCell.Tools -> str(R.string.tools_title); DrawerCell.Dev -> str(R.string.drawer_dev); is DrawerCell.App -> c.info.label }
        Column(
            Modifier.padding(4.dp).clip(RoundedCornerShape(14.dp))
                .androidxCombined(
                    onClick = {
                        vm.drawer = false
                        when (c) {
                            DrawerCell.Tools -> vm.tools = true
                            DrawerCell.Dev -> { vm.closeOverlays(); AppActions.developerOptions(ctx) }
                            is DrawerCell.App -> { vm.recordUse(c.info.pkg); Embedder.launch(ctx, c.info.pkg, vm.boundsFor(c.info.pkg)) }
                        }
                    },
                    onLong = { if (c is DrawerCell.App) vm.menuApp = c.info },
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (c is DrawerCell.App) Image(c.info.icon, c.info.label, Modifier.size(size))
            else Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Surface2), Alignment.Center) {
                Glyph(if (c == DrawerCell.Tools) G.GAUGE else G.DEV, accent, size * 0.6f)
            }
            if (p.drawerLabels) Txt(label, 14, maxLines = 2, align = TextAlign.Center, modifier = Modifier.width(size + 24.dp).padding(top = 4.dp))
        }
    }
    // system shortcuts come first: Car Tools (no dock button anymore), then developer options when enabled
    val shown = remember(apps, p.hidden, p.sort, p.customOrder, p.usage) { orderedApps(apps, p) }
    val entries: List<DrawerCell> = remember(shown, p.drawerDev) { listOf(DrawerCell.Tools) + (if (p.drawerDev) listOf(DrawerCell.Dev) else emptyList()) + shown.map { DrawerCell.App(it) } }
    val cells = if (p.drawerSpan == 0) GridCells.Adaptive(p.drawerIconDp.dp + 48.dp) else GridCells.Fixed(p.drawerSpan)
    val pad = PaddingValues(8.dp)
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Txt(str(R.string.drawer_title), 24, TextHi, bold = true)
            GlyphButton(G.CLOSE, TextHi, { vm.drawer = false })
        }
        val fixedSize = p.drawerIconDp.dp
        if (p.drawerFill && !p.drawerHorizontal) BoxWithConstraints(Modifier.fillMaxSize()) {
            // pick the column count that gives the biggest icon while every cell still fits without scrolling
            val n = entries.size; val pad2 = 24f; val labelH = if (p.drawerLabels) 44f else 0f
            val candidates = if (p.drawerSpan > 0) p.drawerSpan..p.drawerSpan else 1..n
            var cols = candidates.first; var icon = 0f
            for (c in candidates) {
                val r = (n + c - 1) / c
                val s = minOf(maxWidth.value / c - pad2, maxHeight.value / r - pad2 - labelH)
                if (s > icon) { icon = s; cols = c }
            }
            if (icon >= 56f) {
                val rows = (n + cols - 1) / cols
                val cellH = maxHeight / rows
                LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize(), userScrollEnabled = false) {
                    items(entries, key = { it.key }) { Box(Modifier.fillMaxWidth().height(cellH), Alignment.Center) { cell(it, minOf(icon, 128f).dp) } }
                }
            } else if (p.drawerSpan > 0) {
                // too many apps for one screen: scroll, with the icon following the column width
                val ic = (maxWidth.value / p.drawerSpan * 0.42f).coerceIn(p.drawerIconDp.toFloat(), 104f).dp
                LazyVerticalGrid(GridCells.Fixed(p.drawerSpan), Modifier.fillMaxSize(), contentPadding = pad) { items(entries, key = { it.key }) { cell(it, ic) } }
            } else LazyVerticalGrid(cells, Modifier.fillMaxSize(), contentPadding = pad) { items(entries, key = { it.key }) { cell(it, fixedSize) } }
        }
        else if (p.drawerHorizontal) LazyHorizontalGrid(cells, Modifier.fillMaxSize(), contentPadding = pad) { items(entries, key = { it.key }) { cell(it, fixedSize) } }
        else LazyVerticalGrid(cells, Modifier.fillMaxSize(), contentPadding = pad) { items(entries, key = { it.key }) { cell(it, fixedSize) } }
    }
}

private enum class Destructive { CACHE, STOP, DATA, UNINSTALL }

/** Long-press menu: safe actions first, then destructive ones in red, mildest to harshest (cache, stop, data, uninstall). */
@Composable
private fun AppMenu(vm: LauncherHub, p: Prefs, a: AppInfo, accent: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val removable = remember(a.pkg) { AppActions.isRemovable(ctx, a.pkgName) }
    var confirm by remember { mutableStateOf<Destructive?>(null) }
    val done = str(R.string.msg_done)
    val viaSettings = str(R.string.msg_use_settings)
    val close = { vm.menuApp = null }

    fun run(d: Destructive) {
        if (d == Destructive.UNINSTALL) { vm.closeOverlays(); AppActions.uninstall(ctx, a.pkgName); return }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                when (d) {
                    Destructive.CACHE -> AppActions.clearCache(ctx, a.pkgName)
                    Destructive.STOP -> AppActions.forceStop(ctx, a.pkgName)
                    else -> AppActions.clearData(ctx, a.pkgName)
                }
            }
            Toast.makeText(ctx, if (r == AppActions.Result.DONE) done else viaSettings, Toast.LENGTH_SHORT).show()
            if (r == AppActions.Result.SETTINGS) vm.closeOverlays() else close()
        }
    }

    Dim(close) {
        Column(
            Modifier.align(Alignment.Center).width(440.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(Surface)
                .pointerInput(Unit) { detectTapGestures { } }.verticalScroll(rememberScrollState()).padding(20.dp),
            Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(a.icon, null, Modifier.size(48.dp)); Txt(a.label, 22, bold = true)
            }
            MenuBtn(str(R.string.menu_info), TextHi, G.INFO) { vm.closeOverlays(); AppActions.info(ctx, a.pkgName) }
            MenuBtn(str(if (a.pkg in p.pinned) R.string.menu_unpin else R.string.menu_pin), accent, G.PIN) { vm.togglePin(a.pkg); close() }
            MenuBtn(str(R.string.menu_hide), TextHi, G.EYE_OFF) { vm.toggleHidden(a.pkg); close() }
            MenuBtn(str(R.string.menu_move), TextHi, G.SWAP) { vm.enableCustomOrder(); vm.manager = true; close() }
            // role shortcuts only for apps that actually declare navigation / music
            if (a.nav) MenuBtn(str(R.string.menu_set_nav), accent, if (a.pkg == p.navPkg) G.CHECK else G.NAV) { vm.set(K.nav, a.pkg); close() }
            if (a.music) MenuBtn(str(R.string.menu_set_player), accent, if (a.pkg == p.mediaPkg) G.CHECK else G.MUSIC) { vm.set(K.media, a.pkg); close() }
            Box(Modifier.fillMaxWidth().height(1.dp).background(TextLo.copy(alpha = 0.3f)))
            val c = confirm
            if (c == null) {
                MenuBtn(str(R.string.menu_clear_cache), Danger, G.REFRESH) { run(Destructive.CACHE) }
                MenuBtn(str(R.string.menu_force_stop), Danger, G.STOP) { confirm = Destructive.STOP }
                MenuBtn(str(R.string.menu_clear_data), Danger, G.DATA) { confirm = Destructive.DATA }
                if (removable) MenuBtn(str(R.string.menu_uninstall), Danger, G.TRASH) { confirm = Destructive.UNINSTALL }
                MenuBtn(str(R.string.cancel), TextLo, G.CLOSE) { close() }
            } else {
                Txt(
                    str(when (c) { Destructive.STOP -> R.string.confirm_force_stop; Destructive.DATA -> R.string.confirm_clear_data; else -> R.string.confirm_uninstall }, a.label),
                    17, maxLines = 3,
                )
                MenuBtn(str(R.string.confirm_yes), Danger, G.WARN) { run(c) }
                MenuBtn(str(R.string.cancel), TextLo, G.CLOSE) { confirm = null }
            }
        }
    }
}

@Composable
private fun MenuBtn(text: String, tint: Color, icon: G, onClick: () -> Unit) =
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).clickable(onClick = onClick).padding(horizontal = 18.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) { Glyph(icon, tint, 28.dp); Txt(text, 18, tint, bold = true) }

// ---- app picker: navigators / players, prefiltered by what the apps declare ----

@Composable
private fun AppPicker(vm: LauncherHub, p: Prefs, apps: List<AppInfo>, kind: PickerKind, accent: Color) {
    val nav = kind == PickerKind.NAV
    var all by remember { mutableStateOf(false) }
    val list = if (all) apps else apps.filter { if (nav) it.nav else it.music }
    val current = if (nav) p.navPkg else p.mediaPkg
    val close = { vm.picker = null }
    Dim(close) {
        Column(
            Modifier.align(Alignment.Center).width(560.dp).fillMaxHeight(0.86f).clip(RoundedCornerShape(20.dp)).background(Surface)
                .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Txt(str(if (nav) R.string.picker_nav_title else R.string.picker_player_title), 22, bold = true)
                GlyphButton(G.CLOSE, TextHi, close)
            }
            if (!all) Txt(str(if (nav) R.string.picker_filter_nav else R.string.picker_filter_player), 14, TextLo, maxLines = 2)
            ToggleRow(str(R.string.picker_show_all), all, accent, icon = G.GRID) { all = it }
            if (list.isEmpty()) Txt(str(R.string.picker_empty), 16, TextLo, modifier = Modifier.padding(vertical = 16.dp), maxLines = 3)
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.pkg }) { a ->
                    val sel = a.pkg == current
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (sel) accent.copy(alpha = 0.2f) else Surface2).clickable { vm.pick(a.pkg) }.padding(12.dp),
                        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
                    ) {
                        Image(a.icon, null, Modifier.size(48.dp))
                        Box(Modifier.weight(1f)) { Txt(a.label, 19, bold = true) }
                        if (sel) Txt(str(R.string.picker_current), 14, accent, bold = true)
                    }
                }
            }
        }
    }
}

// ---- drawer manager: hide apps, reorder in custom mode ----

@Composable
private fun DrawerManager(vm: LauncherHub, p: Prefs, apps: List<AppInfo>, accent: Color) {
    val list = remember(apps, p.hidden, p.sort, p.customOrder, p.usage) { orderedApps(apps, p, includeHidden = true) }
    val close = { vm.manager = false }
    Dim(close) {
        Column(
            Modifier.align(Alignment.Center).width(560.dp).fillMaxHeight(0.9f).clip(RoundedCornerShape(20.dp)).background(Surface)
                .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Txt(str(R.string.manage_title), 22, bold = true); GlyphButton(G.CLOSE, TextHi, close)
            }
            Txt(str(if (p.sort == AppSort.CUSTOM) R.string.manage_hint_custom else R.string.manage_hint), 14, TextLo, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.pkg }) { a ->
                    val hidden = a.pkg in p.hidden
                    Row(Modifier.fillMaxWidth().height(60.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                        Image(a.icon, null, Modifier.size(40.dp).alpha(if (hidden) 0.4f else 1f))
                        Box(Modifier.weight(1f)) { Txt(a.label, 17, if (hidden) TextLo else TextHi) }
                        if (p.sort == AppSort.CUSTOM) {
                            GlyphButton(G.UP, TextHi, { vm.moveInOrder(a.pkg, -1) }, size = 48.dp, glyph = 28.dp)
                            GlyphButton(G.DOWN, TextHi, { vm.moveInOrder(a.pkg, 1) }, size = 48.dp, glyph = 28.dp)
                        }
                        GlyphButton(if (hidden) G.EYE_OFF else G.EYE, if (hidden) TextLo else accent, { vm.toggleHidden(a.pkg) }, size = 48.dp, glyph = 28.dp)
                    }
                }
            }
        }
    }
}

// ---- custom accent colour: saturation/value square + hue bar ----

@Composable
private fun ColorPicker(vm: LauncherHub, p: Prefs, accent: Color) {
    val init = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(p.accentArgb, it) } }
    var h by remember { mutableFloatStateOf(init[0]) }
    var sat by remember { mutableFloatStateOf(init[1]) }
    var v by remember { mutableFloatStateOf(init[2].coerceAtLeast(MIN_V)) }
    var sv by remember { mutableStateOf(IntSize(1, 1)) }
    var hb by remember { mutableIntStateOf(1) }
    val color = Color.hsv(h, sat, v)
    val close = { vm.colorPicker = false }
    val setSv = { o: Offset -> sat = (o.x / sv.width).coerceIn(0f, 1f); v = (1f - o.y / sv.height).coerceIn(MIN_V, 1f) }
    val setHue = { x: Float -> h = (x / hb * 360f).coerceIn(0f, 359.9f) }
    val ring = TextHi
    Dim(close) {
        Column(
            Modifier.align(Alignment.Center).width(460.dp).clip(RoundedCornerShape(20.dp)).background(Surface)
                .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp),
            Arrangement.spacedBy(14.dp),
        ) {
            Labeled(str(R.string.color_pick_title), G.THEME, TextHi, 22, true, 30.dp)
            Canvas(
                Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).onSizeChanged { sv = it }
                    .pointerInput(Unit) { detectTapGestures { setSv(it) } }
                    .pointerInput(Unit) { detectDragGestures { c, _ -> c.consume(); setSv(c.position) } },
            ) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(h, 1f, 1f))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val c = Offset(sat * size.width, (1f - v) * size.height)
                drawCircle(Color.Black, 17f, c, style = Stroke(5f)); drawCircle(Color.White, 14f, c, style = Stroke(4f))
            }
            Canvas(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).onSizeChanged { hb = it.width.coerceAtLeast(1) }
                    .pointerInput(Unit) { detectTapGestures { setHue(it.x) } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> c.consume(); setHue(c.position.x) } },
            ) {
                drawRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
                val x = h / 360f * size.width
                drawCircle(ring, 17f, Offset(x, size.height / 2), style = Stroke(5f))
            }
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(color))
                Txt("#%06X".format(color.toArgb() and 0xFFFFFF), 20, TextHi, bold = true)
            }
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).clickable(onClick = close), Alignment.Center) { Txt(str(R.string.cancel), 18, TextLo, bold = true) }
                Box(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp)).background(color)
                        .clickable { vm.set(K.accentCustom, color.toArgb()); vm.set(K.accent, ACCENTS.size); close() },
                    Alignment.Center,
                ) { Txt(str(R.string.color_apply), 18, onAccent(color), bold = true) }
            }
        }
    }
}

// minimum brightness keeps text on accent fills and the accent itself readable on the dark theme
private const val MIN_V = 0.35f

// ---- quick tools ----

@Composable
private fun ToolsPanel(vm: LauncherHub, p: Prefs, accent: Color) {
    val ctx = LocalContext.current
    val gps = remember(vm.gpsTick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
    val st by remember(p.toolsIntervalSec) { CarTools.stats(ctx, p.toolsIntervalSec * 1000L) }.collectAsStateWithLifecycle(null)
    val speed by remember(gps) { if (gps) CarTools.speedKmh(ctx) else flowOf(null) }.collectAsStateWithLifecycle(null)
    var bright by remember { mutableFloatStateOf(CarTools.brightness(ctx) / 255f) }

    Dim({ vm.tools = false }) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(480.dp).background(Surface).pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Txt(str(R.string.tools_title), 24, bold = true); GlyphButton(G.CLOSE, TextHi, { vm.tools = false })
            }
            Section(str(R.string.sec_system), accent, G.GAUGE) {
                val temp = st?.cpuC
                Stat(str(R.string.stat_cpu_temp), temp?.let { "${it.roundToInt()} °C" } ?: str(R.string.stat_na), if (temp != null && temp >= p.tempWarnC) Warn else TextHi, icon = G.GAUGE)
                Stat(str(R.string.stat_ram_free), st?.let { "${it.ramFreeMb} / ${it.ramTotalMb} MB" } ?: "…", icon = G.DATA)
                Stat(str(R.string.stat_battery), st?.battV?.let { if (it < 6f) "%.1f V (int.)".format(it) else "%.1f V".format(it) } ?: str(R.string.stat_na), icon = G.POWER)
                if (gps) Stat(str(R.string.stat_gps_speed), speed?.let { "${it.roundToInt()} km/h" } ?: str(R.string.stat_no_fix), icon = G.NAV)
                else Chip(str(R.string.gps_enable), false, accent, icon = G.NAV) { vm.requestLocation() }
            }
            Section(str(R.string.sec_screen), accent, G.SUN) {
                if (!CarTools.canWriteSettings(ctx)) Chip(str(R.string.write_grant), false, accent, icon = G.SUN) { vm.closeOverlays(); openWriteSettings(ctx) }
                else SimpleSlider(bright, accent, { bright = it }, onDone = { CarTools.setBrightness(ctx, (bright * 255).roundToInt()) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    FlowRow(modifier, Arrangement.spacedBy(8.dp), Arrangement.spacedBy(8.dp)) { content() }

@Composable
private fun ThemeChips(vm: LauncherHub, p: Prefs, accent: Color) = ChipRow {
    Chip(str(R.string.theme_dark), p.theme == ThemeMode.DARK, accent) { vm.setEnum(K.theme, ThemeMode.DARK) }
    Chip(str(R.string.theme_light), p.theme == ThemeMode.LIGHT, accent) { vm.setEnum(K.theme, ThemeMode.LIGHT) }
    Chip(str(R.string.theme_auto), p.theme == ThemeMode.AUTO, accent) { vm.setEnum(K.theme, ThemeMode.AUTO) }
}

@Composable
private fun DimChips(vm: LauncherHub, p: Prefs, accent: Color) = ChipRow {
    Chip(str(R.string.dim_off), p.dimMode == DimMode.OFF, accent) { vm.setEnum(K.dim, DimMode.OFF) }
    Chip(str(R.string.dim_on), p.dimMode == DimMode.ON, accent) { vm.setEnum(K.dim, DimMode.ON) }
    Chip(str(R.string.dim_auto), p.dimMode == DimMode.AUTO, accent) { vm.setEnum(K.dim, DimMode.AUTO) }
}

@Composable
private fun Stat(label: String, value: String, color: Color = TextHi, icon: G? = null) =
    Row(Modifier.fillMaxWidth().height(44.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) { Labeled(label, icon, TextLo, 15, modifier = Modifier.weight(1f)); Txt(value, 16, color, bold = true) }

@Composable
private fun SettingsPanel(vm: LauncherHub, p: Prefs, apps: List<AppInfo>, accent: Color) {
    val ctx = LocalContext.current
    // Permission/status rows are re-read every time the user comes back from a system settings screen.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val reading by vm.illumination.state.collectAsStateWithLifecycle()
    val appLabel = { pkg: String? -> pkg?.let { id -> apps.firstOrNull { it.pkg == id }?.label ?: id } }
    val notSet = str(R.string.not_set)
    val auto = str(R.string.auto)

    Dim({ vm.settings = false }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.92f).fillMaxHeight(0.94f).clip(RoundedCornerShape(20.dp)).background(Surface)
                .pointerInput(Unit) { detectTapGestures { } }.verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                Txt(str(R.string.settings_title), 26, bold = true, modifier = Modifier.weight(1f))
                EmergencyBar(vm, Modifier.weight(2.4f))
                GlyphButton(G.CLOSE, TextHi, { vm.settings = false })
            }

            val blocks: List<@Composable () -> Unit> = listOf(
                {
            Section(str(R.string.sec_look), accent, G.THEME) {
                SubLabel(str(R.string.theme_label)); ThemeChips(vm, p, accent)
                SubLabel(str(R.string.lang_label))
                ChipRow {
                    Chip(str(R.string.lang_it), p.lang == Lang.IT, accent) { vm.setEnum(K.lang, Lang.IT) }
                    Chip(str(R.string.lang_en), p.lang == Lang.EN, accent) { vm.setEnum(K.lang, Lang.EN) }
                }
                SubLabel(str(R.string.accent_label))
                ChipRow {
                    ACCENTS.forEachIndexed { i, c -> Swatch(Color(c), p.accent == i) { vm.set(K.accent, i) } }
                    RainbowSwatch(p.accent == ACCENTS.size, Color(p.accentCustom)) { vm.colorPicker = true }
                }
            }
                },
                {
            Section(str(R.string.sec_led), accent, G.LED) {
                SubLabel(str(R.string.led_source))
                ChipRow {
                    Chip(str(R.string.src_all), p.ledSource == LedSource.AUTO, accent) { vm.setEnum(K.ledSource, LedSource.AUTO) }
                    Chip(str(R.string.src_broadcast), p.ledSource == LedSource.BROADCAST, accent) { vm.setEnum(K.ledSource, LedSource.BROADCAST) }
                    Chip(str(R.string.src_setting), p.ledSource == LedSource.SETTING, accent) { vm.setEnum(K.ledSource, LedSource.SETTING) }
                    Chip(str(R.string.src_sysfs), p.ledSource == LedSource.SYSFS, accent) { vm.setEnum(K.ledSource, LedSource.SYSFS) }
                }
                ToggleRow(str(R.string.led_invert), p.ledInvert, accent, hint = str(R.string.led_invert_hint)) { vm.set(K.ledInvert, it) }
                Stat(
                    str(R.string.led_state),
                    str(when (reading.on) { true -> R.string.led_on; false -> R.string.led_off; null -> R.string.led_unknown }) + (reading.source?.let { " · $it" } ?: ""),
                )
                Txt(str(R.string.led_hint), 13, TextLo, maxLines = 2)
                SubLabel(str(R.string.dim_mode))
                DimChips(vm, p, accent)
                Txt(str(R.string.dim_strength, (p.nightAlpha * 100).roundToInt()), 15, TextLo)
                var alpha by remember(p.nightAlpha) { mutableFloatStateOf((p.nightAlpha - 0.1f) / 0.75f) }
                SimpleSlider(alpha, accent, { alpha = it }, onDone = { vm.set(K.nightAlpha, 0.1f + alpha * 0.75f) })
            }
                },
                {
            Section(str(R.string.sec_layout), accent, G.SPLIT) {
                SubLabel(str(R.string.sec_panels))
                ChipRow {
                    Chip(str(R.string.panels_split), p.panels == Panels.BOTH, accent) { vm.setEnum(K.panels, Panels.BOTH) }
                    Chip(str(R.string.panels_nav_only), p.panels == Panels.NAV, accent) { vm.setEnum(K.panels, Panels.NAV) }
                    Chip(str(R.string.panels_player_only), p.panels == Panels.MEDIA, accent) { vm.setEnum(K.panels, Panels.MEDIA) }
                }
                SubLabel(str(R.string.dock_pos))
                ChipRow {
                    Chip(str(R.string.dock_bottom), p.dock == DockPos.BOTTOM, accent) { vm.setEnum(K.dock, DockPos.BOTTOM) }
                    Chip(str(R.string.dock_left), p.dock == DockPos.LEFT, accent) { vm.setEnum(K.dock, DockPos.LEFT) }
                    Chip(str(R.string.dock_right), p.dock == DockPos.RIGHT, accent) { vm.setEnum(K.dock, DockPos.RIGHT) }
                }
                StepRow(str(R.string.dock_thickness), "${p.dockDp} dp", accent, { vm.set(K.dockDp, p.dockDp - 8) }, { vm.set(K.dockDp, p.dockDp + 8) })
                StepRow(str(R.string.dock_icon_size), "${p.iconDp} dp", accent, { vm.set(K.iconDp, p.iconDp - 4) }, { vm.set(K.iconDp, p.iconDp + 4) })
                ToggleRow(str(R.string.dock_labels), p.dockLabels, accent) { vm.set(K.labels, it) }
                StepRow(str(R.string.divider_width), "${p.handleDp} dp", accent, { vm.set(K.handle, p.handleDp - 4) }, { vm.set(K.handle, p.handleDp + 4) })
                StepRow(str(R.string.split_ratio), "${(p.ratio * 100).roundToInt()}/${100 - (p.ratio * 100).roundToInt()}", accent,
                    { vm.set(K.ratio, p.ratio - 0.05f) }, { vm.set(K.ratio, p.ratio + 0.05f) })
                ToggleRow(str(R.string.swap_panels), p.swapPanels, accent, hint = str(R.string.swap_panels_hint)) { vm.set(K.swap, it) }
            }
                },
                {
            Section(str(R.string.sec_drawer), accent, G.GRID) {
                ChipRow {
                    Chip(str(R.string.drawer_vertical), !p.drawerHorizontal, accent) { vm.set(K.drawerH, false) }
                    Chip(str(R.string.drawer_horizontal), p.drawerHorizontal, accent) { vm.set(K.drawerH, true) }
                }
                StepRow(str(if (p.drawerHorizontal) R.string.drawer_rows else R.string.drawer_cols), if (p.drawerSpan == 0) auto else "${p.drawerSpan}", accent,
                    { vm.set(K.drawerSpan, p.drawerSpan - 1) }, { vm.set(K.drawerSpan, p.drawerSpan + 1) })
                StepRow(str(R.string.drawer_icon_size), "${p.drawerIconDp} dp", accent, { vm.set(K.drawerIcon, p.drawerIconDp - 8) }, { vm.set(K.drawerIcon, p.drawerIconDp + 8) })
                SubLabel(str(R.string.drawer_sort))
                ChipRow {
                    Chip(str(R.string.sort_alpha), p.sort == AppSort.ALPHA, accent) { vm.setEnum(K.sort, AppSort.ALPHA) }
                    Chip(str(R.string.sort_usage), p.sort == AppSort.USAGE, accent) { vm.setEnum(K.sort, AppSort.USAGE) }
                    Chip(str(R.string.sort_custom), p.sort == AppSort.CUSTOM, accent) { vm.enableCustomOrder() }
                }
                ChooseRow(str(R.string.drawer_manage), str(R.string.drawer_hidden_count, p.hidden.size), accent) { vm.manager = true }
                ToggleRow(str(R.string.drawer_dev_show), p.drawerDev, accent) { vm.set(K.drawerDev, it) }
                ToggleRow(str(R.string.drawer_labels), p.drawerLabels, accent) { vm.set(K.drawerLabels, it) }
                ToggleRow(str(R.string.drawer_fill), p.drawerFill, accent, hint = str(R.string.drawer_fill_hint)) { vm.set(K.drawerFill, it) }
            }
                },
                {
            Section(str(R.string.sec_apps), accent, G.NAV) {
                ChooseRow(str(R.string.navigator), appLabel(p.navPkg) ?: notSet, accent) { vm.picker = PickerKind.NAV }
                ChooseRow(str(R.string.player), appLabel(p.mediaPkg) ?: notSet, accent) { vm.picker = PickerKind.PLAYER }
                Txt(str(R.string.apps_hint), 14, TextLo)
                ChipRow(Modifier.padding(top = 8.dp)) {
                    Chip(str(R.string.mode_widget), p.mediaMode == MediaMode.WIDGET, accent) { vm.setEnum(K.mode, MediaMode.WIDGET) }
                    Chip(str(R.string.mode_app_embedded), p.mediaMode == MediaMode.EMBED, accent) { vm.setEnum(K.mode, MediaMode.EMBED) }
                    Chip(str(R.string.mode_radio_native), p.mediaMode == MediaMode.RADIO, accent) { vm.setEnum(K.mode, MediaMode.RADIO) }
                }
            }
                },
                {
            Section(str(R.string.sec_radio), accent, G.RADIO) {
                if (vm.radio.canTune) {
                ToggleRow(str(R.string.swc_seek), p.swcSeek, accent) { vm.set(K.swcSeek, it) }
                SubLabel(str(R.string.fm_step))
                ChipRow {
                    listOf(50, 100, 200).forEach { k -> Chip("$k kHz", p.fmStepKhz == k, accent) { vm.set(K.fmStep, k) } }
                }
                SubLabel(str(R.string.am_step))
                ChipRow {
                    listOf(9, 10).forEach { k -> Chip("$k kHz", p.amStepKhz == k, accent) { vm.set(K.amStep, k) } }
                }
                ChipRow(Modifier.padding(top = 8.dp)) {
                    Chip(str(R.string.presets_import), false, accent, icon = G.DATA) { vm.importStockPresets() }
                    Chip(str(R.string.radio_open_stock), false, accent, icon = G.RADIO) { vm.closeOverlays(); vm.radio.openStockApp() }
                }
                } else ChipRow {
                    Chip(str(R.string.radio_open_stock), false, accent, icon = G.RADIO) { vm.closeOverlays(); vm.radio.openStockApp() }
                }
            }
                },
                {
            Section(str(R.string.sec_dock), accent, G.DOCK) {
                ToggleRow(str(R.string.dock_overlay), p.dockOverlay, accent, hint = str(R.string.dock_overlay_hint)) { vm.set(K.dockOverlay, it) }
                ToggleRow(str(R.string.previews), p.previews, accent, hint = str(R.string.previews_hint)) { vm.set(K.previews, it) }
                ToggleRow(str(R.string.embed_windows), p.embedWindows, accent, hint = str(R.string.embed_windows_hint)) { vm.set(K.embedWindows, it) }
                ToggleRow(str(R.string.clock_24h), p.clock24h, accent) { vm.set(K.clock24, it) }
                ToggleRow(str(R.string.show_date), p.showDate, accent) { vm.set(K.date, it) }
                ToggleRow(str(R.string.show_wifi), p.showWifi, accent) { vm.set(K.wifi, it) }
                ToggleRow(str(R.string.show_bt), p.showBt, accent) { vm.set(K.bt, it) }
            }
                },
                {
            Section(str(R.string.sec_cartools), accent, G.GAUGE) {
                StepRow(str(R.string.sensor_interval), "${p.toolsIntervalSec} s", accent, { vm.set(K.interval, p.toolsIntervalSec - 1) }, { vm.set(K.interval, p.toolsIntervalSec + 1) })
                StepRow(str(R.string.temp_warn), "${p.tempWarnC} °C", accent, { vm.set(K.tempWarn, p.tempWarnC - 5) }, { vm.set(K.tempWarn, p.tempWarnC + 5) })
            }
                },
                {
            Section(str(R.string.sec_boot), accent, G.POWER) {
                ToggleRow(str(R.string.auto_start), p.autoStart, accent) { vm.set(K.auto, it) }
                StepRow(str(R.string.auto_start_delay), "${p.autoStartDelaySec} s", accent, { vm.set(K.autoDelay, p.autoStartDelaySec - 1) }, { vm.set(K.autoDelay, p.autoStartDelaySec + 1) })
                ToggleRow(str(R.string.auto_play), p.autoPlay, accent) { vm.set(K.play, it) }
                StepRow(str(R.string.auto_play_delay), "${p.autoPlayDelaySec} s", accent, { vm.set(K.playDelay, p.autoPlayDelaySec - 1) }, { vm.set(K.playDelay, p.autoPlayDelaySec + 1) })
                ToggleRow(str(R.string.resume_on_wake), p.resumeOnWake, accent) { vm.set(K.resume, it) }
                StepRow(str(R.string.wake_delay), "${p.wakeDelaySec} s", accent, { vm.set(K.wakeDelay, p.wakeDelaySec - 1) }, { vm.set(K.wakeDelay, p.wakeDelaySec + 1) })
            }
                },
                {
            Section(str(R.string.sec_autogrant), accent, G.SHIELD) {
                var busy by remember { mutableStateOf(false) }
                var report by remember { mutableStateOf<PermissionGranter.Report?>(null) }
                val scope = rememberCoroutineScope()
                Txt(str(R.string.autogrant_hint), 14, TextLo, maxLines = 4, modifier = Modifier.padding(bottom = 8.dp))
                Chip(str(if (busy) R.string.autogrant_busy else R.string.autogrant_run), false, accent, icon = G.SHIELD) {
                    if (!busy) {
                        busy = true
                        scope.launch { report = withContext(Dispatchers.IO) { PermissionGranter.run(ctx) }; busy = false; tick++ }
                    }
                }
                report?.let { r ->
                    r.fail?.let { Txt(str(if (it == InternalAdb.Fail.AUTH) R.string.autogrant_err_auth else R.string.autogrant_err_connect), 15, Warn, maxLines = 5, modifier = Modifier.padding(top = 8.dp)) }
                    r.steps.forEach { (label, ok) -> Stat(str(label), str(if (ok) R.string.perm_ok else R.string.autogrant_fail), if (ok) accent else Warn, if (ok) G.CHECK else G.WARN) }
                }
            }
                },
                {
            Section(str(R.string.sec_perms), accent, G.SHIELD) {
                val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
                val write = remember(tick) { CarTools.canWriteSettings(ctx) }
                val listener = remember(tick) { vm.media.hasAccess() }
                val freeform = remember(tick) { Embedder.freeformEnabled(ctx) }
                PermRow(str(R.string.perm_overlay), overlay, accent) { vm.closeOverlays(); openOverlayAccess(ctx) }
                PermRow(str(R.string.perm_write), write, accent) { vm.closeOverlays(); openWriteSettings(ctx) }
                PermRow(str(R.string.perm_listener), listener, accent) { vm.closeOverlays(); openNotificationAccess(ctx) }
                PermRow(str(R.string.perm_usage), null, accent) { vm.closeOverlays(); openUsageAccess(ctx) }
                Stat(str(R.string.freeform_windows), str(if (freeform) R.string.freeform_on else R.string.freeform_off), if (freeform) accent else Warn)
            }
                },
            )
            if (LocalConfiguration.current.screenWidthDp >= 800) {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f)) { blocks.take(4).forEach { it() } }
                    Column(Modifier.weight(1f)) { blocks.drop(4).forEach { it() } }
                }
            } else blocks.forEach { it() }
        }
    }
}

@Composable
private fun ChooseRow(label: String, value: String, accent: Color, icon: G? = null, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        Labeled(label, icon, TextHi, 16, modifier = Modifier.weight(1f))
        Txt(value, 15, TextLo, modifier = Modifier.widthIn(max = 140.dp)); Txt(str(R.string.choose), 15, accent, bold = true)
    }

@Composable
private fun PermRow(label: String, ok: Boolean?, accent: Color, icon: G? = null, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        Labeled(label, icon, TextHi, 16, modifier = Modifier.weight(1f))
        Txt(str(when (ok) { true -> R.string.perm_ok; false -> R.string.perm_grant; null -> R.string.perm_open }), 17, if (ok == true) accent else Warn, bold = true)
    }
