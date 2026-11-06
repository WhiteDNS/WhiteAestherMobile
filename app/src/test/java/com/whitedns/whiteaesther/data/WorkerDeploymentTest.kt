package com.whitedns.whiteaesther.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class WorkerDeploymentTest {
    @Test
    fun dashboardUsesTheSelectedOrOnlyReadyDeployment() {
        val first = deployment.copy(ready = true)
        val second = first.copy(name = "whiteaesther-other")
        assertEquals(first, WorkerDeployment.pickReady(listOf(first, second.copy(ready = false)), null))
        assertEquals(second, WorkerDeployment.pickReady(listOf(first, second), second.key))
        assertNull(WorkerDeployment.pickReady(listOf(first, second), null))
        assertNull(WorkerDeployment.pickReady(listOf(first.copy(ready = false)), first.key))
    }

    private val deployment = WorkerDeployment(
        accountId = "a".repeat(32), name = "whiteaesther-test", pages = false,
        uuid = "12345678-1234-4234-8234-123456789abc", host = "whiteaesther-test.example.workers.dev",
    )

    @Test
    fun connectionUsesVerifiedTlsAndTheWorkerHostForWebSocketRouting() {
        val uri = URI(deployment.shareLink())
        assertEquals("vless", uri.scheme)
        assertEquals(deployment.host, uri.host)
        assertEquals(443, uri.port)
        assertTrue(uri.query.contains("security=tls"))
        assertTrue(uri.query.contains("type=ws"))
        assertTrue(uri.query.contains("host=${deployment.host}"))
        assertTrue(uri.query.contains("sni=${deployment.host}"))
        assertFalse(uri.query.contains("allowInsecure"))
    }

    @Test
    fun configuringWorkerKeepsExistingSourcesNodesIdentityOptionsAndRules() {
        val existing = AppSettings(
            chain = ChainSettings(
                enabled = true,
                manual = "vless://old#Old", sources = listOf(ChainSource("Existing", "https://example.com/sub")),
            ),
            routeDirect = "private", routeBlock = "bad.example", dnsServers = "1.1.1.1",
        )
        val configured = deployment.configure(existing)
        assertEquals(existing.chain.sources, configured.chain.sources)
        assertEquals(existing.chain.manual, configured.chain.manual)
        val proxy = org.json.JSONObject(deployment.proxyDocument()).getJSONArray("proxies").getJSONObject(0)
        assertEquals(deployment.host, proxy.getString("server"))
        assertFalse(proxy.getBoolean("udp"))
        assertFalse(proxy.getBoolean("xudp"))
        assertEquals(existing.routeDirect, configured.routeDirect)
        assertEquals(existing.routeBlock, configured.routeBlock)
        assertEquals(existing.dnsServers, configured.dnsServers)
        assertEquals(existing.chain, configured.chain)
        assertFalse(configured.chainForService().enabled)
        assertTrue(configured.copy(carrier = Carrier.AETHER).chainForService().enabled)
        assertEquals(existing.chain.throughTunnel, configured.chain.throughTunnel)
        assertFalse(configured.automaticCarrier)
        assertEquals(Carrier.WORKER, configured.carrier)
        assertEquals(deployment, configured.worker)
        assertNull(configured.secondCarrier)
        assertEquals(existing.chain.node, configured.chain.node)
        assertEquals(configured, deployment.configure(configured))
    }

    @Test
    fun storedDeploymentRoundTripsWithoutAnyApiToken() {
        val json = deployment.toJson()
        assertEquals(deployment, WorkerDeployment.fromJson(json))
        assertFalse(json.toString().contains("token", ignoreCase = true))
    }

    @Test
    fun incompleteOrInvalidDeploymentsCannotBecomeConnectionLinks() {
        for (host in listOf("", "127.0.0.1", "host\n.example.com", "evil.example/path")) {
            assertThrows(IllegalArgumentException::class.java) { deployment.copy(host = host).shareLink() }
        }
    }
}
