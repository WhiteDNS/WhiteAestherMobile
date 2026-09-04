package com.whitedns.whiteaesther.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.whitedns.whiteaesther.core.CarrierClient
import com.whitedns.whiteaesther.core.CarrierStage
import com.whitedns.whiteaesther.core.PsiphonClient
import com.whitedns.whiteaesther.core.TorClient
import com.whitedns.whiteaesther.core.TorConfig
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.TorBridge
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.SplitTunnel
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
     * How Tor should reach its first hop this session.
     *
     * Held beside the carrier because it is part of how tor is started rather
     * than something it can be told afterwards: the choice becomes lines in a
     * torrc that tor reads once.
     */
    private var torBridge: TorBridge = TorBridge.NONE
    private val chain by lazy { ChainController(this) }
    private val psiphon by lazy { PsiphonClient(this) }
    private var torClient: TorClient? = null

    /**
     * The carrier this session is using, or null when the engine is.
     *
     * Resolved once per session rather than branched on at each use: what
     * the session does with a carrier is the same whichever one it is, and
     * a `when` at every call site is a place for the two to drift apart.
     */
    private val carrierClient: CarrierClient?
        get() = when (carrier) {
            Carrier.AETHER -> null
            Carrier.PSIPHON -> psiphon
            // Rebuilt when the bridge changes rather than held: the choice is
            // part of how tor is started, not something it can be told later.
            Carrier.TOR -> torClient
        }

    override fun onCreate() {
        super.onCreate()
        AetherNotification.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var restartPolicy = START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> stopFromUser()
            ACTION_LIFT_BLOCK -> {
                dropBlackhole()
                blockAfterStop = false
                EngineStatusStore.update(EngineStatus())
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
                torBridge = TorBridge.entries
                    .firstOrNull { it.wireName == intent.getStringExtra(EXTRA_TOR_BRIDGE) }
                    ?: TorBridge.NONE
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
                        putString(LAST_TOR_BRIDGE, torBridge.wireName)
                    }
                    restartPolicy = START_STICKY
                } else {
                    preferences.edit {
                        remove(LAST_TUN_CONFIG)
                        remove(LAST_CHAIN_CONFIG)
                        remove(LAST_SPLIT_CONFIG)
                        remove(LAST_CARRIER)
                        remove(LAST_TOR_BRIDGE)
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
                    torBridge = TorBridge.entries
                        .firstOrNull { it.wireName == preferences.getString(LAST_TOR_BRIDGE, null) }
                        ?: TorBridge.NONE
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
                reconnectAttempt = 0
                baseConfigJson = configJson
                chainJson = chainSettings
                splitJson = splitSettings
                NativeAetherBridge.stop()
                runCatching { chain.stop() }
                runCatching { stopCarrier() }
                sessionJob?.join()
                val sessionGeneration = generation
                sessionJob = serviceScope.launch {
                    runSession(configJson, sessionGeneration)
                }
            }
        }
    }

    private suspend fun runSession(configJson: String, sessionGeneration: Long) {
        val mode = runCatching {
            when (JSONObject(configJson).getString("mode")) {
                "proxy" -> EngineMode.PROXY
                else -> EngineMode.TUN
            }
        }.getOrDefault(EngineMode.TUN)

        val chainSettings = ChainSettings.decode(chainJson)
        val splitTunnel = SplitTunnel.decode(splitJson)

        if (carrier != Carrier.AETHER) {
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
        val resolved = configForAttempt(configJson, reconnectAttempt)
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
        EngineStatusStore.update(
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
        val tunFd: Int
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
            tunFd = tun.detachFd()
        } else {
            clearSocketProtector(sessionGeneration)
            tunFd = -1
        }

        val peer = prepared?.peer
        if (engineInPath) {
            EngineStatusStore.update(
                EngineStatus(EngineStage.CONNECTING, mode, peer, sayNow(R.string.status_validating_route)),
            )
            updateNotification(mode, sayNow(R.string.status_connecting_to, peer.orEmpty()))
        } else {
            EngineStatusStore.update(
                EngineStatus(EngineStage.CONNECTING, mode, null, sayNow(R.string.status_starting_chain)),
            )
            updateNotification(mode, sayNow(R.string.status_starting_chain))
        }

        // With the chain on, the engine coming up is the halfway point rather
        // than the destination, so the two paths report different things.
        val tunnelUp = CompletableDeferred<Unit>()
        val listener = NativeEngineListener {
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
            val result = withContext(Dispatchers.IO) {
                NativeAetherBridge.run(engineConfig, peer.orEmpty(), tunFd, listener)
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
            tunFd = tunFd,
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
     * Not one number for all of them. Psiphon races a dozen protocols and is
     * either up in seconds or not coming; tor fetches a consensus and builds a
     * circuit through three relays, and on a filtered network spends most of
     * that working out which directory authorities it can reach. A timeout set
     * for the first would report the second broken for working normally.
     */
    private fun carrierWaitMs(): Long = when (carrier) {
        Carrier.TOR -> TorConfig.bootstrapTimeoutMs(torBridge)
        else -> PSIPHON_WAIT_MS
    }

    private fun stopCarrier() {
        runCatching { psiphon.stop() }
        runCatching { torClient?.stop() }
    }

    private suspend fun runCarrierSession(
        configJson: String,
        mode: EngineMode,
        chainSettings: ChainSettings,
        splitTunnel: SplitTunnel,
        sessionGeneration: Long,
    ) {
        val name = carrier.wireName
        EngineLog.record(LogLevel.INFO, "carrier", "carrying this session on $name")
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

        EngineStatusStore.update(
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
        val tunFd = tun.detachFd()

        if (sessionGeneration != generation) {
            stopCarrier()
            return
        }

        EngineStatusStore.update(
            EngineStatus(EngineStage.CONNECTING, mode, null, sayNow(R.string.status_carrier_connecting, sayNow(carrier.label))),
        )
        updateNotification(mode, sayNow(R.string.status_carrier_connecting, sayNow(carrier.label)))

        if (carrier == Carrier.TOR) {
            // Rebuilt rather than reused. The bridge is part of the torrc tor
            // reads at startup, so a client built for the previous choice would
            // start tor with the previous configuration and report success.
            torClient?.let { runCatching { it.stop() } }
            torClient = TorClient(this, torBridge)
        }
        val client = carrierClient ?: run {
            reportError(mode, sayNow(R.string.err_carrier_failed))
            finishIfCurrent(sessionGeneration)
            return
        }
        val port = client.start(carrierWaitMs()).getOrElse { error ->
            val reason = error.message ?: sayNow(R.string.err_carrier_failed)
            EngineLog.record(LogLevel.ERROR, "carrier", reason)
            stopCarrier()
            if (sessionGeneration != generation) return
            scheduleReconnect(configJson, sessionGeneration, mode, reason)
            return
        }

        if (sessionGeneration != generation) {
            stopCarrier()
            return
        }

        EngineLog.record(LogLevel.INFO, "carrier", "$name is up on 127.0.0.1:$port")
        EngineStatusStore.update(
            EngineStatus(EngineStage.CONNECTING, mode, null, sayNow(R.string.status_starting_chain)),
        )

        val failure = withContext(Dispatchers.IO) {
            chain.startCarrier(
                settings = chainSettings,
                socksPort = port,
                // Psiphon forwards UDP over its own tunnel; Tor carries none at
                // all. Declaring it either way is not cosmetic: a proxy that
                // says it takes datagrams and then drops them makes DNS and
                // QUIC hang, while one that refuses them makes both fall back
                // within a round trip.
                udp = carrier.carriesUdp,
                tunFd = tunFd,
            )
        }
        if (failure != null) {
            EngineLog.record(LogLevel.ERROR, "chain", failure)
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
            sayNow(R.string.status_carrier_carries, sayNow(carrier.label)),
            carrierSocksPort = port,
        )

        // Watched rather than assumed. The carrier is in another process and can
        // be killed on its own -- by the system reclaiming memory, or by its own
        // tunnel giving up -- and mihomo would keep the interface up dialling a
        // listener that has gone, which the phone experiences as connected and
        // carrying nothing.
        client.state.collect { snapshot ->
            if (sessionGeneration != generation) return@collect
            if (snapshot.stage == CarrierStage.FAILED || snapshot.stage == CarrierStage.STOPPED) {
                val reason = snapshot.failure ?: sayNow(R.string.err_carrier_stopped)
                EngineLog.record(LogLevel.ERROR, "carrier", reason)
                runCatching { chain.stop() }
                stopCarrier()
                scheduleReconnect(configJson, sessionGeneration, mode, reason)
                return@collect
            }
        }
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
        tunFd: Int,
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

        EngineStatusStore.update(
            EngineStatus(EngineStage.CONNECTING, mode, peer, sayNow(R.string.status_starting_chain)),
        )
        updateNotification(mode, sayNow(R.string.status_starting_chain))
        val failure = withContext(Dispatchers.IO) {
            chain.start(
                settings = chainSettings,
                socksPort = if (engine != null) socksPort else null,
                tunFd = tunFd,
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
        // A working tunnel is the answer to whatever the blocking was for.
        dropBlackhole()
        // The session's byte counting starts here, not when a screen opens, so
        // the totals cover the whole session however late somebody looks.
        TrafficMeter.start()
        EngineStatusStore.update(
            EngineStatus(
                EngineStage.CONNECTED,
                mode,
                peer,
                message,
                connectedAtMillis = System.currentTimeMillis(),
                carrierSocksPort = carrierSocksPort,
            ),
        )
        updateNotification(mode, message)
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
        EngineStatusStore.update(
            EngineStatus(EngineStage.STOPPING, EngineStatusStore.status.value.mode, message = said),
        )
        // Invalidate and signal immediately, outside commandMutex. A session
        // wedged in a native call may be holding that lock, and waiting for it
        // is what left the service unstoppable.
        generation += 1
        runCatching { NativeAetherBridge.cancelScan() }
        runCatching { NativeAetherBridge.stop() }

        serviceScope.launch {
            // prepare() and run() are blocking JNI calls. On a network that
            // hangs connections rather than refusing them they can sit for
            // minutes, and nativeStop cannot always interrupt a blocked socket
            // read. Give the session a moment to unwind, then tear down
            // regardless -- the user asked it to stop, and the process is going
            // away. Anything still running dies with it.
            withTimeoutOrNull(STOP_GRACE_MS) {
                listOfNotNull(sessionJob).joinAll()
            }
            sessionJob = null
            // Also a JNI call into the Go engine, and this one runs while the
            // user is watching a "Stopping" spinner.
            withContext(Dispatchers.IO) { runCatching { chain.stop() } }
            clearSocketProtector(generation)
            // Rates go to zero, totals stay: what a session cost is asked
            // after it ended, not while it is running.
            TrafficMeter.stop()
            // Strict keeps blocking across the gap between sessions, so the
            // service and its interface outlive the tunnel deliberately. Said
            // plainly in the notification, or a user who forgot they turned it
            // on has a phone with no internet and no reason given.
            if (blockAfterStop && raiseBlackhole("disconnected with strict blocking on")) {
                EngineStatusStore.update(
                    EngineStatus(EngineStage.IDLE, message = sayNow(R.string.traffic_is_blocked)),
                )
                startForegroundNow(sayNow(R.string.traffic_is_blocked), sayNow(R.string.notify_strict_blocking))
                return@launch
            }
            EngineStatusStore.update(EngineStatus())
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
        EngineStatusStore.update(EngineStatus(EngineStage.ERROR, mode, message = message))
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
        EngineStatusStore.update(
            EngineStatus(EngineStage.CONNECTING, mode, message = message),
        )
        updateNotification(mode, message)
        serviceScope.launch {
            delay(delayMs)
            if (sessionGeneration == generation) {
                sessionJob = serviceScope.launch {
                    runSession(nextConfig, sessionGeneration)
                }
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
        val remembered = preferences.getString(LAST_GOOD_TRANSPORT, null)
        val ladder = buildList {
            // Deep, because it is already known to work here.
            remembered?.let { add(it to json.optString("scanMode", "balanced")) }
            // Then both framings, quickly. H2 first: it is TCP on 443 and looks
            // like any other HTTPS connection, so it is the one more likely to
            // survive a filtered network.
            add("h2" to "turbo")
            add("h3" to "turbo")
            // Only then spend a full search on each.
            add("h2" to json.optString("scanMode", "balanced"))
            add("h3" to json.optString("scanMode", "balanced"))
        }.distinct()

        val (transport, scan) = ladder[attempt.coerceAtLeast(0) % ladder.size]
        return json.put("transport", transport).put("scanMode", scan).toString()
    }

    /**
     * Remembers the transport that reached CONNECTED.
     *
     * Only meaningful for Automatic, and only worth writing when it changes:
     * this is on the connect path, and a preference write per session for a
     * value that rarely moves is work nobody asked for.
     */
    private fun rememberWorkingTransport(engineConfig: String) {
        val transport = transportOf(engineConfig)
        if (transport == "auto") return
        if (preferences.getString(LAST_GOOD_TRANSPORT, null) == transport) return
        preferences.edit { putString(LAST_GOOD_TRANSPORT, transport) }
        EngineLog.record(LogLevel.INFO, "auto", "this network carries $transport")
    }

    /** 3s, 6s, 12s, 24s, 48s, then a minute between attempts. */
    private fun reconnectDelayMs(attempt: Int): Long =
        (RECONNECT_DELAY_MS shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_RECONNECT_DELAY_MS)

    /**
     * Called from inside the session coroutine, so it must not join sessionJob --
     * that would be the job waiting on itself. Bumping the generation is what
     * makes the in-flight session inert.
     */
    private fun giveUp(mode: EngineMode, reason: String) {
        generation += 1
        preferences.edit { remove(LAST_TUN_CONFIG) }
        // The moment the feature exists for: every retry is spent, the tunnel
        // is not coming back on its own, and without this the phone resumes
        // over the ordinary route without saying anything.
        val blocking = blockOnFailure && raiseBlackhole(reason)
        reportError(
            mode,
            if (blocking) {
                "$reason. Stopped after $MAX_RECONNECT_ATTEMPTS attempts, and traffic is blocked."
            } else {
                "$reason. Stopped after $MAX_RECONNECT_ATTEMPTS attempts."
            },
        )
        serviceScope.launch {
            runCatching { chain.stop() }
            runCatching { NativeAetherBridge.stop() }
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
        private const val LAST_TUN_CONFIG = "last_tun_config"
        private const val LAST_CHAIN_CONFIG = "last_chain_config"
        private const val LAST_SPLIT_CONFIG = "last_split_config"
        private const val LAST_GOOD_TRANSPORT = "last_good_transport"
        private const val EXTRA_CARRIER = "carrier"
        private const val LAST_CARRIER = "last_carrier"
        private const val EXTRA_TOR_BRIDGE = "torBridge"
        private const val LAST_TOR_BRIDGE = "last_tor_bridge"
        // Psiphon establishes over a network that is actively hostile to it,
        // and its own timeout is two minutes. Ours has to be the longer of
        // the two or we would tear down a tunnel that was about to arrive.
        private const val PSIPHON_WAIT_MS = 150_000L
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
            torBridge: TorBridge = TorBridge.NONE,
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
                    .putExtra(EXTRA_TOR_BRIDGE, torBridge.wireName),
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
