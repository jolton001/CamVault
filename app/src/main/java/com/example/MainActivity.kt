package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.navigation.AddEditCameraRoute
import com.example.ui.navigation.CameraPlayerRoute
import com.example.ui.navigation.CloudStorageRoute
import com.example.ui.navigation.MonitorRoute
import com.example.ui.navigation.RecordingsRoute
import com.example.ui.screens.AddEditCameraScreen
import com.example.ui.screens.CameraPlayerScreen
import com.example.ui.screens.CloudStorageScreen
import com.example.ui.screens.MonitorScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.theme.CamVaultTheme
import com.example.ui.viewmodel.AddCameraViewModel
import com.example.ui.viewmodel.CameraPlayerViewModel
import com.example.ui.viewmodel.CloudStorageViewModel
import com.example.ui.viewmodel.MonitorViewModel
import com.example.ui.viewmodel.RecordingsViewModel

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            CamVaultTheme(darkTheme = true) {
                CamVaultRoot(widthSizeClass = windowSizeClass.widthSizeClass)
            }
        }
    }
}

private data class TopDestination(
    val route: Any,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
private fun CamVaultRoot(widthSizeClass: WindowWidthSizeClass) {
    val context = LocalContext.current
    val app = context.applicationContext as CamVaultApplication
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    // Request POST_NOTIFICATIONS cleanly on Android 13+ for foreground upload notifications
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val topDestinations = listOf(
        TopDestination(
            route = MonitorRoute,
            labelRes = R.string.nav_monitor,
            selectedIcon = Icons.Filled.Videocam,
            unselectedIcon = Icons.Outlined.Videocam
        ),
        TopDestination(
            route = RecordingsRoute,
            labelRes = R.string.nav_recordings,
            selectedIcon = Icons.Filled.VideoLibrary,
            unselectedIcon = Icons.Outlined.VideoLibrary
        ),
        TopDestination(
            route = CloudStorageRoute,
            labelRes = R.string.nav_cloud,
            selectedIcon = Icons.Filled.CloudQueue,
            unselectedIcon = Icons.Outlined.CloudQueue
        )
    )

    val showTopLevelChrome = currentDest?.let { dest ->
        dest.hasRoute<MonitorRoute>() || dest.hasRoute<RecordingsRoute>() || dest.hasRoute<CloudStorageRoute>()
    } ?: true

    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (showTopLevelChrome && !useRail) {
                NavigationBar {
                    topDestinations.forEach { dest ->
                        val selected = when (dest.route) {
                            MonitorRoute -> currentDest?.hasRoute<MonitorRoute>() == true
                            RecordingsRoute -> currentDest?.hasRoute<RecordingsRoute>() == true
                            CloudStorageRoute -> currentDest?.hasRoute<CloudStorageRoute>() == true
                            else -> false
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateTopLevel(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = stringResource(dest.labelRes)
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showTopLevelChrome && useRail) {
                NavigationRail {
                    topDestinations.forEach { dest ->
                        val selected = when (dest.route) {
                            MonitorRoute -> currentDest?.hasRoute<MonitorRoute>() == true
                            RecordingsRoute -> currentDest?.hasRoute<RecordingsRoute>() == true
                            CloudStorageRoute -> currentDest?.hasRoute<CloudStorageRoute>() == true
                            else -> false
                        }
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = stringResource(dest.labelRes)
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.background
            ) {
                NavHost(
                    navController = navController,
                    startDestination = MonitorRoute
                ) {
                    composable<MonitorRoute> {
                        val vm: MonitorViewModel = viewModel(
                            factory = MonitorViewModel.Factory(app)
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        MonitorScreen(
                            uiState = state,
                            isExpandedWidth = widthSizeClass != WindowWidthSizeClass.Compact,
                            onAddCameraClick = { navController.navigate(AddEditCameraRoute(-1L)) },
                            onEditCameraClick = { id -> navController.navigate(AddEditCameraRoute(id)) },
                            onOpenCameraPlayer = { id -> navController.navigate(CameraPlayerRoute(id)) },
                            onStartRecording = vm::startRecording,
                            onStopRecording = vm::stopRecording,
                            onToggleAudio = vm::toggleAudio,
                            onDeleteCamera = vm::deleteCamera,
                            onConfigureCloudClick = { navigateTopLevel(CloudStorageRoute) },
                            onClearBanner = vm::clearBanner
                        )
                    }

                    composable<RecordingsRoute> {
                        val vm: RecordingsViewModel = viewModel(
                            factory = RecordingsViewModel.Factory(app.repository)
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        val context = LocalContext.current
                        RecordingsScreen(
                            uiState = state,
                            onFilterChange = vm::setFilter,
                            onDateFilterChange = vm::setDateFilter,
                            onToggleSelectionMode = vm::toggleSelectionMode,
                            onToggleSelect = vm::toggleSelect,
                            onSelectAll = vm::selectAll,
                            onClearSelection = vm::clearSelection,
                            onDeleteSelected = vm::deleteSelected,
                            onSyncSelected = vm::syncSelected,
                            onExportSelectedToGallery = { vm.exportSelectedToGallery(context) },
                            onExportToGallery = { rec -> vm.exportToGallery(context, rec) },
                            onDeleteAllUploaded = vm::deleteAllUploaded,
                            onUploadClick = vm::uploadRecording,
                            onRetryAllClick = vm::retryAllPendingOrFailed,
                            onDeleteClick = vm::deleteRecording,
                            onClearBanner = vm::clearBanner
                        )
                    }

                    composable<CloudStorageRoute> {
                        val vm: CloudStorageViewModel = viewModel(
                            factory = CloudStorageViewModel.Factory(
                                repository = app.repository,
                                uploader = app.cloudStorageUploader,
                                syncScheduler = app.tenMinuteSyncScheduler,
                                onCloudTargetConfigured = { restartOnIntervalChange ->
                                    app.ensureAutoRecordingForConfiguredCameras(
                                        forceClearManualStop = true,
                                        restartOnIntervalChange = restartOnIntervalChange
                                    )
                                }
                            )
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        CloudStorageScreen(
                            uiState = state,
                            onSelectConfig = vm::selectConfig,
                            onNewConfig = vm::startNewConfig,
                            onApplyPreset = vm::applyPreset,
                            onNameChange = vm::updateName,
                            onProviderChange = vm::updateProviderType,
                            onEndpointChange = vm::updateEndpointUrl,
                            onBucketChange = vm::updateBucketName,
                            onRegionChange = vm::updateRegion,
                            onPrefixChange = vm::updateFolderPrefix,
                            onAccessKeyChange = vm::updateAccessKey,
                            onSecretKeyChange = vm::updateSecretKey,
                            onBearerTokenChange = vm::updateBearerToken,
                            onAutoUploadChange = vm::setAutoUpload,
                            onWifiOnlyChange = vm::setWifiOnly,
                            onPeriodic10MinChange = vm::setPeriodicUploadEvery10Min,
                            onSplitEvery10MinChange = vm::setSplitRecordingEvery10Min,
                            onRecordIntervalChange = vm::setRecordIntervalMinutes,
                            onAutoRecordChange = vm::setAutoRecordWhenCloudConfigured,
                            onDeleteLocalAfterUploadChange = vm::setDeleteLocalAfterUpload,
                            onSyncNowClick = vm::triggerSyncNow,
                            onTestConnection = vm::testConnection,
                            onSaveConfig = vm::saveConfig,
                            onDeleteConfig = vm::deleteConfig
                        )
                    }

                    composable<AddEditCameraRoute> { entry ->
                        val args = entry.toRoute<AddEditCameraRoute>()
                        val vm: AddCameraViewModel = viewModel(
                            factory = AddCameraViewModel.Factory(
                                cameraId = args.cameraId,
                                repository = app.repository,
                                onvifDiscovery = app.onvifDiscovery,
                                onvifSoapClient = app.onvifSoapClient
                            )
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        AddEditCameraScreen(
                            uiState = state,
                            onBack = { navController.popBackStack() },
                            onNameChange = vm::updateName,
                            onHostChange = vm::updateHost,
                            onPortChange = vm::updateOnvifPort,
                            onUsernameChange = vm::updateUsername,
                            onPasswordChange = vm::updatePassword,
                            onRtspUrlChange = vm::updateRtspUrl,
                            onSubStreamUrlChange = vm::updateSubStreamUrl,
                            onUseRtpTcpChange = vm::setUseRtpTcp,
                            onAudioEnabledChange = vm::setAudioEnabled,
                            onScanLanClick = vm::scanOnvifNetwork,
                            onSelectDiscovered = vm::selectDiscoveredCamera,
                            onProbeOnvifClick = vm::probeOnvifCamera,
                            onSelectProfile = vm::selectProfile,
                            onApplyRtspPreset = vm::applyRtspBrandPreset,
                            onTestAndPreviewClick = vm::testAndCapturePreview,
                            onSaveClick = {
                                vm.saveCamera { savedId ->
                                    app.autoStartCameraIfCloudConfigured(savedId)
                                    navController.popBackStack()
                                }
                            }
                        )
                    }

                    composable<CameraPlayerRoute> { entry ->
                        val args = entry.toRoute<CameraPlayerRoute>()
                        val vm: CameraPlayerViewModel = viewModel(
                            factory = CameraPlayerViewModel.Factory(
                                app = app,
                                cameraId = args.cameraId
                            )
                        )
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        CameraPlayerScreen(
                            uiState = state,
                            onBack = { navController.popBackStack() },
                            onToggleMute = vm::toggleMute,
                            onToggleSubStream = vm::toggleSubStream,
                            onToggleTransportTcp = vm::toggleTransportTcp,
                            onStartRecording = vm::startRecording,
                            onStopRecording = vm::stopRecording,
                            onPtzMove = vm::sendPtzMove,
                            onPtzStop = vm::stopPtz,
                            onSaveSnapshot = vm::saveSnapshot,
                            onUploadRecording = vm::uploadRecordingNow,
                            onPlayerStatusChange = vm::updatePlayerStatus,
                            onClearBanner = vm::clearBanner
                        )
                    }
                }
            }
        }
    }
}
