package com.whitedns.whiteaesther.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming the REALITY nodes in a subscription.
 *
 * Reads the provider text rather than asking mihomo, which reports a node's
 * protocol but not its security layer -- so a REALITY node is indistinguishable
 * from any other VLESS one until it fails to authenticate.
 */
class RealityNodesTest {
    @Test
    fun theClashFormIsReadFromItsInlineEntries() {
        val yaml = """
            proxies:
              - {name: Tokyo A, type: vless, server: a.example, port: 443, reality-opts: {public-key: k}}
              - {name: Tokyo B, type: vless, server: b.example, port: 443}
        """.trimIndent()

        assertEquals(listOf("Tokyo A"), RealityNodes.namesIn(yaml))
    }

    @Test
    fun theClashFormIsReadFromItsBlockEntries() {
        val yaml = """
            proxies:
              - name: "Frankfurt 🇩🇪"
                type: vless
                server: c.example
                reality-opts:
                  public-key: k
              - name: Plain One
                type: trojan
                server: d.example
        """.trimIndent()

        assertEquals(listOf("Frankfurt 🇩🇪"), RealityNodes.namesIn(yaml))
    }

    @Test
    fun theUriFormIsReadFromItsQueryAndFragment() {
        val links = """
            vless://uuid@a.example:443?security=reality&pbk=k&sni=x#Node%20One
            vless://uuid@b.example:443?security=tls&sni=x#Node%20Two
        """.trimIndent()

        // The fragment is percent-encoded and node names routinely carry
        // spaces, so a raw fragment would never match what mihomo reports.
        assertEquals(listOf("Node One"), RealityNodes.namesIn(links))
    }

    @Test
    fun bothBase64AlphabetsAreTried() {
        // Subscriptions use either, and a link containing - or _ decoded under
        // the wrong alphabet becomes bytes that match nothing rather than an
        // error anybody would see.
        val link = "vless://uuid@a.example:443?security=reality&pbk=k#Berlin"
        val encoders = listOf(
            "standard" to java.util.Base64.getEncoder(),
            "url-safe" to java.util.Base64.getUrlEncoder(),
        )
        for ((label, encoder) in encoders) {
            val encoded = encoder.encodeToString(link.toByteArray())
            assertEquals(label, listOf("Berlin"), RealityNodes.namesIn(encoded))
            // Served unpadded is the common case, and the decoders demand it.
            assertEquals(label, listOf("Berlin"), RealityNodes.namesIn(encoded.trimEnd('=')))
        }
    }

    @Test
    fun aSubscriptionWithoutRealityNamesNothing() {
        val yaml = """
            proxies:
              - {name: A, type: trojan, server: a.example, port: 443}
              - {name: B, type: hysteria2, server: b.example, port: 443}
        """.trimIndent()

        assertTrue(RealityNodes.namesIn(yaml).isEmpty())
        assertTrue(RealityNodes.namesIn("").isEmpty())
        assertTrue(RealityNodes.namesIn("   \n  ").isEmpty())
    }

    @Test
    fun everyRealityNodeIsNamedRatherThanJustTheFirst() {
        val yaml = """
            proxies:
              - {name: One, type: vless, reality-opts: {public-key: k}}
              - {name: Two, type: vless}
              - {name: Three, type: vless, reality-opts: {public-key: k}}
        """.trimIndent()

        assertEquals(listOf("One", "Three"), RealityNodes.namesIn(yaml))
    }
}
