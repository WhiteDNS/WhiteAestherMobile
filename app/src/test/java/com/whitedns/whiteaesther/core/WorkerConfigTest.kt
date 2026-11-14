package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkerConfigTest {
    private val auth = SocksAuth("u".repeat(32), "p".repeat(32))
    private val deployment = WorkerDeployment("a".repeat(32), "whiteaesther-test", false, host = "test.example.workers.dev", ready = true)
    @Test fun eachVariantProducesAnActualEngineConfiguration() {
        WorkerMode.entries.forEach { mode ->
            val options = WorkerOptions(mode = mode, ports = listOf(443, 8443), ech = true, upstreamProxy = "relay.example.com:443")
            val text = WorkerConfig.render(deployment.copy(options = options), 19876, auth)
            val config = JSONObject(text)
            val outbounds = config.getJSONArray("outbounds")
            val stream = outbounds.getJSONObject(0).getJSONObject("streamSettings")
            assertEquals(mode == WorkerMode.FRAGMENT, stream.has("finalmask"))
            assertEquals(mode == WorkerMode.NORMAL, stream.getJSONObject("tlsSettings").has("echConfigList"))
            assertEquals(mode != WorkerMode.BROWSER, config.has("observatory"))
            if (mode != WorkerMode.BROWSER) {
                val selector = config.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getJSONArray("selector").getString(0)
                (0 until outbounds.length()).forEach { index ->
                    val outbound = outbounds.getJSONObject(index)
                    if (outbound.getString("tag").startsWith(selector)) assertEquals("vless", outbound.getString("protocol"))
                }
            }
            assertEquals("127.0.0.1", config.getJSONArray("inbounds").getJSONObject(0).getString("listen"))
            assertFalse(config.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getBoolean("udp"))
            assertEquals(if (mode == WorkerMode.BROWSER) 3 else if (mode == WorkerMode.NORMAL) 9 else 8, outbounds.length())
            File("build/worker-config-test").apply { mkdirs() }.resolve("${mode.name.lowercase()}.json").writeText(text)
        }
    }
    @Test fun startsOnACleanAddressAndResolvesTheWorkerOverDoh() {
        fun fallbackAddress(options: WorkerOptions): String {
            val config = JSONObject(WorkerConfig.render(deployment.copy(options = options), 19876, auth))
            assertTrue(config.getJSONObject("dns").getJSONArray("servers").getString(0).startsWith("https+local://"))
            val tag = config.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getString("fallbackTag")
            val outbounds = config.getJSONArray("outbounds")
            return (0 until outbounds.length()).map(outbounds::getJSONObject).single { it.getString("tag") == tag }
                .getJSONObject("settings").getString("address")
        }
        assertEquals("104.17.148.22", fallbackAddress(WorkerOptions()))
        assertEquals("104.17.148.22", fallbackAddress(WorkerOptions(mode = WorkerMode.FRAGMENT)))
        assertEquals("test.example.workers.dev", fallbackAddress(WorkerOptions(cleanAddresses = emptyList())))
    }
    @Test fun theListenerAsksForThisSessionsPassword() {
        val settings = JSONObject(WorkerConfig.render(deployment, 19876, auth))
            .getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertEquals("password", settings.getString("auth"))
        val account = settings.getJSONArray("accounts").getJSONObject(0)
        assertEquals(auth.user, account.getString("user"))
        assertEquals(auth.password, account.getString("pass"))
    }
    @Test fun everySessionGetsItsOwnCredentials() {
        val first = SocksAuth.random()
        val second = SocksAuth.random()
        assertEquals(32, first.user.length)
        assertNotEquals(first.user, second.user)
        assertNotEquals(first.password, second.password)
        assertFalse(first.toString().contains(first.password))
    }
    @Test fun cdnChangesOnlyItsOwnCandidatesAndUsesTheAttachedDomain() {
        val options = WorkerOptions(customCdn = true, cdnAddresses = listOf("cdn.example.net"), cdnHost = "front.example.net", cdnSni = "tls.example.net")
        val config = JSONObject(WorkerConfig.render(deployment.copy(customDomain = "vpn.example.org", options = options), 19876, auth))
        val outbounds = config.getJSONArray("outbounds")
        assertEquals("vpn.example.org", outbounds.getJSONObject(0).getJSONObject("settings").getString("address"))
        val custom = outbounds.getJSONObject(3).getJSONObject("streamSettings")
        assertEquals("front.example.net", custom.getJSONObject("wsSettings").getString("host"))
        assertEquals("tls.example.net", custom.getJSONObject("tlsSettings").getString("serverName"))
    }
}
