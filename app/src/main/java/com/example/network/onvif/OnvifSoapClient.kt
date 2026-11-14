package com.example.network.onvif

import android.net.Uri
import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class OnvifSoapClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val soap12MediaType = "application/soap+xml; charset=utf-8".toMediaType()
    private val soap11MediaType = "text/xml; charset=utf-8".toMediaType()

    suspend fun probeCamera(
        hostOrUrl: String,
        port: Int = 80,
        username: String = "",
        password: String = ""
    ): Result<OnvifProbeResult> = withContext(Dispatchers.IO) {
        runCatching {
            val serviceUrl = normalizeServiceUrl(hostOrUrl, port)
            val capabilities = getCapabilitiesInternal(serviceUrl, username, password)
            val mediaUrl = capabilities.first.ifBlank {
                serviceUrl.replace("device_service", "media_service")
            }
            val ptzUrl = capabilities.second

            val deviceInfo = runCatching {
                getDeviceInformationInternal(serviceUrl, username, password)
            }.getOrDefault(OnvifDeviceInfo())

            val serviceHost = runCatching { URI(serviceUrl).host }.getOrNull().orEmpty()
            val rawProfiles = getProfilesInternal(mediaUrl, username, password)
            val enrichedProfiles = rawProfiles.map { profile ->
                val rawUri = runCatching {
                    getStreamUriInternal(mediaUrl, profile.token, username, password)
                }.getOrDefault("")
                val normalizedUri = rewriteInvalidRtspHost(rawUri, serviceHost)
                val authUri = injectRtspCredentials(normalizedUri, username, password, serviceHost)
                profile.copy(rtspUri = authUri)
            }

            OnvifProbeResult(
                serviceUrl = serviceUrl,
                mediaServiceUrl = mediaUrl,
                ptzServiceUrl = ptzUrl,
                deviceInfo = deviceInfo,
                profiles = enrichedProfiles,
                ptzSupported = ptzUrl.isNotBlank()
            )
        }
    }

    suspend fun continuousMove(
        ptzServiceUrl: String,
        profileToken: String,
        direction: PtzDirection,
        username: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(ptzServiceUrl.isNotBlank()) { "Camera does not advertise an ONVIF PTZ service." }
            require(profileToken.isNotBlank()) { "Missing ONVIF media profile token." }
            val body = """
                <tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
                    xmlns:tt="http://www.onvif.org/ver10/schema">
                    <tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>
                    <tptz:Velocity>
                        <tt:PanTilt x="${direction.pan}" y="${direction.tilt}"/>
                        <tt:Zoom x="${direction.zoom}"/>
                    </tptz:Velocity>
                </tptz:ContinuousMove>
            """.trimIndent()
            executeSoap(
                url = ptzServiceUrl,
                action = "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove",
                bodyXml = body,
                username = username,
                password = password
            )
            Unit
        }
    }

    suspend fun stopPtz(
        ptzServiceUrl: String,
        profileToken: String,
        username: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (ptzServiceUrl.isBlank() || profileToken.isBlank()) return@runCatching
            val body = """
                <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                    <tptz:ProfileToken>${escapeXml(profileToken)}</tptz:ProfileToken>
                    <tptz:PanTilt>true</tptz:PanTilt>
                    <tptz:Zoom>true</tptz:Zoom>
                </tptz:Stop>
            """.trimIndent()
            executeSoap(
                url = ptzServiceUrl,
                action = "http://www.onvif.org/ver20/ptz/wsdl/Stop",
                bodyXml = body,
                username = username,
                password = password
            )
            Unit
        }
    }

    private fun getCapabilitiesInternal(
        serviceUrl: String,
        username: String,
        password: String
    ): Pair<String, String> {
        val body = """
            <tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
                <tds:Category>All</tds:Category>
            </tds:GetCapabilities>
        """.trimIndent()
        val xml = executeSoap(
            url = serviceUrl,
            action = "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
            bodyXml = body,
            username = username,
            password = password
        )
        return parseCapabilities(xml)
    }

    private fun getDeviceInformationInternal(
        serviceUrl: String,
        username: String,
        password: String
    ): OnvifDeviceInfo {
        val body = """<tds:GetDeviceInformation xmlns:tds="http://www.onvif.org/ver10/device/wsdl"/>"""
        val xml = executeSoap(
            url = serviceUrl,
            action = "http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation",
            bodyXml = body,
            username = username,
            password = password
        )
        return parseDeviceInfo(xml)
    }

    private fun getProfilesInternal(
        mediaUrl: String,
        username: String,
        password: String
    ): List<OnvifMediaProfile> {
        val body = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
        val xml = executeSoap(
            url = mediaUrl,
            action = "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
            bodyXml = body,
            username = username,
            password = password
        )
        return parseProfiles(xml)
    }

    private fun getStreamUriInternal(
        mediaUrl: String,
        profileToken: String,
        username: String,
        password: String
    ): String {
        val body = """
            <trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                xmlns:tt="http://www.onvif.org/ver10/schema">
                <trt:StreamSetup>
                    <tt:Stream>RTP-Unicast</tt:Stream>
                    <tt:Transport>
                        <tt:Protocol>RTSP</tt:Protocol>
                    </tt:Transport>
                </trt:StreamSetup>
                <trt:ProfileToken>${escapeXml(profileToken)}</trt:ProfileToken>
            </trt:GetStreamUri>
        """.trimIndent()
        val xml = executeSoap(
            url = mediaUrl,
            action = "http://www.onvif.org/ver10/media/wsdl/GetStreamUri",
            bodyXml = body,
            username = username,
            password = password
        )
        return parseStreamUri(xml)
    }

    private fun executeSoap(
        url: String,
        action: String,
        bodyXml: String,
        username: String,
        password: String
    ): String {
        val envelope = buildSoapEnvelope(bodyXml, username, password)
        val request12 = Request.Builder()
            .url(url)
            .post(envelope.toRequestBody(soap12MediaType))
            .header("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
            .build()

        client.newCall(request12).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (resp.isSuccessful && !respBody.contains("Fault", ignoreCase = false)) {
                return respBody
            }
            if (resp.code == 415 || resp.code == 400) {
                // Retry with SOAP 1.1 envelope for legacy ONVIF devices
                val soap11Envelope = buildSoapEnvelope(bodyXml, username, password, soap11 = true)
                val request11 = Request.Builder()
                    .url(url)
                    .post(soap11Envelope.toRequestBody(soap11MediaType))
                    .header("SOAPAction", "\"$action\"")
                    .build()
                client.newCall(request11).execute().use { resp11 ->
                    val body11 = resp11.body?.string().orEmpty()
                    if (!resp11.isSuccessful) {
                        throw IllegalStateException("ONVIF HTTP ${resp11.code}: ${extractFaultReason(body11)}")
                    }
                    return body11
                }
            }
            throw IllegalStateException("ONVIF HTTP ${resp.code}: ${extractFaultReason(respBody)}")
        }
    }

    private fun buildSoapEnvelope(
        bodyXml: String,
        username: String,
        password: String,
        soap11: Boolean = false
    ): String {
        val envNs = if (soap11) {
            "http://schemas.xmlsoap.org/soap/envelope/"
        } else {
            "http://www.w3.org/2003/05/soap-envelope"
        }

        val securityHeader = if (username.isNotBlank()) {
            val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(System.currentTimeMillis())

            val sha1 = MessageDigest.getInstance("SHA-1").apply {
                update(nonceBytes)
                update(created.toByteArray(Charsets.UTF_8))
                update(password.toByteArray(Charsets.UTF_8))
            }.digest()

            val passwordDigest = Base64.encodeToString(sha1, Base64.NO_WRAP)
            val nonceBase64 = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)

            """
            <s:Header>
                <Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                    <UsernameToken>
                        <Username>${escapeXml(username)}</Username>
                        <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$passwordDigest</Password>
                        <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</Nonce>
                        <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
                    </UsernameToken>
                </Security>
            </s:Header>
            """.trimIndent()
        } else {
            ""
        }

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="$envNs">
                $securityHeader
                <s:Body>
                    $bodyXml
                </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun parseCapabilities(xml: String): Pair<String, String> {
        var mediaUrl = ""
        var ptzUrl = ""
        var section = ""
        runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val name = parser.name.orEmpty()
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when {
                            name.equals("Media", ignoreCase = true) -> section = "media"
                            name.equals("PTZ", ignoreCase = true) -> section = "ptz"
                            name.equals("Imaging", ignoreCase = true) ||
                                name.equals("Events", ignoreCase = true) ||
                                name.equals("Analytics", ignoreCase = true) -> section = ""
                            name.equals("XAddr", ignoreCase = true) -> {
                                val text = parser.nextText().trim()
                                if (section == "media" && mediaUrl.isBlank()) mediaUrl = text
                                if (section == "ptz" && ptzUrl.isBlank()) ptzUrl = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name.equals("Media", true) || name.equals("PTZ", true)) {
                            section = ""
                        }
                    }
                }
                event = parser.next()
            }
        }
        return mediaUrl to ptzUrl
    }

    private fun parseDeviceInfo(xml: String): OnvifDeviceInfo {
        var manufacturer = ""
        var model = ""
        var firmware = ""
        var serial = ""
        var hardwareId = ""
        runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "Manufacturer" -> manufacturer = parser.nextText().trim()
                        "Model" -> model = parser.nextText().trim()
                        "FirmwareVersion" -> firmware = parser.nextText().trim()
                        "SerialNumber" -> serial = parser.nextText().trim()
                        "HardwareId" -> hardwareId = parser.nextText().trim()
                    }
                }
                event = parser.next()
            }
        }
        return OnvifDeviceInfo(manufacturer, model, firmware, serial, hardwareId)
    }

    private fun parseProfiles(xml: String): List<OnvifMediaProfile> {
        val list = mutableListOf<OnvifMediaProfile>()
        runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))

            var inProfile = false
            var inVideoEncoder = false
            var inAudioEncoder = false
            var token = ""
            var profileName = ""
            var videoEnc = ""
            var width = 0
            var height = 0
            var hasAudio = false
            var audioEnc = ""
            var audioSampleRate = 0

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name.orEmpty()
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (tag) {
                            "Profiles" -> {
                                inProfile = true
                                token = parser.getAttributeValue(null, "token").orEmpty()
                                profileName = ""
                                videoEnc = ""
                                width = 0
                                height = 0
                                hasAudio = false
                                audioEnc = ""
                                audioSampleRate = 0
                            }
                            "VideoEncoderConfiguration" -> if (inProfile) inVideoEncoder = true
                            "AudioEncoderConfiguration" -> if (inProfile) {
                                inAudioEncoder = true
                                hasAudio = true
                            }
                            "AudioSourceConfiguration" -> if (inProfile) hasAudio = true
                            "Name" -> if (inProfile && !inVideoEncoder && !inAudioEncoder && profileName.isBlank()) {
                                profileName = parser.nextText().trim()
                            }
                            "Encoding" -> {
                                val text = parser.nextText().trim()
                                if (inVideoEncoder) videoEnc = text
                                if (inAudioEncoder) audioEnc = text
                            }
                            "Width" -> if (inVideoEncoder) {
                                width = parser.nextText().trim().toIntOrNull() ?: 0
                            }
                            "Height" -> if (inVideoEncoder) {
                                height = parser.nextText().trim().toIntOrNull() ?: 0
                            }
                            "SampleRate" -> if (inAudioEncoder) {
                                audioSampleRate = parser.nextText().trim().toIntOrNull() ?: 0
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (tag) {
                            "VideoEncoderConfiguration" -> inVideoEncoder = false
                            "AudioEncoderConfiguration" -> inAudioEncoder = false
                            "Profiles" -> {
                                if (inProfile && token.isNotBlank()) {
                                    list.add(
                                        OnvifMediaProfile(
                                            token = token,
                                            name = profileName.ifBlank { token },
                                            videoEncoding = videoEnc,
                                            width = width,
                                            height = height,
                                            hasAudio = hasAudio,
                                            audioEncoding = audioEnc,
                                            audioSampleRate = audioSampleRate
                                        )
                                    )
                                }
                                inProfile = false
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
        return list
    }

    private fun parseStreamUri(xml: String): String {
        runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.equals("Uri", ignoreCase = true)) {
                    return parser.nextText().trim()
                }
                event = parser.next()
            }
        }
        return ""
    }

    private fun extractFaultReason(body: String): String {
        val textMatch = Regex("<(?:[a-zA-Z0-9]+:)?Text[^>]*>([^<]+)</").find(body)
        if (textMatch != null) return textMatch.groupValues[1].trim()
        val faultString = Regex("<faultstring[^>]*>([^<]+)</faultstring>").find(body)
        if (faultString != null) return faultString.groupValues[1].trim()
        return body.take(140).ifBlank { "Request rejected by camera" }
    }

    companion object {
        fun normalizeServiceUrl(hostOrUrl: String, port: Int): String {
            val trimmed = hostOrUrl.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return trimmed
            }
            return "http://$trimmed:$port/onvif/device_service"
        }

        fun rewriteInvalidRtspHost(rtspUrl: String, fallbackHost: String): String {
            if (rtspUrl.isBlank() || fallbackHost.isBlank()) return rtspUrl
            val uri = runCatching { URI(rtspUrl) }.getOrNull() ?: return rtspUrl
            val host = uri.host ?: return rtspUrl
            if (host == "0.0.0.0" || host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) {
                val portPart = if (uri.port > 0) ":${uri.port}" else ""
                val pathPart = uri.rawPath.orEmpty()
                val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
                val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
                return "${uri.scheme ?: "rtsp"}://$userInfo$fallbackHost$portPart$pathPart$queryPart"
            }
            return rtspUrl
        }

        fun injectRtspCredentials(
            rtspUrl: String,
            username: String,
            password: String,
            expectedHost: String
        ): String {
            if (rtspUrl.isBlank() || username.isBlank() || expectedHost.isBlank()) return rtspUrl
            val uri = runCatching { URI(rtspUrl) }.getOrNull() ?: return rtspUrl
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "rtsp" && scheme != "rtsps") return rtspUrl
            val host = uri.host ?: return rtspUrl
            if (!hostsMatch(host, expectedHost)) return rtspUrl
            if (uri.rawUserInfo != null) return rtspUrl
            if (rtspUrl.contains("user=", ignoreCase = true) && rtspUrl.contains("password=", ignoreCase = true)) {
                return rtspUrl
            }
            if (uri.port !in -1..65535) return rtspUrl

            val authority = uri.rawAuthority ?: return rtspUrl
            val encUser = Uri.encode(username)
            val encPass = Uri.encode(password)
            val pathPart = uri.rawPath.orEmpty()
            val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragmentPart = uri.rawFragment?.let { "#$it" }.orEmpty()
            return "${uri.scheme}://$encUser:$encPass@$authority$pathPart$queryPart$fragmentPart"
        }

        private fun hostsMatch(uriHost: String, expectedHost: String): Boolean {
            fun normalize(host: String) = host.trim()
                .removeSurrounding("[", "]")
                .trimEnd('.')
                .lowercase(Locale.ROOT)
            val expected = normalize(expectedHost)
            return expected.isNotEmpty() && normalize(uriHost) == expected
        }

        private fun escapeXml(input: String): String =
            input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
    }
}
