package com.example

import android.app.Application
import com.example.data.local.CameraEntity
import com.example.data.local.CamVaultDatabase
import com.example.data.local.RecordingEntity
import com.example.data.local.UploadStatus
import com.example.data.local.effectiveRecordIntervalMinutes
import com.example.data.repository.CamVaultRepository
import com.example.data.repository.isUploadConfigured
import com.example.media.RecordingForegroundService
import com.example.media.RtspStreamRecorder
import com.example.network.cloud.CloudStorageUploader
import com.example.network.cloud.TenMinuteCloudSyncScheduler
import com.example.network.onvif.OnvifDiscovery
import com.example.network.onvif.OnvifSoapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class CamVaultApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recordingMessages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val recordingMessages = _recordingMessages.asSharedFlow()
    private val manuallyStoppedCameraIds = ConcurrentHashMap.newKeySet<Long>()
    private val activeRecordingCameras = ConcurrentHashMap<Long, String>()
    private val reconnectAttempts = ConcurrentHashMap<Long, Int>()
    @Volatile
    private var lastAppliedIntervalMinutes: Int = 10

    val database: CamVaultDatabase by lazy {
        CamVaultDatabase.getInstance(this)
    }

    val repository: CamVaultRepository by lazy {
        CamVaultRepository(
            appContext = this,
            cameraDao = database.cameraDao(),
            recordingDao = database.recordingDao(),
            cloudConfigDao = database.cloudStorageConfigDao()
        )
    }

    val onvifDiscovery: OnvifDiscovery by lazy {
        OnvifDiscovery(this)
    }

    val onvifSoapClient: OnvifSoapClient by lazy {
        OnvifSoapClient()
    }

    val cloudStorageUploader: CloudStorageUploader by lazy {
        CloudStorageUploader()
    }

    val rtspStreamRecorder: RtspStreamRecorder by lazy {
        RtspStreamRecorder(this)
    }

    val tenMinuteSyncScheduler: TenMinuteCloudSyncScheduler by lazy {
        TenMinuteCloudSyncScheduler(
            appContext = this,
            scope = applicationScope,
            repository = repository,
            onSyncMessage = { msg -> _recordingMessages.tryEmit(msg) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        tenMinuteSyncScheduler.refreshSchedule()
        ensureAutoRecordingForConfiguredCameras(forceClearManualStop = false)
    }

    fun notifyCloudSyncEvent(message: String) {
        _recordingMessages.tryEmit(message)
    }

    /**
     * Automatically starts recording for all saved cameras whenever cloud storage is configured
     * and auto-record is enabled. If the selected recording interval changed, active sessions are
     * rolled cleanly so the new interval takes effect immediately.
     */
    fun ensureAutoRecordingForConfiguredCameras(
        forceClearManualStop: Boolean = false,
        restartOnIntervalChange: Boolean = false
    ) {
        applicationScope.launch {
            val cloud = repository.getDefaultCloudConfig() ?: return@launch
            if (!cloud.isUploadConfigured() || !cloud.autoRecordWhenCloudConfigured) return@launch

            val intervalMinutes = cloud.effectiveRecordIntervalMinutes()
            val intervalChanged = lastAppliedIntervalMinutes != intervalMinutes
            lastAppliedIntervalMinutes = intervalMinutes
            if (forceClearManualStop) {
                manuallyStoppedCameraIds.clear()
            }

            val allCameras = repository.cameras.first()
            if (allCameras.isEmpty()) return@launch

            if (restartOnIntervalChange && intervalChanged) {
                val activeToRestart = allCameras.filter { rtspStreamRecorder.isRecording(it.id) }
                if (activeToRestart.isNotEmpty()) {
                    activeToRestart.forEach { rtspStreamRecorder.stopRecording(it.id) }
                    delay(1200L)
                }
            }

            var startedCount = 0
            for (camera in allCameras) {
                if (!forceClearManualStop && camera.id in manuallyStoppedCameraIds) continue
                if (!rtspStreamRecorder.isRecording(camera.id)) {
                    startCameraRecording(camera)
                    startedCount++
                }
            }
            if (startedCount > 0) {
                _recordingMessages.tryEmit(
                    "Auto-recording $startedCount camera(s) (${intervalMinutes}-min clips → ${cloud.name})"
                )
            }
        }
    }

    fun autoStartCameraIfCloudConfigured(cameraId: Long) {
        if (cameraId <= 0L) return
        applicationScope.launch {
            val cloud = repository.getDefaultCloudConfig() ?: return@launch
            if (!cloud.isUploadConfigured() || !cloud.autoRecordWhenCloudConfigured) return@launch
            val camera = repository.getCameraById(cameraId) ?: return@launch
            manuallyStoppedCameraIds.remove(cameraId)
            if (!rtspStreamRecorder.isRecording(cameraId)) {
                startCameraRecording(camera)
                val intervalMinutes = cloud.effectiveRecordIntervalMinutes()
                _recordingMessages.tryEmit(
                    "Auto-recording ${camera.name} (${intervalMinutes}-min clips → ${cloud.name})"
                )
            }
        }
    }

    fun startCameraRecording(camera: CameraEntity, useSubStream: Boolean = false) {
        manuallyStoppedCameraIds.remove(camera.id)
        if (rtspStreamRecorder.isRecording(camera.id)) return
        activeRecordingCameras[camera.id] = camera.name
        runCatching {
            RecordingForegroundService.startOrUpdate(this, activeRecordingCameras.values.toList())
        }
        val streamUrl = if (useSubStream && camera.subStreamUrl.isNotBlank()) {
            camera.subStreamUrl
        } else {
            camera.rtspUrl
        }
        applicationScope.launch {
            val cloud = repository.getDefaultCloudConfig()
            val intervalMinutes = cloud?.effectiveRecordIntervalMinutes() ?: 10
            lastAppliedIntervalMinutes = intervalMinutes
            val segmentMs = if (cloud?.splitRecordingEvery10Min != false) {
                intervalMinutes * 60_000L
            } else {
                0L
            }
            rtspStreamRecorder.startRecording(
                scope = applicationScope,
                cameraId = camera.id,
                cameraName = camera.name,
                rtspUrl = streamUrl,
                audioEnabled = camera.audioEnabled,
                preferTcp = camera.useRtpTcp,
                segmentDurationMs = segmentMs
            ) { result ->
                result.fold(
                    onSuccess = { completed ->
                        reconnectAttempts.remove(camera.id)
                        applicationScope.launch {
                            val entity = RecordingEntity(
                                cameraId = completed.cameraId,
                                cameraName = completed.cameraName,
                                fileName = completed.fileName,
                                localFilePath = completed.localFilePath,
                                thumbnailPath = completed.thumbnailPath,
                                durationMs = completed.durationMs,
                                fileSizeBytes = completed.fileSizeBytes,
                                hasAudio = completed.hasAudio,
                                audioCodec = completed.audioCodec,
                                resolution = completed.resolution,
                                startedAt = completed.startedAt,
                                uploadStatus = UploadStatus.LOCAL_ONLY
                            )
                            val recId = repository.saveRecordingAndMaybeUpload(entity)
                            val activeCloud = repository.getDefaultCloudConfig()
                            val msg = if (activeCloud != null &&
                                activeCloud.autoUploadAfterRecord &&
                                activeCloud.isUploadConfigured()
                            ) {
                                "Saved ${completed.fileName} • Uploading to ${activeCloud.name}..."
                            } else {
                                "Saved ${completed.fileName} to Vault (#$recId)"
                            }
                            _recordingMessages.tryEmit(msg)
                        }
                    },
                    onFailure = { err ->
                        activeRecordingCameras.remove(camera.id)
                        runCatching {
                            if (activeRecordingCameras.isEmpty()) {
                                RecordingForegroundService.stop(this@CamVaultApplication)
                            } else {
                                RecordingForegroundService.startOrUpdate(
                                    this@CamVaultApplication,
                                    activeRecordingCameras.values.toList()
                                )
                            }
                        }
                        val errMsg = com.example.security.SecurityGuards.sanitizeErrorMessage(
                            err.localizedMessage,
                            "Stream error"
                        )
                        _recordingMessages.tryEmit("${camera.name} recording stopped: $errMsg")

                        // Stream Auto-Reconnect & Watchdog
                        if (!manuallyStoppedCameraIds.contains(camera.id)) {
                            val attempts = (reconnectAttempts[camera.id] ?: 0) + 1
                            reconnectAttempts[camera.id] = attempts
                            val backoffMs = minOf(30_000L, 5_000L * minOf(attempts, 6))
                            applicationScope.launch {
                                delay(backoffMs)
                                if (!manuallyStoppedCameraIds.contains(camera.id) && !rtspStreamRecorder.isRecording(camera.id)) {
                                    _recordingMessages.tryEmit("Watchdog: Reconnecting to ${camera.name} (attempt #$attempts)...")
                                    startCameraRecording(camera, useSubStream)
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    fun stopCameraRecording(cameraId: Long) {
        manuallyStoppedCameraIds.add(cameraId)
        activeRecordingCameras.remove(cameraId)
        reconnectAttempts.remove(cameraId)
        rtspStreamRecorder.stopRecording(cameraId)
        runCatching {
            if (activeRecordingCameras.isEmpty()) {
                RecordingForegroundService.stop(this)
            } else {
                RecordingForegroundService.startOrUpdate(this, activeRecordingCameras.values.toList())
            }
        }
    }
}
