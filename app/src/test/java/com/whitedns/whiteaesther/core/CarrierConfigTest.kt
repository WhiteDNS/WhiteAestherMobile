package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.ChainSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration mihomo is given when something other than the engine is
 * carrying the tunnel.
 *
 * Every check here is against a failure that would not announce itself. A
 * carrier config with a missing default route still starts, still reports
 * connected, and routes the phone's traffic straight onto the local network --
 * the exact outcome the whole app exists to prevent.
 */
class CarrierConfigTest {
    private val off = ChainSettings(enabled = false)

    @Test
    fun everythingDefaultsIntoTheCarrier() {
        val config = ChainConfig.renderCarrier(off, socksPort = 41234)

        assertTrue(
            config.contains(
                "- {name: ${ChainConfig.CARRIER_PROXY}, type: socks5, " +
                    "server: 127.0.0.1, port: 41234, udp: true}",
            ),
        )
        // Last and unconditional. mihomo treats an unmatched connection as
        // direct, so a config without this is one that carries nothing while
        // looking like it carries everything.
        assertTrue(config.trimEnd().endsWith("MATCH,${ChainConfig.EXIT_GROUP}"))
    }

    @Test
    fun resolversGoThroughTheCarrierAndNotPastIt() {
        val config = ChainConfig.renderCarrier(off, socksPort = 41234)

        // A name looked up outside the carrier names the destination to the
        // network the carrier exists to hide it from -- and here it would be
        // answered by the interface asking the question.
        assertTrue(config.contains("https://1.1.1.1/dns-query#${ChainConfig.CARRIER_PROXY}"))
        assertFalse(config.contains("dns-query#${ChainConfig.TUNNEL_PROXY}"))
        assertTrue(config.contains("enhanced-mode: fake-ip"))
    }

    @Test
    fun aCarrierWithoutUdpRefusesItRatherThanSwallowingIt() {
        val config = ChainConfig.renderCarrier(off, socksPort = 41234, udp = false)

        assertTrue(config.contains("udp: false"))
        // Refused, not dropped. A rejected datagram makes a resolver fall back
        // to TCP within a round trip; one handed to a proxy that cannot carry
        // it is silence, and every request waits out a timeout instead.
        assertTrue(config.contains("NETWORK,udp,REJECT"))
        // Before the default route, or the default route claims it first.
        val reject = config.indexOf("NETWORK,udp,REJECT")
        val match = config.indexOf("MATCH,${ChainConfig.EXIT_GROUP}")
        assertTrue(reject in 1 until match)
    }

    @Test
    fun aCarrierWithUdpSaysNothingAboutIt() {
        val config = ChainConfig.renderCarrier(off, socksPort = 41234, udp = true)

        assertTrue(config.contains("udp: true"))
        assertFalse(config.contains("NETWORK,udp,REJECT"))
    }

    @Test
    fun theUsersOwnRulesSurviveAChangeOfCarrier() {
        val settings = ChainSettings(
            enabled = false,
            routeBlock = "ads.example",
            routeDirect = "bank.example",
        )
        val config = ChainConfig.renderCarrier(settings, socksPort = 41234)

        assertTrue(config.contains("DOMAIN-SUFFIX,ads.example,REJECT"))
        assertTrue(config.contains("DOMAIN-SUFFIX,bank.example,DIRECT"))
        // Refusals first: a blocked destination that also matches a direct rule
        // must stay blocked rather than being dialled anyway.
        assertTrue(config.indexOf("REJECT") < config.indexOf("DIRECT"))
    }

    @Test
    fun anExitChainOverACarrierDialsItsNodesThroughTheCarrier() {
        val settings = ChainSettings(
            enabled = true,
            sources = listOf(ChainSource("Test", "https://example.invalid/sub", true)),
        )
        val config = ChainConfig.render(
            settings,
            socksPort = 41234,
            proxyName = ChainConfig.CARRIER_PROXY,
        )

        // The whole point of the name being a parameter: a user who has set up
        // an exit chain keeps it when they change carrier, and every node is
        // dialled through the new one rather than straight off the network.
        assertTrue(config.contains("dialer-proxy: ${ChainConfig.CARRIER_PROXY}"))
        assertFalse(config.contains("dialer-proxy: ${ChainConfig.TUNNEL_PROXY}"))
    }

    @Test
    fun theEngineKeepsItsOwnNameWhenNothingAsksOtherwise() {
        val settings = ChainSettings(
            enabled = true,
            sources = listOf(ChainSource("Test", "https://example.invalid/sub", true)),
        )
        val config = ChainConfig.render(settings, socksPort = 1819)

        assertTrue(config.contains("dialer-proxy: ${ChainConfig.TUNNEL_PROXY}"))
    }

    @Test
    fun onlyTheEngineUsesTheEngine() {
        assertTrue(Carrier.AETHER.usesEngine)
        assertFalse(Carrier.AETHER.needsChain)
        Carrier.entries.filter { it != Carrier.AETHER }.forEach { carrier ->
            assertFalse(carrier.usesEngine)
            // Nothing but mihomo in this build can turn an interface into
            // connections, so a carrier that is not the engine cannot run
            // without it. A carrier added later that forgets this is one that
            // comes up and carries nothing.
            assertTrue(carrier.needsChain)
        }
    }

    @Test
    fun carrierWireNamesAreStableAndDistinct() {
        // The wire name crosses a process boundary on an intent and is written
        // to preferences that outlive an upgrade. Renaming one silently sends a
        // restarted session to the wrong carrier.
        assertEquals("aether", Carrier.AETHER.wireName)
        assertEquals("psiphon", Carrier.PSIPHON.wireName)
        assertEquals(
            Carrier.entries.size,
            Carrier.entries.map { it.wireName }.distinct().size,
        )
    }
}
