package com.pasq96.headunit.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings", corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

enum class DockPos { BOTTOM, LEFT, RIGHT }
enum class MediaMode { EMBED, WIDGET, RADIO }
enum class Panels { BOTH, NAV, MEDIA }
enum class ThemeMode { DARK, LIGHT, AUTO }          // AUTO: dark while the button LEDs are on, light when off
enum class AppSort { ALPHA, USAGE, CUSTOM }            // drawer order
enum class DimMode { OFF, ON, AUTO }                // dark overlay; AUTO follows the button LEDs
enum class LedSource { AUTO, BROADCAST, SETTING, SYSFS }
enum class Lang(val tag: String) { IT("it"), EN("en") }

/** Accent palette (ARGB), indexed by [Prefs.accent]. */
val ACCENTS = longArrayOf(0xFF3DDC84, 0xFF2B7FFF, 0xFFFFB300, 0xFFFF5252, 0xFFB388FF)

/** Effective accent: preset or the user-picked colour. */
val Prefs.accentArgb: Int get() = if (accent in ACCENTS.indices) ACCENTS[accent].toInt() else accentCustom

data class Prefs(
    // layout
    val ratio: Float = 0.65f,
    val dock: DockPos = DockPos.BOTTOM,
    val panels: Panels = Panels.BOTH,
    val swapPanels: Boolean = false,        // media on the left, navigation on the right
    val handleDp: Int = 28,                 // gap/drag-handle width between the panels
    val dockDp: Int = 88,                   // dock thickness
    val iconDp: Int = 56,                   // dock icon size
    val dockLabels: Boolean = false,
    val drawerHorizontal: Boolean = false,  // drawer scrolls sideways
    val drawerSpan: Int = 0,                // columns (vertical) / rows (horizontal), 0 = auto
    val drawerIconDp: Int = 64,
    val drawerLabels: Boolean = true,
    val previews: Boolean = true,           // still previews of the nav / player apps in their panels
    val embedWindows: Boolean = false,      // experimental: place the apps as freeform windows inside the panels
    val drawerFill: Boolean = true,         // auto grid sized so every app fits on screen (vertical, auto columns only)
    val drawerDev: Boolean = true,          // developer-options shortcut as first drawer cell
    val theme: ThemeMode = ThemeMode.DARK,
    val lang: Lang = Lang.IT,
    val accent: Int = 0,                    // index into ACCENTS; ACCENTS.size = custom colour
    val accentCustom: Int = 0xFF3DDC84.toInt(),
    // apps
    val navPkg: String? = null,
    val mediaPkg: String? = null,
    val mediaMode: MediaMode = MediaMode.WIDGET,
    val pinned: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),   // apps hidden from the drawer
    val sort: AppSort = AppSort.ALPHA,
    val customOrder: List<String> = emptyList(),
    val usage: Map<String, Int> = emptyMap(), // launches per package, drives AppSort.USAGE
    // dock extras
    val clock24h: Boolean = true,
    val showWifi: Boolean = true,           // WiFi (and hotspot) icon in the dock
    val showBt: Boolean = true,
    val showDate: Boolean = true,
    // car tools
    val toolsIntervalSec: Int = 2,
    val tempWarnC: Int = 80,
    val dimMode: DimMode = DimMode.OFF,
    val ledSource: LedSource = LedSource.AUTO,
    val ledInvert: Boolean = false,         // signal polarity: true = LEDs on means day
    val nightAlpha: Float = 0.55f,
    // boot
    val autoStart: Boolean = false,
    val autoStartDelaySec: Int = 2,
    val autoPlay: Boolean = false,
    val autoPlayDelaySec: Int = 5,
    // ACC ON/OFF (suspend-to-RAM): re-place the embedded windows after wake
    val resumeOnWake: Boolean = true,
    val wakeDelaySec: Int = 2,
    // native FM radio
    val presets: List<Int> = List(20) { 0 },   // kHz, 0 = empty slot
    val swcSeek: Boolean = false,             // steering wheel NEXT/PREV: seek instead of walking the presets
    val fmStepKhz: Int = 100,
    val amStepKhz: Int = 9,
    // dock hosted in an overlay window (needs SYSTEM_ALERT_WINDOW + freeform)
    val dockOverlay: Boolean = true,
    val dockHideFull: Boolean = true,
    val dockHideApps: Set<String> = DOCK_HIDE_DEFAULT,   // packages in front of which the dock steps aside
)

object K {
    val ratio = floatPreferencesKey("ratio")
    val dock = stringPreferencesKey("dock")
    val panels = stringPreferencesKey("panels")
    val swap = booleanPreferencesKey("swap_panels")
    val handle = intPreferencesKey("handle_dp")
    val dockDp = intPreferencesKey("dock_dp")
    val iconDp = intPreferencesKey("icon_dp")
    val labels = booleanPreferencesKey("dock_labels")
    val drawerH = booleanPreferencesKey("drawer_horizontal")
    val drawerSpan = intPreferencesKey("drawer_span")
    val drawerIcon = intPreferencesKey("drawer_icon_dp")
    val drawerLabels = booleanPreferencesKey("drawer_labels")
    val drawerFill = booleanPreferencesKey("drawer_fill")
    val previews = booleanPreferencesKey("previews")
    val embedWindows = booleanPreferencesKey("embed_windows")
    val drawerDev = booleanPreferencesKey("drawer_dev")
    val theme = stringPreferencesKey("theme")
    val lang = stringPreferencesKey("lang")
    val accent = intPreferencesKey("accent")
    val accentCustom = intPreferencesKey("accent_custom")
    val nav = stringPreferencesKey("nav")
    val media = stringPreferencesKey("media")
    val mode = stringPreferencesKey("media_mode")
    val pinned = stringPreferencesKey("pinned")
    val hidden = stringPreferencesKey("hidden")
    val sort = stringPreferencesKey("drawer_sort")
    val order = stringPreferencesKey("drawer_order")
    val usage = stringPreferencesKey("usage")
    val clock24 = booleanPreferencesKey("clock_24h")
    val date = booleanPreferencesKey("show_date")
    val links = booleanPreferencesKey("show_links") // legacy single toggle, still read as default
    val wifi = booleanPreferencesKey("show_wifi")
    val bt = booleanPreferencesKey("show_bt")
    val interval = intPreferencesKey("tools_interval_s")
    val tempWarn = intPreferencesKey("temp_warn_c")
    val dim = stringPreferencesKey("dim_mode")
    val ledSource = stringPreferencesKey("led_source")
    val ledInvert = booleanPreferencesKey("led_invert")
    val nightAlpha = floatPreferencesKey("night_alpha")
    val auto = booleanPreferencesKey("auto_start")
    val autoDelay = intPreferencesKey("auto_start_delay_s")
    val play = booleanPreferencesKey("auto_play")
    val playDelay = intPreferencesKey("auto_play_delay_s")
    val resume = booleanPreferencesKey("resume_on_wake")
    val wakeDelay = intPreferencesKey("wake_delay_s")
    val presets = stringPreferencesKey("radio_presets")
    val swcSeek = booleanPreferencesKey("swc_seek")
    val fmStep = intPreferencesKey("fm_step_khz")
    val amStep = intPreferencesKey("am_step_khz")
    val dockOverlay = booleanPreferencesKey("dock_overlay")
    val dockHideFull = booleanPreferencesKey("dock_hide_full")
    val dockHideApps = stringPreferencesKey("dock_hide_apps")
}

class SettingsStore(private val ctx: Context) {
    val prefs: Flow<Prefs> = ctx.store.data.catch { emit(emptyPreferences()) }.map(::read)

    private fun read(p: Preferences) = Prefs(
        ratio = (p[K.ratio] ?: 0.65f).coerceIn(MIN_RATIO, MAX_RATIO),
        dock = p[K.dock].toEnum(DockPos.BOTTOM),
        panels = p[K.panels].toEnum(Panels.BOTH),
        swapPanels = p[K.swap] ?: false,
        handleDp = (p[K.handle] ?: 28).coerceIn(16, 56),
        dockDp = (p[K.dockDp] ?: 88).coerceIn(64, 140),
        iconDp = (p[K.iconDp] ?: 56).coerceIn(40, 88),
        dockLabels = p[K.labels] ?: false,
        drawerHorizontal = p[K.drawerH] ?: false,
        drawerSpan = (p[K.drawerSpan] ?: 0).coerceIn(0, 10),
        drawerIconDp = (p[K.drawerIcon] ?: 64).coerceIn(40, 112),
        drawerLabels = p[K.drawerLabels] ?: true,
        drawerFill = p[K.drawerFill] ?: true,
        previews = p[K.previews] ?: true,
        embedWindows = p[K.embedWindows] ?: false,
        drawerDev = p[K.drawerDev] ?: true,
        theme = p[K.theme].toEnum(ThemeMode.DARK),
        lang = p[K.lang].toEnum(Lang.IT),
        accent = (p[K.accent] ?: 0).coerceIn(0, ACCENTS.size),
        accentCustom = p[K.accentCustom] ?: 0xFF3DDC84.toInt(),
        navPkg = p[K.nav],
        mediaPkg = p[K.media],
        mediaMode = p[K.mode].toEnum(MediaMode.WIDGET),
        pinned = p[K.pinned]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
        hidden = p[K.hidden]?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
        sort = p[K.sort].toEnum(AppSort.ALPHA),
        customOrder = p[K.order]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
        usage = p[K.usage]?.split(',')?.mapNotNull { e -> e.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }?.toMap() ?: emptyMap(),
        clock24h = p[K.clock24] ?: true,
        showWifi = p[K.wifi] ?: p[K.links] ?: true,
        showBt = p[K.bt] ?: p[K.links] ?: true,
        showDate = p[K.date] ?: true,
        toolsIntervalSec = (p[K.interval] ?: 2).coerceIn(1, 10),
        tempWarnC = (p[K.tempWarn] ?: 80).coerceIn(50, 110),
        dimMode = p[K.dim].toEnum(DimMode.OFF),
        ledSource = p[K.ledSource].toEnum(LedSource.AUTO),
        ledInvert = p[K.ledInvert] ?: false,
        nightAlpha = (p[K.nightAlpha] ?: 0.55f).coerceIn(0.1f, 0.85f),
        autoStart = p[K.auto] ?: false,
        autoStartDelaySec = (p[K.autoDelay] ?: 2).coerceIn(0, 30),
        autoPlay = p[K.play] ?: false,
        autoPlayDelaySec = (p[K.playDelay] ?: 5).coerceIn(0, 60),
        resumeOnWake = p[K.resume] ?: true,
        wakeDelaySec = (p[K.wakeDelay] ?: 2).coerceIn(0, 15),
        presets = (p[K.presets]?.split(',')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()).let { l -> List(20) { l.getOrElse(it) { 0 } } },
        swcSeek = p[K.swcSeek] ?: false,
        fmStepKhz = (p[K.fmStep] ?: 100).coerceIn(50, 200),
        amStepKhz = (p[K.amStep] ?: 9).coerceIn(5, 10),
        dockOverlay = p[K.dockOverlay] ?: true,
        dockHideFull = p[K.dockHideFull] ?: true,
        dockHideApps = p[K.dockHideApps]?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: DOCK_HIDE_DEFAULT,
    )

    suspend fun <T> set(key: Preferences.Key<T>, value: T) = ctx.store.edit { it[key] = value }
    suspend fun setEnum(key: Preferences.Key<String>, value: Enum<*>) = set(key, value.name)
    suspend fun setPinned(v: List<String>) = set(K.pinned, v.joinToString(","))

    /** Adds or removes [item] in a comma list inside one DataStore transaction, so quick taps never overwrite each other. */
    suspend fun toggleIn(key: Preferences.Key<String>, item: String) {
        ctx.store.edit { p ->
            val l = p[key]?.split(',')?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
            if (!l.remove(item)) l.add(item)
            p[key] = l.joinToString(",")
        }
    }

    companion object {
        const val MIN_RATIO = 0.25f
        const val MAX_RATIO = 0.75f
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default

/** Phone-projection apps (Android Auto, CarPlay, Link) draw their own bottom bar. */
val DOCK_HIDE_DEFAULT = setOf("com.imotor.phoneconnect", "com.google.android.projection.gearhead")
