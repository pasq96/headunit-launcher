package com.pasq96.headunit.ui

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.Preferences
import com.pasq96.headunit.MainActivity
import com.pasq96.headunit.data.AppInfo
import com.pasq96.headunit.data.AppRepo
import com.pasq96.headunit.data.K
import com.pasq96.headunit.data.MediaMode
import com.pasq96.headunit.data.Panels
import com.pasq96.headunit.data.Prefs
import com.pasq96.headunit.data.SettingsStore
import com.pasq96.headunit.media.MediaRepo
import com.pasq96.headunit.radio.DefaultImotorRadioBridge
import com.pasq96.headunit.radio.ImotorRadioController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-wide state shared by the activity and the overlay service (dock + panels), so both always show the same thing.
 * Owned by [com.pasq96.headunit.HeadUnitApp], not by an activity.
 */
class LauncherHub(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = SettingsStore(app)
    val prefs: StateFlow<Prefs> = store.prefs.stateIn(scope, SharingStarted.Eagerly, Prefs())
    val apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val media = MediaRepo(app)
    val radio: ImotorRadioController = DefaultImotorRadioBridge(app)

    var drawer by mutableStateOf(false)
    var tools by mutableStateOf(false)
    var settings by mutableStateOf(false)
    var menuApp by mutableStateOf<AppInfo?>(null)
    var overlayInWindow by mutableStateOf(false)   // panels hosted by the overlay service (above freeform apps)
    var dockInOverlay by mutableStateOf(false)     // dock hosted by the overlay service: the in-activity dock stays blank
    var restoreTick by mutableIntStateOf(0)        // bumped on wake: HomeScreen re-places embedded windows
    var gpsTick by mutableIntStateOf(0)            // bumped when the location permission answer comes back

    // Last measured panel windows (screen px), used to open an app straight into its panel.
    var navRect: Rect? = null
    var mediaRect: Rect? = null

    suspend fun loadedPrefs(): Prefs = store.prefs.first()

    fun reloadApps() { scope.launch { apps.value = AppRepo.load(app) } }

    fun <V> set(key: Preferences.Key<V>, value: V) { scope.launch { store.set(key, value) } }
    fun setEnum(key: Preferences.Key<String>, value: Enum<*>) { scope.launch { store.setEnum(key, value) } }

    fun togglePin(pkg: String) {
        val cur = prefs.value.pinned
        scope.launch { store.setPinned(if (pkg in cur) cur - pkg else cur + pkg) }
    }

    fun cyclePanels() = setEnum(K.panels, when (prefs.value.panels) {
        Panels.BOTH -> Panels.NAV
        Panels.NAV -> Panels.MEDIA
        Panels.MEDIA -> Panels.BOTH
    })

    fun hasOverlay() = drawer || tools || settings || menuApp != null

    fun closeOverlays() { drawer = false; tools = false; settings = false; menuApp = null }

    /** The location prompt needs an activity: bring the launcher up and ask there. */
    fun requestLocation() {
        closeOverlays()
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_REQUEST_LOCATION, true))
    }

    /** Window an app should open into: its panel when it is the navigator / embedded player, else null (full screen). */
    fun boundsFor(pkg: String): Rect? {
        val p = prefs.value
        return when {
            pkg == p.navPkg && p.panels != Panels.MEDIA -> navRect
            pkg == p.mediaPkg && p.mediaMode == MediaMode.EMBED && p.panels != Panels.NAV -> mediaRect
            else -> null
        }
    }

    // ---- native radio: local presets (DataStore, independent from the MCU memory) ----

    fun radioActive() = prefs.value.mediaMode == MediaMode.RADIO && prefs.value.panels != Panels.NAV

    private fun setPresets(l: List<Int>) = set(K.presets, l.joinToString(","))

    fun recallPreset(slot: Int) {
        prefs.value.presets.getOrNull(slot)?.takeIf { it > 0 }?.let { radio.setFrequency(it) }
    }

    /** Long press: store the current frequency in the slot, or clear the slot when it already holds it. */
    fun togglePreset(slot: Int) {
        val cur = radio.state.value.freqKhz
        val list = prefs.value.presets.toMutableList()
        if (slot !in list.indices) return
        if (list[slot] == cur) list[slot] = 0 else if (cur > 0) list[slot] = cur else return
        setPresets(list)
    }

    fun importStockPresets() {
        val s = radio.state.value.stockPresets
        if (s.isNotEmpty()) setPresets(List(PRESET_SLOTS) { s.getOrElse(it) { 0 } })
    }

    /** Steering wheel NEXT/PREV while the radio panel is active: walk the saved presets, or seek when there are none. */
    fun steerRadio(next: Boolean) {
        val p = prefs.value
        val saved = p.presets.filter { it > 0 }
        if (p.swcSeek || saved.isEmpty()) { if (next) radio.seekUp() else radio.seekDown(); return }
        val idx = saved.indexOf(radio.state.value.freqKhz)
        val target = if (idx < 0) (if (next) saved.first() else saved.last())
        else saved[(idx + (if (next) 1 else -1) + saved.size) % saved.size]
        radio.setFrequency(target)
    }

    companion object { const val PRESET_SLOTS = 8 }
}
