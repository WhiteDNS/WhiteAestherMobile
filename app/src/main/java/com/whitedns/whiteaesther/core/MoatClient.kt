package com.whitedns.whiteaesther.core

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Asks Tor which bridges to use here.
 *
 * This is the service Tor Browser's own "Connection Assist" uses. It takes a
 * country and answers with the transports Tor currently recommends for it,
 * newest first, each with real bridge lines -- and, unlike the built-in list,
 * those lines come from BridgeDB, which hands out different ones to different
 * people. That difference is the whole point: a bridge everybody has is a
 * bridge everybody's censor has.
 *
 * No CAPTCHA. The per-user endpoint has one; this one does not, because what it
 * returns is already rate-limited by being tied to a country rather than to a
 * request.
 */
object MoatClient {
    private const val HOST = "bridges.torproject.org"
    private const val PATH = "/moat/circumvention/settings"
    private const val ENDPOINT = "https://bridges.torproject.org/moat/circumvention/settings"
    private const val CONTENT_TYPE = "application/vnd.api+json"
    private const val TIMEOUT_MS = 30_000

    /** One transport Tor recommends, with the bridges to use it with. */
    data class Recommendation(val transport: String, val lines: List<String>)

    /**
     * The country to ask about.
     *
     * The network's, not the SIM's and not the phone's language. The SIM says
     * where an account was opened and the language says what someone prefers to
     * read; neither says which network is doing the filtering. Falls back to the
     * locale only when there is no network operator to ask, which is roughly
     * only on wifi-only hardware.
     */
    fun country(context: Context): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val fromNetwork = telephony?.networkCountryIso?.takeIf { it.length == 2 }
        return (fromNetwork ?: Locale.getDefault().country).lowercase(Locale.US)
    }

    /**
     * @param socksPort a loopback SOCKS5 proxy to ask through, or null to ask
     *   directly. This matters more than it looks: bridges.torproject.org is
     *   blocked in most of the places its answer is needed, so the request goes
     *   through whichever carrier is already carrying the device -- which is
     *   something this app has and Tor Browser does not.
     *
     *   Through a carrier it goes out via [CarriedSocket] rather than through
     *   `openConnection(proxy)`, because that resolves the name on this device
     *   before it dials. On a network that answers the censor's address for
     *   this name -- which is most of the networks where the answer is worth
     *   having -- it asked the carrier to fetch the block page instead, so the
     *   feature failed exactly where the comment above says it is needed.
     * @return what Tor recommends, in the order it recommends it, or a failure.
     */
    suspend fun recommendations(
        country: String,
        socksPort: Int? = null,
    ): Result<List<Recommendation>> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = """{"country":"$country"}"""
            val body = when (socksPort) {
                null -> askDirectly(payload)
                else -> {
                    val answer = CarriedSocket.request(
                        host = HOST,
                        path = PATH,
                        socksPort = socksPort,
                        timeoutMs = TIMEOUT_MS,
                        method = "POST",
                        contentType = CONTENT_TYPE,
                        body = payload,
                    )
                    if (answer.status != HttpURLConnection.HTTP_OK) {
                        error("Tor's bridge service answered ${answer.status}")
                    }
                    answer.body
                }
            }
            parse(body)
        }
    }

    /**
     * The same request with no carrier in front, for a phone on a network that
     * does not need one.
     */
    private fun askDirectly(payload: String): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", CONTENT_TYPE)
            // Nothing that identifies this client. The endpoint does not ask
            // and there is no reason to volunteer.
            setRequestProperty("User-Agent", "")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray()) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("Tor's bridge service answered ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(body: String): List<Recommendation> {
        val settings = JSONObject(body).optJSONArray("settings") ?: return emptyList()
        return buildList {
            for (index in 0 until settings.length()) {
                val bridges = settings.optJSONObject(index)?.optJSONObject("bridges") ?: continue
                val transport = bridges.optString("type").takeIf { it.isNotBlank() } ?: continue
                val strings = bridges.optJSONArray("bridge_strings") ?: continue
                val lines = buildList {
                    for (line in 0 until strings.length()) {
                        strings.optString(line).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                if (lines.isNotEmpty()) add(Recommendation(transport, lines))
            }
        }
    }
}
