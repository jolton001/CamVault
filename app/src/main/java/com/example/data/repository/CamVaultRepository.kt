package com.example.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.local.CameraDao
import com.example.data.local.CameraEntity
import com.example.data.local.CloudStorageConfigDao
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.RecordingDao
import com.example.data.local.RecordingEntity
import com.example.data.local.UploadStatus
import com.example.network.cloud.UploadRecordingWorker
import com.example.security.CredentialCipher
import com.example.security.SecurityGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

fun CloudStorageConfigEntity.isUploadConfigured(): Boolean {
    return when (providerType) {
        com.example.data.local.CloudProviderType.MEGA ->
            accessKey.isNotBlank() && secretKey.isNotEmpty()
        else -> endpointUrl.isNotBlank()
    }
}

class CamVaultRepository(
    private val appContext: Context,
    private val cameraDao: CameraDao,
    private val recordingDao: RecordingDao,
    private val cloudConfigDao: CloudStorageConfigDao,
    private val credentialCipher: CredentialCipher = CredentialCipher()
) {
    val cameras: Flow<List<CameraEntity>> = cameraDao.observeCameras()
        .map { list -> list.map(::decryptCamera) }
    val recordings: Flow<List<RecordingEntity>> = recordingDao.observeRecordings()
    val cloudConfigs: Flow<List<CloudStorageConfigEntity>> = cloudConfigDao.observeConfigs()
        .map { list -> list.map(::decryptCloudConfig) }
    val defaultCloudConfig: Flow<CloudStorageConfigEntity?> = cloudConfigDao.observeDefaultConfig()
        .map { it?.let(::decryptCloudConfig) }

    fun observeCamera(id: Long): Flow<CameraEntity?> = cameraDao.observeCameraById(id)
        .map { it?.let(::decryptCamera) }

    fun observeRecordingsForCamera(cameraId: Long): Flow<List<RecordingEntity>> =
        recordingDao.observeRecordingsForCamera(cameraId)

    suspend fun getCameraById(id: Long): CameraEntity? =
        cameraDao.getCameraById(id)?.let(::decryptCamera)

    suspend fun saveCamera(camera: CameraEntity): Long =
        cameraDao.insertCamera(encryptCamera(camera))

    suspend fun updateCamera(camera: CameraEntity) =
        cameraDao.updateCamera(encryptCamera(camera))

    suspend fun deleteCamera(id: Long) = cameraDao.deleteCameraById(id)

    suspend fun saveRecordingAndMaybeUpload(recording: RecordingEntity): Long {
        val insertedId = recordingDao.insertRecording(recording)
        val defaultConfig = getDefaultCloudConfig()
        if (defaultConfig != null && defaultConfig.autoUploadAfterRecord && defaultConfig.isUploadConfigured()) {
            enqueueCloudUpload(insertedId, defaultConfig)
        }
        return insertedId
    }

    suspend fun getRecordingById(id: Long): RecordingEntity? = recordingDao.getRecordingById(id)

    suspend fun updateUploadState(
        recordingId: Long,
        status: UploadStatus,
        progress: Int,
        remoteUrl: String = "",
        error: String = "",
        uploadedAt: Long? = null
    ) {
        val safeError = if (error.isBlank()) "" else SecurityGuards.sanitizeErrorMessage(error)
        recordingDao.updateUploadState(
            id = recordingId,
            status = status,
            progress = progress,
            remoteUrl = remoteUrl,
            error = safeError,
            uploadedAt = uploadedAt
        )
    }

    suspend fun enqueueCloudUpload(
        recordingId: Long,
        explicitConfig: CloudStorageConfigEntity? = null,
        replaceExisting: Boolean = true
    ): Boolean {
        val config = explicitConfig ?: getDefaultCloudConfig() ?: return false
        if (!config.isUploadConfigured()) return false

        recordingDao.updateUploadState(
            id = recordingId,
            status = UploadStatus.QUEUED,
            progress = 0,
            remoteUrl = "",
            error = "",
            uploadedAt = null
        )

        val networkType = if (config.uploadOverWifiOnly) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadRecordingWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    UploadRecordingWorker.KEY_RECORDING_ID to recordingId,
                    UploadRecordingWorker.KEY_CONFIG_ID to config.id
                )
            )
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "upload_recording_$recordingId",
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
        return true
    }

    suspend fun syncPendingOrFailedRecordings(forceReplace: Boolean = false): Int {
        val config = getDefaultCloudConfig() ?: return 0
        if (!config.isUploadConfigured()) return 0

        val targets = recordingDao.getPendingOrFailedRecordings()
        var queued = 0
        for (rec in targets) {
            val file = File(rec.localFilePath)
            if (file.exists() && file.length() > 0L) {
                if (enqueueCloudUpload(rec.id, config, replaceExisting = forceReplace)) {
                    queued++
                }
            }
        }
        cloudConfigDao.updateLastAutoSyncAt(config.id, System.currentTimeMillis())
        return queued
    }

    suspend fun deleteRecording(recording: RecordingEntity) {
        withContext(Dispatchers.IO) {
            if (SecurityGuards.isPathInsideAppStorage(appContext, recording.localFilePath)) {
                runCatching { File(recording.localFilePath).delete() }
            }
            if (recording.thumbnailPath.isNotBlank() &&
                SecurityGuards.isPathInsideAppStorage(appContext, recording.thumbnailPath)
            ) {
                runCatching { File(recording.thumbnailPath).delete() }
            }
        }
        recordingDao.deleteRecordingById(recording.id)
    }

    suspend fun getDefaultCloudConfig(): CloudStorageConfigEntity? =
        cloudConfigDao.getDefaultConfig()?.let(::decryptCloudConfig)

    suspend fun getCloudConfigById(id: Long): CloudStorageConfigEntity? =
        cloudConfigDao.getConfigById(id)?.let(::decryptCloudConfig)

    suspend fun saveCloudConfig(config: CloudStorageConfigEntity): Long {
        if (config.isDefault) {
            cloudConfigDao.clearDefaultFlags()
        }
        return cloudConfigDao.insertConfig(encryptCloudConfig(config))
    }

    suspend fun setDefaultCloudConfig(id: Long) {
        cloudConfigDao.clearDefaultFlags()
        cloudConfigDao.setAsDefault(id)
    }

    suspend fun deleteCloudConfig(id: Long) {
        cloudConfigDao.deleteConfigById(id)
    }

    private fun encryptCamera(camera: CameraEntity): CameraEntity = camera.copy(
        rtspUrl = credentialCipher.encrypt(camera.rtspUrl),
        subStreamUrl = credentialCipher.encrypt(camera.subStreamUrl),
        password = credentialCipher.encrypt(camera.password)
    )

    private fun decryptCamera(camera: CameraEntity): CameraEntity = camera.copy(
        rtspUrl = credentialCipher.decryptSafe(camera.rtspUrl),
        subStreamUrl = credentialCipher.decryptSafe(camera.subStreamUrl),
        password = credentialCipher.decryptSafe(camera.password)
    )

    private fun encryptCloudConfig(config: CloudStorageConfigEntity): CloudStorageConfigEntity = config.copy(
        accessKey = credentialCipher.encrypt(config.accessKey),
        secretKey = credentialCipher.encrypt(config.secretKey),
        bearerToken = credentialCipher.encrypt(config.bearerToken)
    )

    private fun decryptCloudConfig(config: CloudStorageConfigEntity): CloudStorageConfigEntity = config.copy(
        accessKey = credentialCipher.decryptSafe(config.accessKey),
        secretKey = credentialCipher.decryptSafe(config.secretKey),
        bearerToken = credentialCipher.decryptSafe(config.bearerToken)
    )
}
