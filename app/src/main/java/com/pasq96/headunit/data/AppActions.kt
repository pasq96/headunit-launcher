package com.pasq96.headunit.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import com.pasq96.headunit.system.InternalAdb
import java.util.concurrent.TimeUnit

/** Per-app quick actions. Privileged ones try a shell (works if the unit exposes su/adb uid), else degrade to a normal-permission path. */
object AppActions {
    enum class Result { DONE, SETTINGS }

    fun isRemovable(ctx: Context, pkg: String): Boolean = runCatching {
        val f = ctx.packageManager.getApplicationInfo(pkg, 0).flags
        f and ApplicationInfo.FLAG_SYSTEM == 0 || f and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }.getOrDefault(false)

    fun info(ctx: Context, pkg: String) = start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    fun uninstall(ctx: Context, pkg: String) = start(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

    /** Blocking: call off the main thread. */
    fun clearCache(ctx: Context, pkg: String): Result =
        // adb uid cannot touch another app's dirs: trim-caches evicts cached data system-wide instead
        if (shell("test -w /data/data/$pkg/cache && rm -rf /data/data/$pkg/cache/* /data/data/$pkg/code_cache/* /sdcard/Android/data/$pkg/cache/*") || InternalAdb.run(ctx, "pm trim-caches 1099511627776")) Result.DONE else settings(ctx, pkg)

    fun clearData(ctx: Context, pkg: String): Result =
        if (shell("pm clear $pkg") || InternalAdb.run(ctx, "pm clear $pkg")) Result.DONE else settings(ctx, pkg)

    fun forceStop(ctx: Context, pkg: String): Result {
        if (shell("am force-stop $pkg") || InternalAdb.run(ctx, "am force-stop $pkg")) return Result.DONE
        // no privileged path: ends background processes only (KILL_BACKGROUND_PROCESSES is a normal permission)
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        return Result.DONE
    }

    /** Developer options in one tap: raises the enabled flag when WRITE_SECURE_SETTINGS was granted via adb, then tries known entry points. */
    fun developerOptions(ctx: Context) {
        runCatching { Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1) }
        val targets = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent().setClassName("com.android.settings", "com.android.settings.DevelopmentSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
            Intent(Settings.ACTION_SETTINGS),
        )
        targets.firstOrNull { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    }

    private fun settings(ctx: Context, pkg: String): Result { info(ctx, pkg); return Result.SETTINGS }

    private fun start(ctx: Context, i: Intent) { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    private fun shell(cmd: String): Boolean = listOf(arrayOf("sh", "-c", cmd), arrayOf("su", "-c", cmd)).any { run(it) }

    private fun run(argv: Array<String>): Boolean = runCatching {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)
}
