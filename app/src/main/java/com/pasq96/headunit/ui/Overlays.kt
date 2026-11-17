package com.pasq96.headunit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
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
import com.pasq96.headunit.data.AppInfo
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
import com.pasq96.headunit.tools.CarTools
import kotlin.math.roundToInt

private val Scrim = Color(0xCC000000)
private val Danger = Color(0xFFFF5252)

// ---- system shortcuts (also the safe-mode exit) ----

private fun Context.go(i: Intent): Boolean = runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
internal fun openNotificationAccess(ctx: Context) { ctx.go(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
private fun openAndroidSettings(ctx: Context) { ctx.go(Intent(Settings.ACTION_SETTINGS)) }
private fun openDefaultApps(ctx: Context) { if (!ctx.go(Intent(Settings.ACTION_HOME_SETTINGS))) openAndroidSettings(ctx) }
private fun openOverlayAccess(ctx: Context) { ctx.go(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
private fun openWriteSettings(ctx: Context) { ctx.go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
private fun openUsageAccess(ctx: Context) { ctx.go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

/** Safe mode: two taps from the launcher to Android settings / the default-home picker. Always visible, top of every panel. */
@Composable
private fun EmergencyBar(vm: LauncherHub) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), Arrangement.spacedBy(12.dp)) {
        EmergencyButton(str(R.string.emg_android_settings), Modifier.weight(1f)) { vm.closeOverlays(); openAndroidSettings(ctx) }
        EmergencyButton(str(R.string.emg_default_apps), Modifier.weight(1f)) { vm.closeOverlays(); openDefaultApps(ctx) }
    }
}

@Composable
private fun EmergencyButton(text: String, modifier: Modifier, onClick: () -> Unit) =
    Box(modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(Danger).clickable(onClick = onClick), Alignment.Center) {
        Txt(text, 17, Color.White, bold = true, align = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp))
    }

// ---- layer ----

/** Drawer / quick tools / settings / app menu / app picker. Inline in the activity, or in an overlay window while apps are embedded. */
@Composable
fun OverlayLayer(vm: LauncherHub) = HeadUnitTheme(vm) {
    val p by vm.prefs.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val accent = Color(ACCENTS[p.accent])
    if (vm.drawer) Drawer(vm, p, apps, accent)
    if (vm.tools) ToolsPanel(vm, p, accent)
    if (vm.settings) SettingsPanel(vm, p, apps, accent)
    vm.menuApp?.let { AppMenu(vm, p, it, accent) }
    vm.picker?.let { AppPicker(vm, p, apps, it, accent) }
}

@Composable
private fun Dim(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.fillMaxSize().background(Scrim).pointerInput(Unit) { detectTapGestures { onDismiss() } }) { content() }

// ---- app drawer: vertical or horizontal grid, fixed span or auto ----

@Composable
private fun Drawer(vm: LauncherHub, p: Prefs, apps: List<AppInfo>, accent: Color) {
    val ctx = LocalContext.current
    val cell: @Composable (AppInfo) -> Unit = { a ->
        Column(
            Modifier.padding(4.dp).clip(RoundedCornerShape(14.dp))
                .androidxCombined(onClick = { vm.drawer = false; Embedder.launch(ctx, a.pkg, vm.boundsFor(a.pkg)) }, onLong = { vm.menuApp = a })
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(a.icon, a.label, Modifier.size(p.drawerIconDp.dp))
            if (p.drawerLabels) Txt(a.label, 14, maxLines = 2, align = TextAlign.Center, modifier = Modifier.width(p.drawerIconDp.dp + 24.dp).padding(top = 4.dp))
        }
    }
    val cells = if (p.drawerSpan == 0) GridCells.Adaptive(p.drawerIconDp.dp + 48.dp) else GridCells.Fixed(p.drawerSpan)
    val pad = PaddingValues(8.dp)
    Column(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.96f)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Txt(str(R.string.drawer_title, apps.size), 18, TextLo)
            GlyphButton(G.CLOSE, TextHi, { vm.drawer = false })
        }
        if (p.drawerHorizontal) LazyHorizontalGrid(cells, Modifier.fillMaxSize(), contentPadding = pad) { items(apps, key = { it.pkg }) { cell(it) } }
        else LazyVerticalGrid(cells, Modifier.fillMaxSize(), contentPadding = pad) { items(apps, key = { it.pkg }) { cell(it) } }
    }
}

@Composable
private fun AppMenu(vm: LauncherHub, p: Prefs, a: AppInfo, accent: Color) {
    val close = { vm.menuApp = null }
    Dim(close) {
        Column(
            Modifier.align(Alignment.Center).width(440.dp).clip(RoundedCornerShape(20.dp)).background(Surface)
                .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp),
            Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(a.icon, null, Modifier.size(48.dp)); Txt(a.label, 22, bold = true)
            }
            MenuBtn(str(if (a.pkg in p.pinned) R.string.menu_unpin else R.string.menu_pin), accent) { vm.togglePin(a.pkg); close() }
            MenuBtn((if (a.pkg == p.navPkg) "✓ " else "") + str(R.string.menu_set_nav), accent) { vm.set(K.nav, a.pkg); close() }
            MenuBtn((if (a.pkg == p.mediaPkg) "✓ " else "") + str(R.string.menu_set_player), accent) { vm.set(K.media, a.pkg); close() }
            MenuBtn(str(R.string.cancel), TextLo) { close() }
        }
    }
}

@Composable
private fun MenuBtn(text: String, tint: Color, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).clickable(onClick = onClick), Alignment.Center) { Txt(text, 18, tint, bold = true) }

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
            ToggleRow(str(R.string.picker_show_all), all, accent) { all = it }
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

// ---- quick tools ----

@Composable
private fun ToolsPanel(vm: LauncherHub, p: Prefs, accent: Color) {
    val ctx = LocalContext.current
    val gps = remember(vm.gpsTick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
    val st by remember(p.toolsIntervalSec) { CarTools.stats(ctx, p.toolsIntervalSec * 1000L) }.collectAsStateWithLifecycle(null)
    val speed by remember(gps) { CarTools.speedKmh(ctx) }.collectAsStateWithLifecycle(null)
    var bright by remember { mutableFloatStateOf(CarTools.brightness(ctx) / 255f) }

    Dim({ vm.tools = false }) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(480.dp).background(Surface).pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Txt(str(R.string.tools_title), 24, bold = true); GlyphButton(G.CLOSE, TextHi, { vm.tools = false })
            }
            Section(str(R.string.sec_system), accent) {
                val temp = st?.cpuC
                Stat(str(R.string.stat_cpu_temp), temp?.let { "${it.roundToInt()} °C" } ?: str(R.string.stat_na), if (temp != null && temp >= p.tempWarnC) Warn else TextHi)
                Stat(str(R.string.stat_ram_free), st?.let { "${it.ramFreeMb} / ${it.ramTotalMb} MB" } ?: "…")
                Stat(str(R.string.stat_battery), st?.battV?.let { "%.1f V".format(it) } ?: str(R.string.stat_na))
                if (gps) Stat(str(R.string.stat_gps_speed), speed?.let { "${it.roundToInt()} km/h" } ?: str(R.string.stat_no_fix))
                else Chip(str(R.string.gps_enable), false, accent) { vm.requestLocation() }
            }
            Section(str(R.string.sec_screen), accent) {
                if (!CarTools.canWriteSettings(ctx)) Chip(str(R.string.write_grant), false, accent) { vm.closeOverlays(); openWriteSettings(ctx) }
                else SimpleSlider(bright, accent, { bright = it; CarTools.setBrightness(ctx, (it * 255).roundToInt()) })
                Txt(str(R.string.theme_label), 15, TextLo, modifier = Modifier.padding(top = 8.dp))
                ThemeChips(vm, p, accent)
                Txt(str(R.string.dim_mode), 15, TextLo, modifier = Modifier.padding(top = 8.dp))
                DimChips(vm, p, accent)
            }
            Section(str(R.string.sec_panels), accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(str(R.string.panels_split), p.panels == Panels.BOTH, accent) { vm.setEnum(K.panels, Panels.BOTH) }
                    Chip(str(R.string.panels_nav_only), p.panels == Panels.NAV, accent) { vm.setEnum(K.panels, Panels.NAV) }
                    Chip(str(R.string.panels_player_only), p.panels == Panels.MEDIA, accent) { vm.setEnum(K.panels, Panels.MEDIA) }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(str(R.string.mode_widget), p.mediaMode == MediaMode.WIDGET, accent) { vm.setEnum(K.mode, MediaMode.WIDGET) }
                    Chip(str(R.string.mode_app), p.mediaMode == MediaMode.EMBED, accent) { vm.setEnum(K.mode, MediaMode.EMBED) }
                    Chip(str(R.string.mode_radio), p.mediaMode == MediaMode.RADIO, accent) { vm.setEnum(K.mode, MediaMode.RADIO) }
                }
            }
            Section(str(R.string.sec_emergency), Danger) { EmergencyBar(vm) }
        }
    }
}

@Composable
private fun ThemeChips(vm: LauncherHub, p: Prefs, accent: Color) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Chip(str(R.string.theme_dark), p.theme == ThemeMode.DARK, accent) { vm.setEnum(K.theme, ThemeMode.DARK) }
    Chip(str(R.string.theme_light), p.theme == ThemeMode.LIGHT, accent) { vm.setEnum(K.theme, ThemeMode.LIGHT) }
    Chip(str(R.string.theme_auto), p.theme == ThemeMode.AUTO, accent) { vm.setEnum(K.theme, ThemeMode.AUTO) }
}

@Composable
private fun DimChips(vm: LauncherHub, p: Prefs, accent: Color) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Chip(str(R.string.dim_off), p.dimMode == DimMode.OFF, accent) { vm.setEnum(K.dim, DimMode.OFF) }
    Chip(str(R.string.dim_on), p.dimMode == DimMode.ON, accent) { vm.setEnum(K.dim, DimMode.ON) }
    Chip(str(R.string.dim_auto), p.dimMode == DimMode.AUTO, accent) { vm.setEnum(K.dim, DimMode.AUTO) }
}

@Composable
private fun Stat(label: String, value: String, color: Color = TextHi) =
    Row(Modifier.fillMaxWidth().height(44.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) { Txt(label, 17, TextLo); Txt(value, 20, color, bold = true) }

// ---- settings ----

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
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Txt(str(R.string.settings_title), 26, bold = true); GlyphButton(G.CLOSE, TextHi, { vm.settings = false })
            }
            EmergencyBar(vm)

            Section(str(R.string.sec_look), accent) {
                Txt(str(R.string.theme_label), 15, TextLo); ThemeChips(vm, p, accent)
                Txt(str(R.string.lang_label), 15, TextLo, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(str(R.string.lang_it), p.lang == Lang.IT, accent) { vm.setEnum(K.lang, Lang.IT) }
                    Chip(str(R.string.lang_en), p.lang == Lang.EN, accent) { vm.setEnum(K.lang, Lang.EN) }
                }
                Txt(str(R.string.accent_label), 15, TextLo, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACCENTS.forEachIndexed { i, c -> Swatch(Color(c), p.accent == i) { vm.set(K.accent, i) } }
                }
            }
            Section(str(R.string.sec_led), accent) {
                Txt(str(R.string.led_source), 15, TextLo)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(str(R.string.src_all), p.ledSource == LedSource.AUTO, accent) { vm.setEnum(K.ledSource, LedSource.AUTO) }
                    Chip(str(R.string.src_broadcast), p.ledSource == LedSource.BROADCAST, accent) { vm.setEnum(K.ledSource, LedSource.BROADCAST) }
                    Chip(str(R.string.src_setting), p.ledSource == LedSource.SETTING, accent) { vm.setEnum(K.ledSource, LedSource.SETTING) }
                    Chip(str(R.string.src_sysfs), p.ledSource == LedSource.SYSFS, accent) { vm.setEnum(K.ledSource, LedSource.SYSFS) }
                }
                ToggleRow(str(R.string.led_invert), p.ledInvert, accent) { vm.set(K.ledInvert, it) }
                Stat(
                    str(R.string.led_state),
                    str(when (reading.on) { true -> R.string.led_on; false -> R.string.led_off; null -> R.string.led_unknown }) + (reading.source?.let { " · $it" } ?: ""),
                )
                Txt(str(R.string.led_hint), 13, TextLo, maxLines = 2)
                Txt(str(R.string.dim_mode), 15, TextLo, modifier = Modifier.padding(top = 8.dp))
                DimChips(vm, p, accent)
                Txt(str(R.string.dim_strength, (p.nightAlpha * 100).roundToInt()), 15, TextLo)
                SimpleSlider((p.nightAlpha - 0.1f) / 0.75f, accent, { vm.set(K.nightAlpha, 0.1f + it * 0.75f) })
            }
            Section(str(R.string.sec_layout), accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                ToggleRow(str(R.string.swap_panels), p.swapPanels, accent) { vm.set(K.swap, it) }
            }
            Section(str(R.string.sec_drawer), accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(str(R.string.drawer_vertical), !p.drawerHorizontal, accent) { vm.set(K.drawerH, false) }
                    Chip(str(R.string.drawer_horizontal), p.drawerHorizontal, accent) { vm.set(K.drawerH, true) }
                }
                StepRow(str(if (p.drawerHorizontal) R.string.drawer_rows else R.string.drawer_cols), if (p.drawerSpan == 0) auto else "${p.drawerSpan}", accent,
                    { vm.set(K.drawerSpan, p.drawerSpan - 1) }, { vm.set(K.drawerSpan, p.drawerSpan + 1) })
                StepRow(str(R.string.drawer_icon_size), "${p.drawerIconDp} dp", accent, { vm.set(K.drawerIcon, p.drawerIconDp - 8) }, { vm.set(K.drawerIcon, p.drawerIconDp + 8) })
                ToggleRow(str(R.string.drawer_labels), p.drawerLabels, accent) { vm.set(K.drawerLabels, it) }
            }
            Section(str(R.string.sec_apps), accent) {
                ChooseRow(str(R.string.navigator), appLabel(p.navPkg) ?: notSet, accent) { vm.picker = PickerKind.NAV }
                ChooseRow(str(R.string.player), appLabel(p.mediaPkg) ?: notSet, accent) { vm.picker = PickerKind.PLAYER }
                Txt(str(R.string.apps_hint), 14, TextLo)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(str(R.string.mode_widget), p.mediaMode == MediaMode.WIDGET, accent) { vm.setEnum(K.mode, MediaMode.WIDGET) }
                    Chip(str(R.string.mode_app_embedded), p.mediaMode == MediaMode.EMBED, accent) { vm.setEnum(K.mode, MediaMode.EMBED) }
                    Chip(str(R.string.mode_radio_native), p.mediaMode == MediaMode.RADIO, accent) { vm.setEnum(K.mode, MediaMode.RADIO) }
                }
            }
            Section(str(R.string.sec_radio), accent) {
                ToggleRow(str(R.string.swc_seek), p.swcSeek, accent) { vm.set(K.swcSeek, it) }
                Txt(str(R.string.fm_step), 15, TextLo)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 200).forEach { k -> Chip("$k kHz", p.fmStepKhz == k, accent) { vm.set(K.fmStep, k) } }
                }
                Txt(str(R.string.am_step), 15, TextLo, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(9, 10).forEach { k -> Chip("$k kHz", p.amStepKhz == k, accent) { vm.set(K.amStep, k) } }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(str(R.string.presets_import), false, accent) { vm.importStockPresets() }
                    Chip(str(R.string.radio_open_stock), false, accent) { vm.closeOverlays(); vm.radio.openStockApp() }
                }
            }
            Section(str(R.string.sec_dock), accent) {
                ToggleRow(str(R.string.dock_overlay), p.dockOverlay, accent) { vm.set(K.dockOverlay, it) }
                ToggleRow(str(R.string.clock_24h), p.clock24h, accent) { vm.set(K.clock24, it) }
                ToggleRow(str(R.string.show_links), p.showLinks, accent) { vm.set(K.links, it) }
            }
            Section(str(R.string.sec_cartools), accent) {
                StepRow(str(R.string.sensor_interval), "${p.toolsIntervalSec} s", accent, { vm.set(K.interval, p.toolsIntervalSec - 1) }, { vm.set(K.interval, p.toolsIntervalSec + 1) })
                StepRow(str(R.string.temp_warn), "${p.tempWarnC} °C", accent, { vm.set(K.tempWarn, p.tempWarnC - 5) }, { vm.set(K.tempWarn, p.tempWarnC + 5) })
            }
            Section(str(R.string.sec_boot), accent) {
                ToggleRow(str(R.string.auto_start), p.autoStart, accent) { vm.set(K.auto, it) }
                StepRow(str(R.string.auto_start_delay), "${p.autoStartDelaySec} s", accent, { vm.set(K.autoDelay, p.autoStartDelaySec - 1) }, { vm.set(K.autoDelay, p.autoStartDelaySec + 1) })
                ToggleRow(str(R.string.auto_play), p.autoPlay, accent) { vm.set(K.play, it) }
                StepRow(str(R.string.auto_play_delay), "${p.autoPlayDelaySec} s", accent, { vm.set(K.playDelay, p.autoPlayDelaySec - 1) }, { vm.set(K.playDelay, p.autoPlayDelaySec + 1) })
                ToggleRow(str(R.string.resume_on_wake), p.resumeOnWake, accent) { vm.set(K.resume, it) }
                StepRow(str(R.string.wake_delay), "${p.wakeDelaySec} s", accent, { vm.set(K.wakeDelay, p.wakeDelaySec - 1) }, { vm.set(K.wakeDelay, p.wakeDelaySec + 1) })
            }
            Section(str(R.string.sec_perms), accent) {
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
        }
    }
}

@Composable
private fun ChooseRow(label: String, value: String, accent: Color, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Txt(label, 17)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(value, 17, TextLo); Txt(str(R.string.choose), 17, accent, bold = true)
        }
    }

@Composable
private fun PermRow(label: String, ok: Boolean?, accent: Color, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Txt(label, 17)
        Txt(str(when (ok) { true -> R.string.perm_ok; false -> R.string.perm_grant; null -> R.string.perm_open }), 17, if (ok == true) accent else Warn, bold = true)
    }
