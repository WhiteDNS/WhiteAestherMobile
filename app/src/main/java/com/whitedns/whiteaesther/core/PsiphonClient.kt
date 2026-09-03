package com.whitedns.whiteaesther.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.whitedns.whiteaesther.service.PsiphonService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the Psiphon process is doing, as seen from this one.
 *
 * [port] is set as soon as the listener exists, which is before the tunnel is
 * established -- the two are separate because routing into a listener with no
 * tunnel behind it is a way to drop traffic silently.
 */
data class PsiphonState(
    val state: PsiphonService.State = PsiphonService.State.STOPPED,
    val port: Int = 0,
    val failure: String? = null,
)

/**
 * The near side of [PsiphonService].
 *
 * Binding rather than broadcasting, for two reasons that both matter here. A
 * bound service with [Context.BIND_IMPORTANT] is raised to the importance of the
 * binder, which for us is a foreground VpnService -- an unbound background
 * process carrying a tunnel is one the system may reclaim while the screen is
 * off. And a Messenger delivers to whoever is bound now, so a state change
 * cannot be missed by a client that was not listening yet: the service answers
 * a registration with the current state rather than only the next one.
 */
class PsiphonClient(private val context: Context) {
    private val mutableState = MutableStateFlow(PsiphonState())
    val state = mutableState

    private var outgoing: Messenger? = null
    private var connection: ServiceConnection? = null

    private val incoming = Messenger(
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != PsiphonService.MSG_STATE) {
                    super.handleMessage(message)
                    return
                }
                val state = PsiphonService.State.entries
                    .getOrElse(message.arg1) { PsiphonService.State.STOPPED }
                mutableState.value = PsiphonState(
                    state = state,
                    port = message.arg2,
                    failure = message.peekData()?.getString(PsiphonService.EXTRA_FAILURE),
                )
            }
        },
    )

    /**
     * Starts Psiphon and waits for a tunnel.
     *
     * @return the loopback SOCKS5 port, or a failure describing why there is
     *   none. A timeout is reported as a failure rather than as a port that
     *   might work later: the caller is about to route an interface into this,
     *   and a proxy with no tunnel behind it swallows packets rather than
     *   refusing them.
     */
    suspend fun start(egressRegion: String, timeoutMs: Long): Result<Int> {
        bind()
        context.startService(
            Intent(context, PsiphonService::class.java)
                .setAction(PsiphonService.ACTION_START)
                .putExtra(PsiphonService.EXTRA_REGION, egressRegion),
        )

        val settled = withTimeoutOrNull(timeoutMs) {
            mutableState.first { snapshot ->
                when (snapshot.state) {
                    PsiphonService.State.CONNECTED -> snapshot.port > 0
                    PsiphonService.State.FAILED -> true
                    // STOPPED is the state before the service has answered as
                    // much as it is the state after it gives up, so it is not
                    // an outcome on its own.
                    else -> false
                }
            }
        }

        return when {
            settled == null -> Result.failure(
                IllegalStateException("Psiphon did not connect in ${timeoutMs / 1000}s"),
            )
            settled.state == PsiphonService.State.FAILED -> Result.failure(
                IllegalStateException(settled.failure ?: "Psiphon failed to start"),
            )
            else -> Result.success(settled.port)
        }
    }

    fun stop() {
        runCatching {
            context.startService(
                Intent(context, PsiphonService::class.java).setAction(PsiphonService.ACTION_STOP),
            )
        }
        unbind()
        mutableState.value = PsiphonState()
    }

    private suspend fun bind() {
        if (connection != null) return
        val established = kotlinx.coroutines.CompletableDeferred<Unit>()
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val messenger = Messenger(binder)
                outgoing = messenger
                runCatching {
                    messenger.send(
                        Message.obtain(null, PsiphonService.MSG_REGISTER).apply { replyTo = incoming },
                    )
                }
                established.complete(Unit)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The process died. Reported as a failure rather than left at
                // whatever it last said, because the caller is waiting on a
                // state that is never going to arrive now.
                outgoing = null
                mutableState.value = PsiphonState(
                    state = PsiphonService.State.FAILED,
                    failure = "The Psiphon process stopped unexpectedly",
                )
            }
        }
        connection = newConnection
        context.bindService(
            Intent(context, PsiphonService::class.java),
            newConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        // Bounded: bindService can simply never call back if the component is
        // missing from the manifest, and a suspend that waits forever there is
        // a connect button that never returns.
        withTimeoutOrNull(BIND_TIMEOUT_MS) { established.await() }
    }

    private fun unbind() {
        outgoing?.let { messenger ->
            runCatching {
                messenger.send(
                    Message.obtain(null, PsiphonService.MSG_UNREGISTER).apply { replyTo = incoming },
                )
            }
        }
        outgoing = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
