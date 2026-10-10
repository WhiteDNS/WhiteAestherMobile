package com.whitedns.whiteaesther.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHandoffTest {
    @Test fun configurationFailureClosesTheTunnelWithoutDetaching() {
        var closed = 0
        var detached = 0
        val tunnel = TunnelHandoff({ detached++; 42 }, { closed++ })
        runCatching {
            tunnel.use { ChainConfig.render(com.whitedns.whiteaesther.data.ChainSettings(enabled = true), 1819) }
        }
        tunnel.close()
        assertEquals(1, closed)
        assertEquals(0, detached)
    }

    @Test fun successfulHandoffLeavesNativeOwnershipIntact() {
        var closed = 0
        val tunnel = TunnelHandoff({ 42 }, { closed++ })
        tunnel.use { assertEquals(42, it.take()) }
        assertEquals(0, closed)
        assertTrue(runCatching { tunnel.take() }.isFailure)
    }

    @Test fun cancellingCarrierStartupClosesTheUnclaimedTunnel() = runTest {
        var closed = 0
        val tunnel = TunnelHandoff({ error("not handed off") }, { closed++ })
        val job = launch { tunnel.use { awaitCancellation() } }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, closed)
    }

    @Test fun failedDetachStillReleasesItsDescriptor() {
        var closed = 0
        runCatching { TunnelHandoff({ error("detach failed") }, { closed++ }).use { it.take() } }
        assertEquals(1, closed)
    }
}
