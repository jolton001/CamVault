package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object MonitorRoute

@Serializable
data object RecordingsRoute

@Serializable
data object CloudStorageRoute

@Serializable
data class CameraPlayerRoute(val cameraId: Long)

@Serializable
data class AddEditCameraRoute(val cameraId: Long = -1L)
