package com.whitedns.whiteaesther.core

import java.io.File

/**
 * Which nodes use REALITY, read from what the chain already has on disk.
 *
 * mihomo reports a node's name and protocol but not its security layer, so a
 * REALITY node arrives looking exactly like any other VLESS one and then fails
 * to authenticate. Naming them requires the source text -- and the source text
 * is already here: mihomo caches every fetched subscription under the chain's
 * own providers directory, so nothing is downloaded again and nothing leaves
 * the device to work this out.
 *
 * Shown rather than hidden. A node quietly missing from a list of seven reads
 * as a broken subscription, and the user has no way to tell which.
 */
object RealityNodes {
    /**
     * Names of the REALITY nodes among the chain's sources.
     *
     * Empty when nothing has been fetched yet, which is also what a chain that
     * has never run looks like -- the caller treats both the same, because an
     * unmarked node is the state everything worked in before.
     */
    private var cachedStamp: String? = null
    private var cached: Set<String> = emptySet()

    /**
     * Cached against the provider files' own names, sizes and timestamps.
     *
     * Without this the whole subscription was read from disk, base64-decoded
     * and parsed on every call -- and it is called after every node refresh and
     * after every selection. On a list of fifty that work landed between the
     * tap and the screen responding, which is where the lag came from.
     *
     * The stamp is cheap: a directory listing, no file contents. mihomo
     * rewrites a provider file when it refetches, so a changed subscription
     * changes the stamp.
     */
    @Synchronized
    fun detect(home: File): Set<String> {
        val providers = File(home, "providers")
        if (!providers.isDirectory) {
            cachedStamp = null
            cached = emptySet()
            return emptySet()
        }
        val files = providers.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
        val stamp = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (stamp == cachedStamp) return cached

        cached = files
            .flatMap { file -> namesIn(runCatching { file.readText() }.getOrDefault("")) }
            .toSet()
        cachedStamp = stamp
        return cached
    }

    /**
     * Reads both shapes a provider file can take.
     *
     * A subscription is either a Clash YAML document or a base64 blob of URI
     * links, and mihomo accepts both, so both have to be understood here.
     */
    internal fun namesIn(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        return fromClash(trimmed) + fromLinks(trimmed) + fromLinks(decodeBase64(trimmed))
    }

    /**
     * The Clash form: a proxy entry carrying `reality-opts`.
     *
     * Parsed by indentation rather than with a YAML library. The question is
     * narrow -- which named entries mention one key -- and a parser for the
     * whole format would be a dependency and a new way to fail on a document
     * mihomo has already accepted.
     */
    private fun fromClash(text: String): List<String> {
        val found = mutableListOf<String>()
        var name: String? = null
        var reality = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            // A new entry closes the previous one.
            if (line.startsWith("- ")) {
                if (reality) name?.let(found::add)
                name = inlineName(line) ?: blockName(line)
                reality = line.contains("reality-opts")
                continue
            }
            if (name == null) continue
            if (line.startsWith("name:")) {
                name = line.removePrefix("name:").trim().trim('"', '\'')
            }
            if (line.startsWith("reality-opts")) reality = true
        }
        if (reality) name?.let(found::add)
        return found
    }

    /** `- {name: x, ...}` and `- name: x` both start an entry on the same line. */
    private fun inlineName(line: String): String? {
        val start = line.indexOf("name:")
        if (start < 0) return null
        val rest = line.substring(start + "name:".length).trimStart()
        return rest.takeWhile { it != ',' && it != '}' }.trim().trim('"', '\'').ifBlank { null }
    }

    private fun blockName(line: String): String? =
        line.removePrefix("- ").trim().takeIf { it.startsWith("name:") }
            ?.removePrefix("name:")?.trim()?.trim('"', '\'')?.ifBlank { null }

    /**
     * The URI form: `security=reality` in the query, name in the fragment.
     *
     * The fragment is percent-encoded, and node names routinely carry spaces
     * and emoji, so it is decoded before being matched against what mihomo
     * reports.
     */
    private fun fromLinks(text: String): List<String> =
        text.lineSequence()
            .map(String::trim)
            .filter { it.contains("://") && it.contains("security=reality", ignoreCase = true) }
            .mapNotNull { line ->
                val fragment = line.substringAfter('#', "")
                percentDecode(fragment).takeIf { it.isNotBlank() }
            }
            .toList()

    /**
     * Decodes a base64 subscription, or returns nothing.
     *
     * Both alphabets, because subscriptions use either and a link containing a
     * `-` or `_` decoded under the wrong one turns into bytes that match
     * nothing rather than into an error.
     */
    private fun decodeBase64(text: String): String {
        if (text.contains("://")) return ""
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return ""
        // java.util.Base64 rather than android.util: it exists from API 26,
        // which is this app's minimum, and unlike the Android one it also runs
        // in a plain unit test.
        val decoders = listOf(
            java.util.Base64.getMimeDecoder(),
            java.util.Base64.getUrlDecoder(),
        )
        for (decoder in decoders) {
            val decoded = runCatching { String(decoder.decode(padded(compact))) }.getOrNull()
            if (decoded != null && decoded.contains("://")) return decoded
        }
        return ""
    }

    /** Subscriptions are routinely served unpadded; the decoders require it. */
    private fun padded(value: String): String = when (value.length % 4) {
        2 -> "$value=="
        3 -> "$value="
        else -> value
    }

    private fun percentDecode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}
