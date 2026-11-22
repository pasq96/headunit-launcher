package com.pasq96.headunit.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [nav]: handles geo:/google.navigation: or declares the maps category; [music]: media browser / button receiver / music category. */
/** [pkg] is the package name, or "package/Activity" for the extra launcher entries of a package that has several. */
class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap, val nav: Boolean = false, val music: Boolean = false) {
    val pkgName get() = pkg.substringBefore('/')
}

object AppRepo {
    private const val ICON_PX = 128

    /** Launchable apps (own package excluded), sorted by label; icons rasterised once at a fixed size. */
    suspend fun load(ctx: Context): List<AppInfo> = withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val navPkgs = activityPkgs(pm, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=test"))) +
            activityPkgs(pm, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=test"))) +
            activityPkgs(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS))
        val musicPkgs = activityPkgs(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)) +
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).map { it.serviceInfo.packageName } +
            pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0).map { it.activityInfo.packageName }
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .let { all ->
                val seen = HashSet<String>()
                all.map {
                    val pkg = it.activityInfo.packageName
                    // first entry keeps the plain package key (saved prefs stay valid), further ones are keyed by activity
                    val key = if (seen.add(pkg)) pkg else ComponentName(pkg, it.activityInfo.name).flattenToString()
                    AppInfo(key, it.loadLabel(pm).toString(), it.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap(), pkg in navPkgs, pkg in musicPkgs)
                }
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun activityPkgs(pm: PackageManager, i: Intent): Set<String> =
        pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
}

/** Drawer order per [Prefs.sort]; custom order lists known packages first, unknown ones follow alphabetically. */
fun orderedApps(apps: List<AppInfo>, p: Prefs, includeHidden: Boolean = false): List<AppInfo> {
    val visible = if (includeHidden) apps else apps.filter { it.pkg !in p.hidden }
    val alpha = visible.sortedBy { it.label.lowercase() }
    return when (p.sort) {
        AppSort.ALPHA -> alpha
        AppSort.USAGE -> alpha.sortedByDescending { p.usage[it.pkg] ?: 0 } // stable: ties stay alphabetical
        AppSort.CUSTOM -> { val rank = p.customOrder.withIndex().associate { it.value to it.index }; alpha.sortedBy { rank[it.pkg] ?: Int.MAX_VALUE } }
    }
}
