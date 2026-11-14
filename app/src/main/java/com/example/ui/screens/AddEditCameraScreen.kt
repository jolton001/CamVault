package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.StatusPill
import com.example.ui.theme.CloudEmerald
import com.example.ui.theme.CloudEmeraldContainer
import com.example.ui.viewmodel.AddCameraUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditCameraScreen(
    uiState: AddCameraUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRtspUrlChange: (String) -> Unit,
    onSubStreamUrlChange: (String) -> Unit,
    onUseRtpTcpChange: (Boolean) -> Unit,
    onAudioEnabledChange: (Boolean) -> Unit,
    onScanLanClick: () -> Unit,
    onSelectDiscovered: (com.example.network.onvif.DiscoveredOnvifCamera) -> Unit,
    onProbeOnvifClick: () -> Unit,
    onSelectProfile: (com.example.network.onvif.OnvifMediaProfile) -> Unit,
    onApplyRtspPreset: (String) -> Unit = {},
    onTestAndPreviewClick: () -> Unit = {},
    onSaveClick: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.editingId > 0L) "Edit Camera" else "Connect ONVIF / RTSP Camera",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: ONVIF LAN Discovery
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "1. ONVIF LAN Auto-Discovery",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = uiState.localNetworkLabel.ifBlank {
                                    "Detecting local Wi-Fi IP & subnet..."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        FilledTonalButton(
                            onClick = onScanLanClick,
                            enabled = !uiState.isScanningLan,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scan_onvif_button")
                        ) {
                            if (uiState.isScanningLan) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (uiState.isScanningLan) "Scanning..." else "Scan LAN")
                        }
                    }

                    if (uiState.scanMessage.isNotBlank()) {
                        Text(
                            text = uiState.scanMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    uiState.discoveredCameras.forEach { cam ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectDiscovered(cam) },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cam.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "${cam.host}:${cam.port} • ${cam.xAddr}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "Select",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: ONVIF Credentials & Probe
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("2. Camera Endpoint & ONVIF Probe", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = onNameChange,
                        label = { Text("Camera Label") },
                        placeholder = { Text("Front Gate Cam") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("camera_name_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = uiState.host,
                            onValueChange = onHostChange,
                            label = { Text("Camera IP / Host") },
                            placeholder = {
                                Text(
                                    if (uiState.localSubnetPrefix.isNotBlank()) "${uiState.localSubnetPrefix}50"
                                    else "192.168.1.50"
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(2f)
                                .testTag("camera_host_input")
                        )
                        OutlinedTextField(
                            value = uiState.onvifPort,
                            onValueChange = onPortChange,
                            label = { Text("ONVIF Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = uiState.username,
                            onValueChange = onUsernameChange,
                            label = { Text("Username", maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier.weight(0.95f)
                        )
                        OutlinedTextField(
                            value = uiState.password,
                            onValueChange = onPasswordChange,
                            label = { Text("Password", maxLines = 1) },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle password visibility"
                                    )
                                }
                            },
                            modifier = Modifier.weight(1.05f)
                        )
                    }

                    OutlinedButton(
                        onClick = onProbeOnvifClick,
                        enabled = !uiState.isProbingOnvif,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("probe_onvif_button")
                    ) {
                        if (uiState.isProbingOnvif) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (uiState.isProbingOnvif) "Probing ONVIF Services..." else "Fetch ONVIF Profiles & RTSP URI")
                    }

                    if (uiState.probeStatusMessage.isNotBlank()) {
                        StatusPill(
                            label = uiState.probeStatusMessage,
                            containerColor = CloudEmeraldContainer,
                            contentColor = CloudEmerald,
                            icon = Icons.Default.CheckCircle
                        )
                    }

                    if (uiState.probeError.isNotBlank()) {
                        Text(
                            text = uiState.probeError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (uiState.discoveredProfiles.isNotEmpty()) {
                        Text("Discovered ONVIF Media Profiles", style = MaterialTheme.typography.labelLarge)
                        uiState.discoveredProfiles.forEach { profile ->
                            val selected = profile.token == uiState.profileToken
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectProfile(profile) },
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "${profile.name} (${profile.videoEncoding} ${profile.width}x${profile.height})",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        text = buildString {
                                            append(if (profile.hasAudio) "Audio: ${profile.audioEncoding.ifBlank { "Enabled" }}" else "No Audio")
                                            if (profile.rtspUri.isNotBlank()) append(" • ${profile.rtspUri}")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: Direct RTSP & Audio/Transport Settings
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("3. RTSP Stream & Audio Settings", style = MaterialTheme.typography.titleMedium)

                    Text("Quick Brand RTSP Templates", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("Hikvision", "Dahua", "Tapo / TP-Link", "Reolink", "Generic / IP Cam")) { brand ->
                            OutlinedButton(
                                onClick = { onApplyRtspPreset(brand) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text(brand, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = uiState.rtspUrl,
                        onValueChange = onRtspUrlChange,
                        label = { Text("Primary RTSP Stream URL") },
                        placeholder = { Text("rtsp://192.168.1.50:554/stream1") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rtsp_url_input")
                    )

                    OutlinedButton(
                        onClick = onTestAndPreviewClick,
                        enabled = !uiState.isCapturingPreview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("test_preview_button")
                    ) {
                        if (uiState.isCapturingPreview) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting & Capturing Live Frame...")
                        } else {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test RTSP Stream & Capture Live Frame")
                        }
                    }

                    uiState.previewBitmap?.let { bmp ->
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            border = BorderStroke(1.dp, CloudEmerald)
                        ) {
                            Column {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Live camera snapshot",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                )
                                StatusPill(
                                    label = "Live Preview Frame Verified Connected",
                                    containerColor = CloudEmeraldContainer,
                                    contentColor = CloudEmerald,
                                    icon = Icons.Default.CheckCircle,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = uiState.subStreamUrl,
                        onValueChange = onSubStreamUrlChange,
                        label = { Text("Sub-Stream RTSP URL (Optional)") },
                        placeholder = { Text("rtsp://192.168.1.50:554/stream2") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RTSP Audio Stream", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Monitor & record AAC / G.711 camera audio",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.audioEnabled,
                            onCheckedChange = onAudioEnabledChange
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Force RTP over TCP (Interleaved)", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Recommended behind routers/Wi-Fi for packet reliability",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.useRtpTcp,
                            onCheckedChange = onUseRtpTcpChange
                        )
                    }
                }
            }

            if (uiState.validationError.isNotBlank()) {
                Text(
                    text = uiState.validationError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = onSaveClick,
                enabled = !uiState.isSaving,
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("save_camera_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (uiState.editingId > 0L) "Update Camera" else "Save Camera")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
