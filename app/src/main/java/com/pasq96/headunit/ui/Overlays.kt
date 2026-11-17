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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.pasq96.headunit.data.ACCENTS
import com.pasq96.headunit.data.AppInfo
import com.pasq96.headunit.data.DockPos
import com.pasq96.headunit.data.K
import com.pasq96.headunit.data.MediaMode
import com.pasq96.headunit.data.Panels
import com.pasq96.headunit.data.Prefs
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
        EmergencyButton("Impostazioni Android", Modifier.weight(1f)) { vm.closeOverlays(); openAndroidSettings(ctx) }
        EmergencyButton("App predefinite (cambia launcher)", Modifier.weight(1f)) { vm.closeOverlays(); openDefaultApps(ctx) }
    }
}

@Composable
private fun EmergencyButton(text: String, modifier: Modifier, onClick: () -> Unit) =
    Box(modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(Danger).clickable(onClick = onClick), Alignment.Center) {
        Txt(text, 17, Color.White, bold = true, align = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp))
    }

// ---- layer ----

/** Drawer / quick tools / settings / app menu. Inline in the activity, or in an overlay window while apps are embedded. */
@Composable
fun OverlayLayer(vm: LauncherHub) {
    val p by vm.prefs.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val accent = Color(ACCENTS[p.accent])
    if (vm.drawer) Drawer(vm, p, apps, accent)
    if (vm.tools) ToolsPanel(vm, p, accent)
    if (vm.settings) SettingsPanel(vm, p, apps, accent)
    vm.menuApp?.let { AppMenu(vm, p, it, accent) }
}

@Composable
private fun Dim(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.fillMaxSize().background(Scrim).pointerInput(Unit) { detectTapGestures { onDismiss() } }) { content() }

// ---- app drawer ----

@Composable
private fun Drawer(vm: LauncherHub, p: Prefs, apps: List<AppInfo>, accent: Color) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color(0xF20B0F14)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Txt("App (${apps.size})  ·  tieni premuto per pinnare / impostare", 18, TextLo)
            GlyphButton(G.CLOSE, TextHi, { vm.drawer = false })
        }
        LazyVerticalGrid(GridCells.Adaptive(p.drawerCellDp.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            items(apps, key = { it.pkg }) { a ->
                Column(
                    Modifier.padding(4.dp).clip(RoundedCornerShape(14.dp))
                        .androidxCombined(onClick = { vm.drawer = false; Embedder.launch(ctx, a.pkg, vm.boundsFor(a.pkg)) }, onLong = { vm.menuApp = a })
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(a.icon, a.label, Modifier.size(64.dp))
                    Txt(a.label, 14, maxLines = 2, align = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
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
            MenuBtn(if (a.pkg in p.pinned) "Rimuovi dalla dock" else "Pinna nella dock", accent) { vm.togglePin(a.pkg); close() }
            MenuBtn((if (a.pkg == p.navPkg) "✓ " else "") + "Imposta come navigatore", accent) { vm.set(K.nav, a.pkg); close() }
            MenuBtn((if (a.pkg == p.mediaPkg) "✓ " else "") + "Imposta come player", accent) { vm.set(K.media, a.pkg); close() }
            MenuBtn("Annulla", TextLo) { close() }
        }
    }
}

@Composable
private fun MenuBtn(text: String, tint: Color, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).clickable(onClick = onClick), Alignment.Center) { Txt(text, 18, tint, bold = true) }

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
                Txt("Car Tools", 24, bold = true); GlyphButton(G.CLOSE, TextHi, { vm.tools = false })
            }
            Section("Sistema", accent) {
                val temp = st?.cpuC
                Stat("Temp CPU", temp?.let { "${it.roundToInt()} °C" } ?: "n/d", if (temp != null && temp >= p.tempWarnC) Warn else TextHi)
                Stat("RAM libera", st?.let { "${it.ramFreeMb} / ${it.ramTotalMb} MB" } ?: "…")
                Stat("Batteria auto", st?.battV?.let { "%.1f V".format(it) } ?: "n/d")
                if (gps) Stat("Velocità GPS", speed?.let { "${it.roundToInt()} km/h" } ?: "nessun fix")
                else Chip("Abilita GPS per la velocità", false, accent) { vm.requestLocation() }
            }
            Section("Schermo", accent) {
                if (!CarTools.canWriteSettings(ctx)) Chip("Concedi “Modifica impostazioni di sistema”", false, accent) { vm.closeOverlays(); openWriteSettings(ctx) }
                else SimpleSlider(bright, accent, { bright = it; CarTools.setBrightness(ctx, (it * 255).roundToInt()) })
                ToggleRow("Modalità notte (overlay scuro)", p.night, accent) { vm.set(K.night, it) }
            }
            Section("Pannelli", accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Split", p.panels == Panels.BOTH, accent) { vm.setEnum(K.panels, Panels.BOTH) }
                    Chip("Solo mappa", p.panels == Panels.NAV, accent) { vm.setEnum(K.panels, Panels.NAV) }
                    Chip("Solo player", p.panels == Panels.MEDIA, accent) { vm.setEnum(K.panels, Panels.MEDIA) }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Player: widget", p.mediaMode == MediaMode.WIDGET, accent) { vm.setEnum(K.mode, MediaMode.WIDGET) }
                    Chip("Player: app", p.mediaMode == MediaMode.EMBED, accent) { vm.setEnum(K.mode, MediaMode.EMBED) }
                    Chip("Radio FM", p.mediaMode == MediaMode.RADIO, accent) { vm.setEnum(K.mode, MediaMode.RADIO) }
                }
            }
            Section("Uscita di emergenza", Danger) { EmergencyBar(vm) }
        }
    }
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
    val label = { pkg: String? -> pkg?.let { id -> apps.firstOrNull { it.pkg == id }?.label ?: id } ?: "non impostato" }

    Dim({ vm.settings = false }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.92f).fillMaxHeight(0.94f).clip(RoundedCornerShape(20.dp)).background(Surface)
                .pointerInput(Unit) { detectTapGestures { } }.verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Txt("Impostazioni", 26, bold = true); GlyphButton(G.CLOSE, TextHi, { vm.settings = false })
            }
            EmergencyBar(vm)

            Section("Layout", accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Dock in basso", p.dock == DockPos.BOTTOM, accent) { vm.setEnum(K.dock, DockPos.BOTTOM) }
                    Chip("Dock a sinistra", p.dock == DockPos.LEFT, accent) { vm.setEnum(K.dock, DockPos.LEFT) }
                    Chip("Dock a destra", p.dock == DockPos.RIGHT, accent) { vm.setEnum(K.dock, DockPos.RIGHT) }
                }
                StepRow("Spessore dock", "${p.dockDp} dp", accent, { vm.set(K.dockDp, p.dockDp - 8) }, { vm.set(K.dockDp, p.dockDp + 8) })
                StepRow("Dimensione icone dock", "${p.iconDp} dp", accent, { vm.set(K.iconDp, p.iconDp - 4) }, { vm.set(K.iconDp, p.iconDp + 4) })
                ToggleRow("Etichette sotto le icone", p.dockLabels, accent) { vm.set(K.labels, it) }
                StepRow("Larghezza divisore", "${p.handleDp} dp", accent, { vm.set(K.handle, p.handleDp - 4) }, { vm.set(K.handle, p.handleDp + 4) })
                StepRow("Proporzione sinistra/destra", "${(p.ratio * 100).roundToInt()}/${100 - (p.ratio * 100).roundToInt()}", accent,
                    { vm.set(K.ratio, p.ratio - 0.05f) }, { vm.set(K.ratio, p.ratio + 0.05f) })
                ToggleRow("Scambia pannelli (player a sinistra)", p.swapPanels, accent) { vm.set(K.swap, it) }
                StepRow("Colonne drawer (cella)", "${p.drawerCellDp} dp", accent, { vm.set(K.cell, p.drawerCellDp - 10) }, { vm.set(K.cell, p.drawerCellDp + 10) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    ACCENTS.forEachIndexed { i, c -> Swatch(Color(c), p.accent == i) { vm.set(K.accent, i) } }
                }
            }
            Section("App", accent) {
                Stat("Navigatore", label(p.navPkg)); Stat("Player", label(p.mediaPkg))
                Txt("Tieni premuta un'app nel drawer per impostarla o pinnarla.", 14, TextLo)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Player: widget", p.mediaMode == MediaMode.WIDGET, accent) { vm.setEnum(K.mode, MediaMode.WIDGET) }
                    Chip("Player: app embedded", p.mediaMode == MediaMode.EMBED, accent) { vm.setEnum(K.mode, MediaMode.EMBED) }
                    Chip("Radio FM nativa", p.mediaMode == MediaMode.RADIO, accent) { vm.setEnum(K.mode, MediaMode.RADIO) }
                }
            }
            Section("Radio FM nativa", accent) {
                ToggleRow("Volante: seek invece dei preset", p.swcSeek, accent) { vm.set(K.swcSeek, it) }
                Txt("Passo FM", 15, TextLo)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 200).forEach { k -> Chip("$k kHz", p.fmStepKhz == k, accent) { vm.set(K.fmStep, k) } }
                }
                Txt("Passo AM", 15, TextLo, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(9, 10).forEach { k -> Chip("$k kHz", p.amStepKhz == k, accent) { vm.set(K.amStep, k) } }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Importa preset dalla radio", false, accent) { vm.importStockPresets() }
                    Chip("Apri radio stock", false, accent) { vm.closeOverlays(); vm.radio.openStockApp() }
                }
            }
            Section("Dock", accent) {
                ToggleRow("Dock sempre sopra le app (overlay, serve freeform)", p.dockOverlay, accent) { vm.set(K.dockOverlay, it) }
                ToggleRow("Orologio 24 ore", p.clock24h, accent) { vm.set(K.clock24, it) }
                ToggleRow("Indicatori WiFi / Hotspot / Bluetooth", p.showLinks, accent) { vm.set(K.links, it) }
            }
            Section("Car Tools", accent) {
                StepRow("Aggiornamento sensori", "${p.toolsIntervalSec} s", accent, { vm.set(K.interval, p.toolsIntervalSec - 1) }, { vm.set(K.interval, p.toolsIntervalSec + 1) })
                StepRow("Allarme temperatura CPU", "${p.tempWarnC} °C", accent, { vm.set(K.tempWarn, p.tempWarnC - 5) }, { vm.set(K.tempWarn, p.tempWarnC + 5) })
                ToggleRow("Modalità notte", p.night, accent) { vm.set(K.night, it) }
                Txt("Intensità overlay notte: ${(p.nightAlpha * 100).roundToInt()}%", 15, TextLo)
                SimpleSlider((p.nightAlpha - 0.1f) / 0.75f, accent, { vm.set(K.nightAlpha, 0.1f + it * 0.75f) })
            }
            Section("Avvio e risveglio (ACC)", accent) {
                ToggleRow("Avvia il navigatore all'accensione", p.autoStart, accent) { vm.set(K.auto, it) }
                StepRow("Ritardo avvio navigatore", "${p.autoStartDelaySec} s", accent, { vm.set(K.autoDelay, p.autoStartDelaySec - 1) }, { vm.set(K.autoDelay, p.autoStartDelaySec + 1) })
                ToggleRow("Riprendi la musica (PLAY)", p.autoPlay, accent) { vm.set(K.play, it) }
                StepRow("Ritardo PLAY", "${p.autoPlayDelaySec} s", accent, { vm.set(K.playDelay, p.autoPlayDelaySec - 1) }, { vm.set(K.playDelay, p.autoPlayDelaySec + 1) })
                ToggleRow("Riaggancia le finestre al risveglio", p.resumeOnWake, accent) { vm.set(K.resume, it) }
                StepRow("Attesa dopo il risveglio", "${p.wakeDelaySec} s", accent, { vm.set(K.wakeDelay, p.wakeDelaySec - 1) }, { vm.set(K.wakeDelay, p.wakeDelaySec + 1) })
            }
            Section("Permessi e stato", accent) {
                val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
                val write = remember(tick) { CarTools.canWriteSettings(ctx) }
                val listener = remember(tick) { vm.media.hasAccess() }
                val freeform = remember(tick) { Embedder.freeformEnabled(ctx) }
                PermRow("Sovrapposizione (overlay)", overlay, accent) { vm.closeOverlays(); openOverlayAccess(ctx) }
                PermRow("Modifica impostazioni di sistema", write, accent) { vm.closeOverlays(); openWriteSettings(ctx) }
                PermRow("Accesso notifiche (copertina e titolo)", listener, accent) { vm.closeOverlays(); openNotificationAccess(ctx) }
                PermRow("Accesso utilizzo app", null, accent) { vm.closeOverlays(); openUsageAccess(ctx) }
                Stat("Finestre freeform", if (freeform) "attive" else "non attive (vedi README)", if (freeform) accent else Warn)
            }
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean?, accent: Color, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Txt(label, 17)
        Txt(when (ok) { true -> "OK"; false -> "Concedi ›"; null -> "Apri ›" }, 17, if (ok == true) accent else Warn, bold = true)
    }
