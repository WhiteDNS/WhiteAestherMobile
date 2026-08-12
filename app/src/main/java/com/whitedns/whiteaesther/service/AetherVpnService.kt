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
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.NativeEngineListener
import com.whitedns.whiteaesther.core.NativeSocketProtector
import com.whitedns.whiteaesther.data.EngineMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
                if (JSONObject(configJson).optString("mode") == "tun") {
                    preferences.edit { putString(LAST_TUN_CONFIG, configJson) }
                    restartPolicy = START_STICKY
                } else {
                    preferences.edit { remove(LAST_TUN_CONFIG) }
                }
                startForegroundNow("Preparing connection", "Validating native engine")
                replaceSession(configJson)
            }
            else -> {
                val configJson = preferences.getString(LAST_TUN_CONFIG, null)
                if (configJson == null) {
                    stopSelf(startId)
                } else {
                    restartPolicy = START_STICKY
                    startForegroundNow("Restoring WhiteAesther", "Reconnecting whole-device VPN")
                    replaceSession(configJson)
                }
            }
        }
        return restartPolicy
    }

    override fun onRevoke() {
        preferences.edit { remove(LAST_TUN_CONFIG) }
        stopFromUser("VPN permission was revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        generation += 1
        NativeAetherBridge.stop()
        runCatching { NativeAetherBridge.setSocketProtector(null) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun replaceSession(configJson: String) {
        serviceScope.launch {
            commandMutex.withLock {
                generation += 1
                reconnectAttempt = 0
                NativeAetherBridge.stop()
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
        EngineStatusStore.update(
            EngineStatus(EngineStage.PREPARING, mode, message = "Preparing identity and gateway"),
        )

        val prepared = withContext(Dispatchers.IO) {
            NativeAetherBridge.prepare(configJson)
        }.getOrElse { error ->
            scheduleReconnect(
                configJson,
                sessionGeneration,
                mode,
                error.message ?: "Native preparation failed",
            )
            return
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
            tun = establishTun(prepared.ipv4, prepared.ipv6)
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

        EngineStatusStore.update(
            EngineStatus(EngineStage.CONNECTING, mode, prepared.peer, "Validating encrypted route"),
        )
        updateNotification(mode, "Connecting to ${prepared.peer}")

        val listener = NativeEngineListener {
            reconnectAttempt = 0
            EngineStatusStore.update(
                EngineStatus(EngineStage.CONNECTED, mode, prepared.peer, connectedMessage(mode, configJson)),
            )
            updateNotification(mode, connectedMessage(mode, configJson))
        }
        val result = withContext(Dispatchers.IO) {
            NativeAetherBridge.run(configJson, prepared.peer, tunFd, listener)
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
    }

    private fun establishTun(ipv4: String, ipv6: String): ParcelFileDescriptor? {
        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Builder()
            .setSession("WhiteAesther")
            .setConfigureIntent(configureIntent)
            .setMtu(1280)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
            .addRoute("0.0.0.0", 0)

        addAddress(builder, ipv4, 32)
        if (ipv6.isNotBlank()) {
            addAddress(builder, ipv6, 128)
            builder.addDnsServer("2606:4700:4700::1111")
            builder.addDnsServer("2606:4700:4700::1001")
            builder.addRoute("::", 0)
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
        preferences.edit { remove(LAST_TUN_CONFIG) }
        startForegroundNow("Stopping WhiteAesther", message)
        EngineStatusStore.update(
            EngineStatus(EngineStage.STOPPING, EngineStatusStore.status.value.mode, message = message),
        )
        serviceScope.launch {
            commandMutex.withLock {
                generation += 1
                NativeAetherBridge.stop()
                listOfNotNull(sessionJob).joinAll()
                sessionJob = null
                runCatching { NativeAetherBridge.setSocketProtector(null) }
                EngineStatusStore.update(EngineStatus())
                ServiceCompat.stopForeground(this@AetherVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
        val message = "$reason · retry $reconnectAttempt of $MAX_RECONNECT_ATTEMPTS in ${delayMs / 1_000}s"
        EngineStatusStore.update(
            EngineStatus(EngineStage.CONNECTING, mode, message = message),
        )
        updateNotification(mode, message)
        serviceScope.launch {
            delay(delayMs)
            if (sessionGeneration == generation) {
                sessionJob = serviceScope.launch {
                    runSession(configJson, sessionGeneration)
                }
            }
        }
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
        private const val LAST_TUN_CONFIG = "last_tun_config"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 8

        fun start(context: Context, configJson: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AetherVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONFIG, configJson),
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
