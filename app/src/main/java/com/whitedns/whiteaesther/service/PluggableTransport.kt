package com.whitedns.whiteaesther.service

import android.util.Log
import java.io.File

/**
 * A pluggable transport, launched by us rather than by tor.
 *
 * tor normally spawns these itself -- `ClientTransportPlugin obfs4 exec <path>`
 * -- and speaks the managed-proxy protocol to whatever it started. Guardian
 * Project's Android build cannot: `exec` aborts inside `pt_parse_transport_line`
 * before tor has logged a word, which is the same as saying this libtor was
 * built without the fork it needs. Measured, not assumed: the identical torrc
 * with `socks5` in place of `exec` starts cleanly.
 *
 * So this does tor's half of that protocol. It sets the environment the spec
 * defines, starts the binary, and reads back the `CMETHOD` lines saying which
 * loopback port each transport ended up on -- which is exactly what the
 * `socks5` form of the line then tells tor.
 *
 * See pt-spec.txt, "Pluggable Transport Specification (Version 1)".
 */
class PluggableTransport(
    private val binary: File,
    private val stateDir: File,
) {
    private var process: Process? = null

    /**
     * Starts [transports] and returns where each one is listening.
     *
     * @return transport name to `host:port`, empty if the proxy started but
     *   offered none of what was asked for.
     */
    fun start(transports: List<String>): Map<String, String> {
        check(process == null) { "already started" }
        stateDir.mkdirs()

        val builder = ProcessBuilder(binary.absolutePath)
        builder.directory(stateDir)
        // Errors on stdout with everything else, so one reader sees the whole
        // conversation in the order it happened.
        builder.redirectErrorStream(true)
        builder.environment().apply {
            put("TOR_PT_MANAGED_TRANSPORT_VER", "1")
            put("TOR_PT_STATE_LOCATION", stateDir.absolutePath)
            put("TOR_PT_CLIENT_TRANSPORTS", transports.joinToString(","))
            // The proxy exits when its stdin closes, which is how it is stopped:
            // a transport left running after tor has gone is a listener on
            // loopback that nothing owns.
            put("TOR_PT_EXIT_ON_STDIN_CLOSE", "1")
        }

        val started = builder.start()
        process = started

        val methods = mutableMapOf<String, String>()
        val reader = started.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS

        // Read until the proxy says it is done, it says it cannot, or it has
        // taken so long that it is not going to. Blocking is right here: there
        // is nothing to do until it answers, and the answer arrives in
        // milliseconds when it arrives at all.
        while (System.currentTimeMillis() < deadline) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            Log.d("pt", line)
            val parts = line.trim().split(' ')
            when (parts.firstOrNull()) {
                // CMETHOD <transport> <protocol> <address:port> [options]
                "CMETHOD" -> if (parts.size >= 4) methods[parts[1]] = parts[3]
                "CMETHODS" -> if (parts.getOrNull(1) == "DONE") return methods
                // The proxy refusing one transport is not the proxy failing:
                // the others may still be listening, so this is recorded and
                // the loop continues to CMETHODS DONE.
                "CMETHOD-ERROR" -> Log.w("pt", line)
                "ENV-ERROR", "VERSION-ERROR" -> {
                    stop()
                    return emptyMap()
                }
            }
        }
        return methods
    }

    /**
     * Stops the transport.
     *
     * Its stdin first, which is what the spec says to do and what lets it close
     * its listeners on the way out; destroy afterwards for the case where it
     * ignored that.
     */
    fun stop() {
        val running = process ?: return
        process = null
        runCatching { running.outputStream.close() }
        runCatching { running.waitFor() }
        runCatching { running.destroy() }
    }

    private companion object {
        /**
         * Generous for what it is. The handshake is local and instant; a
         * transport that has not answered in ten seconds has failed to start,
         * and waiting longer only delays saying so.
         */
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
    }
}
