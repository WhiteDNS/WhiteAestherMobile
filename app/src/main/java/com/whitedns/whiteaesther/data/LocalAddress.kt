package com.whitedns.whiteaesther.data

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * This device's address on the network it is attached to.
 *
 * Shown beside the LAN sharing switch, because the listener binds to every
 * interface and "0.0.0.0" is not something anyone can type into another
 * machine. Without it the feature works and nobody can find it.
 */
object LocalAddress {
    /**
     * The address another machine on the same network would dial, or null.
     *
     * IPv4 only. Every client that takes a SOCKS5 host accepts one, the
     * addresses are short enough to copy off a screen by hand, and a phone's
     * IPv6 addresses are mostly not reachable from the local network anyway.
     */
    fun onLocalNetwork(): String? = candidates().firstOrNull()

    /**
     * Every private IPv4 address this device holds, tunnels excluded.
     *
     * The VPN interface has one too, and it is the one address that is never
     * the answer: it belongs to the tunnel this proxy feeds, not to the
     * network the client is on.
     */
    fun candidates(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !isTunnel(it.name) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /**
     * Interfaces that are a tunnel rather than a network.
     *
     * Matched by name because Android exposes no flag for it: `tun` is the
     * VpnService interface and `rmnet` names the modem's own, neither of which
     * another machine can reach.
     */
    private fun isTunnel(name: String): Boolean =
        name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("ppp")
}
