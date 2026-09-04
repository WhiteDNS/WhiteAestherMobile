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
     * Tor's own built-in list, taken from its service and checked reachable --
     * `native/tor/refresh-bridges.ps1` does both and is the way to redo it. The
     * first version of this list was written from memory and two of its three
     * addresses had been gone long enough to be unreachable from an uncensored
     * network, which is a failure worth not repeating: a list of addresses
     * rots, and there has to be a way to tell that it has.
     *
     * They are public by design -- a bridge nobody can find is a bridge nobody
     * can use -- and by the same token they are the first ones a censor blocks,
     * and in the places this mode matters most they are already blocked.
     * [TorBridge.CUSTOM] is the answer there. These are the fallback for
     * everywhere else.
     */
    private val DEFAULT_OBFS4 = listOf(
        "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
        "obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1",
        "obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 cert=2uplIpLQ0q9+0qMFrK5pkaYRDOe460LL9WHBvatgkuRr/SL31wBOEupaMMJ6koRE6Ld0ew iat-mode=0",
        "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0",
        "obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNinLmxQDpVa+7kTOa9/m+tGWT1SmSYpQ9uTBGa6Hw iat-mode=0",
        "obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=1",
    )

    /** One line, and it is the same one every snowflake client uses. */
    private const val DEFAULT_SNOWFLAKE =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn"

    /**
     * The transport a bridge mode needs, by the name the proxy answers to.
     *
     * For custom bridges it comes from the lines themselves, because that is
     * the only thing that knows: a paste can be obfs4, snowflake or webtunnel
     * and the screen cannot tell which until it is read.
     */
    fun transportName(bridge: TorBridge, custom: String = ""): String? = when (bridge) {
        TorBridge.NONE -> null
        TorBridge.OBFS4 -> "obfs4"
        TorBridge.SNOWFLAKE -> "snowflake"
        TorBridge.CUSTOM -> TorBridges.transportOf(TorBridges.parse(custom))
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
        customBridges: String = "",
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

        val name = transportName(bridge, customBridges)
        val bridges = bridgeLines(bridge, customBridges)
        // Both, or neither. A transport with no bridge to use it is tor
        // connecting directly while the screen says it is behind a bridge, and
        // bridges with no transport is tor refusing to start.
        if (name != null && transport != null && bridges.isNotEmpty()) {
            appendLine("ClientTransportPlugin $name socks5 $transport")
            appendLine("UseBridges 1")
            bridges.forEach { appendLine("Bridge $it") }
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

    internal fun bridgeLines(bridge: TorBridge, custom: String = ""): List<String> =
        when (bridge) {
            TorBridge.NONE -> emptyList()
            TorBridge.OBFS4 -> DEFAULT_OBFS4
            TorBridge.SNOWFLAKE -> listOf(DEFAULT_SNOWFLAKE)
            TorBridge.CUSTOM -> {
                val lines = TorBridges.parse(custom)
                // Only the ones matching the transport we are about to start.
                // A paste holding two kinds would otherwise start one proxy and
                // hand tor bridges for the other, which fails as an unreachable
                // bridge rather than as the mix-up it is.
                TorBridges.transportOf(lines)
                    ?.let { TorBridges.forTransport(lines, it) }
                    ?: emptyList()
            }
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

    /** Why a bridge mode cannot start, or null when it can. */
    fun refusal(bridge: TorBridge, customBridges: String): Refusal? = when {
        bridge != TorBridge.CUSTOM -> null
        TorBridges.parse(customBridges).isEmpty() -> Refusal.NO_BRIDGES
        transportName(bridge, customBridges) == null -> Refusal.NO_BRIDGES
        else -> null
    }

    enum class Refusal { NO_BRIDGES }
}
