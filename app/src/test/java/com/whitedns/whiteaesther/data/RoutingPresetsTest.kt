package com.whitedns.whiteaesther.data

import com.whitedns.whiteaesther.core.ChainConfig
import org.junit.Assert.*
import org.junit.Test

class RoutingPresetsTest {
    @Test
    fun presetsAreOptInAndNeverReplaceCustomRules() {
        val original = AppSettings(routeBlock = "blocked.example", routeDirect = "direct.example")
        assertEquals("blocked.example", original.effectiveRouteBlock())
        assertEquals("direct.example", original.effectiveRouteDirect())
        val settings = original.copy(routingPresets = RoutingPreset.entries.toSet())
        assertTrue(settings.effectiveRouteBlock().contains("blocked.example"))
        assertTrue(settings.effectiveRouteBlock().contains("doubleclick.net"))
        assertTrue(settings.effectiveRouteDirect().contains("direct.example"))
        assertTrue(settings.effectiveRouteDirect().contains("domain:ir"))
        assertTrue(settings.effectiveRouteDirect().contains("private"))
    }

    @Test
    fun presetsReachEveryCarrierAndBlockRulesWinOverBypass() {
        val settings = AppSettings(
            routeBlock = "bank.ir",
            routingPresets = setOf(RoutingPreset.IRANIAN_DOMAINS),
        )
        val chain = settings.chainForService()
        val config = ChainConfig.renderCarrier(chain, 1819, udp = true)
        assertEquals(settings.effectiveRouteBlock(), chain.routeBlock)
        assertEquals(settings.effectiveRouteDirect(), chain.routeDirect)
        assertTrue(config.indexOf("DOMAIN-SUFFIX,bank.ir,REJECT") < config.indexOf("DOMAIN-SUFFIX,ir,DIRECT"))
    }

    @Test
    fun storageIgnoresUnknownPresetsAndPreservesKnownChoices() {
        val presets = setOf(RoutingPreset.IRANIAN_DOMAINS, RoutingPreset.COMMON_ADS)
        assertEquals(presets, RoutingPreset.decode(RoutingPreset.encode(presets)))
        assertEquals(emptySet<RoutingPreset>(), RoutingPreset.decode("not json"))
        assertEquals(setOf(RoutingPreset.COMMON_ADS), RoutingPreset.decode("[\"COMMON_ADS\",\"FUTURE\"]"))
    }
}
