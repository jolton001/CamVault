package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class CamVaultConverters {
    @TypeConverter
    fun fromUploadStatus(status: UploadStatus): String = status.name

    @TypeConverter
    fun toUploadStatus(value: String): UploadStatus =
        runCatching { UploadStatus.valueOf(value) }.getOrDefault(UploadStatus.LOCAL_ONLY)

    @TypeConverter
    fun fromCloudProviderType(type: CloudProviderType): String = type.name

    @TypeConverter
    fun toCloudProviderType(value: String): CloudProviderType =
        runCatching { CloudProviderType.valueOf(value) }.getOrDefault(CloudProviderType.S3_COMPATIBLE)
}

@Database(
    entities = [
        CameraEntity::class,
        RecordingEntity::class,
        CloudStorageConfigEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(CamVaultConverters::class)
abstract class CamVaultDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao
    abstract fun recordingDao(): RecordingDao
    abstract fun cloudStorageConfigDao(): CloudStorageConfigDao

    companion object {
        @Volatile
        private var instance: CamVaultDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cloud_storage_configs ADD COLUMN periodicUploadEvery10Min INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE cloud_storage_configs ADD COLUMN splitRecordingEvery10Min INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE cloud_storage_configs ADD COLUMN lastAutoSyncAt INTEGER"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cloud_storage_configs ADD COLUMN recordIntervalMinutes INTEGER NOT NULL DEFAULT 10"
                )
                db.execSQL(
                    "ALTER TABLE cloud_storage_configs ADD COLUMN autoRecordWhenCloudConfigured INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cloud_storage_configs ADD COLUMN deleteLocalAfterUpload INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): CamVaultDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CamVaultDatabase::class.java,
                    "camvault.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
