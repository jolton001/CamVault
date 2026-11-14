package com.example.security

import android.content.Context
import com.example.data.local.CloudProviderType
import java.io.File
import java.net.URI
import java.util.Locale

object SecurityGuards {

    private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u001F\\u007F]")
    private val URL_CREDS_REGEX = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\\s]+)@")
    private val SENSITIVE_QUERY_REGEX =
        Regex("(?i)([?&](?:password|pwd|pass|token|sid|auth|secret|signature|x-amz-signature)=)([^&\\s]+)")
    private val AUTH_HEADER_REGEX =
        Regex("(?i)(Authorization:\\s*(?:Basic|Bearer|Digest|AWS4-HMAC-SHA256)\\s+)([^\\r\\n]+)")

    fun isLoopbackOrPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase(Locale.US).trim('[', ']')
        if (h.isBlank()) return false
        if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true

        val parts = h.split('.')
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull()
            val b = parts[1].toIntOrNull()
            val c = parts[2].toIntOrNull()
            val d = parts[3].toIntOrNull()
            if (a != null && b != null && c != null && d != null &&
                a in 0..255 && b in 0..255 && c in 0..255 && d in 0..255
            ) {
                if (a == 127) return true
                if (a == 10) return true
                if (a == 192 && b == 168) return true
                if (a == 172 && b in 16..31) return true
                if (a == 169 && b == 254) return true
            }
        }
        return false
    }

    /**
     * Requires HTTPS for all public/internet cloud endpoints (and always for MEGA domains),
     * permitting plain HTTP only for loopback/RFC1918 local LAN hosts (e.g., local MinIO/WebDAV NAS).
     */
    fun requireSafeCloudUrl(url: String, providerType: CloudProviderType) {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid cloud endpoint URL.")
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        require(host.isNotBlank()) { "Cloud endpoint URL must include a host." }
        require(scheme == "https" || scheme == "http") {
            "Unsupported cloud URL scheme '$scheme'. Only HTTPS (or local LAN HTTP) is allowed."
        }

        if (scheme == "http") {
            if (providerType == CloudProviderType.MEGA &&
                (host.endsWith("mega.co.nz") || host.endsWith("mega.nz"))
            ) {
                throw IllegalArgumentException("MEGA Drive connections must use HTTPS.")
            }
            if (!isLoopbackOrPrivateHost(host)) {
                throw IllegalArgumentException(
                    "Cleartext HTTP is blocked for public host '$host'. Use HTTPS to protect credentials."
                )
            }
        }
    }

    /**
     * Ensures a remote upload filename cannot escape the target folder via path traversal.
     */
    fun sanitizeRemoteFileName(name: String): String {
        val cleaned = name.replace(CONTROL_CHAR_REGEX, "").trim()
        require(!cleaned.contains("..")) { "Unsafe remote file name rejected." }
        val base = cleaned
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        require(base.isNotBlank() && base != ".") {
            "Unsafe remote file name rejected."
        }
        return base
    }

    /**
     * Normalizes remote folder prefixes while blocking '..' path traversal segments.
     */
    fun sanitizeFolderPrefix(prefix: String): String {
        val cleaned = prefix.replace(CONTROL_CHAR_REGEX, "").replace('\\', '/').trim().trim('/')
        val segments = cleaned.split('/').filter { it.isNotBlank() }
        require(segments.none { it == "." || it == ".." || it.contains("..") }) {
            "Unsafe cloud folder path rejected."
        }
        return segments.joinToString("/")
    }

    /**
     * Masks embedded RTSP/HTTP credentials and sensitive query tokens before UI display or logging.
     */
    fun redactUrlCredentials(url: String): String {
        if (url.isBlank()) return url
        var out = url.replace(URL_CREDS_REGEX) { match ->
            val scheme = match.groupValues[1]
            val userInfo = match.groupValues[2]
            val userPart = userInfo.substringBefore(':', "")
            if (userInfo.contains(':') && userPart.isNotEmpty()) {
                "$scheme$userPart:•••@"
            } else {
                "$scheme•••@"
            }
        }
        out = out.replace(SENSITIVE_QUERY_REGEX) { match ->
            "${match.groupValues[1]}•••"
        }
        return out
    }

    fun sanitizeErrorMessage(message: String?, fallback: String = "Operation failed"): String {
        val raw = message?.takeIf { it.isNotBlank() } ?: return fallback
        var cleaned = redactUrlCredentials(raw)
        cleaned = cleaned.replace(AUTH_HEADER_REGEX) { match ->
            "${match.groupValues[1]}•••"
        }
        return cleaned.take(220)
    }

    /**
     * Confines local file deletions to files located strictly inside CamVault's private storage dirs.
     */
    fun isPathInsideAppStorage(context: Context, filePath: String): Boolean {
        if (filePath.isBlank()) return false
        val targetCanonical = runCatching { File(filePath).canonicalPath }.getOrNull() ?: return false
        val allowedRoots = listOfNotNull(
            runCatching { context.filesDir.canonicalPath }.getOrNull(),
            runCatching { context.cacheDir.canonicalPath }.getOrNull(),
            runCatching { context.getExternalFilesDir(null)?.canonicalPath }.getOrNull()
        )
        return allowedRoots.any { root ->
            targetCanonical == root || targetCanonical.startsWith("$root${File.separator}")
        }
    }
}
