package com.pasq96.headunit

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.snapshotFlow
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pasq96.headunit.embed.Embedder
import com.pasq96.headunit.tools.CarTools
import com.pasq96.headunit.ui.HomeScreen
import com.pasq96.headunit.ui.LauncherHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// This launcher never requests audio focus and never plays sounds: radio, music and Vela voice prompts keep priority.
class MainActivity : ComponentActivity() {
    private var overlayAsked = false
    private val vm: LauncherHub get() = (application as HeadUnitApp).hub
    private var wakeJob: Job? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.gpsTick++ }

    private val phonePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.phoneTick++ }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.storageTick++ }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_ON) onWake() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        val t0 = SystemClock.uptimeMillis()
        // hold the splash until the app list is ready (capped), so the home never pops in half-built
        splash.setKeepOnScreenCondition { vm.apps.value.isEmpty() && SystemClock.uptimeMillis() - t0 < 1500 }
        splash.setOnExitAnimationListener { v ->
            v.view.animate().alpha(0f).setDuration(220).withEndAction { v.remove() }.start()
        }
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
            combine(vm.prefs.map { it.dimMode to it.nightAlpha }.distinctUntilChanged(), vm.lit) { (mode, a), lit -> vm.dimOn(vm.prefs.value.copy(dimMode = mode), lit) to a }
                .distinctUntilChanged().collect { (on, a) -> CarTools.setNightOverlay(this@MainActivity, on, a) }
        }
        lifecycleScope.launch { vm.prefs.map { it.dockOverlay }.distinctUntilChanged().collect { OverlayService.sync(this@MainActivity) } }

        // Inline panels end up behind freeform apps: without the overlay permission ask for it once per run.
        lifecycleScope.launch {
            snapshotFlow { vm.hasOverlay() && !vm.overlayInWindow }.collect { inline ->
                if (inline && !overlayAsked && Embedder.freeformEnabled(this@MainActivity) && !Settings.canDrawOverlays(this@MainActivity)) {
                    overlayAsked = true
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                }
            }
        }

        handleIntent(intent)
        if (!vm.bootHandled) { vm.bootHandled = true; autoStart() }
    }

    override fun onStart() {
        super.onStart()
        vm.homeVisible = true
        vm.restoreTick++ // coming back from another app: put hosted windows back in their panels
        vm.media.start()
        vm.radio.start()
        OverlayService.sync(this) // permissions may have changed while the user was in system settings
    }

    override fun onStop() {
        vm.homeVisible = false
        vm.media.stop()
        vm.radio.stop()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.closeOverlays() // HOME pressed: back to the plain split view
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent) {
        if (i.getBooleanExtra(EXTRA_REQUEST_STORAGE, false)) {
            i.removeExtra(EXTRA_REQUEST_STORAGE)
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (i.getBooleanExtra(EXTRA_REQUEST_PHONE, false)) {
            i.removeExtra(EXTRA_REQUEST_PHONE)
            phonePermission.launch(com.pasq96.headunit.phone.PhoneData.PERMS)
        }
        if (i.getBooleanExtra(EXTRA_REQUEST_LOCATION, false)) {
            i.removeExtra(EXTRA_REQUEST_LOCATION)
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // Steering-wheel keys. With the native radio panel active NEXT/PREV walk the presets (or seek) and are consumed;
    // otherwise transport keys go to the active MediaSession once. Everything else (volume, home, other keys, or media
    // keys with no session) is never consumed, so the system / com.imotor keeps handling it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) && vm.radioActive()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.steerRadio(code == KeyEvent.KEYCODE_MEDIA_NEXT)
            return true
        }
        if (code in TRANSPORT_KEYS && vm.media.dispatchKey(event)) return true
        return super.dispatchKeyEvent(event)
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
            CarTools.setNightOverlay(this@MainActivity, vm.dimOn(), p.nightAlpha)
            OverlayService.sync(this@MainActivity)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { vm.media.start(); vm.radio.start() }
            if (!p.resumeOnWake) return@launch
            vm.restoreTick++ // HomeScreen re-launches nav/media into their windows (also revives dead hosted tasks)
            if (p.autoStart && !Embedder.canEmbed(this@MainActivity)) p.navPkg?.let { Embedder.launch(this@MainActivity, it) }
            if (p.autoPlay) {
                delay(p.autoPlayDelaySec * 1000L)
                CarTools.sendMediaPlay(this@MainActivity)
            }
        }
    }

    companion object {
        const val EXTRA_REQUEST_LOCATION = "request_location"
        const val EXTRA_REQUEST_STORAGE = "request_storage"
        const val EXTRA_REQUEST_PHONE = "request_phone"
        private val TRANSPORT_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        )
    }
}
