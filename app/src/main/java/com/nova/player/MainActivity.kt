@file:OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)

package com.nova.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    /** A video handed to us by another app ("Open with"). Observable so onNewIntent can update it. */
    private val externalUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalUri.value = videoUriFrom(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8AB4F8),
                    background = Color(0xFF0E0F12),
                    surface = Color(0xFF16181D),
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    AppRoot(
                        viewModel = viewModel,
                        externalUri = externalUri.value,
                        onFinish = ::finish,
                    )
                }
            }
        }
    }

    // launchMode=singleTask: a second "Open with" arrives here instead of creating a new Activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        videoUriFrom(intent)?.let { externalUri.value = it }
    }

    /**
     * Route the hardware volume keys through AudioBooster. Left to the system, volume-down while
     * boosted would lower the stream below max but leave the digital gain on, and volume-up could
     * never go past 100 %.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.playerScreenVisible &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            viewModel.onVolumeKey(up = keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // onDestroy: nothing to do by hand. ExoPlayer, AudioBooster (LoudnessEnhancer), the subtitle
    // controller and the MediaSession all live in PlayerViewModel and are released in onCleared(),
    // which runs when the Activity is finishing and NOT on rotation.

    private fun videoUriFrom(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null
}

// ------------------------------------------------------------------------------------------------
// Navigation between the library and the player
// ------------------------------------------------------------------------------------------------

@Composable
private fun AppRoot(viewModel: PlayerViewModel, externalUri: Uri?, onFinish: () -> Unit) {
    val context = LocalContext.current
    var current by rememberSaveable { mutableStateOf<Uri?>(externalUri) }
    var currentTitle by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(externalUri) {
        externalUri?.let {
            current = it
            currentTitle = context.displayNameOf(it)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            current = picked
            currentTitle = context.displayNameOf(picked)
        }
    }

    val uri = current
    if (uri == null) {
        LibraryScreen(
            onPlay = { videoUri, name ->
                current = videoUri
                currentTitle = name
            },
            onPickFile = { filePicker.launch(arrayOf("video/*")) },
        )
    } else {
        VideoPlayerScreen(
            viewModel = viewModel,
            uri = uri,
            title = currentTitle,
            onBack = {
                viewModel.stop()
                // Opened from another app: Back returns to that app instead of showing our library.
                if (externalUri != null && uri == externalUri) onFinish() else current = null
            },
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Library
// ------------------------------------------------------------------------------------------------

private data class VideoItem(val uri: Uri, val title: String, val durationMs: Long, val sizeBytes: Long)

@Composable
private fun LibraryScreen(onPlay: (Uri, String) -> Unit, onPickFile: () -> Unit) {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionState = rememberPermissionState(permission)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nova Player") },
                actions = {
                    IconButton(onClick = onPickFile) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Open a video file")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (permissionState.status.isGranted) {
                VideoList(onPlay)
            } else {
                PermissionPrompt(
                    showRationale = permissionState.status.shouldShowRationale,
                    onGrant = permissionState::launchPermissionRequest,
                    onPickFile = onPickFile,
                )
            }
        }
    }
}

@Composable
private fun VideoList(onPlay: (Uri, String) -> Unit) {
    val context = LocalContext.current
    val videos by produceState<List<VideoItem>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { queryVideos(context) }
    }

    when (val list = videos) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        else -> if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos on this device yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.uri.toString() }) { video ->
                    ListItem(
                        modifier = Modifier.clickable { onPlay(video.uri, video.title) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Rounded.VideoLibrary, contentDescription = null) },
                        headlineContent = { Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${formatTime(video.durationMs)} · ${Formatter.formatShortFileSize(context, video.sizeBytes)}")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(showRationale: Boolean, onGrant: () -> Unit, onPickFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = if (showRationale) {
                "Nova Player needs access to your videos to list them. You can also open a single file without granting access."
            } else {
                "Allow access to your videos to see them here."
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onGrant) { Text("Allow access") }
        OutlinedButton(onClick = onPickFile) { Text("Open a file instead") }
    }
}

private fun queryVideos(context: Context): List<VideoItem> {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
    )
    val result = ArrayList<VideoItem>()
    context.contentResolver.query(
        collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        while (cursor.moveToNext()) {
            result += VideoItem(
                uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                title = cursor.getString(nameCol) ?: "Video",
                durationMs = cursor.getLong(durationCol),
                sizeBytes = cursor.getLong(sizeCol),
            )
        }
    }
    return result
}

/** File name for a content:// or file:// URI; also used to pick the subtitle parser by extension. */
internal fun Context.displayNameOf(uri: Uri): String =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
