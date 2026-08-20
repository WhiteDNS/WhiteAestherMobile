package com.whitedns.whiteaesther.service

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.SplitTunnel
import com.whitedns.whiteaesther.data.SplitTunnelMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Brings a tunnel up with per-app rules and holds it.
 *
 * The model tests cover which packages survive filtering; only a real
 * `VpnService.Builder` can say whether Android accepts the calls. It throws on
 * an unknown package and refuses an allow list and a deny list together, and
 * either would surface as a connection that fails for no visible reason.
 *
 * Which uid ends up routed has to be checked from outside, the same way the
 * chain's containment is:
 *
 *     -Pandroid.testInstrumentationRunnerArguments.split=true
 *     -Pandroid.testInstrumentationRunnerArguments.hold=90
 */
class SplitTunnelSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val enabled: Boolean
        get() = InstrumentationRegistry.getArguments().getString("split") == "true"

    private val holdSeconds: Long
        get() = InstrumentationRegistry.getArguments().getString("hold")?.toLongOrNull() ?: 0L

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
        Thread.sleep(3_000)
    }

    @Test
    fun aDenyListStillBringsTheTunnelUp() {
        assumeTrue("needs a network and VPN consent", enabled)

        // A package that certainly exists, so this tests the rules rather than
        // the error path for a missing one.
        connect(
            SplitTunnel(
                mode = SplitTunnelMode.EXCEPT,
                packages = setOf("com.android.settings"),
            ),
        )
    }

    @Test
    fun anAllowListStillBringsTheTunnelUp() {
        assumeTrue("needs a network and VPN consent", enabled)

        connect(
            SplitTunnel(
                mode = SplitTunnelMode.ONLY,
                packages = setOf("com.android.settings"),
            ),
        )
    }

    @Test
    fun aPackageThatIsNoLongerInstalledDoesNotRefuseTheConnection() {
        assumeTrue("needs a network and VPN consent", enabled)

        // Android throws for an unknown package. Uninstalling something after
        // choosing it must not leave the user unable to connect at all.
        connect(
            SplitTunnel(
                mode = SplitTunnelMode.EXCEPT,
                packages = setOf("com.android.settings", "com.example.uninstalled.long.ago"),
            ),
        )
    }

    private fun connect(rules: SplitTunnel) {
        val settings = AppSettings(mode = EngineMode.TUN, splitTunnel = rules)
        AetherVpnService.start(
            context,
            settings.toNativeJson(context),
            settings.chain.encode(),
            rules.encode(),
        )

        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            when (EngineStatusStore.status.value.stage) {
                EngineStage.CONNECTED -> {
                    if (holdSeconds > 0) Thread.sleep(holdSeconds * 1_000)
                    assertEquals(
                        "the tunnel did not stay up",
                        EngineStage.CONNECTED,
                        EngineStatusStore.status.value.stage,
                    )
                    return
                }
                EngineStage.ERROR -> throw AssertionError(
                    "rules ${rules.summary()} failed: ${EngineStatusStore.status.value.message}",
                )
                else -> Thread.sleep(500)
            }
        }
        throw AssertionError("never connected with rules ${rules.summary()}")
    }
}
