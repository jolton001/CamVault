package com.example.ui.screens

import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import com.example.ui.viewmodel.DateFilterPeriod
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.RecordingEntity
import com.example.data.local.UploadStatus
import com.example.media.LocalMp4FallbackPlayer
import com.example.media.Mp4TrackInspection
import com.example.media.inspectMp4Tracks
import com.example.ui.components.StatusPill
import com.example.ui.components.formatDuration
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.AmberAlertContainer
import com.example.ui.theme.CloudEmerald
import com.example.ui.theme.CloudEmeraldContainer
import com.example.ui.theme.RecordingCrimson
import com.example.ui.theme.RecordingCrimsonContainer
import com.example.ui.viewmodel.RecordingFilter
import com.example.ui.viewmodel.RecordingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RecordingsScreen(
    uiState: RecordingsUiState,
    onFilterChange: (RecordingFilter) -> Unit,
    onDateFilterChange: (DateFilterPeriod) -> Unit = {},
    onToggleSelectionMode: () -> Unit = {},
    onToggleSelect: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onSyncSelected: () -> Unit = {},
    onExportSelectedToGallery: () -> Unit = {},
    onExportToGallery: (RecordingEntity) -> Unit = {},
    onDeleteAllUploaded: () -> Unit = {},
    onUploadClick: (Long) -> Unit,
    onRetryAllClick: () -> Unit,
    onDeleteClick: (RecordingEntity) -> Unit,
    onClearBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var playingRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var deletingRecording by remember { mutableStateOf<RecordingEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header + Sync All action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "RECORDING VAULT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "MP4 Clips & Cloud Sync",
                    style = MaterialTheme.typography.headlineMedium
                )
                uiState.defaultCloudConfig?.let { cfg ->
                    val targetLabel = if (cfg.providerType == com.example.data.local.CloudProviderType.MEGA) {
                        "MEGA Drive"
                    } else {
                        cfg.name
                    }
                    val scheduleLabel = if (cfg.periodicUploadEvery10Min) " • Every 10 min backup" else ""
                    Text(
                        text = "$targetLabel$scheduleLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onToggleSelectionMode,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(if (uiState.isSelectionMode) "Done" else "Select")
                }
                FilledTonalButton(
                    onClick = onRetryAllClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("sync_all_button")
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync All")
                }
            }
        }

        // Selection Mode Bulk Toolbar
        if (uiState.isSelectionMode) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${uiState.selectedIds.size} selected",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onSelectAll, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(onClick = onExportSelectedToGallery, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Download, contentDescription = "Save selected to Gallery")
                        }
                        IconButton(onClick = onSyncSelected, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.CloudUpload, contentDescription = "Sync selected")
                        }
                        IconButton(onClick = onDeleteSelected, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        if (uiState.statusBanner.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable(onClick = onClearBanner),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = uiState.statusBanner,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Status Filter chips & Purge Action
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(RecordingFilter.entries) { filter ->
                    FilterChip(
                        selected = uiState.selectedFilter == filter,
                        onClick = { onFilterChange(filter) },
                        label = {
                            Text(
                                text = when (filter) {
                                    RecordingFilter.ALL -> "All (${uiState.allRecordings.size})"
                                    RecordingFilter.UPLOADED -> "Uploaded"
                                    RecordingFilter.PENDING -> "Pending"
                                    RecordingFilter.FAILED -> "Failed"
                                }
                            )
                        }
                    )
                }
            }
            IconButton(
                onClick = onDeleteAllUploaded,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.CleaningServices,
                    contentDescription = "Purge local copies of uploaded clips",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Date Filter chips row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(DateFilterPeriod.entries) { dateFilter ->
                FilterChip(
                    selected = uiState.selectedDateFilter == dateFilter,
                    onClick = { onDateFilterChange(dateFilter) },
                    label = { Text(dateFilter.label) },
                    leadingIcon = if (uiState.selectedDateFilter == dateFilter) {
                        { Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (uiState.filteredRecordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = stringResource(R.string.empty_recordings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.empty_recordings_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.filteredRecordings, key = { it.id }) { recording ->
                    RecordingItemCard(
                        recording = recording,
                        isSelectionMode = uiState.isSelectionMode,
                        isSelected = uiState.selectedIds.contains(recording.id),
                        onToggleSelect = { onToggleSelect(recording.id) },
                        onPlay = { playingRecording = recording },
                        onUpload = { onUploadClick(recording.id) },
                        onExportToGallery = { onExportToGallery(recording) },
                        onShare = {
                            val file = File(recording.localFilePath)
                            if (file.exists()) {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share ${recording.fileName}"))
                                }
                            }
                        },
                        onDelete = { deletingRecording = recording }
                    )
                }
            }
        }
    }

    playingRecording?.let { rec ->
        LocalMp4PlayerDialog(
            recording = rec,
            onDismiss = { playingRecording = null }
        )
    }

    deletingRecording?.let { rec ->
        AlertDialog(
            onDismissRequest = { deletingRecording = null },
            title = { Text("Delete Recording?") },
            text = { Text("Delete ${rec.fileName} from device storage?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteClick(rec)
                        deletingRecording = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingRecording = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RecordingItemCard(
    recording: RecordingEntity,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onPlay: () -> Unit,
    onUpload: () -> Unit,
    onExportToGallery: () -> Unit = {},
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() }
                    )
                }
                // Thumbnail / Play button
                Box(
                    modifier = Modifier
                        .size(width = 112.dp, height = 68.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) {
                    if (recording.thumbnailPath.isNotBlank() && File(recording.thumbnailPath).exists()) {
                        AsyncImage(
                            model = File(recording.thumbnailPath),
                            contentDescription = "Recording thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f))
                    )
                    Icon(
                        imageVector = Icons.Default.PlayCircleFilled,
                        contentDescription = "Play MP4 clip",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = recording.cameraName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val resPart = if (recording.resolution.isNotBlank()) " • ${recording.resolution}" else ""
                    Text(
                        text = "${formatDuration(recording.durationMs)} • ${formatFileSize(recording.fileSizeBytes)}$resPart • ${formatTimestamp(recording.startedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (recording.hasAudio) {
                            StatusPill(
                                label = recording.audioCodec.ifBlank { "AUDIO" },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                                icon = Icons.AutoMirrored.Filled.VolumeUp
                            )
                        }
                        UploadBadge(recording.uploadStatus, recording.uploadProgress)
                    }
                }
            }

            if (recording.uploadStatus == UploadStatus.UPLOADING) {
                LinearProgressIndicator(
                    progress = { (recording.uploadProgress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (recording.uploadError.isNotBlank() && recording.uploadStatus == UploadStatus.FAILED) {
                Text(
                    text = recording.uploadError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (recording.remoteStorageUrl.isNotBlank() && recording.uploadStatus == UploadStatus.UPLOADED) {
                Text(
                    text = recording.remoteStorageUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = CloudEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (recording.uploadStatus != UploadStatus.UPLOADED && recording.uploadStatus != UploadStatus.UPLOADING) {
                    FilledTonalButton(
                        onClick = onUpload,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (recording.uploadStatus == UploadStatus.FAILED) "Retry Upload" else "Upload to Cloud")
                    }
                } else {
                    Text(
                        text = recording.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPlay,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("play_recording_button_${recording.id}")
                    ) {
                        Icon(
                            Icons.Default.PlayCircleFilled,
                            contentDescription = "Play MP4 recording",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onExportToGallery, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Save to Gallery",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    IconButton(onClick = onShare, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Default.Share, contentDescription = "Share MP4")
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete recording",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadBadge(status: UploadStatus, progress: Int) {
    when (status) {
        UploadStatus.UPLOADED -> StatusPill(
            label = "SYNCED",
            containerColor = CloudEmeraldContainer,
            contentColor = CloudEmerald,
            icon = Icons.Default.CloudDone
        )
        UploadStatus.UPLOADING -> StatusPill(
            label = "UPLOADING $progress%",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.secondary
        )
        UploadStatus.QUEUED -> StatusPill(
            label = "QUEUED",
            containerColor = AmberAlertContainer,
            contentColor = AmberAlert
        )
        UploadStatus.FAILED -> StatusPill(
            label = "FAILED",
            containerColor = RecordingCrimsonContainer,
            contentColor = RecordingCrimson,
            icon = Icons.Default.ErrorOutline
        )
        UploadStatus.LOCAL_ONLY -> StatusPill(
            label = "LOCAL",
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Default.CloudOff
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LocalMp4PlayerDialog(
    recording: RecordingEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val videoFile = remember(recording.localFilePath) { File(recording.localFilePath) }
    val fileMissing = !videoFile.exists() || videoFile.length() == 0L

    var inspection by remember(recording.localFilePath) { mutableStateOf<Mp4TrackInspection?>(null) }
    var useSwFallback by remember(recording.localFilePath) { mutableStateOf(false) }
    var autoFallbackTriggered by remember(recording.localFilePath) { mutableStateOf(false) }
    var playbackError by remember(recording.localFilePath) {
        mutableStateOf(if (fileMissing) "Recording file missing or empty on device." else "")
    }
    var isBuffering by remember(recording.localFilePath) { mutableStateOf(!fileMissing) }
    var renderedSize by remember(recording.localFilePath) { mutableStateOf("") }
    var hasRenderedFrame by remember(recording.localFilePath) { mutableStateOf(false) }
    var decoderLabel by remember(recording.localFilePath) { mutableStateOf("ExoPlayer") }

    // Software fallback state
    val swPlayer = remember(recording.localFilePath) { LocalMp4FallbackPlayer() }
    var swSurface by remember(recording.localFilePath) { mutableStateOf<Surface?>(null) }
    var swReplayNonce by remember(recording.localFilePath) { mutableIntStateOf(0) }
    var swPaused by remember(recording.localFilePath) { mutableStateOf(false) }
    var swMuted by remember(recording.localFilePath) { mutableStateOf(false) }
    var swEnded by remember(recording.localFilePath) { mutableStateOf(false) }
    var swPositionMs by remember(recording.localFilePath) { mutableLongStateOf(0L) }
    var swDurationMs by remember(recording.localFilePath) { mutableLongStateOf(recording.durationMs) }

    LaunchedEffect(recording.localFilePath) {
        if (!fileMissing) {
            val result = withContext(Dispatchers.IO) { inspectMp4Tracks(recording.localFilePath) }
            inspection = result
            if (!result.hasVideo && result.error.isBlank()) {
                playbackError = "MP4 contains no video track (${result.summaryText})."
            }
        }
    }

    val exoPlayer = remember(recording.localFilePath) {
        ExoPlayer.Builder(context).build().apply {
            if (!fileMissing) {
                setMediaItem(MediaItem.fromUri(videoFile.toUri()))
                prepare()
                playWhenReady = true
            }
        }
    }

    // Auto-switch from ExoPlayer to software-first MediaCodec player if ExoPlayer produces blank video
    LaunchedEffect(useSwFallback, hasRenderedFrame, fileMissing, playbackError) {
        if (!useSwFallback && !fileMissing && playbackError.isBlank() && !hasRenderedFrame) {
            delay(2500L)
            if (!hasRenderedFrame && !useSwFallback && !autoFallbackTriggered) {
                autoFallbackTriggered = true
                exoPlayer.stop()
                playbackError = ""
                useSwFallback = true
            }
        }
    }

    // Drive ExoPlayer when selected
    LaunchedEffect(useSwFallback, fileMissing) {
        if (!useSwFallback && !fileMissing) {
            swPlayer.stop()
            decoderLabel = "ExoPlayer"
            hasRenderedFrame = false
            isBuffering = true
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.setMediaItem(MediaItem.fromUri(videoFile.toUri()))
                exoPlayer.prepare()
            }
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.stop()
        }
    }

    // Drive Software-first MediaExtractor + MediaCodec fallback player when active
    LaunchedEffect(useSwFallback, swSurface, swReplayNonce, fileMissing) {
        if (!useSwFallback || fileMissing) {
            swPlayer.stop()
            return@LaunchedEffect
        }
        val surface = swSurface ?: return@LaunchedEffect
        swEnded = false
        swPaused = false
        swPositionMs = 0L
        hasRenderedFrame = false
        isBuffering = true
        playbackError = ""
        swPlayer.setMuted(swMuted)
        swPlayer.start(
            scope = coroutineScope,
            filePath = recording.localFilePath,
            surface = surface,
            preferSoftware = true,
            listener = object : LocalMp4FallbackPlayer.Listener {
                override fun onStatusChanged(status: String, decoderName: String) {
                    if (decoderName.isNotBlank()) {
                        decoderLabel = decoderName
                    }
                }

                override fun onVideoDimensions(width: Int, height: Int) {
                    if (width > 0 && height > 0) {
                        renderedSize = "${width}x${height}"
                    }
                }

                override fun onFrameRendered(frameCount: Long, decoderName: String) {
                    hasRenderedFrame = true
                    isBuffering = false
                    if (decoderName.isNotBlank()) {
                        decoderLabel = decoderName
                    }
                }

                override fun onProgress(positionMs: Long, durationMs: Long) {
                    swPositionMs = positionMs
                    if (durationMs > 0L) swDurationMs = durationMs
                }

                override fun onPlaybackEnded() {
                    isBuffering = false
                    swEnded = true
                }

                override fun onError(message: String) {
                    isBuffering = false
                    playbackError = message
                }
            }
        )
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!useSwFallback) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                }
            }

            override fun onRenderedFirstFrame() {
                if (!useSwFallback) {
                    hasRenderedFrame = true
                    isBuffering = false
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    renderedSize = "${videoSize.width}x${videoSize.height}"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!useSwFallback && !autoFallbackTriggered) {
                    autoFallbackTriggered = true
                    useSwFallback = true
                } else {
                    isBuffering = false
                    playbackError = error.localizedMessage ?: "Unable to decode MP4 clip (${error.errorCodeName})"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            swPlayer.stop()
            swSurface?.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = recording.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val diag = buildString {
                            inspection?.summaryText?.let { append(it) }
                            if (decoderLabel.isNotBlank()) {
                                if (isNotEmpty()) append(" • ")
                                append(decoderLabel)
                            }
                        }
                        if (diag.isNotBlank()) {
                            Text(
                                text = diag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    val badgeText = renderedSize.ifBlank { recording.resolution }
                    if (badgeText.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasRenderedFrame) CloudEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (inspection?.missingRealKeyframes == true) {
                    Text(
                        text = "Clip has no IDR keyframes (green tint). Record a fresh clip to capture full keyframes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberAlert
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (playbackError.isBlank()) {
                        if (useSwFallback) {
                            AndroidView(
                                factory = { ctx ->
                                    TextureView(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                                swSurface?.release()
                                                swSurface = Surface(st)
                                            }

                                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                swPlayer.stop()
                                                swSurface?.release()
                                                swSurface = null
                                                return true
                                            }

                                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            // Bottom controls bar for software decoder
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.58f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (swEnded) {
                                            swReplayNonce++
                                        } else {
                                            val next = !swPaused
                                            swPaused = next
                                            swPlayer.setPaused(next)
                                        }
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("sw_play_pause_button")
                                ) {
                                    Icon(
                                        imageVector = when {
                                            swEnded -> Icons.Default.Replay
                                            swPaused -> Icons.Default.PlayArrow
                                            else -> Icons.Default.Pause
                                        },
                                        contentDescription = if (swEnded) "Replay video" else if (swPaused) "Resume video" else "Pause video",
                                        tint = Color.White
                                    )
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val progress = if (swDurationMs > 0L) {
                                        (swPositionMs.toFloat() / swDurationMs.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = "${formatDuration(swPositionMs)} / ${formatDuration(swDurationMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val next = !swMuted
                                        swMuted = next
                                        swPlayer.setMuted(next)
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("sw_mute_button")
                                ) {
                                    Icon(
                                        imageVector = if (swMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (swMuted) "Unmute audio" else "Mute audio",
                                        tint = Color.White
                                    )
                                }
                            }
                        } else {
                            AndroidView(
                                factory = { ctx ->
                                    (LayoutInflater.from(ctx).inflate(R.layout.exo_texture_player_view, null, false) as PlayerView).apply {
                                        this.player = exoPlayer
                                        useController = true
                                        controllerShowTimeoutMs = 3000
                                        keepScreenOn = true
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                update = { view ->
                                    view.player = exoPlayer
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (isBuffering) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Playback error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = playbackError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (videoFile.exists() && videoFile.length() > 0L) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            videoFile
                                        )
                                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "video/mp4")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(viewIntent, "Play MP4"))
                                    }
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("External")
                            }
                            TextButton(
                                onClick = {
                                    playbackError = ""
                                    autoFallbackTriggered = true
                                    useSwFallback = !useSwFallback
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("toggle_mp4_decoder_button")
                            ) {
                                Text(if (useSwFallback) "ExoPlayer" else "SW Decode")
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
