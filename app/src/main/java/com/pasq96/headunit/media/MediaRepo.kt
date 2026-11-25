package com.pasq96.headunit.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NowPlaying(
    val hasAccess: Boolean = false,
    val pkg: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val art: ImageBitmap? = null,
    val playing: Boolean = false,
    val album: String? = null,
    val pos: Long = 0,          // ms at [stamp]
    val dur: Long = 0,
    val speed: Float = 1f,
    val stamp: Long = 0,        // elapsedRealtime of the position sample
) {
    /** Position extrapolated to now while playing. */
    fun position(now: Long = SystemClock.elapsedRealtime()): Long =
        if (playing) (pos + ((now - stamp) * speed).toLong()).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE) else pos
}

class MediaRepo(private val ctx: Context) {
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, MediaListener::class.java)
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = pick(msm.safeSessions())
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }

    fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** Idempotent; call again after the user grants Notification access. */
    fun start() {
        if (!hasAccess()) { _state.value = NowPlaying(hasAccess = false); return }
        if (!started) {
            runCatching { msm.addOnActiveSessionsChangedListener(sessionsListener, listener) }.onSuccess { started = true }
        }
        pick(msm.safeSessions())
    }

    fun stop() {
        if (started) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        controller?.unregisterCallback(callback)
        controller = null
    }

    fun playPause() = controller?.let { c ->
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    } ?: sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }

    fun next() = controller?.transportControls?.skipToNext() ?: sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = controller?.transportControls?.skipToPrevious() ?: sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /** Hands a hardware key to the active session; false = none active, caller must not consume it. */
    fun dispatchKey(e: KeyEvent): Boolean = controller?.dispatchMediaButtonEvent(e) == true

    fun sendKey(code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun MediaSessionManager.safeSessions(): List<MediaController> =
        runCatching { getActiveSessions(listener) }.getOrDefault(emptyList())

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback)
        }
        publish()
    }

    private var artKey: Bitmap? = null
    private var artImage: ImageBitmap? = null

    private fun publish() {
        val c = controller
        val md = c?.metadata
        val raw = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (raw !== artKey) {
            artKey = raw
            artImage = raw?.let { Bitmap.createScaledBitmap(it, ART_PX, ART_PX, true).asImageBitmap() }
        }
        _state.value = NowPlaying(
            hasAccess = true,
            pkg = c?.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            art = artImage,
            playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING,
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            pos = c?.playbackState?.position?.coerceAtLeast(0) ?: 0,
            dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            speed = c?.playbackState?.playbackSpeed ?: 1f,
            stamp = c?.playbackState?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
        )
    }

    private companion object { const val ART_PX = 256 }
}
