package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CameraEntity
import com.example.data.repository.CamVaultRepository
import com.example.network.onvif.DiscoveredOnvifCamera
import com.example.network.onvif.OnvifDiscovery
import com.example.network.onvif.OnvifMediaProfile
import com.example.network.onvif.OnvifSoapClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.example.media.RtspProbe
import java.net.URI

data class AddCameraUiState(
    val editingId: Long = -1L,
    val name: String = "",
    val host: String = "",
    val onvifPort: String = "80",
    val onvifServiceUrl: String = "",
    val rtspUrl: String = "",
    val subStreamUrl: String = "",
    val username: String = "",
    val password: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val firmware: String = "",
    val profileToken: String = "",
    val ptzServiceUrl: String = "",
    val ptzSupported: Boolean = false,
    val useRtpTcp: Boolean = true,
    val audioEnabled: Boolean = true,
    val isScanningLan: Boolean = false,
    val localIpAddress: String = "",
    val localSubnetPrefix: String = "",
    val localNetworkLabel: String = "",
    val discoveredCameras: List<DiscoveredOnvifCamera> = emptyList(),
    val scanMessage: String = "",
    val isProbingOnvif: Boolean = false,
    val discoveredProfiles: List<OnvifMediaProfile> = emptyList(),
    val probeStatusMessage: String = "",
    val probeError: String = "",
    val isCapturingPreview: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val isSaving: Boolean = false,
    val validationError: String = ""
)

class AddCameraViewModel(
    private val cameraId: Long,
    private val repository: CamVaultRepository,
    private val onvifDiscovery: OnvifDiscovery,
    private val onvifSoapClient: OnvifSoapClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddCameraUiState(editingId = cameraId))
    val uiState: StateFlow<AddCameraUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val localNet = withContext(Dispatchers.IO) { onvifDiscovery.detectLocalNetwork() }
            if (localNet != null) {
                _uiState.update { state ->
                    state.copy(
                        localIpAddress = localNet.ipAddress,
                        localSubnetPrefix = localNet.subnetBase,
                        localNetworkLabel = buildString {
                            append("Your IP: ${localNet.ipAddress}/${localNet.prefixLength}")
                            if (localNet.interfaceName.isNotBlank()) append(" (${localNet.interfaceName})")
                        },
                        host = if (cameraId <= 0L && state.host.isBlank()) localNet.subnetBase else state.host
                    )
                }
            }
            if (cameraId > 0L) {
                repository.getCameraById(cameraId)?.let { cam ->
                    _uiState.update {
                        it.copy(
                            editingId = cam.id,
                            name = cam.name,
                            host = cam.host,
                            onvifPort = cam.onvifPort.toString(),
                            onvifServiceUrl = cam.onvifServiceUrl,
                            rtspUrl = cam.rtspUrl,
                            subStreamUrl = cam.subStreamUrl,
                            username = cam.username,
                            password = cam.password,
                            manufacturer = cam.manufacturer,
                            model = cam.model,
                            firmware = cam.firmware,
                            profileToken = cam.profileToken,
                            ptzServiceUrl = cam.ptzServiceUrl,
                            ptzSupported = cam.ptzSupported,
                            useRtpTcp = cam.useRtpTcp,
                            audioEnabled = cam.audioEnabled
                        )
                    }
                }
            } else {
                scanOnvifNetwork()
            }
        }
    }

    fun updateName(v: String) = _uiState.update { it.copy(name = v, validationError = "") }
    fun updateHost(v: String) = _uiState.update { it.copy(host = v, validationError = "", probeError = "") }
    fun updateOnvifPort(v: String) = _uiState.update { it.copy(onvifPort = v.filter { ch -> ch.isDigit() }) }
    fun updateRtspUrl(v: String) = _uiState.update { it.copy(rtspUrl = v, validationError = "") }
    fun updateSubStreamUrl(v: String) = _uiState.update { it.copy(subStreamUrl = v) }
    fun updateUsername(v: String) = _uiState.update { it.copy(username = v) }
    fun updatePassword(v: String) = _uiState.update { it.copy(password = v) }
    fun setUseRtpTcp(v: Boolean) = _uiState.update { it.copy(useRtpTcp = v) }
    fun setAudioEnabled(v: Boolean) = _uiState.update { it.copy(audioEnabled = v) }

    fun scanOnvifNetwork() {
        if (_uiState.value.isScanningLan) return
        viewModelScope.launch {
            val localNet = withContext(Dispatchers.IO) { onvifDiscovery.detectLocalNetwork() }
            val subnetText = localNet?.let { "${it.subnetBase}0/24" } ?: "LAN"
            _uiState.update { state ->
                state.copy(
                    isScanningLan = true,
                    localIpAddress = localNet?.ipAddress ?: state.localIpAddress,
                    localSubnetPrefix = localNet?.subnetBase ?: state.localSubnetPrefix,
                    localNetworkLabel = localNet?.let {
                        buildString {
                            append("Your IP: ${it.ipAddress}/${it.prefixLength}")
                            if (it.interfaceName.isNotBlank()) append(" (${it.interfaceName})")
                        }
                    } ?: "No active LAN IP detected",
                    host = if (state.editingId <= 0L && (state.host.isBlank() || state.host.endsWith(".")) && localNet != null) {
                        localNet.subnetBase
                    } else state.host,
                    scanMessage = if (localNet != null) {
                        "Scanning ${localNet.ipAddress} subnet ($subnetText) + WS-Discovery..."
                    } else {
                        "Broadcasting WS-Discovery probe on 239.255.255.250:3702..."
                    }
                )
            }
            val found = runCatching { onvifDiscovery.discoverCameras(3500L, localNet) }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    isScanningLan = false,
                    discoveredCameras = found,
                    scanMessage = if (found.isEmpty()) {
                        if (localNet != null) {
                            "No ONVIF/RTSP devices responded on ${localNet.subnetBase}0/24. Enter camera IP after ${localNet.subnetBase} below."
                        } else {
                            "No ONVIF devices responded on LAN. Connect to Wi-Fi or enter the camera IP below."
                        }
                    } else {
                        "Found ${found.size} camera(s) on ${localNet?.subnetBase?.let { base -> "${base}0/24" } ?: "LAN"}."
                    }
                )
            }
        }
    }

    fun selectDiscoveredCamera(cam: DiscoveredOnvifCamera) {
        _uiState.update {
            it.copy(
                name = it.name.ifBlank { cam.name },
                host = cam.host,
                onvifPort = cam.port.toString(),
                onvifServiceUrl = cam.xAddr,
                model = it.model.ifBlank { cam.hardwareModel },
                probeStatusMessage = "Selected ${cam.host}:${cam.port}. Enter credentials if required and tap Fetch ONVIF Profiles."
            )
        }
    }

    fun probeOnvifCamera() {
        val current = _uiState.value
        val hostOrUrl = current.onvifServiceUrl.ifBlank { current.host.trim() }
        if (hostOrUrl.isBlank()) {
            _uiState.update { it.copy(probeError = "Enter a camera IP address or ONVIF URL first.") }
            return
        }
        val port = current.onvifPort.toIntOrNull() ?: 80

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProbingOnvif = true,
                    probeError = "",
                    probeStatusMessage = "Querying ONVIF Capabilities, Profiles & Stream URI..."
                )
            }
            val result = onvifSoapClient.probeCamera(
                hostOrUrl = hostOrUrl,
                port = port,
                username = current.username.trim(),
                password = current.password
            )
            result.fold(
                onSuccess = { probe ->
                    val mainProfile = probe.profiles.firstOrNull()
                    val subProfile = probe.profiles.drop(1).firstOrNull()
                    val autoName = current.name.ifBlank {
                        listOf(probe.deviceInfo.manufacturer, probe.deviceInfo.model)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifBlank { current.host.ifBlank { "ONVIF Camera" } }
                    }
                    _uiState.update {
                        it.copy(
                            isProbingOnvif = false,
                            name = autoName,
                            onvifServiceUrl = probe.serviceUrl,
                            manufacturer = probe.deviceInfo.manufacturer,
                            model = probe.deviceInfo.model,
                            firmware = probe.deviceInfo.firmwareVersion,
                            ptzServiceUrl = probe.ptzServiceUrl,
                            ptzSupported = probe.ptzSupported,
                            discoveredProfiles = probe.profiles,
                            profileToken = mainProfile?.token.orEmpty(),
                            rtspUrl = mainProfile?.rtspUri?.ifBlank { it.rtspUrl } ?: it.rtspUrl,
                            subStreamUrl = subProfile?.rtspUri.orEmpty(),
                            audioEnabled = mainProfile?.hasAudio ?: it.audioEnabled,
                            probeStatusMessage = buildString {
                                append("ONVIF connected")
                                if (probe.deviceInfo.model.isNotBlank()) append(" • ${probe.deviceInfo.model}")
                                append(" • ${probe.profiles.size} profile(s)")
                                if (probe.ptzSupported) append(" • PTZ ready")
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isProbingOnvif = false,
                            probeError = err.localizedMessage ?: "ONVIF probe failed",
                            probeStatusMessage = ""
                        )
                    }
                }
            )
        }
    }

    fun selectProfile(profile: OnvifMediaProfile) {
        _uiState.update {
            it.copy(
                profileToken = profile.token,
                rtspUrl = profile.rtspUri.ifBlank { it.rtspUrl },
                audioEnabled = if (profile.hasAudio) true else it.audioEnabled
            )
        }
    }

    fun applyRtspBrandPreset(brand: String) {
        val host = _uiState.value.host.trim().ifBlank { "192.168.1.100" }
        val user = _uiState.value.username.trim()
        val pass = _uiState.value.password
        val auth = if (user.isNotBlank() && pass.isNotBlank()) "$user:$pass@" else if (user.isNotBlank()) "$user@" else ""

        val (mainPath, subPath) = when (brand) {
            "Hikvision" -> "/Streaming/Channels/101" to "/Streaming/Channels/102"
            "Dahua" -> "/cam/realmonitor?channel=1&subtype=0" to "/cam/realmonitor?channel=1&subtype=1"
            "Tapo / TP-Link" -> "/stream1" to "/stream2"
            "Reolink" -> "/h264Preview_01_main" to "/h264Preview_01_sub"
            else -> "/live/ch00_0" to "/live/ch00_1"
        }
        _uiState.update {
            it.copy(
                rtspUrl = "rtsp://$auth$host:554$mainPath",
                subStreamUrl = "rtsp://$auth$host:554$subPath"
            )
        }
    }

    fun testAndCapturePreview() {
        val current = _uiState.value
        val finalRtsp = if (current.rtspUrl.isNotBlank()) {
            OnvifSoapClient.injectRtspCredentials(
                current.rtspUrl.trim(),
                current.username.trim(),
                current.password
            )
        } else {
            ""
        }
        if (finalRtsp.isBlank() || !finalRtsp.startsWith("rtsp", ignoreCase = true)) {
            _uiState.update { it.copy(validationError = "Enter a valid rtsp:// URL to test.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isCapturingPreview = true, validationError = "") }
            withContext(Dispatchers.IO) {
                val report = RtspProbe.inspect(finalRtsp)
                val retriever = MediaMetadataRetriever()
                val bmp = runCatching {
                    retriever.setDataSource(finalRtsp, HashMap<String, String>())
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                }.getOrNull()
                runCatching { retriever.release() }

                val summary = if (bmp != null) {
                    "Live frame verified (${bmp.width}x${bmp.height}) • ${report.sdpSummary}"
                } else {
                    "RTSP probe complete: ${report.sdpSummary.ifBlank { "Stream accessible" }}"
                }

                _uiState.update {
                    it.copy(
                        isCapturingPreview = false,
                        previewBitmap = bmp,
                        probeStatusMessage = summary
                    )
                }
            }
        }
    }

    fun saveCamera(onSaved: (Long) -> Unit) {
        val current = _uiState.value
        val finalRtsp = if (current.rtspUrl.isNotBlank()) {
            OnvifSoapClient.injectRtspCredentials(
                current.rtspUrl.trim(),
                current.username.trim(),
                current.password
            )
        } else {
            ""
        }
        if (finalRtsp.isBlank() || !finalRtsp.startsWith("rtsp", ignoreCase = true)) {
            _uiState.update {
                it.copy(validationError = "Provide a valid rtsp:// stream URL or fetch it via ONVIF.")
            }
            return
        }

        val parsedHost = current.host.trim().ifBlank {
            runCatching { URI(finalRtsp).host }.getOrNull().orEmpty()
        }
        val finalName = current.name.trim().ifBlank {
            parsedHost.ifBlank { "IP Camera" }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, validationError = "") }
            val entity = CameraEntity(
                id = if (current.editingId > 0L) current.editingId else 0L,
                name = finalName,
                host = parsedHost,
                onvifPort = current.onvifPort.toIntOrNull() ?: 80,
                onvifServiceUrl = current.onvifServiceUrl.trim(),
                rtspUrl = finalRtsp,
                subStreamUrl = current.subStreamUrl.trim(),
                username = current.username.trim(),
                password = current.password,
                manufacturer = current.manufacturer,
                model = current.model,
                firmware = current.firmware,
                profileToken = current.profileToken,
                ptzServiceUrl = current.ptzServiceUrl,
                ptzSupported = current.ptzSupported,
                useRtpTcp = current.useRtpTcp,
                audioEnabled = current.audioEnabled
            )
            runCatching { repository.saveCamera(entity) }.fold(
                onSuccess = { savedId ->
                    _uiState.update { it.copy(isSaving = false) }
                    onSaved(savedId)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            validationError = err.localizedMessage ?: "Failed to save camera."
                        )
                    }
                }
            )
        }
    }

    class Factory(
        private val cameraId: Long,
        private val repository: CamVaultRepository,
        private val onvifDiscovery: OnvifDiscovery,
        private val onvifSoapClient: OnvifSoapClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AddCameraViewModel(cameraId, repository, onvifDiscovery, onvifSoapClient) as T
        }
    }
}
