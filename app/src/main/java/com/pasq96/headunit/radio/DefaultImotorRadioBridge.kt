package com.pasq96.headunit.radio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.pasq96.headunit.embed.Embedder
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

/**
 * Bridge to the stock iMotor radio (com.imotor.fmam). The app publishes no state broadcasts: the tuned frequency is read
 * from the system log line `VehicleHelperService: setRadioInfo:<n>` (needs READ_LOGS, granted over adb) and commands are
 * the actions its RadioService accepts. Direct tuning, mute, AF/TA and presets are not exposed by the app.
 */
class DefaultImotorRadioBridge(context: Context) : ImotorRadioController {
    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RadioState())
    override val state: StateFlow<RadioState> = _state.asStateFlow()
    override val canTune = false
    private var job: Job? = null
    @Volatile private var proc: Process? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) { runCatching { readLog() }; delay(5_000) }
        }
    }

    override fun stop() {
        job?.cancel(); job = null
        proc?.destroy(); proc = null
    }

    /** Blocks on the filtered logcat stream until the process ends (stop() destroys it). */
    private fun readLog() {
        val p = ProcessBuilder("logcat", "-T", "1", "-s", "$LOG_TAG:D").redirectErrorStream(true).start()
        proc = p
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) RE.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let(::apply)
        }
    }

    /** FM comes as tens of kHz (9540 = 95.40 MHz), AM as kHz. */
    private fun apply(raw: Int) {
        val fm = raw in 6_500..10_800
        val s = _state.value
        _state.value = s.copy(connected = true, freqKhz = if (fm) raw * 10 else raw, band = if (!fm) Band.AM else if (s.band == Band.AM) Band.FM1 else s.band)
    }

    // ---- commands: the service has no seek/step distinction, next/previous station is the closest match ----

    override fun nextStation() = cmd(NEXT)
    override fun previousStation() = cmd(PREV)
    override fun seekUp() = cmd(NEXT)
    override fun seekDown() = cmd(PREV)
    override fun toggleBand() = cmd(BAND)
    override fun setBand(band: Band) { if (band != _state.value.band) cmd(BAND) }
    override fun step(up: Boolean, stepKhz: Int) = Unit
    override fun setFrequency(khz: Int) = Unit
    override fun toggleMute() = Unit
    override fun toggleAf() = Unit
    override fun toggleTa() = Unit

    override fun openStockApp(): Boolean = Embedder.launch(ctx, PKG)

    private fun cmd(action: String) {
        runCatching { ctx.startService(Intent(action).setComponent(ComponentName(PKG, SERVICE))) }
    }

    private companion object {
        const val PKG = "com.imotor.fmam"
        const val SERVICE = "com.imotor.radiosupport.radio.RadioService"
        const val LOG_TAG = "VehicleHelperService"
        const val NEXT = "com.imotor.radio.ACITON_NEXT_STATION" // sic: the app's own spelling
        const val PREV = "com.imotor.radio.ACTION_PREV_STATION"
        const val BAND = "com.imotor.radio.ACTION_SWITCH_BAND"
        val RE = Regex("setRadioInfo:(\\d+)")
    }
}
