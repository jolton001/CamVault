package com.example.network.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.CamVaultApplication
import com.example.R
import com.example.data.local.UploadStatus
import java.io.File

class UploadRecordingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getLong(KEY_RECORDING_ID, -1L)
        val configId = inputData.getLong(KEY_CONFIG_ID, -1L)
        if (recordingId <= 0L) return Result.failure()

        val app = applicationContext as CamVaultApplication
        val repository = app.repository
        val uploader = app.cloudStorageUploader

        val recording = repository.getRecordingById(recordingId) ?: return Result.failure()
        val config = (if (configId > 0L) repository.getCloudConfigById(configId) else null)
            ?: repository.getDefaultCloudConfig()
            ?: run {
                repository.updateUploadState(
                    recordingId = recordingId,
                    status = UploadStatus.FAILED,
                    progress = 0,
                    error = "No cloud storage target configured."
                )
                return Result.failure()
            }

        val localFile = File(recording.localFilePath)
        if (!localFile.exists() || localFile.length() == 0L) {
            repository.updateUploadState(
                recordingId = recordingId,
                status = UploadStatus.FAILED,
                progress = 0,
                error = "Local MP4 file missing."
            )
            return Result.failure()
        }

        ensureNotificationChannel()
        runCatching {
            setForeground(createForegroundInfo(recording.fileName, 0))
        }

        repository.updateUploadState(
            recordingId = recordingId,
            status = UploadStatus.UPLOADING,
            progress = 0
        )

        val targetFolder = CloudStorageNaming.buildTargetFolderPrefix(
            basePrefix = config.folderPrefix,
            cameraName = recording.cameraName,
            cameraId = recording.cameraId,
            startedAt = recording.startedAt
        )
        val remoteFileName = CloudStorageNaming.buildRemoteFileName(
            originalFileName = recording.fileName,
            startedAt = recording.startedAt
        )

        var lastNotifiedPct = -1
        val result = uploader.uploadVideoFile(
            file = localFile,
            remoteFileName = remoteFileName,
            config = config,
            targetFolderPrefix = targetFolder,
            onProgress = { pct ->
                repository.updateUploadState(
                    recordingId = recordingId,
                    status = UploadStatus.UPLOADING,
                    progress = pct
                )
                if (pct - lastNotifiedPct >= 5 || pct == 100) {
                    lastNotifiedPct = pct
                    runCatching {
                        setForeground(createForegroundInfo(remoteFileName, pct))
                    }
                }
            }
        )

        return result.fold(
            onSuccess = { uploaded ->
                repository.updateUploadState(
                    recordingId = recordingId,
                    status = UploadStatus.UPLOADED,
                    progress = 100,
                    remoteUrl = uploaded.remoteUrl,
                    error = "",
                    uploadedAt = System.currentTimeMillis()
                )
                if (config.deleteLocalAfterUpload) {
                    runCatching {
                        if (localFile.exists()) {
                            localFile.delete()
                        }
                    }
                }
                Result.success()
            },
            onFailure = { err ->
                Log.e("UploadRecordingWorker", "Upload failed for ${recording.fileName}: ${err.message}", err)
                val message = com.example.security.SecurityGuards.sanitizeErrorMessage(
                    err.localizedMessage,
                    "Cloud upload failed"
                )
                repository.updateUploadState(
                    recordingId = recordingId,
                    status = UploadStatus.FAILED,
                    progress = 0,
                    error = message
                )
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.upload_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.upload_notification_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(fileName: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading $fileName")
            .setContentText("Cloud sync in progress ($progress%)")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_BASE_ID + (inputData.getLong(KEY_RECORDING_ID, 1L).toInt() and 0xFF),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                NOTIFICATION_BASE_ID + (inputData.getLong(KEY_RECORDING_ID, 1L).toInt() and 0xFF),
                notification
            )
        }
    }

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        const val KEY_CONFIG_ID = "config_id"
        private const val CHANNEL_ID = "camvault_cloud_uploads"
        private const val NOTIFICATION_BASE_ID = 4100
    }
}
