@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.nova.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch
import java.util.Locale

// ------------------------------------------------------------------------------------------------
// Model
// ------------------------------------------------------------------------------------------------

/**
 * One selectable audio stream. Indices point into `player.currentTracks.groups`; they are only
 * ever used together with a fresh lookup (see [selectAudioTrack]) so a stale option can't select
 * the wrong stream.
 */
data class AudioTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val title: String,
    val details: String,
    val isSelected: Boolean,
    val isSupported: Boolean,
)

/**
 * Flattens every audio track ExoPlayer knows about. That covers tracks embedded in the container
 * (MKV / MP4 / TS...), alternate renditions from HLS / DASH manifests, and audio files merged in
 * with MergingMediaSource (see PlayerViewModel.addExternalAudio).
 */
fun Tracks.toAudioTrackOptions(): List<AudioTrackOption> {
    val out = ArrayList<AudioTrackOption>()
    for ((gi, group) in groups.withIndex()) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (ti in 0 until group.length) {
            val format = group.getTrackFormat(ti)
            out += AudioTrackOption(
                groupIndex = gi,
                trackIndex = ti,
                title = format.trackTitle(out.size + 1),
                details = format.audioDetails(),
                isSelected = group.isTrackSelected(ti),
                isSupported = group.isTrackSupported(ti),
            )
        }
    }
    return out
}

/**
 * Switches the active audio stream.
 *
 * This only updates `trackSelectionParameters`, which ExoPlayer applies in place: no re-prepare,
 * no seek, and the video renderer is left alone, so the picture never stalls. Only the audio
 * renderer is re-pointed at the new stream; since the player keeps its position, the new audio
 * resumes from the current playback position. Depending on the source, the audio pipeline may need
 * a moment to refill, which is a short audio gap rather than a video rebuffer.
 */
fun ExoPlayer.selectAudioTrack(groupIndex: Int, trackIndex: Int): Boolean {
    val group = currentTracks.groups.getOrNull(groupIndex) ?: return false
    if (group.type != C.TRACK_TYPE_AUDIO) return false
    if (trackIndex !in 0 until group.length || !group.isTrackSupported(trackIndex)) return false
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        // setOverrideForType replaces any earlier audio override instead of stacking them.
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        .build()
    return true
}

// ------------------------------------------------------------------------------------------------
// Human-readable labels
// ------------------------------------------------------------------------------------------------

/** "English", "Japanese · Commentary", or "Track 3" when the file carries no metadata. */
internal fun Format.trackTitle(ordinal: Int): String {
    val languageName = language
        ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        ?.takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    val extra = label?.takeIf { it.isNotBlank() && !it.equals(languageName, ignoreCase = true) }
    return listOfNotNull(languageName, extra).joinToString(" · ").ifEmpty { "Track $ordinal" }
}

/** "5.1 · Dolby Digital Plus · 48 kHz · 640 kbps" */
internal fun Format.audioDetails(): String = listOfNotNull(
    channelLabel(channelCount),
    codecLabel(sampleMimeType),
    sampleRate.takeIf { it > 0 }?.let { hz ->
        if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.ROOT, "%.1f kHz", hz / 1000f)
    },
    bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
).joinToString(" · ")

private fun channelLabel(count: Int): String? = when (count) {
    Format.NO_VALUE, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
    3 -> "2.1"
    4 -> "4.0"
    5 -> "5.0"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "$count ch"
}

private fun codecLabel(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
    MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_ALAC -> "ALAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_MPEG_L2 -> "MP2"
    MimeTypes.AUDIO_RAW -> "PCM"
    else -> mime?.substringAfter('/')?.uppercase(Locale.ROOT)
}

// ------------------------------------------------------------------------------------------------
// UI
// ------------------------------------------------------------------------------------------------

@Composable
fun AudioTrackBottomSheet(
    options: List<AudioTrackOption>,
    onSelect: (AudioTrackOption) -> Unit,
    onAddExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun closeAfter(action: () -> Unit) {
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ImmersiveDialogEffect()
        Text(
            text = "Audio",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (options.isEmpty()) {
            Text(
                text = "No audio tracks found in this file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(options, key = { "${it.groupIndex}:${it.trackIndex}" }) { option ->
                AudioTrackRow(option) { closeAfter { onSelect(option) } }
            }
        }
        OutlinedButton(
            onClick = { closeAfter(onAddExternal) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Add external audio file", modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun AudioTrackRow(option: AudioTrackOption, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = option.isSupported, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = option.isSelected, onClick = null, enabled = option.isSupported)
        Column(Modifier.weight(1f)) {
            Text(
                text = option.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (option.isSupported) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            val subtitle = if (option.isSupported) option.details else "Not supported on this device. ${option.details}"
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Dialog-like windows (the bottom sheet) get their own window, which resets the system-bar state
 * and makes the status/navigation bars pop back during playback. Hide them again on that window.
 */
@Composable
internal fun ImmersiveDialogEffect() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
