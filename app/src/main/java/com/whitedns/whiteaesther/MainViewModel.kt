package com.whitedns.whiteaesther

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whitedns.whiteaesther.core.ChainController
import com.whitedns.whiteaesther.core.ChainNode
import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.data.SettingsRepository
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EndpointOperation {
    SCANNING,
    TESTING,
    CANCELLING,
}

data class EndpointScannerState(
    val operation: EndpointOperation? = null,
    val results: List<EndpointScanResult> = emptyList(),
    val message: String? = null,
    val error: String? = null,
)

/**
 * What the running chain has to say about its nodes.
 *
 * [available] is separate from an empty list on purpose. No nodes because the
 * chain is not running, and no nodes because the subscription returned none, are
 * different problems and the screen says different things about them.
 */
data class ChainState(
    val available: Boolean = false,
    val nodes: List<ChainNode> = emptyList(),
    val selected: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** The engine is running a configuration the settings no longer describe. */
    val stale: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    // Reaches the same mihomo the service is running: there is one per process.
    private val chain = ChainController(application)

    val settings = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )
    val engineStatus = EngineStatusStore.status
    private val mutableEndpointScannerState = MutableStateFlow(EndpointScannerState())
    val endpointScannerState = mutableEndpointScannerState.asStateFlow()
    private var endpointJob: Job? = null

    // Seeded with what the build can do, rather than waiting for a refresh
    // that only happens once connected. Whether the library is present is known
    // from the start, and the screen has to say so before the user configures
    // something that can never run.
    private val mutableChainState = MutableStateFlow(ChainState(available = chain.isAvailable))
    val chainState = mutableChainState.asStateFlow()
    private var chainJob: Job? = null

    fun save(settings: AppSettings) {
        viewModelScope.launch { repository.save(settings) }
    }

    /**
     * Reads the node list back from the running chain.
     *
     * There is no list to read when it is not running. The nodes come from a
     * subscription mihomo has fetched and parsed, and fetching it early -- before
     * the tunnel exists -- would send the request over the local network in the
     * clear, which is the one thing dialling through the tunnel exists to avoid.
     */
    fun refreshChainNodes(settings: AppSettings) {
        if (chainJob?.isCompleted == false) return
        chainJob = viewModelScope.launch {
            mutableChainState.value = mutableChainState.value.copy(busy = true, error = null)
            // Editing a subscription does not reconfigure a chain that is
            // already running, so the engine would answer with the previous
            // one's nodes. Reporting them as the new subscription's is what
            // reads as "deleting it did nothing".
            if (!chain.isRunningConfigCurrent(settings.chain)) {
                mutableChainState.value = ChainState(available = chain.isAvailable, stale = true)
                return@launch
            }
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = ChainState(
                available = chain.isAvailable,
                nodes = reported.nodes,
                selected = reported.selected,
            )
        }
    }

    /** Switches the live chain, and remembers the choice for the next connect. */
    fun selectChainNode(settings: AppSettings, node: String) {
        if (chainJob?.isCompleted == false) return
        chainJob = viewModelScope.launch {
            // Saved first. If the engine refuses the switch the preference is
            // still what the user asked for, and the next connect honours it --
            // better than a screen that silently reverts.
            repository.save(settings.copy(chain = settings.chain.copy(node = node)))
            val failure = withContext(Dispatchers.IO) { chain.select(node) }
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = ChainState(
                available = chain.isAvailable,
                nodes = reported.nodes,
                selected = reported.selected,
                error = failure,
            )
        }
    }

    /**
     * Measures every node.
     *
     * The results come back through mihomo's event stream and land in the log, so
     * this waits before re-reading rather than expecting an answer here.
     */
    fun testChainNodes() {
        if (chainJob?.isCompleted == false) return
        val names = mutableChainState.value.nodes.map { it.name }
        if (names.isEmpty()) return
        chainJob = viewModelScope.launch {
            mutableChainState.value = mutableChainState.value.copy(busy = true, error = null)
            withContext(Dispatchers.IO) { chain.testNodes(names) }
            delay(DELAY_TEST_SETTLE_MS)
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = ChainState(
                available = chain.isAvailable,
                nodes = reported.nodes,
                selected = reported.selected,
            )
        }
    }

    fun scanEndpoints(settings: AppSettings) {
        if (!canRunEndpointOperation()) return
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.SCANNING,
                results = mutableEndpointScannerState.value.results,
                message = "Scanning validated ${settings.transport.label} routes…",
            )
            val base = settings.copy(endpointMode = EndpointMode.AUTOMATIC, customEndpoint = "")
            var result = withContext(Dispatchers.IO) {
                NativeAetherBridge.scan(base.toNativeJson(getApplication()))
            }
            // The MASQUE prober picks TCP or UDP from the framing it is handed:
            // H2 probes over TCP, H3 over QUIC. Scanning with the configured one
            // therefore searches UDP only on the default profile, and a network
            // that blocks UDP returns nothing at all -- which is what Iranian
            // mobile users were seeing. Sweep the other rather than reporting an
            // empty network.
            val other = when (base.transport) {
                TunnelProtocol.H3 -> TunnelProtocol.H2
                TunnelProtocol.H2 -> TunnelProtocol.H3
                // Neither WireGuard nor its nested form has another framing to
                // sweep. Their endpoints are their own, so falling back to
                // MASQUE would list addresses the chosen protocol cannot use.
                TunnelProtocol.WIREGUARD, TunnelProtocol.WARP_IN_WARP -> null
            }
            if (other != null &&
                result.getOrNull()?.isEmpty() != false &&
                mutableEndpointScannerState.value.operation == EndpointOperation.SCANNING
            ) {
                mutableEndpointScannerState.value = mutableEndpointScannerState.value.copy(
                    message = "Nothing over ${base.transport.label}, trying ${other.label}…",
                )
                result = withContext(Dispatchers.IO) {
                    NativeAetherBridge.scan(base.copy(transport = other).toNativeJson(getApplication()))
                }
            }
            result.fold(
                onSuccess = { endpoints ->
                    if (mutableEndpointScannerState.value.operation == EndpointOperation.CANCELLING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            message = "Scan cancelled",
                        )
                    } else {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = endpoints,
                            message = "${endpoints.size} validated endpoint${if (endpoints.size == 1) "" else "s"} found",
                        )
                    }
                },
                onFailure = { error ->
                    if (mutableEndpointScannerState.value.operation == EndpointOperation.CANCELLING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            message = "Scan cancelled",
                        )
                    } else if (mutableEndpointScannerState.value.operation == EndpointOperation.SCANNING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            error = error.message ?: "Endpoint scan failed",
                        )
                    }
                },
            )
        }
    }

    fun testEndpoint(settings: AppSettings) {
        val validationError = settings.copy(endpointMode = EndpointMode.CUSTOM_ONLY)
            .endpointValidationError()
        if (validationError != null) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                error = validationError,
            )
            return
        }
        if (!canRunEndpointOperation()) return
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.TESTING,
                results = mutableEndpointScannerState.value.results,
                message = "Testing custom endpoint…",
            )
            val config = settings.copy(endpointMode = EndpointMode.CUSTOM_ONLY)
                .toNativeJson(getApplication())
            val result = withContext(Dispatchers.IO) { NativeAetherBridge.testEndpoint(config) }
            result.fold(
                onSuccess = { endpoint ->
                    mutableEndpointScannerState.value = EndpointScannerState(
                        results = mutableEndpointScannerState.value.results,
                        message = "${endpoint.peer} validated in ${endpoint.rttMillis} ms",
                    )
                },
                onFailure = { error ->
                    mutableEndpointScannerState.value = EndpointScannerState(
                        results = mutableEndpointScannerState.value.results,
                        error = error.message ?: "Endpoint test failed",
                    )
                },
            )
        }
    }

    fun cancelEndpointScan() {
        if (mutableEndpointScannerState.value.operation != EndpointOperation.SCANNING) return
        NativeAetherBridge.cancelScan()
        mutableEndpointScannerState.value = EndpointScannerState(
            operation = EndpointOperation.CANCELLING,
            results = mutableEndpointScannerState.value.results,
            message = "Cancelling scan…",
        )
    }

    private fun canRunEndpointOperation(): Boolean {
        if (endpointJob?.isCompleted == false) return false
        if (EngineStatusStore.status.value.stage !in setOf(EngineStage.IDLE, EngineStage.ERROR)) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                error = "Disconnect before testing or scanning endpoints",
            )
            return false
        }
        return true
    }

    override fun onCleared() {
        NativeAetherBridge.cancelScan()
        endpointJob?.cancel()
        chainJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Long enough for the slowest test to answer, short enough to watch. */
        const val DELAY_TEST_SETTLE_MS = 6_000L
    }
}
