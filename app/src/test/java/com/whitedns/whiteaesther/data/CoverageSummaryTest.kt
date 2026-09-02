package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the home screen claims is being carried.
 *
 * It used to be read from the coverage mode alone, so it said "Whole device"
 * while a per-app rule restricted the tunnel to a single app. Someone whose
 * traffic was not going through had nothing on screen to explain it.
 */
class CoverageSummaryTest {
    @Test
    fun theDefaultReallyIsTheWholeDevice() {
        val settings = AppSettings(mode = EngineMode.TUN)

        assertEquals(Coverage.WholeDevice, settings.coverage())
        assertFalse(settings.coverageIsRestricted())
    }

    @Test
    fun anAllowListIsNotTheWholeDevice() {
        val settings = AppSettings(
            mode = EngineMode.TUN,
            splitTunnel = SplitTunnel(SplitTunnelMode.ONLY, setOf("org.telegram.messenger")),
        )

        // The exact wording that was wrong before: this must never read as
        // "Whole device" while one app is carried and the rest are not.
        assertEquals(Coverage.OnlySome(1), settings.coverage())
        assertTrue(settings.coverageIsRestricted())
    }

    @Test
    fun aDenyListIsNotTheWholeDeviceEither() {
        val settings = AppSettings(
            mode = EngineMode.TUN,
            splitTunnel = SplitTunnel(SplitTunnelMode.EXCEPT, setOf("com.bank.app", "ir.local")),
        )

        assertEquals(Coverage.AllExcept(2), settings.coverage())
        assertTrue(settings.coverageIsRestricted())
    }

    @Test
    fun anEmptyDenyListStillCoversEverything() {
        // Nothing is excluded, so nothing is restricted -- warning here would be
        // crying wolf on a configuration that carries every app.
        val settings = AppSettings(
            mode = EngineMode.TUN,
            splitTunnel = SplitTunnel(SplitTunnelMode.EXCEPT, emptySet()),
        )

        assertEquals(Coverage.WholeDevice, settings.coverage())
        assertFalse(settings.coverageIsRestricted())
    }

    @Test
    fun anAllowListWithNothingInItSaysSo() {
        val settings = AppSettings(
            mode = EngineMode.TUN,
            splitTunnel = SplitTunnel(SplitTunnelMode.ONLY, emptySet()),
        )

        assertEquals(Coverage.NothingChosen, settings.coverage())
        assertTrue(settings.coverageIsRestricted())
    }

    @Test
    fun proxyModeIsReportedAsProxyRegardlessOfAnyRule() {
        // Per-app rules only apply to a tunnel interface. Reporting a rule here
        // would describe something that is not in effect.
        val settings = AppSettings(
            mode = EngineMode.PROXY,
            splitTunnel = SplitTunnel(SplitTunnelMode.ONLY, setOf("org.telegram.messenger")),
        )

        assertEquals(Coverage.ProxyOnly, settings.coverage())
        assertFalse(settings.coverageIsRestricted())
    }
}
