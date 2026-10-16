package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.TlsFragmentMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EndpointDiscoveryTest {
    private val settings = AppSettings(transport = TunnelProtocol.AUTO)
    private val hit = EndpointScanResult("162.159.197.3:443", 80)

    @Test fun automaticSelectionStopsCheckingOtherCandidatesOnceOneRouteIsVerified() = runTest {
        val backend = Backend().apply {
            onScan = { _, _ -> Result.success(listOf(hit, hit.copy(peer = "162.159.198.2:443"))) }
            onCheck = { Result.success(hit) }
        }
        EndpointDiscovery(backend).discover(settings, TunnelProtocol.H2, targetVerified = 1) {}
        assertEquals(2, backend.checks.size)
        assertEquals(1, backend.scans.size)
    }

    private class Backend : EndpointDiscoveryBackend {
        val scans = mutableListOf<AppSettings>()
        val checks = mutableListOf<AppSettings>()
        var cancellations = 0
        var onScan: (AppSettings, (NativeScanProgress) -> Unit) -> Result<List<EndpointScanResult>> = { _, _ -> Result.success(emptyList()) }
        var onCheck: (AppSettings) -> Result<EndpointScanResult> = { Result.failure(IllegalStateException("blocked")) }
        override suspend fun scan(settings: AppSettings, budgetMillis: Long, onProgress: (NativeScanProgress) -> Unit): Result<List<EndpointScanResult>> {
            assertTrue(budgetMillis > 0)
            scans += settings
            return onScan(settings, onProgress)
        }
        override suspend fun confirm(settings: AppSettings, budgetMillis: Long): Result<EndpointScanResult> {
            assertTrue(budgetMillis > 0)
            checks += settings
            return onCheck(settings)
        }
        override fun cancel() { cancellations++ }
    }

    @Test fun udpFailureStillDiscoversTcpAndSelectionAppliesTheTestedRecipe() = runTest {
        val backend = Backend().apply {
            onScan = { config, _ ->
                if (config.transport == TunnelProtocol.H3) Result.failure(IllegalStateException("UDP blocked"))
                else Result.success(listOf(hit))
            }
            onCheck = { Result.success(hit) }
        }
        val result = EndpointDiscovery(backend).discover(settings, TunnelProtocol.H3) {}
        assertEquals(listOf(TunnelProtocol.H3, TunnelProtocol.H2), backend.scans.take(2).map { it.transport })
        val selected = result.results.first().applyTo(settings)
        assertEquals(TunnelProtocol.H2, selected.transport)
        assertEquals(TunnelProtocol.H2, selected.customEndpointProtocol)
        assertEquals(EndpointMode.CUSTOM_FIRST, selected.endpointMode)
        assertEquals(2, result.results.first().successfulChecks)
        assertTrue(result.diagnostics.any { "UDP blocked" in it })
    }

    @Test fun progressArrivesBeforeNativeScanReturnsAndKeepsConfirmedSamples() = runTest {
        val updates = mutableListOf<DiscoveryUpdate>()
        val backend = Backend().apply {
            onScan = { _, progress ->
                progress(NativeScanProgress(1, 100, results = listOf(hit)))
                assertTrue(updates.any { it.results.any { candidate -> candidate.peer == hit.peer } })
                Result.success(listOf(hit))
            }
            onCheck = { Result.success(hit) }
        }
        val result = EndpointDiscovery(backend).discover(settings, TunnelProtocol.H2, onUpdate = updates::add)
        assertTrue(updates.any { it.completed == 1 && it.total == 100 })
        assertTrue(result.results.any { it.successfulChecks == 2 })
    }

    @Test fun aHandshakeWithoutTrafficIsNotMarkedInternetVerifiedAndTriggersDeepening() = runTest {
        val backend = Backend().apply { onScan = { _, _ -> Result.success(listOf(hit)) } }
        val result = EndpointDiscovery(backend).discover(settings, TunnelProtocol.H2) {}
        assertTrue(backend.scans.size > EndpointDiscovery.profiles(settings, TunnelProtocol.H2).size)
        assertTrue(result.results.all { it.successfulChecks == 0 })
        assertTrue(result.results.any { it.attemptedChecks == 2 })
    }

    @Test fun cancellationRetainsFreshPartialResultsAndStartsNoMorePasses() = runTest {
        val backend = Backend()
        val discovery = EndpointDiscovery(backend)
        backend.onScan = { _, progress ->
            progress(NativeScanProgress(1, 100, results = listOf(hit)))
            discovery.cancel()
            Result.failure(IllegalStateException("cancelled"))
        }
        val result = discovery.discover(settings, TunnelProtocol.H2) {}
        assertEquals(DiscoveryStage.CANCELLED, result.stage)
        assertEquals(1, backend.scans.size)
        assertEquals(0, backend.checks.size)
        assertEquals(1, result.results.size)
    }

    @Test fun networkChangesCancelNativeWorkAndDiscardResults() = runTest {
        var sameNetwork = true
        val backend = Backend().apply {
            onScan = { _, progress ->
                sameNetwork = false
                progress(NativeScanProgress(1, 100, results = listOf(hit)))
                Result.success(listOf(hit))
            }
        }
        val result = EndpointDiscovery(backend, { sameNetwork }).discover(settings, TunnelProtocol.H2) {}
        assertEquals(DiscoveryStage.NETWORK_CHANGED, result.stage)
        assertTrue(result.results.isEmpty())
        assertTrue(backend.cancellations > 0)
        assertEquals(1, backend.scans.size)
    }

    @Test fun cachedHintsAreRecheckedAndFailedOnesAreNotPublishedAsFresh() = runTest {
        val cached = hit.copy(peer = "162.159.197.99:443", profile = EndpointProbeProfile.from(settings.copy(transport = TunnelProtocol.H2)), successfulChecks = 2)
        val backend = Backend()
        val result = EndpointDiscovery(backend).discover(settings, TunnelProtocol.H2, listOf(cached)) {}
        assertEquals(2, backend.checks.size)
        assertTrue(result.results.isEmpty())
    }

    @Test fun wireguardAndNestedProfilesNeverBorrowMasqueEndpoints() = runTest {
        for (protocol in listOf(TunnelProtocol.WIREGUARD, TunnelProtocol.WARP_IN_WARP, TunnelProtocol.WIREGUARD_OVER_MASQUE, TunnelProtocol.MASQUE_IN_MASQUE)) {
            val backend = Backend()
            EndpointDiscovery(backend).discover(settings.copy(transport = protocol), TunnelProtocol.H2) {}
            assertTrue(backend.scans.all { it.transport == protocol })
        }
    }

    @Test fun rankingPrefersRepeatedTrafficSuccessOverOneFastHandshake() {
        val fast = hit.copy(rttMillis = 1)
        val reliable = hit.copy(peer = "162.159.197.4:443", rttMillis = 500, successfulChecks = 2, attemptedChecks = 2)
        assertEquals(reliable, EndpointDiscovery.rank(listOf(fast, reliable)).first())
    }

    @Test fun tlsRecoverySettingsFollowTheSelectedResult() {
        val recipe = EndpointProbeProfile(TunnelProtocol.H2, true, true, TlsFragmentMode.RECORDS)
        val applied = hit.copy(profile = recipe).applyTo(settings)
        assertTrue(applied.fragmentTls)
        assertTrue(applied.encryptedHello)
        assertEquals(TlsFragmentMode.RECORDS, applied.tlsOptions.fragmentMode)
    }

    @Test fun editsMadeDuringDiscoveryCannotReplaceTheTestedHandshakeRecipe() {
        val original = settings.copy(transport = TunnelProtocol.H2, tlsGroups = "X25519", noizeProfile = "off", dualStack = true)
        val result = hit.copy(profile = EndpointProbeProfile.from(original))
        val edited = settings.copy(tlsGroups = "P-256", noizeProfile = "firewall", dualStack = false)
        val selected = result.applyTo(edited)
        assertEquals("X25519", selected.tlsGroups)
        assertEquals("off", selected.noizeProfile)
        assertTrue(selected.dualStack)
    }

    @Test fun cancellationExceptionsArePropagated() = runTest {
        val backend = Backend().apply { onScan = { _, _ -> Result.failure(CancellationException("view closed")) } }
        try {
            EndpointDiscovery(backend).discover(settings, TunnelProtocol.H2) {}
            fail("cancellation was swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun expiredTimeBudgetDoesNotStartMoreNativeCalls() = runTest {
        var elapsed = 0L
        val backend = Backend().apply {
            onScan = { _, _ -> elapsed = 220_000; Result.success(emptyList()) }
        }
        EndpointDiscovery(backend, nowMillis = { elapsed }).discover(settings, TunnelProtocol.H2) {}
        assertEquals(1, backend.scans.size)
    }

    @Test fun manyFastUnusableHitsCannotStarveASlowerRecoveryProfile() = runTest {
        val backend = Backend().apply {
            onScan = { config, _ -> Result.success((1..12).map {
                hit.copy(peer = "162.159.197.$it:443", rttMillis = if (config.encryptedHello) 500 else 1)
            }) }
            onCheck = { config ->
                if (config.encryptedHello && config.transport == TunnelProtocol.H3) Result.success(hit)
                else Result.failure(IllegalStateException("no traffic"))
            }
        }
        val result = EndpointDiscovery(backend).discover(settings, TunnelProtocol.H2) {}
        assertTrue(backend.checks.any { it.encryptedHello && it.transport == TunnelProtocol.H3 })
        assertEquals(TunnelProtocol.H3, result.results.first().profile?.transport)
        assertEquals(2, result.results.first().successfulChecks)
    }
}
