package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CamVaultDatabase
import com.example.data.local.CameraEntity
import com.example.data.local.CloudProviderType
import com.example.data.local.CloudStorageConfigEntity
import com.example.data.repository.CamVaultRepository
import com.example.security.CredentialCipher
import com.example.security.SecurityGuards
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityHardeningTest {

    private lateinit var context: Context
    private lateinit var db: CamVaultDatabase
    private lateinit var cipher: CredentialCipher
    private lateinit var repository: CamVaultRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val fixedTestKey = SecretKeySpec(ByteArray(32) { (it * 7 + 3).toByte() }, "AES")
        cipher = CredentialCipher(keyProvider = { fixedTestKey })
        db = Room.inMemoryDatabaseBuilder(context, CamVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CamVaultRepository(
            appContext = context,
            cameraDao = db.cameraDao(),
            recordingDao = db.recordingDao(),
            cloudConfigDao = db.cloudStorageConfigDao(),
            credentialCipher = cipher
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun credentialCipherUsesUniqueIvAndRejectsTampering() {
        val secret = "SuperS3cret!Cam#2026"
        val ct1 = cipher.encrypt(secret)
        val ct2 = cipher.encrypt(secret)

        assertTrue(ct1.startsWith(CredentialCipher.PREFIX))
        assertTrue(ct2.startsWith(CredentialCipher.PREFIX))
        assertNotEquals("Same plaintext must produce unique random IV ciphertexts", ct1, ct2)
        assertFalse(ct1.contains(secret))

        assertEquals(secret, cipher.decryptOrThrow(ct1))
        assertEquals(secret, cipher.decryptOrThrow(ct2))

        // Tamper last character of GCM tag/ciphertext
        val flippedChar = if (ct1.last() == 'A') 'B' else 'A'
        val tampered = ct1.dropLast(1) + flippedChar
        try {
            cipher.decryptOrThrow(tampered)
            fail("Tampered AES-GCM ciphertext must fail tag verification")
        } catch (_: AEADBadTagException) {
            // expected
        } catch (_: IllegalArgumentException) {
            // also acceptable if framing/base64 rejected
        }

        // Fail-closed safe decrypt returns empty string on tampered payload
        assertEquals("", cipher.decryptSafe(tampered))
        // Legacy plaintext passes through until re-saved
        assertEquals("legacy-plain-password", cipher.decryptSafe("legacy-plain-password"))
    }

    @Test
    fun repositoryEncryptsCameraAndCloudCredentialsInSqliteAtRest() = runTest {
        val camId = repository.saveCamera(
            CameraEntity(
                name = "Garage Cam",
                host = "192.168.1.55",
                rtspUrl = "rtsp://admin:CamPass99@192.168.1.55:554/stream1",
                subStreamUrl = "rtsp://admin:CamPass99@192.168.1.55:554/stream2",
                username = "admin",
                password = "CamPass99"
            )
        )

        val rawDbCam = db.cameraDao().getCameraById(camId)
        assertNotNull(rawDbCam)
        assertTrue(rawDbCam!!.password.startsWith(CredentialCipher.PREFIX))
        assertTrue(rawDbCam.rtspUrl.startsWith(CredentialCipher.PREFIX))
        assertTrue(rawDbCam.subStreamUrl.startsWith(CredentialCipher.PREFIX))
        assertFalse(rawDbCam.password.contains("CamPass99"))
        assertFalse(rawDbCam.rtspUrl.contains("CamPass99"))

        val loadedCam = repository.getCameraById(camId)!!
        assertEquals("CamPass99", loadedCam.password)
        assertEquals("rtsp://admin:CamPass99@192.168.1.55:554/stream1", loadedCam.rtspUrl)
        assertEquals("rtsp://admin:CamPass99@192.168.1.55:554/stream2", loadedCam.subStreamUrl)
        assertEquals("CamPass99", repository.cameras.first().first().password)

        val cfgId = repository.saveCloudConfig(
            CloudStorageConfigEntity(
                name = "MEGA Vault",
                providerType = CloudProviderType.MEGA,
                endpointUrl = "https://g.api.mega.co.nz",
                accessKey = "user@example.com",
                secretKey = "MegaVaultPassword!42",
                bearerToken = "tok_secret_xyz",
                isDefault = true
            )
        )

        val rawDbCfg = db.cloudStorageConfigDao().getConfigById(cfgId)!!
        assertTrue(rawDbCfg.accessKey.startsWith(CredentialCipher.PREFIX))
        assertTrue(rawDbCfg.secretKey.startsWith(CredentialCipher.PREFIX))
        assertTrue(rawDbCfg.bearerToken.startsWith(CredentialCipher.PREFIX))
        assertFalse(rawDbCfg.secretKey.contains("MegaVaultPassword"))
        assertFalse(rawDbCfg.bearerToken.contains("tok_secret_xyz"))

        val loadedCfg = repository.getDefaultCloudConfig()!!
        assertEquals("user@example.com", loadedCfg.accessKey)
        assertEquals("MegaVaultPassword!42", loadedCfg.secretKey)
        assertEquals("tok_secret_xyz", loadedCfg.bearerToken)
    }

    @Test
    fun cloudUrlPolicyBlocksPublicHttpAndAllowsHttpsOrLocalLan() {
        // Public HTTPS allowed
        SecurityGuards.requireSafeCloudUrl("https://g.api.mega.co.nz", CloudProviderType.MEGA)
        SecurityGuards.requireSafeCloudUrl("https://s3.us-east-1.amazonaws.com", CloudProviderType.S3_COMPATIBLE)

        // Loopback & RFC1918 LAN HTTP allowed for NAS / test servers
        SecurityGuards.requireSafeCloudUrl("http://127.0.0.1:8080", CloudProviderType.MEGA)
        SecurityGuards.requireSafeCloudUrl("http://192.168.1.100:9000", CloudProviderType.S3_COMPATIBLE)
        SecurityGuards.requireSafeCloudUrl("http://10.0.0.15/remote.php/dav", CloudProviderType.WEBDAV)
        SecurityGuards.requireSafeCloudUrl("http://172.16.0.4:8080/upload", CloudProviderType.CUSTOM_HTTP)

        // Public cleartext HTTP MUST be blocked
        listOf(
            "http://g.api.mega.co.nz" to CloudProviderType.MEGA,
            "http://mega.nz/cs" to CloudProviderType.MEGA,
            "http://s3.us-east-1.amazonaws.com/my-bucket" to CloudProviderType.S3_COMPATIBLE,
            "http://cloud.public-example.com/dav" to CloudProviderType.WEBDAV,
            "ftp://192.168.1.100/file.mp4" to CloudProviderType.CUSTOM_HTTP
        ).forEach { (url, provider) ->
            try {
                SecurityGuards.requireSafeCloudUrl(url, provider)
                fail("Expected insecure URL to be rejected: $url")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun filenameSanitizationAndRedactionAndStoragePathConfinement() {
        assertEquals("clip_01.mp4", SecurityGuards.sanitizeRemoteFileName("clip_01.mp4"))
        assertEquals("clip_01.mp4", SecurityGuards.sanitizeRemoteFileName("folder/sub/clip_01.mp4"))
        try {
            SecurityGuards.sanitizeRemoteFileName("..secret..mp4")
            fail("Expected '..' filename to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            SecurityGuards.sanitizeRemoteFileName("..")
            fail("Expected '..' to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val redacted = SecurityGuards.redactUrlCredentials(
            "rtsp://admin:MySecret123@192.168.1.20:554/stream?token=abc123&sid=xyz987"
        )
        assertFalse(redacted.contains("MySecret123"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("xyz987"))
        assertTrue(redacted.contains("admin:•••@192.168.1.20"))

        val errSanitized = SecurityGuards.sanitizeErrorMessage(
            "GET http://admin:Pass42@192.168.1.10/fail Authorization: Bearer secretTokenHere"
        )
        assertFalse(errSanitized.contains("Pass42"))
        assertFalse(errSanitized.contains("secretTokenHere"))

        val insidePath = File(context.filesDir, "recordings/cam1.mp4").absolutePath
        assertTrue(SecurityGuards.isPathInsideAppStorage(context, insidePath))
        assertFalse(SecurityGuards.isPathInsideAppStorage(context, "/etc/passwd"))
        assertFalse(
            SecurityGuards.isPathInsideAppStorage(
                context,
                File(context.filesDir, "../shared_prefs/secret.xml").absolutePath
            )
        )
    }
}
