package com.whitedns.whiteaesther.data

import com.whitedns.whiteaesther.core.ChainConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RuleImportTest {
    @Test
    fun everyCommonListShapeBecomesThisAppsGrammar() {
        val report = RuleImport.parse(
            """
            # a note
            payload:
              - 'DOMAIN-SUFFIX,ads.example.com'
              - "DOMAIN,tracker.example.net"
            DOMAIN-KEYWORD,adserver
            IP-CIDR,203.0.113.0/24,no-resolve
            IP-CIDR6,2001:db8::/32
            0.0.0.0 banner.example.org
            +.metrics.example.com
            plain.example.ir
            198.51.100.7
            GEOIP,private
            DST-PORT,6881-6889
            full:exact.example.com
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "ads.example.com", "full:tracker.example.net", "keyword:adserver", "cidr:203.0.113.0/24",
                "cidr:2001:db8::/32", "banner.example.org", "metrics.example.com", "plain.example.ir",
                "cidr:198.51.100.7/32", "private", "port:6881-6889", "full:exact.example.com",
            ),
            report.accepted,
        )
        assertEquals(0, report.ignoredTotal)
    }

    @Test
    fun whatCannotBeAppliedIsCountedByReason() {
        val report = RuleImport.parse(
            """
            GEOSITE,category-ads-all
            GEOIP,IR
            PROCESS-NAME,com.example
            IP-CIDR,0.0.0.0/0
            IP-CIDR6,::/0
            DST-PORT,443
            DST-PORT,1-1000
            DOMAIN-KEYWORD,ad
            DOMAIN-REGEX,.*
            DOMAIN-REGEX,a{1,3}
            ads.example.com
            ads.example.com
            """.trimIndent(),
        )
        assertEquals(listOf("ads.example.com"), report.accepted)
        assertEquals(2, report.ignored[IgnoredBecause.NEEDS_DATABASE])
        assertEquals(2, report.ignored[IgnoredBecause.UNSUPPORTED])
        assertEquals(6, report.ignored[IgnoredBecause.TOO_BROAD])
        assertEquals(1, report.ignored[IgnoredBecause.DUPLICATE])
    }

    @Test
    fun aListPastTheLimitKeepsTheFirstAndCountsTheRest() {
        val text = (1..RuleImport.MAX_ENTRIES + 3).joinToString("\n") { "host$it.example.com" }
        val report = RuleImport.parse(text)
        assertEquals(RuleImport.MAX_ENTRIES, report.accepted.size)
        assertEquals(3, report.ignored[IgnoredBecause.OVER_LIMIT])
    }

    @Test
    fun theLookupFollowsTheEnginesOrder() {
        val block = "ads.example.com\nkeyword:tracker"
        val direct = "domain:ir\nprivate\ncidr:198.51.100.0/24"
        assertEquals(RuleMatch(RuleAction.BLOCK, "ads.example.com"), RuleMatcher.match("x.ads.example.com", block, direct))
        assertEquals(RuleAction.BLOCK, RuleMatcher.match("ads.example.com", block, "domain:com").action)
        assertEquals(RuleAction.DIRECT, RuleMatcher.match("bank.example.ir", block, direct).action)
        assertEquals(RuleAction.DIRECT, RuleMatcher.match("192.168.1.10", block, direct).action)
        assertEquals(RuleAction.DIRECT, RuleMatcher.match("198.51.100.9", block, direct).action)
        assertNull(RuleMatcher.match("example.org", block, direct).action)
        // A suffix rule is not a substring rule.
        assertNull(RuleMatcher.match("notads.example.com.evil", block, "").action)
    }

    private fun store() = RuleListStore(Files.createTempDirectory("rules").toFile())

    @Test
    fun listsAreKeptSwitchedAndDeletedOnTheirOwn() {
        val store = store()
        val block = store.add("ads.txt", RuleAction.BLOCK, listOf("ads.example.com"))!!
        val direct = store.add("home.txt", RuleAction.DIRECT, listOf("domain:ir"))!!
        assertEquals("ads.example.com", store.active(RuleAction.BLOCK))
        val first = store.signature()
        assertTrue(store.setEnabled(block.id, false))
        assertEquals("", store.active(RuleAction.BLOCK))
        assertFalse(first == store.signature())
        store.delete(direct.id)
        assertEquals(listOf(block.id), store.lists().map { it.id })
        assertEquals("", store.signature())
    }

    @Test
    fun theEnabledTotalHasACeiling() {
        val store = store()
        val many = (1..RuleListStore.MAX_TOTAL).map { "h$it.example.com" }
        val full = store.add("all", RuleAction.BLOCK, many)!!
        assertNull(store.add("one more", RuleAction.BLOCK, listOf("x.example.com")))
        assertTrue(store.setEnabled(full.id, false))
        val small = store.add("small", RuleAction.BLOCK, listOf("x.example.com"))!!
        assertFalse(store.setEnabled(full.id, true))
        store.delete(small.id)
        assertTrue(store.setEnabled(full.id, true))
    }

    @Test
    fun bothEnginesAreGivenTheEnabledListsAfterTheUsersOwnRules() {
        val store = store()
        store.add("ads", RuleAction.BLOCK, listOf("ads.example.com"))
        store.add("home", RuleAction.DIRECT, listOf("domain:ir"))
        val engine = JSONObject(store.mergeInto(JSONObject().put("routeBlock", "mine.example.com").toString()))
        assertEquals("mine.example.com\nads.example.com", engine.getString("routeBlock"))
        assertEquals("domain:ir", engine.getString("routeDirect"))

        val chain = store.withImported(ChainSettings(enabled = false, routeBlock = "mine.example.com"))
        val config = ChainConfig.renderCarrier(chain, socksPort = 41234)
        assertTrue(config.contains("DOMAIN-SUFFIX,ads.example.com,REJECT"))
        assertTrue(config.contains("DOMAIN-SUFFIX,mine.example.com,REJECT"))
        assertTrue(config.contains("DOMAIN-SUFFIX,ir,DIRECT"))
    }

    @Test
    fun nothingImportedLeavesTheConfigsAsTheyWere() {
        val store = store()
        val json = JSONObject().put("routeBlock", "mine.example.com").toString()
        assertEquals(json, store.mergeInto(json))
        val chain = ChainSettings(routeBlock = "mine.example.com")
        assertEquals(chain, store.withImported(chain))
    }
}
