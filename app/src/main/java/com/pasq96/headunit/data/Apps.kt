package com.pasq96.headunit.data

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap)

object AppRepo {
    private const val ICON_PX = 96

    /** Launchable apps (own package excluded), sorted by label; icons rasterised once at a fixed size. */
    suspend fun load(ctx: Context): List<AppInfo> = withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap()) }
            .sortedBy { it.label.lowercase() }
    }
}
