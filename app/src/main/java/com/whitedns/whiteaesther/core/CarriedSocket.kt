package com.whitedns.whiteaesther.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * HTTPS to a host, fetched by a carrier rather than by this phone.
 *
 * Exists because the obvious way to do this is wrong in a way that only shows
 * up on the networks this app is for. `URL.openConnection(proxy)` resolves the
 * host here before it dials -- measured, not assumed: pointed at a listener
 * that records what it is asked for, it sends `104.16.123.96` rather than
 * `www.cloudflare.com`. Where the resolver answers with the censor's address
 * for names worth asking about, that turns every request through a working
 * carrier into a request to fetch the block page. Three places in this app did
 * it that way, and two of them carried a comment explaining that the name was
 * resolved at the far end.
 *
 * SOCKS5 carries a name form precisely so the far end does the lookup, and all
 * three carriers here resolve it somewhere the phone's resolver cannot reach:
 * the engine asks through its own tunnel, Psiphon at its server, tor at the
 * exit. [InetSocketAddress.createUnresolved] is what makes Java use that form.
 *
 * The TLS layer is verified against the name, which is the other half. Once the
 * lookup happens somewhere else, "something answered" stops being evidence --
 * an interception answers too. A certificate chaining to a trusted root and
 * matching the name asked for is something only the real host can present.
 */
object CarriedSocket {
    private const val CRLF = "\r\n"
    private const val HTTPS_PORT = 443

    /** The response to a request made through a carrier. */
    data class Answer(val status: Int, val body: String)

    /**
     * Opens a verified TLS session to [host]:[port] through the SOCKS5 proxy on
     * loopback at [socksPort].
     *
     * The caller owns the socket and must close it. Throws if the carrier
     * refuses, the handshake fails, or the certificate does not match [host].
     */
    fun openTls(host: String, port: Int, socksPort: Int, timeoutMs: Int): SSLSocket {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val carried = Socket(proxy)
        try {
            // Unresolved, deliberately. A resolved address here is one this
            // device looked up, which is the thing being routed around.
            carried.connect(InetSocketAddress.createUnresolved(host, port), timeoutMs)
            carried.soTimeout = timeoutMs

            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(carried, host, port, true) as SSLSocket
            // Names the host for SNI and, with this set, for the certificate
            // check: a socket layered over an existing connection validates the
            // chain but not who it belongs to, so without this any valid
            // certificate for any name would pass.
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            tls.startHandshake()
            return tls
        } catch (error: Throwable) {
            runCatching { carried.close() }
            throw error
        }
    }

    /**
     * One HTTPS request through the carrier, written by hand.
     *
     * By hand because every client that would do this for us resolves the name
     * first -- see above. It is a small amount of HTTP: one request, `Connection:
     * close`, and the body read to end of stream, with chunked transfer decoded
     * because an edge will use it whatever we ask for.
     */
    fun request(
        host: String,
        path: String,
        socksPort: Int,
        timeoutMs: Int,
        method: String = "GET",
        contentType: String? = null,
        body: String? = null,
    ): Answer = openTls(host, HTTPS_PORT, socksPort, timeoutMs).use { tls ->
        val payload = body?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1").append(CRLF)
            append("Host: ").append(host).append(CRLF)
            // Nothing that identifies this client. Nothing here asks, and there
            // is no reason to volunteer.
            append("User-Agent:").append(CRLF)
            append("Accept: */*").append(CRLF)
            contentType?.let { append("Content-Type: ").append(it).append(CRLF) }
            payload?.let { append("Content-Length: ").append(it.size).append(CRLF) }
            append("Connection: close").append(CRLF).append(CRLF)
        }

        tls.outputStream.write(head.toByteArray(Charsets.US_ASCII))
        payload?.let(tls.outputStream::write)
        tls.outputStream.flush()

        parseAnswer(tls.inputStream.readBytes().toString(Charsets.UTF_8), host)
    }

    /**
     * Splits one HTTP/1.1 response into its status and its body.
     *
     * Separate from the socket so it can be tested without one, which matters
     * more than usual here: this is hand-written HTTP, and the reason it is
     * hand-written has nothing to do with wanting to parse HTTP.
     */
    internal fun parseAnswer(raw: String, host: String): Answer {
        val split = raw.indexOf(CRLF + CRLF)
        if (split < 0) throw IOException("$host answered without a complete header")
        val header = raw.substring(0, split)
        val rest = raw.substring(split + 4)

        val status = header.lineSequence().first()
            .split(' ')
            .getOrNull(1)
            ?.toIntOrNull()
            ?: throw IOException("$host answered without a status")

        val chunked = header.lineSequence().any {
            it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", true)
        }

        return Answer(status, if (chunked) dechunk(rest) else rest)
    }

    /**
     * Joins a chunked body back together.
     *
     * Stops at the first chunk header that is not a size, which covers both the
     * terminating zero and a truncated answer -- neither is worth an error of
     * its own, because the caller is going to fail to parse what it gets either
     * way and can say so in terms of what it wanted.
     */
    internal fun dechunk(body: String): String = buildString {
        var cursor = 0
        while (cursor < body.length) {
            val lineEnd = body.indexOf(CRLF, cursor)
            if (lineEnd < 0) return@buildString
            val size = body.substring(cursor, lineEnd)
                .substringBefore(';')
                .trim()
                .toIntOrNull(16)
                ?: return@buildString
            if (size == 0) return@buildString
            val from = lineEnd + 2
            val to = minOf(from + size, body.length)
            append(body, from, to)
            cursor = to + 2
        }
    }
}
