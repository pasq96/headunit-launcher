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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pasq96.headunit.system.Previews
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import com.pasq96.headunit.data.AppInfo
import com.pasq96.headunit.embed.Embedder
import com.pasq96.headunit.data.AppRepo
import com.pasq96.headunit.data.AppSort
import com.pasq96.headunit.data.orderedApps
import com.pasq96.headunit.data.DimMode
import com.pasq96.headunit.data.K
import com.pasq96.headunit.data.MediaMode
import com.pasq96.headunit.data.Panels
import com.pasq96.headunit.data.Prefs
import com.pasq96.headunit.data.SettingsStore
import com.pasq96.headunit.data.ThemeMode
import com.pasq96.headunit.audio.AudioFx
import com.pasq96.headunit.media.MediaRepo
import com.pasq96.headunit.radio.DefaultImotorRadioBridge
import com.pasq96.headunit.radio.ImotorRadioController
import com.pasq96.headunit.system.IlluminationMonitor
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PickerKind { NAV, PLAYER }

/**
 * Process-wide state shared by the activity and the overlay service (dock + panels), so both always show the same thing.
 * Owned by [com.pasq96.headunit.HeadUnitApp], not by an activity.
 */
class LauncherHub(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.w("HUHub", "uncaught", e) })
    private var loadJob: Job? = null
    var bootHandled = false
    private val store = SettingsStore(app)
    val prefs: StateFlow<Prefs> = store.prefs.stateIn(scope, SharingStarted.Eagerly, Prefs())
    val apps = MutableStateFlow<List<AppInfo>>(emptyList())
    /** Latest still of the nav / player app, shown in its panel. */
    val previews = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val lastCap = HashMap<String, Long>()
    val media = MediaRepo(app)
    val radio: ImotorRadioController = DefaultImotorRadioBridge(app)
    val audio = AudioFx(app)
    val illumination = IlluminationMonitor(app)
    /** Button LEDs lit (true), off (false) or unknown (null). */
    val lit: StateFlow<Boolean?> = illumination.state.map { it.on }.stateIn(scope, SharingStarted.Eagerly, null)

    var drawer by mutableStateOf(false)
    var tools by mutableStateOf(false)
    var radioScreen by mutableStateOf(false)         // full radio section opened from the radio tile
    var settings by mutableStateOf(false)
    var menuApp by mutableStateOf<AppInfo?>(null)
    var screen by mutableStateOf<NativeApp?>(null)
    var clockScreen by mutableStateOf(false)         // flip clock + calendar, opened from the dock clock
    var hideManager by mutableStateOf(false)          // apps in front of which the dock hides
    var manager by mutableStateOf(false)              // drawer apps: visibility + custom order
    var colorPicker by mutableStateOf(false)         // custom accent colour dialog
    var picker by mutableStateOf<PickerKind?>(null)  // app chooser (navigator / player)
    var overlayInWindow by mutableStateOf(false)   // panels hosted by the overlay service (above freeform apps)
    var dockInOverlay by mutableStateOf(false)     // dock hosted by the overlay service: the in-activity dock stays blank
    var restoreTick by mutableIntStateOf(0)        // bumped on wake: HomeScreen re-places embedded windows
    var gpsTick by mutableIntStateOf(0)            // bumped when the location permission answer comes back

    init {
        reloadApps()
        scope.launch(Dispatchers.IO) { while (isActive) { delay(3_000); runCatching { previewTick() } } }
        // Icons are rasterised once; afterwards only a package change refreshes the list.
        val pkgFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { reloadJob?.cancel(); reloadJob = scope.launch { delay(800); reloadApps() } }
        }, pkgFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The LED monitor runs only while an AUTO mode needs it (or the settings panel shows its diagnostics).
        scope.launch {
            combine(
                prefs.map { Triple(it.theme == ThemeMode.AUTO || it.dimMode == DimMode.AUTO, it.ledSource, it.ledInvert) }.distinctUntilChanged(),
                snapshotFlow { settings },
            ) { (need, src, inv), open -> Triple(need || open, src, inv) }.distinctUntilChanged()
                .collect { (run, src, inv) -> if (run) illumination.start(src, inv) else illumination.stop() }
        }
    }

    // Last measured panel windows (screen px), used to open an app straight into its panel.
    var navRect by mutableStateOf<Rect?>(null)
    var mediaRect by mutableStateOf<Rect?>(null)
    var homeVisible by mutableStateOf(false)       // launcher activity on screen

    suspend fun loadedPrefs(): Prefs = store.prefs.first()

    private var reloadJob: Job? = null

    private fun previewTick() {
        val p = prefs.value
        val pkgs = listOfNotNull(p.navPkg, p.mediaPkg.takeIf { p.mediaMode == MediaMode.EMBED })
        if (pkgs.isEmpty()) return
        for (pkg in pkgs) if (pkg !in previews.value) Previews.load(app, pkg)?.let { b -> previews.update { it + (pkg to b.asImageBitmap()) } }
        if (!p.previews) return
        val fg = Previews.foreground(app)?.takeIf { it in pkgs } ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastCap[fg] ?: 0L) < 10_000) return
        lastCap[fg] = now
        val b = Previews.capture(app) ?: return
        previews.update { it + (fg to b.asImageBitmap()) }
        Previews.save(app, fg, b)
    }
    fun reloadApps() { loadJob?.cancel(); loadJob = scope.launch { apps.value = AppRepo.load(app) } }

    fun <V> set(key: Preferences.Key<V>, value: V) { scope.launch { store.set(key, value) } }
    fun setEnum(key: Preferences.Key<String>, value: Enum<*>) { scope.launch { store.setEnum(key, value) } }

    fun togglePin(pkg: String) { scope.launch { store.toggleIn(K.pinned, pkg) } }

    fun toggleDockHide(pkg: String) {
        // stored as plain package names: one drawer entry per launcher activity ("pkg/Activity") all map to the same app
        val id = pkg.substringBefore('/'); val cur = prefs.value.dockHideApps
        set(K.dockHideApps, (if (id in cur) cur - id else cur + id).joinToString(","))
    }

    fun toggleHidden(pkg: String) { scope.launch { store.toggleIn(K.hidden, pkg) } }

    /** Moves [pkg] inside the custom order (which first absorbs the current visible order); [delta] = null sends it to the top. */
    fun moveInOrder(pkg: String, delta: Int?) {
        val list = orderedApps(apps.value, prefs.value.copy(sort = AppSort.CUSTOM), includeHidden = true).map { it.pkg }.toMutableList()
        val i = list.indexOf(pkg).takeIf { it >= 0 } ?: return
        val j = if (delta == null) 0 else (i + delta).coerceIn(0, list.lastIndex)
        list.add(j, list.removeAt(i))
        set(K.order, list.joinToString(","))
    }

    /** Switches the drawer to custom order, seeded with the order currently on screen so nothing jumps. */
    fun enableCustomOrder() {
        val p = prefs.value
        if (p.sort == AppSort.CUSTOM) return
        val order = orderedApps(apps.value, p, includeHidden = true).map { it.pkg }
        scope.launch { store.set(K.order, order.joinToString(",")); store.setEnum(K.sort, AppSort.CUSTOM) }
    }

    // Launch counters live in memory and reach the DataStore a few seconds later, so launching an app never triggers a Prefs emission.
    private var usageMem: MutableMap<String, Int>? = null
    private var usageJob: Job? = null

    fun recordUse(pkg: String) {
        val u = usageMem ?: prefs.value.usage.toMutableMap().also { usageMem = it }
        u[pkg] = (u[pkg] ?: 0) + 1
        usageJob?.cancel()
        usageJob = scope.launch { delay(5_000); store.set(K.usage, u.entries.joinToString(",") { "${it.key}=${it.value}" }) }
    }

    fun cyclePanels() = setEnum(K.panels, when (prefs.value.panels) {
        Panels.BOTH -> Panels.NAV
        Panels.NAV -> Panels.MEDIA
        Panels.MEDIA -> Panels.BOTH
    })

    fun hasOverlay() = drawer || tools || radioScreen || settings || menuApp != null || picker != null || colorPicker || manager || hideManager || screen != null || clockScreen

    fun closeOverlays() { drawer = false; tools = false; radioScreen = false; settings = false; menuApp = null; picker = null; colorPicker = false; manager = false; hideManager = false; screen = null; clockScreen = false }

    /** Dark overlay on/off for the current preferences and LED state. */
    fun dimOn(p: Prefs = prefs.value, lit: Boolean? = this.lit.value) = p.dimMode == DimMode.ON || (p.dimMode == DimMode.AUTO && lit == true)

    fun pick(pkg: String) {
        when (picker) { PickerKind.NAV -> set(K.nav, pkg); PickerKind.PLAYER -> set(K.media, pkg); null -> {} }
        picker = null
    }

    /** The location prompt needs an activity: bring the launcher up and ask there. */
    fun requestLocation() {
        closeOverlays()
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_REQUEST_LOCATION, true))
    }

    class Shield(val slot: Int, val pkg: String, val rect: Rect)

    /** Panels whose hosted app window must be covered by a touch shield (the panel is a preview: a tap opens the app full screen). */
    fun shields(): List<Shield> {
        val p = prefs.value
        if (!p.embedWindows || !Embedder.freeformEnabled(app) || !homeVisible || hasOverlay()) return emptyList()
        return buildList {
            if (p.panels != Panels.MEDIA) p.navPkg?.let { pkg -> navRect?.let { add(Shield(0, pkg, it)) } }
            if (p.panels != Panels.NAV && p.mediaMode == MediaMode.EMBED) p.mediaPkg?.let { pkg -> mediaRect?.let { add(Shield(1, pkg, it)) } }
        }
    }

    /** Window an app should open into: its panel when it is the navigator / embedded player, else null (full screen). */
    fun boundsFor(pkg: String): Rect? {
        val p = prefs.value
        if (!p.embedWindows) return null
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

    /** Stores the tuned frequency in the first free slot (no-op when already saved or the list is full). */
    fun saveCurrentPreset() {
        val cur = radio.state.value.freqKhz
        val list = prefs.value.presets.toMutableList()
        if (cur <= 0 || cur in list) return
        val i = list.indexOf(0)
        if (i < 0) return
        list[i] = cur
        setPresets(list)
    }

    fun clearPreset(slot: Int) {
        val list = prefs.value.presets.toMutableList()
        if (slot in list.indices) { list[slot] = 0; setPresets(list) }
    }

    fun importStockPresets() {
        val s = radio.state.value.stockPresets
        if (s.isNotEmpty()) setPresets(List(PRESET_SLOTS) { s.getOrElse(it) { 0 } })
    }

    /** Steering wheel NEXT/PREV while the radio panel is active: walk the saved presets, or seek when there are none. */
    fun steerRadio(next: Boolean) {
        val p = prefs.value
        val saved = p.presets.filter { it > 0 }
        if (p.swcSeek || saved.isEmpty() || !radio.canTune) { if (next) radio.seekUp() else radio.seekDown(); return }
        val idx = saved.indexOf(radio.state.value.freqKhz)
        val target = if (idx < 0) (if (next) saved.first() else saved.last())
        else saved[(idx + (if (next) 1 else -1) + saved.size) % saved.size]
        radio.setFrequency(target)
    }

    companion object { const val PRESET_SLOTS = 20 }
}
