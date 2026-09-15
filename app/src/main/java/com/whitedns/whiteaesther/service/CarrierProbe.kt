package com.whitedns.whiteaesther.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.selects.select
import com.whitedns.whiteaesther.core.CarriedSocket

/**
 * Whether anything actually reaches the internet through a carrier.
 *
 * A carrier that says it is connected believes it has a tunnel, which is not the
 * same thing. Automatic is choosing between routes, and the worst one to settle
 * on is a route that finished its handshake and carries nothing: the screen says
 * connected and no page loads. So a route counts only once something has made
 * the round trip through it.
 *
 * The verdict is a verified TLS handshake through the carrier, not an HTTP
 * status. This used to be an `HttpURLConnection` through a `Proxy` asking for a
 * 200-to-399, which was wrong twice over on the networks this app is for: it
 * resolved the host on the phone before dialling -- see [CarriedSocket] -- and
 * then accepted whatever answered. A status line proves nothing once the far end
 * is doing the lookup, because an interception answers too, while a certificate
 * matching the name asked for is something only the real host can present. It is
 * also cheaper: the handshake is the whole test, and no body ever has to arrive.
 *
 * What this does not measure is UDP. A carrier whose listener refuses datagrams
 * passes here, which is correct -- that is declared rather than discovered, in
 * Carrier.carriesUdp -- but a pass is not a statement about it.
 */
object CarrierProbe {
    /**
     * Hosts to try, by name.
     *
     * Two, unrelated, so one exit that a single site refuses is not mistaken for
     * a route that carries nothing. Both are ordinary HTTPS on 443, which is
     * what a carrier has to carry before anything else matters.
     */
    private val TARGETS = listOf("www.cloudflare.com", "www.google.com")

    private const val PORT = 443

    /** Tor's round trips are three relays long; this is a check, not a benchmark. */
    private const val TIMEOUT_MS = 20_000

    /**
     * True as soon as any target answers.
     *
     * The requests run outside this coroutine's scope on purpose: a blocking
     * socket read does not hear a cancellation, and a scope that waited for the
     * slow target to time out would turn the first answer into the last.
     */
    suspend fun works(socksPort: Int): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val pending: MutableList<Deferred<Boolean>> =
                TARGETS.map { host -> scope.async { answers(host, socksPort) } }.toMutableList()
            while (pending.isNotEmpty()) {
                val (done, reached) = select<Pair<Deferred<Boolean>, Boolean>> {
                    pending.forEach { attempt -> attempt.onAwait { attempt to it } }
                }
                if (reached) return true
                pending.remove(done)
            }
            return false
        } finally {
            scope.cancel()
        }
    }

    /**
     * Opens a verified TLS session to [host] through the carrier, and says
     * whether it got one.
     */
    private fun answers(host: String, socksPort: Int): Boolean = runCatching {
        CarriedSocket.openTls(host, PORT, socksPort, TIMEOUT_MS).use { tls ->
            EngineLog.record(
                LogLevel.INFO,
                "auto",
                "probe reached $host through 127.0.0.1:$socksPort (${tls.session.protocol})",
            )
            true
        }
    }.getOrElse { error ->
        EngineLog.record(
            LogLevel.DEBUG,
            "auto",
            "probe of $host through 127.0.0.1:$socksPort did not answer: ${error.message}",
        )
        false
    }
}
