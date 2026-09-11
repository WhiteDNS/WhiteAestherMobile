package com.whitedns.whiteaesther.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import ca.psiphon.PsiphonTunnel
import com.whitedns.whiteaesther.core.PsiphonConfig
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Psiphon, in a process of its own.
 *
 * The process is the whole reason this class exists. psiphon-tunnel-core is Go,
 * and so is the exit chain; two `-buildmode=c-shared` Go runtimes in one linker
 * namespace export the same symbols and the first call to bind to the wrong copy
 * enters a runtime that has never heard of the calling goroutine. `:psiphon` in
 * the manifest is what keeps them apart, and it costs an IPC hop that carries
 * four integers.
 *
 * What it produces is a SOCKS5 listener on loopback. It never sees the tun and
 * never asks for one -- [PsiphonTunnel.setVpnMode] is false, so tunnel-core
 * routes nothing and merely proxies. The interface belongs to
 * [AetherVpnService], which hands it to mihomo pointed at the port reported
 * back from here.
 *
 * Its own sockets must leave by the physical network or the tunnel would be
 * carrying the thing that builds it. `protect()` cannot help: that is a call on
 * a descriptor in the VpnService's process and these are in this one. What does
 * help is that both processes share a uid, and the interface excludes this
 * package -- see `applySplitTunnel(excludeSelf = true)`, which is unconditional
 * on the chain path this carrier always takes.
 */
class PsiphonService : Service() {
    private var tunnel: PsiphonTunnel? = null
    private val clients = mutableListOf<Messenger>()
    private val socksPort = AtomicInteger(0)
    private var state = State.STOPPED
    private var failure: String? = null

    /** Notices forwarded to the report this session, so tunnel-core cannot flood it. */
    private var forwarded = 0

    /** Servers dialled per protocol since the last summary. */
    private val attempts = linkedMapOf<String, Int>()

    enum class State { STOPPED, CONNECTING, CONNECTED, FAILED }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_REGISTER -> message.replyTo?.let { client ->
                    clients += client
                    // Answered immediately rather than only on the next change.
                    // A client that binds after the tunnel is already up would
                    // otherwise wait for an event that has been and gone.
                    send(client, stateMessage())
                }
                MSG_UNREGISTER -> message.replyTo?.let(clients::remove)
                else -> super.handleMessage(message)
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?) = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                intent.getStringExtra(EXTRA_REGION).orEmpty(),
                intent.getIntExtra(EXTRA_UPSTREAM, 0),
            )
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Never restarted by the system on its own. This process carries traffic
        // only while the interface above it exists, and one revived without it
        // would be a Psiphon tunnel nothing is routed into.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun start(egressRegion: String, upstreamPort: Int) {
        if (tunnel != null) return
        state = State.CONNECTING
        failure = null
        socksPort.set(0)
        forwarded = 0
        attempts.clear()
        broadcast()

        val entries = runCatching {
            assets.open(PsiphonConfig.SERVER_ENTRIES_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            // The list is fetched at build time, not committed, so a build that
            // skipped native/psiphon/setup.ps1 arrives here. Saying so beats a
            // tunnel that spends two minutes dialling an empty list.
            fail("The Psiphon server list is missing from this build (${error.message})")
            return
        }

        val host = object : PsiphonTunnel.HostService {
            override fun getContext(): Context = this@PsiphonService
            override fun getPsiphonConfig(): String =
                PsiphonConfig.render(this@PsiphonService, egressRegion, upstreamPort)

            override fun onAvailableEgressRegions(regions: MutableList<String>?) {
                // Recorded rather than reported. It changes rarely and nothing
                // is waiting on it -- the screen reads it the next time it is
                // opened, which is soon enough for a list of countries.
                PsiphonConfig.rememberRegions(this@PsiphonService, regions.orEmpty())
            }

            override fun onListeningSocksProxyPort(port: Int) {
                socksPort.set(port)
                // Not connected yet: the listener is up before a tunnel is, and
                // routing into it now would send packets into a proxy with
                // nowhere to forward them. The port is carried so the chain can
                // be rendered while the tunnel finishes establishing.
                broadcast()
            }

            override fun onConnected() {
                state = State.CONNECTED
                broadcast()
            }

            override fun onConnecting() {
                // Reconnects land here too, after a CONNECTED. The interface
                // above stays up either way -- mihomo holds the tun and the
                // SOCKS listener does not go away -- so this is reported rather
                // than acted on.
                if (state == State.CONNECTED) {
                    state = State.CONNECTING
                    broadcast()
                }
            }

            override fun onExiting() {
                if (state != State.FAILED) {
                    state = State.STOPPED
                    broadcast()
                }
            }

            override fun onDiagnosticMessage(message: String) {
                // All of it to logcat, and a few kinds of it to the report.
                //
                // Only the state changes used to cross, on the reasoning that
                // tunnel-core emits hundreds of notices while establishing and
                // would evict the engine's own lines from a 400-line buffer. The
                // reasoning holds; the conclusion left every report about a
                // Psiphon that would not connect saying only that it did not,
                // with nothing about how many servers it had, which protocols it
                // tried, or whether it ever got tactics. So a short list of
                // notice types crosses, capped per session, and the hundreds of
                // per-server attempts are folded into one line of counts.
                Log.d("psiphon", message)
                handler.post { consider(message) }
            }
        }

        runCatching {
            PsiphonTunnel.newPsiphonTunnel(host).also {
                tunnel = it
                // Proxy only. VPN mode is tunnel-core building its own
                // interface, and there is already one of those.
                it.setVpnMode(false)
                it.startTunneling(entries)
            }
        }.onFailure { error ->
            tunnel = null
            fail(error.message ?: "Psiphon did not start")
        }
    }

    private fun stopTunnel() {
        val running = tunnel ?: return
        tunnel = null
        runCatching { running.stop() }
        socksPort.set(0)
        if (state != State.FAILED) state = State.STOPPED
        broadcast()
    }

    /**
     * Decides whether a notice is worth a line in the user's report.
     *
     * PsiphonTunnel does not pass the notice through as tunnel-core wrote it: it
     * arrives as "Type: {json}", the type pulled out in front of its data. A
     * JSON parse of the whole string fails on every one of them, which is how
     * the first version of this forwarded nothing at all.
     *
     * Success is ConnectedServer. ActiveTunnel, which reads like the obvious
     * signal, is not delivered here.
     */
    private fun consider(raw: String) {
        val separator = raw.indexOf(": ")
        if (separator <= 0) return
        val type = raw.substring(0, separator)
        val payload = runCatching { JSONObject(raw.substring(separator + 2)) }.getOrNull()
        when (type) {
            "ConnectingServer" -> {
                val protocol = payload?.optString("protocol").orEmpty().ifBlank { "unknown" }
                attempts[protocol] = (attempts[protocol] ?: 0) + 1
            }
            "ConnectedServer" -> {
                summariseAttempts()
                forwardText(
                    "connected over ${payload?.optString("protocol").orEmpty()} " +
                        "to a server in ${payload?.optString("region").orEmpty()}",
                    warn = false,
                )
            }
            // Where Psiphon believes the phone is. If this says the wrong country
            // the tactics it applied were for the wrong network.
            "ClientRegion" ->
                forwardText("Psiphon sees this device in ${payload?.optString("region").orEmpty()}", warn = false)
            "EstablishTunnelTimeout", "Exiting" -> {
                summariseAttempts()
                forward(type, payload, warn = type == "EstablishTunnelTimeout")
            }
            "CandidateServers", "RequestedTactics", "Tactics" ->
                forward(type, payload, warn = false)
            "Alert", "Error", "ServerAlert", "UpstreamProxyError", "LocalProxyError" ->
                forward(type, payload, warn = true)
        }
    }

    private fun summariseAttempts() {
        if (attempts.isEmpty()) return
        val total = attempts.values.sum()
        val detail = attempts.entries.joinToString(", ") { "${it.key} x${it.value}" }
        attempts.clear()
        forwardText("dialled $total servers: $detail", warn = false)
    }

    private fun forward(type: String, payload: JSONObject?, warn: Boolean) {
        forwardText(
            buildString {
                append(type)
                payload?.let { append(' ').append(it.toString().take(MAX_NOTICE_CHARS)) }
            },
            warn,
        )
    }

    private fun forwardText(text: String, warn: Boolean) {
        if (forwarded >= MAX_FORWARDED) return
        forwarded += 1
        clients.toList().forEach { client ->
            send(
                client,
                Message.obtain(null, MSG_NOTICE).apply {
                    arg1 = if (warn) 1 else 0
                    data = Bundle().apply { putString(EXTRA_NOTICE, text) }
                },
            )
        }
    }

    private fun fail(reason: String) {
        state = State.FAILED
        failure = reason
        Log.e("psiphon", reason)
        broadcast()
    }

    private fun stateMessage(): Message = Message.obtain(null, MSG_STATE).apply {
        arg1 = state.ordinal
        arg2 = socksPort.get()
        failure?.let { data = Bundle().apply { putString(EXTRA_FAILURE, it) } }
    }

    private fun broadcast() {
        // Copied before iterating: send() prunes the list when a client's
        // process has gone, and that is a modification mid-iteration.
        clients.toList().forEach { send(it, stateMessage()) }
    }

    private fun send(client: Messenger, message: Message) {
        try {
            client.send(message)
        } catch (_: RemoteException) {
            // The other side died. Dropping it here is the only cleanup there
            // is: a Messenger has no death notification of its own.
            clients.remove(client)
        }
    }

    companion object {
        const val ACTION_START = "com.whitedns.whiteaesther.PSIPHON_START"
        const val ACTION_STOP = "com.whitedns.whiteaesther.PSIPHON_STOP"
        const val EXTRA_REGION = "region"
        const val EXTRA_UPSTREAM = "upstream"
        const val EXTRA_FAILURE = "failure"
        const val EXTRA_NOTICE = "notice"

        const val MSG_REGISTER = 1
        const val MSG_UNREGISTER = 2
        const val MSG_STATE = 3
        const val MSG_NOTICE = 4

        /** A session's worth of report lines, out of a 400-line buffer. */
        private const val MAX_FORWARDED = 40
        private const val MAX_NOTICE_CHARS = 300
    }
}
