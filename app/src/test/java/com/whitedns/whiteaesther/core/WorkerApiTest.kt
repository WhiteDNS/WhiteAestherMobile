package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.WorkerDeployment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class WorkerApiTest {
    private val token = "a".repeat(40)
    private val deployment = WorkerDeployment("a".repeat(32), "whiteaesther-test", false)
    private fun ok(result: Any = JSONObject()) = JSONObject().put("success", true).put("result", result)

    @Test
    fun tokenTemplatePrefillsOnlyTheRequiredPermissions() {
        val uri = URI(WorkerApi.tokenTemplateUrl)
        assertEquals("https", uri.scheme)
        assertEquals("dash.cloudflare.com", uri.host)
        assertEquals("/profile/api-tokens", uri.path)
        assertNotNull("The token button must open a prefilled form", uri.rawQuery)
        val query = uri.rawQuery.split('&').associate {
            val (key, value) = it.split('=', limit = 2)
            key to URLDecoder.decode(value, "UTF-8")
        }
        assertEquals(setOf("permissionGroupKeys", "accountId", "zoneId", "name"), query.keys)
        assertEquals("WhiteAesther", query["name"])
        assertEquals("*", query["accountId"])
        assertEquals("all", query["zoneId"])
        val permissions = JSONArray(query.getValue("permissionGroupKeys"))
        assertEquals(6, permissions.length())
        assertEquals(mapOf("account_settings" to "read", "workers_scripts" to "edit", "page" to "edit", "zone" to "read", "dns" to "edit", "account_analytics" to "read"),
            (0 until permissions.length()).associate {
                permissions.getJSONObject(it).let { permission -> permission.getString("key") to permission.getString("type") }
            })
    }

    @Test
    fun workerDeploymentBindsTheSameIdentityAndEnablesItsPublicRoute() {
        val calls = mutableListOf<WorkerRequest>()
        val saved = mutableListOf<WorkerDeployment>()
        val api = WorkerApi(token, "export default {}") { suppliedToken, request ->
            assertEquals(token, suppliedToken)
            calls += request
            if (request.path.endsWith("workers/subdomain")) ok(JSONObject().put("subdomain", "my-edge")) else ok()
        }
        val result = api.deploy(deployment, saved::add)
        assertTrue(result.ready)
        assertEquals(deployment.uuid, result.uuid)
        assertEquals("${deployment.name}.my-edge.workers.dev", result.host)
        assertFalse(saved.first().ready)
        assertTrue(saved.first().created)
        assertTrue(calls[1].contentType.startsWith("multipart/form-data; boundary="))
        val body = calls[1].body!!.toString(Charsets.UTF_8)
        assertTrue(body.contains(deployment.environment()))
        assertTrue(body.contains("filename=\"worker.js\""))
        assertFalse(body.contains(token))
        assertTrue(calls.last().path.endsWith("/${deployment.name}/subdomain"))
    }

    @Test
    fun failedPagesUploadKeepsTheCreatedProjectForRetryInsteadOfCreatingItAgain() {
        val calls = mutableListOf<WorkerRequest>()
        var recorded = deployment.copy(pages = true)
        var failUpload = true
        val api = WorkerApi(token, "export default {}") { _, request ->
            calls += request
            if (request.path.endsWith("/deployments") && failUpload) throw java.io.IOException("offline")
            ok(JSONObject().put("subdomain", "whiteaesther-test.pages.dev"))
        }
        assertThrows(java.io.IOException::class.java) { api.deploy(recorded) { recorded = it } }
        assertTrue(recorded.created)
        assertFalse(recorded.ready)
        failUpload = false
        api.deploy(recorded) { recorded = it }
        assertTrue(recorded.ready)
        assertEquals(1, calls.count { it.method == "POST" && it.path.endsWith("/pages/projects") })
        assertTrue(calls.any { it.method == "PATCH" })
    }

    @Test
    fun listAccountsFollowsPaginationAndRejectsBadIds() {
        var call = 0
        val api = WorkerApi(token, "") { _, _ ->
            call++
            ok(JSONArray().put(JSONObject().put("id", "a".repeat(32)).put("name", "Account $call")))
                .put("result_info", JSONObject().put("total_pages", 2))
        }
        assertEquals(2, api.accounts().size)
    }

    @Test
    fun invalidTokensNeverReachTheTransport() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkerApi("Bearer\n$token", "") { _, _ -> fail("transport called"); ok() }
        }
    }

    @Test
    fun deleteTreatsOnlyNotFoundAsAlreadyDeleted() {
        WorkerApi(token, "") { _, _ -> JSONObject().put("success", false).put("httpStatus", 404) }.delete(deployment)
        assertThrows(WorkerApiException::class.java) {
            WorkerApi(token, "") { _, _ -> JSONObject().put("success", false).put("httpStatus", 403) }.delete(deployment)
        }
    }

    @Test
    fun pagesDomainNeverReplacesAnExistingWebsiteRecord() {
        val calls = mutableListOf<WorkerRequest>()
        var saved = deployment.copy(pages = true, host = "whiteaesther-test.pages.dev", ready = true)
        val api = WorkerApi(token, "") { _, request ->
            calls += request
            if (request.path.startsWith("/zones?")) ok(JSONArray().put(JSONObject().put("id", "b".repeat(32))))
            else ok(JSONArray().put(JSONObject().put("type", "A").put("name", "vpn.example.com").put("content", "192.0.2.1")))
        }
        assertThrows(IllegalArgumentException::class.java) { api.attachDomain(saved, "vpn.example.com") { saved = it } }
        assertTrue(saved.domainPending)
        assertEquals(saved.host, saved.connectionHost)
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test
    fun failedPagesAttachmentRetainsTheCreatedRecordAndRetryDoesNotDuplicateIt() {
        var saved = deployment.copy(pages = true, host = "whiteaesther-test.pages.dev", ready = true)
        var recordCreated = false
        var fail = true
        var creates = 0
        val record = JSONObject().put("id", "c".repeat(32)).put("type", "CNAME").put("name", "vpn.example.com").put("content", saved.host)
        val api = WorkerApi(token, "") { _, request ->
            when {
                request.path.startsWith("/zones?") -> ok(JSONArray().put(JSONObject().put("id", "b".repeat(32))))
                request.method == "POST" && request.path.endsWith("dns_records") -> { creates++; recordCreated = true; ok(record) }
                request.path.contains("dns_records?") -> ok(JSONArray().apply { if (recordCreated) put(record) })
                request.method == "GET" -> ok(JSONArray())
                fail -> throw java.io.IOException("offline")
                else -> ok()
            }
        }
        assertThrows(java.io.IOException::class.java) { api.attachDomain(saved, "vpn.example.com") { saved = it } }
        assertEquals(record.getString("id"), saved.dnsRecord)
        assertTrue(saved.domainPending)
        fail = false
        saved = api.attachDomain(saved, "vpn.example.com") { saved = it }
        assertFalse(saved.domainPending)
        assertEquals("vpn.example.com", saved.connectionHost)
        assertEquals(1, creates)
    }

    @Test
    fun detachingKeepsDnsRecordsThatTheOperatorChanged() {
        val original = deployment.copy(pages = true, host = "whiteaesther-test.pages.dev", customDomain = "vpn.example.com", dnsZone = "b".repeat(32), dnsRecord = "c".repeat(32))
        val deleted = mutableListOf<String>()
        val api = WorkerApi(token, "") { _, request ->
            if (request.method == "DELETE") deleted += request.path
            ok(JSONObject().put("name", original.customDomain).put("type", "CNAME").put("content", "another-site.example.com"))
        }
        assertEquals("", api.detachDomain(original) {}.customDomain)
        assertEquals(listOf("/accounts/${original.accountId}/pages/projects/${original.name}/domains/vpn.example.com"), deleted)
    }

    @Test
    fun usageErrorsAreReportedInsteadOfDisplayingZero() {
        val api = WorkerApi(token, "") { _, _ -> JSONObject().put("errors", JSONArray().put(JSONObject().put("message", "forbidden"))) }
        assertThrows(java.io.IOException::class.java) { api.usage(deployment) }
    }
}
