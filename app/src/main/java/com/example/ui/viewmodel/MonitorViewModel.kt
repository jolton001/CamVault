package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.CamVaultApplication
import com.example.data.local.CameraEntity
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.RecordingEntity
import com.example.data.local.UploadStatus
import com.example.media.RecordingSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MonitorUiState(
    val cameras: List<CameraEntity> = emptyList(),
    val recentRecordings: List<RecordingEntity> = emptyList(),
    val defaultCloudConfig: CloudStorageConfigEntity? = null,
    val recordingSessions: Map<Long, RecordingSessionState> = emptyMap(),
    val uploadedCount: Int = 0,
    val pendingUploadCount: Int = 0,
    val statusBanner: String = ""
)

class MonitorViewModel(
    private val app: CamVaultApplication
) : ViewModel() {

    private val repository = app.repository
    private val recorder = app.rtspStreamRecorder
    private val bannerFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            app.recordingMessages.collect { message ->
                bannerFlow.value = message
            }
        }
    }

    val uiState: StateFlow<MonitorUiState> = combine(
        repository.cameras,
        repository.recordings,
        repository.defaultCloudConfig,
        recorder.sessionsState,
        bannerFlow
    ) { cameras, recordings, cloudConfig, sessions, banner ->
        MonitorUiState(
            cameras = cameras,
            recentRecordings = recordings.take(5),
            defaultCloudConfig = cloudConfig,
            recordingSessions = sessions,
            uploadedCount = recordings.count { it.uploadStatus == UploadStatus.UPLOADED },
            pendingUploadCount = recordings.count {
                it.uploadStatus == UploadStatus.QUEUED || it.uploadStatus == UploadStatus.UPLOADING
            },
            statusBanner = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MonitorUiState()
    )

    fun startRecording(camera: CameraEntity) {
        app.startCameraRecording(camera)
    }

    fun stopRecording(cameraId: Long) {
        app.stopCameraRecording(cameraId)
    }

    fun clearBanner() {
        bannerFlow.value = ""
    }

    fun deleteCamera(cameraId: Long) {
        viewModelScope.launch {
            app.stopCameraRecording(cameraId)
            repository.deleteCamera(cameraId)
        }
    }

    fun toggleAudio(camera: CameraEntity) {
        viewModelScope.launch {
            repository.updateCamera(camera.copy(audioEnabled = !camera.audioEnabled))
        }
    }

    class Factory(
        private val app: CamVaultApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MonitorViewModel(app) as T
        }
    }
}
