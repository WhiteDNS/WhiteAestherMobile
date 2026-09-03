package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.ChainSettings

/**
 * Renders the mihomo configuration for the exit chain.
 *
 * A port of the desktop's `chain.rs::render`, re-reviewed against a tun rather
 * than copied. The desktop hands applications a mixed port and anything that
 * misses the chain merely goes out in the clear -- bad, but it terminates. Here
 * the interface carries `0.0.0.0/0` and `::/0`, so a connection that escapes the
 * chain is pulled back into the tunnel that is creating it. Every difference
 * below comes from that.
 */
object ChainConfig {
    /** The SOCKS5 proxy standing in for the Aether tunnel. */
    const val TUNNEL_PROXY = "aether"

    /** The group everything is routed through. */
    const val EXIT_GROUP = "exit"

    const val MANUAL_PROVIDER = "manual"

    /**
     * The interface addresses mihomo is told it owns.
     *
     * Carrier-grade NAT space, not RFC1918: a phone's own network is very likely
     * to be using 192.168/16 or 10/8, and an overlap there would capture the
     * local subnet.
     */
    const val TUN_IPV4 = "198.18.0.1/30"
    const val TUN_IPV6 = "fdfe:dcba:9876::1/126"

    /** Where the system resolver points, and what mihomo hijacks. */
    const val TUN_DNS = "198.18.0.2"

    /**
     * gVisor rather than the system stack.
     *
     * The system stack needs the kernel to hand it packets for an interface it
     * did not create, which is not something an unprivileged Android app can
     * arrange. gVisor terminates TCP in userspace and is what FlClash ships on
     * Android for the same reason.
     */
    const val TUN_STACK = "gvisor"

    private const val HEALTH_CHECK_URL = "http://www.gstatic.com/generate_204"

    /**
     * @param socksPort the port the carrying tunnel's SOCKS5 listener is on, or
     *   null to dial nodes directly rather than through a tunnel.
     * @param proxyName what that tunnel is called in the config it appears in.
     *   Named rather than fixed because the engine is no longer the only thing
     *   that can be in front of the chain: a carrier ends in the same shape of
     *   listener, and a config calling Psiphon "aether" would mislead every log
     *   line and screen that reads a proxy name back.
     */
    fun render(
        settings: ChainSettings,
        socksPort: Int?,
        proxyName: String = TUNNEL_PROXY,
    ): String = buildString {
        // No external-controller. The desktop needs one because mihomo is a
        // separate process there; in-process we drive it through the action
        // protocol, so binding a control API would add an attack surface that
        // nothing uses -- any page the user opens could reach a loopback port.
        append("mode: rule\n")
        append("log-level: info\n")
        append("ipv6: true\n")
        // Off. Rules match on destination only, and resolving the owning process
        // for every connection costs a /proc walk per socket on a phone.
        append("find-process-mode: off\n")

        appendDns(socksPort, proxyName)

        val through = if (socksPort != null) {
            append("proxies:\n")
            append(
                "  - {name: $proxyName, type: socks5, server: 127.0.0.1, " +
                    "port: $socksPort, udp: true}\n",
            )
            "\n    dialer-proxy: $proxyName"
        } else {
            ""
        }

        val names = appendProviders(settings, through)

        // A group with nothing in it is a config mihomo rejects, and the reason
        // it gives names YAML rather than the empty subscription behind it.
        require(names.isNotEmpty()) { "The chain has no node sources" }

        append("proxy-groups:\n")
        append("  - name: $EXIT_GROUP\n    type: select\n    use: [${names.joinToString(", ")}]\n")

        appendRules(settings)
    }

    /** The SOCKS5 proxy standing in for a carrier that is not the engine. */
    const val CARRIER_PROXY = "carrier"

    /**
     * Everything through one upstream SOCKS5, with no exit chain in front.
     *
     * This is what a carrier that is not the engine needs from mihomo, and it is
     * less than [render] does: there are no nodes to choose from and no group to
     * choose with, only a proxy and a default route into it. mihomo is here as
     * the thing that terminates the interface -- Psiphon hands us a listener on
     * loopback, and a listener cannot carry a tun.
     *
     * The user's block and direct rules still apply. They are the same rules the
     * engine path honours, and a different carrier is not a reason to stop.
     *
     * \nparam socksPort the carrier's SOCKS5 listener, with its tunnel already
     *   established. A port that is merely listening is not enough: traffic
     *   routed into a proxy whose tunnel has not come up is dropped rather than
     *   refused, which the phone experiences as everything hanging.
     * \nparam udp whether the carrier forwards UDP. Psiphon does. Tor does not,
     *   and a proxy declared `udp: true` that cannot carry it swallows every
     *   datagram -- DNS and QUIC failing silently while TCP works, which is the
     *   hardest shape of broken to recognise.
     */
    fun renderCarrier(settings: ChainSettings, socksPort: Int, udp: Boolean = true): String =
        buildString {
            append("mode: rule\n")
            append("log-level: info\n")
            append("ipv6: true\n")
            append("find-process-mode: off\n")

            appendDns(socksPort, CARRIER_PROXY)

            append("proxies:\n")
            append(
                "  - {name: $CARRIER_PROXY, type: socks5, server: 127.0.0.1, " +
                    "port: $socksPort, udp: $udp}\n",
            )

            // A group of one, rather than routing to the proxy by name. mihomo
            // reports health and traffic per group, so the screens and the logs
            // can describe a carrier exactly as they describe an exit node
            // instead of having a second shape to understand.
            append("proxy-groups:\n")
            append("  - name: $EXIT_GROUP\n    type: select\n    proxies: [$CARRIER_PROXY]\n")

            appendCarrierRules(settings, udp)
        }

    /**
     * The rules for a carrier: the ordinary ones, plus what it cannot do.
     *
     * A carrier with no UDP gets an explicit refusal for it. Without one the
     * datagrams match `MATCH` and are handed to a proxy that drops them, and
     * dropping is worse than refusing: a refused datagram makes a resolver fall
     * back to TCP and a browser fall back off QUIC, while a dropped one makes
     * both wait out a timeout on every request.
     */
    private fun StringBuilder.appendCarrierRules(settings: ChainSettings, udp: Boolean) {
        append("rules:\n")
        settings.blockRules().forEach { pattern ->
            mihomoRules(pattern, "REJECT").forEach { append("  - $it\n") }
        }
        settings.directRules().forEach { pattern ->
            mihomoRules(pattern, "DIRECT").forEach { append("  - $it\n") }
        }
        if (!udp) {
            // After the user's rules and before MATCH: a destination they chose
            // to block stays blocked, and one they chose to send direct still
            // goes direct over UDP, because direct does not go through this
            // carrier at all.
            append("  - NETWORK,udp,REJECT\n")
        }
        append("  - MATCH,$EXIT_GROUP\n")
    }

    /**
     * The user's routing rules, translated for mihomo.
     *
     * They have to live here and not only in the engine. With a chain running,
     * mihomo owns the interface and dials each node *through* the engine's
     * SOCKS5 listener, so the engine only ever sees the node's address and
     * never the destination the user asked for. A rule written against a
     * domain is invisible to it, which is why the rules added for the plain
     * tunnel do nothing at all once a chain is switched on.
     */
    private fun StringBuilder.appendRules(settings: ChainSettings) {
        append("rules:\n")
        // Refusals first, so a blocked destination cannot also match a direct
        // rule further down and get dialled anyway.
        settings.blockRules().forEach { pattern ->
            mihomoRules(pattern, "REJECT").forEach { append("  - $it\n") }
        }
        settings.directRules().forEach { pattern ->
            mihomoRules(pattern, "DIRECT").forEach { append("  - $it\n") }
        }
        // Everything not named goes to the exit group. Last and unconditional:
        // without it the file is a list of exceptions with no default, and
        // mihomo treats an unmatched connection as direct.
        append("  - MATCH,$EXIT_GROUP\n")
    }

    /**
     * One rule in our grammar as mihomo rules, or empty if it is neither.
     *
     * A list rather than one string because `private` is several ranges.
     *
     * Address rules carry `no-resolve` so a domain is never looked up merely
     * to test it against a block -- that lookup would leave the tunnel and
     * name the destination to whoever is watching, which is the thing the rule
     * was written to prevent.
     */
    internal fun mihomoRules(pattern: String, action: String): List<String> {
        val entry = pattern.trim()
        if (entry.isEmpty() || entry.startsWith("#")) return emptyList()

        val split = entry.split(":", limit = 2)
        val hasKind = split.size == 2 &&
            !split[0].contains('.') &&
            !split[0].contains('/')
        val kind = if (hasKind) split[0].trim().lowercase() else ""
        val value = if (hasKind) split[1].trim() else entry
        if (value.isEmpty()) return emptyList()

        return when (kind) {
            "domain", "suffix" -> listOf("DOMAIN-SUFFIX,$value,$action")
            "full", "exact" -> listOf("DOMAIN,$value,$action")
            "keyword" -> listOf("DOMAIN-KEYWORD,$value,$action")
            "regexp", "regex" -> listOf("DOMAIN-REGEX,$value,$action")
            "ip", "cidr" -> listOf("IP-CIDR,$value,$action,no-resolve")
            "port" -> listOf("DST-PORT,$value,$action")
            "geoip", "geosite" ->
                if (value.equals("private", ignoreCase = true)) privateRules(action)
                else emptyList()
            "" -> when {
                value.equals("private", ignoreCase = true) -> privateRules(action)
                value.contains('/') -> listOf("IP-CIDR,$value,$action,no-resolve")
                else -> listOf("DOMAIN-SUFFIX,$value,$action")
            }
            else -> emptyList()
        }
    }

    /**
     * `private`, spelled out.
     *
     * Not GEOIP,PRIVATE: that wants a database this build does not ship, and a
     * rule mihomo cannot resolve is one it drops without saying so.
     */
    private fun privateRules(action: String): List<String> = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "127.0.0.0/8",
    ).map { "IP-CIDR,$it,$action,no-resolve" }

    /**
     * Resolvers live inside the chain.
     *
     * A query that escapes names the destination even when the traffic itself
     * does not, and here it would also be answered by the interface asking the
     * question. The `#$TUNNEL_PROXY` fragment is mihomo's syntax for dialling a
     * resolver through a named proxy, which is what keeps that from happening.
     */
    private fun StringBuilder.appendDns(socksPort: Int?, proxyName: String = TUNNEL_PROXY) {
        val via = if (socksPort != null) "#$proxyName" else ""
        append("dns:\n")
        append("  enable: true\n")
        append("  ipv6: true\n")
        append("  listen: 0.0.0.0:1053\n")
        // fake-ip, so a name is answered instantly from a reserved range and the
        // real lookup happens at the far end where it cannot leak.
        append("  enhanced-mode: fake-ip\n")
        append("  fake-ip-range: 198.19.0.1/16\n")
        append("  nameserver:\n")
        append("    - https://1.1.1.1/dns-query$via\n")
        append("    - https://dns.google/dns-query$via\n")
    }

    private fun StringBuilder.appendProviders(
        settings: ChainSettings,
        through: String,
    ): List<String> {
        val names = mutableListOf<String>()
        val sources = settings.sources.filter { it.enabled && it.url.isNotBlank() }
        val manual = settings.manual.trim()
        if (sources.isEmpty() && manual.isEmpty()) return names

        append("proxy-providers:\n")
        sources.forEach { source ->
            val key = providerKey(source.url)
            names += key
            append("  $key:\n")
            append("    type: http\n")
            append("    url: ${quote(source.url)}\n")
            append("    interval: 3600\n")
            append("    path: ./providers/$key.yaml$through\n")
            append(healthCheck())
        }
        if (manual.isNotEmpty()) {
            names += MANUAL_PROVIDER
            append("  $MANUAL_PROVIDER:\n")
            append("    type: file\n")
            append("    path: ./providers/manual.txt$through\n")
            append(healthCheck())
        }
        return names
    }

    /**
     * A provider's name, derived from its URL rather than its position.
     *
     * Position was wrong in a way that looked like a caching bug. Delete the
     * first subscription, add a different one, and it takes the same name and
     * the same cache file -- so mihomo finds a provider it already has, less
     * than its refresh interval old, and serves the previous subscription's
     * nodes for the new one. Keying on the URL makes a different subscription a
     * different provider, which is what it is.
     */
    fun providerKey(url: String): String {
        var hash = 0x811c9dc5u
        url.trim().forEach { character ->
            hash = (hash xor character.code.toUInt()) * 0x01000193u
        }
        return "source${hash.toString(16).padStart(8, '0')}"
    }

    /**
     * `lazy: true` so a node nobody selected is not dialled every five minutes.
     * On a phone that is the difference between a health check and a battery
     * complaint.
     */
    private fun healthCheck(): String =
        "    health-check: {enable: true, url: \"$HEALTH_CHECK_URL\", interval: 300, lazy: true}\n"

    /**
     * A YAML double-quoted scalar.
     *
     * Matters because a subscription URL may carry `#`, which unquoted starts a
     * comment and silently truncates the URL to whatever came before it -- the
     * provider then fetches a different address than the user pasted, or none.
     */
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"', '\\' -> append('\\').append(character)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\x%02x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
