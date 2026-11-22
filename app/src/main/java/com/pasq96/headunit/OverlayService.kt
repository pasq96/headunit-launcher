package com.pasq96.headunit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pasq96.headunit.data.DockPos
import com.pasq96.headunit.data.Prefs
import com.pasq96.headunit.embed.Embedder
import com.pasq96.headunit.ui.DockLayer
import com.pasq96.headunit.ui.ShieldTile
import com.pasq96.headunit.ui.OverlayLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Hosts the dock and the panels (drawer, tools, settings) as overlay windows: with freeform apps the system draws those
 * windows above the launcher, so inline UI would disappear behind the map. Overlay windows are never focusable, which keeps
 * hardware keys flowing to the focused app / media session.
 */
class OverlayService : Service() {
    private val owner = ViewOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private val hub get() = (application as HeadUnitApp).hub
    private var dockView: ComposeView? = null
    private var panelView: ComposeView? = null
    private val shieldViews = HashMap<Int, Pair<ComposeView, String>>()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!wanted(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        owner.create()
        wm = getSystemService(WindowManager::class.java)
        startForeground(NOTIFICATION_ID, notification())
        scope.launch { hub.prefs.collect { updateDock(it) } }
        scope.launch { snapshotFlow { hub.hasOverlay() }.collect { updatePanels(it) } }
        scope.launch { hub.prefs.collect { updateShields() } }
        scope.launch { snapshotFlow { Triple(hub.homeVisible, hub.hasOverlay(), hub.navRect to hub.mediaRect) }.collect { updateShields() } }
    }

    override fun onDestroy() {
        dockView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        shieldViews.values.forEach { runCatching { wm.removeView(it.first) } }; shieldViews.clear()
        dockView = null; panelView = null
        hub.dockInOverlay = false; hub.overlayInWindow = false
        scope.cancel()
        owner.destroy()
        super.onDestroy()
    }

    private fun updateDock(p: Prefs) {
        if (!p.dockOverlay) {
            dockView?.let { runCatching { wm.removeView(it) } }
            dockView = null; hub.dockInOverlay = false
            return
        }
        val px = (p.dockDp * resources.displayMetrics.density).roundToInt()
        val lp = when (p.dock) {
            DockPos.BOTTOM -> params(WindowManager.LayoutParams.MATCH_PARENT, px, Gravity.BOTTOM)
            DockPos.LEFT -> params(px, WindowManager.LayoutParams.MATCH_PARENT, Gravity.START)
            DockPos.RIGHT -> params(px, WindowManager.LayoutParams.MATCH_PARENT, Gravity.END)
        }
        val v = dockView
        if (v == null) {
            val nv = composeView { DockLayer(hub) }
            if (runCatching { wm.addView(nv, lp) }.isSuccess) { dockView = nv; hub.dockInOverlay = true }
        } else runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun updatePanels(open: Boolean) {
        if (open && panelView == null) {
            val nv = composeView { OverlayLayer(hub) }
            val lp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.NO_GRAVITY)
            if (runCatching { wm.addView(nv, lp) }.isSuccess) panelView = nv
        } else if (!open && panelView != null) {
            runCatching { wm.removeView(panelView) }
            panelView = null
        }
        hub.overlayInWindow = true // while the service runs, panels always belong to it (even when closed)
    }

    /** One transparent window per hosted panel app, above the freeform window: hides its title bar and swallows touches. */
    private fun updateShields() {
        val want = hub.shields().associateBy { it.slot }
        shieldViews.keys.toList().forEach { slot ->
            val cur = shieldViews[slot] ?: return@forEach
            if (want[slot]?.pkg != cur.second) { runCatching { wm.removeView(cur.first) }; shieldViews.remove(slot) }
        }
        want.values.forEach { s ->
            val lp = params(s.rect.width(), s.rect.height(), Gravity.TOP or Gravity.START).also {
                it.x = s.rect.left; it.y = s.rect.top; it.flags = it.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            val cur = shieldViews[s.slot]
            if (cur == null) {
                val nv = composeView { ShieldTile(hub, s.pkg) }
                if (runCatching { wm.addView(nv, lp) }.isSuccess) shieldViews[s.slot] = nv to s.pkg
            } else runCatching { wm.updateViewLayout(cur.first, lp) }
        }
    }

    private fun params(w: Int, h: Int, gravity: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).also { it.gravity = gravity }

    private fun composeView(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        isSoundEffectsEnabled = false
        setContent(content)
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN))
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name)).setOngoing(true).build()
    }

    /** Minimal owner so Compose can live in a window that has no activity. */
    private class ViewOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        fun create() { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
    }

    companion object {
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL = "overlay"

        /** Overlay windows only make sense with the permission and with freeform apps that would cover the launcher. */
        fun wanted(ctx: Context) = Settings.canDrawOverlays(ctx) && Embedder.freeformEnabled(ctx)

        fun sync(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            runCatching { if (wanted(ctx)) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i) }
        }
    }
}
