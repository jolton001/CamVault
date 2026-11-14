package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class UploadStatus {
    LOCAL_ONLY,
    QUEUED,
    UPLOADING,
    UPLOADED,
    FAILED
}

enum class CloudProviderType {
    S3_COMPATIBLE,
    WEBDAV,
    CUSTOM_HTTP,
    MEGA
}

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val onvifPort: Int = 80,
    val onvifServiceUrl: String = "",
    val rtspUrl: String,
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
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cameraId: Long,
    val cameraName: String,
    val fileName: String,
    val localFilePath: String,
    val thumbnailPath: String = "",
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val hasAudio: Boolean = false,
    val audioCodec: String = "",
    val resolution: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val uploadStatus: UploadStatus = UploadStatus.LOCAL_ONLY,
    val uploadProgress: Int = 0,
    val remoteStorageUrl: String = "",
    val uploadError: String = "",
    val uploadedAt: Long? = null
)

@Entity(tableName = "cloud_storage_configs")
data class CloudStorageConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val providerType: CloudProviderType = CloudProviderType.S3_COMPATIBLE,
    val endpointUrl: String,
    val bucketName: String = "",
    val region: String = "us-east-1",
    val folderPrefix: String = "camvault/recordings",
    val accessKey: String = "",
    val secretKey: String = "",
    val bearerToken: String = "",
    val autoUploadAfterRecord: Boolean = true,
    val uploadOverWifiOnly: Boolean = false,
    val periodicUploadEvery10Min: Boolean = true,
    val splitRecordingEvery10Min: Boolean = true,
    val recordIntervalMinutes: Int = 10,
    val autoRecordWhenCloudConfigured: Boolean = true,
    val deleteLocalAfterUpload: Boolean = false,
    val lastAutoSyncAt: Long? = null,
    val isDefault: Boolean = true,
    val lastTestedAt: Long? = null,
    val lastTestSuccess: Boolean? = null,
    val lastTestMessage: String = ""
)

val RECORD_INTERVAL_OPTIONS_MINUTES = listOf(1, 5, 10, 15, 20, 25, 30)

fun CloudStorageConfigEntity.effectiveRecordIntervalMinutes(): Int =
    recordIntervalMinutes.takeIf { it in RECORD_INTERVAL_OPTIONS_MINUTES } ?: 10
