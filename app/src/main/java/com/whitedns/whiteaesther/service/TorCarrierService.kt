package com.whitedns.whiteaesther.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import com.whitedns.whiteaesther.core.TorConfig
import com.whitedns.whiteaesther.data.TorBridge
import java.io.File
import org.torproject.jni.TorService

/**
 * Tor, in a process of its own.
 *
 * Unlike Psiphon this one does not have to be here -- tor is C, and a C library
 * has no runtime to collide with mihomo's. It is here anyway, for two reasons
 * that will matter more later than they do now. tor is loaded through a JNI
 * library with a process-wide lock and static state, so a crash in it takes down
 * whatever process it is in; and its pluggable transports are separate
 * executables that have to be launched and reaped by whoever owns tor, which is
 * a job for a process that can be restarted on its own.
 *
 * The shape is deliberately the same as [PsiphonService], down to the message
 * protocol: a carrier is a SOCKS5 listener on loopback and a state, and having
 * two different shapes for that would be two things to get wrong.
 *
 * [TorService] is Guardian Project's, the one Orbot uses. It is declared in this
 * app's manifest with `android:process` so that it lands here rather than in the
 * main process, and this service binds it because the port it ends up listening
 * on is readable only through its binder.
 */
class TorCarrierService : android.app.Service() {
    private val clients = mutableListOf<Messenger>()
    private var state = State.STOPPED
    private var socksPort = 0
    private var failure: String? = null

    private var torService: TorService? = null
    private var torConnection: ServiceConnection? = null
    private var started = false
    private var transport: PluggableTransport? = null
    private var bootstrapWatcher: Thread? = null

    enum class State { STOPPED, CONNECTING, CONNECTED, FAILED }

    /**
     * tor's own status, which crosses the process boundary on a plain
     * broadcast.
     *
     * [TorService] sends its status twice -- once through
     * LocalBroadcastManager, which never leaves the process it was sent from,
     * and once through the ordinary broadcaster. Only the second one is any use
     * to a listener that might be elsewhere, and registering for it here rather
     * than in the app's own process is what keeps the binder-only port lookup
     * next to the thing that can do it.
     */
    private val torStatus = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_ON -> {
                    // Asked for only now. The port is chosen while tor starts --
                    // 9050 when it is free and an arbitrary one when it is not --
                    // and it is not knowable before tor says it is listening.
                    socksPort = torService?.socksPort ?: 0
                    if (socksPort > 0) {
                        // Listening, which is not the same as usable. This
                        // status arrives when tor's control connection comes up,
                        // and tor has a consensus to fetch and a circuit to
                        // build after that. Reporting CONNECTED here is how a
                        // slow transport ends up looking connected while it
                        // carries nothing -- meek reached this point in seconds
                        // and then failed to answer a single request in three
                        // minutes. The port is published now so the chain can be
                        // rendered; CONNECTED waits for the circuit.
                        broadcast()
                        awaitBootstrap()
                    } else {
                        state = State.FAILED
                        failure = "Tor started without a SOCKS listener"
                        broadcast()
                    }
                }

                TorService.STATUS_STARTING -> {
                    state = State.CONNECTING
                    broadcast()
                }

                TorService.STATUS_OFF, TorService.STATUS_STOPPING -> {
                    // Only meaningful after we asked it to start. TorService
                    // broadcasts OFF as it is created, which would otherwise be
                    // read as a tunnel that came up and went away.
                    if (started && state != State.FAILED) {
                        state = State.STOPPED
                        socksPort = 0
                        broadcast()
                    }
                }
            }
        }
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_REGISTER -> message.replyTo?.let { client ->
                    clients += client
                    send(client, stateMessage())
                }
                MSG_UNREGISTER -> message.replyTo?.let(clients::remove)
                else -> super.handleMessage(message)
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            torStatus,
            IntentFilter(TorService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                TorBridge.entries.firstOrNull { it.wireName == intent.getStringExtra(EXTRA_BRIDGE) }
                    ?: TorBridge.NONE,
                intent.getStringExtra(EXTRA_COUNTRY),
            )
            ACTION_STOP -> {
                stopTor()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTor()
        runCatching { unregisterReceiver(torStatus) }
        super.onDestroy()
    }

    private fun start(bridge: TorBridge, exitCountry: String?) {
        if (started) return
        started = true
        state = State.CONNECTING
        failure = null
        socksPort = 0
        broadcast()

        // The transport first, because the torrc has to name the port it
        // ended up on. tor cannot start it for us -- see PluggableTransport --
        // so a bridge mode whose proxy will not start has to fail here rather
        // than become a tor that quietly connects directly.
        var listening: String? = null
        val wanted = TorConfig.transportName(bridge)
        if (wanted != null) {
            val binary = transportBinary(bridge)
            if (binary == null) {
                fail("This build ships no pluggable transports")
                return
            }
            val proxy = PluggableTransport(binary, File(filesDir, "pt-state"))
            val methods = runCatching { proxy.start(listOf(wanted)) }.getOrElse { error ->
                fail("The $wanted transport did not start: ${error.message}")
                return
            }
            listening = methods[wanted]
            if (listening == null) {
                runCatching { proxy.stop() }
                fail("The $wanted transport started without offering $wanted")
                return
            }
            transport = proxy
            Log.i("tor", "$wanted is listening on $listening")
        }

        runCatching {
            // Written before tor is started, because it is read once at
            // startup. TorService owns torrc-defaults -- that is where the
            // SOCKS port lands -- so this is the file for everything else.
            TorService.getTorrc(this).writeText(
                TorConfig.render(bridge, exitCountry, listening),
            )
        }.onFailure { error ->
            fail("Could not write tor's configuration: ${error.message}")
            return
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                torService = (binder as? TorService.LocalBinder)?.service
                // tor may already be listening by the time this binds, in which
                // case its status broadcast has been and gone and nothing else
                // will arrive. A port is the same evidence the broadcast
                // carries, and asking for it costs nothing when there is none.
                val port = torService?.socksPort ?: 0
                if (port > 0 && state != State.CONNECTED) {
                    socksPort = port
                    broadcast()
                    awaitBootstrap()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                torService = null
            }
        }
        torConnection = connection

        runCatching {
            bindService(
                Intent(this, TorService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure { error ->
            fail(error.message ?: "Tor did not start")
        }
    }

    /**
     * Where the transport executables were extracted, or null if this build
     * ships none.
     *
     * They live in the app's native library directory because that is the only
     * place Android will extract a file from an APK and leave it executable.
     * Checked rather than assumed: a build made without native/tor/build.ps1
     * has tor and no transports, and the bridge modes have to be unavailable
     * rather than produce a torrc naming files that are not there.
     */
    private fun transportBinary(bridge: TorBridge): File? {
        val dir = File(applicationInfo.nativeLibraryDir)
        // Snowflake is its own program; obfs4 and meek_lite both come out of
        // lyrebird. Named by which binary provides it rather than by the
        // transport, because asking the wrong one for a transport it does not
        // implement is a CMETHOD-ERROR and a bridge mode that never works.
        val name = when (bridge) {
            TorBridge.SNOWFLAKE -> "libsnowflake.so"
            else -> "liblyrebird.so"
        }
        return File(dir, name).takeIf { it.exists() }
    }

    /**
     * Waits for tor to finish bootstrapping, then reports CONNECTED.
     *
     * `status/bootstrap-phase` is tor's own account of how far it has got, and
     * `PROGRESS=100` is the only point at which it can carry anything. Polled
     * rather than subscribed because the control connection here is jtorctl's
     * synchronous one and a poll a second for a minute costs nothing measurable.
     *
     * On its own thread: this is called from a broadcast receiver on the main
     * looper, and tor can take minutes behind a bridge.
     */
    private fun awaitBootstrap() {
        if (bootstrapWatcher?.isAlive == true) return
        val watcher = Thread {
            val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && started) {
                val phase = runCatching { torService?.getInfo("status/bootstrap-phase") }
                    .getOrNull()
                    .orEmpty()
                if (phase.contains("PROGRESS=100") || phase.contains("TAG=done")) {
                    state = State.CONNECTED
                    Log.i("tor", "bootstrapped")
                    broadcast()
                    return@Thread
                }
                Thread.sleep(1_000)
            }
            if (started && state != State.CONNECTED) {
                // Not a failure of ours to report as one: the session above has
                // its own deadline and a better message for it. Left CONNECTING
                // so that deadline is what decides.
                Log.w("tor", "still bootstrapping after ${BOOTSTRAP_TIMEOUT_MS / 1000}s")
            }
        }
        bootstrapWatcher = watcher
        watcher.start()
    }

    private fun stopTor() {
        if (!started) return
        started = false
        torConnection?.let { runCatching { unbindService(it) } }
        torConnection = null
        torService = null
        runCatching { stopService(Intent(this, TorService::class.java)) }
        // After tor, not before. A transport killed while tor still holds a
        // connection through it leaves tor retrying a port that has gone.
        transport?.let { runCatching { it.stop() } }
        transport = null
        socksPort = 0
        if (state != State.FAILED) state = State.STOPPED
        broadcast()
    }

    private fun fail(reason: String) {
        state = State.FAILED
        failure = reason
        Log.e("tor", reason)
        broadcast()
    }

    private fun stateMessage(): Message = Message.obtain(null, MSG_STATE).apply {
        arg1 = state.ordinal
        arg2 = socksPort
        failure?.let { data = Bundle().apply { putString(EXTRA_FAILURE, it) } }
    }

    private fun broadcast() {
        clients.toList().forEach { send(it, stateMessage()) }
    }

    private fun send(client: Messenger, message: Message) {
        try {
            client.send(message)
        } catch (_: RemoteException) {
            clients.remove(client)
        }
    }

    companion object {
        const val ACTION_START = "com.whitedns.whiteaesther.TOR_START"
        const val ACTION_STOP = "com.whitedns.whiteaesther.TOR_STOP"
        const val EXTRA_COUNTRY = "country"
        const val EXTRA_BRIDGE = "bridge"
        const val EXTRA_FAILURE = "failure"

        const val MSG_REGISTER = 1
        const val MSG_UNREGISTER = 2
        const val MSG_STATE = 3

        /**
         * How long the watcher keeps asking before it stops.
         *
         * Longer than any carrier deadline above it, deliberately: this thread
         * giving up first would leave the session waiting on a report that is
         * never coming, and the session has the better message for a timeout.
         */
        private const val BOOTSTRAP_TIMEOUT_MS = 420_000L
    }
}
