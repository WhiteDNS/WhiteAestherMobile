package com.whitedns.whiteaesther.service

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.ChainSource
import com.whitedns.whiteaesther.data.EngineMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Brings the exit chain up on a device and holds it, so the routing loop can be
 * checked from outside the app.
 *
 * The check that matters cannot be made from in here. Everything this process
 * opens is excluded from the interface -- that exclusion is the containment
 * being tested -- so a request from this test would bypass the chain and prove
 * nothing. It has to come from another uid, which in practice means `adb shell`
 * while this test holds the tunnel open. See `docs/EXIT_CHAIN_PLAN.md`.
 *
 * Needs a subscription, which is not in the repository:
 *
 *     -Pandroid.testInstrumentationRunnerArguments.chainSub=<url>
 *
 * and VPN consent already granted:
 *
 *     adb shell appops set com.whitedns.whiteaesther ACTIVATE_VPN allow
 */
class ChainSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val subscription: String?
        get() = InstrumentationRegistry.getArguments().getString("chainSub")

    private val holdSeconds: Long
        get() = InstrumentationRegistry.getArguments().getString("chainHold")?.toLongOrNull() ?: 0L

    /**
     * Whether to put the MASQUE tunnel underneath. Off by default so a failure
     * is unambiguous: with it on, either half can be the one that broke.
     */
    private val throughTunnel: Boolean
        get() = InstrumentationRegistry.getArguments().getString("chainThroughTunnel") == "true"

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
    }

    @Test
    fun chainReachesConnectedAndHolds() {
        val url = subscription
        assumeTrue("no chainSub argument, skipping the live chain test", !url.isNullOrBlank())

        val settings = AppSettings(
            mode = EngineMode.TUN,
            chain = ChainSettings(
                enabled = true,
                throughTunnel = throughTunnel,
                sources = listOf(ChainSource("Test", url!!, true)),
            ),
        )

        AetherVpnService.start(
            context,
            settings.toNativeJson(context),
            settings.chain.encode(),
        )

        val status = awaitStage(EngineStage.CONNECTED, timeoutMs = if (throughTunnel) 300_000 else 180_000)
        assertEquals(
            "the chain did not connect: ${EngineStatusStore.status.value.message}",
            EngineStage.CONNECTED,
            status,
        )

        // Held open so an `adb shell` request can be made against it. Zero by
        // default so the suite does not stall in CI.
        if (holdSeconds > 0) {
            Thread.sleep(holdSeconds * 1_000)
            assertEquals(
                "the chain did not stay up",
                EngineStage.CONNECTED,
                EngineStatusStore.status.value.stage,
            )
        }

        // mihomo's own log has to be reaching ours. Without it the chain is a
        // black box: a node that will not dial, a provider that will not parse
        // and a health check that never passes all look identical from outside,
        // and the diagnostics report the user sends would say nothing about the
        // half that failed.
        assertTrue(
            "no log from the chain reached the app",
            EngineLog.entries.value.any { it.tag == "chain" },
        )
    }

    @Test
    fun aChainWithNoNodesIsRefusedRatherThanConnectedWithout() {
        val settings = AppSettings(
            mode = EngineMode.TUN,
            chain = ChainSettings(enabled = true, sources = emptyList()),
        )

        AetherVpnService.start(
            context,
            settings.toNativeJson(context),
            settings.chain.encode(),
        )

        // The failure that would not look like one: connecting anyway means the
        // user believes traffic leaves from their node while it leaves from
        // Cloudflare. It has to be refused, out loud.
        val status = awaitStage(EngineStage.ERROR, timeoutMs = 30_000)
        assertEquals(EngineStage.ERROR, status)
        assertTrue(
            "the refusal did not say what was missing: ${EngineStatusStore.status.value.message}",
            EngineStatusStore.status.value.message.contains("subscription", ignoreCase = true),
        )
    }

    private fun awaitStage(target: EngineStage, timeoutMs: Long): EngineStage {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = EngineStatusStore.status.value.stage
            if (current == target || current == EngineStage.ERROR) return current
            Thread.sleep(500)
        }
        return EngineStatusStore.status.value.stage
    }
}
