package com.whitedns.whiteaesther.data

import org.junit.Assert.*
import org.junit.Test

class StandaloneConnectionTest {
    @Test fun proxyProfilesNeverUseTheAetherTunnelOrTheManagedWorkerProvider() {
        val settings = AppSettings(automaticCarrier = false, carrier = Carrier.PROXY,
            chain = ChainSettings(enabled = false, throughTunnel = true, manual = "trojan://password@example.com:443#test", worker = "old worker"))
        val chain = settings.chainForService()
        assertTrue(chain.enabled)
        assertFalse(chain.throughTunnel)
        assertEquals("", chain.worker)
        assertEquals(settings.chain.manual, chain.manual)
        assertNull(settings.standaloneError())
        assertNotNull(settings.copy(chain = ChainSettings()).standaloneError())
    }
    @Test fun workerHasItsOwnIdentityAndDnsWithoutSelectingAether() {
        val deployment = WorkerDeployment("a".repeat(32), "whiteaesther-test", false, host = "test.example.workers.dev", ready = true)
        val configured = deployment.configure(AppSettings())
        assertEquals(Carrier.WORKER, configured.carrier)
        assertFalse(configured.carrier.usesEngine)
        assertFalse(configured.carrier.carriesUdp)
        assertFalse(configured.automaticCarrier)
        assertEquals(deployment.options.remoteDns, configured.chainForService().remoteDns)
        assertNull(configured.standaloneError())
        assertNotNull(configured.copy(worker = null).standaloneError())
    }
}
