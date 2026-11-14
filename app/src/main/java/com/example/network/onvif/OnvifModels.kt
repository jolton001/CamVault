package com.example.network.onvif

data class DiscoveredOnvifCamera(
    val xAddr: String,
    val host: String,
    val port: Int,
    val name: String,
    val hardwareModel: String,
    val scopes: List<String> = emptyList()
)

data class OnvifDeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val firmwareVersion: String = "",
    val serialNumber: String = "",
    val hardwareId: String = ""
)

data class OnvifMediaProfile(
    val token: String,
    val name: String,
    val videoEncoding: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
    val audioEncoding: String = "",
    val audioSampleRate: Int = 0,
    val rtspUri: String = ""
)

data class OnvifProbeResult(
    val serviceUrl: String,
    val mediaServiceUrl: String,
    val ptzServiceUrl: String,
    val deviceInfo: OnvifDeviceInfo,
    val profiles: List<OnvifMediaProfile>,
    val ptzSupported: Boolean
)

enum class PtzDirection(val pan: Float, val tilt: Float, val zoom: Float) {
    UP(0f, 0.5f, 0f),
    DOWN(0f, -0.5f, 0f),
    LEFT(-0.5f, 0f, 0f),
    RIGHT(0.5f, 0f, 0f),
    ZOOM_IN(0f, 0f, 0.5f),
    ZOOM_OUT(0f, 0f, -0.5f)
}
