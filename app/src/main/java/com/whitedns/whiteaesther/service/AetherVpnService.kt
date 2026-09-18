package com.whitedns.whiteaesther.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.core.AppLocale
import com.whitedns.whiteaesther.MainActivity
import com.whitedns.whiteaesther.core.ChainConfig
import com.whitedns.whiteaesther.core.ChainController
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.NativeEngineListener
import com.whitedns.whiteaesther.core.NativeSocketProtector
import com.whitedns.whiteaesther.core.AetherCarrierClient
import com.whitedns.whiteaesther.core.CarrierClient
import com.whitedns.whiteaesther.core.CarrierStage
import com.whitedns.whiteaesther.core.PsiphonClient
import com.whitedns.whiteaesther.core.PsiphonConfig
import com.whitedns.whiteaesther.core.TorClient
import com.whitedns.whiteaesther.core.TorBridges
import com.whitedns.whiteaesther.core.TorConfig
import com.whitedns.whiteaesther.data.AutoOptions
import com.whitedns.whiteaesther.data.AutoPlanner
import com.whitedns.whiteaesther.data.AutoRoute
import com.whitedns.whiteaesther.data.AutoStep
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.Lane
import com.whitedns.whiteaesther.data.NetworkKey
import com.whitedns.whiteaesther.data.RoamAction
import com.whitedns.whiteaesther.data.Roaming
import com.whitedns.whiteaesther.data.RouteMemory
import com.whitedns.whiteaesther.data.TorBridge
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.SplitTunnel
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.data.SplitTunnelMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetAddress

class AetherVpnService : VpnService() {
    /**
     * The notification is the app's only face while it is in the background, so
     * it has to speak the language the rest of the app does. A service gets its
     * own context and none of the activity's, so the wrapping is repeated here
     * rather than inherited.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /**
     * A string in the language the app is set to *now*.
     *
     * attachBaseContext runs once, when the service is created, and a service
     * outlives the screen that started it: one created while the app was in
     * English kept English resources for the rest of the session, so the status
     * line under "متصل شدید" stayed in the language the user had already left.
     * Reading the choice per message costs a preferences lookup and removes the
     * question of when the service happened to be built.
     */
    private fun sayNow(resId: Int, vararg args: Any): String =
        AppLocale.wrap(applicationContext).getString(resId, *args)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private var sessionJob: Job? = null
    private var generation: Long = 0
    private var reconnectAttempt = 0
    // The configuration the user asked for, before any per-attempt
    // transport substitution, so retries never compound.
    private var baseConfigJson: String? = null
    private var chainJson: String? = null
    private var splitJson: String? = null
    /**
     * Which engine is carrying this session.
     *
     * Held rather than read from the config, because it is not the engine's
     * business: the JSON handed to the bridge describes MASQUE, and a
     * carrier that is not the engine never reaches the bridge at all.
     */
    private var carrier: Carrier = Carrier.AETHER

    /**
     * A second carrier for the first to dial through, when there is one.
     *
     * Held for the same reason as [carrier]: it is not the engine's
     * business, and two of the three carriers never reach the bridge.
     */
    private var secondCarrier: Carrier? = null

    /**
     * How Tor should reach its first hop this session.
     *
     * Held beside the carrier because it is part of how tor is started rather
     * than something it can be told afterwards: the choice becomes lines in a
     * torrc that tor reads once.
     */
    private var torBridge: TorBridge = TorBridge.NONE
    private var torBridges: String = ""
    private var psiphonRegion: String = ""
    private val chain by lazy { ChainController(this) }
    private var psiphonClient: PsiphonClient? = null
    private var torClient: TorClient? = null
    private var aetherCarrier: AetherCarrierClient? = null

    /**
     * The hops this session started, in the order it started them.
     *
     * Kept because teardown is the reverse: an inner hop dials through the
     * outer one, and stopping the outer first leaves the inner retrying into
     * a listener that has gone.
     */
    private val startedHops = mutableListOf<Pair<Carrier, CarrierClient>>()

    /**
     * The attempt whose collapse has already been dealt with.
     *
     * Hops fail together: the second one goes because the first did, moments
     * apart. Without this each watcher schedules its own reconnect, so one
     * failure spends two of the attempt budget and the user is told about
     * whichever hop happened to notice last rather than the one that went
     * first.
     */
    private var collapsedAttempt: Long = -1

    /**
     * Which dial of the hops the watchers and [collapsedAttempt] belong to.
     *
     * Not the generation. A retry reuses it -- scheduleReconnect hands the same
     * one back to runSession -- so a marker keyed on the generation stays set
     * for every attempt after the first, and the second failure of a session
     * was watched by nothing: the VPN stayed up, carrying nothing, with no
     * reconnect scheduled. This moves every time hops are dialled, so each
     * attempt collapses its own failures and no one else's.
     */
    private var hopAttempt: Long = 0

    /**
     * What the engine said the last time it failed during this session.
     *
     * Kept so the next lane can lead with the rung that answers it. The engine
     * now names a gateway demanding an Encrypted Client Hello rather than
     * reporting a stop with no reason, and that name is only worth having if
     * something acts on it.
     */
    private var lastEngineFailure: String? = null

    /** Registered for the life of the service; see [watchTheNetworkUnderneath]. */
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    /**
     * Whether this session has already bought the engine an identity over a
     * carrier that was working.
     *
     * Once is the whole idea. It costs a Cloudflare registration, the engine
     * keeps what it buys, and a second attempt in the same session would be
     * spending an allowance to learn something already on disk.
     */
    private var boughtIdentityBehindCarrier = false

    /**
     * The watchers of the current attempt's hops.
     *
     * Cancelled when the next attempt begins. Without that they accumulate one
     * collector per retry, each still subscribed to a client that was stopped
     * attempts ago.
     */
    private val hopWatchers = mutableListOf<Job>()

    /**
     * How far each hop has got, in the order the path dials them.
     *
     * Ordered because the order is the thing being reported: which hop is
     * still waiting, and which one the session is stuck behind.
     */
    private val hopStages = linkedMapOf<Carrier, CarrierStage>()

    /**
     * The user asked the app to find the way out itself.
     *
     * While set, [carrier] and [secondCarrier] stop being the user's choice and
     * become whatever the current step of the plan is running.
     */
    private var automatic = false

    /** This pass's plan; empty between passes, which is what starts a new one. */
    private var autoSteps: List<AutoStep> = emptyList()
    private var autoStepIndex = 0

    /** Which rung of the engine's transport ladder this step is on. */
    private var autoEngineAttempt = 0

    /** When the engine step's budget runs out. Zero once the leash has pulled it. */
    private var autoStepDeadline = 0L

    /** Bumped whenever a step starts or a route wins, so an older leash stands down. */
    @Volatile private var autoToken = 0L

    /** Set from the engine's callback thread the moment its route opens. */
    @Volatile private var autoEngineUp = false

    /** A route carried traffic this pass. What a later failure means depends on it. */
    @Volatile private var autoConnected = false

    /** The route the session is running, named on screen once it connects. */
    @Volatile private var autoRunningRoute: AutoRoute? = null

    private var autoPasses = 0

    /** When this search began, for the clock on the home screen. Zero when not searching. */
    @Volatile private var autoSearchStartedAt = 0L
    private var autoNetworkKey = RouteMemory.ANY_NETWORK
    private var autoMode: EngineMode? = null

    /**
     * How far each carrier has got this pass, in the order the pass tries them.
     *
     * Replaced rather than mutated: the engine's callback thread reads it when
     * it reports, and a map changing under that read is a crash on connect.
     */
    @Volatile private var autoStages: Map<Carrier, CarrierStage> = emptyMap()

    /** Routes being tried right now, for the one line that says so. */
    private val autoTrying = mutableListOf<AutoRoute>()

    /** The clients of those routes, so a race that has its winner can stop the rest at once. */
    private val autoInFlight = mutableMapOf<AutoRoute, CarrierClient>()

    /** Which race is running, and the last one settled; a late runner checks the two. */
    private var autoRaceId = 0L
    private var autoRaceSettled = -1L

    /** An engine session the leash had to leave behind, until it has gone. */
    private var staleEngine: Job? = null

    /**
     * Whether the current session can be cancelled rather than waited for.
     *
     * True only where cancelling is safe and waiting is not: a connected
     * carrier session parks until it is cancelled, and a race waits out head
     * starts measured in tens of seconds. The engine path is neither -- it
     * sits in a native call that only stopping the engine ends, and cancelling
     * it would skip the cleanup that runs when that call returns.
     */
    private var sessionCancellable = false

    /**
     * The carrier this session is using, or null when the engine is.
     *
     * Resolved once per session rather than branched on at each use: what
     * the session does with a carrier is the same whichever one it is, and
     * a `when` at every call site is a place for the two to drift apart.
     */
    private val carrierClient: CarrierClient?
        get() = when (carrier) {
            Carrier.AETHER -> aetherCarrier
            Carrier.PSIPHON -> psiphonClient
            // Rebuilt when the bridge changes rather than held: the choice is
            // part of how tor is started, not something it can be told later.
            Carrier.TOR -> torClient
        }

    /**
     * The carriers this session runs, the one the network sees first.
     *
     * One is the ordinary case. Two is a chain, and which way round is the
     * user's choice rather than something that could be decided here: what gets
     * out of a network is a property of that network.
     */
    private val hops: List<Carrier> get() = listOfNotNull(carrier, secondCarrier)

    override fun onCreate() {
        super.onCreate()
        AetherNotification.createChannel(this)
        watchTheNetworkUnderneath()
    }

    /**
     * Notices when the phone changes the network the tunnel is riding on.
     *
     * Nothing was watching. The network is read once per search and then held,
     * so a phone that moved from Wi-Fi to mobile data went on planning against
     * the network it had left -- leading with an endpoint proven somewhere else
     * and writing what it learned against the wrong key -- for as long as the
     * search lasted, which can be minutes.
     *
     * Not the default network: while a session is up the default is this app's
     * own interface, which says nothing about what is underneath it. This asks
     * for networks that offer the internet and are not a VPN, the same ones
     * [NetworkIdentity] looks at.
     */
    private fun watchTheNetworkUnderneath() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkMayHaveChanged()
            override fun onLost(network: Network) = networkMayHaveChanged()
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = networkMayHaveChanged()
        }
        runCatching { connectivity.registerNetworkCallback(request, watch) }
            .onSuccess { networkWatch = watch }
            .onFailure {
                EngineLog.record(
                    LogLevel.WARN,
                    "auto",
                    "could not watch for network changes: ${it.message}",
                )
            }
    }

    /**
     * Called for every capability change, so it has to be cheap and sure.
     *
     * Only a different network counts. Android reports a great deal that is not
     * a move -- signal strength, metering, validation -- and acting on those
     * would restart a search for nothing.
     *
     * A session that is carrying traffic is left alone. A tunnel often survives
     * a roam, and tearing down a working one to re-plan would cost the user the
     * connection they have to fix a plan they are not using. What is corrected
     * immediately is the key everything is recorded against, and the plan of a
     * search still in progress -- which is being made against a network the
     * phone has left.
     */
    private fun networkMayHaveChanged() {
        val now = NetworkIdentity.current(this).key ?: return
        if (now == autoNetworkKey) return

        serviceScope.launch {
            commandMutex.withLock {
                val was = autoNetworkKey
                val action = Roaming.actionFor(
                    was = was,
                    now = now,
                    connected = autoConnected,
                    searching = autoSteps.isNotEmpty(),
                )
                if (action == RoamAction.Ignore) return@withLock

                autoNetworkKey = now
                EngineLog.record(LogLevel.INFO, "auto", "the network changed from $was to $now")
                if (action != RoamAction.Replan) return@withLock

                EngineLog.record(
                    LogLevel.INFO,
                    "auto",
                    "replanning for $now; the search so far was for $was",
                )
                autoSteps = emptyList()
                autoStepIndex = 0
                lastEngineFailure = null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var restartPolicy = START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> stopFromUser()
            ACTION_LIFT_BLOCK -> {
                dropBlackhole()
                blockAfterStop = false
                publish(EngineStatus())
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (configJson == null) {
                    reportError(null, sayNow(R.string.err_settings_missing))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val chainSettings = intent.getStringExtra(EXTRA_CHAIN)
                val splitSettings = intent.getStringExtra(EXTRA_SPLIT)
                carrier = Carrier.entries
                    .firstOrNull { it.wireName == intent.getStringExtra(EXTRA_CARRIER) }
                    ?: Carrier.AETHER
                secondCarrier = Carrier.entries
                    .firstOrNull { it.wireName == intent.getStringExtra(EXTRA_SECOND_CARRIER) }
                torBridge = TorBridge.entries
                    .firstOrNull { it.wireName == intent.getStringExtra(EXTRA_TOR_BRIDGE) }
                    ?: TorBridge.NONE
                torBridges = intent.getStringExtra(EXTRA_TOR_BRIDGES).orEmpty()
                psiphonRegion = intent.getStringExtra(EXTRA_PSIPHON_REGION).orEmpty()
                automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
                // Held for the life of the session: giveUp runs long after
                // this, and is not a place that can read DataStore.
                blockOnFailure = intent.getBooleanExtra(EXTRA_KILL_SWITCH, false)
                blockAfterStop = intent.getBooleanExtra(EXTRA_STRICT_KILL, false)
                if (JSONObject(configJson).optString("mode") == "tun") {
                    preferences.edit {
                        putString(LAST_TUN_CONFIG, configJson)
                        putString(LAST_CHAIN_CONFIG, chainSettings)
                        putString(LAST_SPLIT_CONFIG, splitSettings)
                        putString(LAST_CARRIER, carrier.wireName)
                        putString(LAST_SECOND_CARRIER, secondCarrier?.wireName)
                        putString(LAST_TOR_BRIDGE, torBridge.wireName)
                        putString(LAST_TOR_BRIDGES, torBridges)
                        putString(LAST_PSIPHON_REGION, psiphonRegion)
                        putBoolean(LAST_AUTOMATIC, automatic)
                        // Part of the session, not of the request that started
                        // it. Without these two the START_STICKY restart after
                        // the process was killed came back with both false, so
                        // a tunnel the user had told to block on failure
                        // stopped blocking -- silently, and only at the moment
                        // it mattered.
                        putBoolean(LAST_KILL_SWITCH, blockOnFailure)
                        putBoolean(LAST_STRICT_KILL, blockAfterStop)
                    }
                    restartPolicy = START_STICKY
                } else {
                    preferences.edit {
                        remove(LAST_TUN_CONFIG)
                        remove(LAST_CHAIN_CONFIG)
                        remove(LAST_SPLIT_CONFIG)
                        remove(LAST_CARRIER)
                        remove(LAST_SECOND_CARRIER)
                        remove(LAST_TOR_BRIDGE)
                        remove(LAST_TOR_BRIDGES)
                        remove(LAST_PSIPHON_REGION)
                        remove(LAST_AUTOMATIC)
                        remove(LAST_KILL_SWITCH)
                        remove(LAST_STRICT_KILL)
                    }
                }
                startForegroundNow(sayNow(R.string.status_preparing_connection), sayNow(R.string.status_validating_engine))
                replaceSession(configJson, chainSettings, splitSettings)
            }
            else -> {
                val configJson = preferences.getString(LAST_TUN_CONFIG, null)
                if (configJson == null) {
                    stopSelf(startId)
                } else {
                    carrier = Carrier.entries
                        .firstOrNull { it.wireName == preferences.getString(LAST_CARRIER, null) }
                        ?: Carrier.AETHER
                    secondCarrier = Carrier.entries
                        .firstOrNull {
                            it.wireName == preferences.getString(LAST_SECOND_CARRIER, null)
                        }
                    torBridge = TorBridge.entries
                        .firstOrNull { it.wireName == preferences.getString(LAST_TOR_BRIDGE, null) }
                        ?: TorBridge.NONE
                    torBridges = preferences.getString(LAST_TOR_BRIDGES, null).orEmpty()
                    psiphonRegion = preferences.getString(LAST_PSIPHON_REGION, null).orEmpty()
                    automatic = preferences.getBoolean(LAST_AUTOMATIC, false)
                    blockOnFailure = preferences.getBoolean(LAST_KILL_SWITCH, false)
                    blockAfterStop = preferences.getBoolean(LAST_STRICT_KILL, false)
                    restartPolicy = START_STICKY
                    startForegroundNow(sayNow(R.string.status_restoring), sayNow(R.string.status_reconnecting_tun))
                    replaceSession(
                        configJson,
                        preferences.getString(LAST_CHAIN_CONFIG, null),
                        preferences.getString(LAST_SPLIT_CONFIG, null),
                    )
                }
            }
        }
        return restartPolicy
    }

    override fun onRevoke() {
        preferences.edit {
            remove(LAST_TUN_CONFIG)
            remove(LAST_CHAIN_CONFIG)
            remove(LAST_SPLIT_CONFIG)
        }
        stopFromUser(sayNow(R.string.err_permission_revoked))
        super.onRevoke()
    }

    override fun onDestroy() {
        networkWatch?.let { watch ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(watch)
            }
        }
        networkWatch = null
        dropBlackhole()
        generation += 1
        runCatching { chain.stop() }
        runCatching { stopCarrier() }
        NativeAetherBridge.stop()
        runCatching { NativeAetherBridge.setSocketProtector(null) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun replaceSession(
        configJson: String,
        chainSettings: String?,
        splitSettings: String?,
    ) {
        serviceScope.launch {
            commandMutex.withLock {
                generation += 1
                newHopAttempt()
                reconnectAttempt = 0
                lastEngineFailure = null
                boughtIdentityBehindCarrier = false
                // A new connect is a new search, planned for whichever network
                // the phone is on now.
                autoSteps = emptyList()
                autoPasses = 0
                autoConnected = false
                autoSearchStartedAt = 0L
                baseConfigJson = configJson
                chainJson = chainSettings
                splitJson = splitSettings
                NativeAetherBridge.stop()
                runCatching { chain.stop() }
                runCatching { stopCarrier() }
                // Cancelled where that is safe, not only waited for. A connected
                // carrier session parks until it is cancelled, so joining it alone
                // never returned -- with this lock held, so the new session never
                // started and neither did any command after it.
                if (sessionCancellable) sessionJob?.cancel()
                sessionJob?.join()
                val sessionGeneration = generation
                sessionJob = serviceScope.launch {
                    runSession(configJson, sessionGeneration)
                }
            }
        }
    }

    private suspend fun runSession(requestedConfig: String, sessionGeneration: Long) {
        sessionCancellable = false
        val mode = runCatching {
            when (JSONObject(requestedConfig).getString("mode")) {
                "proxy" -> EngineMode.PROXY
                else -> EngineMode.TUN
            }
        }.getOrDefault(EngineMode.TUN)

        val chainSettings = ChainSettings.decode(chainJson)
        val splitTunnel = SplitTunnel.decode(splitJson)

        // Automatic decides what carries the session before anything else
        // looks. A race runs here and returns; the engine step goes on down the
        // path below exactly as a session carried by Aether alone always has,
        // on the transport the step chose.
        val configJson = if (automatic) {
            when (val step = autoStepFor(mode, sessionGeneration) ?: return) {
                is AutoStep.Race -> {
                    runAutoRace(step, requestedConfig, mode, chainSettings, splitTunnel, sessionGeneration)
                    return
                }
                is AutoStep.Engine -> {
                    carrier = Carrier.AETHER
                    secondCarrier = null
                    autoEngineConfig(requestedConfig, step)
                }
            }
        } else {
            requestedConfig
        }

        // A chain goes down the carrier path even when Aether is one of its
        // links. Aether on its own does not: the engine takes the interface
        // itself, which is faster and fewer moving parts than routing it through
        // mihomo into a loopback listener, and that path is unchanged.
        if (carrier != Carrier.AETHER || secondCarrier != null) {
            runCarrierSession(configJson, mode, chainSettings, splitTunnel, sessionGeneration)
            return
        }

        val useChain =
            chainSettings.enabled && resolveChainUsage(chainSettings, mode, sessionGeneration)
        if (chainSettings.enabled && !useChain) return
        // Behind the chain the engine stops owning the interface and becomes the
        // SOCKS5 listener mihomo dials its nodes through. Rewritten here rather
        // than stored, so a reconnect still starts from what the user asked for.
        // Automatic is a service-level policy: the engine only ever receives a
        // real transport. Resolved here so the very first attempt is already on
        // a rung of the ladder rather than on a name the bridge would reject.
        //
        // Only while it is still unresolved. scheduleReconnect has already
        // chosen the rung for a retry and put its name on screen; resolving its
        // answer a second time ran the h2/h3 alternation over it and, on odd
        // attempts, flipped it. The notification said H2 while the engine was
        // handed H3 -- so on a network that had just lost a UDP flow, four
        // attempts in a row went back to UDP, and TCP on 443, the rung that
        // exists to survive exactly that, was not reached until the sixth.
        val resolved =
            if (transportOf(configJson) == "auto") {
                configForAttempt(configJson, reconnectAttempt)
            } else {
                configJson
            }
        val engineConfig = if (useChain) withEngineMode(resolved, EngineMode.PROXY) else resolved

        // Record what this attempt is actually configured with. Without it a
        // diagnostics report cannot answer the question it was collected for --
        // which transport carried the session, and whether the anti-blocking
        // options were on.
        EngineLog.record(LogLevel.INFO, "config", sessionSummary(engineConfig))
        startEngineLogPump(sessionGeneration)
        if (useChain) {
            EngineLog.record(LogLevel.INFO, "chain", "exit chain on, engine dropped to SOCKS")
        }
        publish(
            EngineStatus(EngineStage.PREPARING, mode, message = preparingMessage(engineConfig)),
        )

        // Dialling the nodes directly takes the engine out of the path entirely,
        // so it is not started: preparing it would scan for a Cloudflare endpoint
        // that nothing would then use, and on a network where MASQUE is dead --
        // the case this mode exists for -- that scan is exactly what never
        // finishes.
        val engineInPath = !useChain || chainSettings.throughTunnel

        // Before preparing, not after. prepare() is where the endpoint hunt
        // happens -- up to several thousand UDP probes -- and an unprotected
        // probe socket is routed by whatever tunnel is currently up. On a
        // reconnect, or when the user switches protocol without disconnecting,
        // that is the tunnel this session is replacing: every probe goes into a
        // dying interface, nothing answers, and the scan reports the network as
        // dead when it was never reached. protect() fails open when no
        // protector is installed, so this was silent.
        if (mode == EngineMode.TUN) {
            if (prepare(this) != null) {
                reportError(mode, sayNow(R.string.err_permission_required))
                finishIfCurrent(sessionGeneration)
                return
            }
            runCatching {
                NativeAetherBridge.setSocketProtector(
                    NativeSocketProtector { fd -> protect(fd) },
                )
            }.onFailure { error ->
                reportError(mode, "Socket protection failed: ${error.message}")
                finishIfCurrent(sessionGeneration)
                return
            }
        }

        val prepared = if (engineInPath) {
            withContext(Dispatchers.IO) {
                NativeAetherBridge.prepare(engineConfig)
            }.getOrElse { error ->
                val reason = error.message ?: sayNow(R.string.err_native_prepare)
                // Retrying a refused registration is not merely useless, it is
                // what keeps it refused: every attempt is another registration
                // against the endpoint that just rate-limited this address.
                // Stop, and say what will actually help.
                if (isConclusive(reason)) {
                    EngineLog.record(LogLevel.ERROR, "identity", reason)
                    // Conclusive for the engine, not for the session: the routes
                    // Automatic still has do not ask Cloudflare for anything.
                    if (automatic) {
                        autoStepDeadline = 0L
                        scheduleReconnect(configJson, sessionGeneration, mode, reason)
                        return
                    }
                    reportError(mode, reason)
                    finishIfCurrent(sessionGeneration)
                    return
                }
                scheduleReconnect(configJson, sessionGeneration, mode, reason)
                return
            }
        } else {
            null
        }

        if (sessionGeneration != generation) return

        var tun: ParcelFileDescriptor? = null
        if (mode == EngineMode.TUN) {
            // Consent and the protector are already in place: both had to
            // happen before the endpoint hunt, above.
            tun = establishTun(
                prepared?.ipv4.orEmpty(),
                prepared?.ipv6.orEmpty(),
                useChain,
                transportOf(engineConfig),
                splitTunnel,
            )
            if (tun == null) {
                reportError(mode, sayNow(R.string.err_no_interface))
                finishIfCurrent(sessionGeneration)
                return
            }
        } else {
            clearSocketProtector(sessionGeneration)
        }

        val peer = prepared?.peer
        if (engineInPath) {
            publish(
                EngineStatus(EngineStage.CONNECTING, mode, peer, sayNow(R.string.status_validating_route)),
            )
            updateNotification(mode, sayNow(R.string.status_connecting_to, peer.orEmpty()))
        } else {
            publish(
                EngineStatus(EngineStage.CONNECTING, mode, null, sayNow(R.string.status_starting_chain)),
            )
            updateNotification(mode, sayNow(R.string.status_starting_chain))
        }

        // With the chain on, the engine coming up is the halfway point rather
        // than the destination, so the two paths report different things.
        val tunnelUp = CompletableDeferred<Unit>()
        val listener = NativeEngineListener {
            // First, and straight from the engine's thread: the leash reads this
            // to know the engine got there, whatever happens after it.
            autoEngineUp = true
            reconnectAttempt = 0
            EngineLog.record(
                LogLevel.INFO,
                "tunnel",
                "up on ${transportOf(engineConfig).uppercase()}",
            )
            rememberWorkingTransport(engineConfig)
            if (useChain) {
                tunnelUp.complete(Unit)
            } else {
                reportConnected(mode, peer, connectedMessage(mode, engineConfig))
            }
        }

        if (!useChain) {
            // Detached at the handoff and not a line earlier. From here the
            // engine owns the descriptor and closes it when it stops; before
            // here it is ours, and a path that gives up without closing it
            // leaves the interface registered with the system after the session
            // it belonged to has ended.
            val result = withContext(Dispatchers.IO) {
                NativeAetherBridge.run(engineConfig, peer.orEmpty(), tun?.detachFd() ?: -1, listener)
            }
            tun?.close()
            clearSocketProtector(sessionGeneration)

            if (sessionGeneration != generation) return
            scheduleReconnect(
                configJson,
                sessionGeneration,
                mode,
                result.error ?: sayNow(R.string.err_route_closed),
            )
            return
        }

        runChainSession(
            configJson = configJson,
            engineConfig = engineConfig,
            chainSettings = chainSettings,
            peer = peer,
            tun = tun,
            listener = listener,
            tunnelUp = tunnelUp,
            mode = mode,
            sessionGeneration = sessionGeneration,
        )
    }

    /**
     * Runs a carrier that is not the engine.
     *
     * Shorter than the engine path because there is no endpoint to find, no
     * identity to provision and no MASQUE handshake to validate: the carrier
     * either produces a working SOCKS5 listener or it does not, and everything
     * after that is mihomo turning the interface into connections through it.
     *
     * The order is the same one [runChainSession] is careful about, and for the
     * same reason. The interface is raised first because raising it needs the
     * user's consent and that is worth failing on early; but it is handed to
     * mihomo last, after the carrier is up and the rules are written, because
     * mihomo with no configuration routes everything DIRECT and a DIRECT route
     * from this process leaves the phone in the clear.
     */
    /**
     * Stops every carrier, not merely the current one.
     *
     * The setting can change between one session and the next, and the process
     * that was carrying the last one does not stop merely because nothing is
     * pointed at it any more. Stopping only [carrierClient] would leave a tunnel
     * running, and paying for it, behind a session that has moved on.
     */
    /**
     * How long to give this carrier before calling it a failure.
     *
     * Not one number for all of them. Psiphon races a dozen protocols, and on
     * a filtered network that race can take minutes -- the two minutes this
     * used to allow was shorter than users saw Psiphon's own app need; tor fetches a consensus and builds a
     * circuit through three relays, and on a filtered network spends most of
     * that working out which directory authorities it can reach. A timeout set
     * for the first would report the second broken for working normally.
     */
    private fun carrierWaitMs(hop: Carrier): Long = when (hop) {
        Carrier.TOR -> TorConfig.bootstrapTimeoutMs(torBridge)
        Carrier.AETHER -> TUNNEL_WAIT_MS
        Carrier.PSIPHON -> PSIPHON_WAIT_MS
    }

    private fun stopCarrier() {
        startedHops.asReversed().forEach { (_, client) -> runCatching { client.stop() } }
        startedHops.clear()
        // And whatever an earlier session left running. The setting changes
        // between sessions, and a process nothing points at any more does not
        // stop merely because of that.
        runCatching { psiphonClient?.stop() }
        runCatching { torClient?.stop() }
        runCatching { aetherCarrier?.stop() }
    }

    /**
     * Whether Android is refusing traffic that does not go through a VPN.
     *
     * A question about the carriers, not the engine, because of where they
     * run. The engine protects its own sockets. Psiphon and Tor run in
     * processes of their own, cannot reach protect(), and get out only because
     * this package is excluded from its own interface -- and under lockdown
     * Android may block an excluded app outright, which leaves the carrier with
     * no network and nothing in its own logs to say so.
     */
    private fun lockdownHint(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled) {
            sayNow(R.string.err_carrier_lockdown_hint)
        } else {
            null
        }

    /**
     * A carrier failure, with whatever this app knows that might explain it.
     *
     * Two things, both of which a user can change and neither of which the
     * carrier itself can see: lockdown, and a Psiphon exit country, which
     * tunnel-core treats as the only country to try rather than a preference.
     */
    private fun withCarrierHint(reason: String): String {
        val hints = listOfNotNull(
            lockdownHint(),
            psiphonRegion.takeIf { Carrier.PSIPHON in hops && it.isNotBlank() }
                ?.let { region ->
                    val code = region.uppercase(java.util.Locale.US)
                    // Named separately when Psiphon has listed its countries
                    // and this is not among them. tunnel-core treats an
                    // unreachable region as a reason to fail rather than to
                    // substitute, and each attempt has a five-minute window of
                    // its own -- so without being told, someone can wait out
                    // eight retries on a country that was never on offer.
                    val known = PsiphonConfig.availableRegions(this)
                    if (known.isNotEmpty() && region !in known) {
                        sayNow(R.string.err_psiphon_region_unlisted, code)
                    } else {
                        sayNow(R.string.err_psiphon_region_hint, code)
                    }
                },
        )
        return if (hints.isEmpty()) reason else reason + " \u2014 " + hints.joinToString(" ")
    }

    /**
     * A carrier ready to start, configured to dial through [upstream].
     *
     * Built rather than reused, for all three and for the same reason: an exit
     * country, a bridge, an upstream to dial through are each read once when the
     * carrier starts. A client built for the previous choice starts with the
     * previous choice and reports success.
     */
    private fun buildCarrier(hop: Carrier, configJson: String, upstream: Int): CarrierClient =
        when (hop) {
            Carrier.PSIPHON -> {
                psiphonClient?.let { runCatching { it.stop() } }
                PsiphonClient(this, psiphonRegion, upstream).also { psiphonClient = it }
            }

            Carrier.TOR -> {
                torClient?.let { runCatching { it.stop() } }
                TorClient(this, bridgeBehind(upstream), torBridges, upstream)
                    .also { torClient = it }
            }

            Carrier.AETHER -> {
                aetherCarrier?.let { runCatching { it.stop() } }
                AetherCarrierClient(serviceScope, carrierEngineConfig(configJson, upstream))
                    .also { aetherCarrier = it }
            }
        }

    /**
     * The engine's configuration when the engine is a hop rather than the whole
     * session.
     *
     * Proxy mode always, because what this hop hands on is a listener. And
     * behind another carrier, H2: the engine consults an upstream proxy for
     * registration, for the API, for the endpoint scan and for the H2 transport,
     * and for nothing else. H3 and WireGuard are datagrams and would dial
     * straight past the hop in front -- not a slower route, but a session
     * leaving by the address the chain was built to hide.
     */
    private fun carrierEngineConfig(configJson: String, upstream: Int): String {
        // Automatic is not a transport the engine accepts -- it refuses the name
        // outright -- so a first hop on it is resolved here, the way the engine
        // path resolves it. Unresolved, every chain that starts with Aether
        // failed its first attempt and only connected on the retry.
        val resolved = if (transportOf(configJson) == "auto") {
            configForAttempt(configJson, reconnectAttempt)
        } else {
            configJson
        }
        val proxyMode = withEngineMode(resolved, EngineMode.PROXY)
        if (upstream <= 0) return proxyMode
        return runCatching {
            JSONObject(proxyMode)
                .put("upstreamProxy", "socks5://127.0.0.1:$upstream")
                .put("transport", TunnelProtocol.H2.wireName)
                .toString()
        }.getOrDefault(proxyMode)
    }

    /**
     * Snowflake's first leg is WebRTC, and a SOCKS5 CONNECT carries no
     * datagrams, so it cannot be reached from behind another carrier. Say so and
     * connect directly, rather than starting a tor that never bootstraps and
     * cannot explain why.
     */
    private fun bridgeBehind(upstream: Int): TorBridge =
        if (upstream > 0 && torBridge == TorBridge.SNOWFLAKE) {
            EngineLog.record(
                LogLevel.WARN,
                "carrier",
                "snowflake cannot run behind another carrier; connecting to tor directly",
            )
            TorBridge.NONE
        } else {
            torBridge
        }

    /**
     * The path, written the way the user chose it.
     *
     * Naming only the first carrier would be true and useless: with two hops
     * the interesting fact is which order is currently carrying the session,
     * because that is the thing the user is about to change.
     */
    private fun pathLabel(): String =
        hops.joinToString(sayNow(R.string.carrier_path_join)) { sayNow(it.label) }

    /**
     * Watches one hop for the rest of the session.
     *
     * Started as soon as the hop is up, not once the whole path is assembled.
     * Bringing up the hop in front of it can take minutes -- an endpoint scan, a
     * tor bootstrap -- and a carrier that dies during that wait used to go
     * unnoticed: the session went on dialling a listener that had gone, and the
     * only symptom was a retry counter climbing against a proxy that was no
     * longer there.
     */
    private fun watchHop(
        hop: Carrier,
        client: CarrierClient,
        configJson: String,
        mode: EngineMode,
        sessionGeneration: Long,
        attempt: Long,
    ) {
        hopWatchers += serviceScope.launch {
            client.state.collect { snapshot ->
                if (sessionGeneration != generation || attempt != hopAttempt) return@collect
                if (snapshot.stage != CarrierStage.FAILED && snapshot.stage != CarrierStage.STOPPED) {
                    return@collect
                }
                if (collapsedAttempt == attempt) return@collect
                collapsedAttempt = attempt
                markHop(hop, snapshot.stage)

                val reason = hopFailure(hop, snapshot.failure ?: sayNow(R.string.err_carrier_stopped))
                EngineLog.record(LogLevel.ERROR, "carrier", reason)
                runCatching { chain.stop() }
                stopCarrier()
                scheduleReconnect(configJson, sessionGeneration, mode, reason)
            }
        }
    }

    /**
     * Retires the previous attempt's watchers and opens a new attempt.
     *
     * Both halves matter. Clearing the marker alone would let a watcher from
     * the attempt before this one report a failure the retry has already moved
     * past; cancelling alone would leave the marker set and the new hops
     * unwatched.
     *
     * @return the token every watcher of this attempt is scoped to.
     */
    private fun newHopAttempt(): Long {
        hopWatchers.forEach { it.cancel() }
        hopWatchers.clear()
        hopAttempt += 1
        collapsedAttempt = -1
        return hopAttempt
    }

    /**
     * The path as the screen should show it, or nothing.
     *
     * Nothing for a single carrier: a one-line path is a label the user already
     * read on the card they set it from.
     */
    private fun pathStatus(): List<HopStatus> =
        if (hopStages.size < 2) emptyList() else hopStages.map { HopStatus(it.key, it.value) }

    private fun markHop(hop: Carrier, stage: CarrierStage) {
        hopStages[hop] = stage
    }

    /**
     * A failure with the hop that produced it named.
     *
     * With one carrier the name is noise. With two it is the whole message: the
     * user is about to decide which end to change, and "the carrier failed" does
     * not say which end that is.
     */
    private fun hopFailure(hop: Carrier, reason: String): String =
        if (hops.size > 1) "${sayNow(hop.label)}: $reason" else reason

    private suspend fun runCarrierSession(
        configJson: String,
        mode: EngineMode,
        chainSettings: ChainSettings,
        splitTunnel: SplitTunnel,
        sessionGeneration: Long,
    ) {
        val name = hops.joinToString(" -> ") { it.wireName }
        EngineLog.record(LogLevel.INFO, "carrier", "carrying this session on $name")
        if (lockdownHint() != null) {
            EngineLog.record(LogLevel.WARN, "carrier", "always-on VPN lockdown is on; $name may have no network")
        }
        startEngineLogPump(sessionGeneration)

        // Whole-device only, and refused rather than quietly substituted. In
        // proxy mode the app's own listener is what applications are pointed
        // at, and this carrier has no listener of ours to offer -- Psiphon's is
        // on a port it chose, without the validation or the LAN rules that
        // listener carries. Starting anyway would leave the user pointed at a
        // port that answers nothing.
        if (mode != EngineMode.TUN) {
            reportError(mode, sayNow(R.string.err_carrier_whole_device_only))
            finishIfCurrent(sessionGeneration)
            return
        }

        // The one build-time failure worth naming precisely. Without the chain
        // library there is nothing that can turn an interface into connections,
        // so this carrier cannot run at all -- and saying "not available in this
        // build" beats an interface that comes up and carries nothing.
        if (!chain.isAvailable) {
            reportError(mode, sayNow(R.string.err_carrier_needs_chain))
            finishIfCurrent(sessionGeneration)
            return
        }

        publish(
            EngineStatus(EngineStage.PREPARING, mode, message = sayNow(R.string.status_starting_carrier)),
        )
        updateNotification(mode, sayNow(R.string.status_starting_carrier))

        if (prepare(this) != null) {
            reportError(mode, sayNow(R.string.err_permission_required))
            finishIfCurrent(sessionGeneration)
            return
        }

        // forChain, because that is exactly what this is from the interface's
        // point of view: mihomo owns it, the addresses are its, and this
        // package is excluded from it. That exclusion is not a nicety here --
        // the carrier runs in another process and protect() cannot reach its
        // sockets, so the interface not carrying our own uid is the only thing
        // keeping the carrier's traffic out of the tunnel it is building.
        val tun = establishTun("", "", forChain = true, transport = name, splitTunnel = splitTunnel)
        if (tun == null) {
            reportError(mode, sayNow(R.string.err_no_interface))
            finishIfCurrent(sessionGeneration)
            return
        }
        // Deliberately not detached yet. Until mihomo takes the descriptor it
        // belongs to this function, and each of the four ways out below has to
        // close it. A carrier that fails to start used to leave its interface
        // behind, so a handful of failed attempts left the system showing a VPN
        // over a session that had ended.
        if (sessionGeneration != generation) {
            stopCarrier()
            runCatching { tun.close() }
            return
        }

        // One hop at a time, each dialling through the one before it. The last
        // port is what mihomo routes the interface into; the ones before exist
        // only so that the next hop has somewhere to dial.
        startedHops.clear()
        hopStages.clear()
        hops.forEach { hopStages[it] = CarrierStage.STOPPED }
        val attempt = newHopAttempt()
        var port = 0
        for (hop in hops) {
            markHop(hop, CarrierStage.CONNECTING)
            publish(
                EngineStatus(
                    EngineStage.CONNECTING,
                    mode,
                    null,
                    sayNow(R.string.status_carrier_connecting, sayNow(hop.label)),
                    path = pathStatus(),
                ),
            )
            updateNotification(mode, sayNow(R.string.status_carrier_connecting, sayNow(hop.label)))

            val upstream = port
            val client = buildCarrier(hop, configJson, port)
            startedHops += hop to client

            port = client.start(carrierWaitMs(hop)).getOrElse { error ->
                markHop(hop, CarrierStage.FAILED)
                val reason = hopFailure(hop, withCarrierHint(error.message ?: sayNow(R.string.err_carrier_failed)))
                EngineLog.record(LogLevel.ERROR, "carrier", reason)
                stopCarrier()
                runCatching { tun.close() }
                if (sessionGeneration != generation) return
                scheduleReconnect(configJson, sessionGeneration, mode, reason)
                return
            }

            if (sessionGeneration != generation) {
                stopCarrier()
                runCatching { tun.close() }
                return
            }

            markHop(hop, CarrierStage.CONNECTED)
            EngineLog.record(LogLevel.INFO, "carrier", "${hop.wireName} is up on 127.0.0.1:$port")
            // A first hop dials the network itself, so the framing it came up
            // on is a fact about this network -- the one the engine path
            // records too, and what the next attempt starts with. Behind
            // another hop the framing is forced, and says nothing.
            if (hop == Carrier.AETHER && upstream == 0) {
                rememberWorkingTransport(carrierEngineConfig(configJson, upstream))
            }
            watchHop(hop, client, configJson, mode, sessionGeneration, attempt)
        }
        publish(
            EngineStatus(EngineStage.CONNECTING, mode, null, sayNow(R.string.status_starting_chain)),
        )

        val failure = withContext(Dispatchers.IO) {
            chain.startCarrier(
                settings = chainSettings,
                socksPort = port,
                // Only the engine's listener takes datagrams; Psiphon's and
                // Tor's refuse them. Declaring it either way is not cosmetic: a
                // proxy that says it takes datagrams and then drops them makes
                // DNS and QUIC hang, while one that refuses them makes both
                // fall back within a round trip.
                // The last hop is the one carrying traffic out, so it is the
                // one whose answer this is.
                udp = hops.last().carriesUdp,
                tunFd = tun.detachFd(),
            )
        }
        if (failure != null) {
            EngineLog.record(LogLevel.ERROR, "chain", failure)
            // Claimed before the hops are stopped: each of them is watched, and
            // a watcher seeing its hop stop would report this same failure a
            // second time and spend a second attempt on it.
            collapsedAttempt = attempt
            runCatching { chain.stop() }
            stopCarrier()
            if (sessionGeneration != generation) return
            scheduleReconnect(configJson, sessionGeneration, mode, failure)
            return
        }

        serviceScope.launch {
            while (sessionGeneration == generation) {
                delay(EVENT_DRAIN_MS)
                withContext(Dispatchers.IO) { chain.collectEvents() }
            }
        }

        reconnectAttempt = 0
        reportConnected(
            mode,
            null,
            sayNow(R.string.status_carrier_carries, pathLabel()),
            carrierSocksPort = port,
        )

        // Watched rather than assumed. The carrier is in another process and can
        // be killed on its own -- by the system reclaiming memory, or by its own
        // tunnel giving up -- and mihomo would keep the interface up dialling a
        // listener that has gone, which the phone experiences as connected and
        // carrying nothing.
        // Every hop has been watched since it came up, so there is nothing left
        // to collect here. Parking keeps this coroutine alive for as long as the
        // session it represents, which is what replaceSession cancels.
        sessionCancellable = true
        awaitCancellation()
    }

    // ------------------------------------------------------------ automatic ----

    /**
     * The step this session should run, starting a new pass when there is none.
     *
     * A pass begins with the network. An automatic connect on a phone with no
     * network at all would spend every route it has confirming that, and then
     * say nothing worked -- when the one thing to do was turn the Wi-Fi on. So
     * it waits for one, and says that it is waiting.
     */
    private suspend fun autoStepFor(mode: EngineMode, sessionGeneration: Long): AutoStep? {
        autoMode = mode
        if (autoSteps.isEmpty()) {
            // Once per search, not per pass: the clock is for the person
            // waiting, and to them a second pass is still the same wait.
            if (autoSearchStartedAt == 0L) autoSearchStartedAt = System.currentTimeMillis()
            if (!awaitNetwork(mode, sessionGeneration)) return null
            autoNetworkKey = NetworkIdentity.current(this).key ?: RouteMemory.ANY_NETWORK
            val remembered = RouteMemory.recall(
                preferences.getString(AUTO_ROUTES, null),
                autoNetworkKey,
                System.currentTimeMillis(),
            )
            val options = autoOptions(mode)
            autoSteps = AutoPlanner.plan(remembered, options)
            autoStages = AutoPlanner.offeredRoutes(options)
                .map { it.carrier }
                .distinct()
                .associateWith { CarrierStage.STOPPED }
            autoConnected = false
            autoRunningRoute = null
            EngineLog.record(
                LogLevel.INFO,
                "auto",
                "network $autoNetworkKey: " +
                    (remembered?.let { "${it.wireName} worked here before" } ?: "nothing remembered"),
            )
            enterStep(0, sessionGeneration)
        }
        val step = autoSteps.getOrNull(autoStepIndex) ?: return null
        if (step is AutoStep.Engine) {
            // One engine at a time. A session the leash had to leave behind may
            // still be inside a call that has not yet heard the stop, and the
            // reference is cleared only once it really has finished: the wait
            // below can expire with the old engine still going, and forgetting
            // it then was how two of them came to run at once.
            //
            // Moving on rather than giving up. This step cannot run, but the
            // rest of the ladder is carriers that have nothing to do with the
            // engine, and ending the session here would leave the screen on
            // "connecting" with nothing behind it.
            staleEngine?.let { stale ->
                if (withTimeoutOrNull(STALE_ENGINE_WAIT_MS) { stale.join() } == null) {
                    val stuck = sayNow(R.string.err_auto_engine_busy)
                    EngineLog.record(LogLevel.WARN, "auto", stuck)
                    finishStep(sessionGeneration, mode, stuck)
                    return null
                }
                staleEngine = null
            }
            if (sessionGeneration != generation) return null
            autoRunningRoute = AutoRoute.AETHER
        }
        return step
    }

    private fun enterStep(index: Int, sessionGeneration: Long) {
        autoStepIndex = index
        autoEngineAttempt = 0
        autoEngineUp = false
        autoToken += 1
        val step = autoSteps.getOrNull(index) ?: return
        EngineLog.record(LogLevel.INFO, "auto", "step ${index + 1} of ${autoSteps.size}: ${describe(step)}")
        if (step is AutoStep.Engine) {
            setAutoStage(Carrier.AETHER, CarrierStage.CONNECTING)
            leash(step, sessionGeneration)
        }
    }

    private fun describe(step: AutoStep): String = when (step) {
        is AutoStep.Engine ->
            "aether for ${step.budgetMs / 1_000}s" + if (step.deep) ", full search" else ""
        is AutoStep.Race -> step.lanes.joinToString(" | ") { lane ->
            lane.routes.joinToString(", ") { it.wireName } +
                if (lane.startAfterMs > 0) " from ${lane.startAfterMs / 1_000}s" else ""
        }
    }

    /**
     * Holds the engine to its step's budget.
     *
     * The engine's own retries were built for someone who chose it: eight of
     * them, backing off to a minute apart. Automatic has other routes to try,
     * and a network that blocks MASQUE is exactly where those eight would be
     * spent. So the engine gets this long, and then the step moves on.
     *
     * Gently first. Stopping the engine makes its session fail through the
     * ordinary path, which moves on by itself. Only if that has not happened a
     * little later is the session abandoned under a new generation: a scan
     * blocked in a socket read does not always hear the stop, and the next step
     * does not need to wait for it to.
     */
    private fun leash(step: AutoStep.Engine, sessionGeneration: Long) {
        val token = autoToken
        autoStepDeadline = System.currentTimeMillis() + step.budgetMs
        serviceScope.launch {
            delay(step.budgetMs)
            if (!leashHolds(token, sessionGeneration)) return@launch
            EngineLog.record(
                LogLevel.WARN,
                "auto",
                "aether did not connect within ${step.budgetMs / 1_000}s; moving on",
            )
            autoStepDeadline = 0L
            runCatching { NativeAetherBridge.cancelPrepare() }
            runCatching { NativeAetherBridge.cancelScan() }
            runCatching { NativeAetherBridge.stop() }
            delay(LEASH_GRACE_MS)
            if (!leashHolds(token, sessionGeneration)) return@launch
            EngineLog.record(LogLevel.WARN, "auto", "the engine has not stopped; leaving it behind")
            staleEngine = sessionJob
            generation += 1
            newHopAttempt()
            finishStep(generation, autoMode ?: EngineMode.TUN, sayNow(R.string.err_auto_engine_timeout))
        }
    }

    private fun leashHolds(token: Long, sessionGeneration: Long): Boolean =
        token == autoToken && sessionGeneration == generation && !autoEngineUp && !autoConnected

    /**
     * Where Automatic goes after something did not work.
     *
     * Three cases, wanting three different things. A route that worked and then
     * stopped starts the search again from the top -- which begins with that
     * same route, since the memory has just put it first. An engine step with
     * time left tries its next transport. Anything else has had its turn.
     */
    private fun advanceAuto(sessionGeneration: Long, mode: EngineMode, reason: String) {
        autoMode = mode
        if (autoConnected) {
            EngineLog.record(LogLevel.WARN, "auto", "the connection dropped ($reason); looking again")
            autoSteps = emptyList()
            autoPasses = 0
            autoConnected = false
            autoRunningRoute = null
            autoSearchStartedAt = System.currentTimeMillis()
            val lost = sayNow(R.string.status_auto_lost)
            publish(EngineStatus(EngineStage.CONNECTING, mode, message = lost))
            updateNotification(mode, lost)
            relaunch(sessionGeneration, AUTO_RETRY_GAP_MS)
            return
        }
        val step = autoSteps.getOrNull(autoStepIndex)
        EngineLog.record(LogLevel.WARN, "auto", "${step?.let(::describe) ?: "no step"}: $reason")
        if (step is AutoStep.Engine &&
            System.currentTimeMillis() + ENGINE_ATTEMPT_FLOOR_MS < autoStepDeadline
        ) {
            autoEngineAttempt += 1
            autoEngineUp = false
            relaunch(sessionGeneration, AUTO_RETRY_GAP_MS)
            return
        }
        finishStep(sessionGeneration, mode, reason)
    }

    /** The current step is over without a way out: the next one, or the end of the pass. */
    private fun finishStep(sessionGeneration: Long, mode: EngineMode, reason: String) {
        if (autoSteps.getOrNull(autoStepIndex) is AutoStep.Engine) {
            setAutoStage(Carrier.AETHER, CarrierStage.FAILED)
            // Written down, so the next connect on this network does not spend
            // the same two and a half minutes finding it out again. It only
            // keeps the engine out of the lead; it still races.
            rememberEngineFailure()
        }
        enterStep(autoStepIndex + 1, sessionGeneration)
        if (autoStepIndex < autoSteps.size) {
            relaunch(sessionGeneration, AUTO_STEP_GAP_MS)
            return
        }
        autoPasses += 1
        EngineLog.record(LogLevel.WARN, "auto", "pass $autoPasses of $MAX_AUTO_PASSES found no way out")
        // Room for a pass to *finish*, not room to start one. Asking whether
        // the ceiling has already been passed is the wrong question: the
        // longest pass this plan can make is under the ceiling by itself, so
        // that test never fires and the second pass runs to its own end --
        // twenty-seven minutes, which is the thing being prevented.
        //
        // A pass that failed quickly leaves room for another, and gets one.
        // The first pass is never cut short: Psiphon's own establish window is
        // five and a half minutes and it is the carrier most likely to get out
        // where nothing else does, so truncating it would trade a long wait for
        // a failed connect.
        val spent = System.currentTimeMillis() - autoSearchStartedAt
        val another = AutoPlanner.longestPassMs(
            RouteMemory.recall(
                preferences.getString(AUTO_ROUTES, null),
                autoNetworkKey,
                System.currentTimeMillis(),
            ),
            autoOptions(mode),
        )
        val outOfTime = autoSearchStartedAt > 0L &&
            !AutoPlanner.hasRoomForAnotherPass(spent, another, MAX_AUTO_SEARCH_MS)
        if (outOfTime) {
            EngineLog.record(
                LogLevel.WARN,
                "auto",
                "stopping after ${spent / 1_000}s; another pass needs ${another / 1_000}s and " +
                    "this search is allowed ${MAX_AUTO_SEARCH_MS / 1_000}s",
            )
        }
        if (autoPasses >= MAX_AUTO_PASSES || outOfTime) {
            // Lockdown is the one cause worth naming here: it fails every
            // carrier at once and nothing in their own logs says so.
            val told = listOfNotNull(sayNow(R.string.err_auto_nothing_worked), lockdownHint())
                .joinToString(" — ")
            giveUp(mode, reason, told = told)
            return
        }
        autoSteps = emptyList()
        autoRunningRoute = null
        val again = sayNow(R.string.status_auto_again)
        publish(EngineStatus(EngineStage.CONNECTING, mode, message = again))
        updateNotification(mode, again)
        relaunch(sessionGeneration, AUTO_PASS_GAP_MS)
    }

    private fun relaunch(sessionGeneration: Long, delayMs: Long) {
        serviceScope.launch {
            delay(delayMs)
            val config = baseConfigJson ?: return@launch
            if (sessionGeneration == generation) {
                replaceParkedSession { runSession(config, sessionGeneration) }
            }
        }
    }

    /**
     * Starts [session] as the current session, first cancelling the previous
     * one if it is parked.
     *
     * A carrier session that lost its carrier stays parked after its watcher
     * has already scheduled the next attempt, and nothing else would ever
     * cancel it: one stranded coroutine per reconnect, for the life of the
     * service.
     */
    private fun replaceParkedSession(session: suspend () -> Unit) {
        if (sessionCancellable) sessionJob?.cancel()
        sessionJob = serviceScope.launch { session() }
    }

    /**
     * Waits for the phone to have a network, and says so while it waits.
     *
     * @return false when the session should not go on: replaced, stopped, or
     *   given up on after waiting long enough that someone has walked away.
     */
    private suspend fun awaitNetwork(mode: EngineMode, sessionGeneration: Long): Boolean {
        if (NetworkIdentity.current(this).online) return true
        EngineLog.record(LogLevel.WARN, "auto", "this phone has no network; waiting for one")
        val waiting = sayNow(R.string.status_auto_waiting_network)
        publish(EngineStatus(EngineStage.CONNECTING, mode, message = waiting))
        updateNotification(mode, waiting)
        val deadline = System.currentTimeMillis() + NETWORK_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(NETWORK_POLL_MS)
            if (sessionGeneration != generation) return false
            if (NetworkIdentity.current(this).online) {
                EngineLog.record(LogLevel.INFO, "auto", "a network is up")
                return true
            }
        }
        giveUp(mode, "no network", told = sayNow(R.string.err_auto_no_network))
        return false
    }

    private fun autoOptions(mode: EngineMode): AutoOptions {
        val libraries = File(applicationInfo.nativeLibraryDir)
        return AutoOptions(
            wholeDevice = mode == EngineMode.TUN,
            chainAvailable = chain.isAvailable,
            transportsAvailable = listOf("snowflake", "obfs4").all {
                File(libraries, TorBridges.binaryFor(it)).exists()
            },
            hasCustomBridges = TorBridges.parse(torBridges).isNotEmpty(),
            engineCanSearchDeeper = transportOf(baseConfigJson ?: "{}") in DEEPER_TRANSPORTS,
            // Written whenever the engine connects, so anyone upgrading from a
            // version where Aether worked for them has it. Deliberately not
            // scoped to this network: the question is whether the engine has
            // ever got out for this user, not whether it has here.
            engineWorkedBefore = preferences.getString(LAST_GOOD_TRANSPORT, null) != null,
            // This one is scoped, because it decides which framing to lead
            // with, and a framing proven on another network says nothing about
            // whether this one carries it.
            provenFraming = rememberedTransport()?.takeIf { it == "h2" || it == "h3" },
            onMobileData = NetworkKey.isCellular(autoNetworkKey),
            lastEngineFailure = lastEngineFailure,
            engineFailedHere = RouteMemory.engineFailedRecently(
                preferences.getString(AUTO_ROUTES, null),
                autoNetworkKey,
                System.currentTimeMillis(),
            ),
        )
    }

    /**
     * The engine's configuration for this rung of an engine step.
     *
     * The same ladder a user on Automatic transport has always climbed, one
     * rung per attempt. The last step starts where that ladder does its full
     * searches, rather than switching to the thorough scan: thorough is slower
     * than any budget worth giving it, so it was a search cut off before it
     * could finish.
     */
    private fun autoEngineConfig(base: String, step: AutoStep.Engine): String =
        configForAttempt(base, autoEngineAttempt + if (step.deep) FULL_SEARCH_RUNG else 0)

    private class AutoWinner(val route: AutoRoute, val client: CarrierClient, val port: Int)

    /**
     * Races one step's carriers, and routes the interface into the first to
     * carry traffic.
     *
     * The interface goes up first and stays unread while the race runs, as on
     * every carrier path: the phone's traffic waits in it rather than leaving
     * by the network the user is trying to get around. mihomo is handed it only
     * once a winner has carried a real request.
     *
     * Everything here is safe to cancel, which is what a new connect or a stop
     * does to it: the interface is closed unless mihomo has taken it, and every
     * carrier the race started is stopped.
     */
    private suspend fun runAutoRace(
        step: AutoStep.Race,
        configJson: String,
        mode: EngineMode,
        chainSettings: ChainSettings,
        splitTunnel: SplitTunnel,
        sessionGeneration: Long,
    ) {
        sessionCancellable = true
        if (lockdownHint() != null) {
            EngineLog.record(LogLevel.WARN, "auto", "always-on VPN lockdown is on; Psiphon and Tor may have no network")
        }
        if (prepare(this) != null) {
            reportError(mode, sayNow(R.string.err_permission_required))
            finishIfCurrent(sessionGeneration)
            return
        }
        val tun = establishTun("", "", forChain = true, transport = "auto", splitTunnel = splitTunnel)
        if (tun == null) {
            reportError(mode, sayNow(R.string.err_no_interface))
            finishIfCurrent(sessionGeneration)
            return
        }
        var handedOff = false
        try {
            publishAutoProgress()
            val winner = raceLanes(step.lanes, sessionGeneration)
            if (sessionGeneration != generation) {
                winner?.let { runCatching { it.client.stop() } }
                return
            }
            if (winner == null) {
                scheduleReconnect(configJson, sessionGeneration, mode, sayNow(R.string.err_auto_race_failed))
                return
            }

            carrier = winner.route.carrier
            secondCarrier = null
            autoRunningRoute = winner.route
            // The framing that got Aether out, so the direct engine starts on
            // it next time -- the memory sends that connect to the direct path.
            if (winner.route.racesEngine) rememberWorkingTransport(raceEngineConfig(winner.route))
            startedHops.clear()
            startedHops += carrier to winner.client
            hopStages.clear()
            hopStages[carrier] = CarrierStage.CONNECTED
            val attempt = newHopAttempt()
            EngineLog.record(
                LogLevel.INFO,
                "auto",
                "${winner.route.wireName} carries traffic; routing the interface into 127.0.0.1:${winner.port}",
            )
            watchHop(carrier, winner.client, configJson, mode, sessionGeneration, attempt)
            buyIdentityBehind(carrier, winner.port, sessionGeneration)
            publish(EngineStatus(EngineStage.CONNECTING, mode, null, sayNow(R.string.status_starting_chain)))

            val fd = tun.detachFd()
            handedOff = true
            val failure = withContext(Dispatchers.IO) {
                chain.startCarrier(
                    settings = chainSettings,
                    socksPort = winner.port,
                    udp = carrier.carriesUdp,
                    tunFd = fd,
                )
            }
            if (failure != null) {
                EngineLog.record(LogLevel.ERROR, "chain", failure)
                // Claimed first, so the winner's watcher does not report this
                // failure a second time when its carrier is stopped below.
                collapsedAttempt = attempt
                runCatching { chain.stop() }
                stopCarrier()
                if (sessionGeneration != generation) return
                scheduleReconnect(configJson, sessionGeneration, mode, failure)
                return
            }

            serviceScope.launch {
                while (sessionGeneration == generation) {
                    delay(EVENT_DRAIN_MS)
                    withContext(Dispatchers.IO) { chain.collectEvents() }
                }
            }
            reportConnected(
                mode,
                null,
                sayNow(R.string.status_carrier_carries, sayNow(carrier.label)),
                carrierSocksPort = winner.port,
            )
            awaitCancellation()
        } finally {
            if (!handedOff) runCatching { tun.close() }
        }
    }

    /**
     * Runs the lanes, and returns the first route to carry traffic -- or null
     * once every lane has run out.
     *
     * A lane waits out its head start, or for every lane already running to run
     * out, whichever is first: a second lane sitting out a head start behind a
     * lane that has already failed would only be waiting for a clock.
     */
    private suspend fun raceLanes(lanes: List<Lane>, sessionGeneration: Long): AutoWinner? {
        val race = ++autoRaceId
        val winner = CompletableDeferred<AutoWinner?>()
        val begun = List(lanes.size) { CompletableDeferred<Unit>() }
        val finished = BooleanArray(lanes.size)
        // Not this coroutine's children. The engine's search inside prepare()
        // is a blocking call: cancelPrepare() ends the work it is doing, but
        // the JNI call itself only returns once that unwinds, and a plain
        // cancellation does not reach it at all. When the lanes were children
        // the race waited for them: on the emulator a Psiphon that had carried
        // traffic in ten seconds sat for forty more while a losing Aether
        // finished its quick search. The lanes are cancelled and left to finish
        // in their own time; settling the race is what keeps a late one from
        // touching anything.
        val lanesScope = CoroutineScope(
            serviceScope.coroutineContext + SupervisorJob(serviceScope.coroutineContext[Job]),
        )

        fun handOn() {
            if (lanes.indices.any { begun[it].isCompleted && !finished[it] }) return
            val next = lanes.indices.firstOrNull { !begun[it].isCompleted }
            if (next != null) begun[next].complete(Unit) else winner.complete(null)
        }

        val jobs = lanes.mapIndexed { index, lane ->
            lanesScope.launch {
                withTimeoutOrNull(lane.startAfterMs) { begun[index].await() }
                begun[index].complete(Unit)
                for (route in lane.routes) {
                    if (winner.isCompleted || sessionGeneration != generation) break
                    val found = tryRoute(route, sessionGeneration, race) ?: continue
                    // Two routes can come good in the same moment. The second
                    // is stopped rather than left running beside the first.
                    if (!winner.complete(found)) runCatching { found.client.stop() }
                    return@launch
                }
                finished[index] = true
                handOn()
            }
        }
        if (lanes.isEmpty()) winner.complete(null)

        var result: AutoWinner? = null
        try {
            result = winner.await()
            return result
        } finally {
            // Settled: won, run out, or the session stopped. Whatever is still
            // running has lost, and is stopped here rather than by its own lane.
            autoRaceSettled = race
            autoTrying.clear()
            val losers = autoInFlight.filterKeys { it != result?.route }
            losers.forEach { (route, client) ->
                // A losing engine may go on searching for up to two minutes
                // after this, unreachable; the next engine session waits for it.
                if (route.racesEngine) {
                    lanes.indexOfFirst { lane -> route in lane.routes }
                        .takeIf { it >= 0 }
                        ?.let { staleEngine = jobs[it] }
                }
                runCatching { client.stop() }
            }
            autoInFlight.clear()
            lanesScope.cancel()
        }
    }

    /**
     * Starts one route and keeps it only if a real request gets through it.
     *
     * Whatever happens -- failure, a route that connects and carries nothing,
     * or this lane being cancelled because another won -- a route that is not
     * kept is stopped here, so nothing the race started outlives it.
     */
    private suspend fun tryRoute(route: AutoRoute, sessionGeneration: Long, race: Long): AutoWinner? {
        if (route.racesEngine) {
            // One engine at a time, as for an engine step: the step before
            // this race may have left one behind.
            staleEngine?.let { stale ->
                if (withTimeoutOrNull(STALE_ENGINE_WAIT_MS) { stale.join() } == null) {
                    EngineLog.record(
                        LogLevel.WARN,
                        "auto",
                        "${route.wireName}: the previous engine has still not stopped",
                    )
                    return null
                }
                staleEngine = null
            }
            if (sessionGeneration != generation) return null
        }
        val client = autoClient(route)
        autoInFlight[route] = client
        setAutoStage(route.carrier, CarrierStage.CONNECTING)
        autoTrying += route
        publishAutoProgress()
        var kept = false
        try {
            val port = client.start(AutoPlanner.budgetMs(route)).getOrElse { error ->
                EngineLog.record(LogLevel.WARN, "auto", "${route.wireName}: ${error.message}")
                if (route.racesEngine) lastEngineFailure = error.message
                return null
            }
            if (sessionGeneration != generation) return null
            EngineLog.record(LogLevel.INFO, "auto", "${route.wireName} is up; checking that traffic gets through")
            if (!CarrierProbe.works(port)) {
                EngineLog.record(
                    LogLevel.WARN,
                    "auto",
                    "${route.wireName} came up, but nothing reached the internet through it",
                )
                return null
            }
            kept = true
            return AutoWinner(route, client, port)
        } finally {
            // Once the race has settled, a runner finishing late -- a losing
            // engine whose search nothing could interrupt -- has already been
            // stopped and cleared away. Touching the screen, the stages or the
            // client from here would undo what came after.
            val settled = autoRaceSettled == race
            if (autoInFlight[route] === client) autoInFlight.remove(route)
            if (!settled) {
                autoTrying -= route
                if (!kept) {
                    runCatching { client.stop() }
                    setAutoStage(route.carrier, CarrierStage.FAILED)
                    if (sessionGeneration == generation) publishAutoProgress()
                }
            }
        }
    }

    private fun autoClient(route: AutoRoute): CarrierClient = when (route.carrier) {
        // Any exit country. One the user picked by hand is a restriction on
        // where Psiphon may go, and Automatic is for not having to choose.
        Carrier.PSIPHON -> PsiphonClient(this, "", 0).also { psiphonClient = it }
        Carrier.TOR -> TorClient(this, route.torBridge ?: TorBridge.NONE, torBridges, 0)
            .also { torClient = it }
        // The engine as a carrier: proxy mode, its listener behind the race's
        // interface like the others. The direct route never gets here -- it
        // runs on the interface in a step of its own, and AutoPlannerTest
        // holds the plan to that.
        Carrier.AETHER -> {
            check(route.racesEngine) { "the direct engine is not raced" }
            AetherCarrierClient(serviceScope, carrierEngineConfig(raceEngineConfig(route), 0))
                .also { aetherCarrier = it }
        }
    }

    /** The user's own configuration, on the framing and at the depth [route] asks for. */
    private fun raceEngineConfig(route: AutoRoute): String {
        val base = baseConfigJson ?: "{}"
        val transport = route.engineTransport ?: return base
        return runCatching {
            val json = JSONObject(base)
            // Full is the user's own depth -- balanced unless they chose
            // otherwise; quick is the engine's quickest.
            val depth = if (route.fullSearch) json.optString("scanMode", "balanced") else "turbo"
            json.put("transport", transport).put("scanMode", depth)
            // A rung that carries a tactic sets it; one that does not leaves
            // the user's own choice alone, so turning something on by hand is
            // still worth doing and is not quietly overridden on every rung.
            route.fragmentTls?.let { json.put("fragmentTls", it) }
            route.encryptedHello?.let { json.put("encryptedHello", it) }
            json.toString()
        }.getOrDefault(base)
    }

    /**
     * Buys the engine an identity over a carrier that is already working.
     *
     * The engine cannot connect without a Cloudflare registration, and a
     * network that blocks `api.cloudflareclient.com` will not let it get one --
     * which is a deadlock the app was walking into every session. Psiphon needs
     * no account at all, so it wins the race, carries the user's traffic, and
     * the engine goes on being unable to register for as long as that lasts.
     *
     * The way out is the route that is already working. This sends the engine's
     * registration through the winner's own SOCKS listener, once, in the
     * background. Nothing is torn down and nobody waits for it: the user stays
     * on the carrier that got them out, and the next connect on this network
     * can be the engine's direct one, which is faster and has fewer moving
     * parts.
     *
     * No endpoint search comes with it -- registration is all that is bought
     * here, and searching would be several thousand probes for a tunnel nobody
     * is about to build.
     *
     * Skipped when the engine is the winner, since it evidently has what it
     * needs, and when it already has an identity: the call is cheap then,
     * answered from the store without a round trip, but saying so here keeps
     * the log honest about what was spent.
     */
    private fun buyIdentityBehind(carrier: Carrier, port: Int, sessionGeneration: Long) {
        if (carrier == Carrier.AETHER || port <= 0) return
        if (boughtIdentityBehindCarrier) return
        val base = baseConfigJson ?: return
        boughtIdentityBehindCarrier = true

        serviceScope.launch {
            // The engine refuses to provision while one is running, and a lane
            // that just lost the race may still be unwinding inside a blocking
            // call. Waiting for it costs nothing here -- nobody is watching
            // this, which is the point of doing it behind a carrier.
            staleEngine?.let { stale -> withTimeoutOrNull(STALE_ENGINE_WAIT_MS) { stale.join() } }
            if (sessionGeneration != generation) return@launch

            val config = carrierEngineConfig(base, port)
            EngineLog.record(
                LogLevel.INFO,
                "identity",
                "asking Cloudflare for an identity through ${carrier.wireName}, " +
                    "so the next connect here can be the engine's own",
            )
            val outcome = withContext(Dispatchers.IO) { NativeAetherBridge.provision(config) }
            if (sessionGeneration != generation) return@launch

            outcome.fold(
                onSuccess = { devices ->
                    EngineLog.record(
                        LogLevel.INFO,
                        "identity",
                        "the engine holds ${devices.size} identit" +
                            (if (devices.size == 1) "y" else "ies") +
                            "; it will not have to ask again",
                    )
                },
                onFailure = { error ->
                    // Not a session failure. The user is connected, by the route
                    // that won -- this was an attempt to make the next one
                    // better, and it can be made again next time.
                    EngineLog.record(
                        LogLevel.WARN,
                        "identity",
                        "could not get an identity through ${carrier.wireName}: ${error.message}",
                    )
                },
            )
        }
    }

    /** Remembers [route] for this network, which is what makes the next connect here quick. */
    private fun autoWon(route: AutoRoute) {
        if (!automatic) return
        autoConnected = true
        autoToken += 1
        autoPasses = 0
        autoSearchStartedAt = 0L
        setAutoStage(route.carrier, CarrierStage.CONNECTED)
        val now = System.currentTimeMillis()
        val stored = preferences.getString(AUTO_ROUTES, null)
        if (RouteMemory.recall(stored, autoNetworkKey, now) != route) {
            EngineLog.record(LogLevel.INFO, "auto", "remembering ${route.wireName} for network $autoNetworkKey")
        }
        preferences.edit { putString(AUTO_ROUTES, RouteMemory.remember(stored, autoNetworkKey, route, now)) }
    }

    /**
     * Notes that leading with the engine did not work on this network.
     *
     * Only the engine-first step writes this. A loss inside the race says
     * nothing new -- everything in a race loses except one -- and recording
     * those would take the engine out of the lead on a network where it is
     * simply slower than Psiphon, which is not the same thing at all.
     */
    private fun rememberEngineFailure() {
        val stored = preferences.getString(AUTO_ROUTES, null)
        EngineLog.record(
            LogLevel.INFO,
            "auto",
            "the engine did not connect first on network $autoNetworkKey; it will race rather than lead",
        )
        preferences.edit {
            putString(
                AUTO_ROUTES,
                RouteMemory.rememberEngineFailure(stored, autoNetworkKey, System.currentTimeMillis()),
            )
        }
    }

    private fun publishAutoProgress() {
        val mode = autoMode ?: return
        val trying = autoTrying.map(::routeLabel)
        val message = when (trying.size) {
            0 -> sayNow(R.string.status_auto_searching)
            1 -> sayNow(R.string.status_auto_trying, trying[0])
            2 -> sayNow(R.string.status_auto_trying_two, trying[0], trying[1])
            else -> sayNow(R.string.status_auto_trying_three, trying[0], trying[1], trying[2])
        }
        publish(EngineStatus(EngineStage.CONNECTING, mode, message = message))
        updateNotification(mode, message)
    }

    /** A route by name, with Tor's bridge beside it: "Tor (Snowflake)". */
    private fun routeLabel(route: AutoRoute): String {
        val name = sayNow(route.carrier.label)
        val bridge = route.torBridge ?: return name
        return sayNow(R.string.auto_route_with_bridge, name, sayNow(bridge.label))
    }

    private fun setAutoStage(carrier: Carrier, stage: CarrierStage) {
        autoStages = autoStages + (carrier to stage)
    }

    private fun autoAttempts(): List<HopStatus> =
        autoStages.map { (carrier, stage) -> HopStatus(carrier, stage) }

    /**
     * Every status this service reports, with Automatic's progress on it.
     *
     * In one place rather than at each call: the engine path reports its
     * progress from a dozen places that know nothing about Automatic, and a row
     * of routes that vanished whenever one of them spoke would be a row nobody
     * could follow. For the same reason the engine step's line reads "Trying
     * Aether" rather than the engine's own vocabulary -- identities, gateways,
     * endpoints -- which the diagnostics log keeps for whoever needs it.
     */
    private fun publish(status: EngineStatus) {
        if (!automatic || status.path.isNotEmpty() || status.stage !in AUTO_PROGRESS_STAGES) {
            EngineStatusStore.update(status)
            return
        }
        val engineStep = autoSteps.getOrNull(autoStepIndex) is AutoStep.Engine &&
            status.stage != EngineStage.ERROR && !autoConnected
        EngineStatusStore.update(
            status.copy(
                message = if (engineStep) {
                    sayNow(R.string.status_auto_trying, sayNow(Carrier.AETHER.label))
                } else {
                    status.message
                },
                attempts = autoAttempts(),
                searchStartedAtMillis = autoSearchStartedAt.takeIf { it > 0L },
            ),
        )
    }

    /**
     * Runs the engine and the chain together.
     *
     * The engine no longer blocks this coroutine, because the chain has to be
     * configured while it is already running: the provider fetch travels through
     * its SOCKS listener, so that listener has to be up first. So the engine goes
     * to a child job, this waits for it to report a route, and only then hands
     * the interface to mihomo.
     *
     * Handing it over last is deliberate. mihomo with no configuration routes
     * everything DIRECT, and a DIRECT route from this process is excluded from
     * the interface -- so an interface attached before the rules exist would put
     * the user's traffic on the local network in the clear. Attached after, the
     * worst case is packets with nowhere to go.
     */
    private suspend fun runChainSession(
        configJson: String,
        engineConfig: String,
        chainSettings: ChainSettings,
        peer: String?,
        tun: ParcelFileDescriptor?,
        listener: NativeEngineListener,
        tunnelUp: CompletableDeferred<Unit>,
        mode: EngineMode,
        sessionGeneration: Long,
    ) {
        val socksPort = runCatching {
            JSONObject(engineConfig).optInt("listenPort", DEFAULT_SOCKS_PORT)
        }.getOrDefault(DEFAULT_SOCKS_PORT)

        val engine = if (chainSettings.throughTunnel && peer != null) {
            serviceScope.launch(Dispatchers.IO) {
                NativeAetherBridge.run(engineConfig, peer, -1, listener)
            }
        } else {
            null
        }

        val cleanUp = {
            runCatching { chain.stop() }
            runCatching { NativeAetherBridge.stop() }
            clearSocketProtector(sessionGeneration)
            // A no-op once mihomo has taken the descriptor, and the whole point
            // until then: every way of giving up below runs through here.
            runCatching { tun?.close() }
            Unit
        }

        if (engine != null) {
            updateNotification(mode, sayNow(R.string.status_connecting_for_chain, peer.orEmpty()))
            val reached = withTimeoutOrNull(TUNNEL_WAIT_MS) {
                // Either outcome ends the wait: the route opened, or the engine
                // stopped and there will never be one.
                select {
                    tunnelUp.onAwait { true }
                    engine.onJoin { false }
                }
            }
            if (reached != true) {
                cleanUp()
                if (sessionGeneration != generation) return
                scheduleReconnect(
                    configJson,
                    sessionGeneration,
                    mode,
                    if (reached == null) {
                        sayNow(R.string.err_chain_timeout)
                    } else {
                        sayNow(R.string.err_route_closed_early)
                    },
                )
                return
            }
        }

        if (sessionGeneration != generation) {
            cleanUp()
            return
        }

        publish(
            EngineStatus(EngineStage.CONNECTING, mode, peer, sayNow(R.string.status_starting_chain)),
        )
        updateNotification(mode, sayNow(R.string.status_starting_chain))
        val failure = withContext(Dispatchers.IO) {
            chain.start(
                settings = chainSettings,
                socksPort = if (engine != null) socksPort else null,
                tunFd = tun?.detachFd() ?: -1,
            )
        }
        if (failure != null) {
            EngineLog.record(LogLevel.ERROR, "chain", failure)
            cleanUp()
            if (sessionGeneration != generation) return
            scheduleReconnect(configJson, sessionGeneration, mode, failure)
            return
        }

        // mihomo's own log, moved into ours while the session runs. Draining only
        // at teardown would lose exactly the lines that explain a chain which is
        // up and carrying nothing.
        // Bounded by the generation rather than by a handle, so the direct-dial
        // path -- which returns from here with mihomo still running -- does not
        // leave it pumping for a session that has been replaced.
        serviceScope.launch {
            while (sessionGeneration == generation) {
                delay(EVENT_DRAIN_MS)
                withContext(Dispatchers.IO) { chain.collectEvents() }
            }
        }

        // Off the main thread, and only for a log line. nodes() is a JNI call
        // returning the whole proxy map as JSON, and it then reads every cached
        // subscription from disk -- on a large list most of a second, landing
        // on the main thread at the exact moment the tunnel comes up. That is
        // the freeze people saw on connect.
        val nodeCount = withContext(Dispatchers.IO) { chain.nodes().nodes.size }
        EngineLog.record(LogLevel.INFO, "chain", "exit chain up on $nodeCount nodes")
        reportConnected(
            mode,
            peer,
            if (engine != null) {
                sayNow(R.string.status_chain_carries)
            } else {
                sayNow(R.string.status_chain_direct)
            },
        )

        // The chain lives exactly as long as the route underneath it. When that
        // closes there is nothing left for mihomo to dial through, so it comes
        // down too rather than quietly falling back to a direct connection.
        // Dialling directly there is no such route, and mihomo runs until the
        // user stops it.
        if (engine == null) return
        engine.join()
        cleanUp()
        if (sessionGeneration != generation) return
        scheduleReconnect(configJson, sessionGeneration, mode, sayNow(R.string.err_route_closed))
    }

    /**
     * Copies the engine's own log into the app's while a session is running.
     *
     * Every session, not just the ones with a chain: the reason a connect
     * failed is in these lines, and a diagnostics report without them says only
     * that it failed. Bounded by the generation so a replaced session stops
     * pumping for one nobody is watching.
     */
    private fun startEngineLogPump(sessionGeneration: Long) {
        serviceScope.launch {
            while (sessionGeneration == generation) {
                delay(ENGINE_LOG_DRAIN_MS)
                val lines = withContext(Dispatchers.IO) { NativeAetherBridge.drainLog() }
                lines.forEach { line ->
                    EngineLog.record(engineLevelOf(line), "engine", line)
                }
            }
        }
    }

    /**
     * The engine writes its level into the text rather than through a channel
     * the bridge can read, so it is recovered from the markers it uses: `[-]`
     * for trouble, `[+]` and `[*]` for progress.
     */
    private fun engineLevelOf(line: String): LogLevel = when {
        line.contains("[-]") -> LogLevel.WARN
        line.contains("error", ignoreCase = true) -> LogLevel.ERROR
        else -> LogLevel.INFO
    }

    private fun reportConnected(
        mode: EngineMode,
        peer: String?,
        message: String,
        carrierSocksPort: Int? = null,
    ) {
        // Automatic names what it chose. The user never picked it, so
        // "connected" alone would leave them no way to know which route is
        // carrying them -- or to say so when asking for help. Proxy mode keeps
        // its own line, because that line is the port to point a client at.
        val route = autoRunningRoute.takeIf { automatic }
        val said = if (route != null && mode == EngineMode.TUN) {
            sayNow(R.string.status_auto_connected, routeLabel(route))
        } else {
            message
        }
        // Posted, because this can arrive on the engine's own thread and the
        // plan belongs to the main one.
        if (route != null) serviceScope.launch { autoWon(route) }
        // A working tunnel is the answer to whatever the blocking was for.
        dropBlackhole()
        // The session's byte counting starts here, not when a screen opens, so
        // the totals cover the whole session however late somebody looks.
        TrafficMeter.start()
        publish(
            EngineStatus(
                EngineStage.CONNECTED,
                mode,
                peer,
                said,
                connectedAtMillis = System.currentTimeMillis(),
                carrierSocksPort = carrierSocksPort,
                path = pathStatus(),
            ),
        )
        updateNotification(mode, said)
    }

    /**
     * Whether this session can actually use the chain, reporting why when it
     * cannot rather than connecting without it. Quietly ignoring the setting
     * would be the worst outcome available: the user believes their traffic
     * leaves from the node, and it leaves from Cloudflare.
     */
    private fun resolveChainUsage(
        settings: ChainSettings,
        mode: EngineMode,
        sessionGeneration: Long,
    ): Boolean {
        val refusal = when {
            !chain.isAvailable -> sayNow(R.string.err_chain_unavailable)
            mode != EngineMode.TUN -> sayNow(R.string.err_chain_needs_tun)
            else -> settings.startupError()
        } ?: return true

        reportError(mode, refusal)
        EngineLog.record(LogLevel.ERROR, "chain", refusal)
        finishIfCurrent(sessionGeneration)
        return false
    }

    /**
     * Whether Cloudflare refused to give this device an identity.
     *
     * Reinstalling discards the identity, so each install registers a new
     * device -- and a handful of those from one address gets the address
     * rate-limited or flagged. It looks exactly like a broken app: nothing
     * connects, and moving between Wi-Fi and mobile data fixes it, because that
     * is a different address.
     *
     * Matched on the engine's own text because that is all that crosses the JNI
     * boundary today. The strings are the ones account.rs produces for 403 and
     * 429, and the test pins them.
     */
    private fun isIdentityRefusal(reason: String): Boolean =
        reason.contains("status 403") ||
            reason.contains("status 429") ||
            reason.contains("too many registrations", ignoreCase = true) ||
            reason.contains("refused this network", ignoreCase = true)

    /**
     * Whether trying again in a few seconds could plausibly help.
     *
     * Two failures cannot be retried out of: an address Cloudflare has stopped
     * issuing identities to, and a network with no reachable endpoint for the
     * chosen protocol. Both take minutes to establish and neither changes on a
     * three-second backoff, so eight attempts is most of an hour spent
     * confirming what the first one already knew -- and from outside it is
     * indistinguishable from a hang.
     */
    private fun isConclusive(reason: String): Boolean =
        isIdentityRefusal(reason) ||
            // The engine is holding a wait of its own -- minutes to an hour,
            // kept across restarts because Cloudflare counts a registration
            // against the address whether or not we keep the answer. Retrying
            // three seconds into that is the behaviour the wait exists to stop.
            reason.contains("registration is on hold", ignoreCase = true) ||
            reason.contains("no WireGuard endpoint answered", ignoreCase = true)

    /**
     * What the engine is doing while it prepares.
     *
     * One message for every protocol read as a hang on the slow ones: WireGuard
     * has its own account to provision and its own endpoints to search, and
     * sayNow(R.string.status_preparing_identity) for four minutes gives the user nothing
     * to judge whether waiting is worth it.
     */
    private fun preparingMessage(configJson: String): String =
        when (transportOf(configJson)) {
            "wg" -> sayNow(R.string.status_searching_wg)
            "wiw" -> sayNow(R.string.status_searching_nested)
            else -> sayNow(R.string.status_preparing_identity)
        }

    private fun withEngineMode(configJson: String, mode: EngineMode): String = runCatching {
        JSONObject(configJson).put("mode", mode.wireName).toString()
    }.getOrDefault(configJson)

    /**
     * @param forChain when true the interface belongs to mihomo, so it carries
     *   mihomo's addresses and resolver rather than the engine's.
     */
    /**
     * Applies the user's per-app rules to the interface.
     *
     * Android takes an allow list or a deny list and throws if given both, so
     * the mode picks the call rather than being something filtered afterwards.
     *
     * This app is never in the allow list and always in the deny list. Routing
     * our own traffic into our own tunnel is the loop everything else here
     * exists to prevent -- the engine's sockets to Cloudflare and mihomo's to
     * its nodes would be captured by the interface they are building.
     */
    private fun applySplitTunnel(builder: Builder, rules: SplitTunnel, excludeSelf: Boolean) {
        val chosen = rules.effectivePackages(packageName)

        if (rules.isEffectivelyEverything(packageName)) {
            if (excludeSelf) {
                runCatching { builder.addDisallowedApplication(packageName) }.onFailure {
                    EngineLog.record(LogLevel.WARN, "split", "could not exclude self: ${it.message}")
                }
            }
            return
        }

        when (rules.mode) {
            SplitTunnelMode.ONLY -> {
                // Our own package is absent by construction, so nothing extra is
                // needed to keep us out: an allow list excludes everyone else.
                var added = 0
                chosen.forEach { name ->
                    runCatching { builder.addAllowedApplication(name); added++ }.onFailure {
                        // Uninstalled since it was chosen. Dropping it is right;
                        // throwing would refuse the whole connection over an app
                        // the user no longer has.
                        EngineLog.record(LogLevel.WARN, "split", "skipped $name: ${it.message}")
                    }
                }
                if (added == 0) {
                    // An allow list Android accepted none of carries nothing at
                    // all, which looks exactly like a connection that failed
                    // silently. Better to route everything and say so.
                    EngineLog.record(
                        LogLevel.ERROR,
                        "split",
                        "none of the chosen apps are installed; routing everything instead",
                    )
                    // And routing everything means this app too, unless it is
                    // excluded here. The allow list did that by construction;
                    // an empty one does not, and a carrier in its own process
                    // would have found itself inside the interface it was
                    // supposed to be carrying.
                    if (excludeSelf) {
                        runCatching { builder.addDisallowedApplication(packageName) }.onFailure {
                            EngineLog.record(
                                LogLevel.WARN,
                                "split",
                                "could not exclude self: ${it.message}",
                            )
                        }
                    }
                }
            }

            else -> {
                (chosen + if (excludeSelf) setOf(packageName) else emptySet()).forEach { name ->
                    runCatching { builder.addDisallowedApplication(name) }.onFailure {
                        EngineLog.record(LogLevel.WARN, "split", "skipped $name: ${it.message}")
                    }
                }
            }
        }
        EngineLog.record(LogLevel.INFO, "split", "coverage ${rules.summary().lowercase()}")
    }

    /**
     * An interface that carries the default routes and forwards nothing.
     *
     * The whole feature in one idea: a VpnService interface exists whether or
     * not anything reads from its descriptor, and while one is up with a
     * default route the kernel sends traffic into it rather than out of the
     * phone. So packets stop here instead of resuming over the ordinary route
     * the moment a tunnel dies.
     *
     * No DNS server is offered, which matters more than it looks: a resolver
     * left over from the real session would be reachable outside the tunnel
     * and would answer, leaking exactly the names the tunnel was hiding.
     */
    private fun raiseBlackhole(reason: String): Boolean {
        if (blackhole != null) return true
        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Builder()
            .setSession(sayNow(R.string.notify_title_blocking))
            .setConfigureIntent(configureIntent)
            .setMtu(MASQUE_MTU)
            .addAddress(BLACKHOLE_IPV4, 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        // This app is always excluded. Left inside its own blackhole it could
        // not reach Cloudflare to reconnect, so the switch would block the one
        // thing able to lift it.
        runCatching { builder.addDisallowedApplication(packageName) }
        blackhole = runCatching { builder.establish() }.getOrNull()
        if (blackhole == null) {
            EngineLog.record(LogLevel.ERROR, "killswitch", "could not raise the blocking interface")
            return false
        }
        EngineLog.record(LogLevel.WARN, "killswitch", "blocking all traffic: $reason")
        return true
    }

    /** Lets traffic out again. Called on a deliberate lift and on a reconnect. */
    private fun dropBlackhole() {
        val open = blackhole ?: return
        blackhole = null
        runCatching { open.close() }
        EngineLog.record(LogLevel.INFO, "killswitch", "blocking lifted")
    }

    private fun establishTun(
        ipv4: String,
        ipv6: String,
        forChain: Boolean,
        transport: String,
        splitTunnel: SplitTunnel = SplitTunnel(),
    ): ParcelFileDescriptor? {
        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Builder()
            .setSession("WhiteAesther")
            .setConfigureIntent(configureIntent)
            .addRoute("0.0.0.0", 0)

        val mtu = mtuFor(transport)

        // Everything this process opens must stay off the interface when the
        // chain runs: the engine's sockets to Cloudflare, mihomo's to its nodes,
        // and the subscription fetch that would otherwise race the tunnel it is
        // being downloaded to configure. protect() covers the same ground one
        // socket at a time, and both are kept -- this one the kernel enforces,
        // that one depends on every caller remembering.
        applySplitTunnel(builder, splitTunnel, excludeSelf = forChain)

        if (forChain) {
            // 9000, not 1280. mihomo terminates TCP in userspace, so this sizes a
            // local write rather than anything that reaches the wire, and a
            // larger one means fewer crossings per megabyte.
            builder.setMtu(9000)
            addAddress(builder, ChainConfig.TUN_IPV4, 30)
            addAddress(builder, ChainConfig.TUN_IPV6, 126)
            builder.addDnsServer(ChainConfig.TUN_DNS)
            builder.addRoute("::", 0)
        } else {
            // The interface has to advertise what the tunnel underneath can
            // actually carry, or the kernel hands the engine packets it then
            // has to fragment. 1280 is Cloudflare's cap on MASQUE and not
            // ours to raise; WireGuard is limited by the path instead, and
            // 1340 inner still leaves a 1400-byte datagram on the wire.
            //
            // This is what kept hysteria2 and tuic nodes from working behind
            // the chain: they need a 1280-byte UDP payload, and 1280 here left
            // 1252 of it.
            builder.setMtu(mtu)
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("1.0.0.1")
            addAddress(builder, ipv4, 32)
            // IPv6 requires a 1280-byte minimum MTU, and Android enforces it:
            // an interface carrying an IPv6 address with anything smaller is
            // refused outright, which as an uncaught exception is a crash on
            // connect. WARP-in-WARP's inner hop carries 1200, so it is IPv4
            // only -- the alternative is advertising an MTU the tunnel cannot
            // honour, which is the fault this number was chosen to fix.
            if (ipv6.isNotBlank() && mtu >= IPV6_MINIMUM_MTU) {
                addAddress(builder, ipv6, 128)
                builder.addDnsServer("2606:4700:4700::1111")
                builder.addDnsServer("2606:4700:4700::1001")
                builder.addRoute("::", 0)
            } else if (ipv6.isNotBlank()) {
                EngineLog.record(
                    LogLevel.INFO,
                    "tun",
                    "IPv6 left off: this tunnel carries $mtu bytes and IPv6 needs $IPV6_MINIMUM_MTU",
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            builder.setBlocking(false)
        }
        return builder.establish()
    }

    private fun addAddress(builder: Builder, cidr: String, defaultPrefix: Int) {
        val address = cidr.substringBefore('/').trim()
        val prefix = cidr.substringAfter('/', defaultPrefix.toString()).toIntOrNull() ?: defaultPrefix
        require(address.isNotBlank()) { "Tunnel address is empty" }
        builder.addAddress(InetAddress.getByName(address), prefix)
    }

    private fun stopFromUser(message: String? = null) {
        preferences.edit {
            remove(LAST_TUN_CONFIG)
            remove(LAST_CHAIN_CONFIG)
            remove(LAST_SPLIT_CONFIG)
        }
        val said = message ?: sayNow(R.string.status_stopped)
        startForegroundNow(sayNow(R.string.status_stopping_app), said)
        publish(
            EngineStatus(EngineStage.STOPPING, EngineStatusStore.status.value.mode, message = said),
        )
        // Invalidate and signal immediately, outside commandMutex. A session
        // wedged in a native call may be holding that lock, and waiting for it
        // is what left the service unstoppable.
        generation += 1
        runCatching { NativeAetherBridge.cancelPrepare() }
        runCatching { NativeAetherBridge.cancelScan() }
        runCatching { NativeAetherBridge.stop() }

        serviceScope.launch {
            // prepare() and run() are blocking JNI calls. On a network that
            // hangs connections rather than refusing them they can sit for
            // minutes, and nativeStop cannot always interrupt a blocked socket
            // read. Give the session a moment to unwind, then tear down
            // regardless -- the user asked it to stop, and the process is going
            // away. Anything still running dies with it.
            // A parked or racing session is cancelled rather than waited on: it
            // would sit out the whole grace period and then be abandoned anyway.
            if (sessionCancellable) sessionJob?.cancel()
            withTimeoutOrNull(STOP_GRACE_MS) {
                listOfNotNull(sessionJob).joinAll()
            }
            sessionJob = null
            // Also a JNI call into the Go engine, and this one runs while the
            // user is watching a "Stopping" spinner.
            withContext(Dispatchers.IO) { runCatching { chain.stop() } }
            // And the carriers. With strict blocking on the service outlives
            // this, and a Psiphon or a tor that nothing points at any more would
            // go on running, and paying for its tunnel, for as long as it did.
            runCatching { stopCarrier() }
            clearSocketProtector(generation)
            // Rates go to zero, totals stay: what a session cost is asked
            // after it ended, not while it is running.
            TrafficMeter.stop()
            // Strict keeps blocking across the gap between sessions, so the
            // service and its interface outlive the tunnel deliberately. Said
            // plainly in the notification, or a user who forgot they turned it
            // on has a phone with no internet and no reason given.
            if (blockAfterStop && raiseBlackhole("disconnected with strict blocking on")) {
                publish(
                    EngineStatus(EngineStage.IDLE, message = sayNow(R.string.traffic_is_blocked)),
                )
                startForegroundNow(sayNow(R.string.traffic_is_blocked), sayNow(R.string.notify_strict_blocking))
                return@launch
            }
            publish(EngineStatus())
            ServiceCompat.stopForeground(this@AetherVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Drops the socket protector, unless a newer session already owns it.
     *
     * The protector is one process-wide slot, but sessions overlap: a session
     * that is being replaced finishes its teardown after its successor has
     * already installed its own. Clearing unconditionally then leaves the live
     * session with no protector, and protect() fails open -- so its sockets
     * quietly start routing through the very tunnel it is bringing up.
     */
    private fun clearSocketProtector(sessionGeneration: Long) {
        if (sessionGeneration != generation) return
        runCatching { NativeAetherBridge.setSocketProtector(null) }
    }

    private fun reportError(mode: EngineMode?, message: String) {
        publish(
            EngineStatus(EngineStage.ERROR, mode, message = message, path = pathStatus()),
        )
        updateNotification(mode, message)
    }

    /**
     * Retries a failed session on an increasing delay, and eventually stops.
     *
     * A fixed delay with no limit meant a permanently refused identity retried
     * forever, which reads on screen as an endless "connecting" and keeps the
     * radio busy. The attempt is named in the message so a slow connect is
     * visibly progress rather than a stall.
     */
    private fun scheduleReconnect(
        configJson: String,
        sessionGeneration: Long,
        mode: EngineMode,
        reason: String,
    ) {
        if (sessionGeneration != generation) return
        // Automatic has its own idea of what comes next: a different route,
        // not the same one again after a longer wait.
        if (automatic) {
            advanceAuto(sessionGeneration, mode, reason)
            return
        }
        reconnectAttempt += 1

        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            giveUp(mode, reason)
            return
        }

        val delayMs = reconnectDelayMs(reconnectAttempt)
        val nextConfig = configForAttempt(baseConfigJson ?: configJson, reconnectAttempt)
        val transport = transportOf(nextConfig).uppercase()
        val message =
            "$reason · retry $reconnectAttempt of $MAX_RECONNECT_ATTEMPTS on $transport in ${delayMs / 1_000}s"
        publish(
            // Still carrying the path: the wait before a retry is exactly when
            // someone reads which hop went, and a row that vanished for it
            // would take the answer away at the moment it is wanted.
            EngineStatus(EngineStage.CONNECTING, mode, message = message, path = pathStatus()),
        )
        updateNotification(mode, message)
        serviceScope.launch {
            delay(delayMs)
            if (sessionGeneration == generation) {
                replaceParkedSession { runSession(nextConfig, sessionGeneration) }
            }
        }
    }

    private fun sessionSummary(configJson: String): String = runCatching {
        val json = JSONObject(configJson)
        buildString {
            append("transport=").append(json.optString("transport", "h3"))
            append(" scan=").append(json.optString("scanMode", "balanced"))
            append(" mode=").append(json.optString("mode", "tun"))
            append(" noize=").append(json.optString("noize", "firewall"))
            append(" fragmentTls=").append(json.optBoolean("fragmentTls", false))
            append(" ech=").append(json.optBoolean("encryptedHello", false))
            append(" ipScan=").append(json.optString("ipScan", "both"))
            append(" peerPinned=").append(json.has("peer"))
        }
    }.getOrDefault("unavailable")

    /**
     * What the interface may advertise, given the tunnel carrying it.
     *
     * Keyed on the wire name the engine was actually given. Automatic has been
     * resolved to a real transport by the time a tun is built, so there is no
     * case here for it.
     */
    private fun mtuFor(transport: String): Int = when (transport) {
        "wg" -> WIREGUARD_MTU
        // Not WireGuard's, despite being built out of two of them. The app's
        // packets enter the *inner* hop, which is sized for what fits inside
        // the outer one -- so this is the inner MTU, not the outer.
        //
        // Grouping it with "wg" told the system it could send 1340 into a
        // tunnel carrying 1200. Small requests survived and anything larger was
        // dropped, which reads as a connection that works until you open a
        // website.
        "wiw" -> WARP_IN_WARP_MTU
        // The same trap as "wiw", and for the same reason: the app's packets
        // enter the inner hop, which is sized for what fits inside the outer
        // one. Grouping it with plain MASQUE would advertise 1280 into a tunnel
        // carrying 1162.
        "mim" -> MASQUE_IN_MASQUE_MTU
        else -> MASQUE_MTU
    }

    private fun transportOf(configJson: String): String =
        runCatching { JSONObject(configJson).optString("transport", "h3") }.getOrDefault("h3")

    /**
     * The engine takes one transport and never falls back between them. H3 rides
     * QUIC, and a network that blocks UDP kills it outright -- reported from MCI
     * in Iran, where QUIC has been down for weeks while H2 over TCP still works.
     * Retrying the same dead transport eight times is eight guaranteed failures,
     * so alternate: the configured one on odd attempts, the other on even.
     */
    private fun configForAttempt(configJson: String, attempt: Int): String = runCatching {
        val json = JSONObject(configJson)
        if (json.optString("transport") == "auto") return@runCatching autoConfig(json, attempt)

        // Only the two MASQUE framings are interchangeable. WireGuard is a
        // different tunnel with its own account and its own endpoints, so
        // substituting it would silently connect the user to something they did
        // not ask for -- and from a different exit address.
        val configured = json.optString("transport", "h3")
        val other = when (configured) {
            "h3" -> "h2"
            "h2" -> "h3"
            else -> return@runCatching configJson
        }
        // The other framing on the first retry, not the second. Repeating the
        // one that just failed costs another full endpoint scan -- on a network
        // that blocks UDP that is four minutes spent confirming UDP is blocked,
        // and most people close the app long before the transport that works is
        // ever tried.
        json.put("transport", if (attempt % 2 == 1) other else configured).toString()
    }.getOrDefault(configJson)

    /**
     * What Automatic tries, in order.
     *
     * A network either carries QUIC or it does not, and the user has no way to
     * know which -- in Iran it varies by operator and by week. So the first two
     * rungs are quick probes of both framings rather than one deep search of a
     * transport that may be blocked outright: a fast failure that moves on beats
     * a thorough one that does not.
     *
     * Whatever connected is remembered, so the next connect starts there and
     * this ladder is only ever climbed once per network.
     */
    private fun autoConfig(json: JSONObject, attempt: Int): String {
        val remembered = rememberedTransport()
        // Asked, not decided here. This ladder used to put H2 first always while
        // the race put H3 first on Wi-Fi, so which framing a user reached
        // depended on which part of the app was asking.
        val order = AutoPlanner.framingOrder(
            provenFraming = remembered?.takeIf { it == "h2" || it == "h3" },
            onMobileData = NetworkKey.isCellular(currentNetworkKey()),
        )
        val ladder = buildList {
            // Deep, because it is already known to work here.
            remembered?.let { add(it to json.optString("scanMode", "balanced")) }
            // Then both framings, quickly.
            order.forEach { add(it to "turbo") }
            // Only then spend a full search on each.
            order.forEach { add(it to json.optString("scanMode", "balanced")) }
        }.distinct()

        val (transport, scan) = ladder[attempt.coerceAtLeast(0) % ladder.size]
        return json.put("transport", transport).put("scanMode", scan).toString()
    }

    /**
     * The framing that last worked on the network the phone is on now.
     *
     * Keyed on the network, as the route memory beside it always was. Held
     * globally it said "H3 worked here" about a wifi the phone had left, and
     * autoConfig puts the remembered rung first with a full search behind it --
     * so moving from wifi to mobile data could begin with a four-minute hunt
     * for UDP on an operator that drops it, before either quick probe was
     * tried.
     *
     * Null when this network has not answered yet, which is the right starting
     * position rather than a missing one: the ladder below it is the answer to
     * not knowing.
     */
    private fun rememberedTransport(): String? {
        val stored = preferences.getString(LAST_GOOD_TRANSPORT, null) ?: return null
        val network = stored.substringBefore(MEMORY_SEPARATOR, missingDelimiterValue = "")
        val transport = stored.substringAfter(MEMORY_SEPARATOR, missingDelimiterValue = "")
        if (transport.isEmpty()) return null
        return transport.takeIf { network == currentNetworkKey() }
    }

    /**
     * Remembers the transport that reached CONNECTED, and where.
     *
     * Only meaningful for Automatic, and only worth writing when it changes:
     * this is on the connect path, and a preference write per session for a
     * value that rarely moves is work nobody asked for.
     */
    private fun rememberWorkingTransport(engineConfig: String) {
        val transport = transportOf(engineConfig)
        if (transport == "auto") return
        val network = currentNetworkKey()
        val entry = network + MEMORY_SEPARATOR + transport
        if (preferences.getString(LAST_GOOD_TRANSPORT, null) == entry) return
        preferences.edit { putString(LAST_GOOD_TRANSPORT, entry) }
        EngineLog.record(LogLevel.INFO, "auto", "network $network carries $transport")
    }

    private fun currentNetworkKey(): String =
        NetworkIdentity.current(this).key ?: RouteMemory.ANY_NETWORK

    /** 3s, 6s, 12s, 24s, 48s, then a minute between attempts. */
    private fun reconnectDelayMs(attempt: Int): Long =
        (RECONNECT_DELAY_MS shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_RECONNECT_DELAY_MS)

    /**
     * Called from inside the session coroutine, so it must not join sessionJob --
     * that would be the job waiting on itself. Bumping the generation is what
     * makes the in-flight session inert.
     */
    /**
     * @param told what to say in place of the retry count, for a caller whose
     *   attempts were not retries at all -- Automatic, which tried different
     *   things and has a plainer sentence for having run out of them.
     */
    private fun giveUp(mode: EngineMode, reason: String, told: String? = null) {
        generation += 1
        preferences.edit { remove(LAST_TUN_CONFIG) }
        // The moment the feature exists for: every retry is spent, the tunnel
        // is not coming back on its own, and without this the phone resumes
        // over the ordinary route without saying anything.
        val blocking = blockOnFailure && raiseBlackhole(reason)
        val said = told ?: sayNow(R.string.err_gave_up, reason, MAX_RECONNECT_ATTEMPTS)
        reportError(
            mode,
            listOfNotNull(
                said,
                // The app has Psiphon and Tor in it and a mode that tries them
                // side by side, and a user whose carrier cannot get out of this
                // network has no way of knowing that from here. Saying so is
                // the difference between them finding the way out this build
                // already has and concluding the app does not work.
                sayNow(R.string.err_gave_up_try_automatic).takeIf { automaticWorthSuggesting(mode) },
                sayNow(R.string.traffic_is_blocked).takeIf { blocking },
            ).joinToString(" "),
        )
        serviceScope.launch {
            runCatching { chain.stop() }
            runCatching { NativeAetherBridge.stop() }
            runCatching { stopCarrier() }
            clearSocketProtector(generation)
            sessionJob = null
            if (blocking) {
                // The service stays up because the interface belongs to it.
                startForegroundNow(sayNow(R.string.traffic_is_blocked), sayNow(R.string.notify_tunnel_failed))
                return@launch
            }
            ServiceCompat.stopForeground(this@AetherVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Whether telling the user about Automatic would be telling them something.
     *
     * Not while it is already running -- it has its own sentence for having run
     * out -- and not where it could not do anything this session did not: both
     * of the other carriers need the whole device and mihomo to route into.
     */
    private fun automaticWorthSuggesting(mode: EngineMode): Boolean =
        !automatic && mode == EngineMode.TUN && chain.isAvailable

    private fun finishIfCurrent(sessionGeneration: Long) {
        if (sessionGeneration != generation) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNow(title: String, text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            AetherNotification.NOTIFICATION_ID,
            AetherNotification.build(this, title, text),
            type,
        )
    }

    private fun updateNotification(mode: EngineMode?, text: String) {
        val title = when (mode) {
            EngineMode.PROXY -> sayNow(R.string.notify_title_proxy)
            EngineMode.TUN -> sayNow(R.string.notify_title_tun)
            null -> sayNow(R.string.app_name)
        }
        getSystemService(android.app.NotificationManager::class.java).notify(
            AetherNotification.NOTIFICATION_ID,
            AetherNotification.build(this, title, text),
        )
    }

    private fun connectedMessage(mode: EngineMode, configJson: String): String = when (mode) {
        EngineMode.TUN -> sayNow(R.string.notify_whole_device_protected)
        EngineMode.PROXY -> {
            val config = runCatching { JSONObject(configJson) }.getOrNull()
            val port = config?.optInt("listenPort", 1819) ?: 1819
            // The notification is where a user checks what to point a client
            // at. Saying loopback while the listener is on the network sends
            // them to an address that refuses them.
            if (config?.optBoolean("lanSharing") == true) {
                sayNow(R.string.notify_socks_shared, port)
            } else {
                sayNow(R.string.notify_socks_local, port)
            }
        }
    }

    companion object {
        /** Cloudflare's cap on MASQUE. Not ours to raise. */
        private const val MASQUE_MTU = 1280

        /** What the path allows WireGuard, which is the larger question. */
        private const val WIREGUARD_MTU = 1340

        /**
         * The inner hop of WARP-in-WARP, which is what the apps talk to.
         *
         * Matches INNER_MTU in the engine. The two have to agree: this is what
         * the system advertises, that is what the tunnel can carry, and a gap
         * between them is silently dropped packets.
         */
        private const val WARP_IN_WARP_MTU = 1200

        /**
         * The inner hop of nested MASQUE, at its smallest.
         *
         * The engine derives this per connection: the outer link carries
         * MASQUE_MTU, an inner QUIC datagram costs 28 bytes of IPv4 header or
         * 48 of IPv6, and MASQUE framing takes 70 more. The interface is
         * declared once, before any of that is known, so it takes the worst
         * case -- (1280 - 48) - 70 -- and an IPv4 inner edge simply leaves 20
         * bytes unused.
         *
         * Only the H3 figure. A profile on H2 gets a larger inner MTU, but
         * nested MASQUE runs H3 on both hops unless the whole profile is H2,
         * and advertising more than the tunnel carries is the failure this
         * constant exists to avoid.
         */
        private const val MASQUE_IN_MASQUE_MTU = 1162

        /**
         * IPv6's own floor, and Android enforces it.
         *
         * An interface carrying an IPv6 address with a smaller MTU is refused,
         * and the refusal is an exception rather than a return value.
         */
        private const val IPV6_MINIMUM_MTU = 1280

        /** Any address will do; nothing is ever sent from it. */
        private const val BLACKHOLE_IPV4 = "10.111.222.1"

        const val ACTION_LIFT_BLOCK = "com.whitedns.whiteaesther.LIFT_BLOCK"

        private const val ACTION_START = "com.whitedns.whiteaesther.START"
        private const val ACTION_STOP = "com.whitedns.whiteaesther.STOP"
        private const val EXTRA_CONFIG = "config"
        private const val EXTRA_CHAIN = "chain"
        private const val EXTRA_SPLIT = "split"
        private const val EXTRA_KILL_SWITCH = "killSwitch"
        private const val EXTRA_STRICT_KILL = "strictKillSwitch"
        private const val LAST_KILL_SWITCH = "last_kill_switch"
        private const val LAST_STRICT_KILL = "last_strict_kill"
        private const val LAST_TUN_CONFIG = "last_tun_config"
        private const val LAST_CHAIN_CONFIG = "last_chain_config"
        private const val LAST_SPLIT_CONFIG = "last_split_config"
        /**
         * Which framing last worked, and on which network: "<network>|<transport>".
         *
         * One entry rather than a table. Unlike the route memory beside it this
         * is only a starting rung on a ladder the session climbs anyway, so
         * remembering every network the phone has ever seen would buy a few
         * seconds on a return to one of them and cost a preference that grows
         * without bound.
         */
        private const val LAST_GOOD_TRANSPORT = "last_good_transport"
        private const val MEMORY_SEPARATOR = "|"
        private const val EXTRA_CARRIER = "carrier"
        private const val EXTRA_SECOND_CARRIER = "second_carrier"
        private const val LAST_CARRIER = "last_carrier"
        private const val LAST_SECOND_CARRIER = "last_second_carrier"
        private const val EXTRA_TOR_BRIDGE = "torBridge"
        private const val EXTRA_TOR_BRIDGES = "torBridges"
        private const val LAST_TOR_BRIDGES = "last_tor_bridges"
        private const val EXTRA_PSIPHON_REGION = "psiphonRegion"
        private const val LAST_PSIPHON_REGION = "last_psiphon_region"
        private const val LAST_TOR_BRIDGE = "last_tor_bridge"
        private const val EXTRA_AUTOMATIC = "automatic"
        private const val LAST_AUTOMATIC = "last_automatic"

        /** Which route last carried traffic, per network. See [RouteMemory]. */
        private const val AUTO_ROUTES = "auto_routes"

        /** How long Automatic waits for the phone to have any network at all. */
        private const val NETWORK_WAIT_MS = 600_000L
        private const val NETWORK_POLL_MS = 3_000L

        /**
         * Whole passes before Automatic says nothing got out. Two, because a
         * network can come good in the minutes one pass takes -- a third would
         * mostly be spent on a network that is simply down.
         */
        private const val MAX_AUTO_PASSES = 2

        /**
         * The whole search, end to end, however many passes fit inside it.
         *
         * A ceiling on what the person waiting experiences rather than on the
         * number of attempts, which is a proxy for it and drifts every time a
         * rung is added. Checked between passes only: a pass that has started
         * runs to its end, because the lanes inside it have their own windows
         * and cutting one in half is how a carrier that was about to connect
         * gets thrown away.
         *
         * Sized so one full pass always fits. AutoPlannerTest holds it there.
         */
        private const val MAX_AUTO_SEARCH_MS = 15 * 60 * 1_000L
        private const val AUTO_RETRY_GAP_MS = 2_000L
        private const val AUTO_STEP_GAP_MS = 1_000L
        private const val AUTO_PASS_GAP_MS = 10_000L

        /** An engine attempt with less than this left of its step is not worth starting. */
        private const val ENGINE_ATTEMPT_FLOOR_MS = 20_000L

        /** How long the leash waits for a stopped engine to notice, before leaving it. */
        private const val LEASH_GRACE_MS = 10_000L

        /**
         * How long the engine waits for one left behind -- by the leash, or a
         * race it lost -- to finish. Two engine sessions at once is not a state
         * the engine was built for, so this is a wait rather than a formality:
         * whoever is waiting moves on to something else instead of starting a
         * second one. cancelPrepare is what keeps it short, by ending the
         * abandoned search rather than letting it run out its own budget --
         * which under Thorough is longer than this on its own.
         */
        private const val STALE_ENGINE_WAIT_MS = 130_000L

        /** Transports whose search has a thorough setting worth a step of its own. */
        private val DEEPER_TRANSPORTS = setOf("auto", "h2", "h3")

        /** Where autoConfig's ladder stops probing quickly and searches in full. */
        private const val FULL_SEARCH_RUNG = 3

        private val AUTO_PROGRESS_STAGES =
            setOf(EngineStage.PREPARING, EngineStage.CONNECTING, EngineStage.ERROR)
        // Psiphon establishes over a network that is actively hostile to it,
        // and its own timeout is two minutes. Ours has to be the longer of
        // the two or we would tear down a tunnel that was about to arrive.
        // tunnel-core's own establish window, with room for it to report.
        private const val PSIPHON_WAIT_MS = 330_000L
        private const val PREFS_NAME = "aether_service"
        // How long the chain waits for the tunnel it dials its nodes through.
        // Generous, because that tunnel is itself still searching for a route.
        private const val TUNNEL_WAIT_MS = 120_000L
        private const val DEFAULT_SOCKS_PORT = 1819
        private const val EVENT_DRAIN_MS = 5_000L
        private const val ENGINE_LOG_DRAIN_MS = 2_000L
        // Long enough for a healthy session to unwind, short enough that a
        // wedged one never leaves the user with only force-stop.
        private const val STOP_GRACE_MS = 4_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 8

        /**
         * Forgets which transport last carried traffic here.
         *
         * That memory is what makes the second connect on a network faster than
         * the first, and it is also what keeps a phone trying a route that
         * stopped working -- the ladder starts at the remembered rung, so a
         * network the device has since left still shapes where it looks. Part
         * of resetting the endpoint, and pointless on its own: the remembered
         * rung is only a starting position, and a fresh search finds it again
         * within one session if it is still the right one.
         */
        fun forgetLastGoodTransport(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit { remove(LAST_GOOD_TRANSPORT) }
        }

        fun start(
            context: Context,
            configJson: String,
            chainJson: String? = null,
            splitJson: String? = null,
            killSwitch: Boolean = false,
            strictKillSwitch: Boolean = false,
            carrier: Carrier = Carrier.AETHER,
            secondCarrier: Carrier? = null,
            torBridge: TorBridge = TorBridge.NONE,
            torBridges: String = "",
            psiphonRegion: String = "",
            automatic: Boolean = false,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AetherVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONFIG, configJson)
                    .putExtra(EXTRA_CHAIN, chainJson)
                    .putExtra(EXTRA_SPLIT, splitJson)
                    .putExtra(EXTRA_KILL_SWITCH, killSwitch)
                    .putExtra(EXTRA_STRICT_KILL, strictKillSwitch)
                    // By name rather than by ordinal. An ordinal is a promise
                    // about the order of an enum that nothing enforces, and a
                    // carrier added in the middle of the list would silently
                    // reinterpret a pending intent written by the old build.
                    .putExtra(EXTRA_CARRIER, carrier.wireName)
                    .putExtra(EXTRA_SECOND_CARRIER, secondCarrier?.wireName)
                    .putExtra(EXTRA_TOR_BRIDGE, torBridge.wireName)
                    .putExtra(EXTRA_TOR_BRIDGES, torBridges)
                    .putExtra(EXTRA_PSIPHON_REGION, psiphonRegion)
                    .putExtra(EXTRA_AUTOMATIC, automatic),
            )
        }

        fun liftBlockIntent(context: Context): Intent =
            Intent(context, AetherVpnService::class.java).setAction(ACTION_LIFT_BLOCK)

        fun liftBlock(context: Context) {
            context.startService(liftBlockIntent(context))
        }

        fun stopIntent(context: Context): Intent = Intent(context, AetherVpnService::class.java)
            .setAction(ACTION_STOP)

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }
    }

    /**
     * The blocking interface, while one is up.
     *
     * Held rather than re-derived: closing the descriptor is what lets traffic
     * out again, so losing the handle would mean a phone that stays blocked
     * until the process dies.
     */
    private var blackhole: ParcelFileDescriptor? = null

    /** Block when the tunnel fails, and block between sessions. */
    private var blockOnFailure = false
    private var blockAfterStop = false

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
}
