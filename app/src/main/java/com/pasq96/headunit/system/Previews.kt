package com.pasq96.headunit.system

import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Still previews of other apps: full-screen capture over the internal adb channel while the app is in front. */
object Previews {
    /** Package of the activity resumed last, from the usage log (needs the usage-access permission). */
    fun foreground(ctx: Context): String? = runCatching {
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = um.queryEvents(now - 30_000, now)
        val e = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == 1) last = e.packageName } // 1 = activity resumed
        last
    }.getOrNull()

    /** False when the package has no live activity (never started, or its task finished / went to stopped); events of the last 6 h. */
    fun alive(ctx: Context, pkg: String): Boolean = runCatching {
        val now = System.currentTimeMillis()
        val ev = (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryEvents(now - 6 * 3_600_000L, now)
        val e = android.app.usage.UsageEvents.Event()
        var last = 0
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.packageName == pkg && e.eventType in LIFECYCLE) last = e.eventType }
        last == 1 || last == 2   // resumed / paused; 23 = stopped
    }.getOrDefault(true)

    private val LIFECYCLE = setOf(1, 2, 23)

    /** Last package resumed between [from] and [to] (ms), or null when nothing was resumed in that window. */
    fun resumedBetween(ctx: Context, from: Long, to: Long): String? = runCatching {
        val ev = (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryEvents(from, to)
        val e = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == 1) last = e.packageName }
        last
    }.getOrNull()

    /** Halved on decode to keep the bitmap small. */
    @Synchronized
    fun capture(ctx: Context): Bitmap? {
        // one adb session is kept between shots (a fresh RSA handshake per shot is costly on this CPU); reopened once if it went stale
        repeat(2) {
            val s = session ?: runCatching { InternalAdb.open(ctx) }.getOrNull()?.also { session = it } ?: return null
            val png = runCatching { s.exec("screencap -p") }.getOrNull()
            if (png != null && png.isNotEmpty()) return BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            runCatching { s.close() }; session = null
        }
        return null
    }

    @Volatile private var session: InternalAdb.Session? = null

    private fun file(ctx: Context, pkg: String) = File(ctx.cacheDir, "preview_$pkg.jpg")

    fun save(ctx: Context, pkg: String, b: Bitmap) {
        runCatching { file(ctx, pkg).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 75, it) } }
    }

    fun load(ctx: Context, pkg: String): Bitmap? = file(ctx, pkg).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
}
