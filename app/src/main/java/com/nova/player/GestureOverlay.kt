package com.nova.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------------------------
// Tunables
// ------------------------------------------------------------------------------------------------

/** Never go fully dark: brightness 0 turns the panel off on some devices. */
internal const val MIN_BRIGHTNESS = 0.02f

/** A vertical swipe over 90 % of the view height covers the full brightness / volume range. */
private const val SWIPE_RANGE_FRACTION = 0.9f

/** A horizontal swipe across the full width scrubs two minutes. */
private const val SEEK_MS_PER_SCREEN_WIDTH = 120_000f
private const val SEEK_THROTTLE_MS = 100L
private const val DOUBLE_TAP_SEEK_MS = 10_000L
private const val FEEDBACK_HIDE_DELAY_MS = 600L

private val BoostColor = Color(0xFFFFA726)

// ------------------------------------------------------------------------------------------------
// Feedback state
// ------------------------------------------------------------------------------------------------

sealed interface GestureFeedback {
    data class Brightness(val fraction: Float) : GestureFeedback
    data class Volume(val percent: Float, val maxPercent: Float = AudioBooster.MAX_PERCENT) : GestureFeedback
    data class Seek(val deltaMs: Long, val targetMs: Long) : GestureFeedback
}

/**
 * Drives the transient on-screen indicators. Kept public so the screen can also flash a volume
 * indicator when the change comes from the hardware volume keys instead of a drag.
 */
@Stable
class GestureFeedbackState internal constructor(private val scope: CoroutineScope) {
    var visible by mutableStateOf(false)
        private set
    var active by mutableStateOf<GestureFeedback?>(null)
        private set

    // The last value of each kind is retained so an indicator keeps its content while it fades out.
    var brightness by mutableStateOf(GestureFeedback.Brightness(0f))
        private set
    var volume by mutableStateOf(GestureFeedback.Volume(0f))
        private set
    var seek by mutableStateOf(GestureFeedback.Seek(0L, 0L))
        private set

    private var hideJob: Job? = null

    /** @param holdUntilReleased keep the indicator up until [release] (used while a finger is down). */
    fun show(feedback: GestureFeedback, holdUntilReleased: Boolean = false) {
        when (feedback) {
            is GestureFeedback.Brightness -> brightness = feedback
            is GestureFeedback.Volume -> volume = feedback
            is GestureFeedback.Seek -> seek = feedback
        }
        active = feedback
        visible = true
        if (holdUntilReleased) hideJob?.cancel() else scheduleHide()
    }

    /** Finger lifted: start the fade-out timer. */
    fun release() = scheduleHide()

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(FEEDBACK_HIDE_DELAY_MS)
            visible = false
        }
    }
}

@Composable
fun rememberGestureFeedbackState(): GestureFeedbackState {
    val scope = rememberCoroutineScope()
    return remember(scope) { GestureFeedbackState(scope) }
}

// ------------------------------------------------------------------------------------------------
// Overlay
// ------------------------------------------------------------------------------------------------

private enum class DragMode { None, Undecided, Brightness, Volume, Seek, Ignored }
private enum class Side { Left, Right }

/**
 * Transparent touch layer that sits above the video surface.
 *
 *  - vertical drag, left half  -> window brightness
 *  - vertical drag, right half -> volume 0..[maxVolumePercent] (delegated to AudioBooster by the caller)
 *  - horizontal drag           -> scrub (throttled live seeks, exact seek on release)
 *  - single tap                -> [onTap];  double tap left / right -> -10 s / +10 s
 *
 * The axis is locked by the first movement after touch slop, so a mostly-vertical swipe can't
 * accidentally start seeking halfway through.
 *
 * @param brightness current brightness, already resolved to 0..1 (never "system default")
 * @param positionMs / durationMs read lazily so the overlay doesn't recompose at playback rate
 */
@Composable
fun GestureOverlay(
    brightness: Float,
    volumePercent: Float,
    maxVolumePercent: Float,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    positionMs: () -> Long,
    durationMs: () -> Long,
    onSeek: (targetMs: Long, isFinal: Boolean) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    feedback: GestureFeedbackState = rememberGestureFeedbackState(),
) {
    val activity = LocalContext.current.findActivity()
    val haptics = LocalHapticFeedback.current

    // The pointerInput blocks below run for the lifetime of the composition, so they must read the
    // latest values through State instead of capturing stale parameters.
    val brightnessNow by rememberUpdatedState(brightness)
    val volumeNow by rememberUpdatedState(volumePercent)
    val maxVolumeNow by rememberUpdatedState(maxVolumePercent)
    val onBrightnessNow by rememberUpdatedState(onBrightnessChange)
    val onVolumeNow by rememberUpdatedState(onVolumeChange)
    val positionNow by rememberUpdatedState(positionMs)
    val durationNow by rememberUpdatedState(durationMs)
    val onSeekNow by rememberUpdatedState(onSeek)
    val onTapNow by rememberUpdatedState(onTap)

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTapNow() },
                    onDoubleTap = { offset ->
                        val duration = durationNow()
                        if (duration > 0L) {
                            val forward = offset.x >= size.width / 2f
                            val start = positionNow()
                            val delta = if (forward) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS
                            val target = (start + delta).coerceIn(0L, duration)
                            onSeekNow(target, true)
                            feedback.show(GestureFeedback.Seek(target - start, target))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                var mode = DragMode.None
                var side = Side.Left
                var accX = 0f
                var accY = 0f
                var startBrightness = 0f
                var startVolume = 0f
                var lastVolume = 0f
                var startPosition = 0L
                var duration = 0L
                var lastTarget = 0L
                var lastSeekUptime = 0L

                fun finish() {
                    if (mode == DragMode.Seek) onSeekNow(lastTarget, true) // exact seek on release
                    if (mode != DragMode.None && mode != DragMode.Ignored) feedback.release()
                    mode = DragMode.None
                }

                detectDragGestures(
                    onDragStart = { offset ->
                        mode = DragMode.Undecided
                        accX = 0f
                        accY = 0f
                        side = if (offset.x < size.width / 2f) Side.Left else Side.Right
                    },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                    onDrag = { change, amount ->
                        change.consume()
                        accX += amount.x
                        accY += amount.y

                        if (mode == DragMode.Undecided) {
                            mode = when {
                                abs(accX) > abs(accY) -> {
                                    duration = durationNow()
                                    if (duration > 0L) {
                                        startPosition = positionNow()
                                        lastTarget = startPosition
                                        DragMode.Seek
                                    } else {
                                        DragMode.Ignored // live stream: nothing to scrub
                                    }
                                }
                                side == Side.Left -> {
                                    startBrightness = brightnessNow
                                    DragMode.Brightness
                                }
                                else -> {
                                    startVolume = volumeNow
                                    lastVolume = startVolume
                                    DragMode.Volume
                                }
                            }
                        }

                        val range = size.height * SWIPE_RANGE_FRACTION
                        when (mode) {
                            DragMode.Brightness -> {
                                val value = (startBrightness - accY / range).coerceIn(MIN_BRIGHTNESS, 1f)
                                // Written straight to the window so it tracks the finger without waiting for recomposition.
                                activity?.setWindowBrightness(value)
                                onBrightnessNow(value)
                                feedback.show(GestureFeedback.Brightness(value), holdUntilReleased = true)
                            }

                            DragMode.Volume -> {
                                val maxV = maxVolumeNow
                                val percent = (startVolume - accY / range * maxV).coerceIn(0f, maxV)
                                // Tick when crossing the 100 % boundary so the boost zone can be felt.
                                if ((lastVolume <= 100f) != (percent <= 100f)) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                lastVolume = percent
                                onVolumeNow(percent)
                                feedback.show(GestureFeedback.Volume(percent, maxV), holdUntilReleased = true)
                            }

                            DragMode.Seek -> {
                                val deltaMs = (accX / size.width * SEEK_MS_PER_SCREEN_WIDTH).toLong()
                                val target = (startPosition + deltaMs).coerceIn(0L, duration)
                                lastTarget = target
                                feedback.show(GestureFeedback.Seek(target - startPosition, target), holdUntilReleased = true)
                                // Live scrubbing, throttled so the decoder isn't flooded with seeks.
                                if (change.uptimeMillis - lastSeekUptime >= SEEK_THROTTLE_MS) {
                                    lastSeekUptime = change.uptimeMillis
                                    onSeekNow(target, false)
                                }
                            }

                            else -> Unit
                        }
                    },
                )
            },
    ) {
        FeedbackLayer(feedback)
    }
}

// ------------------------------------------------------------------------------------------------
// Visual feedback
// ------------------------------------------------------------------------------------------------

@Composable
private fun BoxScope.FeedbackLayer(state: GestureFeedbackState) {
    val enter = fadeIn(tween(120))
    val exit = fadeOut(tween(450))

    AnimatedVisibility(
        visible = state.visible && state.active is GestureFeedback.Brightness,
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 56.dp),
        enter = enter,
        exit = exit,
    ) {
        val b = state.brightness
        LevelIndicator(
            icon = when {
                b.fraction < 0.34f -> Icons.Rounded.BrightnessLow
                b.fraction < 0.67f -> Icons.Rounded.BrightnessMedium
                else -> Icons.Rounded.BrightnessHigh
            },
            fraction = b.fraction,
            label = "${(b.fraction * 100).roundToInt()}%",
            accent = Color.White,
        )
    }

    AnimatedVisibility(
        visible = state.visible && state.active is GestureFeedback.Volume,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 56.dp),
        enter = enter,
        exit = exit,
    ) {
        val v = state.volume
        LevelIndicator(
            icon = when {
                v.percent <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                v.percent < 50f -> Icons.AutoMirrored.Rounded.VolumeDown
                else -> Icons.AutoMirrored.Rounded.VolumeUp
            },
            fraction = v.percent / v.maxPercent,
            label = "${v.percent.roundToInt()}%",
            accent = if (v.percent > 100f) BoostColor else Color.White,
            // A tick marks where the system volume ends and the digital boost begins.
            boostMark = if (v.maxPercent > 100f) 100f / v.maxPercent else null,
        )
    }

    AnimatedVisibility(
        visible = state.visible && state.active is GestureFeedback.Seek,
        modifier = Modifier.align(Alignment.Center),
        enter = enter,
        exit = exit,
    ) {
        val s = state.seek
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 28.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatSignedDelta(s.deltaMs),
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = formatTime(s.targetMs), color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
        }
    }
}

@Composable
private fun LevelIndicator(
    icon: ImageVector,
    fraction: Float,
    label: String,
    accent: Color,
    boostMark: Float? = null,
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(80), label = "level")
    val barHeight = 120.dp
    Column(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
        Box(
            Modifier
                .width(6.dp)
                .height(barHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(animated).background(accent))
            if (boostMark != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-(barHeight.value * boostMark)).dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White),
                )
            }
        }
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------------------------------------
// Helpers shared with the rest of the player UI
// ------------------------------------------------------------------------------------------------

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Sets `window.attributes.screenBrightness` (0..1). Pass -1f to hand control back to the system. */
internal fun Activity.setWindowBrightness(value: Float) {
    val params = window.attributes
    params.screenBrightness = value
    window.attributes = params
}

/** Window brightness if overridden, otherwise the system setting, otherwise 0.5. */
internal fun Activity.readWindowBrightness(): Float {
    val override = window.attributes.screenBrightness
    if (override >= 0f) return override
    return runCatching {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f).coerceIn(MIN_BRIGHTNESS, 1f)
}

internal const val BRIGHTNESS_FOLLOW_SYSTEM = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

/** 83 -> "01:23", 3725 s -> "1:02:05". */
fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val h = totalSeconds / 3600L
    val m = (totalSeconds % 3600L) / 60L
    val s = totalSeconds % 60L
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

/** "+00:15" / "-00:30" */
fun formatSignedDelta(ms: Long): String = (if (ms < 0) "-" else "+") + formatTime(abs(ms))
