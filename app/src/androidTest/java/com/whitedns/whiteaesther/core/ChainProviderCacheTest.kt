package com.whitedns.whiteaesther.core

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.ChainSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The reported bug: replace a subscription and the previous one's nodes keep
 * appearing.
 *
 * Provider names used to be positional, so the replacement took the old one's
 * name and found its cache file already on disk and younger than the refresh
 * interval -- and served the previous subscription's nodes as the new one's.
 * Deleting the subscription did not help, because nothing removed the file.
 */
class ChainProviderCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val providers = File(context.filesDir, "chain/providers")

    private val first = ChainSource("A", "https://one.invalid/sub")
    private val second = ChainSource("B", "https://two.invalid/sub")

    @Before
    fun clean() {
        File(context.filesDir, "chain").deleteRecursively()
    }

    @Test
    fun aReplacedSubscriptionLeavesNoCacheForTheNewOneToInherit() {
        val chain = ChainController(context)

        // Stand in for what mihomo writes once it has fetched the first
        // subscription. The start is expected to fail: the descriptor is
        // refused, deliberately, before it can reach sing-tun. What matters is
        // that the config is written and the directory reconciled first.
        providers.mkdirs()
        val stale = File(providers, "${ChainConfig.providerKey(first.url)}.yaml")
        stale.writeText("proxies: []\n")
        assertTrue(stale.exists())

        chain.start(
            settings = ChainSettings(enabled = true, sources = listOf(second)),
            socksPort = null,
            tunFd = -1,
        )

        assertFalse(
            "the replaced subscription's cache survived, so its nodes can come back",
            stale.exists(),
        )

        val config = File(context.filesDir, "chain/config.yaml").readText()
        assertTrue(config.contains(ChainConfig.providerKey(second.url)))
        assertFalse(
            "the new subscription took the old one's provider name",
            config.contains(ChainConfig.providerKey(first.url)),
        )
    }

    @Test
    fun aSubscriptionThatIsStillConfiguredKeepsItsCache() {
        val chain = ChainController(context)

        providers.mkdirs()
        val kept = File(providers, "${ChainConfig.providerKey(first.url)}.yaml")
        kept.writeText("proxies: []\n")

        chain.start(
            settings = ChainSettings(enabled = true, sources = listOf(first, second)),
            socksPort = null,
            tunFd = -1,
        )

        // Re-fetching what has not changed would cost the user a download on
        // every connect, and on a metered phone that is not free.
        assertTrue("a still-configured subscription lost its cache", kept.exists())
    }

    @Test
    fun disablingASubscriptionDropsItsCacheToo() {
        val chain = ChainController(context)

        providers.mkdirs()
        val disabled = File(providers, "${ChainConfig.providerKey(first.url)}.yaml")
        disabled.writeText("proxies: []\n")

        chain.start(
            settings = ChainSettings(
                enabled = true,
                sources = listOf(first.copy(enabled = false), second),
            ),
            socksPort = null,
            tunFd = -1,
        )

        assertFalse("a disabled subscription kept its cache", disabled.exists())
    }
}
