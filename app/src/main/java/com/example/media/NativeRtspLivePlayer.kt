package com.example.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class NativeRtspLivePlayer {

    interface Listener {
        fun onStatusChanged(status: String, detail: String = "")
        fun onVideoDimensions(width: Int, height: Int)
        fun onError(message: String)
    }

    private var playJob: Job? = null
    private val stopFlag = AtomicBoolean(false)
    @Volatile
    private var muted: Boolean = false

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    fun start(
        scope: CoroutineScope,
        rtspUrl: String,
        surface: Surface,
        preferTcp: Boolean,
        audioEnabled: Boolean,
        listener: Listener
    ) {
        stop()
        stopFlag.set(false)

        playJob = scope.launch(Dispatchers.IO) {
            try {
                runStream(
                    rtspUrl = rtspUrl,
                    surface = surface,
                    preferTcp = preferTcp,
                    audioEnabled = audioEnabled,
                    listener = listener
                )
            } catch (e: Exception) {
                if (!stopFlag.get()) {
                    Log.e("NativeRtspPlayer", "Stream failed", e)
                    listener.onError(e.localizedMessage ?: "RTSP playback failed")
                }
            }
        }
    }

    fun stop() {
        stopFlag.set(true)
        playJob?.cancel()
        playJob = null
    }

    private fun runStream(
        rtspUrl: String,
        surface: Surface,
        preferTcp: Boolean,
        audioEnabled: Boolean,
        listener: Listener
    ) {
        val uri = URI(rtspUrl.trim())
        val host = uri.host ?: throw IllegalArgumentException("Invalid RTSP host")
        val port = if (uri.port > 0) uri.port else 554
        val (user, pass) = extractCredentials(uri)
        val cleanUrl = stripCredentials(uri)

        listener.onStatusChanged("Connecting Native RTSP...", "$host:$port")
        val socket = Socket()
        var videoCodec: MediaCodec? = null
        var audioRenderer: LiveAudioRenderer? = null
        var udpVideoSocket: DatagramSocket? = null
        var udpAudioSocket: DatagramSocket? = null
        var udpAudioJob: Job? = null

        try {
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(host, port), 7000)
            val input = BufferedInputStream(socket.getInputStream(), 65536)
            val output = socket.getOutputStream()

            val rtsp = RtspClient(input, output, user, pass)
            rtsp.request("OPTIONS", cleanUrl)
            val describe = rtsp.request("DESCRIBE", cleanUrl, mapOf("Accept" to "application/sdp"))
            if (describe.code !in 200..299) {
                throw IllegalStateException("RTSP DESCRIBE HTTP ${describe.code}")
            }

            val contentBase = describe.headers["content-base"]
                ?: describe.headers["content-location"]
                ?: cleanUrl
            val sdp = parseSdp(describe.body, contentBase)
            val vTrack = sdp.video ?: throw IllegalStateException("No video track in camera SDP")
            val aTrack = if (audioEnabled) sdp.audio else null

            var usingTcp = preferTcp
            var setupVideo = if (usingTcp) {
                rtsp.request("SETUP", vTrack.controlUrl, mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
            } else {
                udpVideoSocket = DatagramSocket()
                val rtpPort = udpVideoSocket.localPort
                rtsp.request("SETUP", vTrack.controlUrl, mapOf("Transport" to "RTP/AVP;unicast;client_port=$rtpPort-${rtpPort + 1}"))
            }

            if (setupVideo.code == 461 && usingTcp) {
                // Camera rejected TCP interleaved -> fallback to UDP unicast
                usingTcp = false
                udpVideoSocket = DatagramSocket()
                val rtpPort = udpVideoSocket.localPort
                setupVideo = rtsp.request(
                    "SETUP",
                    vTrack.controlUrl,
                    mapOf("Transport" to "RTP/AVP;unicast;client_port=$rtpPort-${rtpPort + 1}")
                )
            }
            if (setupVideo.code !in 200..299) {
                throw IllegalStateException("RTSP SETUP failed (${setupVideo.code})")
            }

            val setupVideoChannel = parseInterleavedRtpChannel(setupVideo.headers["transport"], 0)
            var audioChannel = -1

            var hasAudio = false
            if (aTrack != null) {
                val setupAudio = if (usingTcp) {
                    rtsp.request("SETUP", aTrack.controlUrl, mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=2-3"))
                } else {
                    udpAudioSocket = DatagramSocket()
                    val aPort = udpAudioSocket.localPort
                    rtsp.request("SETUP", aTrack.controlUrl, mapOf("Transport" to "RTP/AVP;unicast;client_port=$aPort-${aPort + 1}"))
                }
                if (setupAudio.code in 200..299) {
                    hasAudio = true
                    audioChannel = if (usingTcp) parseInterleavedRtpChannel(setupAudio.headers["transport"], 2) else 2
                    audioRenderer = runCatching { createAudioRenderer(aTrack) }.getOrNull()
                } else {
                    udpAudioSocket?.close()
                    udpAudioSocket = null
                }
            }

            var playResp = rtsp.request("PLAY", contentBase, mapOf("Range" to "npt=0.000-"))
            if (playResp.code !in 200..299 && contentBase != cleanUrl) {
                playResp = rtsp.request("PLAY", cleanUrl, mapOf("Range" to "npt=0.000-"))
            }
            if (playResp.code !in 200..299) {
                throw IllegalStateException("RTSP PLAY failed (${playResp.code})")
            }

            val isHevc = vTrack.codec.equals("H265", true) || vTrack.codec.equals("HEVC", true)
            val mimeType = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            var spsData: ByteArray? = vTrack.sps
            var ppsData: ByteArray? = vTrack.pps
            var vpsData: ByteArray? = vTrack.vps
            var codecConfigured = false
            var needPrependCsd = true
            var seenKeyframe = false
            var videoPackets = 0L
            var videoAccessUnits = 0L
            var audioPackets = 0L
            var frameCount = 0L
            var firstVideoRtpTs: Long? = null
            var lastVideoPtsUs = -1L
            var lastDiagMs = 0L
            var videoDecoderError = ""
            var triedSwFallback = false
            val outInfo = MediaCodec.BufferInfo()

            val h264Depacketizer = H264Depacketizer()
            val hevcDepacketizer = HevcDepacketizer()

            fun buildTelemetryDetail(extra: String = ""): String = buildString {
                append(vTrack.codec)
                if (vTrack.width > 0 && vTrack.height > 0) append(" ${vTrack.width}x${vTrack.height}")
                append(" • ${if (usingTcp) "TCP ch$setupVideoChannel" else "UDP"}")
                if (hasAudio && aTrack != null) append(" • ${aTrack.codec}(ch$audioChannel)")
                append(" • ${frameCount}f/${videoPackets}vp/${audioPackets}ap")
                if (extra.isNotBlank()) append(" • $extra")
            }

            fun configureDecoderInstance(preferSoftware: Boolean): MediaCodec? {
                val sps = spsData ?: return null
                val pps = ppsData ?: return null
                val dims = if (!isHevc) RtspProbe.parseH264SpsDimensions(sps) else null
                val w = dims?.first ?: vTrack.width.takeIf { it > 0 } ?: 1280
                val h = dims?.second ?: vTrack.height.takeIf { it > 0 } ?: 720
                val format = MediaFormat.createVideoFormat(mimeType, w, h).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(w * h, 1024 * 1024))
                    if (isHevc) {
                        val combined = ByteArrayOutputStream().apply {
                            vpsData?.let { write(it) }
                            write(sps)
                            write(pps)
                        }.toByteArray()
                        setByteBuffer("csd-0", ByteBuffer.wrap(combined))
                    } else {
                        setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                        setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                    }
                }

                val candidateNames = buildList {
                    if (preferSoftware) {
                        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
                            .sortedByDescending {
                                val n = it.name.lowercase(Locale.US)
                                n.startsWith("c2.android.") || n.startsWith("omx.google.")
                            }
                            .forEach { add(it.name) }
                    }
                }

                var created: MediaCodec? = null
                var lastErr: Exception? = null
                if (!preferSoftware) {
                    try {
                        val c = MediaCodec.createDecoderByType(mimeType)
                        c.configure(format, surface, null, 0)
                        c.start()
                        created = c
                    } catch (e: Exception) {
                        lastErr = e
                        runCatching { created?.release() }
                        created = null
                    }
                }
                if (created == null) {
                    val allCodecs = candidateNames.ifEmpty {
                        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
                            .sortedByDescending {
                                val n = it.name.lowercase(Locale.US)
                                n.startsWith("c2.android.") || n.startsWith("omx.google.")
                            }
                            .map { it.name }
                    }
                    for (name in allCodecs) {
                        var c: MediaCodec? = null
                        try {
                            c = MediaCodec.createByCodecName(name)
                            c.configure(format, surface, null, 0)
                            c.start()
                            created = c
                            break
                        } catch (e: Exception) {
                            lastErr = e
                            runCatching { c?.release() }
                        }
                    }
                }
                if (created == null) {
                    videoDecoderError = lastErr?.localizedMessage ?: "No decoder for $mimeType"
                    return null
                }
                listener.onVideoDimensions(w, h)
                return created
            }

            fun tryConfigureVideoCodec() {
                if (codecConfigured || !surface.isValid) return
                if (spsData == null || ppsData == null) return
                if (isHevc && vpsData == null && videoPackets < 45) return

                val codec = configureDecoderInstance(preferSoftware = triedSwFallback)
                if (codec != null) {
                    videoCodec = codec
                    codecConfigured = true
                    needPrependCsd = true
                    videoDecoderError = ""
                    listener.onStatusChanged(
                        if (frameCount > 0) "LIVE" else "Decoder Ready",
                        buildTelemetryDetail()
                    )
                } else {
                    listener.onStatusChanged("Video Codec Error", buildTelemetryDetail(videoDecoderError))
                }
            }

            if (spsData != null && ppsData != null && (!isHevc || vpsData != null)) {
                tryConfigureVideoCodec()
            } else {
                listener.onStatusChanged("Waiting for SPS/PPS...", buildTelemetryDetail())
            }

            if (!usingTcp && udpAudioSocket != null && audioRenderer != null) {
                val aSock = udpAudioSocket
                val aRend = audioRenderer
                aSock.soTimeout = 1000
                udpAudioJob = CoroutineScope(Dispatchers.IO).launch {
                    val buf = ByteArray(4096)
                    val pkt = DatagramPacket(buf, buf.size)
                    while (!stopFlag.get() && isActive) {
                        try {
                            aSock.receive(pkt)
                            val rtp = parseRtp(pkt.data, pkt.length) ?: continue
                            audioPackets++
                            aRend.feed(rtp.payload, muted)
                        } catch (_: SocketTimeoutException) {
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            }

            var lastKeepalive = System.currentTimeMillis()
            val udpBuf = ByteArray(65536)
            val udpPacket = DatagramPacket(udpBuf, udpBuf.size)
            udpVideoSocket?.soTimeout = 3000

            while (!stopFlag.get()) {
                val now = System.currentTimeMillis()
                if (now - lastKeepalive > 25_000L) {
                    lastKeepalive = now
                    runCatching { rtsp.keepalive(contentBase) }
                }

                val packetChannel: Int
                val packetPayload: ByteArray

                if (usingTcp) {
                    val interleaved = try {
                        readInterleaved(input)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: EOFException) {
                        break
                    } ?: continue
                    packetChannel = interleaved.first
                    packetPayload = interleaved.second
                } else {
                    val uSock = udpVideoSocket ?: break
                    try {
                        uSock.receive(udpPacket)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    packetChannel = setupVideoChannel
                    packetPayload = udpPacket.data.copyOfRange(0, udpPacket.length)
                }

                if (packetChannel == setupVideoChannel) {
                    videoPackets++
                    val rtp = parseRtp(packetPayload, packetPayload.size) ?: continue
                    val nals = if (isHevc) {
                        hevcDepacketizer.feed(rtp.payload, rtp.marker)
                    } else {
                        h264Depacketizer.feed(rtp.payload, rtp.marker)
                    }

                    if (now - lastDiagMs > 1500L && frameCount == 0L) {
                        lastDiagMs = now
                        val phase = when {
                            videoDecoderError.isNotBlank() -> videoDecoderError
                            !codecConfigured -> "Waiting SPS/PPS"
                            !seenKeyframe -> "Wait IDR"
                            else -> "Decoding"
                        }
                        listener.onStatusChanged(phase, buildTelemetryDetail())
                    }

                    if (nals.isEmpty()) continue

                    val frameBytes = ByteArrayOutputStream()
                    var isKey = false
                    var paramUpdated = false
                    for (nal in nals) {
                        if (nal.isEmpty()) continue
                        if (isHevc) {
                            val type = (nal[0].toInt() shr 1) and 0x3F
                            when (type) {
                                32 -> { vpsData = withStartCode(nal); paramUpdated = true }
                                33 -> { spsData = withStartCode(nal); paramUpdated = true }
                                34 -> { ppsData = withStartCode(nal); paramUpdated = true }
                                in 16..21 -> {
                                    isKey = true
                                    frameBytes.write(withStartCode(nal))
                                }
                                else -> frameBytes.write(withStartCode(nal))
                            }
                        } else {
                            val type = nal[0].toInt() and 0x1F
                            when (type) {
                                7 -> { spsData = withStartCode(nal); paramUpdated = true }
                                8 -> { ppsData = withStartCode(nal); paramUpdated = true }
                                5 -> {
                                    isKey = true
                                    frameBytes.write(withStartCode(nal))
                                }
                                else -> frameBytes.write(withStartCode(nal))
                            }
                        }
                    }

                    if (paramUpdated) needPrependCsd = true
                    if (!codecConfigured) {
                        tryConfigureVideoCodec()
                    }

                    val codec = videoCodec ?: continue
                    val rawFrame = frameBytes.toByteArray()
                    if (rawFrame.isEmpty()) continue
                    videoAccessUnits++

                    if (!seenKeyframe) {
                        // Allow non-IDR / GDR fallback after 45 video access units if camera never emits NAL type 5
                        if (!isKey && videoAccessUnits < 45L) continue
                        seenKeyframe = true
                        firstVideoRtpTs = rtp.timestamp
                    }

                    val data = if (needPrependCsd || isKey) {
                        needPrependCsd = false
                        ByteArrayOutputStream().apply {
                            if (isHevc) vpsData?.let { write(it) }
                            spsData?.let { write(it) }
                            ppsData?.let { write(it) }
                            write(rawFrame)
                        }.toByteArray()
                    } else {
                        rawFrame
                    }

                    val baseTs = firstVideoRtpTs ?: rtp.timestamp
                    var ptsUs = (((rtp.timestamp - baseTs) and 0xFFFFFFFFL) * 1_000_000L) / 90_000L
                    if (ptsUs <= lastVideoPtsUs) {
                        ptsUs = lastVideoPtsUs + 33_333L
                    }
                    lastVideoPtsUs = ptsUs

                    try {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)
                            if (buf != null && buf.capacity() >= data.size) {
                                buf.clear()
                                buf.put(data)
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    data.size,
                                    ptsUs,
                                    if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                                )
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                            }
                        }

                        var drainTimeout = if (frameCount == 0L) 5_000L else 0L
                        while (true) {
                            val outIdx = codec.dequeueOutputBuffer(outInfo, drainTimeout)
                            drainTimeout = 0L
                            when {
                                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val fmt = codec.outputFormat
                                    val rw = fmt.getInteger(MediaFormat.KEY_WIDTH)
                                    val rh = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                                    if (rw > 0 && rh > 0) listener.onVideoDimensions(rw, rh)
                                }
                                outIdx >= 0 -> {
                                    codec.releaseOutputBuffer(outIdx, surface.isValid)
                                    frameCount++
                                    if (frameCount == 1L || frameCount % 30L == 0L) {
                                        listener.onStatusChanged("LIVE", buildTelemetryDetail())
                                    }
                                }
                                else -> break
                            }
                        }
                    } catch (ce: Exception) {
                        videoDecoderError = ce.localizedMessage ?: "Decoder error"
                        Log.w("NativeRtspPlayer", "Video decode error: $videoDecoderError", ce)
                        runCatching { videoCodec?.stop() }
                        runCatching { videoCodec?.release() }
                        videoCodec = null
                        codecConfigured = false
                        seenKeyframe = false
                        if (!triedSwFallback) {
                            triedSwFallback = true
                            tryConfigureVideoCodec()
                        } else {
                            listener.onStatusChanged("Video Error", buildTelemetryDetail(videoDecoderError))
                        }
                    }
                } else if (hasAudio && packetChannel == audioChannel && audioRenderer != null) {
                    audioPackets++
                    val rtp = parseRtp(packetPayload, packetPayload.size) ?: continue
                    audioRenderer.feed(rtp.payload, muted)
                }
            }

            runCatching { rtsp.request("TEARDOWN", contentBase) }
        } finally {
            udpAudioJob?.cancel()
            runCatching { udpVideoSocket?.close() }
            runCatching { udpAudioSocket?.close() }
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { audioRenderer?.release() }
            runCatching { socket.close() }
        }
    }

    // --- Audio Renderers (G.711 PCMU/PCMA + AAC) ---

    private interface LiveAudioRenderer {
        fun feed(rtpPayload: ByteArray, muted: Boolean)
        fun release()
    }

    private fun createAudioRenderer(track: SdpAudio): LiveAudioRenderer? {
        return when {
            track.codec.equals("PCMU", true) || track.codec.equals("PCMA", true) -> {
                G711AudioRenderer(
                    sampleRate = track.clockRate.coerceAtLeast(8000),
                    isAlaw = track.codec.equals("PCMA", true)
                )
            }
            track.codec.contains("MPEG4-GENERIC", true) || track.codec.contains("AAC", true) -> {
                AacAudioRenderer(
                    sampleRate = track.clockRate.coerceAtLeast(16000),
                    channels = track.channels.coerceAtLeast(1),
                    asc = track.aacConfig
                )
            }
            else -> null
        }
    }

    private class G711AudioRenderer(
        sampleRate: Int,
        private val isAlaw: Boolean
    ) : LiveAudioRenderer {
        private val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        private val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply { play() }

        override fun feed(rtpPayload: ByteArray, muted: Boolean) {
            if (muted || rtpPayload.isEmpty()) return
            val pcm = ShortArray(rtpPayload.size)
            for (i in rtpPayload.indices) {
                pcm[i] = if (isAlaw) alawToLinear(rtpPayload[i]) else ulawToLinear(rtpPayload[i])
            }
            audioTrack.write(pcm, 0, pcm.size)
        }

        override fun release() {
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
        }

        private fun ulawToLinear(b: Byte): Short {
            val u = (b.toInt() and 0xFF).inv()
            val sign = u and 0x80
            val exp = (u ushr 4) and 0x07
            val mant = (((u and 0x0F) shl 3) + 0x84) shl exp
            val sample = mant - 0x84
            return (if (sign != 0) -sample else sample).toShort()
        }

        private fun alawToLinear(b: Byte): Short {
            val a = (b.toInt() and 0xFF) xor 0x55
            var t = (a and 0x0F) shl 4
            val seg = (a and 0x70) ushr 4
            when (seg) {
                0 -> t += 8
                1 -> t += 0x108
                else -> {
                    t += 0x108
                    t = t shl (seg - 1)
                }
            }
            return (if ((a and 0x80) != 0) t else -t).toShort()
        }
    }

    private class AacAudioRenderer(
        sampleRate: Int,
        channels: Int,
        asc: ByteArray?
    ) : LiveAudioRenderer {
        private val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val channelMask = if (channels > 1) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        private val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        private val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply { play() }

        init {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                val cfg = asc ?: buildAsc(sampleRate, channels)
                setByteBuffer("csd-0", ByteBuffer.wrap(cfg))
            }
            decoder.configure(fmt, null, null, 0)
            decoder.start()
        }

        override fun feed(rtpPayload: ByteArray, muted: Boolean) {
            if (rtpPayload.size < 4) return
            val auHdrLenBits = ((rtpPayload[0].toInt() and 0xFF) shl 8) or (rtpPayload[1].toInt() and 0xFF)
            val auHdrBytes = (auHdrLenBits + 7) / 8
            var offset = 2 + auHdrBytes
            val count = auHdrLenBits / 16
            val info = MediaCodec.BufferInfo()

            for (i in 0 until count) {
                val hdrPos = 2 + i * 2
                if (hdrPos + 2 > rtpPayload.size) break
                val auHdr = ((rtpPayload[hdrPos].toInt() and 0xFF) shl 8) or (rtpPayload[hdrPos + 1].toInt() and 0xFF)
                val size = auHdr ushr 3
                if (size > 0 && offset + size <= rtpPayload.size) {
                    val inIdx = decoder.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        buf?.clear()
                        buf?.put(rtpPayload, offset, size)
                        decoder.queueInputBuffer(inIdx, 0, size, System.nanoTime() / 1000L, 0)
                    }
                    offset += size
                }
            }

            while (true) {
                val outIdx = decoder.dequeueOutputBuffer(info, 0)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0 && !muted) {
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pcm)
                        audioTrack.write(pcm, 0, pcm.size)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                } else break
            }
        }

        override fun release() {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
        }

        private fun buildAsc(sr: Int, ch: Int): ByteArray {
            val idx = when (sr) {
                48000 -> 3; 44100 -> 4; 32000 -> 5; 24000 -> 6; 16000 -> 8; 8000 -> 11; else -> 8
            }
            return byteArrayOf(((2 shl 3) or (idx shr 1)).toByte(), (((idx and 1) shl 7) or (ch shl 3)).toByte())
        }
    }

    // --- RTSP / SDP / Depacketizer helpers ---

    private data class RtspReply(val code: Int, val headers: Map<String, String>, val body: String)
    internal data class SdpVideo(
        val controlUrl: String,
        val codec: String,
        val width: Int,
        val height: Int,
        val vps: ByteArray?,
        val sps: ByteArray?,
        val pps: ByteArray?
    )
    internal data class SdpAudio(
        val controlUrl: String,
        val codec: String,
        val clockRate: Int,
        val channels: Int,
        val aacConfig: ByteArray?
    )
    internal data class SdpParsed(val video: SdpVideo?, val audio: SdpAudio?)
    private data class RtpFrame(val marker: Boolean, val timestamp: Long, val payload: ByteArray)

    private inner class RtspClient(
        private val input: BufferedInputStream,
        private val output: OutputStream,
        private val user: String,
        private val pass: String
    ) {
        private var cseq = 1
        private var sessionId: String? = null
        private var realm: String? = null
        private var nonce: String? = null
        private var basic = false

        fun request(method: String, url: String, extra: Map<String, String> = emptyMap()): RtspReply {
            val first = sendOnce(method, url, extra)
            if (first.code == 401 && user.isNotBlank()) {
                val www = first.headers["www-authenticate"].orEmpty()
                if (www.contains("Digest", true)) {
                    realm = Regex("realm=\"([^\"]+)\"").find(www)?.groupValues?.get(1)
                    nonce = Regex("nonce=\"([^\"]+)\"").find(www)?.groupValues?.get(1)
                } else if (www.contains("Basic", true)) {
                    basic = true
                }
                return sendOnce(method, url, extra)
            }
            return first
        }

        fun keepalive(url: String) {
            val sb = StringBuilder()
            sb.append("GET_PARAMETER $url RTSP/1.0\r\nCSeq: ${cseq++}\r\n")
            sessionId?.let { sb.append("Session: $it\r\n") }
            authHeader("GET_PARAMETER", url)?.let { sb.append("Authorization: $it\r\n") }
            sb.append("User-Agent: CamVault/1.0\r\n\r\n")
            output.write(sb.toString().toByteArray(Charsets.UTF_8))
            output.flush()
        }

        private fun sendOnce(method: String, url: String, extra: Map<String, String>): RtspReply {
            val sb = StringBuilder()
            sb.append("$method $url RTSP/1.0\r\nCSeq: ${cseq++}\r\n")
            sessionId?.let { sb.append("Session: $it\r\n") }
            authHeader(method, url)?.let { sb.append("Authorization: $it\r\n") }
            sb.append("User-Agent: CamVault/1.0\r\n")
            extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            output.write(sb.toString().toByteArray(Charsets.UTF_8))
            output.flush()

            var status = readRtspLineSkipInterleaved(input)
            while (status.isBlank()) status = readRtspLineSkipInterleaved(input)
            val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
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
                val b = ByteArray(len)
                readFully(input, b, 0, len)
                String(b, Charsets.UTF_8)
            } else ""
            return RtspReply(code, hdrs, body)
        }

        private fun authHeader(method: String, url: String): String? {
            if (user.isBlank()) return null
            if (!realm.isNullOrBlank() && !nonce.isNullOrBlank()) {
                val ha1 = md5("$user:$realm:$pass")
                val ha2 = md5("$method:$url")
                val resp = md5("$ha1:$nonce:$ha2")
                return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$url\", response=\"$resp\""
            }
            if (basic) {
                val tok = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                return "Basic $tok"
            }
            return null
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private inner class H264Depacketizer {
        private var fu: ByteArrayOutputStream? = null
        fun feed(raw: ByteArray, marker: Boolean): List<ByteArray> {
            val p = stripAnnexBPrefix(raw)
            if (p.isEmpty()) return emptyList()
            val hdr = p[0].toInt() and 0xFF
            val type = hdr and 0x1F
            return when {
                type in 1..23 -> {
                    fu = null
                    splitAnnexBNals(p)
                }
                type == 24 -> {
                    fu = null
                    val list = mutableListOf<ByteArray>()
                    var i = 1
                    while (i + 2 <= p.size) {
                        val sz = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                        i += 2
                        if (sz > 0 && i + sz <= p.size) list.add(p.copyOfRange(i, i + sz))
                        i += sz
                    }
                    list
                }
                type == 28 -> {
                    if (p.size < 2) return emptyList()
                    val fuHdr = p[1].toInt() and 0xFF
                    val start = (fuHdr and 0x80) != 0
                    val end = (fuHdr and 0x40) != 0
                    val origHdr = (hdr and 0xE0) or (fuHdr and 0x1F)
                    if (start) {
                        fu = ByteArrayOutputStream().apply { write(origHdr); write(p, 2, p.size - 2) }
                        if (end) {
                            val res = fu!!.toByteArray(); fu = null; listOf(res)
                        } else emptyList()
                    } else {
                        val b = fu ?: return emptyList()
                        b.write(p, 2, p.size - 2)
                        if (end || marker) {
                            val res = b.toByteArray(); fu = null; listOf(res)
                        } else emptyList()
                    }
                }
                else -> emptyList()
            }
        }
    }

    private inner class HevcDepacketizer {
        private var fu: ByteArrayOutputStream? = null
        fun feed(raw: ByteArray, marker: Boolean): List<ByteArray> {
            val p = stripAnnexBPrefix(raw)
            if (p.size < 2) return emptyList()
            val type = (p[0].toInt() shr 1) and 0x3F
            return when {
                type < 48 -> {
                    fu = null
                    splitAnnexBNals(p)
                }
                type == 48 -> { // AP
                    fu = null
                    val list = mutableListOf<ByteArray>()
                    var i = 2
                    while (i + 2 <= p.size) {
                        val sz = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                        i += 2
                        if (sz > 0 && i + sz <= p.size) list.add(p.copyOfRange(i, i + sz))
                        i += sz
                    }
                    list
                }
                type == 49 -> { // FU
                    if (p.size < 3) return emptyList()
                    val fuHdr = p[2].toInt() and 0xFF
                    val start = (fuHdr and 0x80) != 0
                    val end = (fuHdr and 0x40) != 0
                    val fuType = fuHdr and 0x3F
                    if (start) {
                        val b0 = ((p[0].toInt() and 0x81) or (fuType shl 1)).toByte()
                        val b1 = p[1]
                        fu = ByteArrayOutputStream().apply {
                            write(b0.toInt())
                            write(b1.toInt())
                            write(p, 3, p.size - 3)
                        }
                        if (end) {
                            val res = fu!!.toByteArray(); fu = null; listOf(res)
                        } else emptyList()
                    } else {
                        val b = fu ?: return emptyList()
                        b.write(p, 3, p.size - 3)
                        if (end || marker) {
                            val res = b.toByteArray(); fu = null; listOf(res)
                        } else emptyList()
                    }
                }
                else -> emptyList()
            }
        }
    }

    internal fun parseInterleavedRtpChannel(transportHeader: String?, defaultChannel: Int): Int {
        if (transportHeader.isNullOrBlank()) return defaultChannel
        return Regex("interleaved=(\\d+)")
            .find(transportHeader)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: defaultChannel
    }

    internal fun parseSdp(sdp: String, contentBase: String): SdpParsed {
        var section = ""
        var videoPt = ""
        var audioPt = ""
        var vCtrl = ""; var vCodec = ""; var vW = 0; var vH = 0
        var vps: ByteArray? = null; var sps: ByteArray? = null; var pps: ByteArray? = null
        var aCtrl = ""; var aCodec = ""; var aRate = 8000; var aCh = 1; var aCfg: ByteArray? = null

        sdp.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("m=video") -> {
                    section = "video"
                    videoPt = line.split(Regex("\\s+")).getOrNull(3).orEmpty()
                }
                line.startsWith("m=audio") -> {
                    section = "audio"
                    audioPt = line.split(Regex("\\s+")).getOrNull(3).orEmpty()
                }
                line.startsWith("m=") -> {
                    section = "other"
                }
                line.startsWith("a=control:") -> {
                    val ctrl = line.removePrefix("a=control:").trim()
                    val resolved = if (ctrl.startsWith("rtsp://", true) || ctrl.startsWith("rtsps://", true)) ctrl
                    else if (ctrl == "*") contentBase
                    else "${contentBase.trimEnd('/')}/${ctrl.trimStart('/')}"
                    if (section == "video") vCtrl = resolved
                    if (section == "audio") aCtrl = resolved
                }
                line.startsWith("a=rtpmap:") -> {
                    val after = line.removePrefix("a=rtpmap:").trim()
                    val pt = after.substringBefore(" ")
                    val info = after.substringAfter(" ", "")
                    val parts = info.split("/")
                    val c = parts.getOrNull(0).orEmpty()
                    val r = parts.getOrNull(1)?.toIntOrNull()
                    val ch = parts.getOrNull(2)?.toIntOrNull()
                    if (section == "video" && c.isNotBlank() && (vCodec.isBlank() || pt == videoPt)) {
                        vCodec = c
                    } else if (section == "audio" && c.isNotBlank() && (aCodec.isBlank() || pt == audioPt)) {
                        aCodec = c
                        aRate = r ?: if (c.equals("PCMU", true) || c.equals("PCMA", true)) 8000 else 16000
                        aCh = ch ?: 1
                    }
                }
                line.startsWith("a=framesize:") && section == "video" -> {
                    val dims = line.substringAfter(" ").trim().split("-")
                    if (dims.size == 2) {
                        vW = dims[0].toIntOrNull() ?: vW
                        vH = dims[1].toIntOrNull() ?: vH
                    }
                }
                line.startsWith("a=fmtp:") -> {
                    if (section == "video") {
                        Regex("sprop-parameter-sets=([^;\\s\"]+)").find(line)?.groupValues?.get(1)?.let { s ->
                            val parts = s.split(",")
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
                    } else if (section == "audio") {
                        Regex("config=([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.let { hex ->
                            aCfg = ByteArray(hex.length / 2) { idx ->
                                hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
                            }
                        }
                    }
                }
            }
        }

        val resolvedCodec = vCodec.ifBlank { "H264" }
        if ((vW <= 0 || vH <= 0) && sps != null && !resolvedCodec.equals("H265", true) && !resolvedCodec.equals("HEVC", true)) {
            RtspProbe.parseH264SpsDimensions(sps!!)?.let {
                vW = it.first
                vH = it.second
            }
        }

        val v = if (vCtrl.isNotBlank()) SdpVideo(vCtrl, resolvedCodec, vW, vH, vps, sps, pps) else null
        val a = if (aCtrl.isNotBlank() && aCodec.isNotBlank()) SdpAudio(aCtrl, aCodec, aRate, aCh, aCfg) else null
        return SdpParsed(v, a)
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
                    starts.add(i)
                    i += 4
                    continue
                } else if (p[i + 2].toInt() == 1) {
                    starts.add(i)
                    i += 3
                    continue
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

    private fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(4 + nal.size)
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    private fun readInterleaved(input: BufferedInputStream): Pair<Int, ByteArray>? {
        while (true) {
            val first = input.read()
            if (first == -1) throw EOFException("RTSP closed")
            if (first == 0x24) {
                val ch = input.read(); val hi = input.read(); val lo = input.read()
                if (ch == -1 || hi == -1 || lo == -1) throw EOFException("Truncated interleaved")
                val len = (hi shl 8) or lo
                val buf = ByteArray(len)
                readFully(input, buf, 0, len)
                return ch to buf
            } else if (first == 'R'.code) {
                readLineCrLf(input)
                while (readLineCrLf(input).isNotEmpty()) {}
            }
        }
    }

    private fun parseRtp(data: ByteArray, len: Int): RtpFrame? {
        if (len < 12) return null
        val b0 = data[0].toInt() and 0xFF
        if (((b0 ushr 6) and 0x03) != 2) return null
        val hasPad = (b0 and 0x20) != 0
        val hasExt = (b0 and 0x10) != 0
        val csrc = b0 and 0x0F
        val marker = ((data[1].toInt() and 0xFF) and 0x80) != 0
        val ts = ((data[4].toLong() and 0xFF) shl 24) or
            ((data[5].toLong() and 0xFF) shl 16) or
            ((data[6].toLong() and 0xFF) shl 8) or
            (data[7].toLong() and 0xFF)
        var off = 12 + csrc * 4
        if (hasExt) {
            if (off + 4 > len) return null
            val extWords = ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
            off += 4 + extWords * 4
        }
        var end = len
        if (hasPad && end > off) {
            end = (end - (data[end - 1].toInt() and 0xFF)).coerceAtLeast(off)
        }
        if (off >= end) return null
        return RtpFrame(marker, ts, data.copyOfRange(off, end))
    }

    private fun readRtspLineSkipInterleaved(input: BufferedInputStream): String {
        while (true) {
            input.mark(4)
            val b = input.read()
            if (b == -1) throw EOFException("RTSP closed")
            if (b == 0x24) {
                val ch = input.read(); val hi = input.read(); val lo = input.read()
                if (ch == -1 || hi == -1 || lo == -1) throw EOFException("Truncated")
                val len = (hi shl 8) or lo
                readFully(input, ByteArray(len), 0, len)
            } else {
                input.reset()
                return readLineCrLf(input)
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

    private fun readFully(input: InputStream, dst: ByteArray, off: Int, len: Int) {
        var rem = len; var cur = off
        while (rem > 0) {
            val n = input.read(dst, cur, rem)
            if (n == -1) throw EOFException("EOF")
            cur += n; rem -= n
        }
    }

    private fun extractCredentials(uri: URI): Pair<String, String> {
        val info = uri.rawUserInfo ?: return "" to ""
        val parts = info.split(":", limit = 2)
        val u = runCatching { URLDecoder.decode(parts.getOrElse(0) { "" }, "UTF-8") }.getOrDefault(parts.getOrElse(0) { "" })
        val p = runCatching { URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8") }.getOrDefault(parts.getOrElse(1) { "" })
        return u to p
    }

    private fun stripCredentials(uri: URI): String {
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val pathPart = uri.rawPath.orEmpty()
        val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "${uri.scheme}://${uri.host}$portPart$pathPart$queryPart"
    }
}
