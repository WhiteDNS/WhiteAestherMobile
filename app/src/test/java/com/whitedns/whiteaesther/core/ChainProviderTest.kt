package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.ChainSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainProviderTest {
    @Test
    fun replacingASubscriptionDoesNotInheritItsCache() {
        // The bug this exists for: provider names were positional, so deleting
        // the first subscription and adding a different one gave the new one the
        // old one's name and the old one's cache file. mihomo then found a
        // provider it already had, younger than its refresh interval, and served
        // the previous subscription's nodes as though they were the new one's.
        val first = ChainConfig.providerKey("https://one.invalid/sub")
        val second = ChainConfig.providerKey("https://two.invalid/sub")

        assertNotEquals(first, second)
    }

    @Test
    fun theSameSubscriptionKeepsItsCacheAcrossRestarts() {
        // The other half: re-adding what you already had should reuse the cache
        // rather than re-fetching, so the key has to be stable for a given URL.
        assertEquals(
            ChainConfig.providerKey("https://one.invalid/sub"),
            ChainConfig.providerKey(" https://one.invalid/sub "),
        )
    }

    @Test
    fun providerKeysAreUsableAsBothYamlKeysAndFilenames() {
        val key = ChainConfig.providerKey("https://one.invalid/sub?token=a/b#c")

        assertTrue(key.all { it.isLetterOrDigit() })
    }

    @Test
    fun theRenderedConfigNamesProvidersByUrl() {
        val settings = ChainSettings(
            enabled = true,
            sources = listOf(
                ChainSource("A", "https://one.invalid/sub"),
                ChainSource("B", "https://two.invalid/sub"),
            ),
        )
        val config = ChainConfig.render(settings, socksPort = 1819)

        assertTrue(config.contains(ChainConfig.providerKey("https://one.invalid/sub")))
        assertTrue(config.contains(ChainConfig.providerKey("https://two.invalid/sub")))
        assertFalse("positional names are what caused the stale cache", config.contains("source0:"))
    }

    @Test
    fun changingSourcesChangesTheFingerprint() {
        // What tells the screen that the running engine is describing a
        // subscription the user has already replaced.
        val before = ChainSettings(
            enabled = true,
            sources = listOf(ChainSource("A", "https://one.invalid/sub")),
        )
        val after = before.copy(sources = listOf(ChainSource("B", "https://two.invalid/sub")))

        assertNotEquals(before.fingerprint(), after.fingerprint())
    }

    @Test
    fun pickingANodeDoesNotChangeTheFingerprint() {
        // Node selection happens on the live engine without a restart, so
        // treating it as a configuration change would report the chain stale
        // every time somebody switched node.
        val settings = ChainSettings(
            enabled = true,
            sources = listOf(ChainSource("A", "https://one.invalid/sub")),
        )

        assertEquals(settings.fingerprint(), settings.copy(node = "tokyo-01").fingerprint())
    }

    @Test
    fun disablingASourceChangesTheFingerprint() {
        val settings = ChainSettings(
            enabled = true,
            sources = listOf(ChainSource("A", "https://one.invalid/sub", enabled = true)),
        )
        val disabled = settings.copy(sources = settings.sources.map { it.copy(enabled = false) })

        assertNotEquals(settings.fingerprint(), disabled.fingerprint())
    }
}
