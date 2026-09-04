package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.TorBridge

/**
 * The `torrc` this app writes for tor to read.
 *
 * Short on purpose. tor's defaults are the product of two decades of people
 * attacking it, and every line added here is a way this app can be told apart
 * from every other tor client -- which is the opposite of what tor is for.
 * What is set is what an app embedding tor has to set, and nothing else.
 */
object TorConfig {
    /**
     * The bridges tor falls back on when the user has not been given any.
     *
     * These are the lines Tor Browser ships, unchanged. They are public by
     * design -- a bridge nobody can find is a bridge nobody can use -- and by
     * the same token they are the first ones a censor blocks. A bridge from
     * bridges.torproject.org beats every one of them, which is why the screen
     * says so, but a default that sometimes works beats a field the user has to
     * fill in before anything happens at all.
     *
     * Not fetched at runtime. Asking a server which bridges to use is a request
     * that names this device to somebody, from an app whose whole purpose is
     * not to make those.
     */
    private val DEFAULT_OBFS4 = listOf(
        "obfs4 192.95.36.142:443 CDF2E852BF539B82BD10E27E9115A31734E378C2 " +
            "cert=qUVQ0srL1JI/vO6V6m/24anYXiJD3QP2HgzUKQtQ7GRqqUvs7P+tG43RtAqdhLOALP7DJQ " +
            "iat-mode=1",
        "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D " +
            "cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg " +
            "iat-mode=0",
        "obfs4 85.31.186.98:443 011F2599C0E9B27EE74B353155E244813763C3E5 " +
            "cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg " +
            "iat-mode=0",
    )

    /** One line, and it is the same one every snowflake client uses. */
    private const val DEFAULT_SNOWFLAKE =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=www.cdn77.com,www.phpmyadmin.net " +
            "ice=stun:stun.l.google.com:19302,stun:stun.antisip.com:3478 " +
            "utls-imitate=hellorandomizedalpn"

    /** The transport a bridge mode needs, by the name the proxy answers to. */
    fun transportName(bridge: TorBridge): String? = when (bridge) {
        TorBridge.NONE -> null
        TorBridge.OBFS4 -> "obfs4"
        TorBridge.SNOWFLAKE -> "snowflake"
    }

    /**
     * @param transport where the already-running transport is listening, as
     *   `host:port`, or null when there is none.
     *
     * `socks5` rather than `exec`, and not by preference. tor normally launches
     * a managed proxy itself, but this libtor aborts inside
     * `pt_parse_transport_line` when told to -- before it has logged a word,
     * which is what a build without the fork it needs looks like. The identical
     * torrc with `socks5` starts cleanly, so the proxy is started by
     * [com.whitedns.whiteaesther.service.PluggableTransport] and tor is handed
     * the port it ended up on.
     */
    fun render(
        bridge: TorBridge,
        exitCountry: String?,
        transport: String?,
    ): String = buildString {
        // Nothing is served, dialled or published by this client.
        appendLine("SocksPolicy accept 127.0.0.1/8")
        appendLine("SocksPolicy reject *")
        // A client, not a relay and not a directory mirror. Explicit because the
        // defaults are right today and this says they must stay right.
        appendLine("ClientOnly 1")
        appendLine("AvoidDiskWrites 1")
        // The two things this app is not: it never resolves for other apps and
        // never accepts a connection from off the device.
        appendLine("DNSPort 0")
        appendLine("TransPort 0")

        val name = transportName(bridge)
        if (name != null && transport != null) {
            appendLine("ClientTransportPlugin $name socks5 $transport")
            appendLine("UseBridges 1")
            bridgeLines(bridge).forEach { appendLine("Bridge $it") }
        }

        val country = exitCountry?.trim()?.lowercase()?.takeIf { it.length == 2 }
        if (country != null) {
            appendLine("ExitNodes {$country}")
            // A preference, never a requirement. `StrictNodes 1` on a country
            // with a handful of exit relays -- or none -- is a tunnel that never
            // builds, which a user reads as the app being broken rather than as
            // the country being empty.
            appendLine("StrictNodes 0")
        }
    }

    internal fun bridgeLines(bridge: TorBridge): List<String> = when (bridge) {
        TorBridge.NONE -> emptyList()
        TorBridge.OBFS4 -> DEFAULT_OBFS4
        TorBridge.SNOWFLAKE -> listOf(DEFAULT_SNOWFLAKE)
    }

    /**
     * How long to wait for a circuit before calling it a failure.
     *
     * Longer than Psiphon's, because tor is slower to bootstrap by design: it
     * fetches a consensus and builds a circuit through three relays, and behind
     * a bridge it does all of that through one more hop that is itself being
     * discovered.
     */
    fun bootstrapTimeoutMs(bridge: TorBridge): Long = when (bridge) {
        TorBridge.NONE -> 240_000L
        else -> 300_000L
    }
}
