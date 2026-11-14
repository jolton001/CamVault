package com.example.network.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.CamVaultApplication
import com.example.data.local.effectiveRecordIntervalMinutes
import com.example.data.repository.CamVaultRepository
import com.example.data.repository.isUploadConfigured
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SyncPendingUploadsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? CamVaultApplication ?: return Result.success()
        val config = app.repository.getDefaultCloudConfig()
        if (config != null && config.periodicUploadEvery10Min && config.isUploadConfigured()) {
            val intervalMin = config.effectiveRecordIntervalMinutes().toLong()
            val queued = app.repository.syncPendingOrFailedRecordings(forceReplace = false)
            if (queued > 0) {
                app.notifyCloudSyncEvent("${intervalMin}-min auto-sync queued $queued recording(s) to ${config.name}")
            }
            TenMinuteCloudSyncScheduler.scheduleNextBackgroundWork(
                context = applicationContext,
                wifiOnly = config.uploadOverWifiOnly,
                intervalMinutes = intervalMin
            )
            app.tenMinuteSyncScheduler.onBackgroundSweepCompleted(queued, intervalMin)
        }
        return Result.success()
    }
}

class TenMinuteCloudSyncScheduler(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val repository: CamVaultRepository,
    private val onSyncMessage: (String) -> Unit
) {
    private val _nextScheduledSyncAt = MutableStateFlow<Long?>(null)
    val nextScheduledSyncAt: StateFlow<Long?> = _nextScheduledSyncAt.asStateFlow()

    private var tickerJob: Job? = null

    fun refreshSchedule() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            val config = repository.getDefaultCloudConfig()
            if (config == null || !config.periodicUploadEvery10Min || !config.isUploadConfigured()) {
                _nextScheduledSyncAt.value = null
                cancelBackgroundWork(appContext)
                return@launch
            }

            val initialMinutes = config.effectiveRecordIntervalMinutes().toLong()
            scheduleNextBackgroundWork(appContext, config.uploadOverWifiOnly, initialMinutes)
            while (isActive) {
                val activeConfig = repository.getDefaultCloudConfig()
                if (activeConfig == null || !activeConfig.periodicUploadEvery10Min || !activeConfig.isUploadConfigured()) {
                    _nextScheduledSyncAt.value = null
                    cancelBackgroundWork(appContext)
                    break
                }
                val intervalMinutes = activeConfig.effectiveRecordIntervalMinutes().toLong()
                val intervalMs = intervalMinutes * 60_000L
                val nextAt = System.currentTimeMillis() + intervalMs
                _nextScheduledSyncAt.value = nextAt
                delay(intervalMs)
                val latest = repository.getDefaultCloudConfig()
                if (latest == null || !latest.periodicUploadEvery10Min || !latest.isUploadConfigured()) {
                    _nextScheduledSyncAt.value = null
                    cancelBackgroundWork(appContext)
                    break
                }
                val latestMinutes = latest.effectiveRecordIntervalMinutes().toLong()
                val queued = repository.syncPendingOrFailedRecordings(forceReplace = false)
                if (queued > 0) {
                    onSyncMessage("${latestMinutes}-min auto-sync uploading $queued recording(s) to ${latest.name}")
                }
                scheduleNextBackgroundWork(appContext, latest.uploadOverWifiOnly, latestMinutes)
            }
        }
    }

    suspend fun triggerImmediateSync(): Int {
        val queued = repository.syncPendingOrFailedRecordings(forceReplace = true)
        val config = repository.getDefaultCloudConfig()
        if (config != null && config.periodicUploadEvery10Min && config.isUploadConfigured()) {
            val intervalMinutes = config.effectiveRecordIntervalMinutes().toLong()
            _nextScheduledSyncAt.value = System.currentTimeMillis() + (intervalMinutes * 60_000L)
            scheduleNextBackgroundWork(appContext, config.uploadOverWifiOnly, intervalMinutes)
        }
        return queued
    }

    fun onBackgroundSweepCompleted(queuedCount: Int, intervalMinutes: Long = TEN_MINUTES_MINUTES) {
        _nextScheduledSyncAt.value = System.currentTimeMillis() + (intervalMinutes * 60_000L)
    }

    companion object {
        const val TEN_MINUTES_MINUTES = 10L
        const val TEN_MINUTES_MS = 10L * 60L * 1000L
        const val UNIQUE_SYNC_WORK_NAME = "camvault_ten_minute_cloud_sync"

        fun scheduleNextBackgroundWork(
            context: Context,
            wifiOnly: Boolean,
            intervalMinutes: Long = TEN_MINUTES_MINUTES
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val request = OneTimeWorkRequestBuilder<SyncPendingUploadsWorker>()
                .setInitialDelay(intervalMinutes.coerceAtLeast(1L), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_SYNC_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancelBackgroundWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_SYNC_WORK_NAME)
        }
    }
}
