package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts sensitive credentials at rest (camera passwords, RTSP URLs with embedded credentials,
 * cloud secret keys, MEGA passwords, bearer tokens) using AES-256-GCM backed by AndroidKeyStore.
 *
 * Ciphertext format: "enc:v1:<base64url(12-byte IV)>:<base64url(ciphertext+16-byte GCM tag)>"
 */
class CredentialCipher(
    private val keyProvider: () -> SecretKey = { getOrCreateDefaultKey() }
) {
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        if (plaintext.startsWith(PREFIX)) return plaintext

        val cipher = Cipher.getInstance(TRANSFORMATION)
        // AndroidKeyStore with randomizedEncryptionRequired=true forbids caller-provided IVs on ENCRYPT_MODE.
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        require(iv != null && iv.isNotEmpty()) { "Missing AES-GCM IV from cipher." }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val ivB64 = Base64.encodeToString(iv, BASE64_FLAGS)
        val ctB64 = Base64.encodeToString(ciphertext, BASE64_FLAGS)
        return "$PREFIX$ivB64:$ctB64"
    }

    fun decryptSafe(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext row migrated transparently on next write.
            return stored
        }
        return runCatching { decryptOrThrow(stored) }.getOrDefault("")
    }

    fun decryptOrThrow(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored

        val body = stored.removePrefix(PREFIX)
        val sep = body.indexOf(':')
        require(sep > 0 && sep < body.lastIndex) { "Malformed encrypted credential envelope." }

        val iv = Base64.decode(body.substring(0, sep), BASE64_FLAGS)
        require(iv.isNotEmpty()) { "Invalid AES-GCM IV length." }
        val ciphertext = Base64.decode(body.substring(sep + 1), BASE64_FLAGS)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    companion object {
        const val PREFIX = "enc:v1:"
        private const val KEY_ALIAS = "camvault_db_secrets_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        @Volatile
        private var cachedKey: SecretKey? = null

        private fun getOrCreateDefaultKey(): SecretKey {
            cachedKey?.let { return it }
            synchronized(this) {
                cachedKey?.let { return it }
                val key = runCatching { loadOrCreateAndroidKeyStoreKey() }
                    .getOrElse {
                        // Local JVM / Robolectric fallback when AndroidKeyStore provider is absent.
                        getOrCreatePersistentFallbackKey()
                    }
                cachedKey = key
                return key
            }
        }

        /**
         * Fallback key used ONLY when the AndroidKeyStore provider is unavailable
         * (local JVM unit tests / Robolectric). It persists a random 32-byte AES
         * seed in the JVM temporary directory.
         *
         * This is never exercised on a real Android device, where the KeyStore
         * path (loadOrCreateAndroidKeyStoreKey) always wins. It exists so tests can
         * exercise the cipher without a hardware-backed keystore. The seed file is
         * restricted to owner-only read/write; it is not a supported production
         * secret transport and should not be relied on for sensitive data.
         */
        private fun getOrCreatePersistentFallbackKey(): SecretKey {
            val fallbackFile = java.io.File(System.getProperty("java.io.tmpdir"), ".camvault_sec_seed.bin")
            // Restrict to owner-only (read/write) whether we create it or reuse it.
            runCatching { fallbackFile.setReadable(false, false); fallbackFile.setWritable(false, false) }
            runCatching { fallbackFile.setReadable(true, true); fallbackFile.setWritable(true, true) }
            val bytes = ByteArray(32)
            if (fallbackFile.exists() && fallbackFile.length() == 32L) {
                val readSuccess = runCatching {
                    fallbackFile.inputStream().use { it.read(bytes) } == 32
                }.getOrDefault(false)
                if (readSuccess) return SecretKeySpec(bytes, "AES")
            }
            SecureRandom().nextBytes(bytes)
            val wrote = runCatching {
                fallbackFile.outputStream().use { it.write(bytes) }
                true
            }.getOrDefault(false)
            // If we could not persist, fall back to an ephemeral key for this session.
            if (!wrote) return SecretKeySpec(bytes, "AES")
            // Re-assert owner-only permissions after the fresh write.
            runCatching { fallbackFile.setReadable(false, false); fallbackFile.setWritable(false, false) }
            runCatching { fallbackFile.setReadable(true, true); fallbackFile.setWritable(true, true) }
            return SecretKeySpec(bytes, "AES")
        }

        private fun loadOrCreateAndroidKeyStoreKey(): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGen.init(spec)
            return keyGen.generateKey()
        }
    }
}
