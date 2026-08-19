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
     * @param socksPort the port Aether's SOCKS5 listener is on, or null to dial
     *   nodes directly rather than through the tunnel.
     */
    fun render(settings: ChainSettings, socksPort: Int?): String = buildString {
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

        appendDns(socksPort)

        val through = if (socksPort != null) {
            append("proxies:\n")
            append(
                "  - {name: $TUNNEL_PROXY, type: socks5, server: 127.0.0.1, " +
                    "port: $socksPort, udp: true}\n",
            )
            "\n    dialer-proxy: $TUNNEL_PROXY"
        } else {
            ""
        }

        val names = appendProviders(settings, through)

        // A group with nothing in it is a config mihomo rejects, and the reason
        // it gives names YAML rather than the empty subscription behind it.
        require(names.isNotEmpty()) { "The chain has no node sources" }

        append("proxy-groups:\n")
        append("  - name: $EXIT_GROUP\n    type: select\n    use: [${names.joinToString(", ")}]\n")

        // Everything goes to the exit group. A rule that let anything take a
        // direct route would put that traffic on the local network in the clear,
        // and on this platform it would also be captured by our own interface.
        append("rules:\n  - MATCH,$EXIT_GROUP\n")
    }

    /**
     * Resolvers live inside the chain.
     *
     * A query that escapes names the destination even when the traffic itself
     * does not, and here it would also be answered by the interface asking the
     * question. The `#$TUNNEL_PROXY` fragment is mihomo's syntax for dialling a
     * resolver through a named proxy, which is what keeps that from happening.
     */
    private fun StringBuilder.appendDns(socksPort: Int?) {
        val via = if (socksPort != null) "#$TUNNEL_PROXY" else ""
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
        sources.forEachIndexed { index, source ->
            val key = "source$index"
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
