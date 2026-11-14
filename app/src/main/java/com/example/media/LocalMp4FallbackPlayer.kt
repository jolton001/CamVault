package com.example.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class Mp4TrackInspection(
    val hasVideo: Boolean = false,
    val videoMime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val videoSampleCount: Int = 0,
    val videoKeyframeCount: Int = 0,
    val durationMs: Long = 0L,
    val hasAudio: Boolean = false,
    val audioMime: String = "",
    val audioSampleRate: Int = 0,
    val audioChannelCount: Int = 0,
    val error: String = ""
) {
    val shortCodecLabel: String
        get() = when {
            videoMime.contains("hevc", ignoreCase = true) || videoMime.contains("h265", ignoreCase = true) -> "HEVC"
            videoMime.contains("avc", ignoreCase = true) || videoMime.contains("h264", ignoreCase = true) -> "H.264"
            videoMime.isNotBlank() -> videoMime.substringAfter('/').uppercase(Locale.US)
            else -> "No Video"
        }

    val missingRealKeyframes: Boolean
        get() = hasVideo && videoSampleCount > 30 && videoKeyframeCount <= 1

    val summaryText: String
        get() {
            if (error.isNotBlank()) return "Inspect error: $error"
            if (!hasVideo) return if (hasAudio) "Audio only ($audioMime)" else "No media tracks"
            val dims = if (width > 0 && height > 0) " ${width}x${height}" else ""
            val frames = "${videoSampleCount}f/${videoKeyframeCount}kf"
            val audioLabel = when {
                !hasAudio -> ""
                audioMime.contains("mp4a", ignoreCase = true) -> " • AAC"
                else -> " • ${audioMime.substringAfter('/').uppercase(Locale.US)}"
            }
            return "$shortCodecLabel$dims • $frames$audioLabel"
        }
}

fun inspectMp4Tracks(filePath: String): Mp4TrackInspection {
    val file = File(filePath)
    if (!file.exists() || file.length() == 0L) {
        return Mp4TrackInspection(error = "File missing or empty")
    }
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(filePath)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoMime = ""
        var width = 0
        var height = 0
        var durationUs = 0L
        var audioMime = ""
        var audioSampleRate = 0
        var audioChannels = 0

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/") && videoTrackIndex < 0) {
                videoTrackIndex = i
                videoMime = mime
                width = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
                height = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
                durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                audioTrackIndex = i
                audioMime = mime
                audioSampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                audioChannels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                if (durationUs <= 0L && fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                }
            }
        }

        var sampleCount = 0
        var keyframeCount = 0
        if (videoTrackIndex >= 0) {
            extractor.selectTrack(videoTrackIndex)
            var guard = 0
            while (guard++ < 50_000) {
                val trackIdx = extractor.sampleTrackIndex
                if (trackIdx < 0) break
                if (trackIdx == videoTrackIndex) {
                    sampleCount++
                    if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        keyframeCount++
                    }
                }
                if (!extractor.advance()) break
            }
        }

        Mp4TrackInspection(
            hasVideo = videoTrackIndex >= 0,
            videoMime = videoMime,
            width = width,
            height = height,
            videoSampleCount = sampleCount,
            videoKeyframeCount = keyframeCount,
            durationMs = (durationUs / 1000L).coerceAtLeast(0L),
            hasAudio = audioTrackIndex >= 0,
            audioMime = audioMime,
            audioSampleRate = audioSampleRate,
            audioChannelCount = audioChannels
        )
    } catch (e: Exception) {
        Mp4TrackInspection(error = e.localizedMessage ?: "Unsupported MP4 container")
    } finally {
        runCatching { extractor.release() }
    }
}

class LocalMp4FallbackPlayer {

    interface Listener {
        fun onStatusChanged(status: String, decoderName: String = "")
        fun onVideoDimensions(width: Int, height: Int)
        fun onFrameRendered(frameCount: Long, decoderName: String)
        fun onProgress(positionMs: Long, durationMs: Long)
        fun onPlaybackEnded()
        fun onError(message: String)
    }

    private var playbackJob: Job? = null
    private val stopFlag = AtomicBoolean(false)

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var muted: Boolean = false

    fun isPaused(): Boolean = paused

    fun setPaused(isPaused: Boolean) {
        paused = isPaused
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    fun start(
        scope: CoroutineScope,
        filePath: String,
        surface: Surface,
        preferSoftware: Boolean = true,
        listener: Listener
    ) {
        stop()
        stopFlag.set(false)
        paused = false

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                runPlayback(
                    filePath = filePath,
                    surface = surface,
                    preferSoftware = preferSoftware,
                    listener = listener
                )
            } catch (e: Exception) {
                if (!stopFlag.get()) {
                    Log.e("LocalMp4Fallback", "Playback failed", e)
                    listener.onError(e.localizedMessage ?: "Software MP4 decode failed")
                }
            }
        }
    }

    fun stop() {
        stopFlag.set(true)
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun runPlayback(
        filePath: String,
        surface: Surface,
        preferSoftware: Boolean,
        listener: Listener
    ) {
        val videoExtractor = MediaExtractor()
        var videoCodec: MediaCodec? = null
        var audioJob: Job? = null

        try {
            videoExtractor.setDataSource(filePath)
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                    videoFormat = fmt
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                    audioFormat = fmt
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                listener.onError("No video track found inside MP4 recording.")
                return
            }

            val mimeType = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val width = if (videoFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                videoFormat.getInteger(MediaFormat.KEY_WIDTH).coerceAtLeast(320)
            } else 1280
            val height = if (videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                videoFormat.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(240)
            } else 720
            val durationMs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                (videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(0L)
            } else 0L

            if (!videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 1024 * 1024))
            }

            listener.onVideoDimensions(width, height)

            val (createdCodec, chosenCodecName) = createVideoDecoder(
                mimeType = mimeType,
                format = videoFormat,
                surface = surface,
                preferSoftware = preferSoftware
            )
            videoCodec = createdCodec
            listener.onStatusChanged("Playing ($chosenCodecName)", chosenCodecName)

            videoExtractor.selectTrack(videoTrackIndex)

            if (audioTrackIndex >= 0 && audioFormat != null) {
                audioJob = CoroutineScope(Dispatchers.IO).launch {
                    runAudioTrack(filePath, audioTrackIndex, audioFormat)
                }
            }

            val outInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frameCount = 0L
            var basePtsUs: Long? = null
            var baseWallNs = 0L
            var lastProgressMs = -1L

            while (!stopFlag.get() && !outputDone) {
                if (paused) {
                    val pauseStartNs = System.nanoTime()
                    while (!stopFlag.get() && paused) {
                        Thread.sleep(35L)
                    }
                    if (baseWallNs > 0L) {
                        baseWallNs += (System.nanoTime() - pauseStartNs)
                    }
                    continue
                }

                if (!inputDone) {
                    val inIdx = videoCodec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = videoCodec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) videoExtractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            videoCodec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val samplePtsUs = videoExtractor.sampleTime.coerceAtLeast(0L)
                            val sampleFlags = if ((videoExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else 0
                            videoCodec.queueInputBuffer(inIdx, 0, sampleSize, samplePtsUs, sampleFlags)
                            videoExtractor.advance()
                        }
                    }
                }

                val outIdx = videoCodec.dequeueOutputBuffer(outInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = videoCodec.outputFormat
                        val ow = if (outFmt.containsKey(MediaFormat.KEY_WIDTH)) outFmt.getInteger(MediaFormat.KEY_WIDTH) else width
                        val oh = if (outFmt.containsKey(MediaFormat.KEY_HEIGHT)) outFmt.getInteger(MediaFormat.KEY_HEIGHT) else height
                        if (ow > 0 && oh > 0) listener.onVideoDimensions(ow, oh)
                    }
                    outIdx >= 0 -> {
                        if ((outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        if (outInfo.size > 0 && !stopFlag.get()) {
                            val ptsUs = outInfo.presentationTimeUs.coerceAtLeast(0L)
                            if (basePtsUs == null) {
                                basePtsUs = ptsUs
                                baseWallNs = System.nanoTime()
                            } else {
                                val targetWallNs = baseWallNs + ((ptsUs - basePtsUs) * 1000L)
                                val deltaMs = ((targetWallNs - System.nanoTime()) / 1_000_000L).coerceAtMost(250L)
                                if (deltaMs > 2L) {
                                    Thread.sleep(deltaMs)
                                }
                            }
                            videoCodec.releaseOutputBuffer(outIdx, surface.isValid)
                            frameCount++
                            if (frameCount == 1L || frameCount % 15L == 0L) {
                                listener.onFrameRendered(frameCount, chosenCodecName)
                            }
                            val posMs = ptsUs / 1000L
                            if (lastProgressMs < 0L || posMs - lastProgressMs >= 250L) {
                                lastProgressMs = posMs
                                listener.onProgress(posMs, durationMs)
                            }
                        } else {
                            videoCodec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
            }

            if (!stopFlag.get()) {
                listener.onStatusChanged(
                    if (frameCount > 0L) "Completed ($chosenCodecName)" else "No decoded video frames",
                    chosenCodecName
                )
                listener.onPlaybackEnded()
            }
        } finally {
            audioJob?.cancel()
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { videoExtractor.release() }
        }
    }

    private fun runAudioTrack(filePath: String, audioTrackIndex: Int, audioFormat: MediaFormat) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var audioTrack: AudioTrack? = null
        try {
            extractor.setDataSource(filePath)
            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.isBlank()) return
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else 16000
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1
            val channelMask = if (channels > 1) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(4096)

            audioTrack = AudioTrack.Builder()
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
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().apply { play() }

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var basePtsUs: Long? = null
            var baseWallNs = 0L

            while (!stopFlag.get() && !outDone) {
                if (paused) {
                    val pauseStartNs = System.nanoTime()
                    while (!stopFlag.get() && paused) {
                        Thread.sleep(35L)
                    }
                    if (baseWallNs > 0L) {
                        baseWallNs += (System.nanoTime() - pauseStartNs)
                    }
                    continue
                }

                if (!inDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val sz = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outDone = true
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val ptsUs = info.presentationTimeUs.coerceAtLeast(0L)
                        if (basePtsUs == null) {
                            basePtsUs = ptsUs
                            baseWallNs = System.nanoTime()
                        } else {
                            val targetNs = baseWallNs + ((ptsUs - basePtsUs) * 1000L)
                            val waitMs = ((targetNs - System.nanoTime()) / 1_000_000L).coerceAtMost(200L)
                            if (waitMs > 4L) Thread.sleep(waitMs)
                        }
                        if (!muted) {
                            val pcm = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(pcm)
                            audioTrack.write(pcm, 0, pcm.size)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (_: Exception) {
            // Audio fallback failures should not abort video playback
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun createVideoDecoder(
        mimeType: String,
        format: MediaFormat,
        surface: Surface,
        preferSoftware: Boolean
    ): Pair<MediaCodec, String> {
        val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, ignoreCase = true) } }
            .sortedByDescending { info ->
                val isSw = isSoftwareCodec(info)
                if (preferSoftware) {
                    when {
                        info.name.lowercase(Locale.US).startsWith("c2.android.") -> 3
                        isSw -> 2
                        else -> 0
                    }
                } else {
                    if (!isSw) 2 else 1
                }
            }

        var lastError: Exception? = null
        for (info in candidates) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(info.name)
                codec.configure(format, surface, null, 0)
                codec.start()
                return codec to info.name
            } catch (e: Exception) {
                lastError = e
                runCatching { codec?.release() }
            }
        }

        // Last resort: default platform decoder by MIME
        return try {
            val defaultCodec = MediaCodec.createDecoderByType(mimeType)
            defaultCodec.configure(format, surface, null, 0)
            defaultCodec.start()
            defaultCodec to (runCatching { defaultCodec.name }.getOrDefault(mimeType))
        } catch (e: Exception) {
            throw (lastError ?: e)
        }
    }

    private fun isSoftwareCodec(info: android.media.MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly) return true
        val name = info.name.lowercase(Locale.US)
        return name.startsWith("c2.android.") || name.startsWith("omx.google.") || name.contains(".sw.")
    }
}
