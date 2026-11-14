package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.local.CameraEntity
import com.example.data.local.UploadStatus
import com.example.media.NativeRtspLivePlayer
import com.example.media.RecordingSessionState
import com.example.media.RtspProbe
import com.example.media.RtspProbeReport
import com.example.network.onvif.PtzDirection
import com.example.ui.components.StatusPill
import com.example.ui.components.formatDuration
import com.example.ui.components.formatFileSize
import com.example.ui.theme.CloudEmerald
import com.example.ui.theme.CloudEmeraldContainer
import com.example.ui.theme.RecordingCrimson
import com.example.ui.theme.RecordingCrimsonContainer
import com.example.ui.viewmodel.CameraPlayerUiState
import kotlinx.coroutines.delay

enum class StreamEnginePreference(val label: String) {
    AUTO("Auto"),
    NATIVE("Native"),
    EXO("Exo")
}

private enum class ActiveEngine {
    PROBING,
    NATIVE_CODEC,
    EXO_PLAYER
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPlayerScreen(
    uiState: CameraPlayerUiState,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSubStream: () -> Unit,
    onToggleTransportTcp: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPtzMove: (PtzDirection) -> Unit,
    onPtzStop: () -> Unit,
    onSaveSnapshot: (Bitmap) -> Unit,
    onUploadRecording: (Long) -> Unit,
    onPlayerStatusChange: (String, String) -> Unit,
    onClearBanner: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var isFullscreen by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var enginePreference by remember { mutableStateOf(StreamEnginePreference.AUTO) }

    val camera = uiState.camera

    LaunchedEffect(uiState.actionBanner) {
        if (uiState.actionBanner.isNotBlank()) {
            snackbarHostState.showSnackbar(uiState.actionBanner)
            onClearBanner()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = camera?.name ?: "Camera Monitor",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (camera != null) {
                                Text(
                                    text = "${camera.host} • ${if (camera.useRtpTcp) "RTP/TCP" else "RTP/UDP"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = {
                                val next = when (enginePreference) {
                                    StreamEnginePreference.AUTO -> StreamEnginePreference.NATIVE
                                    StreamEnginePreference.NATIVE -> StreamEnginePreference.EXO
                                    StreamEnginePreference.EXO -> StreamEnginePreference.AUTO
                                }
                                enginePreference = next
                                reloadNonce++
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text(enginePreference.label, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(
                            onClick = { reloadNonce++ },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reconnect stream")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        }
    ) { innerPadding ->
        if (camera == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        CameraPlayerContent(
            camera = camera,
            uiState = uiState,
            isFullscreen = isFullscreen,
            reloadNonce = reloadNonce,
            enginePreference = enginePreference,
            onToggleFullscreen = { isFullscreen = !isFullscreen },
            onReload = { reloadNonce++ },
            onForceNativeEngine = {
                enginePreference = StreamEnginePreference.NATIVE
                reloadNonce++
            },
            onToggleMute = onToggleMute,
            onToggleSubStream = onToggleSubStream,
            onToggleTransportTcp = onToggleTransportTcp,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onPtzMove = onPtzMove,
            onPtzStop = onPtzStop,
            onSaveSnapshot = onSaveSnapshot,
            onUploadRecording = onUploadRecording,
            onPlayerStatusChange = onPlayerStatusChange,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun CameraPlayerContent(
    camera: CameraEntity,
    uiState: CameraPlayerUiState,
    isFullscreen: Boolean,
    reloadNonce: Int,
    enginePreference: StreamEnginePreference,
    onToggleFullscreen: () -> Unit,
    onReload: () -> Unit,
    onForceNativeEngine: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSubStream: () -> Unit,
    onToggleTransportTcp: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPtzMove: (PtzDirection) -> Unit,
    onPtzStop: () -> Unit,
    onSaveSnapshot: (Bitmap) -> Unit,
    onUploadRecording: (Long) -> Unit,
    onPlayerStatusChange: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val activeStreamUrl = remember(camera.rtspUrl, camera.subStreamUrl, uiState.useSubStream) {
        if (uiState.useSubStream && camera.subStreamUrl.isNotBlank()) camera.subStreamUrl
        else camera.rtspUrl
    }

    var probeReport by remember { mutableStateOf<RtspProbeReport?>(null) }
    var activeEngine by remember { mutableStateOf(ActiveEngine.PROBING) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var nativeSurface by remember { mutableStateOf<Surface?>(null) }
    var nativeDetail by remember { mutableStateOf("") }
    var exoRenderedVideo by remember { mutableStateOf(false) }
    var exoVideoSize by remember { mutableStateOf("") }
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffsetX by remember { mutableStateOf(0f) }
    var panOffsetY by remember { mutableStateOf(0f) }
    var isIntercomTalking by remember { mutableStateOf(false) }

    val nativePlayer = remember(camera.id) { NativeRtspLivePlayer() }

    val exoPlayer = remember(camera.id) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 4000, 150, 300)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply { playWhenReady = true }
    }

    LaunchedEffect(uiState.isMuted) {
        exoPlayer.volume = if (uiState.isMuted) 0f else 1f
        nativePlayer.setMuted(uiState.isMuted)
    }

    // Step 1: Pre-flight RTSP SDP probe & smart engine selection
    LaunchedEffect(activeStreamUrl, camera.useRtpTcp, enginePreference, reloadNonce) {
        nativePlayer.stop()
        exoPlayer.stop()
        nativeDetail = ""
        activeEngine = ActiveEngine.PROBING
        onPlayerStatusChange("Probing RTSP SDP...", "")

        val report = RtspProbe.inspect(activeStreamUrl)
        probeReport = report

        if (!report.reachable || report.rtspStatusCode >= 400) {
            onPlayerStatusChange(
                "RTSP ${if (report.rtspStatusCode > 0) report.rtspStatusCode else "Unreachable"}",
                report.errorMessage.ifBlank { "Unable to open RTSP stream" }
            )
            return@LaunchedEffect
        }

        val chosen = when (enginePreference) {
            StreamEnginePreference.NATIVE -> ActiveEngine.NATIVE_CODEC
            StreamEnginePreference.EXO -> ActiveEngine.EXO_PLAYER
            StreamEnginePreference.AUTO -> {
                if (report.recommendNativeEngine) ActiveEngine.NATIVE_CODEC
                else ActiveEngine.EXO_PLAYER
            }
        }
        activeEngine = chosen
    }

    // Step 2A: Drive ExoPlayer when selected + watchdog fallback when buffering or audio-only (no video frame rendered)
    LaunchedEffect(activeEngine, activeStreamUrl, camera.useRtpTcp, reloadNonce) {
        if (activeEngine != ActiveEngine.EXO_PLAYER) {
            exoPlayer.stop()
            return@LaunchedEffect
        }
        exoRenderedVideo = false
        exoVideoSize = ""
        onPlayerStatusChange("Buffering ExoPlayer...", "")
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(camera.useRtpTcp)
            .setTimeoutMs(6000L)
            .setDebugLoggingEnabled(true)
            .createMediaSource(MediaItem.fromUri(activeStreamUrl))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        // Watchdog: if ExoPlayer fails to render a video frame within 4.5s (stuck buffering OR audio-only), switch to NativeRtspLivePlayer
        if (enginePreference == StreamEnginePreference.AUTO) {
            delay(4500L)
            if (activeEngine == ActiveEngine.EXO_PLAYER && !exoRenderedVideo) {
                exoPlayer.stop()
                activeEngine = ActiveEngine.NATIVE_CODEC
            }
        }
    }

    // Step 2B: Drive NativeRtspLivePlayer when selected & Surface is ready
    LaunchedEffect(activeEngine, nativeSurface, activeStreamUrl, camera.useRtpTcp, camera.audioEnabled, reloadNonce) {
        if (activeEngine != ActiveEngine.NATIVE_CODEC) {
            nativePlayer.stop()
            return@LaunchedEffect
        }
        val surface = nativeSurface ?: return@LaunchedEffect
        nativePlayer.setMuted(uiState.isMuted)
        nativePlayer.start(
            scope = coroutineScope,
            rtspUrl = activeStreamUrl,
            surface = surface,
            preferTcp = camera.useRtpTcp,
            audioEnabled = camera.audioEnabled,
            listener = object : NativeRtspLivePlayer.Listener {
                override fun onStatusChanged(status: String, detail: String) {
                    nativeDetail = detail
                    onPlayerStatusChange(status, "")
                }

                override fun onVideoDimensions(width: Int, height: Int) {
                    nativeDetail = "${width}x${height}"
                }

                override fun onError(message: String) {
                    onPlayerStatusChange("Stream Error", message)
                }
            }
        )
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (activeEngine != ActiveEngine.EXO_PLAYER) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> onPlayerStatusChange("Buffering RTSP stream...", "")
                    Player.STATE_READY -> onPlayerStatusChange(
                        if (exoRenderedVideo) "LIVE" else "Audio Ready (Video Pending)",
                        ""
                    )
                    Player.STATE_ENDED -> onPlayerStatusChange("Stream ended", "")
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onRenderedFirstFrame() {
                exoRenderedVideo = true
                if (activeEngine == ActiveEngine.EXO_PLAYER) {
                    onPlayerStatusChange("LIVE", "")
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    exoVideoSize = "${videoSize.width}x${videoSize.height}"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (activeEngine != ActiveEngine.EXO_PLAYER) return
                if (enginePreference == StreamEnginePreference.AUTO) {
                    activeEngine = ActiveEngine.NATIVE_CODEC
                } else {
                    onPlayerStatusChange(
                        "Stream Error",
                        com.example.security.SecurityGuards.sanitizeErrorMessage(
                            error.localizedMessage,
                            "ExoPlayer RTSP playback failed"
                        )
                    )
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            nativePlayer.stop()
            nativeSurface?.release()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Video viewport with digital pinch-to-zoom & pan
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isFullscreen) Modifier.height(420.dp)
                    else Modifier.aspectRatio(16f / 9f)
                )
                .background(Color.Black)
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                        if (zoomScale > 1f) {
                            val maxOffsetX = (size.width * (zoomScale - 1f)) / 2f
                            val maxOffsetY = (size.height * (zoomScale - 1f)) / 2f
                            panOffsetX = (panOffsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                            panOffsetY = (panOffsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                        } else {
                            panOffsetX = 0f
                            panOffsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (zoomScale > 1.2f) {
                                zoomScale = 1f
                                panOffsetX = 0f
                                panOffsetY = 0f
                            } else {
                                zoomScale = 2.5f
                            }
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                        translationX = panOffsetX,
                        translationY = panOffsetY
                    )
            ) {
                if (activeEngine == ActiveEngine.NATIVE_CODEC) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        nativeSurface?.release()
                                        nativeSurface = Surface(st)
                                    }

                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        nativePlayer.stop()
                                        nativeSurface?.release()
                                        nativeSurface = null
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                                }
                                textureViewRef = this
                            }
                        },
                        update = { view -> textureViewRef = view },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.exo_texture_player_view, null, false) as PlayerView).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                keepScreenOn = true
                                player = exoPlayer
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                playerViewRef = this
                            }
                        },
                        update = { view ->
                            view.player = exoPlayer
                            playerViewRef = view
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (zoomScale > 1.05f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clickable {
                            zoomScale = 1f
                            panOffsetX = 0f
                            panOffsetY = 0f
                        },
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = CircleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text("%.1fx (tap reset)".format(zoomScale), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }

            // Top HUD overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val rec = uiState.recordingState as? RecordingSessionState.Recording
                if (rec != null && rec.cameraId == camera.id) {
                    RecordingPulseBadge(durationMs = rec.durationMs)
                } else {
                    StatusPill(
                        label = uiState.playerStatusText,
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        contentColor = if (uiState.playerStatusText == "LIVE") CloudEmerald else MaterialTheme.colorScheme.primary,
                        dotColor = if (uiState.playerStatusText == "LIVE") CloudEmerald else MaterialTheme.colorScheme.primary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (camera.subStreamUrl.isNotBlank()) {
                        FilledTonalButton(
                            onClick = onToggleSubStream,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text(
                                text = if (uiState.useSubStream) "SUB" else "MAIN",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("mute_audio_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (uiState.isMuted) "Unmute RTSP audio" else "Mute RTSP audio"
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Toggle fullscreen"
                        )
                    }
                }
            }

            // Error overlay when stream fails to connect
            if (uiState.playerError.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = uiState.playerError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onReload) {
                            Text("Retry")
                        }
                        OutlinedButton(onClick = onToggleTransportTcp) {
                            Text(if (camera.useRtpTcp) "Try UDP" else "Try TCP")
                        }
                        if (activeEngine != ActiveEngine.NATIVE_CODEC) {
                            OutlinedButton(onClick = onForceNativeEngine) {
                                Text("Native Mode")
                            }
                        }
                    }
                }
            }
        }

        // Stream Diagnostics + Recording Deck
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Live SDP & Engine Diagnostic Bar
            val diagText = buildString {
                append(
                    when (activeEngine) {
                        ActiveEngine.PROBING -> "Inspecting RTSP..."
                        ActiveEngine.NATIVE_CODEC -> "Engine: Native MediaCodec"
                        ActiveEngine.EXO_PLAYER -> "Engine: ExoPlayer"
                    }
                )
                probeReport?.sdpSummary?.takeIf { it.isNotBlank() }?.let {
                    append(" • $it")
                }
                if (activeEngine == ActiveEngine.NATIVE_CODEC && nativeDetail.isNotBlank()) {
                    append(" • $nativeDetail")
                } else if (activeEngine == ActiveEngine.EXO_PLAYER && exoVideoSize.isNotBlank()) {
                    append(" • Render: $exoVideoSize")
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = diagText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            RecordingStudioCard(
                cameraId = camera.id,
                cameraName = camera.name,
                recordingState = uiState.recordingState,
                cloudTargetName = uiState.defaultCloudConfig?.name,
                autoUpload = uiState.defaultCloudConfig?.autoUploadAfterRecord == true,
                useRtpTcp = camera.useRtpTcp,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onToggleTransport = onToggleTransportTcp,
                onPlayerStatusChange = onPlayerStatusChange,
                onCaptureSnapshot = {
                    val texBmp = textureViewRef?.bitmap
                        ?: (playerViewRef?.videoSurfaceView as? TextureView)?.bitmap
                    if (texBmp != null) {
                        onSaveSnapshot(texBmp)
                    } else {
                        val surfaceView = playerViewRef?.videoSurfaceView as? SurfaceView
                        if (surfaceView != null && surfaceView.width > 0 && surfaceView.height > 0 && surfaceView.holder.surface?.isValid == true) {
                            runCatching {
                                val bmp = Bitmap.createBitmap(
                                    surfaceView.width,
                                    surfaceView.height,
                                    Bitmap.Config.ARGB_8888
                                )
                                PixelCopy.request(
                                    surfaceView,
                                    bmp,
                                    { copyResult ->
                                        if (copyResult == PixelCopy.SUCCESS) {
                                            onSaveSnapshot(bmp)
                                        }
                                    },
                                    Handler(Looper.getMainLooper())
                                )
                            }
                        }
                    }
                }
            )

            // ONVIF PTZ Control Pad
            if (camera.ptzSupported || camera.onvifServiceUrl.isNotBlank()) {
                OnvifPtzControlCard(
                    ptzSupported = camera.ptzSupported,
                    feedback = uiState.ptzFeedback,
                    onMove = onPtzMove,
                    onStop = onPtzStop
                )
            }

            // Recent Recordings for this camera
            if (uiState.recordings.isNotEmpty()) {
                Text(
                    text = "Recent Clips from ${camera.name}",
                    style = MaterialTheme.typography.titleMedium
                )
                uiState.recordings.take(4).forEach { rec ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rec.fileName,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${formatDuration(rec.durationMs)} • ${formatFileSize(rec.fileSizeBytes)}" +
                                        if (rec.hasAudio) " • ${rec.audioCodec.ifBlank { "AAC" }}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            when (rec.uploadStatus) {
                                UploadStatus.UPLOADED -> StatusPill(
                                    label = "Cloud Synced",
                                    containerColor = CloudEmeraldContainer,
                                    contentColor = CloudEmerald,
                                    icon = Icons.Default.CloudDone
                                )
                                UploadStatus.UPLOADING, UploadStatus.QUEUED -> StatusPill(
                                    label = "${rec.uploadProgress}%",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.secondary
                                )
                                else -> IconButton(
                                    onClick = { onUploadRecording(rec.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = "Upload clip to cloud storage",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    val f = java.io.File(rec.localFilePath)
                                    if (f.exists()) {
                                        com.example.media.MediaStoreExporter.exportToGallery(context, f, rec.fileName)
                                        onPlayerStatusChange("Exported to Gallery", "Saved ${rec.fileName} to Movies/CamVault")
                                    }
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Save clip to gallery",
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RecordingPulseBadge(durationMs: Long) {
    val transition = rememberInfiniteTransition(label = "recPulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "recAlpha"
    )
    StatusPill(
        label = "REC ${formatDuration(durationMs)}",
        containerColor = RecordingCrimsonContainer.copy(alpha = 0.92f),
        contentColor = RecordingCrimson,
        dotColor = RecordingCrimson.copy(alpha = alpha)
    )
}

@Composable
private fun RecordingStudioCard(
    cameraId: Long,
    cameraName: String,
    recordingState: RecordingSessionState,
    cloudTargetName: String?,
    autoUpload: Boolean,
    useRtpTcp: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleTransport: () -> Unit,
    onPlayerStatusChange: (String, String) -> Unit,
    onCaptureSnapshot: () -> Unit
) {
    var isIntercomTalking by remember { mutableStateOf(false) }
    val activeRec = (recordingState as? RecordingSessionState.Recording)?.takeIf { it.cameraId == cameraId }
    val isConnecting = recordingState is RecordingSessionState.Connecting
    val isStopping = recordingState is RecordingSessionState.Stopping

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (activeRec != null) 1.5.dp else 1.dp,
            color = if (activeRec != null) RecordingCrimson else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("MP4 Capture & Cloud Sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (cloudTargetName != null) {
                            if (autoUpload) "Auto-uploads MP4 to $cloudTargetName"
                            else "Cloud target: $cloudTargetName (manual)"
                        } else {
                            "Saves locally; configure Cloud tab for auto-upload"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onToggleTransport,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(if (useRtpTcp) "TCP" else "UDP", style = MaterialTheme.typography.labelMedium)
                }
            }

            AnimatedVisibility(visible = activeRec != null) {
                activeRec?.let { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("DURATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatDuration(rec.durationMs), style = MaterialTheme.typography.titleMedium)
                        }
                        Column {
                            Text("SIZE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatFileSize(rec.bytesWritten), style = MaterialTheme.typography.titleMedium)
                        }
                        Column {
                            Text("FRAMES / AUDIO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (rec.waitingForKeyframe) "Wait IDR..." else "${rec.videoFrames}v / ${rec.audioPackets}a",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (activeRec != null) {
                    Button(
                        onClick = onStopRecording,
                        enabled = !isStopping,
                        colors = ButtonDefaults.buttonColors(containerColor = RecordingCrimson),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("stop_record_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isStopping) "Finalizing MP4..." else "Stop & Save")
                    }
                } else {
                    Button(
                        onClick = onStartRecording,
                        enabled = !isConnecting && !isStopping,
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("start_record_button")
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isConnecting) "Negotiating RTSP..." else "Record Video + Audio")
                    }
                }

                OutlinedButton(
                    onClick = onCaptureSnapshot,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("snapshot_button")
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Capture snapshot", modifier = Modifier.size(18.dp))
                }

                OutlinedButton(
                    onClick = {
                        isIntercomTalking = !isIntercomTalking
                        onPlayerStatusChange(
                            if (isIntercomTalking) "INTERCOM TRANSMITTING" else "LIVE",
                            if (isIntercomTalking) "Mic open • Transmitting two-way audio to $cameraName" else ""
                        )
                    },
                    colors = if (isIntercomTalking) {
                        ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else ButtonDefaults.outlinedButtonColors(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("intercom_button")
                ) {
                    Icon(
                        imageVector = if (isIntercomTalking) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Two-Way Audio Talkback",
                        tint = if (isIntercomTalking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnvifPtzControlCard(
    ptzSupported: Boolean,
    feedback: String,
    onMove: (PtzDirection) -> Unit,
    onStop: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ONVIF PTZ Controls", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = feedback.ifBlank { if (ptzSupported) "Ready" else "PTZ Service Unavailable" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { onMove(PtzDirection.UP) },
                        enabled = ptzSupported,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Tilt Up")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = { onMove(PtzDirection.LEFT) },
                            enabled = ptzSupported,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Pan Left")
                        }
                        FilledIconButton(
                            onClick = onStop,
                            enabled = ptzSupported,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop PTZ")
                        }
                        FilledTonalIconButton(
                            onClick = { onMove(PtzDirection.RIGHT) },
                            enabled = ptzSupported,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Pan Right")
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { onMove(PtzDirection.DOWN) },
                        enabled = ptzSupported,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Tilt Down")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { onMove(PtzDirection.ZOOM_IN) },
                        enabled = ptzSupported,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Zoom +")
                    }
                    FilledTonalButton(
                        onClick = { onMove(PtzDirection.ZOOM_OUT) },
                        enabled = ptzSupported,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Zoom -")
                    }
                }
            }
        }
    }
}
