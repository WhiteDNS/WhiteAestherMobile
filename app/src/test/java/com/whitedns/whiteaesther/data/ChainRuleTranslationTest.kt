package com.whitedns.whiteaesther.data

import com.whitedns.whiteaesther.core.ChainConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rules, as mihomo has to receive them.
 *
 * They cannot simply be handed to the engine when a chain is running: mihomo
 * owns the interface and dials each node through the engine's SOCKS5 listener,
 * so the engine only ever sees a node's address and never the destination the
 * rule was written about.
 */
class ChainRuleTranslationTest {
    @Test
    fun aPlainNameCoversItsSubdomains() {
        assertEquals(
            listOf("DOMAIN-SUFFIX,example.com,DIRECT"),
            ChainConfig.mihomoRules("example.com", "DIRECT"),
        )
    }

    @Test
    fun eachPrefixBecomesItsOwnMihomoMatcher() {
        assertEquals(
            listOf("DOMAIN,example.com,REJECT"),
            ChainConfig.mihomoRules("full:example.com", "REJECT"),
        )
        assertEquals(
            listOf("DOMAIN-KEYWORD,tracker,REJECT"),
            ChainConfig.mihomoRules("keyword:tracker", "REJECT"),
        )
        assertEquals(
            listOf("DST-PORT,25,REJECT"),
            ChainConfig.mihomoRules("port:25", "REJECT"),
        )
    }

    @Test
    fun addressRulesRefuseToResolveTheNameTheyAreTesting() {
        // Without no-resolve, mihomo looks a domain up just to check it against
        // the block -- and that lookup leaves the tunnel and names the
        // destination to whoever is watching, which is what the rule was
        // written to prevent.
        assertTrue(
            ChainConfig.mihomoRules("cidr:10.0.0.0/8", "DIRECT")
                .single()
                .endsWith(",no-resolve"),
        )
        assertTrue(
            ChainConfig.mihomoRules("192.168.0.0/16", "DIRECT")
                .single()
                .startsWith("IP-CIDR,"),
        )
    }

    @Test
    fun privateIsSpelledOutRatherThanLeftToADatabase() {
        val rules = ChainConfig.mihomoRules("private", "DIRECT")

        // GEOIP,PRIVATE needs a database this build does not ship, and mihomo
        // drops a rule it cannot resolve without saying so.
        assertTrue(rules.size > 1)
        assertTrue(rules.all { it.startsWith("IP-CIDR,") && it.endsWith(",no-resolve") })
        assertTrue(rules.any { it.contains("192.168.0.0/16") })
    }

    @Test
    fun notesAndBlanksProduceNothing() {
        assertTrue(ChainConfig.mihomoRules("", "DIRECT").isEmpty())
        assertTrue(ChainConfig.mihomoRules("   ", "DIRECT").isEmpty())
        assertTrue(ChainConfig.mihomoRules("# a note", "DIRECT").isEmpty())
    }

    @Test
    fun theRulesReachTheChainSettingsThatTheServiceIsGiven() {
        val settings = AppSettings(
            routeBlock = "ads.example.com",
            routeDirect = "bank.example.ir\nprivate",
        )

        // The rules live on AppSettings because one screen edits them, and the
        // chain needs its own copy. Joining them at one place is what stops a
        // caller starting a chain with no rules at all.
        val forService = settings.chainForService()

        assertEquals(listOf("ads.example.com"), forService.blockRules())
        assertEquals(listOf("bank.example.ir", "private"), forService.directRules())
    }

    @Test
    fun editingARuleMakesTheRunningConfigStale() {
        val running = ChainSettings(manual = "vless://x#A")
        val edited = running.copy(routeDirect = "bank.example.ir")

        // Left out of the fingerprint, editing a rule would leave the app
        // believing the live config was current while mihomo went on routing by
        // the previous set.
        assertTrue(running.fingerprint() != edited.fingerprint())
    }

    @Test
    fun aRemovedNodeStaysRemovedAcrossARefresh() {
        val settings = ChainSettings(manual = "vless://x#A", hiddenNodes = listOf("A"))

        // A node from a subscription is not ours to delete -- mihomo fetches
        // the list again and it comes straight back. Keeping the name is the
        // only form of "remove" that survives that.
        val roundTripped = ChainSettings.decode(settings.encode())

        assertEquals(listOf("A"), roundTripped.hiddenNodes)
    }

    @Test
    fun removingANodeDoesNotDisturbTheRunningConfig() {
        val running = ChainSettings(manual = "vless://x#A")
        val hidden = running.copy(hiddenNodes = listOf("A"))

        // Hiding is the app's own bookkeeping: mihomo is still configured with
        // every node, so a reconnect is not needed and the fingerprint must not
        // claim otherwise.
        assertEquals(running.fingerprint(), hidden.fingerprint())
    }
}
