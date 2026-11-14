package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.RecordingEntity
import com.example.data.local.UploadStatus
import com.example.data.repository.CamVaultRepository
import com.example.media.MediaStoreExporter
import com.example.network.cloud.CloudStorageNaming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RecordingFilter {
    ALL, UPLOADED, PENDING, FAILED
}

enum class DateFilterPeriod(val label: String) {
    ALL("All Dates"),
    TODAY("Today"),
    YESTERDAY("Yesterday")
}

data class RecordingsUiState(
    val allRecordings: List<RecordingEntity> = emptyList(),
    val filteredRecordings: List<RecordingEntity> = emptyList(),
    val selectedFilter: RecordingFilter = RecordingFilter.ALL,
    val selectedDateFilter: DateFilterPeriod = DateFilterPeriod.ALL,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val defaultCloudConfig: CloudStorageConfigEntity? = null,
    val statusBanner: String = ""
)

class RecordingsViewModel(
    private val repository: CamVaultRepository
) : ViewModel() {

    private val filterFlow = MutableStateFlow(RecordingFilter.ALL)
    private val dateFilterFlow = MutableStateFlow(DateFilterPeriod.ALL)
    private val selectionModeFlow = MutableStateFlow(false)
    private val selectedIdsFlow = MutableStateFlow<Set<Long>>(emptySet())
    private val bannerFlow = MutableStateFlow("")

    val uiState: StateFlow<RecordingsUiState> = combine(
        repository.recordings,
        repository.defaultCloudConfig,
        filterFlow,
        dateFilterFlow,
        combine(selectionModeFlow, selectedIdsFlow, bannerFlow) { selMode, selIds, banner ->
            Triple(selMode, selIds, banner)
        }
    ) { recordings, cloudConfig, filter, dateFilter, triple ->
        val (selMode, selIds, banner) = triple

        val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val yesterdayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
            Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        )

        val byDate = recordings.filter { rec ->
            val recDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(rec.startedAt))
            when (dateFilter) {
                DateFilterPeriod.ALL -> true
                DateFilterPeriod.TODAY -> recDateStr == todayDateStr
                DateFilterPeriod.YESTERDAY -> recDateStr == yesterdayDateStr
            }
        }

        val filtered = when (filter) {
            RecordingFilter.ALL -> byDate
            RecordingFilter.UPLOADED -> byDate.filter { it.uploadStatus == UploadStatus.UPLOADED }
            RecordingFilter.PENDING -> byDate.filter {
                it.uploadStatus == UploadStatus.LOCAL_ONLY ||
                    it.uploadStatus == UploadStatus.QUEUED ||
                    it.uploadStatus == UploadStatus.UPLOADING
            }
            RecordingFilter.FAILED -> byDate.filter { it.uploadStatus == UploadStatus.FAILED }
        }

        RecordingsUiState(
            allRecordings = recordings,
            filteredRecordings = filtered,
            selectedFilter = filter,
            selectedDateFilter = dateFilter,
            isSelectionMode = selMode,
            selectedIds = selIds,
            defaultCloudConfig = cloudConfig,
            statusBanner = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = RecordingsUiState()
    )

    fun setFilter(filter: RecordingFilter) {
        filterFlow.value = filter
    }

    fun setDateFilter(dateFilter: DateFilterPeriod) {
        dateFilterFlow.value = dateFilter
    }

    fun toggleSelectionMode() {
        val next = !selectionModeFlow.value
        selectionModeFlow.value = next
        if (!next) {
            selectedIdsFlow.value = emptySet()
        }
    }

    fun toggleSelect(recordingId: Long) {
        val current = selectedIdsFlow.value
        selectedIdsFlow.value = if (current.contains(recordingId)) {
            current - recordingId
        } else {
            current + recordingId
        }
    }

    fun selectAll() {
        selectedIdsFlow.value = uiState.value.filteredRecordings.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIdsFlow.value = emptySet()
        selectionModeFlow.value = false
    }

    fun clearBanner() {
        bannerFlow.value = ""
    }

    fun uploadRecording(recordingId: Long) {
        viewModelScope.launch {
            val ok = repository.enqueueCloudUpload(recordingId)
            bannerFlow.value = if (ok) {
                "Upload queued to cloud storage"
            } else {
                "No cloud storage target configured. Set one up in the Cloud tab."
            }
        }
    }

    fun retryAllPendingOrFailed() {
        viewModelScope.launch {
            val targets = uiState.value.allRecordings.filter {
                it.uploadStatus == UploadStatus.LOCAL_ONLY || it.uploadStatus == UploadStatus.FAILED
            }
            if (targets.isEmpty()) {
                bannerFlow.value = "All recordings are already synced."
                return@launch
            }
            var queued = 0
            for (rec in targets) {
                if (repository.enqueueCloudUpload(rec.id)) queued++
            }
            bannerFlow.value = if (queued > 0) {
                "Queued $queued recording(s) for cloud upload"
            } else {
                "Configure a cloud storage target first."
            }
        }
    }

    fun deleteRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            repository.deleteRecording(recording)
            bannerFlow.value = "Deleted ${recording.fileName}"
        }
    }

    fun deleteSelected() {
        val idsToDelete = selectedIdsFlow.value
        if (idsToDelete.isEmpty()) return
        viewModelScope.launch {
            val toDelete = uiState.value.allRecordings.filter { idsToDelete.contains(it.id) }
            for (rec in toDelete) {
                repository.deleteRecording(rec)
            }
            clearSelection()
            bannerFlow.value = "Deleted ${toDelete.size} recording(s)"
        }
    }

    fun syncSelected() {
        val ids = selectedIdsFlow.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var queued = 0
            for (id in ids) {
                if (repository.enqueueCloudUpload(id)) queued++
            }
            clearSelection()
            bannerFlow.value = "Queued $queued selected recording(s) for cloud upload"
        }
    }

    fun deleteAllUploaded() {
        viewModelScope.launch {
            val uploaded = uiState.value.allRecordings.filter { it.uploadStatus == UploadStatus.UPLOADED }
            if (uploaded.isEmpty()) {
                bannerFlow.value = "No uploaded recordings to delete."
                return@launch
            }
            var count = 0
            for (rec in uploaded) {
                // Free up local space
                runCatching {
                    val f = File(rec.localFilePath)
                    if (f.exists()) f.delete()
                }
                repository.deleteRecording(rec)
                count++
            }
            bannerFlow.value = "Deleted $count uploaded recording(s) and freed disk space"
        }
    }

    fun exportToGallery(context: Context, recording: RecordingEntity) {
        val file = File(recording.localFilePath)
        if (!file.exists() || file.length() == 0L) {
            bannerFlow.value = "Local file not found on disk (may have been uploaded & purged)."
            return
        }
        val exportName = CloudStorageNaming.buildRemoteFileName(recording.fileName, recording.startedAt)
        val res = MediaStoreExporter.exportToGallery(context, file, exportName)
        bannerFlow.value = res.fold(
            onSuccess = { "Saved '$exportName' to Gallery (Movies/CamVault)" },
            onFailure = { "Failed to export: ${it.localizedMessage}" }
        )
    }

    fun exportSelectedToGallery(context: Context) {
        val ids = selectedIdsFlow.value
        if (ids.isEmpty()) return
        val selected = uiState.value.allRecordings.filter { ids.contains(it.id) }
        var successCount = 0
        for (rec in selected) {
            val file = File(rec.localFilePath)
            if (file.exists()) {
                val exportName = CloudStorageNaming.buildRemoteFileName(rec.fileName, rec.startedAt)
                val res = MediaStoreExporter.exportToGallery(context, file, exportName)
                if (res.isSuccess) successCount++
            }
        }
        clearSelection()
        bannerFlow.value = "Saved $successCount video(s) to Gallery (Movies/CamVault)"
    }

    class Factory(private val repository: CamVaultRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RecordingsViewModel(repository) as T
        }
    }
}
