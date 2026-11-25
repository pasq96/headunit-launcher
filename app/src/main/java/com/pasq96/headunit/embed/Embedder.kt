package com.pasq96.headunit.embed

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings

/**
 * Hosts third-party apps next to the launcher without system-signature APIs (ActivityView/TaskView):
 *  1. freeform windows with launch bounds (enable_freeform_support, see README);
 *  2. system split-screen, when the launcher itself runs in multi-window (LAUNCH_ADJACENT);
 *  3. otherwise plain full-screen launch.
 */
object Embedder {
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    fun freeformEnabled(ctx: Context): Boolean = 
        (Settings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) == 1 ||
            ctx.packageManager.hasSystemFeature("android.software.freeform_window_management"))

    /** Raises the freeform flags itself when WRITE_SECURE_SETTINGS was granted; true when anything changed. */
    fun ensureFreeform(ctx: Context): Boolean {
        if (freeformEnabled(ctx)) return false
        return runCatching {
            Settings.Global.putInt(ctx.contentResolver, "enable_freeform_support", 1)
            Settings.Global.putInt(ctx.contentResolver, "force_resizable_activities", 1)
        }.getOrDefault(false)
    }

    fun canEmbed(ctx: Context): Boolean = freeformEnabled(ctx) || (ctx is Activity && ctx.isInMultiWindowMode)

    /** Starts [pkg]; with [bounds] (screen px) it is placed in that window when the device allows it. */
    fun launch(ctx: Context, pkg: String, bounds: Rect? = null): Boolean {
        val intent = (if ('/' in pkg) ComponentName.unflattenFromString(pkg)?.let { Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(it) }
            else ctx.packageManager.getLaunchIntentForPackage(pkg)) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val opts = ActivityOptions.makeBasic()
        if (bounds != null) {
            if (freeformEnabled(ctx)) {
                opts.setLaunchBounds(bounds)
                setWindowingMode(opts, WINDOWING_MODE_FREEFORM)
            } else if (ctx is Activity && ctx.isInMultiWindowMode) {
                intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                opts.setLaunchBounds(bounds)
            }
        }
        // without bounds the app must leave its panel window: a task already in freeform would otherwise stay there
        if (bounds == null && freeformEnabled(ctx)) setWindowingMode(opts, WINDOWING_MODE_FULLSCREEN)
        return runCatching { ctx.startActivity(intent, opts.toBundle()) }.isSuccess
    }

    // Hidden API: needs hidden_api_policy relaxed on Android 9+, ignored when missing (bounds alone still apply).
    private fun setWindowingMode(opts: ActivityOptions, mode: Int) {
        runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, mode)
        }
    }
}
