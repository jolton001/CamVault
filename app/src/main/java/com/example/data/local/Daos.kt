package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {
    @Query("SELECT * FROM cameras ORDER BY createdAt DESC")
    fun observeCameras(): Flow<List<CameraEntity>>

    @Query("SELECT * FROM cameras WHERE id = :id")
    fun observeCameraById(id: Long): Flow<CameraEntity?>

    @Query("SELECT * FROM cameras WHERE id = :id")
    suspend fun getCameraById(id: Long): CameraEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCamera(camera: CameraEntity): Long

    @Update
    suspend fun updateCamera(camera: CameraEntity)

    @Query("DELETE FROM cameras WHERE id = :id")
    suspend fun deleteCameraById(id: Long)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observeRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE cameraId = :cameraId ORDER BY startedAt DESC")
    fun observeRecordingsForCamera(cameraId: Long): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE uploadStatus IN ('LOCAL_ONLY', 'FAILED') ORDER BY startedAt ASC")
    suspend fun getPendingOrFailedRecordings(): List<RecordingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Query(
        "UPDATE recordings SET uploadStatus = :status, uploadProgress = :progress, " +
            "remoteStorageUrl = :remoteUrl, uploadError = :error, uploadedAt = :uploadedAt " +
            "WHERE id = :id"
    )
    suspend fun updateUploadState(
        id: Long,
        status: UploadStatus,
        progress: Int,
        remoteUrl: String,
        error: String,
        uploadedAt: Long?
    )

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecordingById(id: Long)
}

@Dao
interface CloudStorageConfigDao {
    @Query("SELECT * FROM cloud_storage_configs ORDER BY isDefault DESC, id DESC")
    fun observeConfigs(): Flow<List<CloudStorageConfigEntity>>

    @Query("SELECT * FROM cloud_storage_configs WHERE isDefault = 1 LIMIT 1")
    fun observeDefaultConfig(): Flow<CloudStorageConfigEntity?>

    @Query("SELECT * FROM cloud_storage_configs WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultConfig(): CloudStorageConfigEntity?

    @Query("SELECT * FROM cloud_storage_configs WHERE id = :id")
    suspend fun getConfigById(id: Long): CloudStorageConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: CloudStorageConfigEntity): Long

    @Update
    suspend fun updateConfig(config: CloudStorageConfigEntity)

    @Query("UPDATE cloud_storage_configs SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE cloud_storage_configs SET isDefault = 1 WHERE id = :id")
    suspend fun setAsDefault(id: Long)

    @Query("UPDATE cloud_storage_configs SET lastAutoSyncAt = :timestamp WHERE id = :id")
    suspend fun updateLastAutoSyncAt(id: Long, timestamp: Long)

    @Query("DELETE FROM cloud_storage_configs WHERE id = :id")
    suspend fun deleteConfigById(id: Long)
}
