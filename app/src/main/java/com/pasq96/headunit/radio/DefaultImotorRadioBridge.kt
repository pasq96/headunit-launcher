package com.pasq96.headunit.radio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.pasq96.headunit.embed.Embedder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.pasq96.headunit.radio.ImotorRadioContract as C

/** Broadcast-based bridge: state is pushed by the radio (no polling), commands are plain broadcasts. */
class DefaultImotorRadioBridge(context: Context) : ImotorRadioController {
    private val ctx = context.applicationContext
    private val _state = MutableStateFlow(RadioState())
    override val state: StateFlow<RadioState> = _state.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            runCatching { when (i.action) { C.Action.STATE -> applyState(i); C.Action.PRESETS -> applyPresets(i) } }
        }
    }

    override fun start() {
        if (registered) return
        val f = IntentFilter(C.Action.STATE).apply { addAction(C.Action.PRESETS) }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        send(C.Action.QUERY)
    }

    override fun stop() {
        if (!registered) return
        runCatching { ctx.unregisterReceiver(receiver) }
        registered = false
    }

    // ---- commands ----

    override fun nextStation() = send(C.Action.NEXT)
    override fun previousStation() = send(C.Action.PREV)
    override fun seekUp() = send(C.Action.SEEK_UP)
    override fun seekDown() = send(C.Action.SEEK_DOWN)
    override fun toggleBand() = send(C.Action.TOGGLE_BAND)
    override fun toggleAf() = send(C.Action.TOGGLE_AF)
    override fun toggleTa() = send(C.Action.TOGGLE_TA)

    override fun setBand(band: Band) = send(C.Action.SET_BAND) { putExtra(C.Extra.BAND, C.encodeBand(band)) }

    override fun setFrequency(khz: Int) = send(C.Action.SET_FREQ) {
        when (val v = C.encodeFreq(khz)) { is Int -> putExtra(C.Extra.FREQ, v); is Float -> putExtra(C.Extra.FREQ, v) }
    }

    /** Moves by [stepKhz] inside the band limits, wrapping around the ends. */
    override fun step(up: Boolean, stepKhz: Int) {
        val s = _state.value
        if (s.freqKhz <= 0) return
        val (lo, hi) = if (s.band == Band.AM) AM_RANGE else FM_RANGE
        val n = s.freqKhz + if (up) stepKhz else -stepKhz
        setFrequency(if (n > hi) lo else if (n < lo) hi else n)
    }

    override fun toggleMute() {
        val target = !_state.value.muted
        _state.value = _state.value.copy(muted = target) // optimistic: the radio confirms with the next STATE
        send(C.Action.MUTE) { putExtra(C.Extra.MUTE, target) }
    }

    override fun openStockApp(): Boolean {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName.startsWith(C.PACKAGE_PREFIX) }
        val radio = apps.firstOrNull { r ->
            val text = (r.activityInfo.packageName + " " + r.loadLabel(pm)).lowercase()
            C.STOCK_APP_HINTS.any { it in text }
        } ?: return false
        return Embedder.launch(ctx, radio.activityInfo.packageName)
    }

    // ---- state from the radio: only the extras that are present are applied ----

    @Suppress("DEPRECATION")
    private fun applyState(i: Intent) {
        val e = i.extras ?: return
        var s = _state.value.copy(connected = true)
        if (e.containsKey(C.Extra.BAND)) C.decodeBand(e.get(C.Extra.BAND))?.let { s = s.copy(band = it) }
        if (e.containsKey(C.Extra.FREQ)) s = s.copy(freqKhz = C.decodeFreqKhz(e.get(C.Extra.FREQ)))
        if (e.containsKey(C.Extra.PS)) s = s.copy(ps = e.getString(C.Extra.PS)?.trim()?.ifEmpty { null })
        if (e.containsKey(C.Extra.RT)) s = s.copy(rt = e.getString(C.Extra.RT)?.trim()?.ifEmpty { null })
        if (e.containsKey(C.Extra.MUTE)) C.decodeBool(e.get(C.Extra.MUTE))?.let { s = s.copy(muted = it) }
        if (e.containsKey(C.Extra.AF)) C.decodeBool(e.get(C.Extra.AF))?.let { s = s.copy(af = it) }
        if (e.containsKey(C.Extra.TA)) C.decodeBool(e.get(C.Extra.TA))?.let { s = s.copy(ta = it) }
        _state.value = s
    }

    @Suppress("DEPRECATION")
    private fun applyPresets(i: Intent) {
        val raw = i.extras?.get(C.Extra.PRESETS)
        _state.value = _state.value.copy(stockPresets = C.decodePresets(raw))
    }

    private fun send(action: String, fill: Intent.() -> Unit = {}) {
        val i = Intent(action)
        C.TARGET_PACKAGE?.let { i.setPackage(it) }
        i.fill()
        runCatching { ctx.sendBroadcast(i) }
    }

    private companion object {
        val FM_RANGE = 87_500 to 108_000
        val AM_RANGE = 522 to 1_710
    }
}
