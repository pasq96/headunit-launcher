package com.pasq96.headunit.radio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import android.os.SystemClock
import com.pasq96.headunit.embed.Embedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private var logJob: Job? = null
    private var pollJob: Job? = null
    private var watchdog: Job? = null
    @Volatile private var proc: Process? = null
    @Volatile private var lastSeen = 0L

    /** The stock RadioService broadcasts this on every station/RDS change (RadioUtil.sendMediaInfo): no READ_LOGS needed. */
    private val mediaRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { runCatching { media(i) } }
    }

    private fun media(i: Intent) {
        val e = i.extras ?: return
        Log.i(TAG, "MEDIA_UPDATE " + e.keySet().joinToString { "$it=${e.get(it)}" })
        val num = Regex("\\d+(\\.\\d+)?")
        val raw = listOf("Media_Title", "Media_Artist", "Media_Album").firstNotNullOfOrNull { k ->
            val v = e.getString(k)?.trim().orEmpty()
            val d = num.find(v)?.value?.toDoubleOrNull() ?: return@firstNotNullOfOrNull null
            when {
                v.contains('.') && d in 64.0..110.0 -> Math.round(d * 100).toInt()   // "97.45" MHz -> 9745
                d in 6_500.0..10_800.0 || d in 530.0..1_710.0 -> d.toInt()           // raw FM (10 kHz) / AM (kHz)
                else -> null
            }
        }
        e.getString("Media_Album")?.trim()?.toIntOrNull()?.let { bandOf(it) }
        raw?.let { apply(it) }
        e.getString("Media_PsName")?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> touch { copy(ps = n) } }
    }

    override fun start() {
        if (pollJob?.isActive == true) return
        runCatching { ContextCompat.registerReceiver(ctx, mediaRx, IntentFilter("com.imotor.intent.MEDIA_UPDATE"), ContextCompat.RECEIVER_EXPORTED) }
        pollJob = scope.launch {
            while (isActive) {
                if (!fw.ready) runCatching { fw.connect() }
                runCatching { poll() }
                checkScanEnd()
                delay(if (_state.value.scanning) SCAN_POLL_MS else if (_state.value.live) POLL_MS else IDLE_POLL_MS)
            }
        }
        logJob = scope.launch {
            var wait = 5_000L
            while (isActive) {
                val t0 = SystemClock.elapsedRealtime()
                runCatching { readLog() }
                // logcat exits at once without READ_LOGS: back off instead of respawning every few seconds
                wait = if (SystemClock.elapsedRealtime() - t0 < 3_000) (wait * 2).coerceAtMost(60_000) else 5_000
                delay(wait)
            }
        }
        watchdog = scope.launch {
            while (isActive) {
                delay(2_000)
                if (_state.value.live && SystemClock.elapsedRealtime() - lastSeen > LIVE_MS) _state.value = _state.value.copy(live = false)
            }
        }
    }

    override fun stop() {
        runCatching { ctx.unregisterReceiver(mediaRx) }
        pollJob?.cancel(); pollJob = null
        logJob?.cancel(); logJob = null
        watchdog?.cancel(); watchdog = null
        proc?.destroy(); proc = null
    }

    /** Blocks on the filtered logcat stream until the process ends (stop() destroys it). */
    private fun readLog() {
        val p = ProcessBuilder("logcat", "-T", "1", "-s", "$LOG_TAG:D", "RadioControlProxy:D", "RadioControl:I").redirectErrorStream(true).start()
        proc = p
        try {
            p.inputStream.bufferedReader().useLines { lines -> for (line in lines) parse(line) }
        } finally { p.destroy(); if (proc === p) proc = null }
    }

    private fun parse(line: String) {
        if (line.contains("onStationChanged")) RE_STATION.find(line)?.let { m -> bandOf(m.groupValues[2].toInt()); apply(m.groupValues[1].toInt()); return }
        if (line.contains("WorkInfo")) RE_WORK.find(line)?.let { m ->
            val on = m.groupValues[1] == "1"
            if (on || SystemClock.elapsedRealtime() - scanSince > 2_000) touch { copy(scanning = on) }
            return
        }
        if (line.contains("FM list")) RE_LIST.find(line)?.let { m -> presets(m.groupValues[1]); return }
        if (line.contains("onRadioStatusChanged")) {
            RE_STATUS.find(line)?.let { m ->
                val g = m.groupValues
                touch { copy(ta = g[1] == "true", af = g[2] == "true", stereoOn = g[3] == "true", loc = g[4] == "true") }
            }
            RE_ST.find(line)?.let { m -> touch { copy(stereo = m.groupValues[1] == "true") } }
            RE_RT.find(line)?.let { m -> touch { copy(ps = m.groupValues[1].trim().ifEmpty { null }, rt = m.groupValues[2].trim().ifEmpty { null }) } }
            return
        }
        if (line.contains("PS name:")) { RE_PS.find(line)?.let { m -> touch { copy(ps = m.groupValues[1].trim().ifEmpty { null }) } }; return }
        if (line.contains("setRadioInfo")) RE_FREQ.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { apply(it) }
    }

    /** Direct state read from the manager: works without READ_LOGS and keeps the panel live while the stock app is closed. */
    private fun poll() {
        val st0 = fw.get("getCurrentStation") ?: return
        val f = (call0(st0, "getFreq") as? Int)?.takeIf { it > 0 } ?: return
        (call0(st0, "getBand") as? Int)?.let { bandOf(it) }
        apply(f)
        readList()
        val st = fw.get("getRadioStatus") ?: return
        fun flag(n: String) = call0(st, n) as? Boolean
        val ps = (call0(st, "getPsName") as? String)?.trim()
        touch {
            copy(ta = flag("getStatusTA") ?: ta, af = flag("getStatusAF") ?: af, stereoOn = flag("getStatusST") ?: stereoOn,
                loc = flag("getStatusFMLOC") ?: loc, stereo = flag("getStereo") ?: stereo, ps = if (ps.isNullOrEmpty()) this.ps else ps)
        }
    }

    private var listAt = 0L
    private var listLogged = false

    /** The radio's own station memory via getFreqList (no-arg or band-code arg; array, list or anything printing the numbers). */
    private fun readList() {
        val now = SystemClock.elapsedRealtime()
        if (now - listAt < (if (_state.value.scanning) 1_000L else LIST_MS)) return
        listAt = now
        val m = fw.manager() ?: return
        val g = m.javaClass.methods.firstOrNull { it.name == "getFreqList" && (it.parameterCount == 0 || (it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType)) } ?: return
        val raw = runCatching { if (g.parameterCount == 0) g.invoke(m) else g.invoke(m, code(_state.value.band)) }.getOrNull() ?: return
        val text = when (raw) { is IntArray -> raw.joinToString(","); is Array<*> -> raw.joinToString(","); is Collection<*> -> raw.joinToString(","); else -> raw.toString() }
        if (!listLogged) { listLogged = true; Log.i(TAG, "getFreqList -> $text") }
        presets(Regex("\\d+").findAll(text).joinToString(",") { it.value })
    }

    private val getters = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method?>()
    private fun call0(o: Any, name: String): Any? =
        runCatching { getters.getOrPut("${o.javaClass.name}#$name") { o.javaClass.getMethod(name) }?.invoke(o) }.getOrNull()

    private fun bandOf(i: Int) { touch { copy(band = when (i) { 1 -> Band.FM1; 2 -> Band.FM2; else -> band }) } }

    /** The radio's own station memory, FM only (tens of kHz); the factory default list is ignored while it is all band edges. */
    private fun presets(csv: String) {
        val l = csv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 8_750..10_800 }.map { it * 10 }.distinct().take(60)
        if (l.isNotEmpty()) touch { copy(stockPresets = l) }
    }

    private fun touch(f: RadioState.() -> RadioState) { lastSeen = SystemClock.elapsedRealtime(); _state.update { it.f().copy(connected = true, live = true) } }

    /** FM comes as tens of kHz (9540 = 95.40 MHz), AM as kHz. */
    private fun apply(raw: Int) {
        val fm = raw in 6_500..10_800
        if (_state.value.scanning && (if (fm) raw * 10 else raw) != _state.value.freqKhz) scanLastChange = SystemClock.elapsedRealtime()
        touch {
            // a new station invalidates the old RDS name until the next report
            val khz = if (fm) raw * 10 else raw
            copy(freqKhz = khz, ps = if (khz != freqKhz) null else ps, rt = if (khz != freqKhz) null else rt, band = if (!fm) Band.AM else if (band == Band.AM) Band.FM1 else band)
        }
    }

    // ---- commands: the firmware RadioManager, the same calls the stock app makes ----

    private fun run(block: () -> Unit) { scope.launch { runCatching { if (!fw.ready) fw.connect(); block() } } }
    private fun code(b: Band) = when (b) { Band.FM1 -> 1; Band.FM2 -> 2; Band.AM -> 3 }

    /**
     * Same entry point the stock UI/steering-wheel path uses: RadioService (exported, runs inside fmam next to its own
     * RadioControl) maps CMD_NAME to RadioControl calls. Codes read from RadioService.cmdByMediaButton:
     * 1 next preset, 2 prev preset, 5/6 seek next/prev, 7 auto scan, 9/16 fine tune up/down.
     */
    private fun svc(cmd: Int, reflect: () -> Unit = {}) = scope.launch {
        val ok = runCatching {
            ctx.startForegroundService(Intent(ACTION_MEDIA_BUTTON).setClassName(PKG, SERVICE).putExtra("CMD_NAME", cmd))
        }.onFailure { Log.w(TAG, "svc($cmd): $it") }.isSuccess
        if (!ok) runCatching { if (!fw.ready) fw.connect(); reflect() }
    }

    override fun step(up: Boolean, stepKhz: Int) { svc(if (up) 9 else 16) { fw.call(if (up) "fineTuneNext" else "fineTunePrev") } }
    override fun seekUp() { svc(5) { fw.call("seekToNext") } }
    override fun seekDown() { svc(6) { fw.call("seekToPrev") } }
    override fun nextStation() { svc(1) { walk(true) } }
    override fun previousStation() { svc(2) { walk(false) } }

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
        apply(if (b == Band.AM) khz else khz / 10)   // show it at once, the next poll confirms or corrects
        fw.call("play", code(b), if (b == Band.AM) khz else khz / 10, -1)
    }

    override fun setBand(band: Band) = run { fw.call("switchWaveband", code(band)) }
    override fun toggleBand() { scope.launch {
        val ok = runCatching { ctx.startForegroundService(Intent(ACTION_SWITCH_BAND).setClassName(PKG, SERVICE)) }.isSuccess
        if (!ok) runCatching { if (!fw.ready) fw.connect(); fw.call("switchWaveband", code(Band.entries[(_state.value.band.ordinal + 1) % Band.entries.size])) }
    } }
    override fun toggleMute() = Unit
    override fun toggleAf() = run { fw.call("switchAf") }
    override fun toggleTa() = run { fw.call("switchTa") }
    override fun toggleStereo() = run { fw.call("switchStereo") }
    override fun toggleLoc() = run { fw.call("toggleLOC") }
    override fun autoScan() {
        scanSince = SystemClock.elapsedRealtime(); scanLastChange = scanSince
        touch { copy(scanning = true) }   // shown at once; the log or the idle check below ends it
        svc(7) { fw.call("scanAS") }
    }
    override fun stopSeek() { touch { copy(scanning = false) }; run { fw.call("stopWork") } }

    private var scanSince = 0L
    private var scanLastChange = 0L

    /** Scan over when the frequency has stopped moving for a few seconds (the log may be unreadable), or after a minute and a half. */
    private fun checkScanEnd() {
        if (!_state.value.scanning) return
        val now = SystemClock.elapsedRealtime()
        if ((now - scanLastChange > SCAN_IDLE_MS && now - scanSince > SCAN_IDLE_MS) || now - scanSince > 90_000L) touch { copy(scanning = false) }
    }

    override fun openStockApp(): Boolean = Embedder.launch(ctx, PKG)

    private companion object {
        const val PKG = "com.imotor.fmam"
        const val SERVICE = "com.imotor.radiosupport.radio.RadioService"
        const val ACTION_MEDIA_BUTTON = "com.imotor.fmam.ACTION_MEDIA_BUTTON"
        const val ACTION_SWITCH_BAND = "com.imotor.radio.ACTION_SWITCH_BAND"
        const val TAG = "HURadio"
        const val LOG_TAG = "VehicleHelperService"
        const val LIVE_MS = 4_000L
        const val POLL_MS = 700L
        const val SCAN_POLL_MS = 200L
        const val SCAN_IDLE_MS = 4_000L
        const val IDLE_POLL_MS = 3_000L
        const val LIST_MS = 4_000L
        val RE_STATION = Regex("RadioControl: +---> onStationChanged: StationInfo \\{freq=(\\d+), band=(\\d)")
        val RE_WORK = Regex("WorkInfo \\{workMode=\\d+, seekType=\\d+, seekStatus=(\\d)")
        val RE_LIST = Regex("FM list: \\[([^\\]]*)\\]")
        val RE_STATUS = Regex("mStatusTA:(\\w+),mStatusAF:(\\w+),[^}]*?mStatusST:(\\w+),mStatusFMLOC:(\\w+)")
        val RE_FREQ = Regex("setRadioInfo:(\\d+)")
        val RE_PS = Regex("PS name:(.*?)\\s+tp:")
        val RE_ST = Regex("mStereo:(true|false)")
        val RE_RT = Regex("mPsName:(.*?),mRt:(.*?),\\}")
    }
}
