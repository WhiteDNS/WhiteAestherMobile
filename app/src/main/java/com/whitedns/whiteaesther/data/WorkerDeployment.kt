package com.whitedns.whiteaesther.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

data class WorkerDeployment(
    val accountId: String,
    val name: String,
    val pages: Boolean,
    val uuid: String = UUID.randomUUID().toString(),
    val host: String = "",
    val relay: String = "",
    val created: Boolean = false,
    val ready: Boolean = false,
    val options: WorkerOptions = WorkerOptions(),
    val customDomain: String = "",
    val version: Int = 1,
    val domainPending: Boolean = false,
    val dnsZone: String = "",
    val dnsRecord: String = "",
) {
    init {
        require(accountId.matches(Regex("[a-fA-F0-9]{32}"))) { "Invalid Cloudflare account" }
        require(name.matches(Regex("whiteaesther-[a-z0-9-]{1,40}"))) { "Invalid deployment name" }
        require(UUID.fromString(uuid).toString() == uuid) { "Invalid connection identifier" }
        require(validRelay(relay)) { "Invalid relay address" }
        require(customDomain.isBlank() || WorkerOptions.host(customDomain) && ':' !in customDomain) { "Invalid custom domain" }
        options.validate()
        require(listOf(dnsZone, dnsRecord).all { it.isBlank() || it.matches(Regex("[a-fA-F0-9]{32}")) }) { "Invalid saved DNS record" }
    }

    val nodeName: String get() = "WhiteAesther ${if (pages) "Pages" else "Worker"} - $name"
    val key: String get() = "$accountId/${if (pages) "pages" else "workers"}/$name"
    val connectionHost: String get() = if (domainPending) host else customDomain.ifBlank { host }

    private fun checkHost() {
        require(host.length <= 253 && host.split('.').size >= 2 &&
            host.split('.').all { it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) } &&
            host.substringAfterLast('.').any(Char::isLetter)) { "The deployment has no valid public hostname yet" }
    }

    fun shareLink(): String {
        checkHost()
        return "vless://$uuid@$host:443?encryption=none&security=tls&type=ws&host=$host&sni=$host&path=%2F" +
            "#${URLEncoder.encode(nodeName, "UTF-8").replace("+", "%20")}"
    }

    /** JSON is also YAML; this avoids interpolating credentials into YAML syntax. */
    fun proxyDocument(): String {
        checkHost()
        return JSONObject().put("proxies", JSONArray().put(JSONObject()
            .put("name", nodeName).put("type", "vless").put("server", host).put("port", 443)
            .put("uuid", uuid).put("tls", true).put("servername", host).put("client-fingerprint", "chrome")
            .put("network", "ws").put("udp", false).put("xudp", false)
            .put("ws-opts", JSONObject().put("path", "/").put("headers", JSONObject().put("Host", host)))))
            .toString()
    }

    fun configure(settings: AppSettings): AppSettings = settings.copy(
        mode = EngineMode.TUN, carrier = Carrier.WORKER, secondCarrier = null, automaticCarrier = false, worker = this,
    )

    fun environment(): String = Base64.getEncoder().encodeToString(
        JSONObject().put("v", 1).put("id", uuid)
            .put("pips", JSONArray((options.proxyIps + listOf(relay).filter(String::isNotBlank)).distinct())).put("fb", options.fallbackUrl)
            .toString().toByteArray(Charsets.UTF_8),
    )

    fun toJson(): JSONObject = JSONObject().put("accountId", accountId).put("name", name)
        .put("pages", pages).put("uuid", uuid).put("host", host).put("relay", relay)
        .put("created", created).put("ready", ready)
        .put("options", options.toJson()).put("customDomain", customDomain).put("version", version)
        .put("domainPending", domainPending).put("dnsZone", dnsZone).put("dnsRecord", dnsRecord)

    companion object {
        internal fun saved(context: android.content.Context): List<WorkerDeployment> {
            val preferences = context.getSharedPreferences("worker_deployments", android.content.Context.MODE_PRIVATE)
            val rows = JSONArray(preferences.getString("deployments", "[]"))
            return (0 until rows.length()).map { fromJson(rows.getJSONObject(it)) }
        }

        internal fun pickReady(saved: List<WorkerDeployment>, selectedKey: String?): WorkerDeployment? {
            val ready = saved.filter { it.ready }
            return ready.firstOrNull { it.key == selectedKey } ?: ready.singleOrNull()
        }

        fun fromJson(json: JSONObject) = WorkerDeployment(
            accountId = json.getString("accountId"), name = json.getString("name"), pages = json.getBoolean("pages"),
            uuid = json.getString("uuid"), host = json.optString("host"), relay = json.optString("relay"),
            created = json.optBoolean("created"), ready = json.optBoolean("ready"),
            options = json.optJSONObject("options")?.let(WorkerOptions::fromJson) ?: WorkerOptions(),
            customDomain = json.optString("customDomain"), version = json.optInt("version", 1),
            domainPending = json.optBoolean("domainPending"), dnsZone = json.optString("dnsZone"), dnsRecord = json.optString("dnsRecord"),
        )

        fun validRelay(value: String): Boolean {
            if (value.isBlank()) return true
            if (value.length > 253 || value.any { it.isWhitespace() || it.isISOControl() }) return false
            val uri = runCatching { java.net.URI("https://$value") }.getOrNull() ?: return false
            return !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.rawPath.isNullOrEmpty() &&
                uri.query == null && uri.fragment == null && uri.port in -1..65535 && uri.port != 0
        }
    }
}
