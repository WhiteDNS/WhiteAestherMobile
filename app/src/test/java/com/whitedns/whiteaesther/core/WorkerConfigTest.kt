package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkerConfigTest {
    private val deployment = WorkerDeployment("a".repeat(32), "whiteaesther-test", false, host = "test.example.workers.dev", ready = true)
    @Test fun eachVariantProducesAnActualEngineConfiguration() {
        WorkerMode.entries.forEach { mode ->
            val options = WorkerOptions(mode = mode, ports = listOf(443, 8443), ech = true, upstreamProxy = "relay.example.com:443")
            val text = WorkerConfig.render(deployment.copy(options = options), 19876)
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
    @Test fun cdnChangesOnlyItsOwnCandidatesAndUsesTheAttachedDomain() {
        val options = WorkerOptions(customCdn = true, cdnAddresses = listOf("cdn.example.net"), cdnHost = "front.example.net", cdnSni = "tls.example.net")
        val config = JSONObject(WorkerConfig.render(deployment.copy(customDomain = "vpn.example.org", options = options), 19876))
        val outbounds = config.getJSONArray("outbounds")
        assertEquals("vpn.example.org", outbounds.getJSONObject(0).getJSONObject("settings").getString("address"))
        val custom = outbounds.getJSONObject(3).getJSONObject("streamSettings")
        assertEquals("front.example.net", custom.getJSONObject("wsSettings").getString("host"))
        assertEquals("tls.example.net", custom.getJSONObject("tlsSettings").getString("serverName"))
    }
}
