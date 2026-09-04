package com.whitedns.whiteaesther.core

/**
 * Bridge lines, whether the user pasted them or the app fetched them.
 *
 * One representation for both on purpose. A line from `@GetBridgesBot` and a
 * line from Tor's own recommendation service are the same thing in the same
 * format, and giving them two paths through the app would mean two ways for
 * either to be wrong.
 */
object TorBridges {
    /**
     * Which binary provides a transport.
     *
     * lyrebird carries obfs4, meek_lite and webtunnel; snowflake is its own
     * program. Asking the wrong one for a transport it does not implement is a
     * `CMETHOD-ERROR` and a bridge mode that never works, so this is decided
     * from the line rather than from what the screen was showing.
     */
    fun binaryFor(transport: String): String =
        if (transport == "snowflake") "libsnowflake.so" else "liblyrebird.so"

    /**
     * The lines in [text] that are usable bridges.
     *
     * Forgiving about what surrounds them, because of where they come from: a
     * user pastes a Telegram reply complete with its greeting, and a blank line
     * or a stray word between bridges should not cost them the whole paste. A
     * line that is not a bridge is dropped rather than refused.
     */
    fun parse(text: String): List<String> = text.lines()
        .map { it.trim() }
        .filter { line ->
            if (line.isEmpty() || line.startsWith("#")) return@filter false
            val parts = line.split(' ')
            // <transport> <address:port> [fingerprint] [options]. The address is
            // what separates a bridge line from a sentence about bridges.
            parts.size >= 2 &&
                parts[0].all { it.isLetterOrDigit() || it == '_' } &&
                parts[1].contains(':')
        }

    /**
     * Which transport a set of lines needs, or null if they name none.
     *
     * The first line decides, and lines naming a different transport are
     * dropped by [forTransport]. Mixing them would mean starting two proxies
     * and telling tor about one, which fails as a bridge it cannot reach rather
     * than as the configuration error it is.
     */
    fun transportOf(lines: List<String>): String? =
        lines.firstOrNull()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** Only the lines belonging to [transport]. */
    fun forTransport(lines: List<String>, transport: String): List<String> =
        lines.filter { it.startsWith("$transport ") }

    /**
     * A short description of what a paste amounts to, for the screen.
     *
     * The count matters as much as the type: one bridge is a single point of
     * failure and the user is the only one who can do anything about that.
     */
    fun summarise(text: String): String? {
        val lines = parse(text)
        val transport = transportOf(lines) ?: return null
        val count = forTransport(lines, transport).size
        return "$count $transport"
    }
}
