package com.pasq96.headunit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pasq96.headunit.embed.Embedder
import com.pasq96.headunit.tools.CarTools
import com.pasq96.headunit.ui.HomeScreen
import com.pasq96.headunit.ui.HomeViewModel
import com.pasq96.headunit.ui.OverlayLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// This launcher never requests audio focus and never plays sounds: radio, music and Vela voice prompts keep priority.
class MainActivity : ComponentActivity() {
    private val vm by viewModels<HomeViewModel>()
    private var overlayView: ComposeView? = null
    private var wakeJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_ON) onWake() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.isSoundEffectsEnabled = false
        setContent { HomeScreen(vm) }

        // Back at the root does nothing (it would finish the HOME activity); with panels open it closes them.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { vm.closeOverlays() }
        })

        // ACC ON/OFF: head units suspend-to-RAM, so SCREEN_ON is the "ignition on" signal (dynamic registration only).
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)

        // The dim overlay is a window of this process: re-apply it whenever the setting changes or the launcher restarts.
        lifecycleScope.launch {
            vm.prefs.map { it.night to it.nightAlpha }.distinctUntilChanged().collect { (on, a) -> CarTools.setNightOverlay(this@MainActivity, on, a) }
        }
        // Drawer/tools/settings live in an overlay window while apps are embedded, otherwise the freeform windows would cover them.
        lifecycleScope.launch { snapshotFlow { vm.hasOverlay() }.collect { syncOverlayWindow(it) } }

        if (savedInstanceState == null) autoStart()
    }

    override fun onStart() {
        super.onStart()
        vm.media.start()
        vm.reloadApps()
        syncOverlayWindow(vm.hasOverlay())
    }

    override fun onStop() {
        syncOverlayWindow(false) // never leave an overlay window above other apps (e.g. Android settings)
        vm.media.stop()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.closeOverlays() // HOME pressed: back to the plain split view
    }

    // Steering-wheel keys: transport keys go to the active MediaSession once; everything else (volume, home, other
    // media keys with no session) is never consumed, so the system / com.imotor keeps handling it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in TRANSPORT_KEYS && vm.media.dispatchKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun syncOverlayWindow(open: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        val want = open && Settings.canDrawOverlays(this) && Embedder.canEmbed(this)
        if (want && overlayView == null) {
            val v = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@MainActivity)
                setViewTreeSavedStateRegistryOwner(this@MainActivity)
                setViewTreeViewModelStoreOwner(this@MainActivity)
                isSoundEffectsEnabled = false
                setContent { OverlayLayer(vm) }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE: hardware keys keep reaching the activity / media session.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            if (runCatching { wm.addView(v, lp) }.isSuccess) { overlayView = v; vm.overlayInWindow = true }
        } else if (!want && overlayView != null) {
            runCatching { wm.removeView(overlayView) }
            overlayView = null
            vm.overlayInWindow = false
        }
    }

    // Boot resume: navigator first (embeddable setups are placed by HomeScreen), then KEYCODE_MEDIA_PLAY.
    private fun autoStart() = lifecycleScope.launch {
        val p = vm.loadedPrefs()
        if (p.autoStart) {
            delay(p.autoStartDelaySec * 1000L)
            if (!Embedder.canEmbed(this@MainActivity)) p.navPkg?.let { Embedder.launch(this@MainActivity, it) }
        }
        if (p.autoPlay) {
            delay(p.autoPlayDelaySec * 1000L)
            CarTools.sendMediaPlay(this@MainActivity)
        }
    }

    // Wake from suspend: wait for the system to settle, then rebuild what the sleep may have dropped.
    private fun onWake() {
        wakeJob?.cancel()
        wakeJob = lifecycleScope.launch {
            val p = vm.loadedPrefs()
            delay(p.wakeDelaySec * 1000L)
            CarTools.setNightOverlay(this@MainActivity, p.night, p.nightAlpha)
            vm.media.start()
            if (!p.resumeOnWake) return@launch
            vm.restoreTick++ // HomeScreen re-launches nav/media into their windows (also revives dead hosted tasks)
            if (p.autoStart && !Embedder.canEmbed(this@MainActivity)) p.navPkg?.let { Embedder.launch(this@MainActivity, it) }
            if (p.autoPlay) {
                delay(p.autoPlayDelaySec * 1000L)
                CarTools.sendMediaPlay(this@MainActivity)
            }
        }
    }

    private companion object {
        val TRANSPORT_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        )
    }
}
