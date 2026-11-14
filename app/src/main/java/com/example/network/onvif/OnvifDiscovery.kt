package com.example.network.onvif

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class LocalNetworkInfo(
    val ipAddress: String,
    val prefixLength: Int,
    val subnetBase: String,
    val broadcastIp: String,
    val interfaceName: String
)

class OnvifDiscovery(private val context: Context) {

    fun detectLocalNetwork(): LocalNetworkInfo? {
        // 1. Prefer active network LinkProperties (reliable on Android 7+ without location permission)
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val activeNet = cm.activeNetwork
            val linkProps = if (activeNet != null) cm.getLinkProperties(activeNet) else null
            val v4Link = linkProps?.linkAddresses?.firstOrNull { la ->
                val addr = la.address
                addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
            }
            val ip = v4Link?.address?.hostAddress
            if (!ip.isNullOrBlank()) {
                val prefix = v4Link.prefixLength.takeIf { it in 8..30 } ?: 24
                return buildLocalNetworkInfo(ip, prefix, linkProps?.interfaceName.orEmpty())
            }
        }

        // 2. Fallback to WifiManager connectionInfo IPv4 integer
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiIpInt = runCatching { wifiManager?.connectionInfo?.ipAddress ?: 0 }.getOrDefault(0)
        if (wifiIpInt != 0) {
            val wifiIp = parseIpv4FromInt(wifiIpInt)
            if (wifiIp != "0.0.0.0" && !wifiIp.startsWith("127.")) {
                return buildLocalNetworkInfo(wifiIp, 24, "wlan0")
            }
        }

        // 3. Fallback to enumerating active NetworkInterfaces (prefer private LAN ranges & wlan/eth)
        val candidates = mutableListOf<Triple<String, Int, String>>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress ?: continue
                        val prefix = iface.interfaceAddresses
                            .firstOrNull { it.address == addr }
                            ?.networkPrefixLength?.toInt()
                            ?.takeIf { it in 8..30 }
                            ?: 24
                        candidates.add(Triple(host, prefix, iface.name.orEmpty()))
                    }
                }
            }
        }
        val best = candidates.firstOrNull { (ip, _, name) ->
            (name.startsWith("wlan") || name.startsWith("eth")) && isPrivateIpv4(ip)
        } ?: candidates.firstOrNull { (ip, _, _) -> isPrivateIpv4(ip) }
            ?: candidates.firstOrNull()

        return best?.let { (ip, prefix, name) -> buildLocalNetworkInfo(ip, prefix, name) }
    }

    suspend fun discoverCameras(
        timeoutMs: Long = 3500L,
        localNetwork: LocalNetworkInfo? = detectLocalNetwork()
    ): List<DiscoveredOnvifCamera> = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("camvault_onvif_discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        val discoveredByHost = linkedMapOf<String, DiscoveredOnvifCamera>()
        val socket = runCatching {
            val localIp = localNetwork?.ipAddress
            if (!localIp.isNullOrBlank()) {
                DatagramSocket(0, InetAddress.getByName(localIp))
            } else {
                DatagramSocket()
            }
        }.getOrElse { DatagramSocket() }

        try {
            socket.soTimeout = 350
            socket.broadcast = true
            val targets = buildList {
                add(InetAddress.getByName("239.255.255.250"))
                localNetwork?.broadcastIp?.takeIf { it.isNotBlank() }?.let { bcast ->
                    runCatching { InetAddress.getByName(bcast) }.getOrNull()?.let { add(it) }
                }
            }

            val probeTypes = listOf("dn:NetworkVideoTransmitter", "tds:Device")
            for (target in targets) {
                for (type in probeTypes) {
                    val xml = buildProbeXml(UUID.randomUUID().toString(), type)
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(bytes, bytes.size, target, 3702)
                    runCatching { socket.send(packet) }
                }
            }

            val wsTimeout = timeoutMs.coerceAtMost(2000L)
            val deadline = System.currentTimeMillis() + wsTimeout
            val buffer = ByteArray(16384)
            while (System.currentTimeMillis() < deadline) {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(responsePacket)
                    val xmlStr = String(
                        responsePacket.data,
                        0,
                        responsePacket.length,
                        Charsets.UTF_8
                    )
                    val senderHost = responsePacket.address?.hostAddress.orEmpty()
                    parseProbeMatch(xmlStr, senderHost).forEach { cam ->
                        discoveredByHost[cam.host] = cam
                    }
                } catch (_: SocketTimeoutException) {
                    // Continue until WS-Discovery window closes.
                }
            }
        } finally {
            runCatching { socket.close() }
            runCatching {
                if (multicastLock?.isHeld == true) multicastLock.release()
            }
        }

        // Active /24 subnet port probe around the detected current device IP so cameras that
        // suppress WS-Discovery multicast (such as ONVIF port 8899 cameras) are still found.
        if (localNetwork != null && localNetwork.subnetBase.isNotBlank()) {
            val subnetHosts = scanSubnetForCameras(
                subnetBase = localNetwork.subnetBase,
                excludeIp = localNetwork.ipAddress,
                alreadyFoundHosts = discoveredByHost.keys
            )
            for (cam in subnetHosts) {
                discoveredByHost.putIfAbsent(cam.host, cam)
            }
        }

        discoveredByHost.values.toList()
    }

    private suspend fun scanSubnetForCameras(
        subnetBase: String,
        excludeIp: String,
        alreadyFoundHosts: Set<String>
    ): List<DiscoveredOnvifCamera> = coroutineScope {
        val semaphore = Semaphore(48)
        val probePorts = listOf(8899, 80, 8080, 554)
        (1..254).map { hostOctet ->
            val ip = "$subnetBase$hostOctet"
            async(Dispatchers.IO) {
                if (ip == excludeIp || ip in alreadyFoundHosts) return@async null
                semaphore.withPermit {
                    var openOnvifPort: Int? = null
                    var rtspOpen = false
                    for (port in probePorts) {
                        if (isTcpPortOpen(ip, port, 240)) {
                            if (port == 554) {
                                rtspOpen = true
                            } else if (openOnvifPort == null) {
                                openOnvifPort = port
                            }
                        }
                    }
                    when {
                        openOnvifPort != null -> DiscoveredOnvifCamera(
                            xAddr = "http://$ip:$openOnvifPort/onvif/device_service",
                            host = ip,
                            port = openOnvifPort,
                            name = "ONVIF Camera ($ip:$openOnvifPort)",
                            hardwareModel = "Port $openOnvifPort" + if (rtspOpen) " • RTSP:554" else ""
                        )
                        rtspOpen -> DiscoveredOnvifCamera(
                            xAddr = "http://$ip:8899/onvif/device_service",
                            host = ip,
                            port = 8899,
                            name = "RTSP Camera ($ip)",
                            hardwareModel = "RTSP:554"
                        )
                        else -> null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { s.close() }
        }
    }

    internal fun buildLocalNetworkInfo(
        ip: String,
        prefixLength: Int,
        interfaceName: String
    ): LocalNetworkInfo {
        val parts = ip.split(".")
        val subnetBase = if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}." else ""
        val broadcastIp = computeBroadcastAddress(ip, prefixLength)
        return LocalNetworkInfo(
            ipAddress = ip,
            prefixLength = prefixLength,
            subnetBase = subnetBase,
            broadcastIp = broadcastIp,
            interfaceName = interfaceName
        )
    }

    internal fun parseIpv4FromInt(ipInt: Int): String {
        val b0 = ipInt and 0xFF
        val b1 = (ipInt ushr 8) and 0xFF
        val b2 = (ipInt ushr 16) and 0xFF
        val b3 = (ipInt ushr 24) and 0xFF
        return "$b0.$b1.$b2.$b3"
    }

    internal fun computeBroadcastAddress(ip: String, prefixLength: Int): String {
        val octets = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return "255.255.255.255"
        val ipWord = ((octets[0] and 0xFF) shl 24) or
            ((octets[1] and 0xFF) shl 16) or
            ((octets[2] and 0xFF) shl 8) or
            (octets[3] and 0xFF)
        val clampedPrefix = prefixLength.coerceIn(0, 32)
        val mask = if (clampedPrefix == 0) 0 else (-1 shl (32 - clampedPrefix))
        val bcast = ipWord or mask.inv()
        return listOf(
            (bcast ushr 24) and 0xFF,
            (bcast ushr 16) and 0xFF,
            (bcast ushr 8) and 0xFF,
            bcast and 0xFF
        ).joinToString(".")
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(ip)
    }

    private fun buildProbeXml(messageUuid: String, targetType: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
            xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
            xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
            xmlns:dn="http://www.onvif.org/ver10/network/wsdl"
            xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
            <e:Header>
                <w:MessageID>uuid:$messageUuid</w:MessageID>
                <w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                <w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
            </e:Header>
            <e:Body>
                <d:Probe>
                    <d:Types>$targetType</d:Types>
                </d:Probe>
            </e:Body>
        </e:Envelope>
    """.trimIndent()

    internal fun parseProbeMatch(xml: String, fallbackHost: String): List<DiscoveredOnvifCamera> {
        val results = mutableListOf<DiscoveredOnvifCamera>()
        runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))

            var inMatch = false
            var xAddrsRaw = ""
            var scopesRaw = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name.orEmpty()
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when {
                            tagName.equals("ProbeMatch", ignoreCase = true) -> {
                                inMatch = true
                                xAddrsRaw = ""
                                scopesRaw = ""
                            }
                            inMatch && tagName.equals("XAddrs", ignoreCase = true) -> {
                                xAddrsRaw = parser.nextText().trim()
                            }
                            inMatch && tagName.equals("Scopes", ignoreCase = true) -> {
                                scopesRaw = parser.nextText().trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("ProbeMatch", ignoreCase = true) && inMatch) {
                            inMatch = false
                            val xAddrs = xAddrsRaw.split(Regex("\\s+")).filter { it.startsWith("http") }
                            val primaryXAddr = xAddrs.firstOrNull { !it.contains(":") || !it.startsWith("http://[") }
                                ?: xAddrs.firstOrNull()
                            if (primaryXAddr != null) {
                                val scopes = scopesRaw.split(Regex("\\s+")).filter { it.isNotBlank() }
                                val uri = runCatching { URI(primaryXAddr) }.getOrNull()
                                val host = uri?.host?.takeIf { it.isNotBlank() } ?: fallbackHost
                                val port = if (uri != null && uri.port > 0) uri.port else 80
                                val nameScope = extractScopeValue(scopes, "onvif://www.onvif.org/name/")
                                val modelScope = extractScopeValue(scopes, "onvif://www.onvif.org/hardware/")
                                results.add(
                                    DiscoveredOnvifCamera(
                                        xAddr = primaryXAddr,
                                        host = host,
                                        port = port,
                                        name = nameScope.ifBlank { modelScope.ifBlank { "ONVIF Camera ($host)" } },
                                        hardwareModel = modelScope,
                                        scopes = scopes
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return results
    }

    private fun extractScopeValue(scopes: List<String>, prefix: String): String {
        val match = scopes.firstOrNull { it.startsWith(prefix, ignoreCase = true) } ?: return ""
        val raw = match.substring(prefix.length)
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
}
