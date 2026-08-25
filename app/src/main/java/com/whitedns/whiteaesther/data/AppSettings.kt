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

/**
 * How the tunnel is carried.
 *
 * H3 and H2 are two framings of one protocol -- same account, same endpoints,
 * same prober -- so the engine alternates between them on a retry. WireGuard is
 * a different tunnel with its own account and its own endpoints, so it is not
 * something a retry can substitute.
 */
enum class TunnelProtocol(val wireName: String, val label: String) {
    /**
     * Work out what this network allows, rather than making the user guess.
     *
     * Not a transport the engine understands -- the service resolves it to a
     * real one before the config is built, and remembers what succeeded so the
     * next connect starts there instead of searching again.
     */
    AUTO("auto", "Automatic"),
    H3("h3", "MASQUE H3"),
    H2("h2", "MASQUE H2"),
    WIREGUARD("wg", "WireGuard"),
    WARP_IN_WARP("wiw", "WARP in WARP"),
    ;

    /** True when a failed attempt can be retried on the other framing. */
    val hasSibling: Boolean get() = this == H3 || this == H2

    /** True when the service picks the real transport rather than the user. */
    val isAutomatic: Boolean get() = this == AUTO

    /**
     * The framing an endpoint search actually probes with.
     *
     * Automatic is a policy, not a transport, so a screen naming it would be
     * describing a check that never ran. The scanner probes H2 for the same
     * reason the ladder tries it first.
     */
    val probedAs: TunnelProtocol get() = if (this == AUTO) H2 else this

    /**
     * Which set of endpoints this protocol reaches.
     *
     * Not the same question as [hasSibling]. H3 and H2 dial the identical
     * address -- only the framing differs -- and WARP-in-WARP picks its outer
     * hop from the same WireGuard endpoints as WireGuard itself. But a retry may
     * only swap the two MASQUE framings: substituting a different tunnel is a
     * different account and a different exit, which is not a retry.
     */
    val endpointFamily: EndpointFamily
        get() = when (this) {
            // Automatic only ever resolves to a MASQUE framing, so it shares
            // their endpoints.
            AUTO, H3, H2 -> EndpointFamily.MASQUE
            WIREGUARD, WARP_IN_WARP -> EndpointFamily.WARP
        }
}

/** Protocols sharing one set of endpoints, so an address found on one fits the other. */
enum class EndpointFamily {
    MASQUE,
    WARP,
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
    // Automatic, because the answer depends on the network and the user has no
    // way to know it. A fixed default of H3 meant every install on a network
    // that blocks UDP spent minutes failing before anything else was tried.
    val transport: TunnelProtocol = TunnelProtocol.AUTO,
    val scanStrategy: ScanStrategy = ScanStrategy.BALANCED,
    val dualStack: Boolean = true,
    val validationEnabled: Boolean = true,
    val noizeProfile: String = "firewall",
    val endpointMode: EndpointMode = EndpointMode.AUTOMATIC,
    val customEndpoint: String = "",
    /**
     * Which protocol the pinned address was found under.
     *
     * Endpoints are not interchangeable between protocols: a MASQUE gateway and
     * a WireGuard endpoint are different services on different ports. Pinning
     * one and then switching protocol otherwise fails at connect time with a
     * message about the address, which reads as a bad address rather than the
     * wrong protocol for it.
     */
    val customEndpointProtocol: TunnelProtocol? = null,
    // Anti-inspection measures for the HTTP/2 transport. Off by default: both
    // cost a little on a healthy network and only matter on a filtered one.
    val fragmentTls: Boolean = false,
    val encryptedHello: Boolean = false,
    // Which apps the tunnel carries. Presentation and VpnService only: the
    // engine has no idea apps exist, so this is deliberately absent from
    // toNativeJson.
    val splitTunnel: SplitTunnel = SplitTunnel(),
    // The second hop. Carried to the service beside the engine config rather
    // than inside it -- mihomo's settings are not the engine's business.
    val chain: ChainSettings = ChainSettings(),
    /**
     * Set once this device has been shown to ignore the standard exemption
     * request: the user opened the dialog and came back still not exempt.
     *
     * Not a manufacturer check. Several OEMs keep their own battery policy
     * beside Android's and grant only that one, and the class names their
     * settings live behind are undocumented and move between versions. What
     * the request did is observable; who built the phone does not have to be.
     */
    val batteryRequestIgnored: Boolean = false,
    /**
     * The user said they have handled it themselves.
     *
     * Needed because on a phone that ignores the request there is nothing left
     * to read: the platform answer stays false however well the user has
     * excluded the app in the manufacturer's own settings, so a notice that
     * only clears itself would never clear.
     */
    val batteryNoticeDismissed: Boolean = false,
    // Presentation only -- deliberately absent from toNativeJson.
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showAdvanced: Boolean = false,
) {
    /**
     * What is actually carried, in one line.
     *
     * Coverage used to be read from [mode] alone, which said "Whole device"
     * while a per-app rule quietly restricted the tunnel to one app -- a user
     * reading it had no way to tell why their traffic was not going through.
     * The rule is part of the answer, so it is part of the label.
     */
    fun coverageSummary(): String = when {
        mode != EngineMode.TUN -> "Proxy only"
        splitTunnel.mode == SplitTunnelMode.ALL -> "Whole device"
        splitTunnel.packages.isEmpty() && splitTunnel.mode == SplitTunnelMode.ONLY ->
            "No apps chosen"
        splitTunnel.packages.isEmpty() -> "Whole device"
        splitTunnel.mode == SplitTunnelMode.ONLY ->
            "${splitTunnel.packages.size} app${if (splitTunnel.packages.size == 1) "" else "s"} only"
        else -> "All apps except ${splitTunnel.packages.size}"
    }

    /** True when a per-app rule means this is not the whole device after all. */
    fun coverageIsRestricted(): Boolean =
        mode == EngineMode.TUN && !splitTunnel.isEffectivelyEverything("")

    fun endpointValidationError(): String? = when {
        endpointMode == EndpointMode.AUTOMATIC -> null
        customEndpoint.isBlank() -> "Enter a custom endpoint"
        EndpointAddress.normalize(customEndpoint) == null -> "Endpoint must be a valid IP:port"
        else -> null
    }

    /**
     * Set when the pinned address belongs to a protocol other than the one now
     * selected, which is a mismatch the user can only fix by knowing about it.
     */
    fun endpointProtocolMismatch(): TunnelProtocol? = customEndpointProtocol?.takeIf {
        endpointMode != EndpointMode.AUTOMATIC && it.endpointFamily != transport.endpointFamily
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
            .put("fragmentTls", fragmentTls)
            .put("encryptedHello", encryptedHello)
        if (endpointMode != EndpointMode.AUTOMATIC) {
            EndpointAddress.normalize(customEndpoint)?.let { json.put("peer", it) }
        }
        return json.toString()
    }
}
