package com.whitedns.whiteaesther.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.selects.select
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Whether anything actually reaches the internet through a carrier.
 *
 * A carrier that says it is connected believes it has a tunnel, which is not the
 * same thing. Automatic is choosing between routes, and the worst one to settle
 * on is a route that finished its handshake and carries nothing: the screen says
 * connected and no page loads. So a route counts only once a real request has
 * made the round trip through it.
 */
object CarrierProbe {
    private val TARGETS = listOf(
        // What the address row already asks through a carrier, so a route that
        // passes here is one that row can fill in too.
        "https://www.cloudflare.com/cdn-cgi/trace",
        // Someone unrelated, so one exit being refused by one site is not
        // mistaken for a route that carries nothing.
        "https://www.google.com/generate_204",
    )

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
                TARGETS.map { url -> scope.async { answers(url, socksPort) } }.toMutableList()
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
     * Any answer at all, redirects included. A site that redirects has been
     * reached, and reached is the question.
     */
    private fun answers(url: String, socksPort: Int): Boolean = runCatching {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val connection = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "")
        }
        try {
            connection.responseCode in 200..399
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
