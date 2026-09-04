package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.TorBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bridge lines as they actually arrive: pasted out of a Telegram reply, or
 * fetched from Tor's recommendation service.
 *
 * The parsing is forgiving on purpose, and these say how far. Someone under a
 * censored network, copying from a chat on a phone, is not going to produce a
 * clean list -- and losing their bridges to a stray greeting line is a failure
 * they cannot debug.
 */
class TorBridgesTest {
    private val obfs4 =
        "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=abc iat-mode=0"
    private val snowflake =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B28 url=https://x/"

    @Test
    fun aTelegramReplyKeepsItsBridgesAndLosesItsGreeting() {
        val pasted = """
            Here are your bridges:

            $obfs4
            $obfs4

            Learn more at https://bridges.torproject.org
        """.trimIndent()

        val lines = TorBridges.parse(pasted)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("obfs4 ") })
    }

    @Test
    fun aCommentedLineIsNotABridge() {
        assertTrue(TorBridges.parse("# $obfs4").isEmpty())
    }

    @Test
    fun somethingThatIsNotABridgeIsDroppedRatherThanRefused() {
        // The address is what separates a bridge from a sentence about bridges,
        // and refusing the whole paste over one bad line is how a user loses
        // the good ones.
        val lines = TorBridges.parse("hello\n$obfs4\nnot a bridge at all\n")
        assertEquals(listOf(obfs4), lines)
    }

    @Test
    fun theTransportComesFromTheLineAndNotFromTheScreen() {
        assertEquals("obfs4", TorBridges.transportOf(TorBridges.parse(obfs4)))
        assertEquals("snowflake", TorBridges.transportOf(TorBridges.parse(snowflake)))
        assertNull(TorBridges.transportOf(emptyList()))
    }

    @Test
    fun aMixedPasteRunsOneTransportAndDropsTheOther() {
        // One proxy runs at a time. Handing tor bridges for a transport that is
        // not running fails as an unreachable bridge rather than as the mix-up
        // it is, which is a much harder thing to be told.
        val lines = TorBridges.parse("$obfs4\n$snowflake")
        val transport = TorBridges.transportOf(lines)
        assertEquals("obfs4", transport)
        assertEquals(listOf(obfs4), TorBridges.forTransport(lines, transport!!))
    }

    @Test
    fun eachTransportIsAskedOfTheBinaryThatImplementsIt() {
        // Asking lyrebird for snowflake is a CMETHOD-ERROR and a bridge mode
        // that never works.
        assertEquals("libsnowflake.so", TorBridges.binaryFor("snowflake"))
        assertEquals("liblyrebird.so", TorBridges.binaryFor("obfs4"))
        assertEquals("liblyrebird.so", TorBridges.binaryFor("webtunnel"))
        assertEquals("liblyrebird.so", TorBridges.binaryFor("meek_lite"))
    }

    @Test
    fun theSummarySaysHowManyAndOfWhat() {
        assertEquals("2 obfs4", TorBridges.summarise("$obfs4\n$obfs4"))
        assertNull(TorBridges.summarise(""))
        assertNull(TorBridges.summarise("just some words"))
    }

    @Test
    fun customBridgesReachTheTorrcAndNothingElseDoes() {
        val config = TorConfig.render(
            TorBridge.CUSTOM,
            null,
            "127.0.0.1:9999",
            customBridges = "$obfs4\n$snowflake",
        )

        assertTrue(config.contains("ClientTransportPlugin obfs4 socks5 127.0.0.1:9999"))
        assertTrue(config.contains("Bridge $obfs4"))
        // The snowflake line is for a proxy that is not running.
        assertFalse(config.contains("Bridge snowflake"))
    }

    @Test
    fun customWithNothingUsableIsRefusedRatherThanStartedBare() {
        // The failure this prevents: no plugin line, no UseBridges, and a tor
        // that connects directly under a screen that says it is behind a
        // bridge -- on a network where direct is exactly what does not work.
        assertEquals(TorConfig.Refusal.NO_BRIDGES, TorConfig.refusal(TorBridge.CUSTOM, ""))
        assertEquals(
            TorConfig.Refusal.NO_BRIDGES,
            TorConfig.refusal(TorBridge.CUSTOM, "no bridges here"),
        )
        assertNull(TorConfig.refusal(TorBridge.CUSTOM, obfs4))
        assertNull(TorConfig.refusal(TorBridge.OBFS4, ""))

        val bare = TorConfig.render(TorBridge.CUSTOM, null, "127.0.0.1:9999", customBridges = "")
        assertFalse(bare.contains("UseBridges"))
        assertFalse(bare.contains("ClientTransportPlugin"))
    }

    @Test
    fun torsRecommendationsAreReadInTheOrderTorGivesThem() {
        // The order is the recommendation: Tor lists what to try first for that
        // country, and the app can only run one transport at a time.
        val body = """
            {"settings":[
              {"bridges":{"type":"webtunnel","source":"bridgedb","bridge_strings":["webtunnel [::1]:443 FP url=https://x/ ver=0.0.5"]}},
              {"bridges":{"type":"snowflake","source":"bridgedb","bridge_strings":["$snowflake"]}},
              {"bridges":{"type":"obfs4","source":"bridgedb","bridge_strings":["$obfs4"]}}
            ]}
        """.trimIndent()

        val parsed = MoatClient.parse(body)
        assertEquals(listOf("webtunnel", "snowflake", "obfs4"), parsed.map { it.transport })
        assertEquals(1, parsed.first().lines.size)
    }

    @Test
    fun anEmptyOrBrokenAnswerIsNoRecommendationRatherThanACrash() {
        assertTrue(MoatClient.parse("""{"settings":[]}""").isEmpty())
        assertTrue(MoatClient.parse("{}").isEmpty())
        // A setting with a type and no lines is not something to select.
        assertTrue(
            MoatClient.parse("""{"settings":[{"bridges":{"type":"obfs4","bridge_strings":[]}}]}""")
                .isEmpty(),
        )
    }
}
