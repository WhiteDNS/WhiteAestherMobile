package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.ScanStrategy
import com.whitedns.whiteaesther.data.TlsFragmentMode
import com.whitedns.whiteaesther.data.TlsOptions
import com.whitedns.whiteaesther.data.TunnelProtocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** The recipe that actually passed; applying a result must apply all of it. */
data class EndpointProbeProfile(
    val transport: TunnelProtocol,
    val fragmentTls: Boolean,
    val encryptedHello: Boolean,
    val fragmentMode: TlsFragmentMode,
    // Fragment mode is canonicalized separately so changing a recovery preset
    // does not hide differences in the rest of the tested TLS configuration.
    val tlsOptions: TlsOptions = TlsOptions(),
    val tlsGroups: String = "",
    val noizeProfile: String = "firewall",
    val dualStack: Boolean = true,
) {
    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        transport = transport,
        fragmentTls = fragmentTls,
        encryptedHello = encryptedHello,
        tlsOptions = tlsOptions.copy(fragmentMode = fragmentMode),
        tlsGroups = tlsGroups,
        noizeProfile = noizeProfile,
        dualStack = dualStack,
    )

    companion object {
        fun from(settings: AppSettings) = EndpointProbeProfile(
            settings.transport, settings.fragmentTls, settings.encryptedHello,
            settings.tlsOptions.fragmentMode,
            settings.tlsOptions.copy(fragmentMode = TlsFragmentMode.AUTOMATIC),
            settings.tlsGroups, settings.noizeProfile, settings.dualStack,
        )
    }
}

fun EndpointScanResult.applyTo(settings: AppSettings): AppSettings =
    (profile?.applyTo(settings) ?: settings).copy(
        endpointMode = EndpointMode.CUSTOM_FIRST,
        customEndpoint = peer,
        customEndpointProtocol = profile?.transport ?: settings.transport,
    )

enum class DiscoveryStage { REMEMBERED, SEARCHING, CONFIRMING, COMPLETE, CANCELLED, NETWORK_CHANGED }

data class DiscoveryUpdate(
    val stage: DiscoveryStage = DiscoveryStage.SEARCHING,
    val profile: EndpointProbeProfile? = null,
    val pass: Int = 0,
    val completed: Int = 0,
    val total: Int = 0,
    val elapsedSeconds: Long = 0,
    val failureSummary: String = "",
    val results: List<EndpointScanResult> = emptyList(),
    val diagnostics: List<String> = emptyList(),
)

interface EndpointDiscoveryBackend {
    suspend fun scan(
        settings: AppSettings,
        budgetMillis: Long,
        onProgress: (NativeScanProgress) -> Unit,
    ): Result<List<EndpointScanResult>>
    suspend fun confirm(settings: AppSettings, budgetMillis: Long): Result<EndpointScanResult>
    fun cancel()
}

/** Sequential native passes: the engine's TLS configuration is process-wide. */
class EndpointDiscovery(
    private val backend: EndpointDiscoveryBackend,
    private val networkUnchanged: () -> Boolean = { true },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        backend.cancel()
    }

    suspend fun discover(
        settings: AppSettings,
        first: TunnelProtocol,
        remembered: List<EndpointScanResult> = emptyList(),
        budgetMillis: Long? = null,
        targetVerified: Int = 3,
        onUpdate: (DiscoveryUpdate) -> Unit,
    ): DiscoveryUpdate {
        val started = nowMillis()
        val budget = budgetMillis?.coerceAtLeast(0) ?: when (settings.scanStrategy) {
            ScanStrategy.TURBO -> 60_000L
            ScanStrategy.THOROUGH -> 360_000L
            else -> 210_000L
        }
        val confirmationBudget = if (settings.scanStrategy == ScanStrategy.TURBO) 10_000L else 25_000L
        val candidates = linkedMapOf<Pair<String, EndpointProbeProfile?>, EndpointScanResult>()
        val diagnostics = mutableListOf<String>()
        var state = DiscoveryUpdate()
        fun publish(update: DiscoveryUpdate = state): DiscoveryUpdate {
            state = update.copy(
                elapsedSeconds = (nowMillis() - started) / 1000,
                results = rank(candidates.values),
                diagnostics = diagnostics.takeLast(12),
            )
            onUpdate(state)
            return state
        }
        fun canContinue(): Boolean = !cancelled.get() && networkUnchanged() && nowMillis() - started < budget
        fun merge(result: EndpointScanResult) {
            val key = result.peer to result.profile
            val old = candidates[key]
            // A later discovery snapshot must not erase completed confirmations.
            if (old == null || result.attemptedChecks >= old.attemptedChecks) candidates[key] = result
        }
        fun failure(profile: EndpointProbeProfile, message: String?) {
            diagnostics += "${profile.transport.wireName}: ${message ?: "probe failed"}"
        }
        suspend fun confirm(candidate: EndpointScanResult): EndpointScanResult {
            val profile = candidate.profile ?: return candidate
            val samples = mutableListOf<Long>()
            var attempted = 0
            repeat(2) {
                if (!canContinue()) return@repeat
                publish(state.copy(stage = DiscoveryStage.CONFIRMING, profile = profile))
                attempted++
                val config = profile.applyTo(settings).copy(
                    customEndpoint = candidate.peer,
                    endpointMode = EndpointMode.CUSTOM_ONLY,
                    scanStrategy = ScanStrategy.IRONCLAD,
                )
                backend.confirm(config, minOf(confirmationBudget, budget - (nowMillis() - started))).fold(
                    onSuccess = { samples += it.rttMillis },
                    onFailure = {
                        if (it is CancellationException) throw it
                        failure(profile, it.message)
                    },
                )
                // The second sample is independent, including tunnel setup.
            }
            val sorted = samples.sorted()
            val latency = when (sorted.size) {
                0 -> candidate.rttMillis
                1 -> sorted.single()
                else -> sorted.first() + (sorted.last() - sorted.first()) / 2
            }
            return candidate.copy(
                rttMillis = latency,
                successfulChecks = samples.size,
                attemptedChecks = attempted,
                jitterMillis = if (sorted.size < 2) 0 else sorted.last() - sorted.first(),
            ).also { merge(it); publish() }
        }

        val profiles = profiles(settings, first)
        // Cached addresses are hints, never results until checked on this network.
        for (hint in remembered.filter { it.profile in profiles }.take(3)) {
            if (candidates.values.count { it.successfulChecks == 2 } >= targetVerified) break
            if (!canContinue()) break
            publish(state.copy(stage = DiscoveryStage.REMEMBERED, profile = hint.profile))
            val tested = confirm(hint.copy(successfulChecks = 0, attemptedChecks = 0))
            if (tested.successfulChecks == 0) {
                candidates.remove(tested.peer to tested.profile)
                publish()
            }
        }

        var pass = 0
        // Probe both framings before spending the budget expanding one pool.
        for (profile in profiles) {
            if (!canContinue()) break
            if (candidates.values.count { it.successfulChecks == 2 } >= targetVerified) break
            pass++
            publish(state.copy(stage = DiscoveryStage.SEARCHING, profile = profile, pass = pass, completed = 0, total = 0))
            val config = profile.applyTo(settings).copy(
                endpointMode = EndpointMode.AUTOMATIC, customEndpoint = "",
                scanStrategy = if (settings.scanStrategy == ScanStrategy.IRONCLAD) ScanStrategy.BALANCED else settings.scanStrategy,
            )
            val remaining = budget - (nowMillis() - started)
            val result = backend.scan(config, minOf(18_000L, remaining)) { progress ->
                progress.results.forEach { merge(it.copy(profile = profile)) }
                if (networkUnchanged()) {
                    publish(state.copy(completed = progress.completed, total = progress.total, failureSummary = progress.failures))
                } else {
                    candidates.clear()
                    backend.cancel()
                }
            }
            result.fold(
                onSuccess = { hits -> hits.forEach { merge(it.copy(profile = profile)) } },
                onFailure = {
                    if (it is CancellationException) throw it
                    failure(profile, it.message)
                },
            )
            publish()
            // Bound expensive confirmation; a large pool cannot consume every pass.
            for (candidate in rank(candidates.values.filter { it.profile == profile && it.attemptedChecks == 0 }).take(2)) {
                if (candidates.values.count { it.successfulChecks == 2 } >= targetVerified) break
                if (canContinue()) confirm(candidate)
            }
        }

        // Only deepen when the quick/tactic passes did not carry real traffic.
        if (canContinue() && candidates.values.none { it.successfulChecks > 0 }) {
            for (profile in profiles.take(2)) {
                if (!canContinue()) break
                pass++
                publish(state.copy(stage = DiscoveryStage.SEARCHING, profile = profile, pass = pass, completed = 0, total = 0))
                val config = profile.applyTo(settings).copy(
                    endpointMode = EndpointMode.AUTOMATIC, customEndpoint = "",
                    scanStrategy = if (settings.scanStrategy == ScanStrategy.IRONCLAD) ScanStrategy.BALANCED else settings.scanStrategy,
                )
                backend.scan(config, minOf(60_000L, budget - (nowMillis() - started))) { progress ->
                    progress.results.forEach { merge(it.copy(profile = profile)) }
                    if (networkUnchanged()) {
                        publish(state.copy(completed = progress.completed, total = progress.total, failureSummary = progress.failures))
                    } else {
                        candidates.clear()
                        backend.cancel()
                    }
                }.fold(
                    onSuccess = { hits -> hits.forEach { merge(it.copy(profile = profile)) } },
                    onFailure = {
                        if (it is CancellationException) throw it
                        failure(profile, it.message)
                    },
                )
                for (candidate in rank(candidates.values.filter { it.profile == profile && it.successfulChecks == 0 }).take(3)) {
                    if (candidates.values.count { it.successfulChecks == 2 } >= targetVerified) break
                    if (canContinue()) confirm(candidate)
                }
                if (candidates.values.any { it.successfulChecks > 0 }) break
            }
        }
        val finalStage = when {
            cancelled.get() -> DiscoveryStage.CANCELLED
            !networkUnchanged() -> DiscoveryStage.NETWORK_CHANGED
            else -> DiscoveryStage.COMPLETE
        }
        if (finalStage == DiscoveryStage.NETWORK_CHANGED) candidates.clear()
        return publish(state.copy(stage = finalStage))
    }

    companion object {
        fun profiles(settings: AppSettings, first: TunnelProtocol): List<EndpointProbeProfile> {
            val base = EndpointProbeProfile.from(settings)
            val protocol = if (settings.transport.isAutomatic) first else settings.transport
            val primary = base.copy(transport = protocol)
            if (protocol !in listOf(TunnelProtocol.H2, TunnelProtocol.H3)) return listOf(primary)
            val sibling = primary.copy(transport = if (protocol == TunnelProtocol.H2) TunnelProtocol.H3 else TunnelProtocol.H2)
            val h2 = primary.copy(transport = TunnelProtocol.H2)
            return listOf(
                primary, sibling,
                h2.copy(fragmentTls = true, fragmentMode = TlsFragmentMode.RECORDS),
                h2.copy(encryptedHello = true),
                primary.copy(transport = TunnelProtocol.H3, encryptedHello = true),
            ).distinct()
        }

        fun rank(results: Collection<EndpointScanResult>): List<EndpointScanResult> = results.sortedWith(
            compareByDescending<EndpointScanResult> { it.successfulChecks }
                .thenBy { if (it.attemptedChecks == 0) 0 else it.attemptedChecks - it.successfulChecks }
                .thenBy { it.jitterMillis }.thenBy { it.rttMillis },
        ).take(24)
    }
}
