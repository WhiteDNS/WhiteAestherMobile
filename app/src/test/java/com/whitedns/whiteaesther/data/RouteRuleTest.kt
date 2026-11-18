package com.whitedns.whiteaesther.data

import com.whitedns.whiteaesther.core.ChainConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One grammar for the engine, mihomo and the lookup.
 *
 * Each of them used to read rules its own way, and what one understood another
 * dropped without a word: these are the entries that went missing.
 */
class RouteRuleTest {
    @Test
    fun whatPeoplePasteBecomesTheRuleTheyMeant() {
        assertEquals("domain:www.sanjesh.org", RouteRule.canonical("https://www.sanjesh.org/"))
        assertEquals("domain:torob.com", RouteRule.canonical("http://torob.com/path?q=1"))
        assertEquals("domain:sanjesh.org", RouteRule.canonical("sanjesh.org:443"))
        assertEquals("domain:sanjesh.org", RouteRule.canonical("Sanjesh.ORG."))
        assertEquals("domain:example.com", RouteRule.canonical("*.example.com"))
        assertEquals("domain:example.com", RouteRule.canonical("domain:https://example.com/"))
        assertEquals("full:example.com", RouteRule.canonical("full:Example.com"))
    }

    @Test
    fun addressesWithoutAPrefixAreAddresses() {
        assertEquals("cidr:1.2.3.4/32", RouteRule.canonical("1.2.3.4"))
        assertEquals("cidr:10.0.0.0/8", RouteRule.canonical("10.0.0.0/8"))
        assertEquals("cidr:2001:db8::1/128", RouteRule.canonical("2001:db8::1"))
        assertEquals("cidr:2001:db8::/32", RouteRule.canonical("2001:db8::/32"))
        assertEquals("cidr:2001:db8::/32", RouteRule.canonical("cidr:2001:db8::/32"))
        assertEquals("cidr:2001:db8::1/128", RouteRule.canonical("[2001:db8::1]"))
    }

    @Test
    fun prefixedRulesAreCheckedRatherThanTrusted() {
        assertEquals("port:3000-3010", RouteRule.canonical("port:3010-3000"))
        assertEquals("keyword:tracker", RouteRule.canonical("keyword:Tracker"))
        assertEquals("regexp:^ad[0-9]+\\.", RouteRule.canonical("regexp:^ad[0-9]+\\."))
        assertEquals("private", RouteRule.canonical("geoip:private"))
        assertNull(RouteRule.canonical("port:99999"))
        assertNull(RouteRule.canonical("regexp:[unclosed"))
        assertNull(RouteRule.canonical("geosite:cn"))
        assertNull(RouteRule.canonical("cidr:1.2.3.4/40"))
        assertNull(RouteRule.canonical("not a rule"))
    }

    @Test
    fun ignoredEntriesAreNamedAndNotCounted() {
        val raw = "sanjesh.org\n# note\nnot a rule\nport:abc"
        assertEquals(listOf("not a rule", "port:abc"), RouteRule.invalid(raw))
        assertEquals(1, RouteRule.count(raw))
        assertEquals(RuleCounts(0, 1), AppSettings(routeDirect = raw).routingCounts())
    }

    @Test
    fun mihomoGetsTheSameRulesTheEngineDoes() {
        assertEquals(listOf("DOMAIN-SUFFIX,example.com,DIRECT"), ChainConfig.mihomoRules("*.example.com", "DIRECT"))
        assertEquals(listOf("IP-CIDR,1.2.3.4/32,DIRECT,no-resolve"), ChainConfig.mihomoRules("1.2.3.4", "DIRECT"))
        assertEquals(listOf("IP-CIDR6,2001:db8::/32,DIRECT,no-resolve"), ChainConfig.mihomoRules("2001:db8::/32", "DIRECT"))
        assertEquals(listOf("DOMAIN-SUFFIX,www.sanjesh.org,DIRECT"), ChainConfig.mihomoRules("https://www.sanjesh.org/", "DIRECT"))
    }

    @Test
    fun theLookupAgreesWithTheEngines() {
        assertEquals(RuleAction.DIRECT, RuleMatcher.match("a.example.com", "", "*.example.com").action)
        assertEquals(RuleAction.DIRECT, RuleMatcher.match("1.2.3.4", "", "1.2.3.4").action)
        assertEquals(RuleAction.DIRECT, RuleMatcher.match("2001:db8::1", "", "2001:db8::/32").action)
        assertEquals(RuleAction.DIRECT, RuleMatcher.match("www.sanjesh.org", "", "https://www.sanjesh.org/").action)
        assertEquals(RuleAction.DIRECT, RuleMatcher.match("https://www.sanjesh.org/x", "", "sanjesh.org").action)
        assertNull(RuleMatcher.match("example.org", "", "not a rule").action)
    }

    @Test
    fun theEngineIsSentTheCanonicalForm() {
        // What toNativeJson puts in routeDirect.
        assertEquals(
            "domain:www.sanjesh.org\ncidr:2001:db8::1/128",
            RouteRule.normalize(AppSettings(routeDirect = "https://www.sanjesh.org/\n2001:db8::1\njunk here").effectiveRouteDirect()),
        )
    }

    @Test
    fun editingRulesWhileConnectedReconnects() {
        val base = AppSettings()
        assertTrue(base.copy(routeDirect = "x.ir").connectionChoiceChanged(base))
        assertTrue(base.copy(routeBlock = "x.ir").connectionChoiceChanged(base))
        assertTrue(base.copy(routeSniff = false).connectionChoiceChanged(base))
        // A note or a space is not a different rule set.
        assertFalse(base.copy(routeDirect = "  # later").connectionChoiceChanged(base))
    }

    @Test
    fun mihomoSniffsUnlessTheUserTurnedItOff() {
        val on = AppSettings(routeDirect = "x.ir").chainForService()
        val off = AppSettings(routeDirect = "x.ir", routeSniff = false).chainForService()
        assertTrue(ChainConfig.renderCarrier(on, 1080).contains("sniffer:\n  enable: true"))
        assertFalse(ChainConfig.renderCarrier(off, 1080).contains("sniffer:"))
        assertEquals(false, ChainSettings.decode(off.encode()).sniff)
        assertTrue(on.hasRules)
        assertFalse(AppSettings(routeDirect = "junk here").chainForService().hasRules)
    }
}
