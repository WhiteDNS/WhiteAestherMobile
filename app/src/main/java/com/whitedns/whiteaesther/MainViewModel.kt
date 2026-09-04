package com.whitedns.whiteaesther

import android.app.Application
import androidx.annotation.StringRes
import com.whitedns.whiteaesther.core.AppLocale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whitedns.whiteaesther.core.ChainController
import com.whitedns.whiteaesther.core.ChainNode
import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.MoatClient
import com.whitedns.whiteaesther.core.TorBridges
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.data.AddressReporter
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TorBridge
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.data.SettingsRepository
import com.whitedns.whiteaesther.data.UpdateChecker
import com.whitedns.whiteaesther.service.AetherVpnService
import com.whitedns.whiteaesther.service.EngineLog
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.LogLevel
import com.whitedns.whiteaesther.service.EngineStatusStore
import com.whitedns.whiteaesther.service.TrafficMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
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
    /**
     * How far a delay run has got, as done to total.
     *
     * A thousand nodes take minutes however they are batched, and "Testing…"
     * with no number is indistinguishable from a run that has died.
     */
    val testProgress: Pair<Int, Int>? = null,
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
    /**
     * A string resource, in whatever language the app is set to.
     *
     * These messages are read on screen, so they follow the setting rather than
     * the phone. The context an AndroidViewModel holds is the application's,
     * which the activity's locale wrapping never touches -- so it is wrapped
     * here on the way past.
     */
    private fun say(@StringRes id: Int, vararg args: Any): String =
        AppLocale.wrap(getApplication<Application>()).getString(id, *args)

    private val repository = SettingsRepository(application)
    // Reaches the same mihomo the service is running: there is one per process.
    private val chain = ChainController(application)

    val settings = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    /**
     * Whether [settings] holds what is on disk yet, or still the placeholder.
     *
     * DataStore cannot be read synchronously, so the first value every screen
     * sees is a default that agrees with nothing the user has chosen. Most of
     * the interface can live with that for a frame. Anything that acts on a
     * setting rather than drawing it cannot: the placeholder says the language
     * is System, and an activity built for Persian read that as a change and
     * rebuilt itself, only to be told the same thing again by the next
     * placeholder -- a loop that never reached the real value.
     */
    val settingsLoaded: StateFlow<Boolean> = repository.settings
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )
    val engineStatus = EngineStatusStore.status
    private val mutableEndpointScannerState = MutableStateFlow(EndpointScannerState())
    val endpointScannerState = mutableEndpointScannerState.asStateFlow()
    private var endpointJob: Job? = null
    private var realAddressJob: Job? = null
    private var bridgeJob: Job? = null

    /** Whether a bridge fetch is in flight, for the button that started it. */
    private val mutableBridgesFetching = MutableStateFlow(false)
    val bridgesFetching = mutableBridgesFetching.asStateFlow()

    /** Why the last bridge fetch failed, or null. */
    private val mutableBridgesMessage = MutableStateFlow<String?>(null)
    val bridgesMessage = mutableBridgesMessage.asStateFlow()

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
     * tunnel is only ever read while there is no tunnel. It is re-read each
     * time the app is idle, because a phone changes networks and the answer
     * changes with them, but never while a session is up -- asking then would
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
     *
     * Read from the repository rather than from [settings]. That flow is a
     * stateIn holding AppSettings() until DataStore answers, and this runs from
     * onResume -- early enough to see the placeholder, whose dualStack is true.
     * The address lookup was therefore deciding by the default instead of by
     * the user, and kept showing the IPv6 address it was meant to discard.
     */
    private suspend fun ipv4Only(): Boolean = !repository.settings.first().dualStack

    /**
     * Whether a second hop is carrying this session's traffic.
     *
     * When it is, the exit belongs to the chain's node rather than to
     * Cloudflare, and nothing reachable from this process can measure it.
     */
    private suspend fun chainCarriesTraffic(): Boolean {
        val current = repository.settings.first()
        return current.chain.enabled && current.mode == EngineMode.TUN
    }

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
        // One at a time. onResume and the idle transition both arrive here, and
        // often within the same moment; two fetches would race to write one
        // field and the loser's answer would be the one left on screen.
        if (realAddressJob?.isActive == true) return
        realAddressJob = viewModelScope.launch {
            val ipv4Only = ipv4Only()
            val stored = AddressReporter.realAddress(getApplication(), ipv4Only)
            // Shown first so the row is filled while the network is asked, then
            // replaced by whatever the network answers.
            if (stored != null && mutableAddresses.value.real != stored) {
                mutableAddresses.value = mutableAddresses.value.copy(real = stored)
            }
            // Asked again every time, not only when nothing is stored. The
            // stored answer was true of the network that produced it: move from
            // wifi to mobile data, or let the ISP hand out a new address, and
            // it becomes a confident lie that nothing in the app can clear --
            // the first reading a device ever took would outlive every network
            // it went on to join. Refreshing is unsafe only while a tunnel is
            // carrying traffic, and this returns above when one is.
            val fresh = AddressReporter.captureRealAddress(getApplication(), ipv4Only)
            // Offline, or a blocked trace, leaves the stored answer standing
            // rather than blanking a field the user was reading.
            mutableAddresses.value = mutableAddresses.value.copy(real = fresh ?: stored)
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
                            val carrierPort = EngineStatusStore.status.value.carrierSocksPort
                            val exit = when {
                                // The chain's node is the exit, and nothing
                                // reachable from this process can measure it.
                                chainCarriesTraffic() -> null
                                // A carrier, asked through its own listener.
                                // Measuring it the ordinary way reports this
                                // phone's address under a heading that says the
                                // opposite, because this process is excluded
                                // from the interface on purpose -- which is
                                // exactly what it did before this existed.
                                carrierPort != null -> AddressReporter.carrierAddress(carrierPort)
                                else -> AddressReporter.tunnelAddress(ipv4Only())
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
                error.message ?: say(R.string.msg_no_identity_yet),
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
                IdentityMessage(say(R.string.msg_disconnect_before_import), isError = true)
            return
        }
        val result = NativeAetherBridge.importIdentity(identityConfigPath(), payload)
        mutableIdentityMessage.value = if (result.ok) {
            IdentityMessage(say(R.string.msg_identity_imported))
        } else {
            IdentityMessage(result.error ?: say(R.string.msg_not_an_identity), isError = true)
        }
    }

    /** Reports how saving the backup went, since only the activity can know. */
    fun reportIdentityWrite(error: String?) {
        mutableIdentityMessage.value = if (error == null) {
            IdentityMessage(say(R.string.msg_identity_saved))
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
            if (!chain.isRunningConfigCurrent(settings.chainForService())) {
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

    /**
     * Stops a delay run and keeps whatever it measured.
     *
     * Needed once a subscription can carry a thousand nodes: the run is minutes
     * long, and without this the only way out is to leave the screen and hope.
     */
    fun cancelChainTests() {
        chainJob?.cancel()
        chainJob = null
        mutableChainState.value = mutableChainState.value.copy(busy = false, testProgress = null)
        viewModelScope.launch {
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = mutableChainState.value.copy(
                nodes = reported.nodes,
                selected = reported.selected,
            )
        }
    }

    /** Switches the live chain, and remembers the choice for the next connect. */
    fun selectChainNode(settings: AppSettings, node: String) {
        if (chainJob?.isCompleted == false) return
        // Moved before anything blocking. Saving, telling mihomo and reading the
        // list back are three round trips, and until they finished the row the
        // user had just tapped looked unchanged -- which reads as a tap that
        // did not register, so people tap again.
        mutableChainState.value = mutableChainState.value.copy(selected = node)
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
    /**
     * Measures only the nodes the user ticked.
     *
     * Worth having separately on a large subscription: testing fifty takes the
     * best part of half a minute, and somebody comparing three of them should
     * not have to wait for the other forty-seven.
     */
    fun testChainNodes(only: List<String>) {
        if (chainJob?.isCompleted == false) return
        val supported = mutableChainState.value.nodes.filter { it.supported }.map { it.name }
        val names = only.filter { it in supported }
        if (names.isEmpty()) return
        runDelayTests(names)
    }

    fun testChainNodes() {
        if (chainJob?.isCompleted == false) return
        // Unsupported nodes are skipped rather than measured. A REALITY node
        // fails the handshake, so its delay test times out and reports the node
        // as slow when the engine simply cannot speak to it.
        val names = mutableChainState.value.nodes.filter { it.supported }.map { it.name }
        if (names.isEmpty()) return
        runDelayTests(names)
    }

    /**
     * Measures the nodes a handful at a time, showing results as they arrive.
     *
     * All at once was the bug: fifty delay tests fired together are fifty
     * concurrent requests through one tunnel, and the tunnel saturates. The
     * first few answered and the rest timed out, so most of the list came back
     * blank however long the wait was afterwards.
     *
     * Reading the list between batches also means the pings appear as they are
     * measured rather than all at the end, which on a long list is the
     * difference between progress and a frozen screen.
     */
    private fun runDelayTests(names: List<String>) {
        chainJob = viewModelScope.launch {
            var done = 0
            mutableChainState.value = mutableChainState.value.copy(
                busy = true,
                error = null,
                testProgress = 0 to names.size,
            )
            for (batch in names.chunked(DELAY_TEST_BATCH)) {
                withContext(Dispatchers.IO) { chain.testNodes(batch) }
                delay(DELAY_TEST_BATCH_MS)
                done += batch.size
                // Reading the list back is a JNI call returning every proxy as
                // JSON, so on a thousand nodes it is the expensive half of the
                // loop. Done every few batches rather than every one: often
                // enough to watch pings arrive, rarely enough not to dominate.
                val partial = if (done % (DELAY_TEST_BATCH * REFRESH_EVERY) == 0) {
                    withContext(Dispatchers.IO) { chain.nodes() }
                } else {
                    null
                }
                mutableChainState.value = mutableChainState.value.copy(
                    nodes = partial?.nodes ?: mutableChainState.value.nodes,
                    selected = partial?.selected ?: mutableChainState.value.selected,
                    testProgress = done to names.size,
                )
            }
            val reported = withContext(Dispatchers.IO) { chain.nodes() }
            mutableChainState.value = ChainState(
                available = chain.isAvailable,
                nodes = reported.nodes,
                selected = reported.selected,
            )
        }
    }

    /**
     * Forgets the endpoint entirely and looks for a new one.
     *
     * Three separate things remember an endpoint, and clearing one of them is
     * what makes this look like it did nothing: the pinned address in settings,
     * the results still listed on screen, and the transport the service saw
     * work last. A pin survives the network it was found on -- an address that
     * answered on home wifi is just an address that times out on mobile data --
     * and with fallback off there is nothing to move on to, so the user reads a
     * dead pin as the app being broken.
     *
     * The scan starts here rather than being left to the next connect so that
     * the reset has something to show for itself.
     */
    fun resetEndpoint(settings: AppSettings) {
        val cleared = settings.withoutPinnedEndpoint()
        save(cleared)
        AetherVpnService.forgetLastGoodTransport(getApplication())
        mutableEndpointScannerState.value = EndpointScannerState()
        scanEndpoints(cleared)
    }

    /**
     * Asks Tor which bridges to use here, and saves them.
     *
     * Through the running carrier when there is one. That is the part Tor
     * Browser cannot do and this app can: `bridges.torproject.org` is blocked
     * in most of the places its answer is wanted, and this app already has two
     * other ways out of exactly those places. Asked directly only when nothing
     * is up, which is the case where direct is likely to work anyway.
     *
     * The country is the network's, not the exit's. Asked through a tunnel, the
     * service would otherwise be told about Singapore and answer about
     * Singapore, which is useless to somebody in Iran.
     */
    fun fetchBridges(settings: AppSettings) {
        if (bridgeJob?.isActive == true) return
        mutableBridgesMessage.value = null
        mutableBridgesFetching.value = true
        bridgeJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val through = EngineStatusStore.status.value
                .takeIf { it.stage == EngineStage.CONNECTED }
                ?.carrierSocksPort
            val country = MoatClient.country(context)
            val result = MoatClient.recommendations(country, through)
            mutableBridgesFetching.value = false

            val recommendations = result.getOrElse { error ->
                EngineLog.record(LogLevel.WARN, "bridges", error.message ?: "fetch failed")
                mutableBridgesMessage.value = say(R.string.tor_bridges_failed)
                return@launch
            }
            // The first one Tor lists, because it lists them in the order it
            // recommends trying them for that country -- and the app can only
            // run one transport at a time.
            // Two different answers that a single failure message would blur.
            // An empty list is Tor saying this network needs no help -- which is
            // true of most of the world and is not a fault to report as one.
            val best = recommendations.firstOrNull { it.lines.isNotEmpty() }
            if (best == null) {
                mutableBridgesMessage.value = say(R.string.tor_bridges_none_recommended, country.uppercase())
                return@launch
            }
            EngineLog.record(
                LogLevel.INFO,
                "bridges",
                "tor recommends ${best.transport} for $country: ${best.lines.size} bridges",
            )
            save(
                settings.copy(
                    torBridge = TorBridge.CUSTOM,
                    torBridges = best.lines.joinToString("\n"),
                ),
            )
        }
    }

    fun clearBridgesMessage() {
        mutableBridgesMessage.value = null
    }

    fun scanEndpoints(settings: AppSettings) {
        if (!canRunEndpointOperation()) return
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.SCANNING,
                results = mutableEndpointScannerState.value.results,
                message = say(R.string.msg_scanning_routes, say(settings.transport.probedAs.label)),
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
                    message = say(R.string.msg_nothing_over_trying, say(base.transport.label), say(other.label)),
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
                            message = say(R.string.msg_scan_cancelled),
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
                            message = say(R.string.msg_scan_cancelled),
                        )
                    } else if (mutableEndpointScannerState.value.operation == EndpointOperation.SCANNING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            error = error.message ?: say(R.string.msg_scan_failed),
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
                error = say(validationError),
            )
            return
        }
        if (!canRunEndpointOperation()) return
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.TESTING,
                results = mutableEndpointScannerState.value.results,
                message = say(R.string.msg_testing_endpoint),
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
                        error = error.message ?: say(R.string.msg_endpoint_test_failed),
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
            message = say(R.string.msg_cancelling_scan),
        )
    }

    private fun canRunEndpointOperation(): Boolean {
        if (endpointJob?.isCompleted == false) return false
        if (EngineStatusStore.status.value.stage !in setOf(EngineStage.IDLE, EngineStage.ERROR)) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                error = say(R.string.msg_disconnect_before_scan),
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
        /**
         * How many nodes are measured at once.
         *
         * Small enough that one tunnel carries them without the tests starving
         * each other, large enough that a long list does not take all evening.
         */
        const val DELAY_TEST_BATCH = 16

        /** One batch's wait, now that it is not counted per node. */
        const val DELAY_TEST_BATCH_MS = 6_000L

        /** Batches between full re-reads of the list. */
        const val REFRESH_EVERY = 4

        /** One second: fast enough to read as live, slow enough to be free. */
        const val TRAFFIC_SAMPLE_MS = 1_000L
    }
}
