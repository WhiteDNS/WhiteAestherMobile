package com.whitedns.whiteaesther.core

import android.content.Context
import com.whitedns.whiteaesther.data.*
import com.whitedns.whiteaesther.service.EngineLog
import com.whitedns.whiteaesther.service.LogLevel
import com.whitedns.whiteaesther.service.NetworkIdentity
import kotlinx.coroutines.*
import org.json.JSONObject

data class EndpointPreparation(val config: String, val engine: PreparedEngine)

/** The same discovery policy as the Routes screen, with time reserved for connecting. */
class AutomaticEndpointPreparation(
    private val backend: EndpointDiscoveryBackend,
    private val provision: suspend (String) -> Result<Unit>,
    private val prepare: suspend (String) -> Result<PreparedEngine>,
    private val networkUnchanged: () -> Boolean = { true },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun run(
        base: String,
        budgetMillis: Long,
        hints: List<EndpointScanResult> = emptyList(),
        onUpdate: (DiscoveryUpdate) -> Unit = {},
    ): Result<EndpointPreparation> {
        val started = nowMillis()
        if (!networkUnchanged()) return Result.failure(IllegalStateException("network unavailable before identity preparation"))
        // Provision once before trying profiles. An enrollment failure must
        // not turn into five more attempts disguised as endpoint searches.
        val provisioned = provision(base)
        provisioned.exceptionOrNull()?.let { return Result.failure(it) }
        if (!networkUnchanged()) return Result.failure(IllegalStateException("network changed during identity preparation"))
        val settings = settings(base)
        val remaining = budgetMillis - (nowMillis() - started) - CONNECT_RESERVE_MS
        if (remaining <= 0) return Result.failure(IllegalStateException("identity preparation used the connection budget"))
        val discovered = EndpointDiscovery(backend, networkUnchanged, nowMillis).discover(
            settings, settings.transport, hints, budgetMillis = remaining, targetVerified = 1,
            onUpdate = onUpdate,
        )
        if (!networkUnchanged() || discovered.stage != DiscoveryStage.COMPLETE) {
            return Result.failure(IllegalStateException("endpoint discovery cancelled or network changed"))
        }
        val selected = discovered.results.firstOrNull { it.successfulChecks > 0 }
            ?: return Result.failure(IllegalStateException(
                "automatic endpoint discovery found no traffic-verified route: " +
                    discovered.diagnostics.joinToString("; ").ifBlank { discovered.failureSummary },
            ))
        val config = JSONObject(configuration(base, selected.applyTo(settings)))
            .put("peerFallback", false).toString()
        return prepare(config).map { EndpointPreparation(config, it) }
    }

    companion object {
        const val CONNECT_RESERVE_MS = 10_000L

        fun settings(base: String): AppSettings {
            val json = JSONObject(base)
            val tls = JSONObject(base).put("splitSni", json.optBoolean("fragmentSni", true))
            // The automatic planner's shape is part of the tested recipe.
            if (json.optString("fragmentMode", "auto") == "auto" &&
                json.optString("helloShape") in listOf("records", "compact")) {
                tls.put("fragmentMode", json.getString("helloShape"))
            }
            return AppSettings(
                transport = TunnelProtocol.entries.firstOrNull { it.wireName == json.optString("transport") } ?: TunnelProtocol.H2,
                scanStrategy = ScanStrategy.entries.firstOrNull { it.wireName == json.optString("scanMode") } ?: ScanStrategy.BALANCED,
                dualStack = json.optString("ipScan", "both") !in listOf("v4", "4", "ipv4"),
                noizeProfile = json.optString("noize", "firewall"),
                fragmentTls = json.optBoolean("fragmentTls"),
                encryptedHello = json.optBoolean("encryptedHello"),
                tlsOptions = TlsOptions.decode(tls.toString()),
                tlsGroups = json.optString("tlsGroups"),
            )
        }

        /** Change only the tested recipe; retain identity, proxy, auth and routing policy. */
        fun configuration(base: String, settings: AppSettings): String {
            val json = JSONObject(base)
                .put("transport", settings.transport.wireName)
                .put("scanMode", settings.scanStrategy.wireName)
                .put("fragmentTls", settings.fragmentTls).put("encryptedHello", settings.encryptedHello)
                .put("tlsGroups", settings.tlsGroups).put("noize", settings.noizeProfile)
            if (json.optString("ipScan") !in listOf("v6", "6", "ipv6")) {
                json.put("ipScan", if (settings.dualStack) "both" else "v4")
            }
            json.remove("peer")
            json.put("peerFallback", false)
            if (settings.customEndpoint.isNotBlank()) json.put("peer", settings.customEndpoint)
            settings.tlsOptions.applyTo(json)
            return json.toString()
        }
    }
}

/** Used by both the direct VPN engine and Aether's lane in the carrier race. */
suspend fun prepareEndpointEngine(context: Context, config: String, budgetMillis: Long): Result<EndpointPreparation> {
    if (!JSONObject(config).optBoolean("advancedDiscovery")) {
        return withContext(Dispatchers.IO) { NativeAetherBridge.prepare(config) }.map { EndpointPreparation(config, it) }
    }
    return coroutineScope {
        val network = NetworkIdentity.current(context)
        var expired = false
        val sameNetwork = {
            val current = NetworkIdentity.current(context)
            !expired && current.online && current.key == network.key && current.handle == network.handle
        }
        val settings = AutomaticEndpointPreparation.settings(config)
        val memory = EndpointDiscoveryMemory(context)
        val watchdog = launch {
            val deadline = System.nanoTime() / 1_000_000 + budgetMillis
            while (isActive) {
                if (System.nanoTime() / 1_000_000 >= deadline || !sameNetwork()) {
                    expired = true
                    NativeAetherBridge.cancelPrepare()
                    NativeAetherBridge.cancelScan()
                }
                delay(100)
            }
        }
        try {
            AutomaticEndpointPreparation(
                backend = NativeEndpointDiscovery(context, sameNetwork) { AutomaticEndpointPreparation.configuration(config, it) },
                provision = { withContext(Dispatchers.IO) { NativeAetherBridge.provision(it) }.map { } },
                prepare = { withContext(Dispatchers.IO) { NativeAetherBridge.prepare(it) } },
                networkUnchanged = sameNetwork,
            ).run(config, budgetMillis, withContext(Dispatchers.IO) {
                val pinned = JSONObject(config).optString("peer").takeIf { it.isNotBlank() }?.let {
                    EndpointScanResult(it, 0, EndpointProbeProfile.from(settings))
                }
                listOfNotNull(pinned) + memory.read(network.key, settings)
            }) { update ->
                if (update.results.any { it.successfulChecks > 0 }) memory.write(network.key, settings, update.results)
            }.also { result ->
                result.fold(
                    onSuccess = { EngineLog.record(LogLevel.INFO, "auto-scanner", "traffic-verified ${JSONObject(it.config).optString("transport")} endpoint ${it.engine.peer}") },
                    onFailure = { EngineLog.record(LogLevel.WARN, "auto-scanner", it.message ?: "preparation failed") },
                )
            }
        } finally { watchdog.cancel() }
    }
}
