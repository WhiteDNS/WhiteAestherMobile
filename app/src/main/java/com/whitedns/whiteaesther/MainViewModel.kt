package com.whitedns.whiteaesther

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whitedns.whiteaesther.core.ChainController
import com.whitedns.whiteaesther.core.ChainNode
import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.data.AddressReporter
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.data.SettingsRepository
import com.whitedns.whiteaesther.data.UpdateChecker
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatusStore
import com.whitedns.whiteaesther.service.TrafficMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something to tell the user about their identity, after an export or import. */
data class IdentityMessage(val text: String, val isError: Boolean = false)

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

/**
 * The address seen on each side of the tunnel.
 *
 * Both nullable and for different reasons. [real] is absent until the app has
 * been open while disconnected long enough to look it up; [tunnel] is absent
 * whenever no session is carrying traffic.
 */
data class AddressPair(
    val real: String? = null,
    val tunnel: String? = null,
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
    private val mutableIdentityMessage = MutableStateFlow<IdentityMessage?>(null)
    val identityMessage = mutableIdentityMessage.asStateFlow()

    /**
     * The address the internet sees, on each side of the tunnel.
     *
     * Two separate lookups rather than one refreshed: the address without the
     * tunnel is only ever read while there is no tunnel, so it is captured
     * before connecting and then left alone. Asking again mid-session would
     * push the user's real address out past the thing hiding it.
     */
    private val mutableAddresses = MutableStateFlow(AddressPair())
    val addresses = mutableAddresses.asStateFlow()

    val traffic = TrafficMeter.sample

    /**
     * A newer release, once one has been seen. Null the rest of the time.
     */
    private val mutableUpdate = MutableStateFlow<UpdateChecker.Available?>(null)
    val update = mutableUpdate.asStateFlow()

    /**
     * Whether the user has switched IPv6 off.
     *
     * The setting names what the tunnel scans, but a screen that shows an IPv6
     * address while the switch says IPv4 only reads as the switch doing
     * nothing, so the lookup follows it too.
     */
    private fun ipv4Only(): Boolean = !settings.value.dualStack

    /**
     * Whether a second hop is carrying this session's traffic.
     *
     * When it is, the exit belongs to the chain's node rather than to
     * Cloudflare, and nothing reachable from this process can measure it.
     */
    private fun chainCarriesTraffic(): Boolean =
        settings.value.chain.enabled && settings.value.mode == EngineMode.TUN

    /** Stops asking about this version. */
    fun dismissUpdate() {
        val available = mutableUpdate.value ?: return
        mutableUpdate.value = null
        viewModelScope.launch { UpdateChecker.dismiss(getApplication(), available.version) }
    }


    init {
        watchConnectionForAddresses()
        sampleTrafficWhileConnected()
    }

    /**
     * Reads the real address now, while nothing is carrying traffic.
     *
     * Called when the app is opened and idle. A user who installs, connects
     * immediately and never opens the app while disconnected simply has no
     * before-figure, which is better than fetching one through the tunnel and
     * labelling the tunnel's own exit as theirs.
     */
    fun captureRealAddressIfIdle() {
        if (EngineStatusStore.status.value.stage != EngineStage.IDLE) return
        if (mutableAddresses.value.real != null) return
        viewModelScope.launch {
            val stored = AddressReporter.realAddress(getApplication(), ipv4Only())
            if (stored != null) {
                mutableAddresses.value = mutableAddresses.value.copy(real = stored)
                return@launch
            }
            val fetched = AddressReporter.captureRealAddress(getApplication(), ipv4Only())
            if (fetched != null) {
                mutableAddresses.value = mutableAddresses.value.copy(real = fetched)
            }
        }
    }

    private fun watchConnectionForAddresses() {
        viewModelScope.launch {
            EngineStatusStore.status
                .map { it.stage }
                .distinctUntilChanged()
                .collect { stage ->
                    when (stage) {
                        EngineStage.CONNECTED -> {
                            mutableAddresses.value = mutableAddresses.value.copy(tunnel = null)
                            // With the exit chain running this process is kept
                            // off its own interface -- deliberately, or the
                            // engine's and mihomo's own sockets would be
                            // captured by the tunnel they are building. A probe
                            // from here therefore leaves by the physical
                            // network and reports the address the tunnel exists
                            // to hide. It said "seen by websites" over the
                            // user's real IP.
                            val exit = if (chainCarriesTraffic()) {
                                null
                            } else {
                                AddressReporter.tunnelAddress(ipv4Only())
                            }
                            mutableAddresses.value = mutableAddresses.value.copy(tunnel = exit)
                            // Only here. Asking GitHub from an unprotected
                            // socket would tell the network this device runs a
                            // circumvention tool; inside the tunnel it is just
                            // more session traffic.
                            mutableUpdate.value = UpdateChecker.check(
                                getApplication(),
                                BuildConfig.VERSION_NAME,
                            )
                        }
                        EngineStage.IDLE -> {
                            mutableAddresses.value = mutableAddresses.value.copy(tunnel = null)
                            captureRealAddressIfIdle()
                        }
                        else -> mutableAddresses.value = mutableAddresses.value.copy(tunnel = null)
                    }
                }
        }
    }

    /**
     * One reading a second, and only while something is running.
     *
     * Sampling a stopped tunnel would burn a wake-up per second to report
     * zero, which is the sort of thing that turns into a battery complaint.
     */
    private fun sampleTrafficWhileConnected() {
        viewModelScope.launch {
            while (true) {
                if (EngineStatusStore.status.value.stage == EngineStage.CONNECTED) {
                    withContext(Dispatchers.IO) { TrafficMeter.sampleNow() }
                }
                delay(TRAFFIC_SAMPLE_MS)
            }
        }
    }

    private val mutableChainState = MutableStateFlow(ChainState(available = chain.isAvailable))
    val chainState = mutableChainState.asStateFlow()
    private var chainJob: Job? = null

    fun save(settings: AppSettings) {
        viewModelScope.launch { repository.save(settings) }
    }

    /**
     * Produces the identity file the user saves before a reinstall.
     *
     * Returns null when there is nothing to export yet, so the caller does not
     * open a file picker for an empty file.
     */
    fun exportIdentity(): String? {
        val result = NativeAetherBridge.exportIdentity(identityConfigPath())
        result.exceptionOrNull()?.let { error ->
            mutableIdentityMessage.value = IdentityMessage(
                error.message ?: "There is no identity to export yet",
                isError = true,
            )
            return null
        }
        return result.getOrNull()
    }

    /**
     * Restores an identity, refusing while a tunnel is up.
     *
     * Swapping the identity under a running session would leave the engine
     * carrying traffic for a device it no longer has the keys for, and the
     * failure would arrive much later than the cause.
     */
    fun importIdentity(payload: String) {
        if (EngineStatusStore.status.value.stage != EngineStage.IDLE &&
            EngineStatusStore.status.value.stage != EngineStage.ERROR
        ) {
            mutableIdentityMessage.value =
                IdentityMessage("Disconnect before importing an identity", isError = true)
            return
        }
        val result = NativeAetherBridge.importIdentity(identityConfigPath(), payload)
        mutableIdentityMessage.value = if (result.ok) {
            IdentityMessage("Identity imported. Connect to use it.")
        } else {
            IdentityMessage(result.error ?: "That file is not a WhiteAesther identity", isError = true)
        }
    }

    /** Reports how saving the backup went, since only the activity can know. */
    fun reportIdentityWrite(error: String?) {
        mutableIdentityMessage.value = if (error == null) {
            IdentityMessage("Identity saved. Keep it somewhere safe.")
        } else {
            IdentityMessage(error, isError = true)
        }
    }

    fun clearIdentityMessage() {
        mutableIdentityMessage.value = null
    }

    /** The base path the engine derives every identity file from. */
    private fun identityConfigPath(): String =
        java.io.File(getApplication<Application>().filesDir, "aether.toml").absolutePath

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
                message = "Scanning validated ${settings.transport.probedAs.label} routes…",
            )
            val base = settings
                .copy(endpointMode = EndpointMode.AUTOMATIC, customEndpoint = "")
                // The bridge takes a real transport. Scanning on H2 first
                // matches what Automatic tries first when connecting.
                .let { if (it.transport.isAutomatic) it.copy(transport = TunnelProtocol.H2) else it }
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
                // Automatic has not resolved yet here -- the scanner is not a
                // connect. Sweeping both framings is exactly what it would do.
                TunnelProtocol.AUTO -> TunnelProtocol.H3
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

        /** One second: fast enough to read as live, slow enough to be free. */
        const val TRAFFIC_SAMPLE_MS = 1_000L
    }
}
