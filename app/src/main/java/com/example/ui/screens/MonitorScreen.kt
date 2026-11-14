package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.CameraEntity
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.effectiveRecordIntervalMinutes
import com.example.media.RecordingSessionState
import com.example.ui.components.StatusPill
import com.example.ui.components.formatDuration
import com.example.ui.theme.CloudEmerald
import com.example.ui.theme.CloudEmeraldContainer
import com.example.ui.theme.RecordingCrimson
import com.example.ui.theme.RecordingCrimsonContainer
import com.example.ui.viewmodel.MonitorUiState

@Composable
fun MonitorScreen(
    uiState: MonitorUiState,
    isExpandedWidth: Boolean,
    onAddCameraClick: () -> Unit,
    onEditCameraClick: (Long) -> Unit,
    onOpenCameraPlayer: (Long) -> Unit,
    onStartRecording: (CameraEntity) -> Unit,
    onStopRecording: (Long) -> Unit,
    onToggleAudio: (CameraEntity) -> Unit,
    onDeleteCamera: (Long) -> Unit,
    onConfigureCloudClick: () -> Unit,
    onClearBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<CameraEntity?>(null) }
    var isGridView by remember { mutableStateOf(false) }
    val activeRecordingCount = uiState.recordingSessions.values.count {
        it is RecordingSessionState.Recording || it is RecordingSessionState.Connecting
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isGridView) 2 else if (isExpandedWidth) 2 else 1),
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            TelemetryOverviewHeader(
                cameraCount = uiState.cameras.size,
                activeRecordingCount = activeRecordingCount,
                uploadedCount = uiState.uploadedCount,
                pendingCount = uiState.pendingUploadCount,
                cloudConfig = uiState.defaultCloudConfig,
                onAddCameraClick = onAddCameraClick,
                onConfigureCloudClick = onConfigureCloudClick
            )
        }

        if (uiState.statusBanner.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onClearBanner),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = uiState.statusBanner,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }

        if (uiState.cameras.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CAMERAS (${uiState.cameras.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !isGridView,
                            onClick = { isGridView = false },
                            label = { Text("Cards") }
                        )
                        FilterChip(
                            selected = isGridView,
                            onClick = { isGridView = true },
                            label = { Text("2x2 Matrix") }
                        )
                    }
                }
            }
        }

        if (uiState.cameras.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyCameraHeroCard(
                    onAddCameraClick = onAddCameraClick
                )
            }
        } else if (isGridView) {
            items(uiState.cameras, key = { it.id }) { camera ->
                val sessionState = uiState.recordingSessions[camera.id] ?: RecordingSessionState.Idle
                CameraGridTile(
                    camera = camera,
                    sessionState = sessionState,
                    onWatchClick = { onOpenCameraPlayer(camera.id) },
                    onStartRecordClick = { onStartRecording(camera) },
                    onStopRecordClick = { onStopRecording(camera.id) }
                )
            }
        } else {
            items(uiState.cameras, key = { it.id }) { camera ->
                val sessionState = uiState.recordingSessions[camera.id] ?: RecordingSessionState.Idle
                CameraMonitorCard(
                    camera = camera,
                    sessionState = sessionState,
                    onWatchClick = { onOpenCameraPlayer(camera.id) },
                    onStartRecordClick = { onStartRecording(camera) },
                    onStopRecordClick = { onStopRecording(camera.id) },
                    onEditClick = { onEditCameraClick(camera.id) },
                    onToggleAudio = { onToggleAudio(camera) },
                    onDeleteClick = { pendingDelete = camera }
                )
            }
        }
    }

    pendingDelete?.let { cam ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${cam.name}?") },
            text = {
                Text("This removes the camera configuration from CamVault. Saved MP4 clips remain in your Vault.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCamera(cam.id)
                        pendingDelete = null
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TelemetryOverviewHeader(
    cameraCount: Int,
    activeRecordingCount: Int,
    uploadedCount: Int,
    pendingCount: Int,
    cloudConfig: CloudStorageConfigEntity?,
    onAddCameraClick: () -> Unit,
    onConfigureCloudClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CAMVAULT MONITOR",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "ONVIF & RTSP Feeds",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            FilledTonalButton(
                onClick = onAddCameraClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("add_camera_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add camera",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Camera")
            }
        }

        // Cloud Status + Telemetry Strip
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onConfigureCloudClick)
                .testTag("cloud_status_banner"),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (cloudConfig != null) CloudEmeraldContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (cloudConfig != null) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = if (cloudConfig != null) CloudEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = cloudConfig?.name ?: "Cloud Storage Not Configured",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (cloudConfig != null) {
                                val interval = cloudConfig.effectiveRecordIntervalMinutes()
                                val recMode = if (cloudConfig.autoRecordWhenCloudConfigured) "Auto-rec ${interval}m" else "Manual rec"
                                "${cloudConfig.providerType.name.replace('_', ' ')} • $recMode • ${if (cloudConfig.autoUploadAfterRecord) "Auto-upload ON" else "Manual upload"}"
                            } else {
                                "Tap to connect S3, R2, MinIO, WebDAV, or HTTP"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (activeRecordingCount > 0) "$cameraCount CAM • $activeRecordingCount REC" else "$cameraCount CAM",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (activeRecordingCount > 0) RecordingCrimson else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (pendingCount > 0) "$pendingCount syncing" else "$uploadedCount synced",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pendingCount > 0) MaterialTheme.colorScheme.secondary else CloudEmerald
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCameraHeroCard(
    onAddCameraClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_camera_hero),
                    contentDescription = "ONVIF and RTSP camera monitoring illustration",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        label = "ONVIF WS-DISCOVERY",
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                    StatusPill(
                        label = "RTSP + AUDIO",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.empty_cameras_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.empty_cameras_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onAddCameraClick,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("empty_scan_onvif_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Discover / Add Camera")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraMonitorCard(
    camera: CameraEntity,
    sessionState: RecordingSessionState,
    onWatchClick: () -> Unit,
    onStartRecordClick: () -> Unit,
    onStopRecordClick: () -> Unit,
    onEditClick: () -> Unit,
    onToggleAudio: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val activeRec = sessionState as? RecordingSessionState.Recording
    val isConnecting = sessionState is RecordingSessionState.Connecting
    val isStopping = sessionState is RecordingSessionState.Stopping
    val isBusyRecording = activeRec != null || isConnecting || isStopping

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onWatchClick)
            .testTag("camera_card_${camera.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isBusyRecording) 1.5.dp else 1.dp,
            color = if (isBusyRecording) RecordingCrimson else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = camera.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                if (camera.model.isNotBlank()) {
                                    append(camera.model)
                                    append(" • ")
                                }
                                append(camera.host.ifBlank { "RTSP Stream" })
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                when {
                    activeRec != null -> StatusPill(
                        label = "REC ${formatDuration(activeRec.durationMs)}",
                        containerColor = RecordingCrimsonContainer,
                        contentColor = RecordingCrimson,
                        dotColor = RecordingCrimson
                    )
                    isConnecting -> StatusPill(
                        label = "CONNECTING...",
                        containerColor = RecordingCrimsonContainer,
                        contentColor = RecordingCrimson,
                        dotColor = RecordingCrimson
                    )
                    isStopping -> StatusPill(
                        label = "SAVING MP4...",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.secondary
                    )
                    else -> StatusPill(
                        label = if (camera.useRtpTcp) "RTSP/TCP" else "RTSP/UDP",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                        dotColor = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Capability badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = if (camera.audioEnabled) "AUDIO ON" else "AUDIO MUTED",
                    containerColor = if (camera.audioEnabled) CloudEmeraldContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (camera.audioEnabled) CloudEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = if (camera.audioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff
                )
                if (camera.ptzSupported) {
                    StatusPill(
                        label = "ONVIF PTZ",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.secondary,
                        icon = Icons.Default.ControlCamera
                    )
                }
            }

            Text(
                text = com.example.security.SecurityGuards.redactUrlCredentials(camera.rtspUrl),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onWatchClick,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("watch_camera_${camera.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Live")
                    }

                    if (activeRec != null || isConnecting) {
                        Button(
                            onClick = onStopRecordClick,
                            enabled = !isStopping,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RecordingCrimson),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("stop_record_card_${camera.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop recording ${camera.name}",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop")
                        }
                    } else if (isStopping) {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Saving")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onStartRecordClick,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("start_record_card_${camera.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FiberManualRecord,
                                contentDescription = "Start recording ${camera.name}",
                                tint = RecordingCrimson,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Rec")
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleAudio,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (camera.audioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Toggle RTSP Audio",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit camera",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete camera",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraGridTile(
    camera: CameraEntity,
    sessionState: RecordingSessionState,
    onWatchClick: () -> Unit,
    onStartRecordClick: () -> Unit,
    onStopRecordClick: () -> Unit
) {
    val isRecording = sessionState is RecordingSessionState.Recording
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (isRecording) RecordingCrimson else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onWatchClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .background(androidx.compose.ui.graphics.Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                )

                // Top badges
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = camera.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }

                    if (isRecording) {
                        StatusPill(
                            label = "REC",
                            containerColor = RecordingCrimsonContainer,
                            contentColor = RecordingCrimson,
                            icon = Icons.Default.FiberManualRecord
                        )
                    }
                }

                // Click to expand icon
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Watch live",
                    tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(28.dp)
                )
            }

            // Bottom bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = camera.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                IconButton(
                    onClick = if (isRecording) onStopRecordClick else onStartRecordClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = "Toggle record",
                        tint = if (isRecording) RecordingCrimson else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
