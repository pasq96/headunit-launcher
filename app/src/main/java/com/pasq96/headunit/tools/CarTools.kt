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
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.bluetooth.BluetoothAdapter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Stats(val cpuC: Float?, val ramFreeMb: Long, val ramTotalMb: Long, val battV: Float?)
class Links(val wifi: Boolean, val hotspot: Boolean, val bluetooth: Boolean)

object CarTools {
    // ---- polling flows: collected with collectAsStateWithLifecycle, so they stop when the launcher is not visible ----

    fun stats(ctx: Context, everyMs: Long = 2_000): Flow<Stats> = flow {
        while (true) { emit(readStats(ctx)); delay(everyMs) }
    }

    fun links(ctx: Context, everyMs: Long = 5_000): Flow<Links> = flow {
        while (true) { emit(readLinks(ctx)); delay(everyMs) }
    }

    fun clock(h24: Boolean = true): Flow<String> = flow {
        val fmt = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.getDefault())
        while (true) { emit(fmt.format(Date())); delay(60_000 - System.currentTimeMillis() % 60_000 + 50) }
    }

    /** GPS speed in km/h (null = no fix / no permission). Registers the provider only while collected. */
    @SuppressLint("MissingPermission")
    fun speedKmh(ctx: Context): Flow<Float?> = callbackFlow {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val granted = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { trySend(null); awaitClose { }; return@callbackFlow }
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { trySend(if (loc.hasSpeed()) loc.speed * 3.6f else null) }
            @Deprecated("legacy") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) { trySend(null) }
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, l, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(l) }
    }

    // ---- readers ----

    private fun readStats(ctx: Context): Stats {
        val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = batt?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        return Stats(cpuTempC(), mi.availMem shr 20, mi.totalMem shr 20, if (mv > 0) (if (mv > 100) mv / 1000f else mv.toFloat()) else null)
    }

    /** Hottest thermal zone, in °C (sysfs reports millidegrees on most kernels). */
    private fun cpuTempC(): Float? =
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.mapNotNull { runCatching { File(it, "temp").readText().trim().toFloat() }.getOrNull() }
            ?.map { if (it > 1000f) it / 1000f else it }
            ?.filter { it in 1f..150f }
            ?.maxOrNull()

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

    fun setBrightness(ctx: Context, v: Int) {
        if (!canWriteSettings(ctx)) return
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255))
    }

    private var overlay: View? = null

    /** Dark, untouchable overlay above everything: dims IPS panels below their hardware minimum. */
    fun setNightOverlay(ctx: Context, on: Boolean, alpha: Float = 0.55f) {
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        overlay?.let { runCatching { wm.removeView(it) } }
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
