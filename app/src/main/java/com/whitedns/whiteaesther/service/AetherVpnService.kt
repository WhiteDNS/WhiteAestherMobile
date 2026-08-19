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
import com.whitedns.whiteaesther.MainActivity
import com.whitedns.whiteaesther.core.ChainConfig
import com.whitedns.whiteaesther.core.ChainController
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.NativeEngineListener
import com.whitedns.whiteaesther.core.NativeSocketProtector
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.EngineMode
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private var sessionJob: Job? = null
    private var generation: Long = 0
    private var reconnectAttempt = 0
    // The configuration the user asked for, before any per-attempt
    // transport substitution, so retries never compound.
    private var baseConfigJson: String? = null
    private var chainJson: String? = null
    private val chain by lazy { ChainController(this) }

    override fun onCreate() {
        super.onCreate()
        AetherNotification.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var restartPolicy = START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> stopFromUser()
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (configJson == null) {
                    reportError(null, "Connection settings are missing")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val chainSettings = intent.getStringExtra(EXTRA_CHAIN)
                if (JSONObject(configJson).optString("mode") == "tun") {
                    preferences.edit {
                        putString(LAST_TUN_CONFIG, configJson)
                        putString(LAST_CHAIN_CONFIG, chainSettings)
                    }
                    restartPolicy = START_STICKY
                } else {
                    preferences.edit { remove(LAST_TUN_CONFIG); remove(LAST_CHAIN_CONFIG) }
                }
                startForegroundNow("Preparing connection", "Validating native engine")
                replaceSession(configJson, chainSettings)
            }
            else -> {
                val configJson = preferences.getString(LAST_TUN_CONFIG, null)
                if (configJson == null) {
                    stopSelf(startId)
                } else {
                    restartPolicy = START_STICKY
                    startForegroundNow("Restoring WhiteAesther", "Reconnecting whole-device VPN")
                    replaceSession(configJson, preferences.getString(LAST_CHAIN_CONFIG, null))
                }
            }
        }
        return restartPolicy
    }

    override fun onRevoke() {
        preferences.edit { remove(LAST_TUN_CONFIG); remove(LAST_CHAIN_CONFIG) }
        stopFromUser("VPN permission was revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        generation += 1
        runCatching { chain.stop() }
        NativeAetherBridge.stop()
        runCatching { NativeAetherBridge.setSocketProtector(null) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun replaceSession(configJson: String, chainSettings: String?) {
        serviceScope.launch {
            commandMutex.withLock {
                generation += 1
                reconnectAttempt = 0
                baseConfigJson = configJson
                chainJson = chainSettings
                NativeAetherBridge.stop()
                runCatching { chain.stop() }
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
        val useChain =
            chainSettings.enabled && resolveChainUsage(chainSettings, mode, sessionGeneration)
        if (chainSettings.enabled && !useChain) return
        // Behind the chain the engine stops owning the interface and becomes the
        // SOCKS5 listener mihomo dials its nodes through. Rewritten here rather
        // than stored, so a reconnect still starts from what the user asked for.
        val engineConfig = if (useChain) withEngineMode(configJson, EngineMode.PROXY) else configJson

        // Record what this attempt is actually configured with. Without it a
        // diagnostics report cannot answer the question it was collected for --
        // which transport carried the session, and whether the anti-blocking
        // options were on.
        EngineLog.record(LogLevel.INFO, "config", sessionSummary(engineConfig))
        if (useChain) {
            EngineLog.record(LogLevel.INFO, "chain", "exit chain on, engine dropped to SOCKS")
        }
        EngineStatusStore.update(
            EngineStatus(EngineStage.PREPARING, mode, message = "Preparing identity and gateway"),
        )

        // Dialling the nodes directly takes the engine out of the path entirely,
        // so it is not started: preparing it would scan for a Cloudflare endpoint
        // that nothing would then use, and on a network where MASQUE is dead --
        // the case this mode exists for -- that scan is exactly what never
        // finishes.
        val engineInPath = !useChain || chainSettings.throughTunnel
        val prepared = if (engineInPath) {
            withContext(Dispatchers.IO) {
                NativeAetherBridge.prepare(engineConfig)
            }.getOrElse { error ->
                scheduleReconnect(
                    configJson,
                    sessionGeneration,
                    mode,
                    error.message ?: "Native preparation failed",
                )
                return
            }
        } else {
            null
        }

        if (sessionGeneration != generation) return

        var tun: ParcelFileDescriptor? = null
        val tunFd: Int
        if (mode == EngineMode.TUN) {
            if (prepare(this) != null) {
                reportError(mode, "Android VPN permission is required")
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
            tun = establishTun(prepared?.ipv4.orEmpty(), prepared?.ipv6.orEmpty(), useChain)
            if (tun == null) {
                reportError(mode, "Android could not establish the VPN interface")
                finishIfCurrent(sessionGeneration)
                return
            }
            tunFd = tun.detachFd()
        } else {
            NativeAetherBridge.setSocketProtector(null)
            tunFd = -1
        }

        val peer = prepared?.peer
        if (engineInPath) {
            EngineStatusStore.update(
                EngineStatus(EngineStage.CONNECTING, mode, peer, "Validating encrypted route"),
            )
            updateNotification(mode, "Connecting to $peer")
        } else {
            EngineStatusStore.update(
                EngineStatus(EngineStage.CONNECTING, mode, null, "Starting the exit chain"),
            )
            updateNotification(mode, "Starting the exit chain")
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
            runCatching { NativeAetherBridge.setSocketProtector(null) }

            if (sessionGeneration != generation) return
            scheduleReconnect(
                configJson,
                sessionGeneration,
                mode,
                result.error ?: "The encrypted route closed",
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
            runCatching { NativeAetherBridge.setSocketProtector(null) }
            Unit
        }

        if (engine != null) {
            updateNotification(mode, "Connecting to $peer for the exit chain")
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
                        "The exit chain timed out waiting for a route"
                    } else {
                        "The encrypted route closed before the exit chain started"
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
            EngineStatus(EngineStage.CONNECTING, mode, peer, "Starting the exit chain"),
        )
        updateNotification(mode, "Starting the exit chain")
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

        EngineLog.record(LogLevel.INFO, "chain", "exit chain up on ${chain.nodes().nodes.size} nodes")
        reportConnected(
            mode,
            peer,
            if (engine != null) {
                "Traffic leaves through the exit chain"
            } else {
                "Traffic leaves through the exit chain, dialled directly"
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
        scheduleReconnect(configJson, sessionGeneration, mode, "The encrypted route closed")
    }

    private fun reportConnected(mode: EngineMode, peer: String?, message: String) {
        EngineStatusStore.update(
            EngineStatus(
                EngineStage.CONNECTED,
                mode,
                peer,
                message,
                connectedAtMillis = System.currentTimeMillis(),
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
            !chain.isAvailable -> "The exit chain is not available in this build"
            mode != EngineMode.TUN -> "The exit chain needs whole-device coverage"
            else -> settings.startupError()
        } ?: return true

        reportError(mode, refusal)
        EngineLog.record(LogLevel.ERROR, "chain", refusal)
        finishIfCurrent(sessionGeneration)
        return false
    }

    private fun withEngineMode(configJson: String, mode: EngineMode): String = runCatching {
        JSONObject(configJson).put("mode", mode.wireName).toString()
    }.getOrDefault(configJson)

    /**
     * @param forChain when true the interface belongs to mihomo, so it carries
     *   mihomo's addresses and resolver rather than the engine's.
     */
    private fun establishTun(
        ipv4: String,
        ipv6: String,
        forChain: Boolean,
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

        if (forChain) {
            // Everything this process opens stays off the interface: the engine's
            // sockets to Cloudflare, mihomo's to its nodes, and the subscription
            // fetch that would otherwise race the tunnel it is being downloaded
            // to configure. protect() covers the same ground one socket at a
            // time, and both are kept -- this one the kernel enforces, that one
            // depends on every caller remembering.
            runCatching { builder.addDisallowedApplication(packageName) }.onFailure {
                EngineLog.record(LogLevel.WARN, "chain", "could not exclude self: ${it.message}")
            }
            // 9000, not 1280. mihomo terminates TCP in userspace, so this sizes a
            // local write rather than anything that reaches the wire, and a
            // larger one means fewer crossings per megabyte.
            builder.setMtu(9000)
            addAddress(builder, ChainConfig.TUN_IPV4, 30)
            addAddress(builder, ChainConfig.TUN_IPV6, 126)
            builder.addDnsServer(ChainConfig.TUN_DNS)
            builder.addRoute("::", 0)
        } else {
            builder.setMtu(1280)
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("1.0.0.1")
            addAddress(builder, ipv4, 32)
            if (ipv6.isNotBlank()) {
                addAddress(builder, ipv6, 128)
                builder.addDnsServer("2606:4700:4700::1111")
                builder.addDnsServer("2606:4700:4700::1001")
                builder.addRoute("::", 0)
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

    private fun stopFromUser(message: String = "Stopped") {
        preferences.edit { remove(LAST_TUN_CONFIG); remove(LAST_CHAIN_CONFIG) }
        startForegroundNow("Stopping WhiteAesther", message)
        EngineStatusStore.update(
            EngineStatus(EngineStage.STOPPING, EngineStatusStore.status.value.mode, message = message),
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
            runCatching { chain.stop() }
            runCatching { NativeAetherBridge.setSocketProtector(null) }
            EngineStatusStore.update(EngineStatus())
            ServiceCompat.stopForeground(this@AetherVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

    private fun transportOf(configJson: String): String =
        runCatching { JSONObject(configJson).optString("transport", "h3") }.getOrDefault("h3")

    /**
     * The engine takes one transport and never falls back between them. H3 rides
     * QUIC, and a network that blocks UDP kills it outright -- reported from MCI
     * in Iran, where QUIC has been down for weeks while H2 over TCP still works.
     * Retrying the same dead transport eight times is eight guaranteed failures,
     * so alternate: the configured one on odd attempts, the other on even.
     */
    private fun configForAttempt(configJson: String, attempt: Int): String {
        if (attempt <= 1) return configJson
        return runCatching {
            val json = JSONObject(configJson)
            val configured = json.optString("transport", "h3")
            val other = if (configured == "h3") "h2" else "h3"
            json.put("transport", if (attempt % 2 == 0) other else configured).toString()
        }.getOrDefault(configJson)
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
        reportError(mode, "$reason. Stopped after $MAX_RECONNECT_ATTEMPTS attempts.")
        serviceScope.launch {
            runCatching { chain.stop() }
            runCatching { NativeAetherBridge.stop() }
            runCatching { NativeAetherBridge.setSocketProtector(null) }
            sessionJob = null
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
            EngineMode.PROXY -> "WhiteAesther proxy"
            EngineMode.TUN -> "WhiteAesther device VPN"
            null -> "WhiteAesther"
        }
        getSystemService(android.app.NotificationManager::class.java).notify(
            AetherNotification.NOTIFICATION_ID,
            AetherNotification.build(this, title, text),
        )
    }

    private fun connectedMessage(mode: EngineMode, configJson: String): String = when (mode) {
        EngineMode.TUN -> "Whole-device traffic is protected"
        EngineMode.PROXY -> {
            val port = runCatching { JSONObject(configJson).getInt("listenPort") }.getOrDefault(1819)
            "SOCKS5 listening on 127.0.0.1:$port"
        }
    }

    companion object {
        private const val ACTION_START = "com.whitedns.whiteaesther.START"
        private const val ACTION_STOP = "com.whitedns.whiteaesther.STOP"
        private const val EXTRA_CONFIG = "config"
        private const val EXTRA_CHAIN = "chain"
        private const val LAST_TUN_CONFIG = "last_tun_config"
        private const val LAST_CHAIN_CONFIG = "last_chain_config"
        // How long the chain waits for the tunnel it dials its nodes through.
        // Generous, because that tunnel is itself still searching for a route.
        private const val TUNNEL_WAIT_MS = 120_000L
        private const val DEFAULT_SOCKS_PORT = 1819
        private const val EVENT_DRAIN_MS = 5_000L
        // Long enough for a healthy session to unwind, short enough that a
        // wedged one never leaves the user with only force-stop.
        private const val STOP_GRACE_MS = 4_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 8

        fun start(context: Context, configJson: String, chainJson: String? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AetherVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONFIG, configJson)
                    .putExtra(EXTRA_CHAIN, chainJson),
            )
        }

        fun stopIntent(context: Context): Intent = Intent(context, AetherVpnService::class.java)
            .setAction(ACTION_STOP)

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }
    }

    private val preferences by lazy {
        getSharedPreferences("aether_service", MODE_PRIVATE)
    }
}
