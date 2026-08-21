package com.whitedns.whiteaesther.service

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Changing protocol while a tunnel is already up.
 *
 * This is how people actually use the app -- MASQUE will not carry, so they
 * open the menu and pick WireGuard -- and it is the one transition nothing
 * tested. Every existing protocol test starts from nothing: the service is
 * stopped, no interface exists, and the endpoint hunt runs on a clean network
 * path. Started that way WireGuard connects, which is why the failure was
 * invisible here while being total on a real phone.
 *
 * Switching is different in a way that matters. The hunt runs inside prepare(),
 * before the new interface is built, while the outgoing tunnel is still the
 * system route. Its probe sockets have to be protected or they are carried by
 * the interface being torn down -- and then no endpoint answers, anywhere,
 * which reads in the log exactly like a network that drops UDP.
 *
 *     -Pandroid.testInstrumentationRunnerArguments.switch=true
 */
class ProtocolSwitchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val enabled: Boolean
        get() = InstrumentationRegistry.getArguments().getString("switch") == "true"

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
        Thread.sleep(5_000)
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

    private fun connect(protocol: TunnelProtocol, timeoutMs: Long): EngineStage {
        val settings = AppSettings(mode = EngineMode.TUN, transport = protocol)
        AetherVpnService.start(context, settings.toNativeJson(context), null, null)
        return awaitStage(EngineStage.CONNECTED, timeoutMs)
    }

    @Test
    fun wireGuardConnectsWhenItReplacesALiveMasqueTunnel() {
        assumeTrue("needs a network and VPN consent", enabled)

        val masque = connect(TunnelProtocol.H2, timeoutMs = 180_000)
        assertEquals(
            "the MASQUE tunnel this test replaces never came up: " +
                EngineStatusStore.status.value.message,
            EngineStage.CONNECTED,
            masque,
        )

        // No stop in between. The service replaces the session in place, which
        // is what the menu does and what the failing phone did.
        val wireGuard = connect(TunnelProtocol.WIREGUARD, timeoutMs = 420_000)
        assertEquals(
            "WireGuard did not connect when it replaced a live tunnel: " +
                EngineStatusStore.status.value.message,
            EngineStage.CONNECTED,
            wireGuard,
        )
    }

    @Test
    fun probeSocketsAreProtectedWhileTheOldTunnelIsStillUp() {
        assumeTrue("needs a network and VPN consent", enabled)

        assertEquals(
            "the MASQUE tunnel this test replaces never came up",
            EngineStage.CONNECTED,
            connect(TunnelProtocol.H2, timeoutMs = 180_000),
        )
        connect(TunnelProtocol.WIREGUARD, timeoutMs = 420_000)
        Thread.sleep(3_000)

        // The engine counts sockets it opened with no protector installed, and
        // reports the figure whenever a scan comes up empty. During a switch
        // that number has to be zero: anything else means probes were routed by
        // the tunnel being replaced, and whatever the scan concluded about the
        // network it never actually reached it.
        val summaries = EngineLog.entries.value
            .map { it.message }
            .filter { it.contains("opened with no protector=") }

        val leaked = summaries.filterNot { it.contains("opened with no protector=0") }
        assertTrue(
            "probe sockets escaped the VpnService during a protocol switch: $leaked",
            leaked.isEmpty(),
        )
    }
}
