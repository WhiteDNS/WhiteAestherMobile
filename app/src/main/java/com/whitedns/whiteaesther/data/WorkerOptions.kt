package com.whitedns.whiteaesther.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class WorkerMode(@androidx.annotation.StringRes val label: Int) {
    NORMAL(com.whitedns.whiteaesther.R.string.worker_normal),
    FRAGMENT(com.whitedns.whiteaesther.R.string.worker_fragment),
    BROWSER(com.whitedns.whiteaesther.R.string.worker_browser),
}

data class WorkerFragment(
    val packets: String = "tlshello",
    val lengths: String = "100-200",
    val delays: String = "1",
    val maxSplit: String = "",
) {
    fun validate() {
        require(packets == "tlshello" || packets.matches(Regex("[1-9][0-9]{0,2}-[1-9][0-9]{0,2}"))) { "Invalid fragment packets" }
        if (packets != "tlshello") ranges(packets, 1, 255)
        ranges(lengths, 1, 65535)
        ranges(delays, 0, 1000)
        if (maxSplit.isNotBlank()) require(ranges(maxSplit, 1, 65535).length() == 1) { "Use one maximum-split range" }
    }

    fun toJson() = JSONObject().put("packets", packets).put("lengths", lengths).put("delays", delays).put("maxSplit", maxSplit)
    fun mask() = JSONObject().put("type", "fragment").put("settings", JSONObject()
        .put("packets", packets).put("lengths", ranges(lengths, 1, 65535)).put("delays", ranges(delays, 0, 1000))
        .apply { if (maxSplit.isNotBlank()) put("maxSplit", ranges(maxSplit, 1, 65535).getString(0)) })

    companion object {
        fun fromJson(j: JSONObject) = WorkerFragment(j.optString("packets", "tlshello"), j.optString("lengths", "100-200"), j.optString("delays", "1"), j.optString("maxSplit"))
        private fun ranges(value: String, minimum: Int, maximum: Int): JSONArray {
            val values = value.split(',').map(String::trim)
            require(values.size in 1..16) { "Use between 1 and 16 fragment ranges" }
            values.forEach {
                require(it.matches(Regex("[0-9]{1,5}(?:-[0-9]{1,5})?"))) { "Use fragment ranges such as 100-200" }
                val parts = it.split('-').map(String::toInt)
                require(parts.first() in minimum..maximum && parts.last() in parts.first()..maximum) { "Fragment range is out of bounds" }
            }
            return JSONArray(values)
        }
    }
}

data class WorkerOptions(
    val mode: WorkerMode = WorkerMode.NORMAL,
    val cleanAddresses: List<String> = listOf("104.17.148.22", "104.17.147.22"),
    val ports: List<Int> = listOf(443),
    val fingerprint: String = "chrome",
    val remoteDns: String = "https://8.8.8.8/dns-query",
    val ech: Boolean = false,
    val echDns: String = "https://8.8.8.8/dns-query",
    val customCdn: Boolean = false,
    val cdnAddresses: List<String> = emptyList(),
    val cdnHost: String = "",
    val cdnSni: String = "",
    val fragments: List<WorkerFragment> = listOf(WorkerFragment()),
    val upstreamProxy: String = "",
    val proxyIps: List<String> = emptyList(),
    val fallbackUrl: String = "",
    val tcpFastOpen: Boolean = false,
    val probeInterval: Int = 60,
) {
    fun validate() {
        require(cleanAddresses.size <= 32 && cdnAddresses.size <= 32 && proxyIps.size <= 16) { "Too many Worker addresses" }
        (cleanAddresses + cdnAddresses).forEach { require(host(it)) { "Invalid Worker address: $it" } }
        require(ports.isNotEmpty() && ports.size <= TLS_PORTS.size && ports.distinct().size == ports.size && ports.all { it in TLS_PORTS }) { "Select Cloudflare TLS ports: ${TLS_PORTS.joinToString()}" }
        require(fingerprint in FINGERPRINTS) { "Unsupported TLS fingerprint" }
        require(dns(remoteDns) && dns(echDns)) { "Use a public HTTPS or TCP DNS address" }
        require(!URI(remoteDns).host.orEmpty().lowercase().let { it in CLOUDFLARE_DNS }) { "Workers cannot reach Cloudflare DNS; use another provider" }
        if (customCdn) require(cdnAddresses.isNotEmpty() && host(cdnHost) && host(cdnSni)) { "Custom CDN requires addresses, Host and SNI" }
        require(fragments.size in 1..8) { "Use between 1 and 8 fragment entries" }
        fragments.forEach(WorkerFragment::validate)
        require(upstreamProxy.isBlank() || (WorkerDeployment.validRelay(upstreamProxy) && URI("https://$upstreamProxy").port in 1..65535)) { "Use upstream host:port" }
        proxyIps.forEach { require(it.isNotBlank() && WorkerDeployment.validRelay(it)) { "Invalid proxy IP address" } }
        require(fallbackUrl.isBlank() || (runCatching { URI(fallbackUrl).let { it.scheme == "https" && host(it.host.orEmpty()) && it.userInfo == null && it.fragment == null } }.getOrDefault(false))) { "Use an HTTPS fallback website" }
        require(probeInterval in 10..300) { "Probe interval must be between 10 and 300 seconds" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name).put("cleanAddresses", JSONArray(cleanAddresses)).put("ports", JSONArray(ports))
        .put("fingerprint", fingerprint).put("remoteDns", remoteDns).put("ech", ech).put("echDns", echDns)
        .put("customCdn", customCdn).put("cdnAddresses", JSONArray(cdnAddresses)).put("cdnHost", cdnHost).put("cdnSni", cdnSni)
        .put("fragments", JSONArray().apply { fragments.forEach { put(it.toJson()) } })
        .put("upstreamProxy", upstreamProxy).put("proxyIps", JSONArray(proxyIps)).put("fallbackUrl", fallbackUrl)
        .put("tcpFastOpen", tcpFastOpen).put("probeInterval", probeInterval)

    companion object {
        val TLS_PORTS = listOf(443, 8443, 2053, 2083, 2087, 2096)
        val FINGERPRINTS = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized")
        private val CLOUDFLARE_DNS = setOf("1.1.1.1", "1.0.0.1", "cloudflare-dns.com", "one.one.one.one", "security.cloudflare-dns.com", "family.cloudflare-dns.com")
        fun host(value: String): Boolean = value.isNotBlank() && value.length <= 253 && !value.any { it.isWhitespace() || it.isISOControl() } &&
            runCatching { URI("https://${if (':' in value && !value.startsWith('[')) "[$value]" else value}").let { it.host != null && it.port == -1 && it.userInfo == null && it.rawPath.isNullOrEmpty() && it.query == null && it.fragment == null } }.getOrDefault(false)
        private fun dns(value: String): Boolean = runCatching { URI(value).let { it.scheme in setOf("https", "tcp") && host(it.host.orEmpty()) && it.userInfo == null && it.fragment == null && it.port in -1..65535 && it.port != 0 } }.getOrDefault(false)
        private fun strings(j: JSONObject, key: String, default: List<String> = emptyList()): List<String> = j.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: default
        fun fromJson(j: JSONObject): WorkerOptions {
            val defaults = WorkerOptions()
            return WorkerOptions(
                mode = WorkerMode.valueOf(j.optString("mode", defaults.mode.name)),
                cleanAddresses = strings(j, "cleanAddresses", defaults.cleanAddresses),
                ports = j.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: defaults.ports,
                fingerprint = j.optString("fingerprint", defaults.fingerprint), remoteDns = j.optString("remoteDns", defaults.remoteDns),
                ech = j.optBoolean("ech"), echDns = j.optString("echDns", defaults.echDns),
                customCdn = j.optBoolean("customCdn"), cdnAddresses = strings(j, "cdnAddresses"), cdnHost = j.optString("cdnHost"), cdnSni = j.optString("cdnSni"),
                fragments = j.optJSONArray("fragments")?.let { a -> (0 until a.length()).map { WorkerFragment.fromJson(a.getJSONObject(it)) } } ?: defaults.fragments,
                upstreamProxy = j.optString("upstreamProxy"), proxyIps = strings(j, "proxyIps"), fallbackUrl = j.optString("fallbackUrl"),
                tcpFastOpen = j.optBoolean("tcpFastOpen"), probeInterval = j.optInt("probeInterval", defaults.probeInterval),
            ).also { it.validate() }
        }
    }
}
