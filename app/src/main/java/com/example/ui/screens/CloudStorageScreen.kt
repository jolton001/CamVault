package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.CloudProviderType
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.RECORD_INTERVAL_OPTIONS_MINUTES
import com.example.ui.components.StatusPill
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.CloudEmerald
import com.example.ui.theme.CloudEmeraldContainer
import com.example.ui.theme.RecordingCrimson
import com.example.ui.theme.RecordingCrimsonContainer
import com.example.ui.viewmodel.CloudStorageUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudStorageScreen(
    uiState: CloudStorageUiState,
    onSelectConfig: (CloudStorageConfigEntity) -> Unit,
    onNewConfig: () -> Unit,
    onApplyPreset: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onProviderChange: (CloudProviderType) -> Unit,
    onEndpointChange: (String) -> Unit,
    onBucketChange: (String) -> Unit,
    onRegionChange: (String) -> Unit,
    onPrefixChange: (String) -> Unit,
    onAccessKeyChange: (String) -> Unit,
    onSecretKeyChange: (String) -> Unit,
    onBearerTokenChange: (String) -> Unit,
    onAutoUploadChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onPeriodic10MinChange: (Boolean) -> Unit,
    onSplitEvery10MinChange: (Boolean) -> Unit,
    onRecordIntervalChange: (Int) -> Unit,
    onAutoRecordChange: (Boolean) -> Unit,
    onDeleteLocalAfterUploadChange: (Boolean) -> Unit = {},
    onSyncNowClick: () -> Unit,
    onTestConnection: () -> Unit,
    onSaveConfig: () -> Unit,
    onDeleteConfig: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSecret by remember { mutableStateOf(false) }
    val presets = listOf("MEGA Drive", "AWS S3", "Cloudflare R2", "MinIO LAN", "Nextcloud WebDAV", "Custom HTTP")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CLOUD STORAGE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Upload Destinations",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            TextButton(
                onClick = onNewConfig,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("new_cloud_target_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Target")
            }
        }

        if (uiState.savedConfigs.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.savedConfigs, key = { it.id }) { cfg ->
                    FilterChip(
                        selected = cfg.id == uiState.editingId,
                        onClick = { onSelectConfig(cfg) },
                        label = {
                            Text(
                                text = if (cfg.isDefault) "${cfg.name} (Active)" else cfg.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = if (uiState.savedConfigs.size > 1) {
                            {
                                IconButton(
                                    onClick = { onDeleteConfig(cfg.id) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete config",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else null
                    )
                }
            }
        }

        // Quick templates
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Quick Endpoint Templates",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets) { preset ->
                    OutlinedButton(
                        onClick = { onApplyPreset(preset) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("preset_${preset.lowercase().replace(' ', '_')}")
                    ) {
                        Text(preset, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Config Form Card
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Cloud Provider",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CloudProviderType.entries.forEach { type ->
                        val label = when (type) {
                            CloudProviderType.MEGA -> "MEGA Drive"
                            CloudProviderType.S3_COMPATIBLE -> "S3 / R2"
                            CloudProviderType.WEBDAV -> "WebDAV"
                            CloudProviderType.CUSTOM_HTTP -> "HTTP PUT"
                        }
                        FilterChip(
                            selected = uiState.providerType == type,
                            onClick = { onProviderChange(type) },
                            label = { Text(label, maxLines = 1) },
                            leadingIcon = if (uiState.providerType == type) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("provider_${type.name.lowercase()}")
                        )
                    }
                }

                if (uiState.providerType == CloudProviderType.MEGA) {
                    StatusPill(
                        label = "MEGA Drive (mega.nz) end-to-end encrypted backup",
                        containerColor = CloudEmeraldContainer,
                        contentColor = CloudEmerald,
                        icon = Icons.Default.CloudQueue
                    )
                }

                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = onNameChange,
                    label = { Text("Destination Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (uiState.providerType == CloudProviderType.MEGA) {
                    OutlinedTextField(
                        value = uiState.accessKey,
                        onValueChange = onAccessKeyChange,
                        label = { Text("MEGA Account Email") },
                        placeholder = { Text("you@example.com") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mega_email_input")
                    )

                    OutlinedTextField(
                        value = uiState.secretKey,
                        onValueChange = onSecretKeyChange,
                        label = { Text("MEGA Account Password") },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mega_password_input")
                    )

                    OutlinedTextField(
                        value = uiState.folderPrefix,
                        onValueChange = onPrefixChange,
                        label = { Text("MEGA Cloud Drive Folder") },
                        placeholder = { Text("CamVault/Recordings") },
                        supportingText = { Text("Clips saved to: <Folder>/<Camera>/<YYYY-MM-DD>/<DD-MM-YYYY-HH-mm-ss>.mp4") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mega_folder_input")
                    )

                    OutlinedTextField(
                        value = uiState.endpointUrl,
                        onValueChange = onEndpointChange,
                        label = { Text("MEGA API Endpoint") },
                        placeholder = { Text("https://g.api.mega.co.nz") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_endpoint_input")
                    )
                } else {
                    OutlinedTextField(
                        value = uiState.endpointUrl,
                        onValueChange = onEndpointChange,
                        label = {
                            Text(
                                when (uiState.providerType) {
                                    CloudProviderType.S3_COMPATIBLE -> "S3 Endpoint URL"
                                    CloudProviderType.WEBDAV -> "WebDAV Base URL"
                                    CloudProviderType.CUSTOM_HTTP -> "Upload PUT Endpoint URL"
                                    CloudProviderType.MEGA -> "MEGA API Endpoint"
                                }
                            )
                        },
                        placeholder = {
                            Text(
                                when (uiState.providerType) {
                                    CloudProviderType.S3_COMPATIBLE -> "https://s3.us-east-1.amazonaws.com"
                                    CloudProviderType.WEBDAV -> "https://cloud.example.com/remote.php/dav/files/user"
                                    CloudProviderType.CUSTOM_HTTP -> "https://api.example.com/videos/{filename}"
                                    CloudProviderType.MEGA -> "https://g.api.mega.co.nz"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_endpoint_input")
                    )

                    if (uiState.providerType == CloudProviderType.S3_COMPATIBLE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = uiState.bucketName,
                                onValueChange = onBucketChange,
                                label = { Text("Bucket Name") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1.4f)
                                    .testTag("cloud_bucket_input")
                            )
                            OutlinedTextField(
                                value = uiState.region,
                                onValueChange = onRegionChange,
                                label = { Text("Region") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.folderPrefix,
                        onValueChange = onPrefixChange,
                        label = { Text("Remote Folder / Key Prefix") },
                        placeholder = { Text("camvault/recordings") },
                        supportingText = { Text("Clips saved to: <Folder>/<Camera>/<YYYY-MM-DD>/<DD-MM-YYYY-HH-mm-ss>.mp4") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.providerType != CloudProviderType.CUSTOM_HTTP) {
                        OutlinedTextField(
                            value = uiState.accessKey,
                            onValueChange = onAccessKeyChange,
                            label = {
                                Text(
                                    if (uiState.providerType == CloudProviderType.S3_COMPATIBLE) "Access Key ID"
                                    else "WebDAV Username"
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = uiState.secretKey,
                            onValueChange = onSecretKeyChange,
                            label = {
                                Text(
                                    if (uiState.providerType == CloudProviderType.S3_COMPATIBLE) "Secret Access Key"
                                    else "WebDAV Password / App Token"
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showSecret = !showSecret }) {
                                    Icon(
                                        imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle secret visibility"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (uiState.providerType != CloudProviderType.S3_COMPATIBLE) {
                        OutlinedTextField(
                            value = uiState.bearerToken,
                            onValueChange = onBearerTokenChange,
                            label = { Text("Bearer Token (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Auto-Record & Cloud Upload Interval",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Auto-Record After Cloud Setup", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Automatically start recording all cameras and uploading clips once cloud storage is configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoRecordWhenCloudConfigured,
                        onCheckedChange = onAutoRecordChange,
                        modifier = Modifier.testTag("switch_auto_record_cloud")
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Record Clip & Upload Duration",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Choose how long each video segment records before saving & uploading",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RECORD_INTERVAL_OPTIONS_MINUTES.forEach { minutes ->
                            FilterChip(
                                selected = uiState.recordIntervalMinutes == minutes,
                                onClick = { onRecordIntervalChange(minutes) },
                                label = { Text("$minutes min") },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("interval_${minutes}_min")
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            "Every ${uiState.recordIntervalMinutes} Min Cloud Upload",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Automatically sweep & upload pending clips every ${uiState.recordIntervalMinutes} min",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.periodicUploadEvery10Min,
                        onCheckedChange = onPeriodic10MinChange,
                        modifier = Modifier.testTag("switch_periodic_10min")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            "Split Recording Every ${uiState.recordIntervalMinutes} Min",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Save & upload a playable MP4 clip every ${uiState.recordIntervalMinutes} min while recording stays active",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.splitRecordingEvery10Min,
                        onCheckedChange = onSplitEvery10MinChange,
                        modifier = Modifier.testTag("switch_split_10min")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Upload Immediately on Save", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Queue each ${uiState.recordIntervalMinutes}-min segment or stopped clip as soon as it finishes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = uiState.autoUploadAfterRecord, onCheckedChange = onAutoUploadChange)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Wi-Fi Only Uploads", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Hold uploads until connected to unmetered Wi-Fi",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = uiState.uploadOverWifiOnly, onCheckedChange = onWifiOnlyChange)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Delete Local MP4 After Cloud Upload", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Frees internal storage by removing the local video file once safely uploaded to cloud (thumbnail is preserved in Vault)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.deleteLocalAfterUpload,
                        onCheckedChange = onDeleteLocalAfterUploadChange,
                        modifier = Modifier.testTag("switch_delete_local_after_upload")
                    )
                }

                if (uiState.periodicUploadEvery10Min) {
                    val scheduleText = buildString {
                        append("Every ${uiState.recordIntervalMinutes} min backup")
                        uiState.nextScheduledSyncAt?.let { append(" • Next: ${formatTimestamp(it)}") }
                        uiState.lastAutoSyncAt?.let { append(" • Last: ${formatTimestamp(it)}") }
                    }
                    StatusPill(
                        label = scheduleText,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Default.Schedule
                    )
                }

                if (uiState.testResultMessage.isNotBlank()) {
                    StatusPill(
                        label = uiState.testResultMessage,
                        containerColor = when (uiState.testSuccess) {
                            true -> CloudEmeraldContainer
                            false -> RecordingCrimsonContainer
                            null -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = when (uiState.testSuccess) {
                            true -> CloudEmerald
                            false -> RecordingCrimson
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        icon = Icons.Default.CloudQueue
                    )
                }

                if (uiState.saveMessage.isNotBlank()) {
                    StatusPill(
                        label = uiState.saveMessage,
                        containerColor = CloudEmeraldContainer,
                        contentColor = CloudEmerald,
                        icon = Icons.Default.CheckCircle
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onTestConnection,
                        enabled = !uiState.isTesting,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("test_cloud_button")
                    ) {
                        if (uiState.isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test")
                    }
                    Button(
                        onClick = onSaveConfig,
                        enabled = !uiState.isSaving,
                        modifier = Modifier
                            .weight(1.3f)
                            .minimumInteractiveComponentSize()
                            .testTag("save_cloud_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Target")
                    }
                }

                FilledTonalButton(
                    onClick = onSyncNowClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("sync_now_cloud_button")
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run ${uiState.recordIntervalMinutes}-Min Backup Sync Now")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
