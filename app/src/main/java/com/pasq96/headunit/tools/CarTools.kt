package com.pasq96.headunit.tools

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.content.res.Resources
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.bluetooth.BluetoothAdapter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Stats(val cpuC: Float?, val ramFreeMb: Long, val ramTotalMb: Long, val battV: Float?)
class Links(val wifi: Boolean, val hotspot: Boolean, val bluetooth: Boolean)

object CarTools {
    // ---- polling flows: collected with collectAsStateWithLifecycle, so they stop when the launcher is not visible ----

    fun stats(ctx: Context, everyMs: Long = 2_000): Flow<Stats> = flow {
        while (true) { runCatching { readStats(ctx) }.getOrNull()?.let { emit(it) }; delay(everyMs) }
    }.flowOn(Dispatchers.IO)

    fun links(ctx: Context, everyMs: Long = 5_000): Flow<Links> = flow {
        while (true) { runCatching { readLinks(ctx) }.getOrNull()?.let { emit(it) }; delay(everyMs) }
    }.flowOn(Dispatchers.IO)

    /** Follows the system clock broadcasts, so the time is right right after ACC-on and after time zone changes. */
    fun clock(ctx: Context, h24: Boolean = true): Flow<String> = callbackFlow {
        val fmt = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.getDefault())
        val rx = object : android.content.BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { trySend(fmt.format(Date())) } }
        trySend(fmt.format(Date()))
        ctx.registerReceiver(rx, IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        awaitClose { runCatching { ctx.unregisterReceiver(rx) } }
    }

    /** GPS speed in km/h (null = no fix / no permission). Registers the provider only while collected. */
    @SuppressLint("MissingPermission")
    fun speedKmh(ctx: Context): Flow<Float?> = callbackFlow {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val granted = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) { trySend(null); awaitClose { }; return@callbackFlow }
        val lastAt = AtomicLong(0)
        var prev: Location? = null
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lastAt.set(SystemClock.elapsedRealtime())
                // freshness is judged by arrival, not by the fix timestamp: head units often have a wrong clock or a zero timestamp
                val p = prev; prev = Location(loc)
                val v = when {
                    loc.hasSpeed() -> loc.speed
                    p != null && loc.time > p.time -> p.distanceTo(loc) / ((loc.time - p.time) / 1000f)   // no speed field: derive it from two fixes
                    else -> null
                }
                trySend(v?.times(3.6f))
            }
            @Deprecated("legacy") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) { trySend(null) }
        }
        trySend(null)
        Log.i(GPS_TAG, "gps provider enabled=" + runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull())
        // registered even while GPS is off: the provider can come on later and onLocationChanged starts flowing
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, l, Looper.getMainLooper()) }
            .onFailure { Log.w(GPS_TAG, "requestLocationUpdates: $it") }
        launch { while (true) { delay(2_000); if (lastAt.get() != 0L && SystemClock.elapsedRealtime() - lastAt.get() > 6_000) trySend(null) } }
        awaitClose { runCatching { lm.removeUpdates(l) } }
    }

    private const val GPS_TAG = "HUGps"

    // ---- readers ----

    private fun readStats(ctx: Context): Stats {
        val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = batt?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val intent = if (mv > 0) (if (mv > 100) mv / 1000f else mv.toFloat()) else null
        // The Android battery of a head unit is its 3 V backup cell; the car's 12 V rail, when exposed, is a power_supply node.
        val best = listOfNotNull(intent, supplyVolts(), carVolt(ctx).read()).maxOrNull()
        return Stats(cpuTempC(), mi.availMem shr 20, mi.totalMem shr 20, best)
    }

    @Volatile private var carVoltage: CarVoltage? = null
    private fun carVolt(ctx: Context) = carVoltage ?: CarVoltage(ctx.applicationContext).also { carVoltage = it }

    private val supplyFiles: List<File> by lazy {
        File("/sys/class/power_supply").listFiles()?.flatMap { d ->
            listOf("voltage_now", "voltage_avg", "input_voltage", "vbus_voltage").map { File(d, it) }
        }.orEmpty()
    }

    private fun supplyVolts(): Float? = supplyFiles
        .mapNotNull { runCatching { it.readText().trim().toDouble() }.getOrNull() }
        .map { if (it > 1_000_000) it / 1_000_000 else if (it > 1000) it / 1000 else it }
        .filter { it in 1.0..60.0 }.maxOrNull()?.toFloat()

    /** Hottest thermal zone, in °C (sysfs reports millidegrees on most kernels). */
    private val thermalFiles: List<File> by lazy {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.map { File(it, "temp") }.orEmpty()
    }

    private fun cpuTempC(): Float? =
        thermalFiles
            .mapNotNull { runCatching { it.readText().trim().toFloat() }.getOrNull() }
            .map { if (it > 1000f) it / 1000f else it }
            .filter { it in 1f..150f }
            .maxOrNull()

    @Suppress("DEPRECATION")
    private fun readLinks(ctx: Context): Links {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val ap = runCatching { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean }.getOrDefault(false)
        val bt = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)
        return Links(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true, ap, bt)
    }

    // ---- brightness / night mode ----

    fun canWriteSettings(ctx: Context) = Settings.System.canWrite(ctx)

    fun brightness(ctx: Context): Int = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)

    /** Value range of the panel on this firmware (framework config), the classic 1..255 when it does not say. */
    fun brightnessRange(): IntRange {
        val r = Resources.getSystem()
        fun cfg(n: String, d: Int) = r.getIdentifier(n, "integer", "android").takeIf { it != 0 }?.let { runCatching { r.getInteger(it) }.getOrNull() } ?: d
        val lo = cfg("config_screenBrightnessSettingMinimum", 1); val hi = cfg("config_screenBrightnessSettingMaximum", 255)
        return if (hi > lo) lo..hi else 1..255
    }

    fun brightnessFraction(ctx: Context): Float = brightnessRange().let { ((brightness(ctx) - it.first).toFloat() / (it.last - it.first)).coerceIn(0f, 1f) }

    /** Sets the level as a 0..1 fraction of the panel range; false when the system did not take the value. */
    // Firmware backlight service (com.imotor.framework.hardware.IMHardwareManager): the only path that moves the panel on this unit,
    // the plain system setting is overridden by the policy's temporary brightness.
    private var hw: Any? = null
    private fun hwManager(ctx: Context): Any? = hw ?: runCatching {
        Class.forName("com.imotor.framework.hardware.IMHardwareManager").getMethod("getInstance", Context::class.java).invoke(null, ctx.applicationContext)
    }.onFailure { Log.w("HUScreen", "IMHardwareManager: ${it.cause ?: it}") }.getOrNull().also { hw = it }

    private fun hwRange(ctx: Context): IntRange? = runCatching {
        val m = hwManager(ctx) ?: return null
        val r = m.javaClass.getMethod("getLcdBacklightRange").invoke(m)!!
        val lo = r.javaClass.getMethod("min").invoke(r) as Int; val hi = r.javaClass.getMethod("max").invoke(r) as Int
        if (hi > lo) lo..hi else null
    }.getOrNull()

    /** Sets the level as a 0..1 fraction of the panel range; false when the system did not take the value. */
    fun setBrightnessFraction(ctx: Context, f: Float): Boolean {
        hwRange(ctx)?.let { r ->
            val v = r.first + (f.coerceIn(0f, 1f) * (r.last - r.first)).roundToInt()
            val ok = runCatching { val m = hwManager(ctx)!!; m.javaClass.getMethod("setLcdBacklight", Int::class.javaPrimitiveType).invoke(m, v) }
                .onFailure { Log.w("HUScreen", "setLcdBacklight: ${it.cause ?: it}") }.isSuccess
            if (ok) return true
        }
        val r = brightnessRange()
        val v = r.first + (f.coerceIn(0f, 1f) * (r.last - r.first)).roundToInt()
        setBrightness(ctx, v)
        return brightness(ctx) == v
    }

    fun setBrightness(ctx: Context, v: Int) {
        if (!canWriteSettings(ctx)) return
        val cr = ctx.contentResolver
        if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(brightnessRange()))
    }

    /** One-off log (tag HUScreen) of what could drive the backlight on this unit: system value, sysfs nodes, firmware manager methods. */
    fun probeScreen(ctx: Context) {
        val t = "HUScreen"
        runCatching {
            Log.i(t, "system=${brightness(ctx)} mode=${Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)} range=${brightnessRange()} canWrite=${canWriteSettings(ctx)}")
            File("/sys/class/backlight").listFiles()?.forEach { d ->
                fun rd(n: String) = runCatching { File(d, n).readText().trim() }.getOrNull()
                Log.i(t, "backlight ${d.name} ${rd("brightness")}/${rd("max_brightness")}")
            }
            listOf("com.imotor.framework.hardware.IMHardwareManager", "android.mcu.IMcuManager").forEach { cn ->
                runCatching {
                    val ms = Class.forName(cn).methods.map { it.name }.distinct().filter { Regex("(?i)bright|backlight|lcd|screen|dim").containsMatchIn(it) }
                    Log.i(t, "$cn -> $ms")
                }.onFailure { Log.i(t, "$cn: ${it.javaClass.simpleName}") }
            }
        }
    }

    private var overlay: View? = null

    /** Dark, untouchable overlay above everything: dims IPS panels below their hardware minimum. */
    fun setNightOverlay(ctx: Context, on: Boolean, alpha: Float = 0.55f) {
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val cur = overlay
        if (on && cur != null && cur.isAttachedToWindow) { cur.setBackgroundColor(Color.argb((alpha * 255).toInt(), 0, 0, 0)); return }
        cur?.let { runCatching { wm.removeView(it) } }
        overlay = null
        if (!on || !Settings.canDrawOverlays(app)) return
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        val v = View(app).apply { setBackgroundColor(Color.argb((alpha * 255).toInt(), 0, 0, 0)) }
        runCatching { wm.addView(v, lp) }.onSuccess { overlay = v }
    }

    // ---- boot resume ----

    fun sendMediaPlay(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }
}
