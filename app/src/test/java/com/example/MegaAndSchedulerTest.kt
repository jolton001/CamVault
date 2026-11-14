package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.local.CamVaultDatabase
import com.example.data.local.CloudProviderType
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.local.RECORD_INTERVAL_OPTIONS_MINUTES
import com.example.data.local.RecordingEntity
import com.example.data.local.UploadStatus
import com.example.data.local.effectiveRecordIntervalMinutes
import com.example.data.repository.CamVaultRepository
import com.example.network.cloud.CloudStorageNaming
import com.example.network.cloud.CloudStorageUploader
import com.example.network.cloud.MegaCloudClient
import com.example.network.cloud.TenMinuteCloudSyncScheduler
import com.example.ui.viewmodel.CloudStorageViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MegaAndSchedulerTest {

    private lateinit var context: Context
    private lateinit var db: CamVaultDatabase
    private lateinit var repository: CamVaultRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runCatching {
            if (!WorkManager.isInitialized()) {
                WorkManager.initialize(context, Configuration.Builder().build())
            }
        }
        db = Room.inMemoryDatabaseBuilder(context, CamVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CamVaultRepository(
            appContext = context,
            cameraDao = db.cameraDao(),
            recordingDao = db.recordingDao(),
            cloudConfigDao = db.cloudStorageConfigDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun megaCryptoV2AndRsaSessionRoundTrip() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val (encKey1, uh1) = MegaCloudClient.deriveLoginKeysV2("SuperSecret123!", salt)
        val (encKey2, uh2) = MegaCloudClient.deriveLoginKeysV2("SuperSecret123!", salt)
        assertEquals(16, encKey1.size)
        assertArrayEquals(encKey1, encKey2)
        assertEquals(uh1, uh2)

        // Build real RSA private key -> pack into MEGA privk format -> encrypt csid -> decrypt via MegaCloudClient
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(1024)
        val kp = kpg.generateKeyPair()
        val priv = kp.private as RSAPrivateCrtKey
        val p = priv.primeP
        val q = priv.primeQ
        val d = priv.privateExponent
        val u = p.modInverse(q)

        val privkPacked = packMpints(p, q, d, u)
        val privkPaddedLen = ((privkPacked.size + 15) / 16) * 16
        val privkPadded = privkPacked.copyOf(privkPaddedLen)
        val masterKey = ByteArray(16) { (0xA0 + it).toByte() }
        val encPrivk = MegaCloudClient.aesEcbEncryptNoPadding(privkPadded, masterKey)

        val expectedSid = ByteArray(43) { (0x21 + it).toByte() }
        val paddedMsg = ByteArray(128).apply {
            this[0] = 0x00 // ensure m < n
            System.arraycopy(expectedSid, 0, this, 1, 43)
        }
        val modN = p.multiply(q)
        val mInt = BigInteger(1, paddedMsg)
        val cInt = mInt.modPow(priv.publicExponent, modN)
        val csidMpint = packMpints(cInt)

        val unpacked = MegaCloudClient.unpackRsaPrivateKey(
            MegaCloudClient.aesEcbDecryptNoPadding(encPrivk, masterKey)
        )
        val decryptedSid = MegaCloudClient.decryptSessionId(csidMpint, unpacked)
        assertArrayEquals(expectedSid, decryptedSid)
    }

    @Test
    fun megaChunkSizesAndCtrEncryption() {
        val sizes = MegaCloudClient.computeChunkSizes(1_500_000L)
        assertEquals(listOf(131072, 262144, 393216, 524288, 189280), sizes)
        assertEquals(1_500_000L, sizes.sum().toLong())

        val key = ByteArray(16) { (it * 3).toByte() }
        val nonce = ByteArray(8) { (it + 7).toByte() }
        val plain = "Hello MEGA encrypted video chunk!".toByteArray()
        val cipher = MegaCloudClient.encryptAesCtrChunk(plain, key, nonce, 131072L)

        // Verify independent AES-CTR decrypt
        val iv = ByteBuffer.allocate(16).put(nonce).putLong(131072L / 16L).array()
        val dec = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(cipher)
        assertArrayEquals(plain, dec)
    }

    @Test
    fun megaHashcashChallengeSolverAndHttp402Retry() = runTest {
        val rawToken = ByteArray(48) { (it + 11).toByte() }
        val tokenB64 = MegaCloudClient.encodeMegaBase64(rawToken)
        // easiness=224 -> (224>>6)=3 -> (3*7+3)=24; (224&63)=32 -> 65<<24 = 0x41000000 (~1 in 4 hashes)
        val challenge = "1:224:1731490000:$tokenB64"
        val proof = MegaCloudClient.solveHashcashChallenge(challenge)
        val parts = proof.split(':')
        assertEquals(3, parts.size)
        assertEquals("1", parts[0])
        assertEquals(tokenB64, parts[1])

        val prefixBytes = MegaCloudClient.decodeMegaBase64(parts[2])
        assertEquals(4, prefixBytes.size)
        val verifyBuf = ByteArray(4 + 262144 * 48)
        System.arraycopy(prefixBytes, 0, verifyBuf, 0, 4)
        for (i in 0 until 262144) {
            System.arraycopy(rawToken, 0, verifyBuf, 4 + i * 48, 48)
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifyBuf)
        val head = ((digest[0].toLong() and 0xFFL) shl 24) or
            ((digest[1].toLong() and 0xFFL) shl 16) or
            ((digest[2].toLong() and 0xFFL) shl 8) or
            (digest[3].toLong() and 0xFFL)
        val threshold = ((((224 and 63).toLong() shl 1) + 1L) shl ((224 shr 6) * 7 + 3))
        assertTrue("Digest head $head should be <= threshold $threshold", head <= threshold)

        // Verify MegaCloudClient automatically retries HTTP 402 with solved X-Hashcash header
        val salt = ByteArray(16) { 0x33.toByte() }
        val (passKey, _) = MegaCloudClient.deriveLoginKeysV2("pass123", salt)
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        val kp = kpg.generateKeyPair()
        val priv = kp.private as RSAPrivateCrtKey
        val masterKey = ByteArray(16) { 0x77.toByte() }
        val encMaster = MegaCloudClient.encodeMegaBase64(MegaCloudClient.aesEcbEncryptNoPadding(masterKey, passKey))
        val privkPacked = packMpints(priv.primeP, priv.primeQ, priv.privateExponent, priv.primeP.modInverse(priv.primeQ))
        val encPrivk = MegaCloudClient.encodeMegaBase64(
            MegaCloudClient.aesEcbEncryptNoPadding(privkPacked.copyOf(((privkPacked.size + 15) / 16) * 16), masterKey)
        )
        val rawSid = ByteArray(43).also { SecureRandom().nextBytes(it); it[0] = 0x51 }
        val cInt = BigInteger(1, ByteArray(128).apply { System.arraycopy(rawSid, 0, this, 1, 43) })
            .modPow(priv.publicExponent, priv.primeP.multiply(priv.primeQ))
        val csidB64 = MegaCloudClient.encodeMegaBase64(packMpints(cInt))

        var sawChallengeHeader = false
        val server = ServerSocket(0)
        val json = Json { ignoreUnknownKeys = true }
        val srvThread = thread(isDaemon = true) {
            var count = 0
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                sock.use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    reader.readLine() ?: return@use
                    var len = 0
                    var hashcashSent = ""
                    while (true) {
                        val h = reader.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
                        if (h.startsWith("X-Hashcash:", true)) hashcashSent = h.substringAfter(':').trim()
                    }
                    val chars = CharArray(len)
                    var r = 0
                    while (r < len) {
                        val n = reader.read(chars, r, len - r); if (n <= 0) break; r += n
                    }
                    val cmd = json.parseToJsonElement(String(chars, 0, r)).jsonArray[0].jsonObject
                    val out = s.getOutputStream()
                    if (count == 0 && hashcashSent.isBlank()) {
                        count++
                        out.write("HTTP/1.1 402 Payment Required\r\nX-Hashcash: $challenge\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                    } else {
                        if (hashcashSent.isNotBlank()) sawChallengeHeader = true
                        val body = when (cmd["a"]?.jsonPrimitive?.content) {
                            "us0" -> """[{"v":2,"s":"${MegaCloudClient.encodeMegaBase64(salt)}"}]"""
                            "us" -> """[{"k":"$encMaster","csid":"$csidB64","privk":"$encPrivk","u":"u1"}]"""
                            "f" -> """[{"f":[{"h":"ROOT","p":"","t":2,"a":"","k":""}]}]"""
                            else -> "[-2]"
                        }.toByteArray()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }
        try {
            val client = MegaCloudClient(OkHttpClient())
            val (code, _) = client.testConnection("http://127.0.0.1:${server.localPort}", "t@example.com", "pass123", "")
            assertEquals(200, code)
            assertTrue("Expected retry with X-Hashcash header", sawChallengeHeader)
        } finally {
            runCatching { server.close() }
            runCatching { srvThread.interrupt() }
        }
    }

    @Test
    fun megaClientTestAndUploadEndToEndOverHttp() = runTest {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(1024)
        val kp = kpg.generateKeyPair()
        val priv = kp.private as RSAPrivateCrtKey
        val masterKey = ByteArray(16) { (0x10 + it).toByte() }
        val salt = ByteArray(16) { (0x55 + it).toByte() }
        val (passKey, expectedUh) = MegaCloudClient.deriveLoginKeysV2("megaPass99", salt)
        val encMasterKeyB64 = MegaCloudClient.encodeMegaBase64(
            MegaCloudClient.aesEcbEncryptNoPadding(masterKey, passKey)
        )

        val privkPacked = packMpints(priv.primeP, priv.primeQ, priv.privateExponent, priv.primeP.modInverse(priv.primeQ))
        val privkPadded = privkPacked.copyOf(((privkPacked.size + 15) / 16) * 16)
        val encPrivkB64 = MegaCloudClient.encodeMegaBase64(
            MegaCloudClient.aesEcbEncryptNoPadding(privkPadded, masterKey)
        )

        val rawSid = ByteArray(43).also { SecureRandom().nextBytes(it); it[0] = 0x42 }
        val mBytes = ByteArray(128).apply { System.arraycopy(rawSid, 0, this, 1, 43) }
        val cInt = BigInteger(1, mBytes).modPow(priv.publicExponent, priv.primeP.multiply(priv.primeQ))
        val csidB64 = MegaCloudClient.encodeMegaBase64(packMpints(cInt))

        var uploadedCipherBytes: ByteArray? = null
        var createdFolderName = ""
        var createdFileAttrsEnc = ""
        var createdFileKeyEnc = ""

        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val json = Json { ignoreUnknownKeys = true }

        val serverThread = thread(isDaemon = true) {
            while (!serverSocket.isClosed) {
                val sock = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                sock.use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestLine = input.readLine() ?: return@use
                    var contentLength = 0
                    while (true) {
                        val h = input.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = input.read(bodyChars, readTotal, contentLength - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    val bodyBytes = String(bodyChars, 0, readTotal).toByteArray(Charsets.ISO_8859_1)

                    val respBody: ByteArray = if (requestLine.startsWith("POST /cs")) {
                        val cmd = json.parseToJsonElement(String(bodyBytes, Charsets.UTF_8)).jsonArray[0].jsonObject
                        val respStr = when (cmd["a"]?.jsonPrimitive?.content) {
                            "us0" -> """[{"v":2,"s":"${MegaCloudClient.encodeMegaBase64(salt)}"}]"""
                            "us" -> {
                                assertEquals(expectedUh, cmd["uh"]?.jsonPrimitive?.content)
                                """[{"k":"$encMasterKeyB64","csid":"$csidB64","privk":"$encPrivkB64","u":"user123"}]"""
                            }
                            "f" -> """[{"f":[{"h":"ROOT_H","p":"","t":2,"a":"","k":""}]}]"""
                            "u" -> """[{"p":"http://127.0.0.1:$port/upload_target"}]"""
                            "p" -> {
                                val node = cmd["n"]!!.jsonArray[0].jsonObject
                                val nType = node["t"]!!.jsonPrimitive.content.toInt()
                                if (nType == 1) {
                                    // Decrypt folder name using masterKey to verify encryption!
                                    val fKey = MegaCloudClient.aesEcbDecryptNoPadding(
                                        MegaCloudClient.decodeMegaBase64(node["k"]!!.jsonPrimitive.content),
                                        masterKey
                                    )
                                    val attrDec = MegaCloudClient.aesCbcDecryptZeroIv(
                                        MegaCloudClient.decodeMegaBase64(node["a"]!!.jsonPrimitive.content),
                                        fKey
                                    ).toString(Charsets.UTF_8).trimEnd('\u0000')
                                    createdFolderName = attrDec
                                    """[{"f":[{"h":"FOLDER_H1","p":"ROOT_H","t":1}]}]"""
                                } else {
                                    createdFileAttrsEnc = node["a"]!!.jsonPrimitive.content
                                    createdFileKeyEnc = node["k"]!!.jsonPrimitive.content
                                    """[{"f":[{"h":"MEGAFILE99","p":"FOLDER_H1","t":0}]}]"""
                                }
                            }
                            else -> "[-2]"
                        }
                        respStr.toByteArray(Charsets.UTF_8)
                    } else if (requestLine.startsWith("POST /upload_target/")) {
                        uploadedCipherBytes = bodyBytes
                        "completion_tok_123456".toByteArray(Charsets.UTF_8)
                    } else {
                        "[-2]".toByteArray(Charsets.UTF_8)
                    }

                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${respBody.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII)
                    )
                    out.write(respBody)
                    out.flush()
                }
            }
        }

        try {
            val client = MegaCloudClient(OkHttpClient())
            val endpoint = "http://127.0.0.1:$port"

            val (code, msg) = client.testConnection(
                endpointUrl = endpoint,
                email = "User@Example.com",
                password = "megaPass99",
                folderPrefix = "CamVault/Clips"
            )
            assertEquals(200, code)
            assertTrue(msg.contains("MEGA Drive connected"))

            val tempMp4 = File.createTempFile("mega_test_clip", ".mp4", context.cacheDir).apply {
                writeBytes(ByteArray(2048) { (it and 0x7F).toByte() })
            }
            val progressReports = mutableListOf<Int>()
            val uploadRes = client.uploadFile(
                endpointUrl = endpoint,
                email = "user@example.com",
                password = "megaPass99",
                folderPrefix = "CamVault",
                file = tempMp4,
                remoteFileName = "cam_10min_clip.mp4",
                onProgress = { progressReports.add(it) }
            )

            assertEquals("https://mega.nz/file/MEGAFILE99", uploadRes.remoteUrl)
            assertEquals(2048L, uploadRes.bytesUploaded)
            assertTrue(progressReports.contains(100))
            assertTrue(createdFolderName.contains("\"CamVault\""))
            assertNotNull(uploadedCipherBytes)

            // Verify server-received ciphertext + encrypted key + attributes decrypt back to exact original bytes!
            val decFullKey = MegaCloudClient.aesEcbDecryptNoPadding(
                MegaCloudClient.decodeMegaBase64(createdFileKeyEnc),
                masterKey
            )
            val fileAesKey = ByteArray(16) { i -> (decFullKey[i].toInt() xor decFullKey[i + 16].toInt()).toByte() }
            val fileNonce = decFullKey.copyOfRange(16, 24)
            val attrText = MegaCloudClient.aesCbcDecryptZeroIv(
                MegaCloudClient.decodeMegaBase64(createdFileAttrsEnc),
                fileAesKey
            ).toString(Charsets.UTF_8).trimEnd('\u0000')
            assertTrue(attrText.contains("cam_10min_clip.mp4"))

            val decryptedFile = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(fileAesKey, "AES"),
                    IvParameterSpec(ByteBuffer.allocate(16).put(fileNonce).putLong(0L).array())
                )
            }.doFinal(uploadedCipherBytes)
            assertArrayEquals(tempMp4.readBytes(), decryptedFile)
            tempMp4.delete()
        } finally {
            runCatching { serverSocket.close() }
            runCatching { serverThread.interrupt() }
        }
    }

    @Test
    fun tenMinutePeriodicSyncQueuesPendingAndFailedRecordings() = runTest {
        val megaCfg = CloudStorageConfigEntity(
            name = "MEGA Drive Backup",
            providerType = CloudProviderType.MEGA,
            endpointUrl = MegaCloudClient.DEFAULT_ENDPOINT,
            folderPrefix = "CamVault/Recordings",
            accessKey = "backup@example.com",
            secretKey = "secret123",
            periodicUploadEvery10Min = true,
            splitRecordingEvery10Min = true,
            isDefault = true
        )
        repository.saveCloudConfig(megaCfg)

        val clip1 = File(context.cacheDir, "clip1.mp4").apply { writeBytes(ByteArray(128)) }
        val clip2 = File(context.cacheDir, "clip2.mp4").apply { writeBytes(ByteArray(128)) }
        val clip3 = File(context.cacheDir, "clip3.mp4").apply { writeBytes(ByteArray(128)) }

        repository.saveRecordingAndMaybeUpload(
            RecordingEntity(
                cameraId = 1L,
                cameraName = "Porch",
                fileName = "clip1.mp4",
                localFilePath = clip1.absolutePath,
                uploadStatus = UploadStatus.LOCAL_ONLY
            )
        )
        db.recordingDao().insertRecording(
            RecordingEntity(
                cameraId = 1L,
                cameraName = "Porch",
                fileName = "clip2.mp4",
                localFilePath = clip2.absolutePath,
                uploadStatus = UploadStatus.FAILED
            )
        )
        db.recordingDao().insertRecording(
            RecordingEntity(
                cameraId = 1L,
                cameraName = "Porch",
                fileName = "clip3.mp4",
                localFilePath = clip3.absolutePath,
                uploadStatus = UploadStatus.UPLOADED
            )
        )

        // Reset clip1 back to LOCAL_ONLY to simulate offline record, then run periodic 10-min sweep
        val allBefore = db.recordingDao().getPendingOrFailedRecordings()
        assertTrue(allBefore.isNotEmpty())

        val queued = repository.syncPendingOrFailedRecordings(forceReplace = false)
        assertEquals(allBefore.size, queued)

        val savedCfg = repository.getDefaultCloudConfig()!!
        assertNotNull(savedCfg.lastAutoSyncAt)

        TenMinuteCloudSyncScheduler.scheduleNextBackgroundWork(context, wifiOnly = false)
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TenMinuteCloudSyncScheduler.UNIQUE_SYNC_WORK_NAME)
            .get()
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos[0].state)

        clip1.delete()
        clip2.delete()
        clip3.delete()
    }

    @Test
    fun autoRecordIntervalsPersistAndTriggerOnCloudConfigSave() = runTest {
        assertEquals(listOf(1, 5, 10, 15, 20, 25, 30), RECORD_INTERVAL_OPTIONS_MINUTES)

        for (minutes in RECORD_INTERVAL_OPTIONS_MINUTES) {
            val id = repository.saveCloudConfig(
                CloudStorageConfigEntity(
                    name = "MEGA $minutes",
                    providerType = CloudProviderType.MEGA,
                    endpointUrl = "https://g.api.mega.co.nz",
                    accessKey = "user@example.com",
                    secretKey = "pass123",
                    recordIntervalMinutes = minutes,
                    autoRecordWhenCloudConfigured = true,
                    isDefault = true
                )
            )
            val saved = repository.getCloudConfigById(id)!!
            assertEquals(minutes, saved.effectiveRecordIntervalMinutes())
            assertTrue(saved.autoRecordWhenCloudConfigured)
        }

        val invalidFallback = CloudStorageConfigEntity(
            name = "Invalid",
            endpointUrl = "https://g.api.mega.co.nz",
            recordIntervalMinutes = 99
        )
        assertEquals(10, invalidFallback.effectiveRecordIntervalMinutes())

        var autoRecordTriggered = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scheduler = TenMinuteCloudSyncScheduler(context, scope, repository) {}
        val vm = CloudStorageViewModel(
            repository = repository,
            uploader = CloudStorageUploader(),
            syncScheduler = scheduler,
            onCloudTargetConfigured = { autoRecordTriggered = true }
        )
        vm.updateAccessKey("cam@example.com")
        vm.updateSecretKey("secretPass")
        vm.setRecordIntervalMinutes(25)
        vm.setAutoRecordWhenCloudConfigured(true)
        vm.saveConfig()

        val deadline = System.currentTimeMillis() + 3000L
        while (!autoRecordTriggered && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(25L)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val persisted = repository.getDefaultCloudConfig()!!
        assertEquals(25, persisted.effectiveRecordIntervalMinutes())
        assertTrue(persisted.autoRecordWhenCloudConfigured)
        assertTrue(autoRecordTriggered)
    }

    @Test
    fun cloudStorageNamingCreatesCameraAndDayFoldersAndTimestampFile() {
        val calendar = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 14, 1, 24, 30)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startedAt = calendar.timeInMillis

        // 1. Camera folder sanitization
        val camFolder = CloudStorageNaming.buildCameraFolderName("INSIDE CAM", 1L)
        assertEquals("INSIDE CAM", camFolder)

        val dirtyCamFolder = CloudStorageNaming.buildCameraFolderName("Camera/Front:12*", 2L)
        assertEquals("Camera_Front_12_", dirtyCamFolder)

        val emptyCamFolder = CloudStorageNaming.buildCameraFolderName("   ", 5L)
        assertEquals("Camera_5", emptyCamFolder)

        // 2. Day folder
        val dayFolder = CloudStorageNaming.buildDayFolderName(startedAt)
        assertTrue(dayFolder.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))

        // 3. Remote filename timestamp: DD-MM-YYYY-HH-mm-ss
        val expectedStamp = java.text.SimpleDateFormat("dd-MM-yyyy-HH-mm-ss", java.util.Locale.US).format(java.util.Date(startedAt))
        val originalFileName = "INSIDE_CAM_1_14-11-2026-01-24-30.mp4"
        val remoteFileName = CloudStorageNaming.buildRemoteFileName(originalFileName, startedAt)
        assertEquals("$expectedStamp.mp4", remoteFileName)

        val splitFileName = "INSIDE_CAM_1_14-11-2026-01-24-30_p2.mp4"
        val splitRemoteFileName = CloudStorageNaming.buildRemoteFileName(splitFileName, startedAt)
        assertEquals("${expectedStamp}_p2.mp4", splitRemoteFileName)

        // 4. Hierarchical cloud target folder
        val basePrefix = "CamVault/Recordings"
        val targetPath = CloudStorageNaming.buildTargetFolderPrefix(basePrefix, "INSIDE CAM", 1L, startedAt)
        assertEquals("CamVault/Recordings/INSIDE CAM/$dayFolder", targetPath)

        val emptyBaseTargetPath = CloudStorageNaming.buildTargetFolderPrefix("", "OUTSIDE CAM", 2L, startedAt)
        assertEquals("OUTSIDE CAM/$dayFolder", emptyBaseTargetPath)
    }

    private fun packMpints(vararg ints: BigInteger): ByteArray {
        val out = ByteArrayOutputStream()
        for (value in ints) {
            val raw = value.toByteArray()
            val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            val bits = BigInteger(1, unsigned).bitLength()
            out.write((bits ushr 8) and 0xFF)
            out.write(bits and 0xFF)
            out.write(unsigned)
        }
        return out.toByteArray()
    }
}
