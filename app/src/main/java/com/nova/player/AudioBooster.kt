package com.nova.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Volume control from 0 % to 400 %.
 *
 *  - 0 – 100 %   -> the real device volume (`AudioManager.STREAM_MUSIC` index).
 *  - 101 – 400 % -> STREAM_MUSIC is pinned at its maximum and a digital gain is applied to the
 *                   player's audio session with [LoudnessEnhancer].
 *
 * The gain is expressed in amplitude terms: `gain_dB = 20 * log10(percent / 100)`, so 200 % is
 * +6 dB (2x amplitude) and 400 % is +12 dB (4x). `LoudnessEnhancer.setTargetGain` takes milli-bels
 * (1 dB = 100 mB), hence the factor 2000 below. LoudnessEnhancer includes a limiter, so peaks are
 * compressed rather than hard-clipped, but very loud sources will still sound distorted at 400 %.
 *
 * Threading: all methods must be called on the main thread (the same thread ExoPlayer is bound to).
 */
class AudioBooster(context: Context) {

    companion object {
        private const val TAG = "AudioBooster"
        private const val EPS = 0.01f

        const val MAX_PERCENT = 400f

        /** Step used by hardware volume keys while above 100 %. */
        const val BOOST_STEP_PERCENT = 25f

        private val MAX_GAIN_MB = (2000.0 * log10(MAX_PERCENT / 100.0)).roundToInt() // ~1204 mB

        /** Digital gain, in milli-bels, for a given volume percentage (0 below or at 100 %). */
        fun gainMilliBelFor(percent: Float): Int {
            if (percent <= 100f) return 0
            return (2000.0 * log10(percent / 100.0)).roundToInt().coerceIn(0, MAX_GAIN_MB)
        }
    }

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: ExoPlayer? = null
    private var enhancer: LoudnessEnhancer? = null

    private val _volumePercent = MutableStateFlow(readSystemPercent())

    /** Current volume, 0..[maxPercent]. */
    val volumePercent: StateFlow<Float> = _volumePercent.asStateFlow()

    private val _maxPercent = MutableStateFlow(MAX_PERCENT)

    /** 400 normally; drops to 100 when the device refuses to create a LoudnessEnhancer. */
    val maxPercent: StateFlow<Float> = _maxPercent.asStateFlow()

    /**
     * ExoPlayer can change its audio session (e.g. after a renderer reset or tunneling switch).
     * An effect bound to a stale session silently does nothing, so we rebuild it every time.
     */
    private val sessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) = rebuildEnhancer(audioSessionId)
    }

    // ---------------------------------------------------------------- lifecycle

    @MainThread
    fun attach(exoPlayer: ExoPlayer) {
        if (player === exoPlayer) return
        detach()
        player = exoPlayer
        exoPlayer.addListener(sessionListener)
        rebuildEnhancer(exoPlayer.audioSessionId)
    }

    @MainThread
    fun detach() {
        player?.let {
            it.removeListener(sessionListener)
            if (audioManager.isVolumeFixed) it.volume = 1f
        }
        player = null
        releaseEnhancer()
    }

    /** Call from ViewModel.onCleared(). Safe to call more than once. */
    fun release() = detach()

    // ---------------------------------------------------------------- public control

    /** Sets the volume (0..[maxPercent]); out-of-range input is clamped. */
    @MainThread
    fun setVolumePercent(percent: Float) {
        if (percent.isNaN()) return
        val target = percent.coerceIn(0f, _maxPercent.value)
        _volumePercent.value = target
        if (target <= 100f) {
            applyGain(0)                       // drop the boost first, then lower the system volume
            applyBaseVolume(target / 100f)
        } else {
            applyBaseVolume(1f)                // lock the system volume at 100 %
            applyGain(gainMilliBelFor(target)) // then add the digital gain on top
        }
    }

    /** One hardware-key step up: system-volume steps below 100 %, 25 % steps above. Returns new percent. */
    @MainThread
    fun stepUp(): Float {
        val cur = _volumePercent.value
        val next = if (cur < 100f - EPS) min(100f, cur + systemStepPercent()) else cur + BOOST_STEP_PERCENT
        setVolumePercent(next)
        return _volumePercent.value
    }

    /** One hardware-key step down. Returns new percent. */
    @MainThread
    fun stepDown(): Float {
        val cur = _volumePercent.value
        val next = if (cur > 100f + EPS) max(100f, cur - BOOST_STEP_PERCENT) else max(0f, cur - systemStepPercent())
        setVolumePercent(next)
        return _volumePercent.value
    }

    /**
     * Re-reads the system volume. Call on resume: the user may have changed the volume from the
     * notification shade or another app. If the system volume is no longer at its maximum the boost
     * is dropped, because it can't be "above 100 %" any more.
     */
    @MainThread
    fun syncFromSystem() {
        if (audioManager.isVolumeFixed) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (_volumePercent.value > 100f && cur >= max) return // still boosted, nothing changed
        _volumePercent.value = readSystemPercent()
        applyGain(0)
    }

    // ---------------------------------------------------------------- internals

    private fun systemStepPercent(): Float =
        100f / max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

    private fun readSystemPercent(): Float {
        val max = max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max
    }

    /** Applies the 0..1 "base" volume to the device (or to the player on fixed-volume outputs). */
    private fun applyBaseVolume(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (audioManager.isVolumeFixed) {
            // HDMI / some TV and car outputs ignore setStreamVolume; fall back to the player gain.
            player?.volume = f
            return
        }
        player?.volume = 1f
        try {
            val maxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = (f * maxIndex).roundToInt().coerceIn(0, maxIndex)
            if (index != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                // flags = 0: no system volume panel, our own overlay is the feedback.
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "System refused to change STREAM_MUSIC volume", e)
        }
    }

    private fun rebuildEnhancer(sessionId: Int) {
        releaseEnhancer()
        // Session 0 would attach to the global output mix, which needs extra permissions and is deprecated.
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            enhancer = LoudnessEnhancer(sessionId)
            _maxPercent.value = MAX_PERCENT
            applyGain(gainMilliBelFor(_volumePercent.value))
        } catch (e: Exception) {
            // Some OEM builds throw RuntimeException / UnsupportedOperationException / IllegalArgumentException.
            Log.w(TAG, "LoudnessEnhancer unavailable, boost disabled", e)
            disableBoost()
        }
    }

    private fun applyGain(gainMb: Int) {
        val e = enhancer ?: return
        try {
            e.setTargetGain(gainMb)
            // Bypass the effect entirely at 0 dB: saves DSP work and keeps the signal bit-exact.
            e.setEnabled(gainMb > 0)
        } catch (t: Exception) {
            Log.w(TAG, "Failed to apply gain", t)
            disableBoost()
        }
    }

    private fun disableBoost() {
        releaseEnhancer()
        _maxPercent.value = 100f
        if (_volumePercent.value > 100f) setVolumePercent(100f)
    }

    private fun releaseEnhancer() {
        val e = enhancer ?: return
        enhancer = null
        try {
            e.release()
        } catch (t: Exception) {
            Log.w(TAG, "Failed to release LoudnessEnhancer", t)
        }
    }
}
