package com.whitedns.whiteaesther.data

import androidx.annotation.StringRes
import com.whitedns.whiteaesther.R

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
enum class TunnelProtocol(val wireName: String, @StringRes val label: Int) {
    /**
     * Work out what this network allows, rather than making the user guess.
     *
     * Not a transport the engine understands -- the service resolves it to a
     * real one before the config is built, and remembers what succeeded so the
     * next connect starts there instead of searching again.
     */
    AUTO("auto", R.string.protocol_automatic),
    H3("h3", R.string.protocol_masque_h3),
    H2("h2", R.string.protocol_masque_h2),
    WIREGUARD("wg", R.string.protocol_wireguard),
    WARP_IN_WARP("wiw", R.string.protocol_warp_in_warp),
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

/**
 * What carries the tunnel.
 *
 * Deliberately not another [TunnelProtocol]. Every member of that enum answers
 * questions about Cloudflare endpoints -- which family it dials, what a scan
 * probes it with, whether a retry may swap it for its sibling -- and Psiphon and
 * Tor answer none of them, because neither has anything to do with an endpoint.
 * Folding them in would mean inventing a family for a carrier that has none and
 * then teaching every `when` to ignore it.
 *
 * They meet in one place instead: each carrier ends in a SOCKS5 listener on
 * loopback, and mihomo routes the interface into whichever one is running.
 */
enum class Carrier(val wireName: String, @StringRes val label: Int) {
    /** The MASQUE engine this app is built around. */
    AETHER("aether", R.string.carrier_aether),

    /**
     * Psiphon, in its own process.
     *
     * Go, and the exit chain already spends this process's one Go runtime. Two
     * `-buildmode=c-shared` libraries export the same runtime symbols into one
     * linker namespace and the second call to bind to the wrong copy takes down
     * the process, so Psiphon is loaded somewhere else entirely.
     */
    PSIPHON("psiphon", R.string.carrier_psiphon),

    /**
     * Tor, in its own process.
     *
     * Slower than either of the others and not a general-purpose tunnel: it is
     * three relays deep by design, and it carries no UDP at all. It is here for
     * the networks where nothing else gets out, and because what it hides is
     * different from what the others hide -- an exit relay does not know who
     * asked.
     */
    TOR("tor", R.string.carrier_tor),
    ;

    /** True when the Aether engine is in the path at all. */
    val usesEngine: Boolean get() = this == AETHER

    /**
     * Whether this carrier forwards datagrams.
     *
     * Tor does not, and saying so is not a detail. A proxy declared as carrying
     * UDP that cannot swallows every datagram instead of refusing it, and a
     * phone experiences that as DNS and QUIC hanging while TCP works -- the
     * hardest shape of broken to recognise. Declared false, mihomo refuses them
     * and everything falls back to TCP within a round trip.
     */
    val carriesUdp: Boolean get() = this != TOR

    /**
     * True when the carrier needs mihomo to reach the interface.
     *
     * Everything that is not the engine arrives as a SOCKS5 listener, and a
     * listener cannot carry a tun on its own. mihomo is what terminates the
     * packets and dials them out through it -- so on a build without the chain
     * library these carriers cannot run at all, and the screen says so rather
     * than starting something that would route nothing.
     */
    val needsChain: Boolean get() = this != AETHER
}

/**
 * How Tor reaches its first hop.
 *
 * Ordered by how much they cost and how much they survive, which is the order a
 * user should try them in. Direct is fastest and blocked wherever Tor is; obfs4
 * hides the shape of the traffic; snowflake goes through whichever volunteer
 * browser answers; meek rides a CDN, which is slow and very hard to block
 * without blocking the CDN.
 */
enum class TorBridge(val wireName: String, @StringRes val label: Int) {
    NONE("none", R.string.tor_bridge_none),
    OBFS4("obfs4", R.string.tor_bridge_obfs4),
    SNOWFLAKE("snowflake", R.string.tor_bridge_snowflake),

    /**
     * Bridges the user was given, or the app fetched for them.
     *
     * The only mode with a real chance where Tor is properly blocked. The two
     * above use the bridges Tor publishes for everyone, and a bridge everybody
     * has is a bridge everybody's censor has -- they are the first addresses to
     * go and they are already gone in the places this mode exists for.
     */
    CUSTOM("custom", R.string.tor_bridge_custom),
    // meek is deliberately absent. lyrebird starts it and tor accepts it, and
    // then it never finishes bootstrapping -- seven minutes on the public
    // bridge, repeatedly, while obfs4 and snowflake took under a minute from
    // the same network. Offering it would be offering a mode that hangs and
    // then fails. The plumbing is generic, so it is a two-line change to bring
    // back when there is a bridge worth pointing at.
    ;

    /** True when this needs a transport executable that a build may not ship. */
    val needsTransports: Boolean get() = this != NONE
}

/** Protocols sharing one set of endpoints, so an address found on one fits the other. */
enum class EndpointFamily {
    MASQUE,
    WARP,
}

enum class ScanStrategy(val wireName: String, @StringRes val label: Int) {
    TURBO("turbo", R.string.scan_turbo),
    BALANCED("balanced", R.string.scan_balanced),
    THOROUGH("thorough", R.string.scan_thorough),
    STEALTH("stealth", R.string.scan_stealth),
    IRONCLAD("ironclad", R.string.scan_ironclad),
}

enum class EndpointMode(@StringRes val label: Int) {
    AUTOMATIC(R.string.protocol_automatic),
    CUSTOM_FIRST(R.string.endpoint_custom_first),
    CUSTOM_ONLY(R.string.endpoint_custom_only),
}

/** How much the LAN sharing notice is asking of the user. */
enum class LanNoticeLevel {
    /** A consequence of a choice that is allowed to stand. */
    CAUTION,

    /** A setup that will not start until it changes. */
    PROBLEM,
}

data class LanNotice(val level: LanNoticeLevel, @StringRes val text: Int)

enum class ThemeMode(@StringRes val label: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
}

/**
 * Which language the app speaks.
 *
 * Separate from the phone's language on purpose. The two are routinely
 * different for the people this app is built for: a phone kept in English
 * because that is what the shops sell and the guides assume, used by someone
 * who would rather read their own language -- and the reverse, a phone in
 * Persian whose owner wants the English words because those are what every
 * article about tunnels and protocols uses.
 *
 * [SYSTEM] follows the phone, which is right until the user says otherwise.
 */
enum class AppLanguage(val tag: String) {
    /** Whatever the phone is set to, falling back to English. */
    SYSTEM(""),
    ENGLISH("en"),
    PERSIAN("fa"),
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

private const val NEWLINE = '\n'

data class AppSettings(
    val mode: EngineMode = EngineMode.TUN,
    val proxyPort: Int = 1819,
    /**
     * Which engine carries the tunnel.
     *
     * Aether unless the user says otherwise. The others are here for the
     * networks Aether cannot get out of, not as an equal choice: they are
     * slower, and one of them is somebody else's network.
     */
    val carrier: Carrier = Carrier.AETHER,
    /**
     * How Tor reaches its first hop, when Tor is the carrier.
     *
     * Direct by default, which is both the fastest and the one that fails on
     * exactly the networks this carrier is wanted for. Defaulting to a bridge
     * instead would make every user pay for a hop most of them do not need, and
     * would put the app's traffic on three well-known public bridges that are
     * blocked in the places a bridge is required.
     */
    val torBridge: TorBridge = TorBridge.NONE,
    /**
     * Bridge lines for [TorBridge.CUSTOM], one per line.
     *
     * Kept as text rather than parsed, because it is text the user pasted and
     * they should get it back exactly as they left it. Everything that needs
     * structure asks [com.whitedns.whiteaesther.core.TorBridges] for it.
     */
    val torBridges: String = "",
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
     * Offer the local SOCKS5 proxy to the rest of the network, not just this
     * phone.
     *
     * Proxy mode only. Whole-device mode has no listener to share: the tunnel
     * is an interface, and another machine cannot route into it.
     */
    val lanSharing: Boolean = false,
    /**
     * Demanded of clients when both are set.
     *
     * Optional by choice. Without them anyone who can reach the port can use
     * the tunnel, and on a network the user does not control -- a cafe, a
     * hotel, a dormitory -- that is everyone on it, leaving with this device's
     * identity. [lanSharingWarning] is what the screen says about it.
     */
    val lanUsername: String = "",
    val lanPassword: String = "",
    /**
     * Destinations to refuse outright, one per line.
     *
     * `ads.example.com`, `cidr:10.0.0.0/8`, `keyword:tracker`, `port:25`,
     * `regexp:^ad[0-9]`, or `private` for the local network. A line starting
     * with `#` is a note.
     */
    val routeBlock: String = "",
    /**
     * Destinations to reach without the tunnel, same grammar as [routeBlock].
     *
     * Useful for a bank or a domestic service that refuses foreign addresses.
     * Traffic named here leaves with this device's real address, which is the
     * point and also the risk.
     */
    val routeDirect: String = "",
    /**
     * Hold a blocking interface up when the tunnel drops unexpectedly.
     *
     * Without it, a tunnel that dies takes its interface with it and the phone
     * quietly resumes over the ordinary route -- which is the one moment the
     * user most needs it not to. The blocking interface carries the default
     * routes and forwards nothing, so traffic stops rather than escapes.
     */
    val killSwitch: Boolean = false,
    /**
     * Keep blocking after a deliberate disconnect too, until it is lifted.
     *
     * Separate from [killSwitch] because it is a different promise: one is
     * about a failure, this is about the gap between sessions. It has to be
     * lifted by hand, or a user who forgets is left with a phone that has no
     * internet and no obvious reason why -- so the app says so plainly while it
     * is on.
     */
    val strictKillSwitch: Boolean = false,
    /**
     * Seconds between WireGuard keepalives.
     *
     * The engine's own default is 5, which is far below the 25 WireGuard
     * recommends and wakes a phone's radio twelve times a minute -- on mobile
     * data each wake costs more in radio tail than the packet it carries. 25 is
     * the standard because most NAT mappings survive 30 seconds; a network with
     * a shorter one needs a lower value, which is why this is a setting and not
     * a constant.
     */
    val wgKeepalive: Int = 25,
    /**
     * A proxy already running on this device to dial out through.
     *
     * `socks5://host:port`, with optional credentials, or an HTTP proxy. Empty
     * dials directly.
     */
    val upstreamProxy: String = "",
    /** Resolvers inside the tunnel, comma separated. Empty keeps the engine's. */
    val dnsServers: String = "",
    /**
     * Read the hostname from a flow's first bytes.
     *
     * Matters more here than on a desktop: this app is always a tun front end,
     * so a flow reaches the engine as a bare address and a rule written against
     * a domain would otherwise never match anything.
     */
    val routeSniff: Boolean = true,
    /** Register a fresh identity when Cloudflare refuses the saved one. */
    val autoReprovision: Boolean = true,
    /** Engine log verbosity. Empty leaves the engine's own default. */
    val engineLogLevel: String = "",
    /** TLS key groups, which change the shape of the handshake. */
    val tlsGroups: String = "",
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
    val language: AppLanguage = AppLanguage.SYSTEM,
    val showAdvanced: Boolean = false,
) {
    /**
     * True when a password will actually be demanded.
     *
     * Both halves or neither. One alone is not a weaker password, it is no
     * password -- and the engine rejects the pair rather than quietly serving
     * the network unguarded.
     */
    fun lanCredentialsUsable(): Boolean =
        lanSharing && lanUsername.isNotBlank() && lanPassword.isNotBlank()

    /**
     * What the screen says about the current sharing setup, or null.
     *
     * Two different things, and telling them apart is the point. Sharing
     * without a password is a choice with a consequence -- [LanNoticeLevel.
     * CAUTION]. A half-filled credential pair is a setup the engine refuses to
     * start -- [LanNoticeLevel.PROBLEM]. Rendering both in the failure colour
     * made the optional one read as a field the user had forgotten to fill.
     */
    fun lanSharingNotice(): LanNotice? = when {
        !lanSharing -> null
        mode != EngineMode.PROXY -> LanNotice(
            LanNoticeLevel.PROBLEM,
            R.string.lan_no_proxy_to_share,
        )
        lanUsername.isBlank() && lanPassword.isBlank() -> LanNotice(
            LanNoticeLevel.CAUTION,
            R.string.lan_no_password,
        )
        !lanCredentialsUsable() -> LanNotice(
            LanNoticeLevel.PROBLEM,
            R.string.lan_half_filled,
        )
        else -> null
    }

    /**
     * Where clients should point, for the screen and the notification to agree.
     */
    fun proxyBindLabel(): String =
        if (lanSharing && mode == EngineMode.PROXY) {
            "0.0.0.0:$proxyPort"
        } else {
            "127.0.0.1:$proxyPort"
        }

    /**
     * The rules in one line, counting only what the engine would accept.
     *
     * Blank lines and notes are dropped, so a list that reads as five rules
     * does not summarise as eight.
     */
    /**
     * The two counts the summary is built from.
     *
     * Counting and wording are separated because only one of them is the same
     * in every language. A test that asserts on the sentence is really testing
     * the English, and would have to be rewritten to say nothing useful the
     * moment a second language existed.
     */
    fun routingCounts(): RuleCounts = RuleCounts(ruleCount(routeBlock), ruleCount(routeDirect))

    private fun ruleCount(raw: String): Int =
        raw.split(NEWLINE, ',', ';')
            .map(String::trim)
            .count { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * What is actually carried, in one line.
     *
     * Coverage used to be read from [mode] alone, which said "Whole device"
     * while a per-app rule quietly restricted the tunnel to one app -- a user
     * reading it had no way to tell why their traffic was not going through.
     * The rule is part of the answer, so it is part of the label.
     */
    fun coverage(): Coverage = when {
        mode != EngineMode.TUN -> Coverage.ProxyOnly
        splitTunnel.mode == SplitTunnelMode.ALL -> Coverage.WholeDevice
        splitTunnel.packages.isEmpty() && splitTunnel.mode == SplitTunnelMode.ONLY ->
            Coverage.NothingChosen
        splitTunnel.packages.isEmpty() -> Coverage.WholeDevice
        splitTunnel.mode == SplitTunnelMode.ONLY -> Coverage.OnlySome(splitTunnel.packages.size)
        else -> Coverage.AllExcept(splitTunnel.packages.size)
    }

    /** True when a per-app rule means this is not the whole device after all. */
    fun coverageIsRestricted(): Boolean =
        mode == EngineMode.TUN && !splitTunnel.isEffectivelyEverything("")

    @StringRes
    fun endpointValidationError(): Int? = when {
        endpointMode == EndpointMode.AUTOMATIC -> null
        customEndpoint.isBlank() -> R.string.endpoint_error_empty
        EndpointAddress.normalize(customEndpoint) == null -> R.string.endpoint_error_malformed
        else -> null
    }

    /**
     * These settings with no endpoint pinned to them.
     *
     * Three fields, and clearing two of them is worse than clearing none: an
     * address left behind with the mode back on automatic is invisible on the
     * screen but reappears the moment the mode changes, and a protocol left
     * behind without an address is what [endpointProtocolMismatch] reads to
     * accuse a pin that no longer exists. They move together or not at all.
     */
    fun withoutPinnedEndpoint(): AppSettings = copy(
        endpointMode = EndpointMode.AUTOMATIC,
        customEndpoint = "",
        customEndpointProtocol = null,
    )

    /**
     * Set when the pinned address belongs to a protocol other than the one now
     * selected, which is a mismatch the user can only fix by knowing about it.
     */
    fun endpointProtocolMismatch(): TunnelProtocol? = customEndpointProtocol?.takeIf {
        endpointMode != EndpointMode.AUTOMATIC && it.endpointFamily != transport.endpointFamily
    }

    /**
     * The chain settings, carrying the routing rules with them.
     *
     * The rules live on [AppSettings] because one screen edits them, but the
     * chain needs its own copy: with mihomo in front, the engine never sees the
     * destination and its copy cannot match. Joined here rather than at each
     * call site, so a caller cannot forget and leave a chain with no rules.
     */
    fun chainForService(): ChainSettings =
        chain.copy(routeBlock = routeBlock, routeDirect = routeDirect)

    fun toNativeJson(context: Context): String {
        val json = JSONObject()
            .put("mode", mode.wireName)
            .put("configPath", File(context.filesDir, "aether.toml").absolutePath)
            .put("listenPort", proxyPort)
            .put("lanSharing", lanSharing && mode == EngineMode.PROXY)
            .put("lanUsername", if (lanCredentialsUsable()) lanUsername else "")
            .put("lanPassword", if (lanCredentialsUsable()) lanPassword else "")
            .put("wgKeepalive", wgKeepalive)
            .put("upstreamProxy", upstreamProxy.trim())
            .put("dnsServers", dnsServers.trim())
            .put("routeSniff", routeSniff)
            .put("routeBlock", routeBlock.trim())
            .put("routeDirect", routeDirect.trim())
            .put("autoReprovision", autoReprovision)
            .put("logLevel", engineLogLevel)
            .put("tlsGroups", tlsGroups.trim())
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

/** How many rules each list holds, once blanks and notes are dropped. */
data class RuleCounts(val blocked: Int, val direct: Int)

/**
 * What the tunnel actually carries.
 *
 * Coverage used to be read from the mode alone, which said "Whole device"
 * while a per-app rule quietly restricted the tunnel to one app. The rule is
 * part of the answer, so it is part of this.
 */
sealed interface Coverage {
    data object ProxyOnly : Coverage
    data object WholeDevice : Coverage
    data object NothingChosen : Coverage
    data class OnlySome(val count: Int) : Coverage
    data class AllExcept(val count: Int) : Coverage
}
