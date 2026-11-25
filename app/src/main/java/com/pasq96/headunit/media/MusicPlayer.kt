package com.pasq96.headunit.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Track(val uri: Uri, val title: String, val artist: String, val album: String)

data class MusicState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = REPEAT_OFF,
    val art: ImageBitmap? = null,
) {
    val track: Track? get() = queue.getOrNull(index)
}

const val REPEAT_OFF = 0
const val REPEAT_ALL = 1
const val REPEAT_ONE = 2

/** Local player: MediaPlayer + a MediaSession, so the dock panel and steering wheel keys see it like any other app. */
class MusicPlayer(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()
    private val _library = MutableStateFlow<List<Track>>(emptyList())
    val library: StateFlow<List<Track>> = _library.asStateFlow()
    private var mp: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null

    // ---- library ----

    /** All audio indexed by MediaStore, internal storage and USB alike. Needs storage read access. */
    suspend fun scan(): Boolean = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val ok = runCatching {
            val c = ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )
            c?.use {
                while (it.moveToNext()) {
                    val artist = it.getString(2)?.takeUnless { a -> a == "<unknown>" }.orEmpty()
                    val album = it.getString(3)?.takeUnless { a -> a == "<unknown>" }.orEmpty()
                    out += Track(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(0)), it.getString(1).orEmpty(), artist, album)
                }
            } != null
        }.getOrDefault(false)
        _library.value = out
        ok
    }

    // ---- transport ----

    fun play(queue: List<Track>, index: Int) {
        if (index !in queue.indices) return
        _state.value = _state.value.copy(queue = queue, index = index, art = null)
        start()
    }

    fun toggle() {
        val p = mp
        if (p == null) { if (_state.value.queue.isNotEmpty()) start(); return }
        if (p.isPlaying) pause() else resume()
    }

    fun next(auto: Boolean = false) {
        val s = _state.value; val n = s.queue.size
        if (n == 0) return
        val i = when {
            auto && s.repeat == REPEAT_ONE -> s.index
            s.shuffle && n > 1 -> generateSequence { (0 until n).random() }.first { it != s.index }
            s.index + 1 < n -> s.index + 1
            !auto || s.repeat == REPEAT_ALL -> 0
            else -> { stopPlayback(); return }
        }
        _state.value = s.copy(index = i, art = null); start()
    }

    fun previous() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (position() > 3000 || s.queue.size == 1) { seekTo(0); return }
        _state.value = s.copy(index = if (s.index > 0) s.index - 1 else s.queue.lastIndex, art = null); start()
    }

    fun seekTo(ms: Long) { mp?.seekTo(ms.toInt()); publish() }
    fun position(): Long = runCatching { mp?.currentPosition?.toLong() }.getOrNull() ?: 0L
    fun duration(): Long = runCatching { mp?.duration?.toLong() }.getOrNull()?.coerceAtLeast(0) ?: 0L
    fun toggleShuffle() { _state.value = _state.value.let { it.copy(shuffle = !it.shuffle) } }
    fun cycleRepeat() { _state.value = _state.value.let { it.copy(repeat = (it.repeat + 1) % 3) } }

    private fun start() {
        val t = _state.value.track ?: return
        stopPlayer()
        if (!requestFocus()) return
        val p = MediaPlayer()
        mp = p
        runCatching {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(ctx, t.uri)
            p.setOnCompletionListener { next(auto = true) }
            p.setOnErrorListener { _, _, _ -> next(auto = true); true }
            p.setOnPreparedListener { it.start(); publish() }
            p.prepareAsync()
        }.onFailure { next(auto = true) }
        ensureSession().isActive = true
        loadArt(t)
        publish()
    }

    private fun pause() { mp?.pause(); publish() }
    private fun resume() { if (requestFocus()) { mp?.start(); publish() } }

    private fun stopPlayback() { stopPlayer(); abandonFocus(); publish() }

    private fun stopPlayer() { mp?.let { runCatching { it.reset(); it.release() } }; mp = null }

    fun release() { stopPlayer(); abandonFocus(); session?.release(); session = null }

    // ---- audio focus ----

    private val focusListener = AudioManager.OnAudioFocusChangeListener {
        when (it) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> mp?.setVolume(1f, 1f)
        }
    }

    private fun requestFocus(): Boolean {
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener).build()
        focus = r
        return am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() { focus?.let { am.abandonAudioFocusRequest(it) }; focus = null }

    // ---- session / metadata ----

    private fun ensureSession(): MediaSession = session ?: MediaSession(ctx, "HeadUnitMusic").also { s ->
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { toggleTo(true) }
            override fun onPause() { toggleTo(false) }
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
            override fun onSeekTo(pos: Long) = seekTo(pos)
        })
        session = s
    }

    private fun toggleTo(play: Boolean) { val on = mp?.isPlaying == true; if (play != on) toggle() }

    private fun publish() {
        val playing = runCatching { mp?.isPlaying == true }.getOrDefault(false)
        _state.value = _state.value.copy(playing = playing)
        val s = session ?: return
        val t = _state.value.track
        s.setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, t?.title).putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t?.album).putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                .apply { _state.value.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it.asAndroidBitmap()) } }.build(),
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        s.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position(), 1f, SystemClock.elapsedRealtime()).build(),
        )
    }

    private fun loadArt(t: Track) {
        if (Build.VERSION.SDK_INT < 29) return
        scope.launch {
            val bmp: Bitmap? = runCatching { ctx.contentResolver.loadThumbnail(t.uri, Size(512, 512), null) }.getOrNull()
            if (_state.value.track === t) { _state.value = _state.value.copy(art = bmp?.asImageBitmap()); publish() }
        }
    }
}
