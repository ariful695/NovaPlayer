@file:OptIn(UnstableApi::class, ExperimentalLayoutApi::class)

package com.nova.player

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------------------------
// Style model (persisted)
// ------------------------------------------------------------------------------------------------

enum class SubtitleFont(val label: String, val family: FontFamily) {
    Sans("Sans", FontFamily.SansSerif),
    Serif("Serif", FontFamily.Serif),
    Mono("Mono", FontFamily.Monospace),
    Cursive("Cursive", FontFamily.Cursive),
    Condensed("Condensed", FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))),
}

/** Colors are ARGB ints so the model stays trivially serialisable. */
data class SubtitleStyle(
    val textSizeSp: Float = 20f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0x99000000.toInt(),
    val strokeWidthDp: Float = 1.5f,
    val strokeColor: Int = 0xFF000000.toInt(),
    val font: SubtitleFont = SubtitleFont.Sans,
    val bold: Boolean = false,
) {
    companion object {
        const val MIN_SIZE_SP = 12f
        const val MAX_SIZE_SP = 40f
        const val MAX_STROKE_DP = 6f
    }
}

private class StyleStore(context: Context) {
    private val prefs = context.getSharedPreferences("subtitle_style", Context.MODE_PRIVATE)

    fun load(): SubtitleStyle {
        val d = SubtitleStyle()
        return SubtitleStyle(
            textSizeSp = prefs.getFloat("size", d.textSizeSp),
            textColor = prefs.getInt("text", d.textColor),
            backgroundColor = prefs.getInt("bg", d.backgroundColor),
            strokeWidthDp = prefs.getFloat("stroke", d.strokeWidthDp),
            strokeColor = prefs.getInt("strokeColor", d.strokeColor),
            font = SubtitleFont.entries.firstOrNull { it.name == prefs.getString("font", null) } ?: d.font,
            bold = prefs.getBoolean("bold", d.bold),
        )
    }

    fun save(s: SubtitleStyle) {
        prefs.edit()
            .putFloat("size", s.textSizeSp)
            .putInt("text", s.textColor)
            .putInt("bg", s.backgroundColor)
            .putFloat("stroke", s.strokeWidthDp)
            .putInt("strokeColor", s.strokeColor)
            .putString("font", s.font.name)
            .putBoolean("bold", s.bold)
            .apply()
    }
}

// ------------------------------------------------------------------------------------------------
// Controller
// ------------------------------------------------------------------------------------------------

data class SubtitleOption(
    val id: String,
    val label: String,
    val isExternal: Boolean,
    val isSelected: Boolean,
)

private class TimedCues(val startUs: Long, val endUs: Long, val cues: List<Cue>)
private class ExternalSubtitle(val id: String, val label: String, val entries: List<TimedCues>)

/**
 * Owns everything subtitle-related: style, source selection, and timing offset.
 *
 * ExoPlayer has no "subtitle delay" parameter: its TextRenderer pushes cues at the exact media
 * timestamp. So the controller owns cue timing itself:
 *
 *  - External files (.srt/.vtt/.ass/.ssa/.ttml) are parsed once with Media3's own extractor parsers
 *    and looked up against `player.currentPosition - offset` every 50 ms. The offset can be
 *    changed instantly in both directions with no re-prepare and no rebuffer.
 *  - Embedded tracks keep flowing through ExoPlayer (`onCues`). They can be delayed (cues are held
 *    back by the offset) but not advanced, because future cues don't exist yet.
 *
 * ExoPlayer's own text output is disabled while an external file is active, and PlayerView's
 * SubtitleView is hidden; [SubtitleOverlay] is the only thing drawing text.
 */
class SubtitleController(
    private val player: ExoPlayer,
    context: Context,
    private val scope: CoroutineScope,
    initialOffsetMs: Long = 0L,
) {
    companion object {
        const val OFFSET_STEP_MS = 100L
        const val MAX_OFFSET_MS = 30_000L
        private const val TAG = "SubtitleController"
        private const val OFF_ID = "off"
        private const val TICK_MS = 50L
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    }

    private val appContext = context.applicationContext
    private val store = StyleStore(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _style = MutableStateFlow(store.load())
    val style: StateFlow<SubtitleStyle> = _style.asStateFlow()

    private val _offsetMs = MutableStateFlow(initialOffsetMs)

    /** Positive = subtitles appear later, negative = earlier. */
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    private val _cues = MutableStateFlow<List<Cue>>(emptyList())

    /** Cues to draw right now, already offset-corrected. */
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    private val _options = MutableStateFlow(listOf(SubtitleOption(OFF_ID, "Off", false, true)))
    val options: StateFlow<List<SubtitleOption>> = _options.asStateFlow()

    private val _canAdvance = MutableStateFlow(false)

    /** True when negative offsets are possible (only for external files). */
    val canAdvance: StateFlow<Boolean> = _canAdvance.asStateFlow()

    private val externals = mutableListOf<ExternalSubtitle>()
    private var activeExternal: ExternalSubtitle? = null
    private var selectedId: String? = null // null = follow whatever ExoPlayer selected
    private var tickerJob: Job? = null
    private var nextExternalId = 0

    private val listener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            if (activeExternal == null) publishEmbedded(cueGroup.cues)
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (activeExternal == null) selectedId = null
            refreshOptions()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek invalidates cues that were queued for the old timeline.
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    init {
        player.addListener(listener)
        setOffset(initialOffsetMs)
        refreshOptions()
    }

    // ------------------------------------------------------------------ style

    fun updateStyle(newStyle: SubtitleStyle) {
        _style.value = newStyle
        store.save(newStyle)
    }

    fun resetStyle() = updateStyle(SubtitleStyle())

    // ------------------------------------------------------------------ offset

    private fun minOffset() = if (activeExternal != null) -MAX_OFFSET_MS else 0L

    fun setOffset(ms: Long) {
        _offsetMs.value = ms.coerceIn(minOffset(), MAX_OFFSET_MS)
    }

    /** +/- [OFFSET_STEP_MS] buttons. */
    fun nudgeOffset(deltaMs: Long) = setOffset(_offsetMs.value + deltaMs)

    fun resetOffset() = setOffset(0L)

    // ------------------------------------------------------------------ sources

    fun select(id: String) {
        mainHandler.removeCallbacksAndMessages(null)
        when {
            id == OFF_ID -> {
                stopExternal()
                setTextDisabled(true)
                selectedId = OFF_ID
            }

            id.startsWith("emb:") -> {
                val parts = id.removePrefix("emb:").split(":")
                val gi = parts.getOrNull(0)?.toIntOrNull() ?: return
                val ti = parts.getOrNull(1)?.toIntOrNull() ?: return
                val group = player.currentTracks.groups.getOrNull(gi)
                if (group == null || group.type != C.TRACK_TYPE_TEXT || ti !in 0 until group.length) return
                stopExternal()
                selectedId = id
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ti))
                    .build()
            }

            else -> {
                val sub = externals.firstOrNull { it.id == id } ?: return
                startExternal(sub)
            }
        }
        setOffset(_offsetMs.value) // re-clamp: embedded sources can't go negative
        refreshOptions()
    }

    /** Parses a user-picked subtitle file and selects it. Returns false if it can't be read. */
    suspend fun addExternal(uri: Uri): Boolean {
        val parsed = withContext(Dispatchers.IO) {
            try {
                val name = appContext.displayNameOf(uri)
                val mime = mimeTypeFor(name) ?: return@withContext null
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext null
                if (bytes.size > MAX_FILE_BYTES) return@withContext null
                val entries = parse(bytes, mime)
                if (entries.isEmpty()) null else Pair(name, entries)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load subtitle file", e)
                null
            }
        } ?: return false

        val sub = ExternalSubtitle("ext:${nextExternalId++}", parsed.first.substringBeforeLast('.'), parsed.second)
        externals += sub
        select(sub.id)
        return true
    }

    /** Called when a new video is loaded. */
    fun reset() {
        mainHandler.removeCallbacksAndMessages(null)
        stopExternal()
        externals.clear()
        selectedId = null
        _offsetMs.value = 0L
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        refreshOptions()
    }

    fun release() {
        player.removeListener(listener)
        mainHandler.removeCallbacksAndMessages(null)
        tickerJob?.cancel()
    }

    // ------------------------------------------------------------------ internals

    private fun setTextDisabled(disabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled)
            .build()
        if (disabled) _cues.value = emptyList()
    }

    private fun startExternal(sub: ExternalSubtitle) {
        activeExternal = sub
        selectedId = sub.id
        _canAdvance.value = true
        setTextDisabled(true) // silence ExoPlayer's own text renderer so cues don't double up
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val positionUs = (player.currentPosition - _offsetMs.value) * 1000L
                _cues.value = activeCuesAt(sub.entries, positionUs) // StateFlow drops equal values
                delay(TICK_MS)
            }
        }
    }

    private fun stopExternal() {
        tickerJob?.cancel()
        tickerJob = null
        activeExternal = null
        _canAdvance.value = false
        _cues.value = emptyList()
    }

    /** Embedded cues: delay by the (positive) offset, scaled by playback speed. */
    private fun publishEmbedded(cues: List<Cue>) {
        val delayMs = (_offsetMs.value.coerceAtLeast(0L) / player.playbackParameters.speed).toLong()
        if (delayMs <= 0L) {
            _cues.value = cues
        } else {
            mainHandler.postDelayed({ _cues.value = cues }, delayMs)
        }
    }

    private fun refreshOptions() {
        val ext = activeExternal
        val embedded = mutableListOf<SubtitleOption>()
        var anyEmbeddedSelected = false
        for ((gi, group) in player.currentTracks.groups.withIndex()) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val id = "emb:$gi:$ti"
                val selected = if (selectedId != null) id == selectedId else group.isTrackSelected(ti)
                anyEmbeddedSelected = anyEmbeddedSelected || selected
                embedded += SubtitleOption(id, group.getTrackFormat(ti).trackTitle(embedded.size + 1), false, selected)
            }
        }
        val offSelected = if (selectedId != null) selectedId == OFF_ID else (ext == null && !anyEmbeddedSelected)
        _options.value = buildList {
            add(SubtitleOption(OFF_ID, "Off", false, offSelected))
            addAll(embedded)
            externals.forEach { add(SubtitleOption(it.id, it.label, true, it === ext)) }
        }
    }

    private fun mimeTypeFor(fileName: String): String? =
        when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> null
        }

    private fun parse(bytes: ByteArray, mimeType: String): List<TimedCues> {
        val format = Format.Builder().setSampleMimeType(mimeType).build()
        val factory = DefaultSubtitleParserFactory()
        if (!factory.supportsFormat(format)) return emptyList()
        val parser = factory.create(format)
        val raw = ArrayList<CuesWithTiming>()
        parser.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { raw.add(it) }
        raw.sortBy { it.startTimeUs }
        return raw.mapIndexed { i, c ->
            val end = when {
                c.durationUs != C.TIME_UNSET -> c.startTimeUs + c.durationUs
                i + 1 < raw.size -> raw[i + 1].startTimeUs // "until replaced" semantics
                else -> Long.MAX_VALUE
            }
            TimedCues(c.startTimeUs, end, c.cues)
        }
    }

    /** Binary-search the last cue that started at or before [positionUs], then collect overlaps. */
    private fun activeCuesAt(list: List<TimedCues>, positionUs: Long): List<Cue> {
        var lo = 0
        var hi = list.lastIndex
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startUs <= positionUs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (idx < 0) return emptyList()
        val out = ArrayList<Cue>(2)
        var i = idx
        var scanned = 0
        while (i >= 0 && scanned < 16) { // overlapping cues are rare; 16 is plenty
            val e = list[i]
            if (positionUs < e.endUs) out.addAll(0, e.cues)
            i--
            scanned++
        }
        return out
    }
}

// ------------------------------------------------------------------------------------------------
// Rendering
// ------------------------------------------------------------------------------------------------

/**
 * Draws the active cues over the video with the user's [SubtitleStyle]. Stroke width is real
 * (a stroked copy of the text under the fill), which Media3's SubtitleView can't do.
 * Does not consume touches.
 */
@Composable
fun SubtitleOverlay(
    cues: List<Cue>,
    style: SubtitleStyle,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    if (cues.isEmpty()) return
    Box(
        modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, bottom = bottomPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (cue in cues) {
                val bitmap = cue.bitmap
                val text = cue.text
                when {
                    // Bitmap subtitles (PGS / VobSub / DVB) can't be restyled, only shown.
                    bitmap != null -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(
                            cue.size.takeIf { it != Cue.DIMEN_UNSET }?.coerceIn(0.1f, 1f) ?: 0.8f,
                        ),
                        contentScale = ContentScale.Fit,
                    )

                    !text.isNullOrBlank() -> SubtitleLine(text.trim(), style)
                }
            }
        }
    }
}

@Composable
private fun SubtitleLine(text: CharSequence, style: SubtitleStyle) {
    val density = LocalDensity.current
    val annotated = remember(text) { text.toAnnotatedString() }
    val base = TextStyle(
        fontSize = style.textSizeSp.sp,
        lineHeight = (style.textSizeSp * 1.25f).sp,
        fontFamily = style.font.family,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
    Box(
        Modifier
            .background(Color(style.backgroundColor), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        if (style.strokeWidthDp > 0f) {
            // A stroke is centred on the glyph outline and the fill is painted on top, so only the
            // outer half is visible: double the width to get the width the slider promises.
            Text(
                text = annotated,
                style = base.copy(
                    color = Color(style.strokeColor),
                    drawStyle = Stroke(
                        width = with(density) { style.strokeWidthDp.dp.toPx() * 2f },
                        join = StrokeJoin.Round,
                    ),
                ),
            )
        }
        Text(text = annotated, style = base.copy(color = Color(style.textColor)))
    }
}

/** Keeps <b> / <i> from SRT / VTT / SSA (they arrive as StyleSpans) and drops everything else. */
private fun CharSequence.toAnnotatedString(): AnnotatedString {
    val source = this
    val plain = source.toString()
    return buildAnnotatedString {
        append(plain)
        if (source is Spanned) {
            for (span in source.getSpans(0, plain.length, StyleSpan::class.java)) {
                val spanStyle = when (span.style) {
                    Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    else -> null
                } ?: continue
                addStyle(spanStyle, source.getSpanStart(span), source.getSpanEnd(span))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Settings panel (docked on the right so the video stays visible for live preview)
// ------------------------------------------------------------------------------------------------

private val TextColors = listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFF80DEEA, 0xFFA5D6A7, 0xFFFFAB91).map { it.toInt() }
private val BackgroundColors = listOf(0x00000000, 0x80000000, 0xCC000000, 0xFF000000, 0xCC1B2A41).map { it.toInt() }
private val StrokeColors = listOf(0xFF000000, 0xFF1B2A41, 0xFFFFFFFF).map { it.toInt() }

@Composable
fun SubtitleSettingsPanel(
    visible: Boolean,
    style: SubtitleStyle,
    offsetMs: Long,
    canAdvance: Boolean,
    options: List<SubtitleOption>,
    onStyleChange: (SubtitleStyle) -> Unit,
    onSelectSource: (String) -> Unit,
    onAddFile: () -> Unit,
    onOffsetStep: (Long) -> Unit,
    onOffsetReset: () -> Unit,
    onResetStyle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                // Swallow taps so they don't fall through to the gesture layer underneath.
                .pointerInput(Unit) { detectTapGestures { } },
            color = Color(0xEB101216),
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Subtitles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                }

                SectionLabel("Track")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { option ->
                        FilterChip(
                            selected = option.isSelected,
                            onClick = { onSelectSource(option.id) },
                            label = { Text(option.label, maxLines = 1) },
                        )
                    }
                    AssistChip(
                        onClick = onAddFile,
                        label = { Text("Add file") },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }

                HorizontalDivider()
                SectionLabel("Sync")
                val minOffset = if (canAdvance) -SubtitleController.MAX_OFFSET_MS else 0L
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(
                        onClick = { onOffsetStep(-SubtitleController.OFFSET_STEP_MS) },
                        enabled = offsetMs > minOffset,
                    ) { Icon(Icons.Rounded.Remove, contentDescription = "Show subtitles 100 ms earlier") }
                    Text(
                        text = String.format(Locale.ROOT, "%+.1f s", offsetMs / 1000.0),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 72.dp),
                    )
                    FilledTonalIconButton(
                        onClick = { onOffsetStep(SubtitleController.OFFSET_STEP_MS) },
                        enabled = offsetMs < SubtitleController.MAX_OFFSET_MS,
                    ) { Icon(Icons.Rounded.Add, contentDescription = "Show subtitles 100 ms later") }
                    TextButton(onClick = onOffsetReset, enabled = offsetMs != 0L) { Text("Reset") }
                }
                Text(
                    text = if (canAdvance) "Minus shows subtitles earlier, plus later. 100 ms per step."
                    else "Embedded tracks can only be delayed. Add a subtitle file to move them earlier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                SectionLabel("Size  ${style.textSizeSp.roundToInt()} sp")
                Slider(
                    value = style.textSizeSp,
                    onValueChange = { onStyleChange(style.copy(textSizeSp = it)) },
                    valueRange = SubtitleStyle.MIN_SIZE_SP..SubtitleStyle.MAX_SIZE_SP,
                )

                SectionLabel("Text color")
                ColorRow(TextColors, style.textColor) { onStyleChange(style.copy(textColor = it)) }

                SectionLabel("Background")
                ColorRow(BackgroundColors, style.backgroundColor) { onStyleChange(style.copy(backgroundColor = it)) }

                SectionLabel("Outline  ${String.format(Locale.ROOT, "%.1f", style.strokeWidthDp)} dp")
                Slider(
                    value = style.strokeWidthDp,
                    onValueChange = { onStyleChange(style.copy(strokeWidthDp = it)) },
                    valueRange = 0f..SubtitleStyle.MAX_STROKE_DP,
                )
                ColorRow(StrokeColors, style.strokeColor) { onStyleChange(style.copy(strokeColor = it)) }

                SectionLabel("Font")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SubtitleFont.entries.forEach { font ->
                        FilterChip(
                            selected = style.font == font,
                            onClick = { onStyleChange(style.copy(font = font)) },
                            label = { Text(font.label) },
                        )
                    }
                    FilterChip(
                        selected = style.bold,
                        onClick = { onStyleChange(style.copy(bold = !style.bold)) },
                        label = { Text("Bold") },
                    )
                }

                TextButton(onClick = onResetStyle) { Text("Reset appearance") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColorRow(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { argb ->
            val isSelected = argb == selected
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray) // shows through translucent swatches
                    .background(Color(argb))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.35f),
                        shape = CircleShape,
                    )
                    .clickable { onSelect(argb) },
            )
        }
    }
}
