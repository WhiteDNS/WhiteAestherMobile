package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.WorkerDeployment
import com.whitedns.whiteaesther.data.WorkerMode
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** BPB's Worker variants, rendered for its pinned Xray core rather than mihomo. */
internal object WorkerConfig {
    private val DNS_SERVERS = listOf("https+local://1.1.1.1/dns-query", "https+local://8.8.8.8/dns-query", "localhost")

    fun render(deployment: WorkerDeployment, socksPort: Int): String {
        val options = deployment.options
        options.validate()
        require(socksPort in 1..65535)
        val domain = deployment.connectionHost
        require(deployment.ready && com.whitedns.whiteaesther.data.WorkerOptions.host(domain)) { "Select a deployed Worker first" }
        val browser = options.mode == WorkerMode.BROWSER
        val fragment = options.mode == WorkerMode.FRAGMENT
        val candidates = mutableListOf<Pair<String, Int>>()
        if (browser) candidates += domain to 443 else {
            val addresses = (listOf(domain) + options.cleanAddresses + if (options.customCdn && !fragment) options.cdnAddresses else emptyList()).distinct()
            options.ports.forEach { port -> addresses.forEach { candidates += it to port } }
            if (!fragment && options.upstreamProxy.isNotBlank()) URI("https://${options.upstreamProxy}").let { candidates += it.host.removeSurrounding("[", "]") to it.port }
        }
        val endpoints = candidates.distinct()
        // Before the first latency probe, start on a clean address: networks that block the
        // Worker's own hostname in DNS would otherwise leave every request on a dead node.
        val fallback = "worker-node-${endpoints.indexOfFirst { it.first != domain }.coerceAtLeast(0) + 1}"
        val outbounds = JSONArray()
        endpoints.forEachIndexed { index, (address, port) ->
            val custom = !browser && !fragment && options.customCdn && address in options.cdnAddresses
            val host = if (custom) options.cdnHost else domain
            val tls = JSONObject().put("serverName", if (custom) options.cdnSni else domain).put("alpn", JSONArray(listOf("http/1.1")))
            if (!browser) {
                tls.put("fingerprint", options.fingerprint)
                if (options.ech && !fragment) tls.put("echConfigList", "cloudflare-ech.com+${options.echDns}").put("echSockopt", JSONObject().put("dialerProxy", "worker-direct"))
            }
            val stream = JSONObject().put("network", "ws").put("security", "tls").put("tlsSettings", tls)
                .put("wsSettings", JSONObject().put("host", host).put("path", "/?ed=2560"))
                .put("sockopt", JSONObject().put("tcpFastOpen", options.tcpFastOpen).put("domainStrategy", "UseIP"))
            if (fragment) stream.put("finalmask", JSONObject().put("tcp", JSONArray().apply { options.fragments.forEach { put(it.mask()) } }))
            outbounds.put(JSONObject().put("tag", "worker-node-${index + 1}").put("protocol", "vless")
                .put("settings", JSONObject().put("address", address.removeSurrounding("[", "]")).put("port", port).put("id", deployment.uuid).put("encryption", "none"))
                .put("streamSettings", stream))
        }
        outbounds.put(JSONObject().put("tag", "worker-direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "worker-block").put("protocol", "blackhole"))
        val route = JSONObject().put("type", "field").put("inboundTag", JSONArray(listOf("worker-in")))
        if (browser) route.put("outboundTag", "worker-node-1") else route.put("balancerTag", "worker-best")
        val routing = JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()
            .put(JSONObject().put("type", "field").put("network", "udp").put("outboundTag", "worker-block"))
            .put(route))
        if (!browser) routing.put("balancers", JSONArray().put(JSONObject().put("tag", "worker-best")
            .put("selector", JSONArray(listOf("worker-node-"))).put("fallbackTag", fallback)
            .put("strategy", JSONObject().put("type", "leastPing"))))
        val config = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            // Resolve the Worker hostname over DoH first; operator DNS may answer it with NXDOMAIN.
            .put("dns", JSONObject().put("servers", JSONArray(DNS_SERVERS)))
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "worker-in").put("listen", "127.0.0.1").put("port", socksPort)
                .put("protocol", "socks").put("settings", JSONObject().put("auth", "noauth").put("udp", false))))
            .put("outbounds", outbounds).put("routing", routing)
        if (!browser) config.put("observatory", JSONObject().put("subjectSelector", JSONArray(listOf("worker-node-")))
            .put("probeURL", "https://www.gstatic.com/generate_204").put("probeInterval", "${options.probeInterval}s").put("enableConcurrency", true))
        return config.toString()
    }
}
