package com.whitedns.whiteaesther.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Which address the internet sees, before the tunnel and after it.
 *
 * Cloudflare's own trace endpoint answers both questions. It is not a neutral
 * choice and it is not an arbitrary one: the tunnel already terminates on
 * Cloudflare, so asking them tells them nothing they do not already handle, and
 * it is reachable on networks where a general-purpose "what is my IP" service
 * is not. Adding a third party here would mean handing this device's real
 * address to someone who had no part in carrying its traffic.
 */
object AddressReporter {
    /**
     * Cloudflare answers this over whichever family the connection used, and
     * the hostname carries both records -- so on a dual-stack network Java
     * picks IPv6 and the answer is an IPv6 address regardless of what the user
     * asked for.
     */
    private const val TRACE_HOST = "https://www.cloudflare.com/cdn-cgi/trace"

    /**
     * The same endpoint reached over IPv4 only.
     *
     * An address literal rather than a name, because a name is what let the
     * resolver choose. Cloudflare's certificate covers this address, so TLS
     * still verifies.
     */
    private const val TRACE_V4 = "https://1.1.1.1/cdn-cgi/trace"

    private const val TIMEOUT_MS = 6_000

    /**
     * Longer than the direct one, and generously so.
     *
     * This is three hops rather than none, and on meek every one of them is a
     * CDN round trip -- measured at over twenty seconds for a single request on
     * a working circuit. Nothing waits on this but a row on a screen, so the
     * cost of being patient is a field that fills in late, and the cost of not
     * being is a blank one under a tunnel that is working.
     */
    private const val CARRIER_TIMEOUT_MS = 60_000

    private val Context.addressStore by preferencesDataStore(name = "whiteaesther_address")
    private val REAL_ADDRESS = stringPreferencesKey("real_address")

    /**
     * The address this device has without the tunnel.
     *
     * Read once, while disconnected, and remembered. Refreshing it during a
     * session would mean sending the user's real address out past the tunnel
     * that exists to hide it -- so the stored answer goes stale instead, and a
     * stale answer is the safe one to be wrong about.
     */
    suspend fun realAddress(context: Context, ipv4Only: Boolean = false): String? =
        context.addressStore.data.first()[REAL_ADDRESS]
            ?.takeUnless(String::isBlank)
            // A stored answer outlives the setting that produced it. One
            // captured over IPv6 kept being shown after the user switched to
            // IPv4 only, which reads exactly like the switch doing nothing.
            ?.takeIf { !ipv4Only || !it.contains(':') }

    /**
     * Looks the real address up and stores it. Only safe while disconnected.
     *
     * The caller is responsible for that: there is nothing in a socket that
     * says whether a tunnel is up, so this cannot check for itself.
     */
    suspend fun captureRealAddress(context: Context, ipv4Only: Boolean = false): String? {
        val address = fetch(ipv4Only) ?: return null
        context.addressStore.edit { preferences -> preferences[REAL_ADDRESS] = address }
        return address
    }

    /**
     * The address seen from inside the tunnel.
     *
     * Not stored. It belongs to one session, and showing the previous session's
     * exit while a new one is negotiating would be a confident lie.
     */
    suspend fun tunnelAddress(ipv4Only: Boolean = false): String? = fetch(ipv4Only)

    /**
     * The address seen from beyond a carrier, asked through the carrier itself.
     *
     * [tunnelAddress] cannot answer this. It dials from this process, and this
     * process is excluded from the interface, so what it measures on a carrier
     * session is the phone's own address -- reported under a heading that says
     * the opposite.
     *
     * By name rather than by literal, unlike everywhere else here. The address
     * form is how the direct lookup avoids the resolver choosing IPv6, and it
     * is exactly wrong through a carrier: Psiphon's servers refuse a port
     * forward to 1.1.1.1, and a name is resolved at the far end where it also
     * cannot leak.
     */
    suspend fun carrierAddress(socksPort: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val connection = (URL(TRACE_HOST).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = CARRIER_TIMEOUT_MS
                readTimeout = CARRIER_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.takeUnless { it.isNullOrBlank() }
    }

    /**
     * Reads the address, over the family the user chose.
     *
     * When they have turned IPv6 off and only an IPv6 answer can be had, this
     * returns null rather than the answer. Showing an IPv6 address on a screen
     * where the user has switched IPv6 off reads as the setting being ignored,
     * which is worse than an empty field.
     */
    private suspend fun fetch(ipv4Only: Boolean): String? {
        // IPv4 first, always. The two rows exist to be compared, and they
        // cannot be when one is IPv6 and the other IPv4 -- which is what a
        // dual-stack phone produced, because the resolver prefers IPv6 for the
        // direct lookup while the tunnel exits on IPv4.
        request(TRACE_V4)?.let { return it }
        // Only when IPv4 could not be had at all: a v6-only network, or a
        // network where the literal is blocked. Better a v6 answer than none.
        return if (ipv4Only) null else request(TRACE_HOST)
    }

    private suspend fun request(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                // The endpoint answers plain text either way; an empty agent
                // matches what the engine sends and keeps the two consistent.
                setRequestProperty("User-Agent", "")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.takeUnless { it.isNullOrBlank() }
    }
}
