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
                        state = State.CONNECTED
                    } else {
                        state = State.FAILED
                        failure = "Tor started without a SOCKS listener"
                    }
                    broadcast()
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
            ACTION_START -> start(intent.getStringExtra(EXTRA_COUNTRY))
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

    private fun start(exitCountry: String?) {
        if (started) return
        started = true
        state = State.CONNECTING
        failure = null
        socksPort = 0
        broadcast()

        runCatching {
            // Written before tor is started, because it is read once at
            // startup. TorService owns torrc-defaults -- that is where the
            // SOCKS port lands -- so this is the file for everything else.
            TorService.getTorrc(this).writeText(TorConfig.render(exitCountry))
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
                    state = State.CONNECTED
                    broadcast()
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

    private fun stopTor() {
        if (!started) return
        started = false
        torConnection?.let { runCatching { unbindService(it) } }
        torConnection = null
        torService = null
        runCatching { stopService(Intent(this, TorService::class.java)) }
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
        const val EXTRA_FAILURE = "failure"

        const val MSG_REGISTER = 1
        const val MSG_UNREGISTER = 2
        const val MSG_STATE = 3
    }
}
