package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGroupTest {
    @Test
    fun aFreshInstallHasNothingChanged() {
        AdvancedGroup.entries.forEach { assertTrue(it.name, it.isDefault(AppSettings())) }
    }

    @Test
    fun aChangeIsShownOnlyOnItsOwnPage() {
        val tuned = AppSettings(fragmentTls = true)

        assertFalse(AdvancedGroup.AETHER.isDefault(tuned))
        AdvancedGroup.entries.filter { it != AdvancedGroup.AETHER }
            .forEach { assertTrue(it.name, it.isDefault(tuned)) }
    }

    @Test
    fun resetAllPutsEveryChoiceBack() {
        val tuned = AppSettings(
            automaticCarrier = false, carrier = Carrier.TOR, secondCarrier = Carrier.AETHER,
            transport = TunnelProtocol.H2, encryptedHello = true, wgKeepalive = 5,
            mode = EngineMode.PROXY, proxyPort = 4000, killSwitch = true, strictKillSwitch = true,
            routingPresets = setOf(RoutingPreset.entries.first()), chain = ChainSettings(enabled = true),
        )

        val reset = AdvancedGroup.resetAll(tuned)

        AdvancedGroup.entries.forEach { assertTrue(it.name, it.isDefault(reset)) }
    }

    @Test
    fun resetKeepsWhatTheUserTypedOrCollected() {
        // Choosing an option again is one tap; retyping bridge lines, rules or a
        // subscription is not, so reset must never take them.
        val collected = AppSettings(
            torBridges = "obfs4 1.2.3.4:443 FP cert=x iat-mode=0",
            customEndpoint = "162.159.197.3:443",
            routeBlock = "ads.example.com", routeDirect = "bank.example.ir",
            lanUsername = "me", lanPassword = "secret",
            chain = ChainSettings(enabled = true, manual = "vless://node"),
            splitTunnel = SplitTunnel(),
        )

        val reset = AdvancedGroup.resetAll(collected)

        assertEquals(collected.torBridges, reset.torBridges)
        assertEquals(collected.customEndpoint, reset.customEndpoint)
        assertEquals(collected.routeBlock, reset.routeBlock)
        assertEquals(collected.routeDirect, reset.routeDirect)
        assertEquals(collected.lanUsername, reset.lanUsername)
        assertEquals(collected.lanPassword, reset.lanPassword)
        assertEquals("vless://node", reset.chain.manual)
        assertFalse(reset.chain.enabled)
    }

    @Test
    fun resetLeavesTheMethodChosenOnTheConnectScreen() {
        // The method is picked on the connect screen, not under Advanced, so
        // resetting Advanced must not quietly switch the user back to Automatic.
        val manual = AppSettings(
            automaticCarrier = false, carrier = Carrier.TOR, torBridge = TorBridge.SNOWFLAKE, psiphonRegion = "DE",
            mode = EngineMode.PROXY,
        )

        val reset = AdvancedGroup.resetAll(manual)

        assertFalse(reset.automaticCarrier)
        assertEquals(Carrier.TOR, reset.carrier)
        // Set in the same sheet as the method, so they go with it.
        assertEquals(TorBridge.SNOWFLAKE, reset.torBridge)
        assertEquals("DE", reset.psiphonRegion)
        assertEquals(EngineMode.PROXY, reset.mode)
    }

    @Test
    fun resetLeavesTheAppSettingsAlone() {
        val app = AppSettings(themeMode = ThemeMode.DARK, language = AppLanguage.entries.last(), showAdvanced = true)

        assertEquals(app, AdvancedGroup.resetAll(app))
    }
}
