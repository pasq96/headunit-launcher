package com.pasq96.headunit.ui

import android.app.Application
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pasq96.headunit.data.AppInfo
import com.pasq96.headunit.data.AppRepo
import com.pasq96.headunit.data.K
import com.pasq96.headunit.data.Panels
import com.pasq96.headunit.data.Prefs
import com.pasq96.headunit.data.SettingsStore
import com.pasq96.headunit.media.MediaRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    val prefs: StateFlow<Prefs> = store.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, Prefs())
    val apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val media = MediaRepo(app)

    var drawer by mutableStateOf(false)
    var tools by mutableStateOf(false)
    var settings by mutableStateOf(false)
    var menuApp by mutableStateOf<AppInfo?>(null)
    var overlayInWindow by mutableStateOf(false)   // panels drawn in an overlay window (above freeform apps)
    var restoreTick by mutableIntStateOf(0)        // bumped on wake: HomeScreen re-places embedded windows

    // Last measured panel windows (screen px), used to open an app straight into its panel.
    var navRect: Rect? = null
    var mediaRect: Rect? = null

    suspend fun loadedPrefs(): Prefs = store.prefs.first()

    fun reloadApps() { viewModelScope.launch { apps.value = AppRepo.load(getApplication()) } }

    fun <V> set(key: Preferences.Key<V>, value: V) { viewModelScope.launch { store.set(key, value) } }
    fun setEnum(key: Preferences.Key<String>, value: Enum<*>) { viewModelScope.launch { store.setEnum(key, value) } }

    fun togglePin(pkg: String) {
        val cur = prefs.value.pinned
        viewModelScope.launch { store.setPinned(if (pkg in cur) cur - pkg else cur + pkg) }
    }

    fun cyclePanels() = setEnum(K.panels, when (prefs.value.panels) {
        Panels.BOTH -> Panels.NAV
        Panels.NAV -> Panels.MEDIA
        Panels.MEDIA -> Panels.BOTH
    })

    fun hasOverlay() = drawer || tools || settings || menuApp != null

    fun closeOverlays() { drawer = false; tools = false; settings = false; menuApp = null }

    /** Window an app should open into: its panel when it is the navigator / embedded player, else null (full screen). */
    fun boundsFor(pkg: String): Rect? {
        val p = prefs.value
        return when {
            pkg == p.navPkg && p.panels != Panels.MEDIA -> navRect
            pkg == p.mediaPkg && p.mediaMode == com.pasq96.headunit.data.MediaMode.EMBED && p.panels != Panels.NAV -> mediaRect
            else -> null
        }
    }

    override fun onCleared() { media.stop() }
}
