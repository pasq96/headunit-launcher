package com.pasq96.headunit.system

import android.content.Context
import androidx.annotation.StringRes
import com.pasq96.headunit.R

/** Grants everything the launcher needs through [InternalAdb]; each step lists alternative commands, first success wins. */
object PermissionGranter {
    class Step(@StringRes val label: Int, val alternatives: List<String>)
    class Report(val fail: InternalAdb.Fail?, val steps: List<Pair<Int, Boolean>>)

    private fun steps(ctx: Context): List<Step> {
        val pkg = ctx.packageName
        val comp = "$pkg/$pkg.media.MediaListener"
        return listOf(
            Step(R.string.grant_secure, listOf("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")),
            Step(R.string.grant_location, listOf("pm grant $pkg android.permission.ACCESS_FINE_LOCATION && pm grant $pkg android.permission.ACCESS_COARSE_LOCATION")),
            Step(R.string.grant_overlay, listOf("appops set $pkg SYSTEM_ALERT_WINDOW allow")),
            Step(R.string.grant_write, listOf("appops set $pkg WRITE_SETTINGS allow")),
            Step(R.string.grant_usage, listOf("appops set $pkg GET_USAGE_STATS allow")),
            Step(
                R.string.grant_listener,
                listOf(
                    "cmd notification allow_listener $comp",
                    "cur=\$(settings get secure enabled_notification_listeners); case \"\$cur\" in *$comp*) ;; null|\"\") " +
                        "settings put secure enabled_notification_listeners $comp;; *) settings put secure enabled_notification_listeners \"\$cur:$comp\";; esac",
                ),
            ),
            Step(R.string.grant_freeform, listOf("settings put global enable_freeform_support 1 && settings put global force_resizable_activities 1")),
            Step(R.string.grant_battery, listOf("dumpsys deviceidle whitelist +$pkg", "cmd deviceidle whitelist +$pkg")),
            Step(R.string.grant_notif, listOf("pm grant $pkg android.permission.POST_NOTIFICATIONS", "echo skip")),
            Step(R.string.grant_logs, listOf("pm grant $pkg android.permission.READ_LOGS")),
            Step(R.string.grant_storage, listOf("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE && appops set $pkg MANAGE_EXTERNAL_STORAGE allow", "pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")),
            Step(R.string.grant_devopts, listOf("settings put global development_settings_enabled 1")),
        )
    }

    /** Blocking: call off the main thread. */
    fun run(ctx: Context): Report {
        val session = try { InternalAdb.open(ctx) } catch (e: InternalAdb.AdbException) { return Report(e.fail, emptyList()) }
        session.use { s ->
            val res = steps(ctx).map { st ->
                st.label to st.alternatives.any { runCatching { InternalAdb.ok(s.shell(it)) }.getOrDefault(false) }
            }
            return Report(null, res)
        }
    }
}
