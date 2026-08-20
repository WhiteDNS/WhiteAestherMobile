package com.whitedns.whiteaesther.service

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EngineMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The default: whole device, no per-app rules, everything carried.
 *
 * This is the configuration almost every user runs and the one nothing else
 * tests directly -- the split-tunnel tests all set a rule, and the protocol
 * tests care about which tunnel came up rather than what it carries. A
 * regression here is invisible to all of them and total for the user.
 *
 *     -Pandroid.testInstrumentationRunnerArguments.whole=true
 *     -Pandroid.testInstrumentationRunnerArguments.hold=90
 */
class WholeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val enabled: Boolean
        get() = InstrumentationRegistry.getArguments().getString("whole") == "true"

    private val holdSeconds: Long
        get() = InstrumentationRegistry.getArguments().getString("hold")?.toLongOrNull() ?: 0L

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
        Thread.sleep(3_000)
    }

    @Test
    fun defaultsCarryEveryApp() {
        assumeTrue("needs a network and VPN consent", enabled)

        // Exactly what a fresh install connects with: no chain, no split tunnel,
        // nothing passed for either.
        val settings = AppSettings(mode = EngineMode.TUN)
        AetherVpnService.start(context, settings.toNativeJson(context), null, null)

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
                    "whole device failed: ${EngineStatusStore.status.value.message}",
                )
                else -> Thread.sleep(500)
            }
        }
        throw AssertionError("whole device never connected")
    }
}
