package com.example

import android.content.Context
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.muxer.BufferInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CamVaultDatabase
import com.example.data.local.CameraEntity
import com.example.data.local.CloudProviderType
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.RecordingEntity
import com.example.data.local.UploadStatus
import com.example.media.NativeRtspLivePlayer
import com.example.media.RecordingSessionState
import com.example.media.RtspProbe
import com.example.media.RtspStreamRecorder
import com.example.network.onvif.OnvifDiscovery
import com.example.network.onvif.OnvifSoapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnvifAndDatabaseTest {

    private lateinit var db: CamVaultDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CamVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `parse ONVIF WS-Discovery ProbeMatch XML extracts host, port, and model`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val discovery = OnvifDiscovery(context)
        val sampleXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope"
                xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
                <SOAP-ENV:Body>
                    <d:ProbeMatches>
                        <d:ProbeMatch>
                            <d:Scopes>onvif://www.onvif.org/name/Front%20Porch onvif://www.onvif.org/hardware/IPC-4MP</d:Scopes>
                            <d:XAddrs>http://192.168.1.88:8080/onvif/device_service</d:XAddrs>
                        </d:ProbeMatch>
                    </d:ProbeMatches>
                </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
        """.trimIndent()

        val cameras = discovery.parseProbeMatch(sampleXml, "192.168.1.88")
        assertEquals(1, cameras.size)
        assertEquals("192.168.1.88", cameras[0].host)
        assertEquals(8080, cameras[0].port)
        assertEquals("Front Porch", cameras[0].name)
        assertEquals("IPC-4MP", cameras[0].hardwareModel)
    }

    @Test
    fun `injectRtspCredentials embeds username and password cleanly`() {
        val result = OnvifSoapClient.injectRtspCredentials(
            rtspUrl = "rtsp://192.168.1.50:554/h264Preview_01_main",
            username = "admin",
            password = "secret",
            expectedHost = "192.168.1.50"
        )
        assertEquals("rtsp://admin:secret@192.168.1.50:554/h264Preview_01_main", result)
    }

    @Test
    fun `injectRtspCredentials accepts matching host case-insensitively`() {
        val result = OnvifSoapClient.injectRtspCredentials(
            rtspUrl = "rtsps://CAMERA.EXAMPLE:322/stream",
            username = "admin",
            password = "secret",
            expectedHost = "camera.example"
        )
        assertEquals("rtsps://admin:secret@CAMERA.EXAMPLE:322/stream", result)
    }

    @Test
    fun `injectRtspCredentials leaves credentials off untrusted or malformed URLs`() {
        val attackerUrl = "rtsp://attacker.example/stream"
        val malformedUrl = "rtsp://attacker.example:invalid/stream"

        assertEquals(
            attackerUrl,
            OnvifSoapClient.injectRtspCredentials(attackerUrl, "admin", "secret", "camera.example")
        )
        assertEquals(
            malformedUrl,
            OnvifSoapClient.injectRtspCredentials(malformedUrl, "admin", "secret", "camera.example")
        )
        assertEquals(
            "http://camera.example/stream",
            OnvifSoapClient.injectRtspCredentials(
                "http://camera.example/stream",
                "admin",
                "secret",
                "camera.example"
            )
        )
    }

    @Test
    fun `room database persists camera, recording, and cloud storage target`() = runTest {
        val camId = db.cameraDao().insertCamera(
            CameraEntity(
                name = "Backyard ONVIF",
                host = "192.168.1.72",
                rtspUrl = "rtsp://192.168.1.72:554/stream1",
                ptzSupported = true,
                audioEnabled = true
            )
        )
        val recId = db.recordingDao().insertRecording(
            RecordingEntity(
                cameraId = camId,
                cameraName = "Backyard ONVIF",
                fileName = "Backyard_01.mp4",
                localFilePath = "/tmp/Backyard_01.mp4",
                durationMs = 15_000L,
                fileSizeBytes = 2_400_000L,
                hasAudio = true,
                audioCodec = "AAC"
            )
        )
        db.recordingDao().updateUploadState(
            id = recId,
            status = UploadStatus.UPLOADED,
            progress = 100,
            remoteUrl = "https://s3.us-east-1.amazonaws.com/my-bucket/Backyard_01.mp4",
            error = "",
            uploadedAt = 123456789L
        )
        db.cloudStorageConfigDao().insertConfig(
            CloudStorageConfigEntity(
                name = "My S3 Bucket",
                providerType = CloudProviderType.S3_COMPATIBLE,
                endpointUrl = "https://s3.us-east-1.amazonaws.com",
                bucketName = "my-bucket"
            )
        )

        val cameras = db.cameraDao().observeCameras().first()
        val recordings = db.recordingDao().observeRecordings().first()
        val cloud = db.cloudStorageConfigDao().getDefaultConfig()

        assertEquals(1, cameras.size)
        assertTrue(cameras.first().ptzSupported)
        assertEquals(UploadStatus.UPLOADED, recordings.first().uploadStatus)
        assertEquals("my-bucket", cloud?.bucketName)
    }

    @Test
    fun `parseSdp preserves video and audio tracks when ONVIF metadata m=application section follows`() {
        val sdp = """
            v=0
            o=- 1700000 1700000 IN IP4 192.168.1.50
            s=Media Presentation
            t=0 0
            a=control:*
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z00AKp2oHgCJ+WbgICAoAAADAAgAAAMBlCA=,aO48gA==
            a=control:trackID=0
            m=audio 0 RTP/AVP 8
            a=rtpmap:8 PCMA/8000/1
            a=control:trackID=1
            m=application 0 RTP/AVP 107
            a=rtpmap:107 vnd.onvif.metadata/90000
            a=control:trackID=2
        """.trimIndent()

        val probe = RtspProbe.parseSdpReport(200, sdp)
        assertEquals("H264", probe.videoCodec)
        assertEquals("PCMA", probe.audioCodec)
        assertEquals(8000, probe.audioSampleRate)
        assertTrue(probe.hasSpropInSdp)
        assertTrue(probe.recommendNativeEngine)

        val player = NativeRtspLivePlayer()
        val parsed = player.parseSdp(sdp, "rtsp://192.168.1.50:554/stream1")
        assertNotNull(parsed.video)
        assertNotNull(parsed.audio)
        assertEquals("H264", parsed.video?.codec)
        assertEquals("rtsp://192.168.1.50:554/stream1/trackID=0", parsed.video?.controlUrl)
        assertEquals("PCMA", parsed.audio?.codec)
        assertEquals("rtsp://192.168.1.50:554/stream1/trackID=1", parsed.audio?.controlUrl)
        assertEquals(2, player.parseInterleavedRtpChannel("RTP/AVP/TCP;unicast;interleaved=2-3", 0))
        assertEquals(0, player.parseInterleavedRtpChannel(null, 0))
    }

    @Test
    fun `onvifDiscovery computes local subnet and broadcast from current IP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val discovery = OnvifDiscovery(context)

        val wifiInt = (42 shl 24) or (1 shl 16) or (168 shl 8) or 192
        val parsedIp = discovery.parseIpv4FromInt(wifiInt)
        assertEquals("192.168.1.42", parsedIp)

        val net24 = discovery.buildLocalNetworkInfo(parsedIp, 24, "wlan0")
        assertEquals("192.168.1.", net24.subnetBase)
        assertEquals("192.168.1.255", net24.broadcastIp)

        val net22 = discovery.buildLocalNetworkInfo("192.168.2.15", 22, "wlan0")
        assertEquals("192.168.3.255", net22.broadcastIp)
    }

    @Test
    fun `recorder encodes AVCC length-prefixed NALs and muxes valid MP4 with avc1 and mp4a boxes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = RtspStreamRecorder(context)

        val seiNal = byteArrayOf(0x06, 0x05, 0x04, 0x01, 0x02, 0x03, 0x04)
        val idrNal = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21, 0x0F, 0x11, 0x22, 0x33)
        val avccSample = recorder.encodeAvccSample(listOf(seiNal, idrNal))
        // Verify 4-byte big-endian length prefixes instead of 00 00 00 01 Annex-B start codes
        assertEquals(4 + seiNal.size + 4 + idrNal.size, avccSample.size)
        assertEquals(0, avccSample[0].toInt())
        assertEquals(0, avccSample[1].toInt())
        assertEquals(0, avccSample[2].toInt())
        assertEquals(seiNal.size, avccSample[3].toInt())
        assertEquals(0x65.toByte(), avccSample[4 + seiNal.size + 4])

        // Verify Mp4Muxer writes genuine ISO BMFF MP4 with avc1/avcC + mp4a/esds + moov + mdat
        val spsRaw = Base64.decode("Z00AKp2oHgCJ+WbgICAoAAADAAgAAAMBlCA=", Base64.DEFAULT)
        val ppsRaw = Base64.decode("aO48gA==", Base64.DEFAULT)
        val spsAnnexB = byteArrayOf(0, 0, 0, 1) + spsRaw
        val ppsAnnexB = byteArrayOf(0, 0, 0, 1) + ppsRaw
        val aacAsc = byteArrayOf(0x14, 0x08) // 16kHz mono AAC-LC

        val mp4File = File(context.cacheDir, "test_valid_av.mp4").apply { delete() }
        FileOutputStream(mp4File).use { fos ->
            val muxer = recorder.createMp4Muxer(fos)
            val vTrack = muxer.addTrack(
                recorder.buildVideoTrackFormat(
                    isHevc = false,
                    width = 1280,
                    height = 720,
                    vpsWithStartCode = null,
                    spsWithStartCode = spsAnnexB,
                    ppsWithStartCode = ppsAnnexB
                )
            )
            val aTrack = muxer.addTrack(
                recorder.buildAacTrackFormat(
                    sampleRate = 16000,
                    channelCount = 1,
                    audioSpecificConfig = aacAsc
                )
            )
            muxer.writeSampleData(vTrack, ByteBuffer.wrap(avccSample), BufferInfo(0L, avccSample.size, C.BUFFER_FLAG_KEY_FRAME))
            val pFrame = recorder.encodeAvccSample(listOf(byteArrayOf(0x41, 0x9A.toByte(), 0x22, 0x33)))
            muxer.writeSampleData(vTrack, ByteBuffer.wrap(pFrame), BufferInfo(33_333L, pFrame.size, 0))
            val aacFrame = byteArrayOf(0x21, 0x10, 0x04, 0x60, 0x8C.toByte(), 0x1C)
            muxer.writeSampleData(aTrack, ByteBuffer.wrap(aacFrame), BufferInfo(0L, aacFrame.size, 0))
            muxer.close()
        }

        val mp4Bytes = mp4File.readBytes()
        val ascii = String(mp4Bytes, Charsets.ISO_8859_1)
        assertTrue(mp4File.length() > 200L)
        assertTrue("Expected ftyp box", ascii.contains("ftyp"))
        assertTrue("Expected moov box", ascii.contains("moov"))
        assertTrue("Expected avc1 video sample entry", ascii.contains("avc1"))
        assertTrue("Expected avcC decoder config box", ascii.contains("avcC"))
        assertTrue("Expected mp4a audio sample entry", ascii.contains("mp4a"))
        assertTrue("Expected mdat media data box", ascii.contains("mdat"))
        mp4File.delete()
    }

    @Test
    fun `recorder tracks concurrent multi-camera sessions independently`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = RtspStreamRecorder(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val server1 = ServerSocket(0)
        val server2 = ServerSocket(0)
        val socketsToClose = mutableListOf<java.net.Socket>()
        thread {
            runCatching {
                val s = server1.accept()
                socketsToClose.add(s)
                Thread.sleep(2000)
            }
        }
        thread {
            runCatching {
                val s = server2.accept()
                socketsToClose.add(s)
                Thread.sleep(2000)
            }
        }

        try {
            recorder.startRecording(
                scope = scope,
                cameraId = 101L,
                cameraName = "Front",
                rtspUrl = "rtsp://127.0.0.1:${server1.localPort}/cam1",
                audioEnabled = true
            ) {}
            recorder.startRecording(
                scope = scope,
                cameraId = 202L,
                cameraName = "Garage",
                rtspUrl = "rtsp://127.0.0.1:${server2.localPort}/cam2",
                audioEnabled = true
            ) {}

            Thread.sleep(200)
            assertTrue(recorder.isRecording(101L))
            assertTrue(recorder.isRecording(202L))
            val stateMap = recorder.sessionsState.value
            assertTrue(stateMap.containsKey(101L))
            assertTrue(stateMap.containsKey(202L))

            recorder.stopRecording(101L)
            assertEquals(RecordingSessionState.Stopping, recorder.sessionsState.value[101L])
            assertTrue(recorder.sessionsState.value[202L] != RecordingSessionState.Stopping)
            assertFalse(recorder.sessionsState.value.isEmpty())
        } finally {
            recorder.stopAllRecordings()
            socketsToClose.forEach { runCatching { it.close() } }
            runCatching { server1.close() }
            runCatching { server2.close() }
            scope.cancel()
        }
    }

    @Test
    fun `access unit assembler combines multi-slice frames and detects H264 intra slices`() {
        val assembler = RtspStreamRecorder.VideoAccessUnitAssembler(isHevc = false)
        val depacketizer = RtspStreamRecorder.H264RtpDepacketizer()

        // Multi-slice IDR sent across two FU-A NALs sharing the same RTP timestamp
        val slice1Part1 = byteArrayOf(0x7C.toByte(), 0x85.toByte(), 0x88.toByte(), 0x84.toByte())
        val slice1Part2 = byteArrayOf(0x7C.toByte(), 0x45.toByte(), 0x21, 0x0F)
        val slice2Single = byteArrayOf(0x65.toByte(), 0x00, 0x99.toByte(), 0x11)

        val n1 = depacketizer.feed(slice1Part1, marker = false, rtpTimestamp = 9000L)
        assertTrue(n1.isEmpty())
        val n2 = depacketizer.feed(slice1Part2, marker = false, rtpTimestamp = 9000L)
        assertEquals(1, n2.size)
        val aus1 = assembler.feed(9000L, marker = false, n2)
        assertTrue("Should wait until marker or timestamp change", aus1.isEmpty())

        val n3 = depacketizer.feed(slice2Single, marker = true, rtpTimestamp = 9000L)
        val aus2 = assembler.feed(9000L, marker = true, n3)
        assertEquals("Expected one combined AU for multi-slice IDR", 1, aus2.size)
        assertTrue(aus2[0].isKeyframe)
        assertEquals(2, aus2[0].nals.size)

        // Check non-IDR H.264 I-slice detection (nalType = 1, first_mb_in_slice = 0 -> '1', slice_type = 7 -> '0001000' => 0x88)
        val intraNonIdrNal = byteArrayOf(0x41, 0x88.toByte(), 0x00)
        assertTrue(RtspStreamRecorder.isH264IntraSlice(intraNonIdrNal))
        // P-slice (first_mb=0 -> '1', slice_type=0 -> '1' => 0xC0)
        val pSliceNal = byteArrayOf(0x41, 0xC0.toByte(), 0x00)
        assertFalse(RtspStreamRecorder.isH264IntraSlice(pSliceNal))
    }

    @Test
    fun `recorder prefers in-band SPS over stale SDP sprop and Mp4Extractor demuxes combined AVCC keyframe`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = RtspStreamRecorder(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Stale SDP SPS (Baseline 320x240) vs real in-band SPS (Main 1280x720)
        val realSps = Base64.decode("Z00AKp2oHgCJ+WbgICAoAAADAAgAAAMBlCA=", Base64.DEFAULT) // 1280x720
        val realPps = Base64.decode("aO48gA==", Base64.DEFAULT)
        val staleSprop = "${Base64.encodeToString(byteArrayOf(0x42, 0x00, 0x1E, 0xE2.toByte(), 0x90.toByte(), 0x14, 0x07, 0xB2.toByte()), Base64.NO_WRAP)},${Base64.encodeToString(realPps, Base64.NO_WRAP)}"

        val slice1 = ByteArray(200) { idx -> if (idx == 0) 0x65.toByte() else ((idx % 120) + 1).toByte() }
        val slice2 = ByteArray(200) { idx -> if (idx == 0) 0x65.toByte() else ((idx % 120) + 2).toByte() }

        val server = ServerSocket(0)
        val completion = java.util.concurrent.CountDownLatch(1)
        var completedResult: Result<com.example.media.CompletedRecording>? = null

        val serverThread = thread {
            runCatching {
                val client = server.accept()
                val reader = client.getInputStream().bufferedReader()
                val out = client.getOutputStream()

                fun readHeaders(): Pair<String, String> {
                    val reqLine = reader.readLine() ?: return "" to ""
                    var cseq = "1"
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("CSeq:", ignoreCase = true)) {
                            cseq = line.substringAfter(":").trim()
                        }
                    }
                    return reqLine to cseq
                }

                // OPTIONS
                val (_, cseq1) = readHeaders()
                out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq1\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n".toByteArray())
                out.flush()

                // DESCRIBE with stale SDP sprop-parameter-sets
                val (_, cseq2) = readHeaders()
                val sdp = buildString {
                    append("v=0\r\n")
                    append("o=- 1 1 IN IP4 127.0.0.1\r\n")
                    append("s=CamTest\r\n")
                    append("t=0 0\r\n")
                    append("a=control:*\r\n")
                    append("m=video 0 RTP/AVP 96\r\n")
                    append("a=rtpmap:96 H264/90000\r\n")
                    append("a=fmtp:96 packetization-mode=1;sprop-parameter-sets=$staleSprop\r\n")
                    append("a=control:trackID=0\r\n")
                    append("m=audio 0 RTP/AVP 97\r\n")
                    append("a=rtpmap:97 MPEG4-GENERIC/16000/1\r\n")
                    append("a=fmtp:97 config=1408;mode=AAC-hbr\r\n")
                    append("a=control:trackID=1\r\n")
                }
                out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq2\r\nContent-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp".toByteArray())
                out.flush()

                // SETUP video
                val (_, cseq3) = readHeaders()
                out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq3\r\nSession: 998877\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n".toByteArray())
                out.flush()

                // SETUP audio
                val (_, cseq4) = readHeaders()
                out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq4\r\nSession: 998877\r\nTransport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n\r\n".toByteArray())
                out.flush()

                // PLAY
                val (_, cseq5) = readHeaders()
                out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq5\r\nSession: 998877\r\n\r\n".toByteArray())
                out.flush()

                fun writeInterleavedRtp(channel: Int, seq: Int, ts: Long, marker: Boolean, payload: ByteArray) {
                    val rtpLen = 12 + payload.size
                    val rtp = ByteArray(rtpLen)
                    rtp[0] = 0x80.toByte()
                    rtp[1] = ((if (marker) 0x80 else 0x00) or 96).toByte()
                    rtp[2] = ((seq ushr 8) and 0xFF).toByte()
                    rtp[3] = (seq and 0xFF).toByte()
                    rtp[4] = ((ts ushr 24) and 0xFF).toByte()
                    rtp[5] = ((ts ushr 16) and 0xFF).toByte()
                    rtp[6] = ((ts ushr 8) and 0xFF).toByte()
                    rtp[7] = (ts and 0xFF).toByte()
                    System.arraycopy(payload, 0, rtp, 12, payload.size)

                    val frame = ByteArray(4 + rtpLen)
                    frame[0] = 0x24
                    frame[1] = channel.toByte()
                    frame[2] = ((rtpLen ushr 8) and 0xFF).toByte()
                    frame[3] = (rtpLen and 0xFF).toByte()
                    System.arraycopy(rtp, 0, frame, 4, rtpLen)
                    out.write(frame)
                }

                // Multi-slice IDR #1 BEFORE in-band SPS/PPS arrives (dropped in favor of fresher IDR #2 matching in-band SPS/PPS)
                writeInterleavedRtp(0, 1, 9000L, false, slice1)
                writeInterleavedRtp(0, 2, 9000L, true, slice2)
                val pSlice = ByteArray(48) { idx ->
                    when (idx) {
                        0 -> 0x41.toByte()
                        1 -> 0xC0.toByte() // first_mb_in_slice=0, slice_type=P
                        else -> 0x22.toByte()
                    }
                }
                val pSliceCont = ByteArray(48) { idx ->
                    when (idx) {
                        0 -> 0x41.toByte()
                        1 -> 0x50.toByte() // first_mb_in_slice=1, slice_type=P
                        else -> 0x23.toByte()
                    }
                }
                writeInterleavedRtp(0, 3, 12000L, true, pSlice)
                // Now in-band 1920x1080 SPS & PPS arrive right before Keyframe #2
                writeInterleavedRtp(0, 4, 15000L, false, realSps)
                writeInterleavedRtp(0, 5, 15000L, false, realPps)
                writeInterleavedRtp(0, 6, 15000L, false, slice1)
                writeInterleavedRtp(0, 7, 15000L, true, slice2)
                // Multi-slice P-frame after Keyframe #2
                writeInterleavedRtp(0, 8, 18000L, false, pSlice)
                writeInterleavedRtp(0, 9, 18000L, true, pSliceCont)
                // AAC audio frame (RFC 3640 AU-headers-length = 16 bits, 1 AU of 6 bytes)
                val aacPayload = byteArrayOf(0x00, 0x10, 0x00, (6 shl 3).toByte(), 0x21, 0x10, 0x04, 0x60, 0x8C.toByte(), 0x1C)
                writeInterleavedRtp(2, 1, 1600L, true, aacPayload)
                out.flush()
                Thread.sleep(350)
                client.close()
            }
        }

        try {
            recorder.startRecording(
                scope = scope,
                cameraId = 303L,
                cameraName = "SliceTestCam",
                rtspUrl = "rtsp://127.0.0.1:${server.localPort}/live",
                audioEnabled = true,
                preferTcp = true
            ) { res ->
                completedResult = res
                completion.countDown()
            }

            assertTrue("Recording did not finish in time", completion.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val recorded = completedResult!!.getOrThrow()
            val mp4File = File(recorded.localFilePath)
            assertTrue(mp4File.exists() && mp4File.length() > 200L)
            assertTrue("Expected 1920x1080 resolution label, got ${recorded.resolution}", recorded.resolution.startsWith("1920x1080"))

            // Demux with Media3 Mp4Extractor and verify actual tracks and sample framing
            val fileBytes = mp4File.readBytes()
            val extractedFormats = mutableMapOf<Int, Format>()
            val extractedSamples = mutableMapOf<Int, MutableList<Pair<Int, ByteArray>>>()

            val dataReader = object : DataReader {
                var pos = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (pos >= fileBytes.size) return C.RESULT_END_OF_INPUT
                    val n = minOf(length, fileBytes.size - pos)
                    System.arraycopy(fileBytes, pos, buffer, offset, n)
                    pos += n
                    return n
                }
            }

            val extractor = Mp4Extractor()
            val extractorInput = DefaultExtractorInput(dataReader, 0L, fileBytes.size.toLong())
            assertTrue("Mp4Extractor failed to sniff recorded file", extractor.sniff(extractorInput))

            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val buf = java.io.ByteArrayOutputStream()
                    return object : TrackOutput {
                        override fun format(format: Format) {
                            extractedFormats[id] = format
                        }
                        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                            val tmp = ByteArray(length)
                            var readTotal = 0
                            while (readTotal < length) {
                                val r = input.read(tmp, readTotal, length - readTotal)
                                if (r <= 0) break
                                readTotal += r
                            }
                            buf.write(tmp, 0, readTotal)
                            return readTotal
                        }
                        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                            val tmp = ByteArray(length)
                            data.readBytes(tmp, 0, length)
                            buf.write(tmp)
                        }
                        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                            val all = buf.toByteArray()
                            val sampleSlice = if (all.size >= size) all.copyOfRange(all.size - size, all.size) else all
                            buf.reset()
                            extractedSamples.getOrPut(id) { mutableListOf() }.add(flags to sampleSlice)
                        }
                    }
                }
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })

            var readPos = 0L
            val posHolder = PositionHolder()
            var loops = 0
            while (loops++ < 200) {
                val loopReader = object : DataReader {
                    var p = readPos.toInt()
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (p >= fileBytes.size) return C.RESULT_END_OF_INPUT
                        val n = minOf(length, fileBytes.size - p)
                        System.arraycopy(fileBytes, p, buffer, offset, n)
                        p += n
                        return n
                    }
                }
                val loopInput = DefaultExtractorInput(loopReader, readPos, fileBytes.size.toLong())
                val status = extractor.read(loopInput, posHolder)
                if (status == Extractor.RESULT_SEEK) {
                    readPos = posHolder.position
                } else if (status == Extractor.RESULT_END_OF_INPUT) {
                    break
                } else {
                    readPos = loopInput.position
                }
            }
            extractor.release()

            val videoEntry = extractedFormats.entries.firstOrNull { it.value.sampleMimeType == MimeTypes.VIDEO_H264 }
            assertNotNull("Expected H264 video track in MP4", videoEntry)
            assertEquals("Expected in-band 1920 width instead of stale SDP", 1920, videoEntry!!.value.width)
            assertEquals("Expected in-band 1080 height instead of stale SDP", 1080, videoEntry.value.height)

            val videoSamples = extractedSamples[videoEntry.key].orEmpty()
            assertEquals("Expected 2 complete video frame AUs (multi-slice IDR + multi-slice P-frame)", 2, videoSamples.size)
            val firstSampleFlags = videoSamples[0].first
            val firstSampleData = videoSamples[0].second
            assertTrue("First sample must be marked KEY_FRAME", (firstSampleFlags and C.BUFFER_FLAG_KEY_FRAME) != 0)
            // Mp4Extractor parses both 4-byte AVCC length prefixes and replaces them with 00 00 00 01 Annex-B start codes for MediaCodec
            assertEquals(4 + slice1.size + 4 + slice2.size, firstSampleData.size)
            assertEquals(1, firstSampleData[3].toInt() and 0xFF)
            assertEquals(0x65.toByte(), firstSampleData[4])
            assertEquals(1, firstSampleData[4 + slice1.size + 3].toInt() and 0xFF)
            assertEquals(0x65.toByte(), firstSampleData[4 + slice1.size + 4])
            // Second sample must combine two 48-byte P-slices into one AU without KEY_FRAME flag
            assertTrue("P-frame must not be marked KEY_FRAME", (videoSamples[1].first and C.BUFFER_FLAG_KEY_FRAME) == 0)
            assertEquals(4 + 48 + 4 + 48, videoSamples[1].second.size)

            val audioEntry = extractedFormats.entries.firstOrNull { it.value.sampleMimeType == MimeTypes.AUDIO_AAC }
            assertNotNull("Expected AAC audio track in MP4", audioEntry)
            assertEquals(16000, audioEntry!!.value.sampleRate)
            assertEquals(1, extractedSamples[audioEntry.key]?.size ?: 0)

            mp4File.delete()
        } finally {
            recorder.stopAllRecordings()
            runCatching { server.close() }
            runCatching { serverThread.join(1000) }
            scope.cancel()
        }
    }

    @Test
    fun `access unit assembler splits constant-timestamp markerless frames by first-slice header and filters RTCP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = RtspStreamRecorder(context)

        // Verify RTCP SR (PT=200 -> byte1 = 0xC8) and RR (PT=201 -> byte1 = 0xC9) are rejected even when length >= 12
        val rtcpSr = ByteArray(28).apply {
            this[0] = 0x80.toByte()
            this[1] = 0xC8.toByte()
        }
        val rtcpRr = ByteArray(28).apply {
            this[0] = 0x81.toByte()
            this[1] = 0xC9.toByte()
        }
        val validRtp = ByteArray(20).apply {
            this[0] = 0x80.toByte()
            this[1] = 0xE0.toByte() // marker=1, pt=96
            this[12] = 0x65.toByte()
        }
        assertNull(recorder.parseRtpPacket(rtcpSr))
        assertNull(recorder.parseRtpPacket(rtcpRr))
        assertNotNull(recorder.parseRtpPacket(validRtp))

        // H.264: IDR slice0 (first_mb=0 -> 0x88) + continuation slice1 (first_mb=1 -> 0x40), then P-frame slice0 (0xC0), all with marker=false & same RTP ts
        val h264Assembler = RtspStreamRecorder.VideoAccessUnitAssembler(isHevc = false)
        val h264IdrSlice0 = byteArrayOf(0x65, 0x88.toByte(), 0x11)
        val h264IdrSlice1 = byteArrayOf(0x65, 0x40, 0x22)
        val h264PSlice0 = byteArrayOf(0x41, 0xC0.toByte(), 0x33)

        assertTrue(h264Assembler.feed(5000L, marker = false, listOf(h264IdrSlice0)).isEmpty())
        assertTrue("Continuation slice should remain in same AU", h264Assembler.feed(5000L, marker = false, listOf(h264IdrSlice1)).isEmpty())
        val flushedOnNextPic = h264Assembler.feed(5000L, marker = false, listOf(h264PSlice0))
        assertEquals("Expected completed IDR AU when next frame's first slice arrived", 1, flushedOnNextPic.size)
        assertTrue(flushedOnNextPic[0].isKeyframe)
        assertEquals(2, flushedOnNextPic[0].nals.size)
        val finalP = h264Assembler.flushPending()
        assertNotNull(finalP)
        assertFalse(finalP!!.isKeyframe)
        assertEquals(1, finalP.nals.size)

        // HEVC: IDR slice0 (first_slice_segment_in_pic_flag=1 -> nal[2] bit7=1) + slice1 (bit7=0), then Trail slice0 (bit7=1)
        val hevcAssembler = RtspStreamRecorder.VideoAccessUnitAssembler(isHevc = true)
        val hevcIdrSlice0 = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x01) // nalType=19 (IDR_W_RADL)
        val hevcIdrSlice1 = byteArrayOf(0x26, 0x01, 0x20, 0x02) // same picture continuation slice
        val hevcTrailSlice0 = byteArrayOf(0x02, 0x01, 0xD0.toByte(), 0x03) // nalType=1 (TRAIL_R), new picture

        assertTrue(hevcAssembler.feed(9000L, marker = false, listOf(hevcIdrSlice0)).isEmpty())
        assertTrue(hevcAssembler.feed(9000L, marker = false, listOf(hevcIdrSlice1)).isEmpty())
        val hevcCompleted = hevcAssembler.feed(9000L, marker = false, listOf(hevcTrailSlice0))
        assertEquals(1, hevcCompleted.size)
        assertTrue(hevcCompleted[0].isKeyframe)
        assertEquals(2, hevcCompleted[0].nals.size)
    }

    @Test
    fun `FU-A depacketizer preserves multi-packet IDR keyframes across timestamp drift and missing end flag`() {
        val depacketizer = RtspStreamRecorder.H264RtpDepacketizer()
        val assembler = RtspStreamRecorder.VideoAccessUnitAssembler(isHevc = false)

        // IDR fragmented via FU-A across 3 packets where camera clock bumps RTP timestamp mid-frame
        val fuStart = byteArrayOf(0x7C.toByte(), 0x85.toByte(), 0x88.toByte(), 0x11, 0x22)
        val fuMid = byteArrayOf(0x7C.toByte(), 0x05.toByte(), 0x33, 0x44)
        val fuEnd = byteArrayOf(0x7C.toByte(), 0x45.toByte(), 0x55, 0x66)

        assertTrue(depacketizer.feed(fuStart, marker = false, rtpTimestamp = 9000L).isEmpty())
        assertTrue(depacketizer.feed(fuMid, marker = false, rtpTimestamp = 9090L).isEmpty())
        val nals = depacketizer.feed(fuEnd, marker = true, rtpTimestamp = 9180L)
        assertEquals("Expected complete reassembled IDR NAL despite mid-frame timestamp drift", 1, nals.size)
        assertEquals(0x65.toByte(), nals[0][0])

        val aus = assembler.feed(9180L, marker = true, nals)
        assertEquals(1, aus.size)
        assertTrue(aus[0].isKeyframe)

        // Also verify IDR FU-A without isEnd/marker is flushed cleanly when next P-frame arrives
        val fuStartNoEnd = byteArrayOf(0x7C.toByte(), 0x85.toByte(), 0x88.toByte(), 0x77)
        val nextPFrame = byteArrayOf(0x41.toByte(), 0xC0.toByte(), 0x10)
        assertTrue(depacketizer.feed(fuStartNoEnd, marker = false, rtpTimestamp = 12000L).isEmpty())
        val flushedBoth = depacketizer.feed(nextPFrame, marker = true, rtpTimestamp = 15000L)
        assertEquals(2, flushedBoth.size)
        assertEquals(0x65.toByte(), flushedBoth[0][0])
        assertEquals(0x41.toByte(), flushedBoth[1][0])
    }
}


