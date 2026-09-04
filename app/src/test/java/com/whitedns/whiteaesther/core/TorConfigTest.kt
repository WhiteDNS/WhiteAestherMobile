package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.TorBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `torrc` this app writes.
 *
 * Every check is against something that fails quietly. tor reads this file once
 * at startup: a line naming a plugin binary that is not there stops it dead, a
 * missing `UseBridges` leaves it connecting directly while the screen says
 * otherwise, and `StrictNodes 1` turns a country preference into a tunnel that
 * never builds.
 */
class TorConfigTest {
    private val listening = "127.0.0.1:41234"

    @Test
    fun directNeedsNoBridgeAndSaysSoByOmission() {
        val config = TorConfig.render(TorBridge.NONE, null, listening)

        assertFalse(config.contains("UseBridges"))
        assertFalse(config.contains("ClientTransportPlugin"))
        assertFalse(config.contains("Bridge "))
    }

    @Test
    fun obfs4NamesLyrebirdAndBringsBridgesWithIt() {
        val config = TorConfig.render(TorBridge.OBFS4, null, listening)

        assertTrue(config.contains("UseBridges 1"))
        assertTrue(config.contains("ClientTransportPlugin obfs4 socks5 $listening"))
        // A transport declared with no bridge to use it is tor connecting
        // directly while the screen says it is behind a bridge.
        assertTrue(config.lines().count { it.startsWith("Bridge obfs4 ") } >= 1)
    }

    @Test
    fun snowflakeNamesItsOwnBinaryAndNotLyrebirds() {
        val config = TorConfig.render(TorBridge.SNOWFLAKE, null, listening)

        assertTrue(config.contains("ClientTransportPlugin snowflake socks5 $listening"))
        assertTrue(config.contains("Bridge snowflake "))
    }

    @Test
    fun everyBridgeModeNamesTheTransportItsProxyAnswersTo() {
        // The name in the plugin line is the one the proxy announced in its
        // CMETHOD, not the one on the screen: lyrebird calls meek meek_lite,
        // and asking it for "meek" gets a CMETHOD-ERROR and a bridge mode that
        // never works.
        assertEquals("obfs4", TorConfig.transportName(TorBridge.OBFS4))
        assertEquals("snowflake", TorConfig.transportName(TorBridge.SNOWFLAKE))
        assertEquals(null, TorConfig.transportName(TorBridge.NONE))
    }

    @Test
    fun withoutAnyTransportsNothingIsDeclaredAtAll() {
        val config = TorConfig.render(TorBridge.OBFS4, null, null)

        assertFalse(config.contains("ClientTransportPlugin"))
        assertFalse(config.contains("UseBridges"))
    }

    @Test
    fun anExitCountryIsAPreferenceAndNeverARequirement() {
        val config = TorConfig.render(TorBridge.NONE, "NL", null)

        assertTrue(config.contains("ExitNodes {nl}"))
        // The whole reason this is here: several countries have a handful of
        // exit relays and some have none, and strict enforcement on those is a
        // tunnel that never builds.
        assertTrue(config.contains("StrictNodes 0"))
        assertFalse(config.contains("StrictNodes 1"))
    }

    @Test
    fun nonsenseCountriesAreIgnoredRatherThanWritten() {
        listOf("", "  ", "nether", "n").forEach { value ->
            val config = TorConfig.render(TorBridge.NONE, value, null)
            assertFalse("accepted '$value'", config.contains("ExitNodes"))
        }
    }

    @Test
    fun thisClientNeverServesAnything() {
        val config = TorConfig.render(TorBridge.NONE, null, null)

        // A resolver or a transparent port open on a phone is a service other
        // apps on it can reach, and neither is used by anything here.
        assertTrue(config.contains("DNSPort 0"))
        assertTrue(config.contains("TransPort 0"))
        assertTrue(config.contains("ClientOnly 1"))
        assertTrue(config.contains("SocksPolicy reject *"))
    }

    @Test
    fun everyBuiltInModeHasLinesAndTheOthersGetThemElsewhere() {
        assertTrue(TorConfig.bridgeLines(TorBridge.NONE).isEmpty())
        // CUSTOM has none of its own by definition: its lines are the ones the
        // user pasted or the app fetched, and an empty list there is the state
        // the screen exists to get them out of.
        assertTrue(TorConfig.bridgeLines(TorBridge.CUSTOM).isEmpty())

        TorBridge.entries
            .filter { it != TorBridge.NONE && it != TorBridge.CUSTOM }
            .forEach { bridge ->
                val lines = TorConfig.bridgeLines(bridge)
                assertTrue("$bridge has no bridge line", lines.isNotEmpty())
                assertTrue(
                    "$bridge line does not name its transport",
                    lines.all { it.startsWith(bridge.wireName) },
                )
            }
    }

    @Test
    fun aBridgeGetsLongerToBootstrapThanADirectConnection() {
        // A bridge adds a hop that is itself being discovered, and a timeout set
        // for a direct connection would report it broken for working normally.
        assertTrue(
            TorConfig.bootstrapTimeoutMs(TorBridge.OBFS4) >
                TorConfig.bootstrapTimeoutMs(TorBridge.NONE),
        )
        TorBridge.entries.forEach { assertTrue(TorConfig.bootstrapTimeoutMs(it) > 0) }
    }

    @Test
    fun bridgeWireNamesAreStable() {
        // They cross a process boundary on an intent and outlive an upgrade in
        // preferences.
        assertEquals("none", TorBridge.NONE.wireName)
        assertEquals("obfs4", TorBridge.OBFS4.wireName)
        assertEquals("snowflake", TorBridge.SNOWFLAKE.wireName)
        assertFalse(TorBridge.NONE.needsTransports)
        assertTrue(TorBridge.entries.filter { it != TorBridge.NONE }.all { it.needsTransports })
    }
}
