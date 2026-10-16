package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutomaticEndpointPreparationTest {
    private val base = """{"transport":"h3","scanMode":"balanced","mode":"proxy","configPath":"private.toml","upstreamProxy":"socks5://127.0.0.1:1234","listenPort":1819,"tlsCiphers":"cipher-policy","fragmentSni":false} """
    private val hit = EndpointScanResult("162.159.198.2:443", 30)
    private class Backend : EndpointDiscoveryBackend {
        val tried = mutableListOf<TunnelProtocol>()
        override suspend fun scan(settings: AppSettings, budgetMillis: Long, onProgress: (NativeScanProgress) -> Unit): Result<List<EndpointScanResult>> {
            tried += settings.transport
            return if (settings.transport == TunnelProtocol.H2) Result.success(listOf(EndpointScanResult("162.159.198.2:443", 30)))
                else Result.failure(IllegalStateException("UDP blocked"))
        }
        override suspend fun confirm(settings: AppSettings, budgetMillis: Long) = Result.success(EndpointScanResult(settings.customEndpoint, 30))
        override fun cancel() = Unit
    }

    @Test fun automaticConnectUsesTheVerifiedTcpRecipeAndPreservesIdentityAndProxy() = runTest {
        val backend = Backend()
        var registrations = 0
        var prepared: String? = null
        val result = AutomaticEndpointPreparation(backend,
            provision = { registrations++; Result.success(Unit) },
            prepare = { prepared = it; Result.success(PreparedEngine("172.16.0.2", "", hit.peer)) },
        ).run(base, 100_000).getOrThrow()
        assertEquals(1, registrations)
        assertEquals(listOf(TunnelProtocol.H3, TunnelProtocol.H2), backend.tried)
        val json = JSONObject(result.config)
        assertEquals("h2", json.getString("transport"))
        assertEquals(hit.peer, json.getString("peer"))
        assertFalse(json.getBoolean("peerFallback"))
        assertEquals("private.toml", json.getString("configPath"))
        assertEquals("socks5://127.0.0.1:1234", json.getString("upstreamProxy"))
        assertEquals("cipher-policy", json.getString("tlsCiphers"))
        assertFalse(json.getBoolean("fragmentSni"))
        assertEquals(result.config, prepared)
    }

    @Test fun identityFailureDoesNotStartScansOrTryRegisteringPerProfile() = runTest {
        val backend = Backend()
        var attempts = 0
        val result = AutomaticEndpointPreparation(backend,
            provision = { attempts++; Result.failure(IllegalStateException("registration on hold")) },
            prepare = { error("must not prepare") },
        ).run(base, 100_000)
        assertTrue(result.isFailure)
        assertEquals(1, attempts)
        assertTrue(backend.tried.isEmpty())
    }

    @Test fun aChangedNetworkCannotSelectOrPrepareAnOldRoute() = runTest {
        val backend = Backend()
        val result = AutomaticEndpointPreparation(backend,
            provision = { Result.success(Unit) }, prepare = { error("must not prepare") },
            networkUnchanged = { false },
        ).run(base, 100_000)
        assertTrue(result.isFailure)
        assertTrue(backend.tried.isEmpty())
    }

    @Test fun identityCannotSpendTheTimeReservedForOpeningTheTunnel() = runTest {
        var clock = 0L
        val backend = Backend()
        val result = AutomaticEndpointPreparation(backend,
            provision = { clock = 40_000; Result.success(Unit) },
            prepare = { error("must not prepare") }, nowMillis = { clock },
        ).run(base, 45_000)
        assertTrue(result.isFailure)
        assertTrue(backend.tried.isEmpty())
    }

    @Test fun aRememberedEndpointIsRecheckedAndConnectsWithoutAnotherPoolScan() = runTest {
        val backend = Backend()
        val settings = AutomaticEndpointPreparation.settings(base)
        val hint = hit.copy(profile = EndpointProbeProfile.from(settings))
        val result = AutomaticEndpointPreparation(backend,
            provision = { Result.success(Unit) },
            prepare = { Result.success(PreparedEngine("172.16.0.2", "", hit.peer)) },
        ).run(base, 100_000, listOf(hint)).getOrThrow()
        assertTrue(backend.tried.isEmpty())
        assertEquals("h3", JSONObject(result.config).getString("transport"))
    }

    @Test fun explicitIpv6AndFragmentShapeSurviveTheRecipeConversion() {
        val ipv6 = JSONObject(base).put("ipScan", "v6").put("fragmentTls", true)
            .put("helloShape", "compact").toString()
        val result = JSONObject(AutomaticEndpointPreparation.configuration(ipv6, AutomaticEndpointPreparation.settings(ipv6)))
        assertEquals("v6", result.getString("ipScan"))
        assertEquals("compact", result.getString("helloShape"))
        assertEquals("compact", result.getString("fragmentMode"))
    }
}
