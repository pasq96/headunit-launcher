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
        val ev = um.queryEvents(now - 120_000, now)
        val e = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == 1) last = e.packageName } // 1 = activity resumed
        last
    }.getOrNull()

    /** Halved on decode to keep the bitmap small. */
    fun capture(ctx: Context): Bitmap? = runCatching {
        val png = InternalAdb.open(ctx).use { it.exec("screencap -p") }
        BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = 2 })
    }.getOrNull()

    private fun file(ctx: Context, pkg: String) = File(ctx.cacheDir, "preview_$pkg.jpg")

    fun save(ctx: Context, pkg: String, b: Bitmap) {
        runCatching { file(ctx, pkg).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 75, it) } }
    }

    fun load(ctx: Context, pkg: String): Bitmap? = file(ctx, pkg).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
}
