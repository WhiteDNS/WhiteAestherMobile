package com.whitedns.whiteaesther.service

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import org.junit.After
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

    /**
     * Waits for the session that is actually running to say it came up.
     *
     * Not [EngineStatusStore]: that holds one status for the whole service, so
     * immediately after a switch it still reads CONNECTED from the session
     * being replaced. Polling it for CONNECTED returns the outgoing tunnel's
     * state and passes without the new one ever existing -- which is exactly
     * what the first version of this test did, declaring success four seconds
     * into a twenty second endpoint scan.
     *
     * The engine log is per-session and names the transport, so it cannot be
     * satisfied by the tunnel being torn down.
     */
    private fun awaitTunnelUp(transport: String, timeoutMs: Long): Boolean {
        val marker = "up on ${transport.uppercase()}"
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val entries = EngineLog.entries.value
            if (entries.any { it.tag == "tunnel" && it.message == marker }) return true
            if (EngineStatusStore.status.value.stage == EngineStage.ERROR) return false
            Thread.sleep(500)
        }
        return false
    }

    private fun start(protocol: TunnelProtocol) {
        val settings = AppSettings(mode = EngineMode.TUN, transport = protocol)
        AetherVpnService.start(context, settings.toNativeJson(context), null, null)
    }

    /** Everything the engine said about why a scan came up empty. */
    private fun probeSummaries(): List<String> = EngineLog.entries.value
        .map { it.message }
        .filter { it.contains("opened with no protector=") }

    @Test
    fun wireGuardConnectsWhenItReplacesALiveMasqueTunnel() {
        assumeTrue("needs a network and VPN consent", enabled)
        EngineLog.clear()

        start(TunnelProtocol.H2)
        assumeTrue(
            "the MASQUE tunnel this test replaces never came up, so there was " +
                "nothing to switch away from",
            awaitTunnelUp("h2", timeoutMs = 180_000),
        )

        // No stop in between. The service replaces the session in place, which
        // is what the menu does and what the failing phone did.
        start(TunnelProtocol.WIREGUARD)
        val up = awaitTunnelUp("wg", timeoutMs = 420_000)

        assertTrue(
            "WireGuard did not connect when it replaced a live tunnel. " +
                "Engine said: ${probeSummaries().ifEmpty { listOf("(no scan summary)") }}. " +
                "Status: ${EngineStatusStore.status.value.message}",
            up,
        )
    }

    @Test
    fun probeSocketsAreProtectedWhileTheOldTunnelIsStillUp() {
        assumeTrue("needs a network and VPN consent", enabled)
        EngineLog.clear()

        start(TunnelProtocol.H2)
        assumeTrue(
            "the MASQUE tunnel this test replaces never came up",
            awaitTunnelUp("h2", timeoutMs = 180_000),
        )

        start(TunnelProtocol.WIREGUARD)
        awaitTunnelUp("wg", timeoutMs = 420_000)
        Thread.sleep(3_000)

        // The engine counts sockets it opened with no protector installed and
        // reports the figure whenever a scan comes up empty. During a switch
        // that number has to be zero: anything else means probes were routed by
        // the tunnel being replaced, and whatever the scan concluded about the
        // network it never actually reached it.
        //
        // A connect that succeeds outright prints no summary, which is a pass:
        // there was no empty scan to explain.
        val leaked = probeSummaries().filterNot { it.contains("opened with no protector=0") }
        assertTrue(
            "probe sockets escaped the VpnService during a protocol switch: $leaked",
            leaked.isEmpty(),
        )
    }
}
