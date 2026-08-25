package com.whitedns.whiteaesther.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
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

    /** True when the address belongs to the family the user asked for. */
    internal fun matchesFamily(address: String, ipv4Only: Boolean): Boolean =
        !ipv4Only || !address.contains(':')

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
     * Reads the address, over the family the user chose.
     *
     * When they have turned IPv6 off and only an IPv6 answer can be had, this
     * returns null rather than the answer. Showing an IPv6 address on a screen
     * where the user has switched IPv6 off reads as the setting being ignored,
     * which is worse than an empty field.
     */
    private suspend fun fetch(ipv4Only: Boolean): String? {
        if (!ipv4Only) return request(TRACE_HOST)
        return request(TRACE_V4)
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
