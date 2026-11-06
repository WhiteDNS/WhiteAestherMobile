package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.WorkerDeployment
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
import java.util.UUID

data class WorkerAccount(val id: String, val name: String)
internal data class WorkerRequest(val method: String, val path: String, val body: ByteArray? = null, val contentType: String = "application/json")

internal class WorkerApi(
    token: String,
    private val script: String,
    private val exchange: (String, WorkerRequest) -> JSONObject = ::request,
) {
    private val token = token.trim().also {
        require(it.matches(Regex("[A-Za-z0-9_-]{30,4096}"))) { "Paste the API token itself, without quotes or a command" }
    }

    private fun send(method: String, path: String, json: JSONObject? = null): Any? =
        result(exchange(token, WorkerRequest(method, path, json?.toString()?.toByteArray(Charsets.UTF_8))))

    private fun upload(method: String, path: String, fields: Map<String, String>, fileName: String): Any? =
        result(exchange(token, multipart(method, path, fields + (fileName to script), fileName)))

    fun accounts(): List<WorkerAccount> {
        val accounts = mutableListOf<WorkerAccount>()
        var page = 1
        do {
            val response = exchange(token, WorkerRequest("GET", "/accounts?per_page=50&page=$page"))
            val rows = result(response) as? JSONArray ?: throw IOException("Cloudflare did not return accounts")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                require(id.matches(Regex("[a-fA-F0-9]{32}"))) { "Cloudflare returned an invalid account ID" }
                accounts += WorkerAccount(id, row.getString("name"))
            }
            val total = response.optJSONObject("result_info")?.optInt("total_pages", 1) ?: 1
            page++
        } while (page <= total && page <= 100)
        return accounts
    }

    fun deploy(original: WorkerDeployment, save: (WorkerDeployment) -> Unit): WorkerDeployment {
        var deployment = original.copy(ready = false)
        val base = "/accounts/${deployment.accountId}"
        val date = LocalDate.now(ZoneOffset.UTC).toString()
        if (deployment.pages) {
            val production = JSONObject().put("compatibility_date", date).put("env_vars",
                JSONObject().put("ENV", JSONObject().put("type", "plain_text").put("value", deployment.environment())))
            val body = JSONObject().put("deployment_configs", JSONObject().put("production", production))
            val project = if (!deployment.created) {
                send("POST", "$base/pages/projects", body.put("name", deployment.name).put("production_branch", "main"))
            } else {
                send("PATCH", "$base/pages/projects/${deployment.name}", body)
            } as? JSONObject ?: throw IOException("Cloudflare did not return a Pages project")
            deployment = deployment.copy(created = true, host = project.getString("subdomain"))
            save(deployment)
            upload("POST", "$base/pages/projects/${deployment.name}/deployments", mapOf("manifest" to "{}", "branch" to "main"), "_worker.js")
        } else {
            val subdomain = try {
                (send("GET", "$base/workers/subdomain") as? JSONObject)?.optString("subdomain").orEmpty()
            } catch (error: WorkerApiException) {
                if (error.status != 404 && !error.message.orEmpty().contains("10007")) throw error
                ""
            }.ifBlank {
                val registered = send("PUT", "$base/workers/subdomain", JSONObject().put("subdomain", WorkerDeployment.randomName())) as? JSONObject
                registered?.getString("subdomain") ?: throw IOException("Could not register workers.dev hostname")
            }
            val metadata = JSONObject().put("main_module", "worker.js").put("compatibility_date", date)
                .put("bindings", JSONArray().put(JSONObject().put("type", "plain_text").put("name", "ENV").put("text", deployment.environment())))
            upload("PUT", "$base/workers/scripts/${deployment.name}", mapOf("metadata" to metadata.toString()), "worker.js")
            deployment = deployment.copy(created = true, host = "${deployment.name}.$subdomain.workers.dev")
            save(deployment)
            send("POST", "$base/workers/scripts/${deployment.name}/subdomain", JSONObject().put("enabled", true).put("previews_enabled", false))
        }
        deployment.shareLink()
        return deployment.copy(ready = true).also(save)
    }

    fun delete(deployment: WorkerDeployment) {
        if (deployment.customDomain.isNotBlank()) detachDomain(deployment) {}
        val path = "/accounts/${deployment.accountId}/" + if (deployment.pages) {
            "pages/projects/${deployment.name}"
        } else {
            "workers/scripts/${deployment.name}"
        }
        try {
            send("DELETE", path)
        } catch (error: WorkerApiException) {
            if (error.status != 404) throw error
        }
    }

    fun attachDomain(original: WorkerDeployment, hostname: String, save: (WorkerDeployment) -> Unit): WorkerDeployment {
        require(original.ready) { "Finish deploying before attaching a domain" }
        val domain = hostname.trim().lowercase()
        require(domain.split('.').size >= 2 && domain.substringAfterLast('.').all(Char::isLetter)) { "Use a domain such as vpn.example.com" }
        require(original.customDomain.isBlank() || original.customDomain == domain) { "Detach the current domain before choosing another" }
        var deployment = original.copy(customDomain = domain, domainPending = true).also(save)
        val base = "/accounts/${deployment.accountId}"
        if (!deployment.pages) {
            val attached = send("GET", "$base/workers/domains?hostname=${URLEncoder.encode(domain, "UTF-8")}") as? JSONArray ?: throw IOException("Cloudflare did not return Worker domains")
            for (i in 0 until attached.length()) {
                val existing = attached.getJSONObject(i)
                if (existing.optString("hostname") == domain) require(existing.optString("service") == deployment.name) { "This domain belongs to another Worker; use an unused subdomain" }
            }
            var failure: IOException? = null
            for (zone in zoneCandidates(domain)) {
                try {
                    send("PUT", "$base/workers/domains", JSONObject().put("hostname", domain).put("service", deployment.name).put("zone_name", zone))
                    return deployment.copy(domainPending = false).also(save)
                } catch (error: WorkerApiException) {
                    if (error.status == 401 || error.status == 403) throw error
                    failure = error
                }
            }
            throw IOException("The domain must belong to this Cloudflare account: ${failure?.message}")
        }
        var zoneId = deployment.dnsZone
        if (zoneId.isBlank()) {
            for (zone in zoneCandidates(domain)) {
                val zones = send("GET", "/zones?account.id=${deployment.accountId}&name=${URLEncoder.encode(zone, "UTF-8")}") as? JSONArray ?: throw IOException("Cloudflare did not return zones")
                if (zones.length() > 0) { zoneId = zones.getJSONObject(0).getString("id"); break }
            }
            require(zoneId.matches(Regex("[a-fA-F0-9]{32}"))) { "No matching zone found; allow Zone: Read and DNS: Edit for your domain" }
            deployment = deployment.copy(dnsZone = zoneId).also(save)
        }
        val records = send("GET", "/zones/$zoneId/dns_records?name=${URLEncoder.encode(domain, "UTF-8")}") as? JSONArray ?: throw IOException("Cloudflare did not return DNS records")
        var existingCname = false
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            if (record.optString("type") in setOf("A", "AAAA", "CNAME")) {
                require(record.optString("type") == "CNAME" && record.optString("content") == deployment.host) { "This hostname already has a DNS record; use an unused subdomain" }
                existingCname = true
            }
        }
        if (!existingCname) {
            val record = send("POST", "/zones/$zoneId/dns_records", JSONObject().put("type", "CNAME").put("name", domain).put("content", deployment.host).put("proxied", true).put("ttl", 1)) as? JSONObject ?: throw IOException("Cloudflare did not return the new DNS record")
            deployment = deployment.copy(dnsRecord = record.getString("id")).also(save)
        }
        val domains = send("GET", "$base/pages/projects/${deployment.name}/domains") as? JSONArray ?: throw IOException("Cloudflare did not return Pages domains")
        if ((0 until domains.length()).none { domains.getJSONObject(it).optString("name") == domain }) {
            send("POST", "$base/pages/projects/${deployment.name}/domains", JSONObject().put("name", domain))
        }
        return deployment.copy(domainPending = false).also(save)
    }

    fun detachDomain(deployment: WorkerDeployment, save: (WorkerDeployment) -> Unit): WorkerDeployment {
        if (deployment.customDomain.isBlank()) return deployment
        val domain = URLEncoder.encode(deployment.customDomain, "UTF-8")
        val base = "/accounts/${deployment.accountId}"
        if (deployment.pages) {
            gone { send("DELETE", "$base/pages/projects/${deployment.name}/domains/$domain") }
            if (deployment.dnsZone.isNotBlank() && deployment.dnsRecord.isNotBlank()) gone {
                val path = "/zones/${deployment.dnsZone}/dns_records/${deployment.dnsRecord}"
                val record = send("GET", path) as? JSONObject ?: throw IOException("Cloudflare did not return the saved DNS record")
                // A changed record belongs to the operator now; never delete it.
                if (record.optString("name") == deployment.customDomain && record.optString("type") == "CNAME" && record.optString("content") == deployment.host) send("DELETE", path)
            }
        } else {
            val records = send("GET", "$base/workers/domains?hostname=$domain&service=${deployment.name}") as? JSONArray ?: throw IOException("Cloudflare did not return Worker domains")
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                if (record.optString("hostname") == deployment.customDomain && record.optString("service") == deployment.name) {
                    val id = record.getString("id")
                    require(id.matches(Regex("[a-fA-F0-9]{32}"))) { "Cloudflare returned an invalid domain ID" }
                    gone { send("DELETE", "$base/workers/domains/$id") }
                }
            }
        }
        return deployment.copy(customDomain = "", domainPending = false, dnsZone = "", dnsRecord = "").also(save)
    }

    fun usage(deployment: WorkerDeployment): Pair<Long, Long> {
        require(!deployment.pages) { "Worker request analytics do not apply to Pages deployments" }
        val end = Instant.now()
        val query = """query Usage(${'$'}account: String!, ${'$'}script: String!, ${'$'}start: String!, ${'$'}end: String!) {
            viewer { accounts(filter: {accountTag: ${'$'}account}) {
                total: workersInvocationsAdaptive(limit: 100, filter: {datetime_geq: ${'$'}start, datetime_leq: ${'$'}end}) { sum { requests } }
                worker: workersInvocationsAdaptive(limit: 100, filter: {scriptName: ${'$'}script, datetime_geq: ${'$'}start, datetime_leq: ${'$'}end}) { sum { requests } }
            }} }"""
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("account", deployment.accountId).put("script", deployment.name).put("start", end.minusSeconds(86400).toString()).put("end", end.toString()))
        val response = exchange(token, WorkerRequest("POST", "/graphql", body.toString().toByteArray(Charsets.UTF_8)))
        if (response.optJSONArray("errors")?.length()?.let { it > 0 } == true || response.optInt("httpStatus", 200) !in 200..299) throw IOException("Usage query failed; allow Account Analytics: Read")
        val account = response.optJSONObject("data")?.optJSONObject("viewer")?.optJSONArray("accounts")?.optJSONObject(0) ?: throw IOException("No account usage data was returned")
        fun sum(key: String): Long = account.optJSONArray(key)?.let { rows -> (0 until rows.length()).sumOf { rows.getJSONObject(it).getJSONObject("sum").getLong("requests") } } ?: 0
        return sum("total") to sum("worker")
    }

    private fun gone(action: () -> Unit) {
        try { action() } catch (error: WorkerApiException) { if (error.status != 404) throw error }
    }

    private fun zoneCandidates(hostname: String): List<String> = hostname.split('.').let { labels -> (0 until labels.size - 1).map { labels.drop(it).joinToString(".") } }

    companion object {
        // Cloudflare's user-token template uses short permission keys; Pages is "page".
        val tokenTemplateUrl = "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=" +
            URLEncoder.encode(
                """[{"key":"account_settings","type":"read"},{"key":"workers_scripts","type":"edit"},{"key":"page","type":"edit"},{"key":"zone","type":"read"},{"key":"dns","type":"edit"},{"key":"account_analytics","type":"read"}]""",
                "UTF-8",
            ) + "&accountId=*&zoneId=all&name=WhiteAesther"

        private fun result(response: JSONObject): Any? {
            if (!response.optBoolean("success")) {
                val errors = response.optJSONArray("errors")
                val message = (0 until (errors?.length() ?: 0)).joinToString("; ") { i ->
                    errors?.optJSONObject(i)?.let { "${it.optString("message")} (${it.optInt("code")})" }.orEmpty()
                }
                throw WorkerApiException(response.optInt("httpStatus"), message.ifBlank { "Cloudflare rejected the request" }.take(1000))
            }
            return response.opt("result")?.takeUnless { it == JSONObject.NULL }
        }

        internal fun multipart(method: String, path: String, fields: Map<String, String>, fileName: String): WorkerRequest {
            val boundary = "whiteaesther-${UUID.randomUUID()}"
            val body = ByteArrayOutputStream()
            fields.forEach { (name, value) ->
                val file = name == fileName
                val filename = if (file) "; filename=\"$fileName\"" else ""
                val type = if (file) "application/javascript+module" else "application/json"
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"$filename\r\nContent-Type: $type\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
            }
            body.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            return WorkerRequest(method, path, body.toByteArray(), "multipart/form-data; boundary=$boundary")
        }

        private fun request(token: String, request: WorkerRequest): JSONObject {
            require(request.path.startsWith("/accounts") && !request.path.contains(".."))
            val connection = URL("https://api.cloudflare.com/client/v4${request.path}").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 30_000
                connection.readTimeout = 90_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", request.contentType)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = ByteArrayOutputStream()
                stream?.use {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (response.size() + count > 2_000_000) throw IOException("Cloudflare response is too large")
                        response.write(buffer, 0, count)
                    }
                }
                val text = response.toString("UTF-8")
                if (text.isBlank() && status in 200..299) return JSONObject().put("success", true)
                val json = runCatching { JSONObject(text) }.getOrElse { throw WorkerApiException(status, "Cloudflare returned HTTP $status") }
                if (status !in 200..299) json.put("success", false)
                return json.put("httpStatus", status)
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal class WorkerApiException(val status: Int, message: String) : IOException(message)
