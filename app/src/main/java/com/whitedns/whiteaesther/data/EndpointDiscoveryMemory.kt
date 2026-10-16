package com.whitedns.whiteaesther.data

import android.content.Context
import com.whitedns.whiteaesther.core.EndpointProbeProfile
import com.whitedns.whiteaesther.core.EndpointScanResult
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local hints. Nothing from disk is presented as a fresh result. */
class EndpointDiscoveryMemory(context: Context) {
    private val preferences = context.getSharedPreferences("endpoint_discovery", Context.MODE_PRIVATE)

    private fun key(network: String, settings: AppSettings): String {
        val material = listOf(
            network, settings.transport.endpointFamily.name, settings.dualStack.toString(),
            settings.noizeProfile, settings.tlsGroups, settings.tlsOptions.encode(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun read(network: String?, settings: AppSettings): List<EndpointScanResult> {
        if (network == null) return emptyList()
        return runCatching {
            val json = JSONObject(preferences.getString(key(network, settings), null) ?: return emptyList())
            val age = System.currentTimeMillis() - json.getLong("saved")
            if (age !in 0..MAX_AGE) return emptyList()
            val rows = json.getJSONArray("results")
            List(minOf(rows.length(), 12)) { index ->
                val row = rows.getJSONObject(index)
                EndpointScanResult(
                    peer = EndpointAddress.normalize(row.getString("peer")) ?: error("invalid cached endpoint"),
                    rttMillis = row.getLong("rtt").coerceAtLeast(0),
                    profile = EndpointProbeProfile.from(settings).copy(
                        transport = TunnelProtocol.valueOf(row.getString("protocol")),
                        fragmentTls = row.getBoolean("fragment"), encryptedHello = row.getBoolean("ech"),
                        fragmentMode = TlsFragmentMode.valueOf(row.getString("fragmentMode")),
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun write(network: String?, settings: AppSettings, results: List<EndpointScanResult>) {
        if (network == null) return
        val rows = JSONArray()
        results.filter { it.successfulChecks > 0 && it.profile != null }.take(12).forEach { result ->
            val profile = requireNotNull(result.profile)
            rows.put(JSONObject().put("peer", result.peer).put("rtt", result.rttMillis)
                .put("protocol", profile.transport.name).put("fragment", profile.fragmentTls)
                .put("ech", profile.encryptedHello).put("fragmentMode", profile.fragmentMode.name))
        }
        val cacheKey = key(network, settings)
        val editor = preferences.edit()
        // Keep at most eight network/configuration combinations.
        val oldest = preferences.all.entries.filter { it.key != cacheKey }.sortedBy {
            runCatching { JSONObject(it.value as String).optLong("saved") }.getOrDefault(0)
        }
        oldest.take((oldest.size - 7).coerceAtLeast(0)).forEach { editor.remove(it.key) }
        if (rows.length() == 0) editor.remove(cacheKey) else editor.putString(
            cacheKey, JSONObject().put("saved", System.currentTimeMillis()).put("results", rows).toString(),
        )
        editor.apply()
    }

    fun forget(network: String?, settings: AppSettings) {
        if (network != null) preferences.edit().remove(key(network, settings)).apply()
    }

    private companion object { const val MAX_AGE = 7L * 24 * 60 * 60 * 1000 }
}
