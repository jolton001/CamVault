package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.muxer.AnnexBToAvccConverter
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

sealed interface RecordingSessionState {
    data object Idle : RecordingSessionState
    data object Connecting : RecordingSessionState
    data class Recording(
        val cameraId: Long,
        val fileName: String,
        val durationMs: Long,
        val bytesWritten: Long,
        val videoFrames: Long,
        val audioPackets: Long,
        val hasAudio: Boolean,
        val audioCodec: String,
        val resolution: String,
        val waitingForKeyframe: Boolean
    ) : RecordingSessionState
    data object Stopping : RecordingSessionState
}

data class CompletedRecording(
    val cameraId: Long,
    val cameraName: String,
    val fileName: String,
    val localFilePath: String,
    val thumbnailPath: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val hasAudio: Boolean,
    val audioCodec: String,
    val resolution: String,
    val startedAt: Long
)

class RtspStreamRecorder(private val context: Context) {

    private class ActiveSession(
        val job: Job,
        val stopRequested: AtomicBoolean = AtomicBoolean(false)
    )

    private val sessions = ConcurrentHashMap<Long, ActiveSession>()
    private val _sessionsState = MutableStateFlow<Map<Long, RecordingSessionState>>(emptyMap())
    val sessionsState: StateFlow<Map<Long, RecordingSessionState>> = _sessionsState.asStateFlow()

    fun isRecording(cameraId: Long): Boolean = sessions[cameraId]?.job?.isActive == true

    fun startRecording(
        scope: CoroutineScope,
        cameraId: Long,
        cameraName: String,
        rtspUrl: String,
        audioEnabled: Boolean,
        preferTcp: Boolean = true,
        segmentDurationMs: Long = 0L,
        onCompleted: (Result<CompletedRecording>) -> Unit
    ) {
        if (sessions[cameraId]?.job?.isActive == true) return

        val stopFlag = AtomicBoolean(false)
        updateCameraState(cameraId, RecordingSessionState.Connecting)

        val job = scope.launch(Dispatchers.IO) {
            val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val thumbsDir = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val safeName = cameraName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifBlank { "cam" }

            fun createSegmentFiles(segmentNumber: Int, previousFileName: String? = null): SegmentTarget {
                val stamp = SimpleDateFormat("dd-MM-yyyy-HH-mm-ss", Locale.US).format(Date())
                val suffix = if (previousFileName == "${safeName}_${cameraId}_$stamp.mp4") "_p$segmentNumber" else ""
                val base = "${safeName}_${cameraId}_$stamp$suffix"
                return SegmentTarget(
                    fileName = "$base.mp4",
                    outputFile = File(recordingsDir, "$base.mp4"),
                    thumbFile = File(thumbsDir, "$base.jpg"),
                    startedAt = System.currentTimeMillis()
                )
            }

            fun finalizeCompletedSegment(target: SegmentTarget, stats: RecordStats): CompletedRecording {
                if (!target.outputFile.exists() || target.outputFile.length() < 1024L || stats.videoFrames <= 0L) {
                    target.outputFile.delete()
                    throw IllegalStateException("No playable video frames received before recording stopped.")
                }
                val thumbPath = extractThumbnail(target.outputFile, target.thumbFile, stats.durationMs)
                return CompletedRecording(
                    cameraId = cameraId,
                    cameraName = cameraName,
                    fileName = target.fileName,
                    localFilePath = target.outputFile.absolutePath,
                    thumbnailPath = thumbPath,
                    durationMs = stats.durationMs,
                    fileSizeBytes = target.outputFile.length(),
                    hasAudio = stats.hasAudio,
                    audioCodec = stats.audioCodec,
                    resolution = stats.resolution,
                    startedAt = target.startedAt
                )
            }

            val initialSegment = createSegmentFiles(1)

            val result = runCatching {
                recordInternal(
                    cameraId = cameraId,
                    rtspUrl = rtspUrl,
                    initialSegment = initialSegment,
                    preferTcp = preferTcp,
                    audioEnabled = audioEnabled,
                    segmentDurationMs = segmentDurationMs,
                    createNextSegment = { idx, prevName -> createSegmentFiles(idx, prevName) },
                    onSegmentFinalized = { segTarget, segStats ->
                        scope.launch(Dispatchers.IO) {
                            val segResult = runCatching { finalizeCompletedSegment(segTarget, segStats) }
                            onCompleted(segResult)
                        }
                    },
                    stopRequested = stopFlag
                )
            }.map { (finalSegment, stats) ->
                finalizeCompletedSegment(finalSegment, stats)
            }

            sessions.remove(cameraId)
            removeCameraState(cameraId)
            onCompleted(result)
        }

        sessions[cameraId] = ActiveSession(job = job, stopRequested = stopFlag)
    }

    fun stopRecording(cameraId: Long) {
        val session = sessions[cameraId] ?: return
        if (session.job.isActive) {
            session.stopRequested.set(true)
            updateCameraState(cameraId, RecordingSessionState.Stopping)
        }
    }

    fun stopAllRecordings() {
        sessions.forEach { (camId, session) ->
            if (session.job.isActive) {
                session.stopRequested.set(true)
                updateCameraState(camId, RecordingSessionState.Stopping)
            }
        }
    }

    private fun updateCameraState(cameraId: Long, state: RecordingSessionState) {
        _sessionsState.update { current -> current + (cameraId to state) }
    }

    private fun removeCameraState(cameraId: Long) {
        _sessionsState.update { current -> current - cameraId }
    }

    private data class SegmentTarget(
        val fileName: String,
        val outputFile: File,
        val thumbFile: File,
        val startedAt: Long
    )

    private data class RecordStats(
        val durationMs: Long,
        val hasAudio: Boolean,
        val audioCodec: String,
        val resolution: String,
        val videoFrames: Long
    )

    internal data class VideoAccessUnit(
        val rtpTimestamp: Long,
        val nals: List<ByteArray>,
        val isKeyframe: Boolean
    )

    internal class VideoAccessUnitAssembler(
        private val isHevc: Boolean
    ) {
        private var pendingTs: Long? = null
        private val pendingNals = mutableListOf<ByteArray>()
        private var pendingIsKey = false
        private var pendingHasVcl = false

        var latestInBandVps: ByteArray? = null
            private set
        var latestInBandSps: ByteArray? = null
            private set
        var latestInBandPps: ByteArray? = null
            private set
        var inBandCsdRevision: Int = 0
            private set

        fun feed(rtpTimestamp: Long, marker: Boolean, nalUnits: List<ByteArray>): List<VideoAccessUnit> {
            val completed = mutableListOf<VideoAccessUnit>()

            if (pendingTs != null && rtpTimestamp != pendingTs) {
                val firstVcl = nalUnits.firstOrNull { isVclNal(it) }
                val continuesSamePicture = pendingHasVcl && firstVcl != null && !isFirstSliceOfPicture(firstVcl, isHevc)
                if (!continuesSamePicture) {
                    flushPending()?.let { completed.add(it) }
                }
            }
            pendingTs = rtpTimestamp

            for (nal in nalUnits) {
                if (nal.isEmpty()) continue
                if (isHevc) {
                    val nalType = (nal[0].toInt() shr 1) and 0x3F
                    when (nalType) {
                        32 -> {
                            if (pendingHasVcl) flushPending()?.let { completed.add(it) }
                            pendingTs = rtpTimestamp
                            val next = withStartCode(nal)
                            if (!next.contentEquals(latestInBandVps)) {
                                latestInBandVps = next
                                inBandCsdRevision++
                            }
                        }
                        33 -> {
                            if (pendingHasVcl) flushPending()?.let { completed.add(it) }
                            pendingTs = rtpTimestamp
                            val next = withStartCode(nal)
                            if (!next.contentEquals(latestInBandSps)) {
                                latestInBandSps = next
                                inBandCsdRevision++
                            }
                        }
                        34 -> {
                            if (pendingHasVcl) flushPending()?.let { completed.add(it) }
                            pendingTs = rtpTimestamp
                            val next = withStartCode(nal)
                            if (!next.contentEquals(latestInBandPps)) {
                                latestInBandPps = next
                                inBandCsdRevision++
                            }
                        }
                        35 -> {
                            if (pendingHasVcl) flushPending()?.let { completed.add(it) }
                            pendingTs = rtpTimestamp
                        }
                        in 16..21 -> {
                            if (pendingHasVcl && isFirstSliceOfPicture(nal, isHevc = true)) {
                                flushPending()?.let { completed.add(it) }
                                pendingTs = rtpTimestamp
                            }
                            pendingIsKey = true
                            pendingHasVcl = true
                            pendingNals.add(nal)
                        }
                        in 0..31 -> {
                            if (pendingHasVcl && isFirstSliceOfPicture(nal, isHevc = true)) {
                                flushPending()?.let { completed.add(it) }
                                pendingTs = rtpTimestamp
                            }
                            pendingHasVcl = true
                            pendingNals.add(nal)
                        }
                        else -> pendingNals.add(nal)
                    }
                } else {
                    val nalType = nal[0].toInt() and 0x1F
                    when (nalType) {
                        7 -> {
                            if (pendingHasVcl) flushPending()?.let { completed.add(it) }
                            pendingTs = rtpTimestamp
                            val next = withStartCode(nal)
                            if (!next.contentEquals(latestInBandSps)) {
                                latestInBandSps = next
                                inBandCsdRevision++
                            }
                        }
                        8 -> {
                            if (pendingHasVcl) flushPending()?.let { completed.add(it) }
                            pendingTs = rtpTimestamp
                            val next = withStartCode(nal)
                            if (!next.contentEquals(latestInBandPps)) {
                                latestInBandPps = next
                                inBandCsdRevision++
                            }
                        }
                        9, 12 -> {
                            if (pendingHasVcl && nalType == 9) {
                                flushPending()?.let { completed.add(it) }
                                pendingTs = rtpTimestamp
                            }
                        }
                        5 -> {
                            if (pendingHasVcl && isFirstSliceOfPicture(nal, isHevc = false)) {
                                flushPending()?.let { completed.add(it) }
                                pendingTs = rtpTimestamp
                            }
                            pendingIsKey = true
                            pendingHasVcl = true
                            pendingNals.add(nal)
                        }
                        in 1..4 -> {
                            if (pendingHasVcl && isFirstSliceOfPicture(nal, isHevc = false)) {
                                flushPending()?.let { completed.add(it) }
                                pendingTs = rtpTimestamp
                            }
                            if (nalType == 1 && isH264IntraSlice(nal)) {
                                pendingIsKey = true
                            }
                            pendingHasVcl = true
                            pendingNals.add(nal)
                        }
                        else -> pendingNals.add(nal)
                    }
                }
            }

            if (marker && pendingHasVcl) {
                flushPending()?.let { completed.add(it) }
            }
            return completed
        }

        private fun isVclNal(nal: ByteArray): Boolean {
            if (nal.isEmpty()) return false
            return if (isHevc) {
                ((nal[0].toInt() shr 1) and 0x3F) in 0..31
            } else {
                (nal[0].toInt() and 0x1F) in 1..5
            }
        }

        private fun isFirstSliceOfPicture(nal: ByteArray, isHevc: Boolean): Boolean {
            return if (isHevc) {
                nal.size >= 3 && (nal[2].toInt() and 0x80) != 0
            } else {
                nal.size >= 2 && (nal[1].toInt() and 0x80) != 0
            }
        }

        fun flushPending(): VideoAccessUnit? {
            if (!pendingHasVcl || pendingNals.isEmpty() || pendingTs == null) {
                pendingNals.clear()
                pendingIsKey = false
                pendingHasVcl = false
                pendingTs = null
                return null
            }
            val au = VideoAccessUnit(
                rtpTimestamp = pendingTs!!,
                nals = pendingNals.toList(),
                isKeyframe = pendingIsKey
            )
            pendingNals.clear()
            pendingIsKey = false
            pendingHasVcl = false
            pendingTs = null
            return au
        }

        private fun withStartCode(nal: ByteArray): ByteArray {
            val out = ByteArray(4 + nal.size)
            out[3] = 1
            System.arraycopy(nal, 0, out, 4, nal.size)
            return out
        }
    }

    @OptIn(UnstableApi::class)
    private fun recordInternal(
        cameraId: Long,
        rtspUrl: String,
        initialSegment: SegmentTarget,
        preferTcp: Boolean,
        audioEnabled: Boolean,
        segmentDurationMs: Long,
        createNextSegment: (Int, String) -> SegmentTarget,
        onSegmentFinalized: (SegmentTarget, RecordStats) -> Unit,
        stopRequested: AtomicBoolean
    ): Pair<SegmentTarget, RecordStats> {
        val uri = URI(rtspUrl.trim())
        val host = uri.host ?: throw IllegalArgumentException("Invalid RTSP URL host")
        val port = if (uri.port > 0) uri.port else 554
        val (username, password) = extractUserPass(uri)
        val cleanRtspUrl = stripCredentials(uri)

        val socket = Socket()
        var udpVideoSocket: java.net.DatagramSocket? = null
        var udpAudioSocket: java.net.DatagramSocket? = null
        var udpVideoThread: Thread? = null
        var udpAudioThread: Thread? = null
        var outputFos: FileOutputStream? = null
        var muxer: Mp4Muxer? = null
        var muxerStarted = false
        var aacEncoder: G711ToAacEncoder? = null

        try {
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(host, port), 8000)
            val input = BufferedInputStream(socket.getInputStream(), 65536)
            val output = socket.getOutputStream()
            val prePlayInterleaved = ArrayDeque<InterleavedPacket>()

            val rtspClient = RtspControlSession(
                input = input,
                output = output,
                username = username,
                password = password,
                capturedInterleaved = prePlayInterleaved
            )

            rtspClient.request("OPTIONS", cleanRtspUrl)
            val describeResp = rtspClient.request(
                "DESCRIBE",
                cleanRtspUrl,
                extraHeaders = mapOf("Accept" to "application/sdp")
            )
            if (describeResp.statusCode !in 200..299) {
                throw IllegalStateException("RTSP DESCRIBE failed (HTTP/RTSP ${describeResp.statusCode})")
            }

            val contentBase = describeResp.headers["content-base"]
                ?: describeResp.headers["content-location"]
                ?: cleanRtspUrl
            val sdp = parseSdp(describeResp.body, contentBase)
            val videoTrack = sdp.videoTrack
                ?: throw IllegalStateException("Camera SDP does not contain a supported video track.")

            var usingTcp = preferTcp
            var videoSetup = if (usingTcp) {
                rtspClient.request(
                    "SETUP",
                    videoTrack.controlUrl,
                    extraHeaders = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
                )
            } else {
                udpVideoSocket = java.net.DatagramSocket().apply {
                    soTimeout = 1200
                    receiveBufferSize = 1024 * 1024
                }
                val rtpPort = udpVideoSocket!!.localPort
                rtspClient.request(
                    "SETUP",
                    videoTrack.controlUrl,
                    extraHeaders = mapOf("Transport" to "RTP/AVP;unicast;client_port=$rtpPort-${rtpPort + 1}")
                )
            }
            if (videoSetup.statusCode == 461 && usingTcp) {
                usingTcp = false
                udpVideoSocket = java.net.DatagramSocket().apply {
                    soTimeout = 1200
                    receiveBufferSize = 1024 * 1024
                }
                val rtpPort = udpVideoSocket!!.localPort
                videoSetup = rtspClient.request(
                    "SETUP",
                    videoTrack.controlUrl,
                    extraHeaders = mapOf("Transport" to "RTP/AVP;unicast;client_port=$rtpPort-${rtpPort + 1}")
                )
            }
            if (videoSetup.statusCode !in 200..299) {
                throw IllegalStateException("RTSP Video SETUP failed (${videoSetup.statusCode})")
            }
            val videoChannel = if (usingTcp) parseInterleavedChannel(videoSetup.headers["transport"], 0) else 0
            var audioChannel = -1

            var audioTrack = if (audioEnabled) sdp.audioTrack else null
            if (audioTrack != null) {
                val audioSetup = if (usingTcp) {
                    rtspClient.request(
                        "SETUP",
                        audioTrack.controlUrl,
                        extraHeaders = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=2-3")
                    )
                } else {
                    udpAudioSocket = java.net.DatagramSocket().apply {
                        soTimeout = 1000
                        receiveBufferSize = 256 * 1024
                    }
                    val aPort = udpAudioSocket!!.localPort
                    rtspClient.request(
                        "SETUP",
                        audioTrack.controlUrl,
                        extraHeaders = mapOf("Transport" to "RTP/AVP;unicast;client_port=$aPort-${aPort + 1}")
                    )
                }
                if (audioSetup.statusCode in 200..299) {
                    audioChannel = if (usingTcp) parseInterleavedChannel(audioSetup.headers["transport"], 2) else 2
                } else {
                    runCatching { udpAudioSocket?.close() }
                    udpAudioSocket = null
                    audioTrack = null
                }
            }

            var playResp = rtspClient.request(
                "PLAY",
                contentBase,
                extraHeaders = mapOf("Range" to "npt=0.000-")
            )
            if (playResp.statusCode !in 200..299 && contentBase != cleanRtspUrl) {
                playResp = rtspClient.request(
                    "PLAY",
                    cleanRtspUrl,
                    extraHeaders = mapOf("Range" to "npt=0.000-")
                )
            }
            if (playResp.statusCode !in 200..299) {
                throw IllegalStateException("RTSP PLAY failed (${playResp.statusCode})")
            }

            val isHevc = videoTrack.codec.equals("H265", true) || videoTrack.codec.equals("HEVC", true)
            val sdpVps: ByteArray? = videoTrack.vps
            val sdpSps: ByteArray? = videoTrack.sps
            val sdpPps: ByteArray? = videoTrack.pps
            var lockedCsdRevision = -1
            var videoTrackIdx = -1
            var audioTrackIdx = -1

            val isG711 = audioTrack?.codec.equals("PCMU", true) || audioTrack?.codec.equals("PCMA", true)
            if (isG711 && audioTrack != null) {
                aacEncoder = runCatching {
                    G711ToAacEncoder(
                        sampleRate = audioTrack.clockRate.coerceAtLeast(8000),
                        channelCount = audioTrack.channels.coerceAtLeast(1),
                        isAlaw = audioTrack.codec.equals("PCMA", true)
                    ).apply { primeFormat() }
                }.getOrNull()
            }

            val h264Depacketizer = H264RtpDepacketizer()
            val hevcDepacketizer = HevcRtpDepacketizer()
            val aacDepacketizer = AacRtpDepacketizer()
            val auAssembler = VideoAccessUnitAssembler(isHevc)
            val preMuxerAuBuffer = ArrayDeque<VideoAccessUnit>()

            var videoFrames = 0L
            var preKeyframeAus = 0L
            var audioPackets = 0L
            var hasSeenKeyframe = false
            var firstVideoRtpTs: Long? = null
            var lastVideoRtpTs: Long? = null
            var lastVideoPtsUs = -1L
            var lastAudioRtpTs: Long? = null
            var lastAudioPtsUs = -1L
            var lastKeepaliveAt = System.currentTimeMillis()
            var currentSegment = initialSegment
            var segmentIndex = 1
            var recordWallStart = currentSegment.startedAt
            var resolutionLabel = if (videoTrack.width > 0 && videoTrack.height > 0) {
                "${videoTrack.width}x${videoTrack.height} ${if (isHevc) "HEVC" else "H264"}"
            } else {
                if (isHevc) "HEVC" else "H264"
            }

            fun resolveActiveCsd(): Triple<ByteArray?, ByteArray?, ByteArray?> {
                val vps = auAssembler.latestInBandVps ?: sdpVps
                val sps = auAssembler.latestInBandSps ?: sdpSps
                val pps = auAssembler.latestInBandPps ?: sdpPps
                return Triple(vps, sps, pps)
            }

            fun hasInBandCsdComplete(): Boolean {
                return auAssembler.latestInBandSps != null &&
                    auAssembler.latestInBandPps != null &&
                    (!isHevc || auAssembler.latestInBandVps != null)
            }

            fun hasAnyCsdComplete(): Boolean {
                val (vps, sps, pps) = resolveActiveCsd()
                return sps != null && pps != null && (!isHevc || vps != null)
            }

            fun startMuxerWithCurrentCsd(): Boolean {
                if (muxerStarted) return true
                val (vps, sps, pps) = resolveActiveCsd()
                if (sps == null || pps == null) return false
                if (isHevc && vps == null) return false

                val dims = if (isHevc) parseHevcSpsDimensions(sps) else RtspProbe.parseH264SpsDimensions(sps)
                val w = dims?.first ?: videoTrack.width.takeIf { it > 0 } ?: 1280
                val h = dims?.second ?: videoTrack.height.takeIf { it > 0 } ?: 720
                resolutionLabel = "${w}x${h} ${if (isHevc) "HEVC" else "H264"}"

                val videoFormat = buildVideoTrackFormat(
                    isHevc = isHevc,
                    width = w,
                    height = h,
                    vpsWithStartCode = vps,
                    spsWithStartCode = sps,
                    ppsWithStartCode = pps
                )

                val fos = FileOutputStream(currentSegment.outputFile)
                val newMuxer = createMp4Muxer(fos)
                videoTrackIdx = newMuxer.addTrack(videoFormat)
                audioTrackIdx = -1

                if (audioTrack != null) {
                    if (isG711 && aacEncoder != null) {
                        val encFormat = aacEncoder.outputFormat
                        val ascBuf = encFormat?.getByteBuffer("csd-0")
                        val ascBytes = if (ascBuf != null && ascBuf.remaining() > 0) {
                            ByteArray(ascBuf.remaining()).also { ascBuf.duplicate().get(it) }
                        } else {
                            buildDefaultAacAsc(
                                audioTrack.clockRate.coerceAtLeast(8000),
                                audioTrack.channels.coerceAtLeast(1)
                            )
                        }
                        val sr = encFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ?: audioTrack.clockRate.coerceAtLeast(8000)
                        val ch = encFormat?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            ?: audioTrack.channels.coerceAtLeast(1)
                        audioTrackIdx = newMuxer.addTrack(buildAacTrackFormat(sr, ch, ascBytes))
                    } else if (audioTrack.codec.contains("MPEG4-GENERIC", true) || audioTrack.codec.contains("AAC", true)) {
                        val sr = audioTrack.clockRate.coerceAtLeast(16000)
                        val ch = audioTrack.channels.coerceAtLeast(1)
                        val asc = audioTrack.aacConfig ?: buildDefaultAacAsc(sr, ch)
                        audioTrackIdx = newMuxer.addTrack(buildAacTrackFormat(sr, ch, asc))
                    }
                }

                lockedCsdRevision = auAssembler.inBandCsdRevision
                outputFos = fos
                muxer = newMuxer
                muxerStarted = true
                return true
            }

            fun rotateSegmentIfDue(upcomingAu: VideoAccessUnit) {
                if (segmentDurationMs <= 0L || !muxerStarted || videoFrames <= 0L) return
                val elapsedMs = (lastVideoPtsUs.coerceAtLeast(0L) / 1000L)
                    .coerceAtLeast(System.currentTimeMillis() - recordWallStart)
                if (elapsedMs < segmentDurationMs) return
                // Prefer rotating on an actual IDR keyframe; fall back after 6s grace if stream lacks frequent IDRs
                if (!upcomingAu.isKeyframe && elapsedMs < segmentDurationMs + 6_000L) return

                val finishedTarget = currentSegment
                val finishedStats = RecordStats(
                    durationMs = elapsedMs,
                    hasAudio = audioTrackIdx >= 0 && audioPackets > 0,
                    audioCodec = if (audioTrackIdx >= 0) {
                        if (isG711) "${audioTrack?.codec} -> AAC" else audioTrack?.codec.orEmpty().ifBlank { "AAC" }
                    } else "",
                    resolution = resolutionLabel,
                    videoFrames = videoFrames
                )

                runCatching { muxer?.close() }
                runCatching { outputFos?.close() }
                muxer = null
                outputFos = null
                muxerStarted = false
                videoTrackIdx = -1
                audioTrackIdx = -1

                onSegmentFinalized(finishedTarget, finishedStats)

                segmentIndex++
                currentSegment = createNextSegment(segmentIndex, finishedTarget.fileName)
                recordWallStart = currentSegment.startedAt
                videoFrames = 0L
                preKeyframeAus = 0L
                audioPackets = 0L
                hasSeenKeyframe = false
                firstVideoRtpTs = null
                lastVideoRtpTs = null
                lastVideoPtsUs = -1L
                lastAudioRtpTs = null
                lastAudioPtsUs = -1L
                preMuxerAuBuffer.clear()
            }

            fun writeAccessUnitToMuxer(au: VideoAccessUnit) {
                val activeMuxer = muxer ?: return
                if (videoTrackIdx < 0 || au.nals.isEmpty()) return

                // If in-band CSD changed AFTER muxer started (meaning camera switched SPS/PPS mid-stream),
                // prepend updated parameter sets in-band ahead of this keyframe AU.
                val nalsToWrite = if (au.isKeyframe && auAssembler.inBandCsdRevision != lockedCsdRevision && hasInBandCsdComplete()) {
                    lockedCsdRevision = auAssembler.inBandCsdRevision
                    buildList {
                        if (isHevc) auAssembler.latestInBandVps?.let { add(stripAnnexBPrefix(it)) }
                        auAssembler.latestInBandSps?.let { add(stripAnnexBPrefix(it)) }
                        auAssembler.latestInBandPps?.let { add(stripAnnexBPrefix(it)) }
                        addAll(au.nals)
                    }
                } else {
                    au.nals
                }

                val frameBytes = encodeAvccSample(nalsToWrite)
                if (frameBytes.isEmpty()) return

                val videoClock = videoTrack.clockRate.coerceAtLeast(90000).toLong()
                val ptsUs = if (firstVideoRtpTs == null || lastVideoPtsUs < 0L) {
                    firstVideoRtpTs = au.rtpTimestamp
                    0L
                } else {
                    val stepTicks = (au.rtpTimestamp - lastVideoRtpTs!!) and 0xFFFFFFFFL
                    val stepUs = (stepTicks * 1_000_000L) / videoClock
                    if (stepTicks in 1L..(videoClock * 2L)) {
                        lastVideoPtsUs + stepUs.coerceAtLeast(1_000L)
                    } else {
                        lastVideoPtsUs + 33_333L
                    }
                }
                lastVideoRtpTs = au.rtpTimestamp
                lastVideoPtsUs = ptsUs

                val sampleFlags = if (au.isKeyframe || videoFrames == 0L) C.BUFFER_FLAG_KEY_FRAME else 0
                val sampleInfo = BufferInfo(ptsUs, frameBytes.size, sampleFlags)
                activeMuxer.writeSampleData(videoTrackIdx, ByteBuffer.wrap(frameBytes), sampleInfo)
                videoFrames++
            }

            fun handleCompletedAccessUnits(aus: List<VideoAccessUnit>, forceFlush: Boolean = false) {
                for (au in aus) {
                    if (!forceFlush) {
                        rotateSegmentIfDue(au)
                    }
                    if (!hasSeenKeyframe) {
                        if (!au.isKeyframe && preKeyframeAus < 90L) {
                            preKeyframeAus++
                            updateCameraState(
                                cameraId,
                                RecordingSessionState.Recording(
                                    cameraId = cameraId,
                                    fileName = currentSegment.fileName,
                                    durationMs = System.currentTimeMillis() - recordWallStart,
                                    bytesWritten = currentSegment.outputFile.length(),
                                    videoFrames = videoFrames,
                                    audioPackets = audioPackets,
                                    hasAudio = audioTrack != null,
                                    audioCodec = audioTrack?.codec.orEmpty(),
                                    resolution = resolutionLabel,
                                    waitingForKeyframe = true
                                )
                            )
                            continue
                        }
                        hasSeenKeyframe = true
                    }

                    if (!muxerStarted) {
                        if (au.isKeyframe && preMuxerAuBuffer.isNotEmpty()) {
                            // Keep buffer tight starting at the most recent keyframe until CSD is locked
                            preMuxerAuBuffer.clear()
                        }
                        preMuxerAuBuffer.addLast(au)

                        val shouldStartNow = hasInBandCsdComplete() ||
                            (hasAnyCsdComplete() && (preMuxerAuBuffer.size >= 60 || forceFlush || segmentIndex > 1))

                        if (shouldStartNow && startMuxerWithCurrentCsd()) {
                            while (preMuxerAuBuffer.isNotEmpty()) {
                                writeAccessUnitToMuxer(preMuxerAuBuffer.removeFirst())
                            }
                        } else {
                            updateCameraState(
                                cameraId,
                                RecordingSessionState.Recording(
                                    cameraId = cameraId,
                                    fileName = currentSegment.fileName,
                                    durationMs = System.currentTimeMillis() - recordWallStart,
                                    bytesWritten = currentSegment.outputFile.length(),
                                    videoFrames = videoFrames,
                                    audioPackets = audioPackets,
                                    hasAudio = audioTrack != null,
                                    audioCodec = audioTrack?.codec.orEmpty(),
                                    resolution = resolutionLabel,
                                    waitingForKeyframe = true
                                )
                            )
                            continue
                        }
                    } else {
                        writeAccessUnitToMuxer(au)
                    }

                    updateCameraState(
                        cameraId,
                        RecordingSessionState.Recording(
                            cameraId = cameraId,
                            fileName = currentSegment.fileName,
                            durationMs = (lastVideoPtsUs.coerceAtLeast(0L) / 1000L),
                            bytesWritten = currentSegment.outputFile.length(),
                            videoFrames = videoFrames,
                            audioPackets = audioPackets,
                            hasAudio = audioTrackIdx >= 0,
                            audioCodec = audioTrack?.codec.orEmpty(),
                            resolution = resolutionLabel,
                            waitingForKeyframe = false
                        )
                    )
                }

                if (forceFlush && !muxerStarted && preMuxerAuBuffer.isNotEmpty() && hasAnyCsdComplete()) {
                    if (startMuxerWithCurrentCsd()) {
                        while (preMuxerAuBuffer.isNotEmpty()) {
                            writeAccessUnitToMuxer(preMuxerAuBuffer.removeFirst())
                        }
                    }
                }
            }

            val udpPacketQueue = if (!usingTcp) java.util.concurrent.LinkedBlockingQueue<InterleavedPacket>(2048) else null
            if (!usingTcp && udpPacketQueue != null && udpVideoSocket != null) {
                val vSock = udpVideoSocket!!
                udpVideoThread = Thread {
                    val buf = ByteArray(65536)
                    val pkt = java.net.DatagramPacket(buf, buf.size)
                    while (!stopRequested.get() && !vSock.isClosed) {
                        try {
                            vSock.receive(pkt)
                            udpPacketQueue.offer(InterleavedPacket(videoChannel, pkt.data.copyOfRange(0, pkt.length)))
                        } catch (_: SocketTimeoutException) {
                        } catch (_: Exception) {
                            break
                        }
                    }
                }.apply { isDaemon = true; start() }

                val aSock = udpAudioSocket
                if (aSock != null && audioChannel >= 0) {
                    udpAudioThread = Thread {
                        val buf = ByteArray(4096)
                        val pkt = java.net.DatagramPacket(buf, buf.size)
                        while (!stopRequested.get() && !aSock.isClosed) {
                            try {
                                aSock.receive(pkt)
                                udpPacketQueue.offer(InterleavedPacket(audioChannel, pkt.data.copyOfRange(0, pkt.length)))
                            } catch (_: SocketTimeoutException) {
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }.apply { isDaemon = true; start() }
                }
            }

            updateCameraState(
                cameraId,
                RecordingSessionState.Recording(
                    cameraId = cameraId,
                    fileName = currentSegment.fileName,
                    durationMs = 0L,
                    bytesWritten = 0L,
                    videoFrames = 0L,
                    audioPackets = 0L,
                    hasAudio = audioTrack != null,
                    audioCodec = audioTrack?.codec.orEmpty(),
                    resolution = resolutionLabel,
                    waitingForKeyframe = true
                )
            )

            while (!stopRequested.get()) {
                val now = System.currentTimeMillis()
                if (now - lastKeepaliveAt > 25_000L) {
                    lastKeepaliveAt = now
                    runCatching { rtspClient.sendKeepalive(contentBase) }
                }

                val packet = if (usingTcp) {
                    if (prePlayInterleaved.isNotEmpty()) {
                        prePlayInterleaved.removeFirst()
                    } else {
                        try {
                            readInterleavedPacket(input)
                        } catch (_: SocketTimeoutException) {
                            continue
                        } catch (_: EOFException) {
                            break
                        } ?: continue
                    }
                } else {
                    udpPacketQueue?.poll(500L, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                }

                if (packet.channel == videoChannel) {
                    val rtp = parseRtpPacket(packet.payload) ?: continue
                    val nalUnits = if (isHevc) {
                        hevcDepacketizer.feed(rtp.payload, rtp.marker, rtp.timestamp)
                    } else {
                        h264Depacketizer.feed(rtp.payload, rtp.marker, rtp.timestamp)
                    }
                    if (nalUnits.isEmpty()) continue

                    val completedAus = auAssembler.feed(rtp.timestamp, rtp.marker, nalUnits)
                    if (completedAus.isNotEmpty()) {
                        handleCompletedAccessUnits(completedAus)
                    }
                } else if (packet.channel == audioChannel && audioTrack != null) {
                    val rtp = parseRtpPacket(packet.payload) ?: continue
                    if (!muxerStarted || audioTrackIdx < 0 || !hasSeenKeyframe) {
                        continue
                    }

                    val audioClock = audioTrack.clockRate.coerceAtLeast(8000).toLong()
                    var audioPtsUs = if (lastAudioRtpTs == null || lastAudioPtsUs < 0L) {
                        0L
                    } else {
                        val stepTicks = (rtp.timestamp - lastAudioRtpTs!!) and 0xFFFFFFFFL
                        val stepUs = (stepTicks * 1_000_000L) / audioClock
                        if (stepTicks in 1L..(audioClock * 2L)) {
                            lastAudioPtsUs + stepUs.coerceAtLeast(1_000L)
                        } else {
                            lastAudioPtsUs + 20_000L
                        }
                    }
                    lastAudioRtpTs = rtp.timestamp

                    if (isG711 && aacEncoder != null) {
                        val encodedFrames = aacEncoder.encodeG711(rtp.payload, audioPtsUs)
                        for (enc in encodedFrames) {
                            val safePts = if (lastAudioPtsUs >= 0L && enc.ptsUs <= lastAudioPtsUs) {
                                lastAudioPtsUs + 20_000L
                            } else enc.ptsUs.coerceAtLeast(0L)
                            lastAudioPtsUs = safePts
                            muxer!!.writeSampleData(
                                audioTrackIdx,
                                ByteBuffer.wrap(enc.data),
                                BufferInfo(safePts, enc.data.size, 0)
                            )
                            audioPackets++
                        }
                    } else {
                        val aus = aacDepacketizer.extractAus(rtp.payload)
                        for (au in aus) {
                            if (lastAudioPtsUs >= 0L && audioPtsUs <= lastAudioPtsUs) {
                                audioPtsUs = lastAudioPtsUs + 21_333L
                            }
                            lastAudioPtsUs = audioPtsUs.coerceAtLeast(0L)
                            muxer!!.writeSampleData(
                                audioTrackIdx,
                                ByteBuffer.wrap(au),
                                BufferInfo(lastAudioPtsUs, au.size, 0)
                            )
                            audioPackets++
                        }
                    }
                }
            }

            // Flush any final partial Access Unit or buffered startup frames on stop
            val tailAus = listOfNotNull(auAssembler.flushPending())
            handleCompletedAccessUnits(tailAus, forceFlush = true)

            runCatching { rtspClient.request("TEARDOWN", contentBase) }

            return currentSegment to RecordStats(
                durationMs = (lastVideoPtsUs.coerceAtLeast(0L) / 1000L).coerceAtLeast(System.currentTimeMillis() - recordWallStart),
                hasAudio = audioTrackIdx >= 0 && audioPackets > 0,
                audioCodec = if (audioTrackIdx >= 0) {
                    if (isG711) "${audioTrack?.codec} -> AAC" else audioTrack?.codec.orEmpty().ifBlank { "AAC" }
                } else "",
                resolution = resolutionLabel,
                videoFrames = videoFrames
            )
        } finally {
            runCatching { udpVideoThread?.interrupt() }
            runCatching { udpAudioThread?.interrupt() }
            runCatching { udpVideoSocket?.close() }
            runCatching { udpAudioSocket?.close() }
            runCatching { aacEncoder?.release() }
            runCatching { muxer?.close() }
            runCatching { outputFos?.close() }
            runCatching { socket.close() }
        }
    }

    @OptIn(UnstableApi::class)
    internal fun createMp4Muxer(fos: FileOutputStream): Mp4Muxer {
        return Mp4Muxer.Builder(SeekableMuxerOutput.of(fos))
            .setAnnexBToAvccConverter(AnnexBToAvccConverter { buffer -> buffer })
            .setAttemptStreamableOutputEnabled(false)
            .setSampleBatchingEnabled(true)
            .setSampleCopyingEnabled(true)
            .build()
    }

    internal fun buildVideoTrackFormat(
        isHevc: Boolean,
        width: Int,
        height: Int,
        vpsWithStartCode: ByteArray?,
        spsWithStartCode: ByteArray,
        ppsWithStartCode: ByteArray
    ): Format {
        return if (isHevc) {
            val combined = ByteArrayOutputStream().apply {
                vpsWithStartCode?.let { write(it) }
                write(spsWithStartCode)
                write(ppsWithStartCode)
            }.toByteArray()
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(width)
                .setHeight(height)
                .setInitializationData(listOf(combined))
                .build()
        } else {
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(width)
                .setHeight(height)
                .setInitializationData(listOf(spsWithStartCode, ppsWithStartCode))
                .build()
        }
    }

    internal fun buildAacTrackFormat(
        sampleRate: Int,
        channelCount: Int,
        audioSpecificConfig: ByteArray
    ): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setSampleRate(sampleRate)
            .setChannelCount(channelCount)
            .setInitializationData(listOf(audioSpecificConfig))
            .build()
    }

    internal fun encodeAvccSample(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            if (nal.isEmpty()) continue
            val len = nal.size
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun extractThumbnail(videoFile: File, thumbFile: File, durationMs: Long): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val probeTimesUs = listOf(
                0L,
                500_000L,
                1_500_000L,
                3_000_000L,
                (durationMs * 500L).coerceAtLeast(100_000L)
            ).distinct()
            var chosen: Bitmap? = null
            for (tsUs in probeTimesUs) {
                val candidate = retriever.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue
                if (!isLikelySolidGreenOrBlank(candidate)) {
                    chosen?.recycle()
                    chosen = candidate
                    break
                }
                if (chosen == null) chosen = candidate else candidate.recycle()
            }
            if (chosen == null) {
                chosen = retriever.frameAtTime
            }
            if (chosen != null) {
                FileOutputStream(thumbFile).use { out ->
                    chosen.compress(Bitmap.CompressFormat.JPEG, 82, out)
                }
                chosen.recycle()
                thumbFile.absolutePath
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w("RtspRecorder", "Thumbnail extraction skipped: ${e.message}")
            ""
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isLikelySolidGreenOrBlank(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return true
        val samplePixels = listOf(
            bmp.getPixel(w / 4, h / 4),
            bmp.getPixel(w / 2, h / 2),
            bmp.getPixel((w * 3) / 4, (h * 3) / 4),
            bmp.getPixel(w / 4, (h * 3) / 4),
            bmp.getPixel((w * 3) / 4, h / 4)
        )
        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0
        for (px in samplePixels) {
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (b < minB) minB = b; if (b > maxB) maxB = b
        }
        return (maxR - minR) < 14 && (maxG - minG) < 14 && (maxB - minB) < 14
    }

    private fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(4 + nal.size)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    private fun buildDefaultAacAsc(sampleRate: Int, channels: Int): ByteArray {
        val freqIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 8
        }
        val objectType = 2
        val b0 = ((objectType shl 3) or (freqIndex shr 1)).toByte()
        val b1 = (((freqIndex and 1) shl 7) or (channels shl 3)).toByte()
        return byteArrayOf(b0, b1)
    }

    private fun extractUserPass(uri: URI): Pair<String, String> {
        val info = uri.rawUserInfo ?: return "" to ""
        val parts = info.split(":", limit = 2)
        val user = runCatching { URLDecoder.decode(parts.getOrElse(0) { "" }, "UTF-8") }.getOrDefault(parts.getOrElse(0) { "" })
        val pass = runCatching { URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8") }.getOrDefault(parts.getOrElse(1) { "" })
        return user to pass
    }

    private fun stripCredentials(uri: URI): String {
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val pathPart = uri.rawPath.orEmpty()
        val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "${uri.scheme}://${uri.host}$portPart$pathPart$queryPart"
    }

    // --- RTSP Control Session ---

    private data class RtspResponse(val statusCode: Int, val headers: Map<String, String>, val body: String)

    private class RtspControlSession(
        private val input: BufferedInputStream,
        private val output: OutputStream,
        private val username: String,
        private val password: String,
        private val capturedInterleaved: ArrayDeque<InterleavedPacket>
    ) {
        private var cseq = 1
        private var sessionId: String? = null
        private var digestRealm: String? = null
        private var digestNonce: String? = null
        private var useBasicAuth = false

        fun request(method: String, url: String, extraHeaders: Map<String, String> = emptyMap()): RtspResponse {
            val first = sendOnce(method, url, extraHeaders)
            if (first.statusCode == 401 && username.isNotBlank()) {
                val wwwAuth = first.headers["www-authenticate"].orEmpty()
                if (wwwAuth.contains("Digest", ignoreCase = true)) {
                    digestRealm = Regex("realm=\"([^\"]+)\"").find(wwwAuth)?.groupValues?.get(1)
                    digestNonce = Regex("nonce=\"([^\"]+)\"").find(wwwAuth)?.groupValues?.get(1)
                    return sendOnce(method, url, extraHeaders)
                } else if (wwwAuth.contains("Basic", ignoreCase = true)) {
                    useBasicAuth = true
                    return sendOnce(method, url, extraHeaders)
                }
            }
            return first
        }

        fun sendKeepalive(url: String) {
            val headers = buildString {
                append("GET_PARAMETER $url RTSP/1.0\r\n")
                append("CSeq: ${cseq++}\r\n")
                sessionId?.let { append("Session: $it\r\n") }
                buildAuthHeader("GET_PARAMETER", url)?.let { append("Authorization: $it\r\n") }
                append("User-Agent: CamVault/1.0\r\n\r\n")
            }
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        private fun sendOnce(method: String, url: String, extraHeaders: Map<String, String>): RtspResponse {
            val sb = StringBuilder()
            sb.append("$method $url RTSP/1.0\r\nCSeq: ${cseq++}\r\n")
            sessionId?.let { sb.append("Session: $it\r\n") }
            buildAuthHeader(method, url)?.let { sb.append("Authorization: $it\r\n") }
            sb.append("User-Agent: CamVault/1.0\r\n")
            extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")

            output.write(sb.toString().toByteArray(Charsets.UTF_8))
            output.flush()

            var statusLine = readRtspLineCaptureInterleaved(input, capturedInterleaved)
            while (statusLine.isBlank()) statusLine = readRtspLineCaptureInterleaved(input, capturedInterleaved)
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
            val hdrs = linkedMapOf<String, String>()
            while (true) {
                val line = readLineCrLf(input)
                if (line.isEmpty()) break
                val c = line.indexOf(':')
                if (c > 0) hdrs[line.substring(0, c).trim().lowercase(Locale.US)] = line.substring(c + 1).trim()
            }
            hdrs["session"]?.let { sessionId = it.substringBefore(';').trim() }
            val len = hdrs["content-length"]?.toIntOrNull() ?: 0
            val body = if (len > 0) {
                val bytes = ByteArray(len)
                readFully(input, bytes, 0, len)
                String(bytes, Charsets.UTF_8)
            } else ""
            return RtspResponse(statusCode, hdrs, body)
        }

        private fun buildAuthHeader(method: String, url: String): String? {
            if (username.isBlank()) return null
            val realm = digestRealm; val nonce = digestNonce
            if (!realm.isNullOrBlank() && !nonce.isNullOrBlank()) {
                val ha1 = md5("$username:$realm:$password")
                val ha2 = md5("$method:$url")
                val resp = md5("$ha1:$nonce:$ha2")
                return "Digest username=\"$userNormalized\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$url\", response=\"$resp\""
            }
            if (useBasicAuth) {
                val token = Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                return "Basic $token"
            }
            return null
        }

        private val userNormalized: String get() = username

        private fun md5(input: String): String =
            MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // --- SDP & RTP Parsing ---

    internal data class SdpVideoTrack(
        val controlUrl: String,
        val codec: String,
        val clockRate: Int,
        val width: Int,
        val height: Int,
        val vps: ByteArray?,
        val sps: ByteArray?,
        val pps: ByteArray?
    )

    internal data class SdpAudioTrack(
        val controlUrl: String,
        val codec: String,
        val clockRate: Int,
        val channels: Int,
        val aacConfig: ByteArray?
    )

    internal data class SdpInfo(
        val videoTrack: SdpVideoTrack?,
        val audioTrack: SdpAudioTrack?
    )

    private fun parseInterleavedChannel(transportHeader: String?, defaultChannel: Int): Int {
        if (transportHeader.isNullOrBlank()) return defaultChannel
        return Regex("interleaved=(\\d+)")
            .find(transportHeader)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: defaultChannel
    }

    internal fun parseSdp(sdp: String, contentBase: String): SdpInfo {
        var currentSection = ""
        var videoPt = ""
        var videoControl = ""
        var videoCodec = ""
        var videoClock = 90000
        var videoW = 0
        var videoH = 0
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        var audioPt = ""
        var audioControl = ""
        var audioCodec = ""
        var audioClock = 8000
        var audioChannels = 1
        var aacConfig: ByteArray? = null

        sdp.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("m=video") -> {
                    currentSection = "video"
                    videoPt = line.split(Regex("\\s+")).getOrNull(3).orEmpty()
                }
                line.startsWith("m=audio") -> {
                    currentSection = "audio"
                    audioPt = line.split(Regex("\\s+")).getOrNull(3).orEmpty()
                }
                line.startsWith("m=") -> currentSection = "other"
                line.startsWith("a=control:") -> {
                    val ctrl = line.removePrefix("a=control:").trim()
                    if (currentSection == "video") videoControl = resolveControlUrl(contentBase, ctrl)
                    if (currentSection == "audio") audioControl = resolveControlUrl(contentBase, ctrl)
                }
                line.startsWith("a=rtpmap:") -> {
                    val after = line.removePrefix("a=rtpmap:").trim()
                    val pt = after.substringBefore(" ")
                    val parts = after.substringAfter(" ", "").split("/")
                    val codec = parts.getOrNull(0).orEmpty()
                    val clock = parts.getOrNull(1)?.toIntOrNull()
                    val chans = parts.getOrNull(2)?.toIntOrNull()
                    if (currentSection == "video" && codec.isNotBlank() && (videoCodec.isBlank() || pt == videoPt || videoPt.isBlank())) {
                        videoCodec = codec
                        videoClock = clock ?: 90000
                    } else if (currentSection == "audio" && (audioCodec.isBlank() || pt == audioPt || audioPt.isBlank())) {
                        audioCodec = codec
                        audioClock = clock ?: if (codec.equals("PCMU", true) || codec.equals("PCMA", true)) 8000 else 16000
                        audioChannels = chans ?: 1
                    }
                }
                line.startsWith("a=framesize:") && currentSection == "video" -> {
                    val dim = line.substringAfter(" ").trim().split("-")
                    if (dim.size == 2) {
                        videoW = dim[0].toIntOrNull() ?: videoW
                        videoH = dim[1].toIntOrNull() ?: videoH
                    }
                }
                line.startsWith("a=fmtp:") -> {
                    if (currentSection == "video") {
                        Regex("sprop-parameter-sets=([^;\\s\"]+)").find(line)?.groupValues?.get(1)?.let { sprop ->
                            val parts = sprop.split(",")
                            parts.getOrNull(0)?.trim()?.let { b64 ->
                                runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { sps = withStartCode(it) }
                            }
                            parts.getOrNull(1)?.trim()?.let { b64 ->
                                runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { pps = withStartCode(it) }
                            }
                        }
                        Regex("sprop-vps=([^;\\s\"]+)").find(line)?.groupValues?.get(1)?.let { b64 ->
                            runCatching { Base64.decode(b64.trim(), Base64.DEFAULT) }.getOrNull()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { vps = withStartCode(it) }
                        }
                        Regex("sprop-sps=([^;\\s\"]+)").find(line)?.groupValues?.get(1)?.let { b64 ->
                            runCatching { Base64.decode(b64.trim(), Base64.DEFAULT) }.getOrNull()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { sps = withStartCode(it) }
                        }
                        Regex("sprop-pps=([^;\\s\"]+)").find(line)?.groupValues?.get(1)?.let { b64 ->
                            runCatching { Base64.decode(b64.trim(), Base64.DEFAULT) }.getOrNull()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { pps = withStartCode(it) }
                        }
                    } else if (currentSection == "audio") {
                        val cfg = Regex("config=([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)
                        if (cfg != null && cfg.length >= 2) aacConfig = hexToBytes(cfg)
                    }
                }
            }
        }

        val finalCodec = videoCodec.ifBlank { "H264" }
        if ((videoW <= 0 || videoH <= 0) && sps != null && !finalCodec.equals("H265", true) && !finalCodec.equals("HEVC", true)) {
            RtspProbe.parseH264SpsDimensions(sps!!)?.let {
                videoW = it.first
                videoH = it.second
            }
        }

        val video = if (videoControl.isNotBlank()) SdpVideoTrack(videoControl, finalCodec, videoClock, videoW, videoH, vps, sps, pps) else null
        val audio = if (audioControl.isNotBlank() && audioCodec.isNotBlank()) SdpAudioTrack(audioControl, audioCodec, audioClock, audioChannels, aacConfig) else null
        return SdpInfo(video, audio)
    }

    private fun resolveControlUrl(contentBase: String, control: String): String {
        if (control.startsWith("rtsp://", true) || control.startsWith("rtsps://", true)) return control
        if (control == "*") return contentBase
        return "${contentBase.trimEnd('/')}/${control.trimStart('/')}"
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private data class InterleavedPacket(val channel: Int, val payload: ByteArray)
    internal data class RtpPacket(val marker: Boolean, val timestamp: Long, val payload: ByteArray)

    private fun readInterleavedPacket(input: BufferedInputStream): InterleavedPacket? {
        while (true) {
            val first = input.read()
            if (first == -1) throw EOFException("RTSP stream closed")
            if (first == 0x24) {
                val channel = readByteResilient(input)
                val lenHi = readByteResilient(input)
                val lenLo = readByteResilient(input)
                val length = (lenHi shl 8) or lenLo
                val payload = ByteArray(length)
                readFully(input, payload, 0, length)
                return InterleavedPacket(channel, payload)
            } else if (first == 'R'.code) {
                readLineCrLf(input)
                var contentLength = 0
                while (true) {
                    val hdr = readLineCrLf(input)
                    if (hdr.isEmpty()) break
                    val colon = hdr.indexOf(':')
                    if (colon > 0 && hdr.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                        contentLength = hdr.substring(colon + 1).trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength > 0) {
                    readFully(input, ByteArray(contentLength), 0, contentLength)
                }
            }
        }
    }

    internal fun parseRtpPacket(data: ByteArray): RtpPacket? {
        if (data.size < 12) return null
        val b0 = data[0].toInt() and 0xFF
        if (((b0 ushr 6) and 0x03) != 2) return null
        val pt = data[1].toInt() and 0x7F
        // Ignore RTCP control packets (SR=200, RR=201, SDES=202, BYE=203, APP=204 -> &0x7F = 72..76)
        if (pt in 72..76) return null
        val hasPadding = (b0 and 0x20) != 0
        val hasExtension = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F
        val marker = ((data[1].toInt() and 0xFF) and 0x80) != 0
        val ts = ((data[4].toLong() and 0xFF) shl 24) or
            ((data[5].toLong() and 0xFF) shl 16) or
            ((data[6].toLong() and 0xFF) shl 8) or
            (data[7].toLong() and 0xFF)

        var offset = 12 + (csrcCount * 4)
        if (hasExtension) {
            if (offset + 4 > data.size) return null
            val extLenWords = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4 + (extLenWords * 4)
        }
        var end = data.size
        if (hasPadding && end > offset) end = (end - (data[end - 1].toInt() and 0xFF)).coerceAtLeast(offset)
        if (offCheckInvalid(offset, end)) return null
        return RtpPacket(marker, ts, data.copyOfRange(offset, end))
    }

    private fun offCheckInvalid(offset: Int, end: Int): Boolean = offset >= end

    internal class H264RtpDepacketizer {
        private var fuBuffer: ByteArrayOutputStream? = null

        private fun flushIncompleteFu(): List<ByteArray> {
            val buf = fuBuffer ?: return emptyList()
            fuBuffer = null
            return if (buf.size() > 1) splitAnnexBNalsStatic(buf.toByteArray()) else emptyList()
        }

        fun feed(raw: ByteArray, marker: Boolean, @Suppress("UNUSED_PARAMETER") rtpTimestamp: Long = 0L): List<ByteArray> {
            val payload = stripAnnexBPrefixStatic(raw)
            if (payload.isEmpty()) return emptyList()
            val header = payload[0].toInt() and 0xFF
            val nalType = header and 0x1F

            return when {
                nalType in 1..23 -> {
                    val prior = flushIncompleteFu()
                    val current = splitAnnexBNalsStatic(payload)
                    if (prior.isEmpty()) current else prior + current
                }
                nalType == 24 -> {
                    val prior = flushIncompleteFu()
                    val nals = mutableListOf<ByteArray>()
                    nals.addAll(prior)
                    var idx = 1
                    while (idx + 2 <= payload.size) {
                        val size = ((payload[idx].toInt() and 0xFF) shl 8) or (payload[idx + 1].toInt() and 0xFF)
                        idx += 2
                        if (size > 0 && idx + size <= payload.size) nals.add(payload.copyOfRange(idx, idx + size))
                        idx += size
                    }
                    nals
                }
                nalType == 28 -> {
                    if (payload.size < 2) return emptyList()
                    val fuHeader = payload[1].toInt() and 0xFF
                    val isStart = (fuHeader and 0x80) != 0
                    val isEnd = (fuHeader and 0x40) != 0
                    val origNalHeader = (header and 0xE0) or (fuHeader and 0x1F)
                    if (isStart) {
                        val prior = flushIncompleteFu()
                        val buf = ByteArrayOutputStream().apply {
                            write(origNalHeader)
                            write(payload, 2, payload.size - 2)
                        }
                        if (isEnd || marker) {
                            val current = splitAnnexBNalsStatic(buf.toByteArray())
                            if (prior.isEmpty()) current else prior + current
                        } else {
                            fuBuffer = buf
                            prior
                        }
                    } else {
                        val buf = fuBuffer ?: return emptyList()
                        buf.write(payload, 2, payload.size - 2)
                        if (isEnd || marker) {
                            fuBuffer = null
                            splitAnnexBNalsStatic(buf.toByteArray())
                        } else emptyList()
                    }
                }
                else -> emptyList()
            }
        }
    }

    internal class HevcRtpDepacketizer {
        private var fuBuffer: ByteArrayOutputStream? = null

        private fun flushIncompleteFu(): List<ByteArray> {
            val buf = fuBuffer ?: return emptyList()
            fuBuffer = null
            return if (buf.size() > 2) splitAnnexBNalsStatic(buf.toByteArray()) else emptyList()
        }

        fun feed(raw: ByteArray, marker: Boolean, @Suppress("UNUSED_PARAMETER") rtpTimestamp: Long = 0L): List<ByteArray> {
            val payload = stripAnnexBPrefixStatic(raw)
            if (payload.size < 2) return emptyList()
            val nalType = (payload[0].toInt() shr 1) and 0x3F
            return when {
                nalType < 48 -> {
                    val prior = flushIncompleteFu()
                    val current = splitAnnexBNalsStatic(payload)
                    if (prior.isEmpty()) current else prior + current
                }
                nalType == 48 -> {
                    val prior = flushIncompleteFu()
                    val nals = mutableListOf<ByteArray>()
                    nals.addAll(prior)
                    var idx = 2
                    while (idx + 2 <= payload.size) {
                        val size = ((payload[idx].toInt() and 0xFF) shl 8) or (payload[idx + 1].toInt() and 0xFF)
                        idx += 2
                        if (size > 0 && idx + size <= payload.size) nals.add(payload.copyOfRange(idx, idx + size))
                        idx += size
                    }
                    nals
                }
                nalType == 49 -> {
                    if (payload.size < 3) return emptyList()
                    val fuHdr = payload[2].toInt() and 0xFF
                    val start = (fuHdr and 0x80) != 0
                    val end = (fuHdr and 0x40) != 0
                    val fuType = fuHdr and 0x3F
                    if (start) {
                        val prior = flushIncompleteFu()
                        val b0 = ((payload[0].toInt() and 0x81) or (fuType shl 1)).toByte()
                        val b1 = payload[1]
                        val buf = ByteArrayOutputStream().apply {
                            write(b0.toInt())
                            write(b1.toInt())
                            write(payload, 3, payload.size - 3)
                        }
                        if (end || marker) {
                            val current = splitAnnexBNalsStatic(buf.toByteArray())
                            if (prior.isEmpty()) current else prior + current
                        } else {
                            fuBuffer = buf
                            prior
                        }
                    } else {
                        val buf = fuBuffer ?: return emptyList()
                        buf.write(payload, 3, payload.size - 3)
                        if (end || marker) {
                            fuBuffer = null
                            splitAnnexBNalsStatic(buf.toByteArray())
                        } else emptyList()
                    }
                }
                else -> emptyList()
            }
        }
    }

    private fun stripAnnexBPrefix(p: ByteArray): ByteArray {
        return when {
            p.size >= 4 && p[0].toInt() == 0 && p[1].toInt() == 0 && p[2].toInt() == 0 && p[3].toInt() == 1 ->
                p.copyOfRange(4, p.size)
            p.size >= 3 && p[0].toInt() == 0 && p[1].toInt() == 0 && p[2].toInt() == 1 ->
                p.copyOfRange(3, p.size)
            else -> p
        }
    }

    private fun splitAnnexBNals(p: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= p.size) {
            if (p[i].toInt() == 0 && p[i + 1].toInt() == 0) {
                if (i + 3 < p.size && p[i + 2].toInt() == 0 && p[i + 3].toInt() == 1) {
                    starts.add(i); i += 4; continue
                } else if (p[i + 2].toInt() == 1) {
                    starts.add(i); i += 3; continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return listOf(p)
        val out = mutableListOf<ByteArray>()
        if (starts[0] > 0) out.add(p.copyOfRange(0, starts[0]))
        for (idx in starts.indices) {
            val scStart = starts[idx]
            val scLen = if (scStart + 3 < p.size && p[scStart + 2].toInt() == 0) 4 else 3
            val nalStart = scStart + scLen
            val nalEnd = if (idx + 1 < starts.size) starts[idx + 1] else p.size
            if (nalStart < nalEnd) out.add(p.copyOfRange(nalStart, nalEnd))
        }
        return out
    }

    private class AacRtpDepacketizer {
        fun extractAus(payload: ByteArray): List<ByteArray> {
            if (payload.size < 4) return emptyList()
            val auHeadersLenBits = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val auHeadersBytes = (auHeadersLenBits + 7) / 8
            var offset = 2 + auHeadersBytes
            if (offset > payload.size) return emptyList()

            val auCount = auHeadersLenBits / 16
            val result = mutableListOf<ByteArray>()
            for (i in 0 until auCount) {
                val hdrPos = 2 + (i * 2)
                if (hdrPos + 2 > payload.size) break
                val auHeader = ((payload[hdrPos].toInt() and 0xFF) shl 8) or (payload[hdrPos + 1].toInt() and 0xFF)
                val auSize = auHeader ushr 3
                if (auSize > 0 && offset + auSize <= payload.size) {
                    result.add(payload.copyOfRange(offset, offset + auSize))
                    offset += auSize
                }
            }
            return result
        }
    }

    private class G711ToAacEncoder(
        sampleRate: Int,
        channelCount: Int,
        private val isAlaw: Boolean
    ) {
        data class EncodedChunk(val data: ByteArray, val ptsUs: Long)

        private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var outputFormat: MediaFormat? = null
            private set

        init {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun primeFormat() {
            val silence = ByteArray(2048)
            for (attempt in 0 until 6) {
                if (outputFormat != null) break
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inIndex)
                    inputBuf?.clear()
                    inputBuf?.put(silence)
                    codec.queueInputBuffer(inIndex, 0, silence.size, 0L, 0)
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                        outIndex >= 0 -> codec.releaseOutputBuffer(outIndex, false)
                        else -> break
                    }
                }
            }
        }

        fun encodeG711(g711: ByteArray, ptsUs: Long): List<EncodedChunk> {
            val pcmBytes = ByteArray(g711.size * 2)
            for (i in g711.indices) {
                val sample = if (isAlaw) alawToLinear(g711[i]) else ulawToLinear(g711[i])
                pcmBytes[i * 2] = (sample.toInt() and 0xFF).toByte()
                pcmBytes[i * 2 + 1] = ((sample.toInt() ushr 8) and 0xFF).toByte()
            }

            val inIndex = codec.dequeueInputBuffer(5000)
            if (inIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inIndex)
                if (inputBuf != null && inputBuf.capacity() >= pcmBytes.size) {
                    inputBuf.clear()
                    inputBuf.put(pcmBytes)
                    codec.queueInputBuffer(inIndex, 0, pcmBytes.size, ptsUs, 0)
                } else {
                    codec.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                }
            }

            val out = mutableListOf<EncodedChunk>()
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val chunk = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(chunk)
                            out.add(EncodedChunk(chunk, info.presentationTimeUs))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    else -> break
                }
            }
            return out
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        private fun ulawToLinear(uValByte: Byte): Short {
            val uVal = (uValByte.toInt() and 0xFF).inv()
            val sign = uVal and 0x80
            val exponent = (uVal ushr 4) and 0x07
            val mantissa = (((uVal and 0x0F) shl 3) + 0x84) shl exponent
            val sample = mantissa - 0x84
            return (if (sign != 0) -sample else sample).toShort()
        }

        private fun alawToLinear(aValByte: Byte): Short {
            val aVal = (aValByte.toInt() and 0xFF) xor 0x55
            var t = (aVal and 0x0F) shl 4
            val seg = (aVal and 0x70) ushr 4
            when (seg) {
                0 -> t += 8
                1 -> t += 0x108
                else -> {
                    t += 0x108
                    t = t shl (seg - 1)
                }
            }
            return (if ((aVal and 0x80) != 0) t else -t).toShort()
        }
    }

    companion object {
        internal fun stripAnnexBPrefixStatic(p: ByteArray): ByteArray {
            return when {
                p.size >= 4 && p[0].toInt() == 0 && p[1].toInt() == 0 && p[2].toInt() == 0 && p[3].toInt() == 1 ->
                    p.copyOfRange(4, p.size)
                p.size >= 3 && p[0].toInt() == 0 && p[1].toInt() == 0 && p[2].toInt() == 1 ->
                    p.copyOfRange(3, p.size)
                else -> p
            }
        }

        internal fun splitAnnexBNalsStatic(p: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Int>()
            var i = 0
            while (i + 3 <= p.size) {
                if (p[i].toInt() == 0 && p[i + 1].toInt() == 0) {
                    if (i + 3 < p.size && p[i + 2].toInt() == 0 && p[i + 3].toInt() == 1) {
                        starts.add(i); i += 4; continue
                    } else if (p[i + 2].toInt() == 1) {
                        starts.add(i); i += 3; continue
                    }
                }
                i++
            }
            if (starts.isEmpty()) return listOf(p)
            val out = mutableListOf<ByteArray>()
            if (starts[0] > 0) out.add(p.copyOfRange(0, starts[0]))
            for (idx in starts.indices) {
                val scStart = starts[idx]
                val scLen = if (scStart + 3 < p.size && p[scStart + 2].toInt() == 0) 4 else 3
                val nalStart = scStart + scLen
                val nalEnd = if (idx + 1 < starts.size) starts[idx + 1] else p.size
                if (nalStart < nalEnd) out.add(p.copyOfRange(nalStart, nalEnd))
            }
            return out
        }

        @OptIn(UnstableApi::class)
        internal fun parseHevcSpsDimensions(spsWithOptionalStartCode: ByteArray): Pair<Int, Int>? {
            return runCatching {
                val raw = stripAnnexBPrefixStatic(spsWithOptionalStartCode)
                if (raw.size < 4) return null
                val data = NalUnitUtil.parseH265SpsNalUnit(raw, 0, raw.size, null)
                if (data.width in 64..7680 && data.height in 64..4320) data.width to data.height else null
            }.getOrNull()
        }

        internal fun isH264IntraSlice(nal: ByteArray): Boolean {
            if (nal.size < 2 || (nal[0].toInt() and 0x1F) != 1) return false
            // Unescape up to 12 bytes of slice_header RBSP and read first_mb_in_slice & slice_type
            val rbsp = ByteArray(12)
            var len = 0
            var zeros = 0
            for (i in 1 until minOf(nal.size, 16)) {
                val b = nal[i].toInt() and 0xFF
                if (zeros == 2 && b == 3) {
                    zeros = 0
                    continue
                }
                rbsp[len++] = b.toByte()
                if (len >= rbsp.size) break
                zeros = if (b == 0) zeros + 1 else 0
            }
            if (len < 1) return false
            var bitPos = 0
            fun readBit(): Int {
                val idx = bitPos ushr 3
                if (idx >= len) return 0
                val shift = 7 - (bitPos and 7)
                bitPos++
                return (rbsp[idx].toInt() ushr shift) and 1
            }
            fun readUe(): Int {
                var lz = 0
                while (bitPos < len * 8 && readBit() == 0 && lz < 20) lz++
                var v = 0
                for (i in 0 until lz) v = (v shl 1) or readBit()
                return ((1 shl lz) - 1) + v
            }
            readUe() // first_mb_in_slice
            val sliceType = readUe()
            return sliceType == 2 || sliceType == 7 || sliceType == 4 || sliceType == 9
        }

        private fun readRtspLineCaptureInterleaved(
            input: BufferedInputStream,
            capturedInterleaved: ArrayDeque<InterleavedPacket>
        ): String {
            while (true) {
                input.mark(4)
                val b = input.read()
                if (b == -1) throw EOFException("RTSP closed")
                if (b == 0x24) {
                    val ch = readByteResilient(input)
                    val hi = readByteResilient(input)
                    val lo = readByteResilient(input)
                    val len = (hi shl 8) or lo
                    val buf = ByteArray(len)
                    readFully(input, buf, 0, len)
                    capturedInterleaved.addLast(InterleavedPacket(ch, buf))
                } else {
                    input.reset()
                    return readLineCrLf(input)
                }
            }
        }

        private fun readByteResilient(input: InputStream): Int {
            var retries = 0
            while (true) {
                try {
                    val b = input.read()
                    if (b == -1) throw EOFException("Truncated interleaved header")
                    return b
                } catch (e: SocketTimeoutException) {
                    if (++retries > 3) throw e
                }
            }
        }

        private fun readLineCrLf(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1 || b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private fun readFully(input: InputStream, dst: ByteArray, offset: Int, len: Int) {
            var remaining = len
            var cur = offset
            var timeouts = 0
            while (remaining > 0) {
                try {
                    val count = input.read(dst, cur, remaining)
                    if (count == -1) throw EOFException("Unexpected end of RTSP stream")
                    cur += count
                    remaining -= count
                    timeouts = 0
                } catch (e: SocketTimeoutException) {
                    if (++timeouts > 3) throw e
                }
            }
        }
    }
}
