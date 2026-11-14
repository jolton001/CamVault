package com.example.media

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

data class RtspProbeReport(
    val reachable: Boolean,
    val rtspStatusCode: Int,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val audioSampleRate: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val hasSpropInSdp: Boolean = false,
    val sdpSummary: String = "",
    val errorMessage: String = "",
    val recommendNativeEngine: Boolean = false
)

object RtspProbe {

    suspend fun inspect(rtspUrl: String): RtspProbeReport = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            val uri = URI(rtspUrl.trim())
            val host = uri.host ?: return@withContext RtspProbeReport(
                reachable = false,
                rtspStatusCode = 0,
                errorMessage = "Invalid RTSP host in URL"
            )
            val port = if (uri.port > 0) uri.port else 554
            val (user, pass) = extractCredentials(uri)
            val cleanUrl = stripCredentials(uri)

            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(host, port), 5000)
            val input = BufferedInputStream(socket.getInputStream(), 16384)
            val output = socket.getOutputStream()

            var cseq = 1
            var digestRealm: String? = null
            var digestNonce: String? = null
            var useBasic = false

            fun buildAuth(method: String): String? {
                if (user.isBlank()) return null
                val realm = digestRealm
                val nonce = digestNonce
                if (!realm.isNullOrBlank() && !nonce.isNullOrBlank()) {
                    val ha1 = md5("$user:$realm:$pass")
                    val ha2 = md5("$method:$cleanUrl")
                    val resp = md5("$ha1:$nonce:$ha2")
                    return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$cleanUrl\", response=\"$resp\""
                }
                if (useBasic) {
                    val tok = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    return "Basic $tok"
                }
                return null
            }

            fun send(method: String, extra: Map<String, String> = emptyMap()): Triple<Int, Map<String, String>, String> {
                val sb = StringBuilder()
                sb.append("$method $cleanUrl RTSP/1.0\r\n")
                sb.append("CSeq: ${cseq++}\r\n")
                buildAuth(method)?.let { sb.append("Authorization: $it\r\n") }
                sb.append("User-Agent: CamVault/1.0\r\n")
                extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                sb.append("\r\n")
                output.write(sb.toString().toByteArray(Charsets.UTF_8))
                output.flush()

                var status = readLineCrLf(input)
                while (status.isBlank()) status = readLineCrLf(input)
                val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLineCrLf(input)
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (len > 0) {
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(buf, read, len - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""
                return Triple(code, headers, body)
            }

            send("OPTIONS")
            var desc = send("DESCRIBE", mapOf("Accept" to "application/sdp"))
            if (desc.first == 401 && user.isNotBlank()) {
                val www = desc.second["www-authenticate"].orEmpty()
                if (www.contains("Digest", true)) {
                    digestRealm = Regex("realm=\"([^\"]+)\"").find(www)?.groupValues?.get(1)
                    digestNonce = Regex("nonce=\"([^\"]+)\"").find(www)?.groupValues?.get(1)
                } else if (www.contains("Basic", true)) {
                    useBasic = true
                }
                desc = send("DESCRIBE", mapOf("Accept" to "application/sdp"))
            }

            if (desc.first !in 200..299) {
                return@withContext RtspProbeReport(
                    reachable = true,
                    rtspStatusCode = desc.first,
                    errorMessage = "RTSP DESCRIBE returned ${desc.first}"
                )
            }

            return@withContext parseSdpReport(desc.first, desc.third)
        } catch (e: Exception) {
            RtspProbeReport(
                reachable = false,
                rtspStatusCode = 0,
                errorMessage = com.example.security.SecurityGuards.sanitizeErrorMessage(
                    e.localizedMessage,
                    "Cannot reach RTSP port"
                )
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    internal fun parseSdpReport(statusCode: Int, sdp: String): RtspProbeReport {
        var section = ""
        var videoPt = ""
        var audioPt = ""
        var videoCodec = ""
        var audioCodec = ""
        var audioRate = 0
        var width = 0
        var height = 0
        var hasSprop = false
        var spsBytes: ByteArray? = null

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
                line.startsWith("a=rtpmap:") -> {
                    val after = line.removePrefix("a=rtpmap:").trim()
                    val pt = after.substringBefore(" ")
                    val info = after.substringAfter(" ", "")
                    val parts = info.split("/")
                    val codec = parts.getOrNull(0).orEmpty()
                    val rate = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    if (section == "video" && (videoCodec.isBlank() || pt == videoPt)) {
                        if (codec.isNotBlank()) videoCodec = codec
                    } else if (section == "audio" && (audioCodec.isBlank() || pt == audioPt)) {
                        if (codec.isNotBlank()) {
                            audioCodec = codec
                            audioRate = rate
                        }
                    }
                }
                line.startsWith("a=fmtp:") && section == "video" -> {
                    val sprop = Regex("sprop-parameter-sets=([^;\\s\"]+)").find(line)?.groupValues?.get(1)
                    if (sprop != null) {
                        hasSprop = true
                        val firstNal = sprop.split(",").firstOrNull()?.trim()
                        if (!firstNal.isNullOrBlank()) {
                            spsBytes = runCatching { Base64.decode(firstNal, Base64.DEFAULT) }.getOrNull()
                        }
                    }
                    if (line.contains("sprop-sps=", ignoreCase = true)) {
                        hasSprop = true
                    }
                }
                line.startsWith("a=framesize:") && section == "video" -> {
                    val dims = line.substringAfter(" ").trim().split("-")
                    if (dims.size == 2) {
                        width = dims[0].toIntOrNull() ?: width
                        height = dims[1].toIntOrNull() ?: height
                    }
                }
            }
        }

        if ((width <= 0 || height <= 0) && spsBytes != null) {
            val parsed = parseH264SpsDimensions(spsBytes!!)
            if (parsed != null) {
                width = parsed.first
                height = parsed.second
            }
        }

        val needsNative = videoCodec.equals("H265", true) ||
            videoCodec.equals("HEVC", true) ||
            (videoCodec.equals("H264", true) && !hasSprop) ||
            audioCodec.equals("PCMA", true) ||
            audioCodec.equals("PCMU", true)

        val summary = buildString {
            append(videoCodec.ifBlank { "Video" })
            if (width > 0 && height > 0) append(" ${width}x${height}")
            if (videoCodec.equals("H264", true) && !hasSprop) append(" (in-band SPS)")
            if (audioCodec.isNotBlank()) {
                append(" • Audio: $audioCodec")
                if (audioRate > 0) append(" ${audioRate}Hz")
            }
        }

        return RtspProbeReport(
            reachable = true,
            rtspStatusCode = statusCode,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            audioSampleRate = audioRate,
            width = width,
            height = height,
            hasSpropInSdp = hasSprop,
            sdpSummary = summary,
            recommendNativeEngine = needsNative
        )
    }

    internal fun parseH264SpsDimensions(spsRaw: ByteArray): Pair<Int, Int>? {
        return runCatching {
            var offset = 0
            if (spsRaw.size >= 4 && spsRaw[0].toInt() == 0 && spsRaw[1].toInt() == 0 && spsRaw[2].toInt() == 0 && spsRaw[3].toInt() == 1) {
                offset = 4
            } else if (spsRaw.size >= 3 && spsRaw[0].toInt() == 0 && spsRaw[1].toInt() == 0 && spsRaw[2].toInt() == 1) {
                offset = 3
            }
            if (offset >= spsRaw.size) return null
            if ((spsRaw[offset].toInt() and 0x1F) != 7) return null

            // Remove emulation prevention 0x03 bytes inside RBSP
            val rbsp = ByteArrayOutputStream()
            var zeros = 0
            for (i in (offset + 1) until spsRaw.size) {
                val b = spsRaw[i].toInt() and 0xFF
                if (zeros == 2 && b == 3) {
                    zeros = 0
                    continue
                }
                rbsp.write(b)
                zeros = if (b == 0) zeros + 1 else 0
            }
            val data = rbsp.toByteArray()
            if (data.size < 4) return null

            val reader = BitReader(data)
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint flags + reserved
            reader.readBits(8) // level_idc
            reader.readUe() // seq_parameter_set_id

            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 ||
                profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 ||
                profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134
            ) {
                val chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) reader.readBits(1)
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBits(1) // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresent = reader.readBits(1)
                if (seqScalingMatrixPresent == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until count) {
                        if (reader.readBits(1) == 1) {
                            val listSize = if (i < 6) 16 else 64
                            var lastScale = 8
                            var nextScale = 8
                            for (j in 0 until listSize) {
                                if (nextScale != 0) {
                                    val delta = reader.readSe()
                                    nextScale = (lastScale + delta + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            reader.readUe() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUe()
            when (picOrderCntType) {
                0 -> reader.readUe()
                1 -> {
                    reader.readBits(1)
                    reader.readSe()
                    reader.readSe()
                    val numRef = reader.readUe()
                    for (i in 0 until numRef) reader.readSe()
                }
            }
            reader.readUe() // max_num_ref_frames
            reader.readBits(1) // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) reader.readBits(1)
            reader.readBits(1) // direct_8x8_inference_flag
            val frameCroppingFlag = reader.readBits(1)
            var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
            if (frameCroppingFlag == 1) {
                cropL = reader.readUe()
                cropR = reader.readUe()
                cropT = reader.readUe()
                cropB = reader.readUe()
            }
            val width = (picWidthInMbsMinus1 + 1) * 16 - (cropL + cropR) * 2
            val height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16 - (cropT + cropB) * 2
            if (width in 64..7680 && height in 64..4320) width to height else null
        }.getOrNull()
    }

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0
        fun readBits(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                val byteIdx = bitPos ushr 3
                val bitIdx = 7 - (bitPos and 7)
                val bit = if (byteIdx < data.size) (data[byteIdx].toInt() ushr bitIdx) and 1 else 0
                v = (v shl 1) or bit
                bitPos++
            }
            return v
        }
        fun readUe(): Int {
            var zeros = 0
            while (bitPos < data.size * 8 && readBits(1) == 0 && zeros < 30) zeros++
            return ((1 shl zeros) - 1) + readBits(zeros)
        }
        fun readSe(): Int {
            val ue = readUe()
            val sign = if ((ue and 1) == 1) 1 else -1
            return sign * ((ue + 1) ushr 1)
        }
    }

    private fun extractCredentials(uri: URI): Pair<String, String> {
        val raw = uri.rawUserInfo ?: return "" to ""
        val parts = raw.split(":", limit = 2)
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

    private fun readLineCrLf(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
