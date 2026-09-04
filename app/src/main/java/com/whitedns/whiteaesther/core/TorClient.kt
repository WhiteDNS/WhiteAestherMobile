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
import com.whitedns.whiteaesther.service.TorCarrierService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The near side of [TorCarrierService].
 *
 * The same arrangement as [PsiphonClient] and for the same reasons: bound
 * rather than broadcast, so the carrier's process is raised to the importance of
 * the foreground service holding the interface, and so a client that binds after
 * the tunnel is already up is told the current state rather than waiting for a
 * change that has been and gone.
 */
class TorClient(private val context: Context) : CarrierClient {
    private val mutableState = MutableStateFlow(CarrierSnapshot())
    override val state = mutableState

    private var outgoing: Messenger? = null
    private var connection: ServiceConnection? = null

    private val incoming = Messenger(
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != TorCarrierService.MSG_STATE) {
                    super.handleMessage(message)
                    return
                }
                val reported = TorCarrierService.State.entries
                    .getOrElse(message.arg1) { TorCarrierService.State.STOPPED }
                mutableState.value = CarrierSnapshot(
                    stage = when (reported) {
                        TorCarrierService.State.STOPPED -> CarrierStage.STOPPED
                        TorCarrierService.State.CONNECTING -> CarrierStage.CONNECTING
                        TorCarrierService.State.CONNECTED -> CarrierStage.CONNECTED
                        TorCarrierService.State.FAILED -> CarrierStage.FAILED
                    },
                    port = message.arg2,
                    failure = message.peekData()?.getString(TorCarrierService.EXTRA_FAILURE),
                )
            }
        },
    )

    override suspend fun start(timeoutMs: Long): Result<Int> {
        bind()
        context.startService(
            Intent(context, TorCarrierService::class.java)
                .setAction(TorCarrierService.ACTION_START),
        )

        val settled = withTimeoutOrNull(timeoutMs) {
            mutableState.first { snapshot ->
                when (snapshot.stage) {
                    CarrierStage.CONNECTED -> snapshot.port > 0
                    CarrierStage.FAILED -> true
                    else -> false
                }
            }
        }

        return when {
            settled == null -> Result.failure(
                // Named rather than generic, because the usual reason for this
                // one is not a slow network: it is a network where tor's
                // directory authorities are unreachable, and without a
                // pluggable transport there is nothing else for it to try.
                IllegalStateException("Tor did not build a circuit in ${timeoutMs / 1000}s"),
            )
            settled.stage == CarrierStage.FAILED -> Result.failure(
                IllegalStateException(settled.failure ?: "Tor failed to start"),
            )
            else -> Result.success(settled.port)
        }
    }

    override fun stop() {
        runCatching {
            context.startService(
                Intent(context, TorCarrierService::class.java)
                    .setAction(TorCarrierService.ACTION_STOP),
            )
        }
        unbind()
        mutableState.value = CarrierSnapshot()
    }

    private suspend fun bind() {
        if (connection != null) return
        val established = CompletableDeferred<Unit>()
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val messenger = Messenger(binder)
                outgoing = messenger
                runCatching {
                    messenger.send(
                        Message.obtain(null, TorCarrierService.MSG_REGISTER)
                            .apply { replyTo = incoming },
                    )
                }
                established.complete(Unit)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                outgoing = null
                mutableState.value = CarrierSnapshot(
                    stage = CarrierStage.FAILED,
                    failure = "The Tor process stopped unexpectedly",
                )
            }
        }
        connection = newConnection
        context.bindService(
            Intent(context, TorCarrierService::class.java),
            newConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        withTimeoutOrNull(BIND_TIMEOUT_MS) { established.await() }
    }

    private fun unbind() {
        outgoing?.let { messenger ->
            runCatching {
                messenger.send(
                    Message.obtain(null, TorCarrierService.MSG_UNREGISTER)
                        .apply { replyTo = incoming },
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
