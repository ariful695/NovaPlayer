@file:OptIn(UnstableApi::class)

package com.nova.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** "Follow the system brightness". The window is only overridden once the user drags. */
const val UNSET_BRIGHTNESS = -1f

private const val KEY_URI = "uri"
private const val KEY_POSITION = "position"
private const val KEY_VOLUME = "volume"
private const val KEY_BRIGHTNESS = "brightness"
private const val KEY_SUB_OFFSET = "subtitle_offset"
private const val CONTROLS_TIMEOUT_MS = 3_500L
private const val SEEK_STEP_MS = 10_000L

enum class Panel { None, Audio, Subtitles }

/** Everything the UI needs except the high-frequency playback position (see [PlaybackProgress]). */
data class PlayerUiState(
    val title: String = "",
    val playWhenReady: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val volumePercent: Float = 100f,
    val maxVolumePercent: Float = AudioBooster.MAX_PERCENT,
    val brightness: Float = UNSET_BRIGHTNESS,
    val controlsVisible: Boolean = true,
    val activePanel: Panel = Panel.None,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleOffsetMs: Long = 0L,
    val error: String? = null,
) {
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
    val isEnded: Boolean get() = playbackState == Player.STATE_ENDED
}

data class PlaybackProgress(
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Owns the ExoPlayer (so it survives rotation and Activity recreation), the [AudioBooster], the
 * [SubtitleController] and the MediaSession.
 *
 * State retention:
 *  - configuration changes: the ViewModel instance itself survives, so playback never restarts;
 *  - process death: URI, position, boost level, brightness and subtitle offset are mirrored into
 *    [SavedStateHandle] and restored by [load].
 */
class PlayerViewModel(
    app: Application,
    private val saved: SavedStateHandle,
) : AndroidViewModel(app) {

    // ------------------------------------------------------------------ player & helpers

    val player: ExoPlayer = ExoPlayer.Builder(
        app,
        DefaultRenderersFactory(app)
            .setEnableDecoderFallback(true) // try the next decoder if the preferred one fails to init
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .build()

    val audioBooster = AudioBooster(app).also { it.attach(player) }

    val subtitles = SubtitleController(
        player = player,
        context = app,
        scope = viewModelScope,
        initialOffsetMs = saved.get<Long>(KEY_SUB_OFFSET) ?: 0L,
    )

    // Handles hardware media keys while the app is in the foreground.
    private val mediaSession = MediaSession.Builder(app, player).build()

    // ------------------------------------------------------------------ state

    private val _ui = MutableStateFlow(
        PlayerUiState(brightness = saved.get<Float>(KEY_BRIGHTNESS) ?: UNSET_BRIGHTNESS),
    )

    /** Core state merged with the booster and subtitle flows into one stable snapshot. */
    val uiState: StateFlow<PlayerUiState> = combine(
        _ui,
        audioBooster.volumePercent,
        audioBooster.maxPercent,
        subtitles.style,
        subtitles.offsetMs,
    ) { ui, volume, maxVolume, style, offset ->
        ui.copy(
            volumePercent = volume,
            maxVolumePercent = maxVolume,
            subtitleStyle = style,
            subtitleOffsetMs = offset,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        _ui.value.copy(
            volumePercent = audioBooster.volumePercent.value,
            maxVolumePercent = audioBooster.maxPercent.value,
            subtitleStyle = subtitles.style.value,
            subtitleOffsetMs = subtitles.offsetMs.value,
        ),
    )

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-shot user-facing messages (shown as toasts). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _volumeKeyEvents = MutableSharedFlow<Float>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits the new volume percent after a hardware volume key press, so the UI can show feedback. */
    val volumeKeyEvents: SharedFlow<Float> = _volumeKeyEvents.asSharedFlow()

    /** Set by the screen; the progress ticker idles while nothing is on screen. */
    var playerScreenVisible: Boolean = false

    private var currentItem: MediaItem? = null
    private var autoHideJob: Job? = null
    private var scrubbing = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _ui.update {
                it.copy(
                    playbackState = playbackState,
                    error = if (playbackState == Player.STATE_READY) null else it.error,
                )
            }
            if (playbackState == Player.STATE_ENDED) showControls() else scheduleAutoHide()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _ui.update { it.copy(playWhenReady = playWhenReady) }
            if (playWhenReady) scheduleAutoHide() else showControls() // paused: keep controls up
        }

        override fun onTracksChanged(tracks: Tracks) {
            _ui.update { it.copy(audioTracks = tracks.toAudioTrackOptions()) }
        }

        override fun onPlayerError(error: PlaybackException) {
            autoHideJob?.cancel()
            _ui.update { it.copy(error = error.userMessage(), controlsVisible = true) }
        }
    }

    init {
        player.addListener(playerListener)

        // Restore a boosted volume after process death (the system volume itself persists on its own).
        saved.get<Float>(KEY_VOLUME)?.takeIf { it > 100f }?.let(audioBooster::setVolumePercent)

        viewModelScope.launch {
            subtitles.offsetMs.collect { saved[KEY_SUB_OFFSET] = it }
        }

        // Position ticker: ~5 Hz while playing, 2 Hz when paused, idle when nothing is visible.
        viewModelScope.launch {
            while (isActive) {
                if (playerScreenVisible) publishProgress()
                delay(if (player.isPlaying) 200L else 500L)
            }
        }
    }

    // ------------------------------------------------------------------ media loading

    /**
     * Idempotent: calling it again with the video that is already loaded (rotation, recomposition,
     * returning to the screen) only refreshes the title. After process death it resumes from the
     * saved position.
     */
    fun load(uri: Uri, title: String) {
        val key = uri.toString()
        val sameAsSaved = saved.get<String>(KEY_URI) == key
        if (player.mediaItemCount > 0 && sameAsSaved) {
            _ui.update { it.copy(title = title) }
            return
        }
        val resumeAt = if (sameAsSaved) (saved.get<Long>(KEY_POSITION) ?: 0L) else 0L
        saved[KEY_URI] = key
        saved[KEY_POSITION] = resumeAt

        subtitles.reset()
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        currentItem = item
        player.setMediaItem(item, resumeAt)
        player.prepare()
        player.playWhenReady = true

        _ui.update {
            it.copy(
                title = title,
                error = null,
                controlsVisible = true,
                activePanel = Panel.None,
                audioTracks = emptyList(),
            )
        }
        scheduleAutoHide()
    }

    /** Back to the library: stop playback and forget what was loaded. */
    fun stop() {
        autoHideJob?.cancel()
        player.stop()
        player.clearMediaItems()
        currentItem = null
        saved.remove<String>(KEY_URI)
        saved.remove<Long>(KEY_POSITION)
        subtitles.reset()
        _progress.value = PlaybackProgress()
        _ui.update { it.copy(controlsVisible = true, activePanel = Panel.None, error = null, audioTracks = emptyList()) }
    }

    fun retry() {
        _ui.update { it.copy(error = null) }
        player.prepare()
        player.play()
    }

    // ------------------------------------------------------------------ playback control

    fun togglePlayPause() {
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0L)
                player.play()
            }

            player.playWhenReady -> player.pause()

            else -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
        showControls()
    }

    /** @param fast snap to the nearest keyframe (used while scrubbing); false = frame-accurate. */
    fun seekTo(positionMs: Long, fast: Boolean) {
        player.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        val duration = player.duration
        val target = if (duration != C.TIME_UNSET) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        player.seekTo(target)
        publishProgress() // so a slider released a moment later doesn't jump back to the old position
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs, fast = false)
        showControls()
    }

    /** Seek bar callback. [fraction] is 0..1 of the duration. */
    fun onScrub(fraction: Float, isFinal: Boolean) {
        scrubbing = !isFinal
        val duration = player.duration
        if (duration != C.TIME_UNSET && duration > 0L) seekTo((fraction * duration).toLong(), fast = !isFinal)
        if (isFinal) scheduleAutoHide() else autoHideJob?.cancel()
    }

    fun currentPositionMs(): Long = player.currentPosition

    fun durationMs(): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

    // ------------------------------------------------------------------ volume & brightness

    fun setVolumePercent(percent: Float) {
        audioBooster.setVolumePercent(percent)
        saved[KEY_VOLUME] = audioBooster.volumePercent.value
    }

    fun onVolumeKey(up: Boolean) {
        val percent = if (up) audioBooster.stepUp() else audioBooster.stepDown()
        saved[KEY_VOLUME] = percent
        _volumeKeyEvents.tryEmit(percent)
    }

    fun setBrightness(value: Float) {
        val v = value.coerceIn(MIN_BRIGHTNESS, 1f)
        saved[KEY_BRIGHTNESS] = v
        _ui.update { it.copy(brightness = v) }
    }

    // ------------------------------------------------------------------ tracks

    fun selectAudioTrack(option: AudioTrackOption) {
        if (!player.selectAudioTrack(option.groupIndex, option.trackIndex)) {
            _messages.tryEmit("That audio track can't be played on this device")
        }
    }

    /**
     * Merges an extra audio file with the current video. This re-prepares the source once; picking
     * between the resulting tracks afterwards is instant.
     */
    fun addExternalAudio(uri: Uri) {
        val item = currentItem ?: return
        val dataSources = DefaultDataSource.Factory(getApplication())
        val video = DefaultMediaSourceFactory(dataSources).createMediaSource(item)
        val audio = ProgressiveMediaSource.Factory(dataSources).createMediaSource(MediaItem.fromUri(uri))
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.setMediaSource(MergingMediaSource(video, audio), position)
        player.prepare()
        player.playWhenReady = wasPlaying
        _messages.tryEmit("External audio added. Choose it from the audio menu.")
    }

    fun addSubtitle(uri: Uri) {
        viewModelScope.launch {
            val ok = subtitles.addExternal(uri)
            _messages.tryEmit(if (ok) "Subtitle loaded" else "Couldn't read that subtitle file")
        }
    }

    // ------------------------------------------------------------------ controls visibility & panels

    fun showControls() {
        _ui.update { it.copy(controlsVisible = true) }
        scheduleAutoHide()
    }

    fun toggleControls() {
        if (_ui.value.controlsVisible) {
            autoHideJob?.cancel()
            _ui.update { it.copy(controlsVisible = false) }
        } else {
            showControls()
        }
    }

    fun openPanel(panel: Panel) {
        autoHideJob?.cancel()
        // The subtitle panel hides the controls so the preview underneath is unobstructed.
        _ui.update { it.copy(activePanel = panel, controlsVisible = panel == Panel.Audio) }
    }

    fun dismissPanel() {
        _ui.update { it.copy(activePanel = Panel.None) }
        showControls()
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        val s = _ui.value
        val keepVisible = !s.controlsVisible || !player.playWhenReady || scrubbing ||
            s.activePanel != Panel.None || s.error != null || player.playbackState == Player.STATE_ENDED
        if (keepVisible) return
        autoHideJob = viewModelScope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            _ui.update { it.copy(controlsVisible = false) }
        }
    }

    // ------------------------------------------------------------------ host lifecycle

    /** Activity.onPause: stop playing and persist where we were. */
    fun onHostPause(isChangingConfigurations: Boolean) {
        publishProgress()
        if (!isChangingConfigurations) player.pause()
    }

    /** Activity.onResume: the user may have changed the volume elsewhere in the meantime. */
    fun onHostResume() {
        audioBooster.syncFromSystem()
        showControls()
    }

    private fun publishProgress() {
        if (player.mediaItemCount == 0) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = player.currentPosition
        _progress.value = PlaybackProgress(position, player.bufferedPosition, duration)
        saved[KEY_POSITION] = if (player.playbackState == Player.STATE_ENDED) 0L else position
    }

    // ------------------------------------------------------------------ teardown

    /** Runs when the Activity is really finishing (not on rotation): release everything exactly once. */
    override fun onCleared() {
        autoHideJob?.cancel()
        player.removeListener(playerListener)
        subtitles.release()
        audioBooster.release()
        mediaSession.release() // before the player it wraps
        player.release()
    }
}

private fun PlaybackException.userMessage(): String = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> "This device can't decode the video or audio format."

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file wasn't found."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "No permission to read this file."

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    -> "The file is damaged or in an unsupported format."

    else -> "Playback failed ($errorCodeName)."
}
