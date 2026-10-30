@file:OptIn(UnstableApi::class, ExperimentalLayoutApi::class)

package com.nova.player

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/**
 * Full-screen player. Layer order, bottom to top:
 *
 *   PlayerView (video only)  ->  SubtitleOverlay  ->  GestureOverlay  ->  controls  ->  panels / sheet
 *
 * Controls sit above the gesture layer, so while they're visible their buttons get the touches;
 * when they auto-hide they leave composition and every touch reaches [GestureOverlay].
 */
@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel,
    uri: Uri,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val cues by viewModel.subtitles.cues.collectAsStateWithLifecycle()
    val subtitleOptions by viewModel.subtitles.options.collectAsStateWithLifecycle()
    val canAdvance by viewModel.subtitles.canAdvance.collectAsStateWithLifecycle()

    val feedback = rememberGestureFeedbackState()
    var resizeMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // The gesture layer needs a concrete 0..1 value, so resolve "system default" once.
    val systemBrightness = remember(activity) { activity?.readWindowBrightness() ?: 0.5f }
    val displayBrightness = if (ui.brightness >= 0f) ui.brightness else systemBrightness

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        picked?.let(viewModel::addSubtitle)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        picked?.let(viewModel::addExternalAudio)
    }

    // ---- load (idempotent: same URI after rotation / re-entry is a no-op) ----
    LaunchedEffect(uri, title) { viewModel.load(uri, title) }

    // ---- immersive mode, display cutout, brightness restore ----
    DisposableEffect(activity) {
        activity?.hideSystemBars()
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = activity.window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            activity.window.attributes = params
        }
        viewModel.playerScreenVisible = true
        onDispose {
            viewModel.playerScreenVisible = false
            activity?.let { a ->
                WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars())
                a.setWindowBrightness(BRIGHTNESS_FOLLOW_SYSTEM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val params = a.window.attributes
                    params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    a.window.attributes = params
                }
            }
        }
    }

    // Re-apply a user-chosen brightness after recreation or re-entry.
    LaunchedEffect(activity) {
        if (ui.brightness >= 0f) activity?.setWindowBrightness(ui.brightness)
    }

    // Panels live in their own window (bottom sheet) and can bring the system bars back.
    LaunchedEffect(ui.activePanel) { activity?.hideSystemBars() }

    // ---- Activity lifecycle: onResume / onPause. onDestroy is handled by the ViewModel (onCleared). ----
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onHostResume()
                    activity?.hideSystemBars()
                }

                Lifecycle.Event.ON_PAUSE -> viewModel.onHostPause(activity?.isChangingConfigurations == true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- one-shot events ----
    LaunchedEffect(viewModel) {
        launch {
            viewModel.volumeKeyEvents.collect { percent ->
                feedback.show(GestureFeedback.Volume(percent, viewModel.audioBooster.maxPercent.value))
            }
        }
        launch {
            viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    BackHandler {
        if (ui.activePanel != Panel.None) viewModel.dismissPanel() else onBack()
    }

    val subtitleBottomPadding by animateDpAsState(if (ui.controlsVisible) 104.dp else 28.dp, label = "subtitleInset")

    Box(modifier.fillMaxSize().background(Color.Black)) {

        // 1. Video surface. Media3's own SubtitleView is hidden; SubtitleOverlay draws the text.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    subtitleView?.visibility = View.GONE
                }
            },
            update = { view ->
                view.player = viewModel.player
                view.resizeMode = resizeMode
            },
            onRelease = { view -> view.player = null }, // detach the Surface from the player
        )

        // 2. Subtitles
        SubtitleOverlay(cues = cues, style = ui.subtitleStyle, bottomPadding = subtitleBottomPadding)

        // 3. Gestures
        GestureOverlay(
            brightness = displayBrightness,
            volumePercent = ui.volumePercent,
            maxVolumePercent = ui.maxVolumePercent,
            onBrightnessChange = viewModel::setBrightness,
            onVolumeChange = viewModel::setVolumePercent, // -> AudioBooster
            positionMs = viewModel::currentPositionMs,
            durationMs = viewModel::durationMs,
            onSeek = { target, isFinal -> viewModel.seekTo(target, fast = !isFinal) },
            onTap = {
                if (ui.activePanel != Panel.None) viewModel.dismissPanel() else viewModel.toggleControls()
            },
            feedback = feedback,
        )

        // Standalone spinner when the controls (which show their own) are hidden.
        if (ui.isBuffering && !ui.controlsVisible && ui.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        // 4. Controls
        PlayerControls(
            visible = ui.controlsVisible && ui.error == null,
            title = ui.title,
            playWhenReady = ui.playWhenReady,
            isBuffering = ui.isBuffering,
            progress = progress,
            onBack = onBack,
            onPlayPause = viewModel::togglePlayPause,
            onSeekBy = viewModel::seekBy,
            onScrub = viewModel::onScrub,
            onAudio = { viewModel.openPanel(Panel.Audio) },
            onSubtitles = { viewModel.openPanel(Panel.Subtitles) },
            onResize = {
                resizeMode = when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
        )

        // 5. Playback error
        ui.error?.let { message ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::retry) { Text("Try again") }
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
        }

        // 6. Panels
        SubtitleSettingsPanel(
            visible = ui.activePanel == Panel.Subtitles,
            style = ui.subtitleStyle,
            offsetMs = ui.subtitleOffsetMs,
            canAdvance = canAdvance,
            options = subtitleOptions,
            onStyleChange = viewModel.subtitles::updateStyle,
            onSelectSource = viewModel.subtitles::select,
            onAddFile = { subtitlePicker.launch(arrayOf("*/*")) },
            onOffsetStep = viewModel.subtitles::nudgeOffset,
            onOffsetReset = viewModel.subtitles::resetOffset,
            onResetStyle = viewModel.subtitles::resetStyle,
            onClose = viewModel::dismissPanel,
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        if (ui.activePanel == Panel.Audio) {
            AudioTrackBottomSheet(
                options = ui.audioTracks,
                onSelect = viewModel::selectAudioTrack,
                onAddExternal = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
                onDismiss = viewModel::dismissPanel,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Controls
// ------------------------------------------------------------------------------------------------

@Composable
private fun PlayerControls(
    visible: Boolean,
    title: String,
    playWhenReady: Boolean,
    isBuffering: Boolean,
    progress: PlaybackProgress,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onScrub: (fraction: Float, isFinal: Boolean) -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onResize: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {

            // ---- top bar ----
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(onClick = onResize) {
                    Icon(Icons.Rounded.AspectRatio, contentDescription = "Change aspect ratio", tint = Color.White)
                }
                IconButton(onClick = onAudio) {
                    Icon(Icons.Rounded.Audiotrack, contentDescription = "Audio tracks", tint = Color.White)
                }
                IconButton(onClick = onSubtitles) {
                    Icon(Icons.Rounded.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                }
            }

            // ---- centre transport ----
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSeekBy(-10_000L) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "Back 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(72.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            imageVector = if (playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playWhenReady) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                IconButton(onClick = { onSeekBy(10_000L) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }

            // ---- bottom: seek bar + time ----
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                // While dragging, the slider shows the finger position, not the (lagging) player position.
                var scrub by remember { mutableStateOf<Float?>(null) }
                val duration = progress.durationMs
                val fraction = if (duration > 0L) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                val shownPosition = scrub?.let { (it * duration).toLong() } ?: progress.positionMs

                Slider(
                    value = scrub ?: fraction,
                    onValueChange = {
                        scrub = it
                        onScrub(it, false)
                    },
                    onValueChangeFinished = {
                        scrub?.let { f -> onScrub(f, true) }
                        scrub = null
                    },
                    enabled = duration > 0L,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTime(shownPosition), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = if (duration > 0L) formatTime(duration) else "--:--",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private fun Activity.hideSystemBars() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}
