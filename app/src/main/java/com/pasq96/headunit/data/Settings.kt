package com.pasq96.headunit.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

enum class DockPos { BOTTOM, LEFT, RIGHT }
enum class MediaMode { EMBED, WIDGET }
enum class Panels { BOTH, NAV, MEDIA }

/** Accent palette (ARGB), indexed by [Prefs.accent]. */
val ACCENTS = longArrayOf(0xFF3DDC84, 0xFF2B7FFF, 0xFFFFB300, 0xFFFF5252, 0xFFB388FF)

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
    val drawerCellDp: Int = 110,
    val accent: Int = 0,
    // apps
    val navPkg: String? = null,
    val mediaPkg: String? = null,
    val mediaMode: MediaMode = MediaMode.WIDGET,
    val pinned: List<String> = emptyList(),
    // dock extras
    val clock24h: Boolean = true,
    val showLinks: Boolean = true,
    // car tools
    val toolsIntervalSec: Int = 2,
    val tempWarnC: Int = 80,
    val night: Boolean = false,
    val nightAlpha: Float = 0.55f,
    // boot
    val autoStart: Boolean = false,
    val autoStartDelaySec: Int = 2,
    val autoPlay: Boolean = false,
    val autoPlayDelaySec: Int = 5,
    // ACC ON/OFF (suspend-to-RAM): re-place the embedded windows after wake
    val resumeOnWake: Boolean = true,
    val wakeDelaySec: Int = 2,
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
    val cell = intPreferencesKey("drawer_cell_dp")
    val accent = intPreferencesKey("accent")
    val nav = stringPreferencesKey("nav")
    val media = stringPreferencesKey("media")
    val mode = stringPreferencesKey("media_mode")
    val pinned = stringPreferencesKey("pinned")
    val clock24 = booleanPreferencesKey("clock_24h")
    val links = booleanPreferencesKey("show_links")
    val interval = intPreferencesKey("tools_interval_s")
    val tempWarn = intPreferencesKey("temp_warn_c")
    val night = booleanPreferencesKey("night")
    val nightAlpha = floatPreferencesKey("night_alpha")
    val auto = booleanPreferencesKey("auto_start")
    val autoDelay = intPreferencesKey("auto_start_delay_s")
    val play = booleanPreferencesKey("auto_play")
    val playDelay = intPreferencesKey("auto_play_delay_s")
    val resume = booleanPreferencesKey("resume_on_wake")
    val wakeDelay = intPreferencesKey("wake_delay_s")
}

class SettingsStore(private val ctx: Context) {
    val prefs: Flow<Prefs> = ctx.store.data.map(::read)

    private fun read(p: Preferences) = Prefs(
        ratio = (p[K.ratio] ?: 0.65f).coerceIn(MIN_RATIO, MAX_RATIO),
        dock = p[K.dock].toEnum(DockPos.BOTTOM),
        panels = p[K.panels].toEnum(Panels.BOTH),
        swapPanels = p[K.swap] ?: false,
        handleDp = (p[K.handle] ?: 28).coerceIn(16, 56),
        dockDp = (p[K.dockDp] ?: 88).coerceIn(64, 140),
        iconDp = (p[K.iconDp] ?: 56).coerceIn(40, 88),
        dockLabels = p[K.labels] ?: false,
        drawerCellDp = (p[K.cell] ?: 110).coerceIn(80, 180),
        accent = (p[K.accent] ?: 0).coerceIn(0, ACCENTS.lastIndex),
        navPkg = p[K.nav],
        mediaPkg = p[K.media],
        mediaMode = p[K.mode].toEnum(MediaMode.WIDGET),
        pinned = p[K.pinned]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
        clock24h = p[K.clock24] ?: true,
        showLinks = p[K.links] ?: true,
        toolsIntervalSec = (p[K.interval] ?: 2).coerceIn(1, 10),
        tempWarnC = (p[K.tempWarn] ?: 80).coerceIn(50, 110),
        night = p[K.night] ?: false,
        nightAlpha = (p[K.nightAlpha] ?: 0.55f).coerceIn(0.1f, 0.85f),
        autoStart = p[K.auto] ?: false,
        autoStartDelaySec = (p[K.autoDelay] ?: 2).coerceIn(0, 30),
        autoPlay = p[K.play] ?: false,
        autoPlayDelaySec = (p[K.playDelay] ?: 5).coerceIn(0, 60),
        resumeOnWake = p[K.resume] ?: true,
        wakeDelaySec = (p[K.wakeDelay] ?: 2).coerceIn(0, 15),
    )

    suspend fun <T> set(key: Preferences.Key<T>, value: T) = ctx.store.edit { it[key] = value }
    suspend fun setEnum(key: Preferences.Key<String>, value: Enum<*>) = set(key, value.name)
    suspend fun setPinned(v: List<String>) = set(K.pinned, v.joinToString(","))

    companion object {
        const val MIN_RATIO = 0.25f
        const val MAX_RATIO = 0.75f
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default
