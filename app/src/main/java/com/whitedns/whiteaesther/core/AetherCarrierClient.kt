package com.whitedns.whiteaesther.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The engine, standing in as one hop of a carrier path.
 *
 * [CarrierClient] was written for carriers that are not the engine, and for a
 * session carried by Aether alone that distinction still holds: the engine takes
 * the interface itself, which is faster and fewer moving parts than routing it
 * through mihomo into a loopback listener. Nothing about that path changes.
 *
 * This class is for the other case. When Aether is one link of a chain it has to
 * look like every other link from the outside -- something that starts, takes a
 * while, and ends in a SOCKS5 port on loopback -- so that the session can run a
 * list of them without knowing which is which.
 *
 * The config handed in is expected to be in proxy mode already and, when this is
 * not the first hop, to name the hop in front of it as its upstream proxy. The
 * engine consults that proxy for registration, for the API, for the H2 transport
 * and for the endpoint scan; it does not consult it for H3 or WireGuard, which
 * are datagrams. Choosing a transport the proxy can actually carry is the
 * caller's job, not this class's.
 */
class AetherCarrierClient(
    private val scope: CoroutineScope,
    private val engineConfig: String,
) : CarrierClient {
    private val mutableState = MutableStateFlow(CarrierSnapshot())
    override val state = mutableState

    private var engine: Job? = null

    /**
     * True between a [stop] and the engine job it cancelled finishing.
     *
     * The watcher below cannot tell a tunnel that broke from one that was taken
     * down on purpose -- both arrive as the run call returning -- and reporting
     * a deliberate stop as a failure would have the service reconnect a session
     * the user had just ended.
     */
    @Volatile
    private var stopping = false

    override suspend fun start(timeoutMs: Long): Result<Int> {
        stopping = false
        mutableState.value = CarrierSnapshot(stage = CarrierStage.CONNECTING)

        // Inside the budget, not before it. prepare provisions an identity and
        // picks an endpoint, and a thorough search of those can run for five
        // minutes -- so a start that only began counting afterwards had no
        // ceiling at all, and the race's budgets described a wait that was
        // already over. The watchdog is a separate coroutine because the call
        // itself is blocking JNI: nothing suspends inside it for a timeout to
        // fire at, and cancelPrepare is what actually ends it.
        val deadline = System.currentTimeMillis() + timeoutMs
        val watchdog = scope.launch {
            delay(timeoutMs)
            NativeAetherBridge.cancelPrepare()
        }
        val preparation = try {
            withContext(Dispatchers.IO) { NativeAetherBridge.prepare(engineConfig) }
        } finally {
            watchdog.cancel()
        }
        val prepared = preparation.getOrElse { error ->
            return failed(error.message ?: "the engine could not prepare a route")
        }

        // Whatever preparation did not spend. A route that opens is still worth
        // waiting for, but not on top of a budget that has already gone.
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) {
            return failed("the engine did not find a route in time")
        }

        val port = runCatching {
            // The engine's own default, and what the settings write here anyway.
            JSONObject(engineConfig).optInt("listenPort", DEFAULT_LISTEN_PORT)
        }.getOrDefault(DEFAULT_LISTEN_PORT)

        // run() returns when the engine stops, so it cannot be awaited for
        // readiness -- the listener is what says a route opened. Both outcomes
        // end the wait: the route opened, or the engine stopped and there will
        // never be one.
        val up = CompletableDeferred<Unit>()
        var outcome: NativeResult? = null
        val job = scope.launch(Dispatchers.IO) {
            outcome = NativeAetherBridge.run(
                engineConfig,
                prepared.peer,
                NO_INTERFACE,
                NativeEngineListener { up.complete(Unit) },
            )
        }
        engine = job

        val reached = withTimeoutOrNull(remaining) {
            select {
                up.onAwait { true }
                job.onJoin { false }
            }
        }

        if (reached != true) {
            stop()
            return failed(
                when (reached) {
                    null -> "the engine did not open a route in time"
                    else -> outcome?.error ?: "the engine stopped before it opened a route"
                },
            )
        }

        mutableState.value = CarrierSnapshot(stage = CarrierStage.CONNECTED, port = port)

        // The wait above ends at the first route; the engine goes on running
        // after it, and can stop at any point in the session that follows --
        // a transport that dies, a keepalive that is never answered. Nothing
        // used to publish that: the snapshot stayed CONNECTED, the hop's
        // watcher had no event to act on, and the phone sat behind an
        // interface routed into a listener that had gone.
        scope.launch {
            job.join()
            // A later start has replaced this engine, or stop() took it down
            // and has already said so. Either way this is not news.
            if (stopping || engine !== job) return@launch
            mutableState.value = CarrierSnapshot(
                stage = CarrierStage.FAILED,
                failure = outcome?.error ?: "the engine stopped",
            )
        }
        return Result.success(port)
    }

    override fun stop() {
        // Stops a running engine, and a preparation that has not produced one
        // yet: nativeStop reaches only the session, and cancelScan belongs to
        // the endpoint scanner rather than to prepare's own search. Neither is
        // waited for -- see raceLanes.
        stopping = true
        runCatching { NativeAetherBridge.cancelPrepare() }
        runCatching { NativeAetherBridge.stop() }
        engine?.cancel()
        engine = null
        mutableState.value = CarrierSnapshot(stage = CarrierStage.STOPPED)
    }

    private fun failed(reason: String): Result<Int> {
        mutableState.value = CarrierSnapshot(stage = CarrierStage.FAILED, failure = reason)
        return Result.failure(IllegalStateException(reason))
    }

    private companion object {
        const val DEFAULT_LISTEN_PORT = 1819

        /** No interface: this hop hands mihomo a listener, not a tunnel device. */
        const val NO_INTERFACE = -1
    }
}
