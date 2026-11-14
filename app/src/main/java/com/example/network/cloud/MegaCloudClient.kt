package com.example.network.cloud

import com.example.data.local.CloudProviderType
import com.example.security.SecurityGuards
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class MegaSession(
    val apiBaseUrl: String,
    val sessionId: String,
    val userHandle: String,
    val masterKey: ByteArray
)

data class MegaNode(
    val handle: String,
    val parentHandle: String,
    val type: Int,
    val name: String
)

data class MegaRsaPrivateKey(
    val p: BigInteger,
    val q: BigInteger,
    val d: BigInteger,
    val u: BigInteger
)

class MegaCloudClient(
    private val client: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestCounter = AtomicLong((System.currentTimeMillis() and 0xFFFFF) + 1)
    private val random = SecureRandom()

    suspend fun testConnection(
        endpointUrl: String,
        email: String,
        password: String,
        folderPrefix: String
    ): Pair<Int, String> {
        val baseUrl = endpointUrl.trim().ifBlank { DEFAULT_ENDPOINT }
        SecurityGuards.requireSafeCloudUrl(baseUrl, CloudProviderType.MEGA)
        val session = login(baseUrl, email, password)
        val nodes = fetchDriveNodes(session)
        val targetInfo = if (folderPrefix.isBlank()) {
            "Cloud Drive root"
        } else {
            folderPrefix.trim('/')
        }
        return 200 to "MEGA Drive connected (${nodes.size} nodes, folder: $targetInfo)"
    }

    suspend fun uploadFile(
        endpointUrl: String,
        email: String,
        password: String,
        folderPrefix: String,
        file: File,
        remoteFileName: String,
        onProgress: suspend (Int) -> Unit
    ): CloudUploadResult {
        val baseUrl = endpointUrl.trim().ifBlank { DEFAULT_ENDPOINT }
        SecurityGuards.requireSafeCloudUrl(baseUrl, CloudProviderType.MEGA)
        val safeRemoteFileName = SecurityGuards.sanitizeRemoteFileName(remoteFileName)
        val session = login(baseUrl, email, password)
        val targetFolderHandle = resolveOrCreateFolderPath(session, folderPrefix)

        val fileSize = file.length()
        require(fileSize > 0L) { "Cannot upload an empty file to MEGA Drive." }

        // 1. Request upload URL from MEGA
        val uploadInitReq = buildJsonObject {
            put("a", "u")
            put("s", fileSize)
        }
        val uploadInitResp = callApi(session.apiBaseUrl, session.sessionId, uploadInitReq).jsonObject
        val rawUploadUrl = uploadInitResp["p"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("MEGA did not return an upload URL.")
        val uploadUri = runCatching { URI(rawUploadUrl) }.getOrNull()
        val uploadHost = uploadUri?.host.orEmpty()
        val uploadUrl = if (rawUploadUrl.startsWith("http://", ignoreCase = true) && !SecurityGuards.isLoopbackOrPrivateHost(uploadHost)) {
            "https://" + rawUploadUrl.substring(7)
        } else {
            rawUploadUrl
        }
        SecurityGuards.requireSafeCloudUrl(uploadUrl, CloudProviderType.MEGA)

        // 2. Generate 16-byte AES file key + 8-byte CTR nonce
        val aesKey = ByteArray(16).also { random.nextBytes(it) }
        val ctrNonce = ByteArray(8).also { random.nextBytes(it) }
        val chunkSizes = computeChunkSizes(fileSize)

        val chunkMacs = ArrayList<ByteArray>(chunkSizes.size)
        var offset = 0L
        var completionHandle = ""

        onProgress(0)
        FileInputStream(file).use { fis ->
            for (chunkLen in chunkSizes) {
                val plainChunk = readExactBytes(fis, chunkLen)
                chunkMacs.add(computeChunkMac(plainChunk, aesKey, ctrNonce))
                val cipherChunk = encryptAesCtrChunk(plainChunk, aesKey, ctrNonce, offset)

                val chunkUrl = "$uploadUrl/$offset"
                val chunkReq = Request.Builder()
                    .url(chunkUrl)
                    .post(cipherChunk.toRequestBody("application/octet-stream".toMediaType()))
                    .build()

                client.newCall(chunkReq).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty().trim()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("MEGA chunk upload failed (HTTP ${resp.code}): ${bodyStr.take(160)}")
                    }
                    if (bodyStr.startsWith("-") && bodyStr.toIntOrNull() != null) {
                        throw IllegalStateException(describeMegaError(bodyStr.toInt()))
                    }
                    if (bodyStr.isNotBlank()) {
                        completionHandle = bodyStr
                    }
                }

                offset += chunkLen
                val pct = ((offset * 95L) / fileSize).toInt().coerceIn(1, 95)
                onProgress(pct)
            }
        }

        if (completionHandle.isBlank()) {
            throw IllegalStateException("MEGA did not return a completion handle after upload.")
        }

        // 3. Finalize file node under target folder
        val metaMac = computeMetaMac(chunkMacs, aesKey)
        val fullKey = ByteArray(32).apply {
            System.arraycopy(aesKey, 0, this, 0, 16)
            System.arraycopy(ctrNonce, 0, this, 16, 8)
            System.arraycopy(metaMac, 0, this, 24, 8)
        }
        val obfuscatedKey = ByteArray(32).apply {
            for (i in 0 until 16) {
                this[i] = (fullKey[i].toInt() xor fullKey[i + 16].toInt()).toByte()
            }
            System.arraycopy(fullKey, 16, this, 16, 16)
        }

        val encNodeKey = encodeMegaBase64(aesEcbEncryptNoPadding(obfuscatedKey, session.masterKey))
        val encAttrs = encodeMegaBase64(encryptAttributes(safeRemoteFileName, aesKey))

        val putNodesReq = buildJsonObject {
            put("a", "p")
            put("t", targetFolderHandle)
            put("n", buildJsonArray {
                add(buildJsonObject {
                    put("h", completionHandle)
                    put("t", 0)
                    put("a", encAttrs)
                    put("k", encNodeKey)
                })
            })
            put("i", generateClientInstanceId())
        }

        val putResp = callApi(session.apiBaseUrl, session.sessionId, putNodesReq).jsonObject
        val createdNodes = putResp["f"]?.jsonArray
        val fileHandle = createdNodes?.firstOrNull()?.jsonObject?.get("h")?.jsonPrimitive?.contentOrNull
            ?: completionHandle

        onProgress(100)
        val remoteUrl = "https://mega.nz/file/$fileHandle"
        return CloudUploadResult(
            remoteUrl = remoteUrl,
            httpCode = 200,
            bytesUploaded = fileSize
        )
    }

    internal fun login(baseUrl: String, email: String, password: String): MegaSession {
        val cleanEmail = email.trim().lowercase(Locale.US)
        require(cleanEmail.isNotBlank()) { "MEGA email address is required." }
        require(password.isNotEmpty()) { "MEGA password is required." }

        // Step 1: Query account version & salt (us0)
        val us0Resp = callApi(
            baseUrl = baseUrl,
            sessionId = null,
            command = buildJsonObject {
                put("a", "us0")
                put("user", cleanEmail)
            }
        ).jsonObject

        val version = us0Resp["v"]?.jsonPrimitive?.intOrNull ?: 1
        val saltB64 = us0Resp["s"]?.jsonPrimitive?.contentOrNull

        val (passwordKey, userHash) = if (version >= 2 && !saltB64.isNullOrBlank()) {
            deriveLoginKeysV2(password, decodeMegaBase64(saltB64))
        } else {
            val v1Key = prepareKeyV1(password.toByteArray(Charsets.UTF_8))
            val uh = stringHashV1(cleanEmail, v1Key)
            v1Key to uh
        }

        // Step 2: Authenticate (us)
        val usResp = callApi(
            baseUrl = baseUrl,
            sessionId = null,
            command = buildJsonObject {
                put("a", "us")
                put("user", cleanEmail)
                put("uh", userHash)
            }
        ).jsonObject

        val encryptedMasterKeyB64 = usResp["k"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("MEGA login failed: missing master key.")
        val csidB64 = usResp["csid"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("MEGA login failed: missing session key.")
        val privkB64 = usResp["privk"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("MEGA login failed: missing RSA private key.")
        val userHandle = usResp["u"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val masterKey = aesEcbDecryptNoPadding(decodeMegaBase64(encryptedMasterKeyB64), passwordKey)
        require(masterKey.size == 16) { "Unexpected MEGA master key length (${masterKey.size})." }

        val privkBytes = aesEcbDecryptNoPadding(decodeMegaBase64(privkB64), masterKey)
        val rsaKey = unpackRsaPrivateKey(privkBytes)
        val rawSid = decryptSessionId(decodeMegaBase64(csidB64), rsaKey)

        return MegaSession(
            apiBaseUrl = baseUrl,
            sessionId = encodeMegaBase64(rawSid),
            userHandle = userHandle,
            masterKey = masterKey
        )
    }

    internal fun fetchDriveNodes(session: MegaSession): List<MegaNode> {
        val fResp = callApi(
            baseUrl = session.apiBaseUrl,
            sessionId = session.sessionId,
            command = buildJsonObject {
                put("a", "f")
                put("c", 1)
            }
        ).jsonObject

        val rawList = fResp["f"]?.jsonArray ?: return emptyList()
        val parsed = ArrayList<MegaNode>(rawList.size)

        for (item in rawList) {
            val obj = item.jsonObject
            val handle = obj["h"]?.jsonPrimitive?.contentOrNull ?: continue
            val parent = obj["p"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val type = obj["t"]?.jsonPrimitive?.intOrNull ?: continue
            val encAttrs = obj["a"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val encKeys = obj["k"]?.jsonPrimitive?.contentOrNull.orEmpty()

            var name = ""
            if (type in 0..1 && encAttrs.isNotBlank() && encKeys.isNotBlank()) {
                val nodeKey = decryptNodeKey(encKeys, session.userHandle, session.masterKey)
                if (nodeKey != null) {
                    name = decryptNodeName(encAttrs, nodeKey).orEmpty()
                }
            }
            parsed.add(MegaNode(handle = handle, parentHandle = parent, type = type, name = name))
        }
        return parsed
    }

    internal fun resolveOrCreateFolderPath(session: MegaSession, folderPrefix: String): String {
        val nodes = fetchDriveNodes(session).toMutableList()
        val rootNode = nodes.firstOrNull { it.type == 2 }
            ?: throw IllegalStateException("MEGA Cloud Drive root node not found.")

        val segments = folderPrefix.trim().trim('/').split('/').filter { it.isNotBlank() }
        var currentParent = rootNode.handle

        for (segment in segments) {
            val existing = nodes.firstOrNull {
                it.type == 1 && it.parentHandle == currentParent && it.name == segment
            }
            if (existing != null) {
                currentParent = existing.handle
                continue
            }

            val folderKey = ByteArray(16).also { random.nextBytes(it) }
            val encKey = encodeMegaBase64(aesEcbEncryptNoPadding(folderKey, session.masterKey))
            val encAttrs = encodeMegaBase64(encryptAttributes(segment, folderKey))

            val createResp = callApi(
                baseUrl = session.apiBaseUrl,
                sessionId = session.sessionId,
                command = buildJsonObject {
                    put("a", "p")
                    put("t", currentParent)
                    put("n", buildJsonArray {
                        add(buildJsonObject {
                            put("h", "xxxxxxxx")
                            put("t", 1)
                            put("a", encAttrs)
                            put("k", encKey)
                        })
                    })
                    put("i", generateClientInstanceId())
                }
            ).jsonObject

            val createdHandle = createResp["f"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("h")?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("Failed to create MEGA folder '$segment'.")

            nodes.add(MegaNode(createdHandle, currentParent, 1, segment))
            currentParent = createdHandle
        }

        return currentParent
    }

    private fun callApi(baseUrl: String, sessionId: String?, command: JsonObject): JsonElement {
        val cleanBase = baseUrl.trimEnd('/')
        val sidQuery = if (!sessionId.isNullOrBlank()) "&sid=$sessionId" else ""
        val bodyArray = JsonArray(listOf(command)).toString()
        var hashcashProof: String? = null
        var lastError: Throwable? = null

        for (attempt in 0 until 4) {
            val url = "$cleanBase/cs?id=${requestCounter.incrementAndGet()}$sidQuery"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", MEGA_USER_AGENT)
                .header("Accept", "application/json")
                .header("Origin", "https://mega.nz")
                .header("Referer", "https://mega.nz/")
                .post(bodyArray.toRequestBody("application/json".toMediaType()))

            if (!hashcashProof.isNullOrBlank()) {
                reqBuilder.header("X-Hashcash", hashcashProof)
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty().trim()
                if (resp.code == 402) {
                    val challenge = resp.header("X-Hashcash")
                    if (!challenge.isNullOrBlank()) {
                        hashcashProof = solveHashcashChallenge(challenge)
                        lastError = IllegalStateException("MEGA X-Hashcash retry exhausted (HTTP 402).")
                        return@use
                    }
                    throw IllegalStateException(
                        "MEGA HTTP 402 (no X-Hashcash challenge returned): ${raw.ifBlank { resp.message }.take(160)}"
                    )
                }
                if (resp.code in listOf(500, 502, 503, 504) && attempt < 3) {
                    lastError = IllegalStateException("MEGA HTTP ${resp.code}: ${resp.message}")
                    Thread.sleep(400L * (attempt + 1))
                    return@use
                }
                if (!resp.isSuccessful) {
                    throw IllegalStateException("MEGA HTTP ${resp.code}: ${raw.ifBlank { resp.message }.take(180)}")
                }
                val topPrimitive = raw.toIntOrNull()
                if (topPrimitive != null && topPrimitive < 0) {
                    if (topPrimitive == -3 && attempt < 3) {
                        lastError = IllegalStateException(describeMegaError(topPrimitive))
                        Thread.sleep(500L * (attempt + 1))
                        return@use
                    }
                    throw IllegalStateException(describeMegaError(topPrimitive))
                }
                val parsedArray = json.parseToJsonElement(raw).jsonArray
                val first = parsedArray.firstOrNull()
                    ?: throw IllegalStateException("Empty MEGA API response.")
                if (first is JsonPrimitive) {
                    val errCode = first.intOrNull
                    if (errCode != null && errCode < 0) {
                        if (errCode == -3 && attempt < 3) {
                            lastError = IllegalStateException(describeMegaError(errCode))
                            Thread.sleep(500L * (attempt + 1))
                            return@use
                        }
                        throw IllegalStateException(describeMegaError(errCode))
                    }
                }
                return first
            }
        }
        throw (lastError ?: IllegalStateException("MEGA API request failed after retries."))
    }

    private fun decryptNodeKey(encKeys: String, userHandle: String, masterKey: ByteArray): ByteArray? {
        val parts = encKeys.split('/')
        val preferred = if (userHandle.isNotBlank()) {
            parts.firstOrNull { it.startsWith("$userHandle:") } ?: parts.firstOrNull()
        } else {
            parts.firstOrNull()
        } ?: return null

        val keyB64 = preferred.substringAfter(':', "")
        if (keyB64.isBlank()) return null

        val encrypted = decodeMegaBase64(keyB64)
        if (encrypted.size != 16 && encrypted.size != 32) return null

        val plain = aesEcbDecryptNoPadding(encrypted, masterKey)
        return if (plain.size == 32) {
            ByteArray(16).apply {
                for (i in 0 until 16) {
                    this[i] = (plain[i].toInt() xor plain[i + 16].toInt()).toByte()
                }
            }
        } else {
            plain
        }
    }

    private fun decryptNodeName(encAttrsB64: String, nodeAesKey: ByteArray): String? {
        val decrypted = aesCbcDecryptZeroIv(decodeMegaBase64(encAttrsB64), nodeAesKey)
        val raw = decrypted.toString(Charsets.UTF_8).trimEnd('\u0000')
        if (!raw.startsWith("MEGA")) return null
        val jsonBody = raw.removePrefix("MEGA")
        return runCatching {
            json.parseToJsonElement(jsonBody).jsonObject["n"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://g.api.mega.co.nz"

        fun deriveLoginKeysV2(password: String, salt: ByteArray): Pair<ByteArray, String> {
            val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val derived = factory.generateSecret(spec).encoded
            val encKey = derived.copyOfRange(0, 16)
            val uh = encodeMegaBase64(derived.copyOfRange(16, 32))
            return encKey to uh
        }

        fun prepareKeyV1(passwordBytes: ByteArray): ByteArray {
            var pkey = byteArrayOf(
                0x93.toByte(), 0xC4.toByte(), 0x67, 0xE3.toByte(),
                0x7D, 0xB0.toByte(), 0xC7.toByte(), 0xA4.toByte(),
                0xD1.toByte(), 0xBE.toByte(), 0x3F, 0x81.toByte(),
                0x01, 0x52, 0xCB.toByte(), 0x56
            )
            val numBlocks = ((passwordBytes.size + 15) / 16).coerceAtLeast(1)
            val blocks = Array(numBlocks) { idx ->
                val block = ByteArray(16)
                val start = idx * 16
                if (start < passwordBytes.size) {
                    val len = minOf(16, passwordBytes.size - start)
                    System.arraycopy(passwordBytes, start, block, 0, len)
                }
                block
            }
            val ciphers = blocks.map { blockKey ->
                Cipher.getInstance("AES/ECB/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(blockKey, "AES"))
                }
            }
            for (round in 0 until 65536) {
                for (cipher in ciphers) {
                    pkey = cipher.doFinal(pkey)
                }
            }
            return pkey
        }

        fun stringHashV1(emailLower: String, aesKey: ByteArray): String {
            val sBytes = emailLower.toByteArray(Charsets.UTF_8)
            var hash = ByteArray(16)
            for (i in sBytes.indices) {
                hash[i and 15] = (hash[i and 15].toInt() xor sBytes[i].toInt()).toByte()
            }
            val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
            }
            for (i in 0 until 16384) {
                hash = cipher.doFinal(hash)
            }
            val out = ByteArray(8)
            System.arraycopy(hash, 0, out, 0, 4)
            System.arraycopy(hash, 8, out, 4, 4)
            return encodeMegaBase64(out)
        }

        fun unpackRsaPrivateKey(decryptedPrivk: ByteArray): MegaRsaPrivateKey {
            var pos = 0
            fun nextMpint(): BigInteger {
                require(pos + 2 <= decryptedPrivk.size) { "Truncated MEGA RSA private key." }
                val bitLen = ((decryptedPrivk[pos].toInt() and 0xFF) shl 8) or
                    (decryptedPrivk[pos + 1].toInt() and 0xFF)
                pos += 2
                val byteLen = (bitLen + 7) / 8
                require(pos + byteLen <= decryptedPrivk.size) { "Invalid MEGA RSA MPint length." }
                val bytes = decryptedPrivk.copyOfRange(pos, pos + byteLen)
                pos += byteLen
                return BigInteger(1, bytes)
            }
            val p = nextMpint()
            val q = nextMpint()
            val d = nextMpint()
            val u = nextMpint()
            return MegaRsaPrivateKey(p = p, q = q, d = d, u = u)
        }

        fun decryptSessionId(csidMpint: ByteArray, rsaKey: MegaRsaPrivateKey): ByteArray {
            require(csidMpint.size >= 3) { "Invalid csid payload." }
            val bitLen = ((csidMpint[0].toInt() and 0xFF) shl 8) or
                (csidMpint[1].toInt() and 0xFF)
            val byteLen = (bitLen + 7) / 8
            require(2 + byteLen <= csidMpint.size) { "Truncated csid MPint." }

            val cipherInt = BigInteger(1, csidMpint.copyOfRange(2, 2 + byteLen))
            val modulus = rsaKey.p.multiply(rsaKey.q)
            val plainInt = cipherInt.modPow(rsaKey.d, modulus)
            val raw = plainInt.toByteArray()
            val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) {
                raw.copyOfRange(1, raw.size)
            } else {
                raw
            }
            require(unsigned.size >= 43) { "Decrypted MEGA session ID too short (${unsigned.size} bytes)." }
            return unsigned.copyOfRange(0, 43)
        }

        fun encryptAttributes(fileName: String, aesKey: ByteArray): ByteArray {
            val attrJson = buildJsonObject { put("n", fileName) }.toString()
            val rawBytes = "MEGA$attrJson".toByteArray(Charsets.UTF_8)
            val paddedLen = ((rawBytes.size + 15) / 16) * 16
            val padded = rawBytes.copyOf(paddedLen.coerceAtLeast(16))
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
            return cipher.doFinal(padded)
        }

        fun computeChunkSizes(fileSize: Long): List<Int> {
            val sizes = mutableListOf<Int>()
            var remaining = fileSize
            var p = 131072
            while (remaining > 0L) {
                val len = minOf(remaining, p.toLong()).toInt()
                sizes.add(len)
                remaining -= len
                if (p < 1048576) {
                    p += 131072
                }
            }
            return sizes
        }

        fun encryptAesCtrChunk(
            plainChunk: ByteArray,
            aesKey: ByteArray,
            ctrNonce8: ByteArray,
            startOffsetBytes: Long
        ): ByteArray {
            val iv = ByteBuffer.allocate(16)
                .put(ctrNonce8, 0, 8)
                .putLong(startOffsetBytes / 16L)
                .array()
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(plainChunk)
        }

        fun computeChunkMac(plainChunk: ByteArray, aesKey: ByteArray, ctrNonce8: ByteArray): ByteArray {
            val mac = ByteArray(16).apply {
                System.arraycopy(ctrNonce8, 0, this, 0, 8)
                System.arraycopy(ctrNonce8, 0, this, 8, 8)
            }
            val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
            }
            val block = ByteArray(16)
            var pos = 0
            while (pos < plainChunk.size) {
                block.fill(0)
                val copyLen = minOf(16, plainChunk.size - pos)
                for (i in 0 until copyLen) {
                    block[i] = (mac[i].toInt() xor plainChunk[pos + i].toInt()).toByte()
                }
                for (i in copyLen until 16) {
                    block[i] = mac[i]
                }
                val enc = ecb.doFinal(block)
                System.arraycopy(enc, 0, mac, 0, 16)
                pos += 16
            }
            return mac
        }

        fun computeMetaMac(chunkMacs: List<ByteArray>, aesKey: ByteArray): ByteArray {
            val state = ByteArray(16)
            val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
            }
            val block = ByteArray(16)
            for (cm in chunkMacs) {
                for (i in 0 until 16) {
                    block[i] = (state[i].toInt() xor cm[i].toInt()).toByte()
                }
                val enc = ecb.doFinal(block)
                System.arraycopy(enc, 0, state, 0, 16)
            }
            return ByteArray(8).apply {
                for (i in 0 until 4) {
                    this[i] = (state[i].toInt() xor state[i + 4].toInt()).toByte()
                    this[i + 4] = (state[i + 8].toInt() xor state[i + 12].toInt()).toByte()
                }
            }
        }

        fun aesEcbEncryptNoPadding(data: ByteArray, key: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            return cipher.doFinal(data)
        }

        fun aesEcbDecryptNoPadding(data: ByteArray, key: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            return cipher.doFinal(data)
        }

        fun aesCbcDecryptZeroIv(data: ByteArray, key: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            return cipher.doFinal(data)
        }

        fun encodeMegaBase64(bytes: ByteArray): String {
            val b64 = android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            return b64
        }

        fun decodeMegaBase64(encoded: String): ByteArray {
            val std = encoded.trim()
                .replace('-', '+')
                .replace('_', '/')
            val padLen = (4 - (std.length % 4)) % 4
            val padded = std + "=".repeat(padLen)
            return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        }

        fun describeMegaError(code: Int): String = when (code) {
            -2 -> "MEGA invalid arguments (-2)."
            -3 -> "MEGA server busy; retry shortly (-3)."
            -4 -> "MEGA rate limit reached (-4)."
            -6 -> "Too many concurrent requests (-6)."
            -9 -> "MEGA account not found or invalid email (-9)."
            -11 -> "MEGA access denied (-11)."
            -14 -> "Incorrect MEGA password or key (-14)."
            -15 -> "MEGA session expired (-15)."
            -16 -> "MEGA user account blocked (-16)."
            -17 -> "MEGA storage quota exceeded (-17)."
            -18 -> "MEGA resource temporarily unavailable (-18)."
            -26 -> "MEGA 2FA enabled on account; please disable 2FA or use an app credential (-26)."
            else -> "MEGA API error ($code)."
        }

        private fun readExactBytes(fis: FileInputStream, expected: Int): ByteArray {
            val out = ByteArrayOutputStream(expected)
            val buf = ByteArray(minOf(32768, expected))
            var remaining = expected
            while (remaining > 0) {
                val read = fis.read(buf, 0, minOf(buf.size, remaining))
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
            return out.toByteArray()
        }

        const val MEGA_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) CamVault/1.0"
        private const val HASHCASH_REPLICATIONS = 262144
        private const val HASHCASH_SLOT_SIZE = 48

        fun parseHashcashHeader(header: String): Pair<Int, String>? {
            val parts = header.trim().split(':')
            if (parts.size < 3) return null
            if (parts[0] != "1") return null
            val easiness = parts[1].toIntOrNull() ?: return null
            val token = parts.last()
            if (easiness !in 0..255 || token.isBlank()) return null
            return easiness to token
        }

        fun solveHashcashChallenge(
            challengeHeader: String,
            replications: Int = HASHCASH_REPLICATIONS,
            maxAttempts: Long = 2_000_000L
        ): String {
            val (easiness, tokenStr) = parseHashcashHeader(challengeHeader)
                ?: throw IllegalArgumentException("Invalid MEGA X-Hashcash challenge: $challengeHeader")

            val threshold: Long =
                ((((easiness and 63).toLong() shl 1) + 1L) shl ((easiness shr 6) * 7 + 3))

            val decodedToken = decodeMegaBase64(tokenStr)
            val slot = ByteArray(HASHCASH_SLOT_SIZE)
            System.arraycopy(decodedToken, 0, slot, 0, minOf(decodedToken.size, HASHCASH_SLOT_SIZE))

            val totalDataBytes = replications * HASHCASH_SLOT_SIZE
            val buffer = ByteArray(4 + totalDataBytes)
            System.arraycopy(slot, 0, buffer, 4, HASHCASH_SLOT_SIZE)
            var filled = HASHCASH_SLOT_SIZE
            while (filled < totalDataBytes) {
                val copyLen = minOf(filled, totalDataBytes - filled)
                System.arraycopy(buffer, 4, buffer, 4 + filled, copyLen)
                filled += copyLen
            }

            val digestEngine = MessageDigest.getInstance("SHA-256")
            val startedMs = System.currentTimeMillis()
            var attempts = 0L

            while (attempts < maxAttempts) {
                // Increment 4-byte prefix
                for (i in 0 until 4) {
                    val next = (buffer[i].toInt() and 0xFF) + 1
                    buffer[i] = next.toByte()
                    if (next <= 0xFF) break
                }

                digestEngine.update(buffer)
                val digest = digestEngine.digest()
                val head: Long =
                    ((digest[0].toLong() and 0xFFL) shl 24) or
                        ((digest[1].toLong() and 0xFFL) shl 16) or
                        ((digest[2].toLong() and 0xFFL) shl 8) or
                        (digest[3].toLong() and 0xFFL)

                if (head <= threshold) {
                    val prefixB64 = encodeMegaBase64(buffer.copyOfRange(0, 4))
                    return "1:$tokenStr:$prefixB64"
                }

                attempts++
                if ((attempts and 0xFFL) == 0L && (System.currentTimeMillis() - startedMs) > 30_000L) {
                    throw IllegalStateException("MEGA Hashcash challenge timed out after $attempts tries.")
                }
            }

            throw IllegalStateException("MEGA Hashcash challenge could not be solved within $maxAttempts attempts.")
        }

        private val companionRandom = SecureRandom()

        private fun generateClientInstanceId(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return buildString(10) {
                repeat(10) { append(chars[companionRandom.nextInt(chars.length)]) }
            }
        }
    }
}
