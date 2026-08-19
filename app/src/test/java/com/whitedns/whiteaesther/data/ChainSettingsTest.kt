package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainSettingsTest {
    @Test
    fun theChainIsOffUntilAskedFor() {
        val settings = ChainSettings()

        assertFalse(settings.enabled)
        // On, though, whenever the chain itself is. It is what hides the node's
        // address from the local network, so it must never be something the user
        // has to find.
        assertTrue(settings.throughTunnel)
    }

    @Test
    fun aChainWithNothingToRouteThroughIsRefused() {
        // The refusal that matters. Connecting anyway means the user believes
        // their traffic leaves from their node while it leaves from Cloudflare.
        val error = ChainSettings(enabled = true).startupError()
        assertTrue(error.orEmpty().contains("subscription"))
    }

    @Test
    fun anOffChainIsNeverRefused() {
        // Nothing about an unconfigured chain should block an ordinary connect.
        assertNull(ChainSettings(enabled = false).startupError())
    }

    @Test
    fun aDisabledSourceDoesNotCount() {
        val settings = ChainSettings(
            enabled = true,
            sources = listOf(ChainSource("Test", "https://example.invalid/sub", enabled = false)),
        )

        assertFalse(settings.hasNodes)
        assertTrue(settings.startupError().orEmpty().contains("subscription"))
    }

    @Test
    fun pastedNodesCountOnTheirOwn() {
        val settings = ChainSettings(enabled = true, manual = "vless://example")

        assertTrue(settings.hasNodes)
        assertNull(settings.startupError())
    }

    @Test
    fun settingsSurviveTheRoundTripToStorage() {
        val original = ChainSettings(
            enabled = true,
            throughTunnel = false,
            sources = listOf(
                ChainSource("One", "https://one.invalid/sub#token", true),
                ChainSource("Two", "https://two.invalid/sub", false),
            ),
            manual = "vless://example\ntrojan://example",
            node = "tokyo-01",
        )

        assertEquals(original, ChainSettings.decode(original.encode()))
    }

    @Test
    fun storageThatCannotBeReadFallsBackToOff() {
        // Preferences that were corrupted, or written by a build that shaped this
        // differently. Defaulting to off is the safe direction: the alternative is
        // a chain the user did not ask for.
        assertEquals(ChainSettings(), ChainSettings.decode("not json at all"))
        assertEquals(ChainSettings(), ChainSettings.decode(null))
        assertEquals(ChainSettings(), ChainSettings.decode(""))
    }

    @Test
    fun aSourceWithoutAUrlIsDropped() {
        val decoded = ChainSettings.decode(
            """{"enabled":true,"sources":[{"name":"Broken"},{"name":"Fine","url":"https://ok.invalid"}]}""",
        )

        assertEquals(1, decoded.sources.size)
        assertEquals("https://ok.invalid", decoded.sources.single().url)
    }
}
