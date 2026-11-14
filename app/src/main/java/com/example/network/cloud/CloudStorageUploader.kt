package com.example.network.cloud

import com.example.data.local.CloudProviderType
import com.example.data.local.CloudStorageConfigEntity
import com.example.security.SecurityGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class CloudUploadResult(
    val remoteUrl: String,
    val httpCode: Int,
    val bytesUploaded: Long
)

data class CloudConnectionTestResult(
    val success: Boolean,
    val httpCode: Int,
    val latencyMs: Long,
    val message: String
)

class CloudStorageUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val megaClient = MegaCloudClient(client)

    suspend fun testConnection(config: CloudStorageConfigEntity): CloudConnectionTestResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            runCatching {
                val effectiveEndpoint = if (config.providerType == CloudProviderType.MEGA) {
                    config.endpointUrl.ifBlank { MegaCloudClient.DEFAULT_ENDPOINT }
                } else {
                    config.endpointUrl
                }
                SecurityGuards.requireSafeCloudUrl(effectiveEndpoint, config.providerType)

                when (config.providerType) {
                    CloudProviderType.S3_COMPATIBLE -> testS3Connection(config)
                    CloudProviderType.WEBDAV -> testWebDavConnection(config)
                    CloudProviderType.CUSTOM_HTTP -> testCustomHttpConnection(config)
                    CloudProviderType.MEGA -> megaClient.testConnection(
                        endpointUrl = effectiveEndpoint,
                        email = config.accessKey,
                        password = config.secretKey,
                        folderPrefix = config.folderPrefix
                    )
                }
            }.fold(
                onSuccess = { (code, msg) ->
                    CloudConnectionTestResult(
                        success = code in 200..299 || code == 207 || code == 405,
                        httpCode = code,
                        latencyMs = System.currentTimeMillis() - start,
                        message = SecurityGuards.sanitizeErrorMessage(msg, "Connected")
                    )
                },
                onFailure = { err ->
                    CloudConnectionTestResult(
                        success = false,
                        httpCode = -1,
                        latencyMs = System.currentTimeMillis() - start,
                        message = SecurityGuards.sanitizeErrorMessage(err.localizedMessage, "Connection failed")
                    )
                }
            )
        }

    suspend fun uploadVideoFile(
        file: File,
        remoteFileName: String,
        config: CloudStorageConfigEntity,
        targetFolderPrefix: String = config.folderPrefix,
        onProgress: suspend (Int) -> Unit
    ): Result<CloudUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.exists() && file.length() > 0) { "Recording file is missing or empty." }
            val safeFileName = SecurityGuards.sanitizeRemoteFileName(remoteFileName)
            val effectiveFolder = targetFolderPrefix.ifBlank { config.folderPrefix }
            val safeFolder = SecurityGuards.sanitizeFolderPrefix(effectiveFolder)
            val effectiveEndpoint = if (config.providerType == CloudProviderType.MEGA) {
                val raw = config.endpointUrl.trim().ifBlank { MegaCloudClient.DEFAULT_ENDPOINT }
                if (raw.startsWith("http://", ignoreCase = true)) {
                    "https://" + raw.substring(7)
                } else if (!raw.startsWith("https://", ignoreCase = true)) {
                    "https://$raw"
                } else {
                    raw
                }
            } else {
                config.endpointUrl
            }
            require(effectiveEndpoint.isNotBlank()) { "Cloud endpoint URL is not configured." }
            SecurityGuards.requireSafeCloudUrl(effectiveEndpoint, config.providerType)
            val sanitizedConfig = config.copy(folderPrefix = safeFolder)

            when (config.providerType) {
                CloudProviderType.S3_COMPATIBLE -> uploadToS3(file, safeFileName, sanitizedConfig, onProgress)
                CloudProviderType.WEBDAV -> uploadToWebDav(file, safeFileName, sanitizedConfig, onProgress)
                CloudProviderType.CUSTOM_HTTP -> uploadToCustomHttp(file, safeFileName, sanitizedConfig, onProgress)
                CloudProviderType.MEGA -> megaClient.uploadFile(
                    endpointUrl = effectiveEndpoint,
                    email = sanitizedConfig.accessKey,
                    password = sanitizedConfig.secretKey,
                    folderPrefix = safeFolder,
                    file = file,
                    remoteFileName = safeFileName,
                    onProgress = onProgress
                )
            }
        }
    }

    private suspend fun uploadToS3(
        file: File,
        remoteFileName: String,
        config: CloudStorageConfigEntity,
        onProgress: suspend (Int) -> Unit
    ): CloudUploadResult {
        val objectKey = buildObjectKey(config.folderPrefix, remoteFileName)
        val target = resolveS3Url(config.endpointUrl, config.bucketName, objectKey)
        val payloadHash = sha256Hex(file)
        val region = config.region.ifBlank { "us-east-1" }

        val headers = signAwsV4(
            method = "PUT",
            fullUrl = target.fullUrl,
            region = region,
            service = "s3",
            accessKey = config.accessKey,
            secretKey = config.secretKey,
            payloadHash = payloadHash,
            contentType = "video/mp4"
        )

        val requestBuilder = Request.Builder()
            .url(target.fullUrl)
            .put(CountingFileRequestBody(file, "video/mp4".toMediaType(), onProgress))
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        client.newCall(requestBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty().take(240)
                throw IllegalStateException("S3 PUT HTTP ${resp.code}: $errBody")
            }
            return CloudUploadResult(
                remoteUrl = target.fullUrl,
                httpCode = resp.code,
                bytesUploaded = file.length()
            )
        }
    }

    private fun testS3Connection(config: CloudStorageConfigEntity): Pair<Int, String> {
        val target = resolveS3Url(config.endpointUrl, config.bucketName, "")
        val listUrl = "${target.fullUrl}?list-type=2&max-keys=1"
        val emptyHash = sha256Hex(ByteArray(0))
        val headers = signAwsV4(
            method = "GET",
            fullUrl = listUrl,
            region = config.region.ifBlank { "us-east-1" },
            service = "s3",
            accessKey = config.accessKey,
            secretKey = config.secretKey,
            payloadHash = emptyHash
        )
        val builder = Request.Builder().url(listUrl).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            val msg = if (resp.isSuccessful) {
                "Bucket reachable & SigV4 verified (HTTP ${resp.code})"
            } else {
                "HTTP ${resp.code}: ${resp.message}"
            }
            return resp.code to msg
        }
    }

    private suspend fun uploadToWebDav(
        file: File,
        remoteFileName: String,
        config: CloudStorageConfigEntity,
        onProgress: suspend (Int) -> Unit
    ): CloudUploadResult {
        val base = config.endpointUrl.trimEnd('/')
        val prefix = config.folderPrefix.trim('/').split('/').filter { it.isNotBlank() }
        val authHeader = buildWebDavAuthHeader(config)

        // Ensure remote directories exist via MKCOL (405/201/301 are fine)
        var currentDir = base
        for (segment in prefix) {
            currentDir = "$currentDir/${encodeSegment(segment)}"
            val mkcol = Request.Builder()
                .url(currentDir)
                .method("MKCOL", null)
                .apply { if (authHeader != null) header("Authorization", authHeader) }
                .build()
            runCatching { client.newCall(mkcol).execute().close() }
        }

        val targetUrl = "$currentDir/${encodeSegment(remoteFileName)}"
        val putReq = Request.Builder()
            .url(targetUrl)
            .put(CountingFileRequestBody(file, "video/mp4".toMediaType(), onProgress))
            .apply { if (authHeader != null) header("Authorization", authHeader) }
            .build()

        client.newCall(putReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(200)
                throw IllegalStateException("WebDAV HTTP ${resp.code}: $err")
            }
            return CloudUploadResult(
                remoteUrl = targetUrl,
                httpCode = resp.code,
                bytesUploaded = file.length()
            )
        }
    }

    private fun testWebDavConnection(config: CloudStorageConfigEntity): Pair<Int, String> {
        val authHeader = buildWebDavAuthHeader(config)
        val req = Request.Builder()
            .url(config.endpointUrl.trim())
            .method("OPTIONS", null)
            .apply { if (authHeader != null) header("Authorization", authHeader) }
            .build()
        client.newCall(req).execute().use { resp ->
            val msg = if (resp.isSuccessful || resp.code == 207) {
                "WebDAV endpoint reachable (HTTP ${resp.code})"
            } else {
                "WebDAV HTTP ${resp.code}: ${resp.message}"
            }
            return resp.code to msg
        }
    }

    private suspend fun uploadToCustomHttp(
        file: File,
        remoteFileName: String,
        config: CloudStorageConfigEntity,
        onProgress: suspend (Int) -> Unit
    ): CloudUploadResult {
        val rawUrl = config.endpointUrl.trim()
        val targetUrl = if (rawUrl.contains("{filename}")) {
            rawUrl.replace("{filename}", encodeSegment(remoteFileName))
        } else if (rawUrl.contains("?") || rawUrl.endsWith(".mp4", ignoreCase = true)) {
            rawUrl
        } else {
            val prefix = config.folderPrefix.trim('/')
            val path = if (prefix.isBlank()) encodeSegment(remoteFileName) else "$prefix/${encodeSegment(remoteFileName)}"
            "${rawUrl.trimEnd('/')}/$path"
        }

        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .put(CountingFileRequestBody(file, "video/mp4".toMediaType(), onProgress))
        if (config.bearerToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.bearerToken.trim()}")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(200)
                throw IllegalStateException("HTTP ${resp.code}: $err")
            }
            return CloudUploadResult(
                remoteUrl = targetUrl,
                httpCode = resp.code,
                bytesUploaded = file.length()
            )
        }
    }

    private fun testCustomHttpConnection(config: CloudStorageConfigEntity): Pair<Int, String> {
        val reqBuilder = Request.Builder()
            .url(config.endpointUrl.trim())
            .head()
        if (config.bearerToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.bearerToken.trim()}")
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            val ok = resp.isSuccessful || resp.code == 405
            val msg = if (ok) "Endpoint responded (HTTP ${resp.code})" else "HTTP ${resp.code}: ${resp.message}"
            return resp.code to msg
        }
    }

    private fun buildWebDavAuthHeader(config: CloudStorageConfigEntity): String? {
        if (config.bearerToken.isNotBlank()) {
            return "Bearer ${config.bearerToken.trim()}"
        }
        if (config.accessKey.isNotBlank()) {
            return okhttp3.Credentials.basic(config.accessKey, config.secretKey)
        }
        return null
    }

    private data class S3Target(val fullUrl: String)

    private fun resolveS3Url(endpointUrl: String, bucketName: String, objectKey: String): S3Target {
        val cleanEndpoint = endpointUrl.trim().trimEnd('/')
        val cleanBucket = bucketName.trim().trim('/')
        val cleanKey = objectKey.trimStart('/')
        val encodedKey = cleanKey.split('/').filter { it.isNotBlank() }.joinToString("/") { encodeSegment(it) }
        val baseWithBucket = if (cleanBucket.isNotBlank() && !cleanEndpoint.endsWith("/$cleanBucket")) {
            "$cleanEndpoint/$cleanBucket"
        } else {
            cleanEndpoint
        }
        val fullUrl = if (encodedKey.isBlank()) baseWithBucket else "$baseWithBucket/$encodedKey"
        return S3Target(fullUrl)
    }

    private fun buildObjectKey(folderPrefix: String, fileName: String): String {
        val cleanPrefix = folderPrefix.trim().trim('/')
        return if (cleanPrefix.isBlank()) fileName else "$cleanPrefix/$fileName"
    }

    private fun signAwsV4(
        method: String,
        fullUrl: String,
        region: String,
        service: String,
        accessKey: String,
        secretKey: String,
        payloadHash: String,
        contentType: String? = null
    ): Map<String, String> {
        val uri = URI(fullUrl)
        val host = if (uri.port > 0 && uri.port != 80 && uri.port != 443) {
            "${uri.host}:${uri.port}"
        } else {
            uri.host
        }
        val now = Date()
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
        val dateStamp = amzDate.substring(0, 8)

        val canonicalUri = uri.rawPath.ifBlank { "/" }
        val canonicalQuery = uri.rawQuery.orEmpty().split('&')
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString("&")

        val headerMap = linkedMapOf<String, String>()
        if (!contentType.isNullOrBlank()) {
            headerMap["content-type"] = contentType
        }
        headerMap["host"] = host
        headerMap["x-amz-content-sha256"] = payloadHash
        headerMap["x-amz-date"] = amzDate

        val sortedKeys = headerMap.keys.sorted()
        val canonicalHeaders = sortedKeys.joinToString("") { "$it:${headerMap[it]?.trim()}\n" }
        val signedHeaders = sortedKeys.joinToString(";")

        val canonicalRequest = buildString {
            append(method).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append(amzDate).append('\n')
            append(credentialScope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        val kDate = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        val kSigning = hmacSha256(kService, "aws4_request")
        val signature = hmacSha256(kSigning, stringToSign).toHex()

        val authorization =
            "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        return buildMap {
            put("Host", host)
            put("x-amz-date", amzDate)
            put("x-amz-content-sha256", payloadHash)
            put("Authorization", authorization)
            if (!contentType.isNullOrBlank()) put("Content-Type", contentType)
        }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private class CountingFileRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: suspend (Int) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1L)
            val buffer = ByteArray(32768)
            var uploaded = 0L
            var lastReported = -1
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    uploaded += read
                    val pct = ((uploaded * 100L) / total).toInt().coerceIn(0, 100)
                    if (pct != lastReported && (pct - lastReported >= 3 || pct == 100)) {
                        lastReported = pct
                        kotlinx.coroutines.runBlocking { onProgress(pct) }
                    }
                }
            }
        }
    }
}
