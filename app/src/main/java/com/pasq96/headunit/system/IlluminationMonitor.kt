package com.pasq96.headunit.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.pasq96.headunit.data.LedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import com.pasq96.headunit.system.IlluminationContract as C

class LightReading(val on: Boolean? = null, val source: String? = null)

/** Button-LED state from broadcast, system setting and/or sysfs. Idle (no receivers, no polling) until [start]. */
class IlluminationMonitor(context: Context) {
    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LightReading())
    val state: StateFlow<LightReading> = _state.asStateFlow()

    private var invert = false
    private var running = false
    private var receiverOn = false
    private var poll: Job? = null

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) { publish(C.parse(i.extras?.get(C.EXTRA_ON)), "broadcast") }
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { readSettings() }
    }

    @Synchronized
    fun start(source: LedSource, invertSignal: Boolean) {
        stop()
        invert = invertSignal
        running = true
        if (source == LedSource.AUTO || source == LedSource.BROADCAST) {
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(C.ACTION_ILLUMINATION), ContextCompat.RECEIVER_EXPORTED)
            receiverOn = true
        }
        if (source == LedSource.AUTO || source == LedSource.SETTING) {
            for (k in C.SETTING_KEYS) for (uri in uris(k)) runCatching { ctx.contentResolver.registerContentObserver(uri, false, observer) }
            readSettings()
        }
        if (source == LedSource.AUTO || source == LedSource.SYSFS) {
            poll = scope.launch { while (isActive) { readSysfs(); delay(C.POLL_MS) } }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        if (receiverOn) runCatching { ctx.unregisterReceiver(receiver) }
        receiverOn = false
        runCatching { ctx.contentResolver.unregisterContentObserver(observer) }
        poll?.cancel(); poll = null
    }

    private fun uris(key: String): List<Uri> = listOf(Settings.System.getUriFor(key), Settings.Global.getUriFor(key), Settings.Secure.getUriFor(key))

    private fun readSettings() {
        val cr = ctx.contentResolver
        for (k in C.SETTING_KEYS) {
            val raw = runCatching { Settings.System.getString(cr, k) }.getOrNull()
                ?: runCatching { Settings.Global.getString(cr, k) }.getOrNull()
                ?: runCatching { Settings.Secure.getString(cr, k) }.getOrNull()
            C.parse(raw)?.let { publish(it, "setting:$k"); return }
        }
    }

    private fun readSysfs() {
        for (path in C.SYSFS_PATHS) {
            val v = runCatching { File(path).readText() }.getOrNull() ?: continue
            C.parse(v)?.let { publish(it, "sysfs:$path"); return }
        }
    }

    private fun publish(on: Boolean?, source: String) {
        if (on == null) return
        _state.value = LightReading(if (invert) !on else on, source)
    }
}
