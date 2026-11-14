package com.example.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.CamVaultApplication
import com.example.data.local.CameraEntity
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.RecordingEntity
import com.example.media.RecordingSessionState
import com.example.network.onvif.PtzDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CameraPlayerUiState(
    val camera: CameraEntity? = null,
    val recordings: List<RecordingEntity> = emptyList(),
    val defaultCloudConfig: CloudStorageConfigEntity? = null,
    val recordingState: RecordingSessionState = RecordingSessionState.Idle,
    val isMuted: Boolean = false,
    val useSubStream: Boolean = false,
    val playerStatusText: String = "Connecting RTSP...",
    val playerError: String = "",
    val ptzFeedback: String = "",
    val actionBanner: String = ""
)

class CameraPlayerViewModel(
    private val app: CamVaultApplication,
    private val cameraId: Long
) : ViewModel() {

    private val repository = app.repository
    private val recorder = app.rtspStreamRecorder
    private val onvifSoapClient = app.onvifSoapClient

    private val localPrefs = MutableStateFlow(LocalPlayerControls())

    private data class LocalPlayerControls(
        val isMuted: Boolean = false,
        val useSubStream: Boolean = false,
        val playerStatusText: String = "Connecting RTSP...",
        val playerError: String = "",
        val ptzFeedback: String = "",
        val actionBanner: String = ""
    )

    init {
        viewModelScope.launch {
            app.recordingMessages.collect { message ->
                localPrefs.update { it.copy(actionBanner = message) }
            }
        }
    }

    val uiState: StateFlow<CameraPlayerUiState> = combine(
        repository.observeCamera(cameraId),
        repository.observeRecordingsForCamera(cameraId),
        repository.defaultCloudConfig,
        recorder.sessionsState.map { sessions -> sessions[cameraId] ?: RecordingSessionState.Idle },
        localPrefs
    ) { camera, recs, cloudConfig, recState, local ->
        CameraPlayerUiState(
            camera = camera,
            recordings = recs,
            defaultCloudConfig = cloudConfig,
            recordingState = recState,
            isMuted = local.isMuted || camera?.audioEnabled == false,
            useSubStream = local.useSubStream,
            playerStatusText = local.playerStatusText,
            playerError = local.playerError,
            ptzFeedback = local.ptzFeedback,
            actionBanner = local.actionBanner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = CameraPlayerUiState()
    )

    fun toggleMute() {
        localPrefs.update { it.copy(isMuted = !it.isMuted) }
    }

    fun toggleSubStream() {
        localPrefs.update { it.copy(useSubStream = !it.useSubStream) }
    }

    fun toggleTransportTcp() {
        val cam = uiState.value.camera ?: return
        viewModelScope.launch {
            repository.updateCamera(cam.copy(useRtpTcp = !cam.useRtpTcp))
            localPrefs.update {
                it.copy(
                    actionBanner = if (!cam.useRtpTcp) "Switched to RTP over TCP (Interleaved)" else "Switched to RTP over UDP"
                )
            }
        }
    }

    fun updatePlayerStatus(status: String, error: String = "") {
        localPrefs.update { it.copy(playerStatusText = status, playerError = error) }
    }

    fun clearBanner() {
        localPrefs.update { it.copy(actionBanner = "") }
    }

    fun startRecording() {
        val cam = uiState.value.camera ?: return
        app.startCameraRecording(cam, uiState.value.useSubStream)
    }

    fun stopRecording() {
        app.stopCameraRecording(cameraId)
    }

    fun sendPtzMove(direction: PtzDirection) {
        val cam = uiState.value.camera ?: return
        viewModelScope.launch {
            localPrefs.update { it.copy(ptzFeedback = "PTZ ${direction.name.replace('_', ' ')}...") }
            val result = onvifSoapClient.continuousMove(
                ptzServiceUrl = cam.ptzServiceUrl,
                profileToken = cam.profileToken,
                direction = direction,
                username = cam.username,
                password = cam.password
            )
            localPrefs.update {
                it.copy(
                    ptzFeedback = result.fold(
                        onSuccess = { "Moved ${direction.name.lowercase().replace('_', ' ')}" },
                        onFailure = { err -> "PTZ: ${err.localizedMessage ?: "Failed"}" }
                    )
                )
            }
        }
    }

    fun stopPtz() {
        val cam = uiState.value.camera ?: return
        viewModelScope.launch {
            onvifSoapClient.stopPtz(
                ptzServiceUrl = cam.ptzServiceUrl,
                profileToken = cam.profileToken,
                username = cam.username,
                password = cam.password
            )
            localPrefs.update { it.copy(ptzFeedback = "PTZ idle") }
        }
    }

    fun saveSnapshot(bitmap: Bitmap) {
        val cam = uiState.value.camera ?: return
        viewModelScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                val dir = File(app.filesDir, "snapshots").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "${cam.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_$stamp.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                file.name
            }
            localPrefs.update { it.copy(actionBanner = "Snapshot saved: $savedPath") }
        }
    }

    fun uploadRecordingNow(recordingId: Long) {
        viewModelScope.launch {
            val queued = repository.enqueueCloudUpload(recordingId)
            localPrefs.update {
                it.copy(
                    actionBanner = if (queued) {
                        "Cloud upload queued"
                    } else {
                        "Configure a Cloud Storage target in the Cloud tab first."
                    }
                )
            }
        }
    }

    class Factory(
        private val app: CamVaultApplication,
        private val cameraId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CameraPlayerViewModel(app, cameraId) as T
        }
    }
}
