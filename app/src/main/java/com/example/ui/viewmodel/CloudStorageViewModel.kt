package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CloudProviderType
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.RECORD_INTERVAL_OPTIONS_MINUTES
import com.example.data.local.effectiveRecordIntervalMinutes
import com.example.data.repository.CamVaultRepository
import com.example.data.repository.isUploadConfigured
import com.example.network.cloud.CloudStorageUploader
import com.example.network.cloud.MegaCloudClient
import com.example.network.cloud.TenMinuteCloudSyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudStorageUiState(
    val savedConfigs: List<CloudStorageConfigEntity> = emptyList(),
    val editingId: Long = 0L,
    val name: String = "MEGA Drive Backup",
    val providerType: CloudProviderType = CloudProviderType.MEGA,
    val endpointUrl: String = MegaCloudClient.DEFAULT_ENDPOINT,
    val bucketName: String = "",
    val region: String = "us-east-1",
    val folderPrefix: String = "CamVault/Recordings",
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
    val nextScheduledSyncAt: Long? = null,
    val isTesting: Boolean = false,
    val testResultMessage: String = "",
    val testSuccess: Boolean? = null,
    val isSaving: Boolean = false,
    val saveMessage: String = ""
)

class CloudStorageViewModel(
    private val repository: CamVaultRepository,
    private val uploader: CloudStorageUploader,
    private val syncScheduler: TenMinuteCloudSyncScheduler,
    private val onCloudTargetConfigured: (restartOnIntervalChange: Boolean) -> Unit = {}
) : ViewModel() {

    private val formState = MutableStateFlow(CloudStorageUiState())
    private var loadedInitial = false

    init {
        viewModelScope.launch {
            val active = repository.cloudConfigs.first().firstOrNull()
            if (active != null && !loadedInitial && formState.value.editingId == 0L && formState.value.accessKey.isBlank()) {
                selectConfig(active)
            }
        }
    }

    val uiState: StateFlow<CloudStorageUiState> = combine(
        repository.cloudConfigs,
        formState,
        syncScheduler.nextScheduledSyncAt
    ) { configs, form, nextSyncAt ->
        val syncedLastAt = configs.firstOrNull { it.id == form.editingId }?.lastAutoSyncAt ?: form.lastAutoSyncAt
        form.copy(
            savedConfigs = configs,
            lastAutoSyncAt = syncedLastAt,
            nextScheduledSyncAt = nextSyncAt
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CloudStorageUiState()
    )

    fun selectConfig(config: CloudStorageConfigEntity) {
        loadedInitial = true
        formState.update {
            it.copy(
                editingId = config.id,
                name = config.name,
                providerType = config.providerType,
                endpointUrl = config.endpointUrl,
                bucketName = config.bucketName,
                region = config.region,
                folderPrefix = config.folderPrefix,
                accessKey = config.accessKey,
                secretKey = config.secretKey,
                bearerToken = config.bearerToken,
                autoUploadAfterRecord = config.autoUploadAfterRecord,
                uploadOverWifiOnly = config.uploadOverWifiOnly,
                periodicUploadEvery10Min = config.periodicUploadEvery10Min,
                splitRecordingEvery10Min = config.splitRecordingEvery10Min,
                recordIntervalMinutes = config.effectiveRecordIntervalMinutes(),
                autoRecordWhenCloudConfigured = config.autoRecordWhenCloudConfigured,
                deleteLocalAfterUpload = config.deleteLocalAfterUpload,
                lastAutoSyncAt = config.lastAutoSyncAt,
                testSuccess = config.lastTestSuccess,
                testResultMessage = config.lastTestMessage,
                saveMessage = ""
            )
        }
    }

    fun startNewConfig() {
        loadedInitial = true
        formState.value = CloudStorageUiState(editingId = 0L, name = "MEGA Drive Target")
    }

    fun applyPreset(presetName: String) {
        formState.update { cur ->
            when (presetName) {
                "MEGA Drive" -> cur.copy(
                    name = "MEGA Drive Backup",
                    providerType = CloudProviderType.MEGA,
                    endpointUrl = MegaCloudClient.DEFAULT_ENDPOINT,
                    folderPrefix = "CamVault/Recordings",
                    autoUploadAfterRecord = true,
                    periodicUploadEvery10Min = true,
                    splitRecordingEvery10Min = true,
                    testResultMessage = "",
                    testSuccess = null,
                    saveMessage = ""
                )
                "AWS S3" -> cur.copy(
                    name = "AWS S3 Vault",
                    providerType = CloudProviderType.S3_COMPATIBLE,
                    endpointUrl = "https://s3.us-east-1.amazonaws.com",
                    region = "us-east-1"
                )
                "Cloudflare R2" -> cur.copy(
                    name = "Cloudflare R2",
                    providerType = CloudProviderType.S3_COMPATIBLE,
                    endpointUrl = "https://<account-id>.r2.cloudflarestorage.com",
                    region = "auto"
                )
                "MinIO LAN" -> cur.copy(
                    name = "MinIO Home Server",
                    providerType = CloudProviderType.S3_COMPATIBLE,
                    endpointUrl = "http://192.168.1.100:9000",
                    region = "us-east-1"
                )
                "Nextcloud WebDAV" -> cur.copy(
                    name = "Nextcloud WebDAV",
                    providerType = CloudProviderType.WEBDAV,
                    endpointUrl = "https://cloud.example.com/remote.php/dav/files/username"
                )
                else -> cur.copy(
                    name = "Custom HTTP Endpoint",
                    providerType = CloudProviderType.CUSTOM_HTTP,
                    endpointUrl = "https://storage.example.com/upload/{filename}"
                )
            }
        }
    }

    fun updateName(v: String) = formState.update { it.copy(name = v, saveMessage = "") }
    fun updateProviderType(v: CloudProviderType) = formState.update { cur ->
        val nextEndpoint = if (v == CloudProviderType.MEGA && cur.endpointUrl.isBlank()) {
            MegaCloudClient.DEFAULT_ENDPOINT
        } else {
            cur.endpointUrl
        }
        val nextFolder = if (v == CloudProviderType.MEGA && cur.folderPrefix == "camvault/recordings") {
            "CamVault/Recordings"
        } else {
            cur.folderPrefix
        }
        cur.copy(
            providerType = v,
            endpointUrl = nextEndpoint,
            folderPrefix = nextFolder,
            testResultMessage = "",
            testSuccess = null,
            saveMessage = ""
        )
    }
    fun updateEndpointUrl(v: String) = formState.update { it.copy(endpointUrl = v, testResultMessage = "", testSuccess = null) }
    fun updateBucketName(v: String) = formState.update { it.copy(bucketName = v) }
    fun updateRegion(v: String) = formState.update { it.copy(region = v) }
    fun updateFolderPrefix(v: String) = formState.update { it.copy(folderPrefix = v) }
    fun updateAccessKey(v: String) {
        loadedInitial = true
        formState.update { it.copy(accessKey = v, saveMessage = "") }
    }
    fun updateSecretKey(v: String) {
        loadedInitial = true
        formState.update { it.copy(secretKey = v, saveMessage = "") }
    }
    fun updateBearerToken(v: String) = formState.update { it.copy(bearerToken = v) }
    fun setAutoUpload(v: Boolean) = formState.update { it.copy(autoUploadAfterRecord = v) }
    fun setWifiOnly(v: Boolean) = formState.update { it.copy(uploadOverWifiOnly = v) }
    fun setPeriodicUploadEvery10Min(v: Boolean) = formState.update { it.copy(periodicUploadEvery10Min = v) }
    fun setSplitRecordingEvery10Min(v: Boolean) = formState.update { it.copy(splitRecordingEvery10Min = v) }
    fun setAutoRecordWhenCloudConfigured(v: Boolean) = formState.update { it.copy(autoRecordWhenCloudConfigured = v) }
    fun setDeleteLocalAfterUpload(v: Boolean) = formState.update { it.copy(deleteLocalAfterUpload = v) }
    fun setRecordIntervalMinutes(minutes: Int) {
        val valid = minutes.takeIf { it in RECORD_INTERVAL_OPTIONS_MINUTES } ?: 10
        formState.update { it.copy(recordIntervalMinutes = valid, saveMessage = "") }
    }

    fun testConnection() {
        val current = formState.value
        if (current.providerType == CloudProviderType.MEGA) {
            if (current.accessKey.isBlank() || current.secretKey.isEmpty()) {
                formState.update {
                    it.copy(
                        testSuccess = false,
                        testResultMessage = "Enter your MEGA account email and password first."
                    )
                }
                return
            }
        } else if (current.endpointUrl.isBlank()) {
            formState.update {
                it.copy(testSuccess = false, testResultMessage = "Enter a valid cloud endpoint URL first.")
            }
            return
        }

        viewModelScope.launch {
            formState.update {
                it.copy(
                    isTesting = true,
                    testResultMessage = if (current.providerType == CloudProviderType.MEGA) {
                        "Authenticating with MEGA Drive..."
                    } else {
                        "Testing cloud endpoint..."
                    }
                )
            }
            val probeConfig = buildEntityFromForm(current)
            val result = uploader.testConnection(probeConfig)
            formState.update {
                it.copy(
                    isTesting = false,
                    testSuccess = result.success,
                    testResultMessage = "${result.message} (${result.latencyMs} ms)"
                )
            }
        }
    }

    fun saveConfig() {
        val current = formState.value
        if (current.providerType == CloudProviderType.MEGA) {
            if (current.accessKey.isBlank() || current.secretKey.isEmpty()) {
                formState.update { it.copy(saveMessage = "MEGA email and password are required.") }
                return
            }
        } else if (current.endpointUrl.isBlank()) {
            formState.update { it.copy(saveMessage = "Endpoint URL is required.") }
            return
        }

        viewModelScope.launch {
            formState.update { it.copy(isSaving = true) }
            val entity = buildEntityFromForm(current).copy(isDefault = true)
            val savedId = repository.saveCloudConfig(entity)
            syncScheduler.refreshSchedule()
            if (entity.isUploadConfigured() && entity.autoRecordWhenCloudConfigured) {
                onCloudTargetConfigured(true)
            }
            val interval = entity.effectiveRecordIntervalMinutes()
            val scheduleNote = buildString {
                if (entity.autoRecordWhenCloudConfigured && entity.isUploadConfigured()) {
                    append(" • Auto-recording & ${interval}-min upload active")
                } else if (entity.periodicUploadEvery10Min) {
                    append(" • ${interval}-min auto-backup active")
                }
            }
            formState.update {
                it.copy(
                    editingId = savedId,
                    isSaving = false,
                    saveMessage = "Saved '${entity.name}' as active target$scheduleNote."
                )
            }
        }
    }

    fun triggerSyncNow() {
        viewModelScope.launch {
            val queued = syncScheduler.triggerImmediateSync()
            val interval = uiState.value.recordIntervalMinutes
            formState.update {
                it.copy(
                    saveMessage = if (queued > 0) {
                        "Queued $queued pending/failed recording(s) for upload now."
                    } else {
                        "No pending recordings to upload (timer reset for next $interval min sweep)."
                    }
                )
            }
        }
    }

    fun setAsDefault(id: Long) {
        viewModelScope.launch {
            repository.setDefaultCloudConfig(id)
            syncScheduler.refreshSchedule()
            onCloudTargetConfigured(true)
        }
    }

    fun deleteConfig(id: Long) {
        viewModelScope.launch {
            repository.deleteCloudConfig(id)
            if (formState.value.editingId == id) {
                startNewConfig()
            }
            syncScheduler.refreshSchedule()
        }
    }

    private fun buildEntityFromForm(state: CloudStorageUiState): CloudStorageConfigEntity {
        val effectiveEndpoint = if (state.providerType == CloudProviderType.MEGA) {
            val raw = state.endpointUrl.trim().ifBlank { MegaCloudClient.DEFAULT_ENDPOINT }
            if (raw.startsWith("http://", ignoreCase = true)) {
                "https://" + raw.substring(7)
            } else if (!raw.startsWith("https://", ignoreCase = true)) {
                "https://$raw"
            } else {
                raw
            }
        } else {
            state.endpointUrl.trim()
        }
        return CloudStorageConfigEntity(
            id = state.editingId,
            name = state.name.trim().ifBlank {
                if (state.providerType == CloudProviderType.MEGA) "MEGA Drive Backup" else "Cloud Storage"
            },
            providerType = state.providerType,
            endpointUrl = effectiveEndpoint,
            bucketName = state.bucketName.trim(),
            region = state.region.trim().ifBlank { "us-east-1" },
            folderPrefix = state.folderPrefix.trim(),
            accessKey = state.accessKey.trim(),
            secretKey = state.secretKey,
            bearerToken = state.bearerToken.trim(),
            autoUploadAfterRecord = state.autoUploadAfterRecord,
            uploadOverWifiOnly = state.uploadOverWifiOnly,
            periodicUploadEvery10Min = state.periodicUploadEvery10Min,
            splitRecordingEvery10Min = state.splitRecordingEvery10Min,
            recordIntervalMinutes = state.recordIntervalMinutes.takeIf { it in RECORD_INTERVAL_OPTIONS_MINUTES } ?: 10,
            autoRecordWhenCloudConfigured = state.autoRecordWhenCloudConfigured,
            deleteLocalAfterUpload = state.deleteLocalAfterUpload,
            lastAutoSyncAt = state.lastAutoSyncAt,
            isDefault = true,
            lastTestedAt = System.currentTimeMillis(),
            lastTestSuccess = state.testSuccess,
            lastTestMessage = state.testResultMessage
        )
    }

    class Factory(
        private val repository: CamVaultRepository,
        private val uploader: CloudStorageUploader,
        private val syncScheduler: TenMinuteCloudSyncScheduler,
        private val onCloudTargetConfigured: (restartOnIntervalChange: Boolean) -> Unit = {}
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CloudStorageViewModel(repository, uploader, syncScheduler, onCloudTargetConfigured) as T
        }
    }
}
