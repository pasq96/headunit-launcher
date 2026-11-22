package com.pasq96.headunit.radio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
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
 * the platform RadioManager (reflection, see FrameworkRadio) with the RadioService actions as fallback.
 */
class DefaultImotorRadioBridge(context: Context) : ImotorRadioController {
    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RadioState())
    override val state: StateFlow<RadioState> = _state.asStateFlow()
    private val fw = FrameworkRadio(ctx)
    override val canTune = true
    private var job: Job? = null
    @Volatile private var proc: Process? = null
    @Volatile private var lastSeen = 0L
    private var watchdog: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        scope.launch { runCatching { fw.connect() }; while (isActive) { runCatching { poll() }; delay(700) } }
        job = scope.launch {
            while (isActive) { runCatching { readLog() }; delay(5_000) }
        }
        watchdog = scope.launch {
            while (isActive) {
                delay(2_000)
                if (_state.value.live && SystemClock.elapsedRealtime() - lastSeen > LIVE_MS) _state.value = _state.value.copy(live = false)
            }
        }
    }

    override fun stop() {
        job?.cancel(); job = null
        watchdog?.cancel(); watchdog = null
        proc?.destroy(); proc = null
    }

    /** Blocks on the filtered logcat stream until the process ends (stop() destroys it). */
    private fun readLog() {
        val p = ProcessBuilder("logcat", "-T", "1", "-s", "$LOG_TAG:D", "RadioControlProxy:D", "RadioControl:I").redirectErrorStream(true).start()
        proc = p
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) parse(line)
        }
    }

    private fun parse(line: String) {
        RE_STATION.find(line)?.let { m -> bandOf(m.groupValues[2].toInt()); apply(m.groupValues[1].toInt()); return }
        RE_WORK.find(line)?.let { m -> touch { copy(scanning = m.groupValues[1] == "1") }; return }
        RE_LIST.find(line)?.let { m -> presets(m.groupValues[1]); return }
        RE_STATUS.find(line)?.let { m ->
            val g = m.groupValues
            touch { copy(ta = g[1] == "true", af = g[2] == "true", stereoOn = g[3] == "true", loc = g[4] == "true") }
        }
        RE_FREQ.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { apply(it); return }
        RE_PS.find(line)?.let { m -> touch { copy(ps = m.groupValues[1].trim().ifEmpty { null }) }; return }
        RE_ST.find(line)?.let { m -> touch { copy(stereo = m.groupValues[1] == "true") } }
        RE_RT.find(line)?.let { m -> touch { copy(ps = m.groupValues[1].trim().ifEmpty { null }, rt = m.groupValues[2].trim().ifEmpty { null }) } }
    }

    /** Direct state read from the manager: works without READ_LOGS and keeps the panel live while the stock app is closed. */
    private fun poll() {
        val f = (fw.get("getCurrentStation", "getFreq") as? Int)?.takeIf { it > 0 } ?: return
        (fw.get("getCurrentStation", "getBand") as? Int)?.let { bandOf(it) }
        apply(f)
        val st = fw.get("getRadioStatus") ?: return
        fun flag(n: String) = runCatching { st.javaClass.getMethod(n).invoke(st) as? Boolean }.getOrNull()
        val ps = runCatching { st.javaClass.getMethod("getPsName").invoke(st) as? String }.getOrNull()?.trim()
        touch {
            copy(ta = flag("getStatusTA") ?: ta, af = flag("getStatusAF") ?: af, stereoOn = flag("getStatusST") ?: stereoOn,
                loc = flag("getStatusFMLOC") ?: loc, stereo = flag("getStereo") ?: stereo, ps = if (ps.isNullOrEmpty()) this.ps else ps)
        }
    }

    private fun bandOf(i: Int) { touch { copy(band = when (i) { 1 -> Band.FM1; 2 -> Band.FM2; else -> band }) } }

    /** The radio's own station memory, FM only (tens of kHz); the factory default list is ignored while it is all band edges. */
    private fun presets(csv: String) {
        val l = csv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 8_750..10_800 }.map { it * 10 }.distinct().take(8)
        if (l.isNotEmpty()) touch { copy(stockPresets = l) }
    }

    private fun touch(f: RadioState.() -> RadioState) { lastSeen = SystemClock.elapsedRealtime(); _state.value = _state.value.f().copy(connected = true, live = true) }

    /** FM comes as tens of kHz (9540 = 95.40 MHz), AM as kHz. */
    private fun apply(raw: Int) {
        val fm = raw in 6_500..10_800
        touch {
            // a new station invalidates the old RDS name until the next report
            val khz = if (fm) raw * 10 else raw
            copy(freqKhz = khz, ps = if (khz != freqKhz) null else ps, rt = if (khz != freqKhz) null else rt, band = if (!fm) Band.AM else if (band == Band.AM) Band.FM1 else band)
        }
    }

    // ---- commands: the firmware RadioManager, the same calls the stock app makes ----

    private fun run(block: () -> Unit) { scope.launch { runCatching { if (!fw.ready) fw.connect(); block() } } }
    private fun code(b: Band) = when (b) { Band.FM1 -> 1; Band.FM2 -> 2; Band.AM -> 3 }

    override fun step(up: Boolean, stepKhz: Int) = run { fw.call(if (up) "fineTuneNext" else "fineTunePrev") }
    override fun seekUp() = run { fw.call("seekToNext") }
    override fun seekDown() = run { fw.call("seekToPrev") }
    override fun nextStation() = walk(true)
    override fun previousStation() = walk(false)

    /** Next/previous entry of the radio's station memory; plain seek when it is empty. */
    private fun walk(next: Boolean) {
        val l = _state.value.stockPresets.sorted()
        val cur = _state.value.freqKhz
        val t = if (l.isEmpty()) null else if (next) l.firstOrNull { it > cur } ?: l.first() else l.lastOrNull { it < cur } ?: l.last()
        if (t != null) setFrequency(t) else if (next) seekUp() else seekDown()
    }

    /** FM goes out in tens of kHz (9745 = 97.45 MHz), AM in kHz; index -1 = not a memory slot. */
    override fun setFrequency(khz: Int) = run {
        val b = _state.value.band
        fw.call("play", code(b), if (b == Band.AM) khz else khz / 10, -1)
    }

    override fun setBand(band: Band) = run { fw.call("switchWaveband", code(band)) }
    override fun toggleBand() = run { fw.call("switchWaveband", code(Band.entries[(_state.value.band.ordinal + 1) % Band.entries.size])) }
    override fun toggleMute() = Unit
    override fun toggleAf() = run { fw.call("switchAf") }
    override fun toggleTa() = run { fw.call("switchTa") }
    override fun toggleStereo() = run { fw.call("switchStereo") }
    override fun toggleLoc() = run { fw.call("toggleLOC") }
    override fun autoScan() = run { fw.call("scanAS") }
    override fun stopSeek() = run { fw.call("stopWork") }

    override fun openStockApp(): Boolean = Embedder.launch(ctx, PKG)

    private fun cmd(action: String) {
        val r = runCatching { ctx.startService(Intent(action).setComponent(ComponentName(PKG, SERVICE))) }
        Log.w("HURadio", "$action -> ${r.getOrNull()} ${r.exceptionOrNull() ?: ""}") // read with: logcat -d -s HURadio
    }

    private companion object {
        const val PKG = "com.imotor.fmam"
        const val SERVICE = "com.imotor.radiosupport.radio.RadioService"
        const val LOG_TAG = "VehicleHelperService"
        const val NEXT = "com.imotor.radio.ACITON_NEXT_STATION" // sic: the app's own spelling
        const val PREV = "com.imotor.radio.ACTION_PREV_STATION"
        const val BAND = "com.imotor.radio.ACTION_SWITCH_BAND"
        const val LIVE_MS = 4_000L
        val RE_STATION = Regex("RadioControl: +---> onStationChanged: StationInfo \\{freq=(\\d+), band=(\\d)")
        val RE_WORK = Regex("WorkInfo \\{workMode=\\d+, seekType=\\d+, seekStatus=(\\d)")
        val RE_LIST = Regex("FM list: \\[([^\\]]*)\\]")
        val RE_STATUS = Regex("mStatusTA:(\\w+),.*?mStatusAF:(\\w+),.*?mStatusST:(\\w+),mStatusFMLOC:(\\w+)")
        val RE_FREQ = Regex("setRadioInfo:(\\d+)")
        val RE_PS = Regex("PS name:(.*?)\\s+tp:")
        val RE_ST = Regex("mStereo:(true|false)")
        val RE_RT = Regex("mPsName:(.*?),mRt:(.*?),\\}")
    }
}
