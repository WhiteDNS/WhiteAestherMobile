package com.whitedns.whiteaesther.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress

enum class EngineMode(val wireName: String) {
    TUN("tun"),
    PROXY("proxy"),
}

enum class MasqueTransport(val wireName: String, val label: String) {
    H3("h3", "MASQUE H3"),
    H2("h2", "MASQUE H2"),
}

enum class ScanStrategy(val wireName: String, val label: String) {
    TURBO("turbo", "Turbo"),
    BALANCED("balanced", "Balanced"),
    THOROUGH("thorough", "Thorough"),
    STEALTH("stealth", "Stealth"),
    IRONCLAD("ironclad", "Ironclad"),
}

enum class EndpointMode(val label: String) {
    AUTOMATIC("Automatic"),
    CUSTOM_FIRST("Custom first"),
    CUSTOM_ONLY("Custom only"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

object EndpointAddress {
    fun normalize(value: String): String? {
        val input = value.trim()
        val (host, portText) = when {
            input.startsWith('[') -> {
                val closing = input.indexOf(']')
                if (closing <= 1 || closing + 1 >= input.length || input[closing + 1] != ':') return null
                input.substring(1, closing) to input.substring(closing + 2)
            }
            input.count { it == ':' } == 1 -> input.substringBefore(':') to input.substringAfter(':')
            else -> return null
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        val isIpv6 = host.contains(':')
        if (!isIpv6) {
            val octets = host.split('.')
            if (octets.size != 4) return null
            val normalized = octets.map { octet ->
                if (octet.isEmpty() || octet.any { !it.isDigit() }) return null
                octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            }
            return "${normalized.joinToString(".")}:$port"
        } else {
            if (host.any { it != ':' && it != '.' && it.digitToIntOrNull(16) == null }) return null
        }
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        return if (address is Inet6Address) "[${address.hostAddress}]:$port" else null
    }
}

data class AppSettings(
    val mode: EngineMode = EngineMode.TUN,
    val proxyPort: Int = 1819,
    val transport: MasqueTransport = MasqueTransport.H3,
    val scanStrategy: ScanStrategy = ScanStrategy.BALANCED,
    val dualStack: Boolean = true,
    val validationEnabled: Boolean = true,
    val noizeProfile: String = "firewall",
    val endpointMode: EndpointMode = EndpointMode.AUTOMATIC,
    val customEndpoint: String = "",
    // Presentation only -- deliberately absent from toNativeJson.
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showAdvanced: Boolean = false,
) {
    fun endpointValidationError(): String? = when {
        endpointMode == EndpointMode.AUTOMATIC -> null
        customEndpoint.isBlank() -> "Enter a custom endpoint"
        EndpointAddress.normalize(customEndpoint) == null -> "Endpoint must be a valid IP:port"
        else -> null
    }

    fun toNativeJson(context: Context): String {
        val json = JSONObject()
            .put("mode", mode.wireName)
            .put("configPath", File(context.filesDir, "aether.toml").absolutePath)
            .put("listenPort", proxyPort)
            .put("scanMode", scanStrategy.wireName)
            .put("ipScan", if (dualStack) "both" else "v4")
            .put("transport", transport.wireName)
            .put("noize", noizeProfile)
            .put("validationEnabled", validationEnabled)
            .put("peerFallback", endpointMode == EndpointMode.CUSTOM_FIRST)
        if (endpointMode != EndpointMode.AUTOMATIC) {
            EndpointAddress.normalize(customEndpoint)?.let { json.put("peer", it) }
        }
        return json.toString()
    }
}
