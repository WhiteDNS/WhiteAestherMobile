package com.whitedns.whiteaesther.service

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Brings a WireGuard tunnel up on a device and holds it.
 *
 * Separate from the MASQUE path because almost nothing is shared: a different
 * account provisioned against a different Cloudflare API, a different prober,
 * a different set of endpoints. The only thing in common is the interface it
 * hands packets to, which is exactly what this checks.
 *
 * Hold it open and probe from `adb shell` for the exit address:
 *
 *     -Pandroid.testInstrumentationRunnerArguments.hold=120
 */
class WireGuardSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val holdSeconds: Long
        get() = InstrumentationRegistry.getArguments().getString("hold")?.toLongOrNull() ?: 0L

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
    }

    @Test
    fun warpInWarpConnectsAndCarriesTheInterface() {
        assumeTrue(
            "no wireguard argument, skipping the live tunnel test",
            InstrumentationRegistry.getArguments().getString("wireguard") == "true",
        )

        val settings = AppSettings(mode = EngineMode.TUN, transport = TunnelProtocol.WARP_IN_WARP)
        AetherVpnService.start(context, settings.toNativeJson(context), null)

        // Two handshakes and a second account to provision, the inner one
        // reached only after the outer is carrying traffic.
        val stage = awaitStage(EngineStage.CONNECTED, timeoutMs = 420_000)
        assertEquals(
            "WARP-in-WARP did not connect: ${EngineStatusStore.status.value.message}",
            EngineStage.CONNECTED,
            stage,
        )

        if (holdSeconds > 0) {
            Thread.sleep(holdSeconds * 1_000)
            assertEquals(
                "the nested tunnel did not stay up",
                EngineStage.CONNECTED,
                EngineStatusStore.status.value.stage,
            )
        }
    }

    @Test
    fun wireGuardConnectsAndCarriesTheInterface() {
        // Needs a network, VPN consent, and a WARP account it may have to
        // provision. CI has none of those, so it is opt-in:
        //   -Pandroid.testInstrumentationRunnerArguments.wireguard=true
        assumeTrue(
            "no wireguard argument, skipping the live tunnel test",
            InstrumentationRegistry.getArguments().getString("wireguard") == "true",
        )

        val settings = AppSettings(mode = EngineMode.TUN, transport = TunnelProtocol.WIREGUARD)

        AetherVpnService.start(context, settings.toNativeJson(context), null)

        // Generous: the first WireGuard connect provisions a WARP account and
        // then scans for an endpoint, neither of which the MASQUE identity can
        // be reused for.
        val stage = awaitStage(EngineStage.CONNECTED, timeoutMs = 300_000)
        assertEquals(
            "WireGuard did not connect: ${EngineStatusStore.status.value.message}",
            EngineStage.CONNECTED,
            stage,
        )

        if (holdSeconds > 0) {
            Thread.sleep(holdSeconds * 1_000)
            assertEquals(
                "the WireGuard tunnel did not stay up",
                EngineStage.CONNECTED,
                EngineStatusStore.status.value.stage,
            )
        }
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
