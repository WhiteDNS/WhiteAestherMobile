package com.whitedns.whiteaesther.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A source of exit nodes: a subscription, or a block of pasted config URIs.
 *
 * Mirrors the desktop's `ChainSource` so the two clients describe a chain the
 * same way and a settings export moves between them.
 */
data class ChainSource(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("url", url)
        .put("enabled", enabled)

    companion object {
        fun fromJson(json: JSONObject): ChainSource? {
            val url = json.optString("url").trim()
            if (url.isEmpty()) return null
            return ChainSource(
                name = json.optString("name").ifBlank { "Subscription" },
                url = url,
                enabled = json.optBoolean("enabled", true),
            )
        }
    }
}

/**
 * The second hop.
 *
 * With the chain on, Aether stops owning the tunnel interface and drops to the
 * SOCKS5 listener it already supports; mihomo takes the interface and dials its
 * nodes back through Aether. Traffic leaves from the node rather than from
 * Cloudflare, which is the point -- a service that blocks Cloudflare egress
 * ranges sees the node instead.
 */
data class ChainSettings(
    val enabled: Boolean = false,
    /**
     * Dial the nodes from inside the MASQUE tunnel.
     *
     * On by default, and worth keeping: it is what hides the node's address and
     * SNI from the local network. But it makes the chain impossible whenever the
     * tunnel cannot connect, so it can be turned off to reach the nodes directly
     * instead of reaching nothing at all.
     */
    val throughTunnel: Boolean = true,
    val sources: List<ChainSource> = emptyList(),
    /** Config URIs pasted by hand, one per line. mihomo converts these itself. */
    val manual: String = "",
    /** The node last selected, so a reconnect returns to it. */
    val node: String? = null,
    /**
     * The user's routing rules, carried here as well as to the engine.
     *
     * Duplicated deliberately. With a chain running, mihomo owns the interface
     * and the engine only sees node addresses, so the engine's copy of these
     * rules can never match a destination -- mihomo has to be told separately
     * or the rules silently stop working the moment a chain is switched on.
     */
    val routeBlock: String = "",
    val routeDirect: String = "",
    /**
     * Nodes the user does not want offered, by name.
     *
     * Set aside rather than deleted, because a node from a subscription is not
     * ours to delete: mihomo fetches the list again on the next connect and the
     * node comes straight back. Keeping the names here is the only form of
     * "remove" that survives a refresh -- and it is reversible, which deletion
     * of somebody else's list should be.
     */
    val hiddenNodes: List<String> = emptyList(),
) {
    /** Rule lines, with blanks and notes dropped as the engine drops them. */
    fun blockRules(): List<String> = ruleLines(routeBlock)

    fun directRules(): List<String> = ruleLines(routeDirect)

    private fun ruleLines(raw: String): List<String> =
        raw.split('\n', ',', ';')
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** True when there is anything for mihomo to route through. */
    val hasNodes: Boolean
        get() = sources.any { it.enabled && it.url.isNotBlank() } || manual.isNotBlank()

    /**
     * Why the chain cannot start, or null when it can.
     *
     * Checked before touching the engine so the reason names the missing piece
     * rather than surfacing as a config mihomo rejected.
     */
    fun startupError(): String? = when {
        !enabled -> null
        !hasNodes -> "Add a subscription or paste a node before turning the chain on"
        else -> null
    }

    /**
     * Identifies the parts the engine is actually configured from.
     *
     * Deliberately not the whole object: the selected node changes on the live
     * engine without a restart, so including it would report a stale
     * configuration every time someone picked a different node.
     */
    /**
     * Only what decides which nodes exist.
     *
     * Separate from [fingerprint] because the screen reloads its list whenever
     * this changes, and the full fingerprint also covers the routing rules --
     * which do not add or remove a single node, but do change on every
     * keystroke in the rules editor.
     */
    fun nodeSourceFingerprint(): String = buildString {
        append(throughTunnel).append('|')
        sources.filter { it.enabled }.forEach { append(it.url).append(',') }
        append('|').append(manual.trim())
    }

    fun fingerprint(): String = buildString {
        append(nodeSourceFingerprint())
        // The rules are part of the config mihomo is running. Left out, editing
        // one would leave the app believing the live config was current while
        // mihomo went on routing by the previous set.
        append('|').append(routeBlock.trim())
        append('|').append(routeDirect.trim())
    }

    fun encode(): String = JSONObject()
        .put("enabled", enabled)
        .put("throughTunnel", throughTunnel)
        .put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
        .put("manual", manual)
        .put("routeBlock", routeBlock)
        .put("routeDirect", routeDirect)
        .put("hiddenNodes", JSONArray().apply { hiddenNodes.forEach { put(it) } })
        .apply { node?.let { put("node", it) } }
        .toString()

    companion object {
        fun decode(raw: String?): ChainSettings {
            if (raw.isNullOrBlank()) return ChainSettings()
            return runCatching {
                val json = JSONObject(raw)
                val sources = json.optJSONArray("sources")
                ChainSettings(
                    enabled = json.optBoolean("enabled", false),
                    throughTunnel = json.optBoolean("throughTunnel", true),
                    sources = buildList {
                        for (index in 0 until (sources?.length() ?: 0)) {
                            sources?.optJSONObject(index)?.let(ChainSource::fromJson)?.let(::add)
                        }
                    },
                    manual = json.optString("manual"),
                    routeBlock = json.optString("routeBlock"),
                    routeDirect = json.optString("routeDirect"),
                    hiddenNodes = json.optJSONArray("hiddenNodes")?.let { array ->
                        buildList {
                            for (index in 0 until array.length()) {
                                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    } ?: emptyList(),
                    node = json.optString("node").takeIf { it.isNotBlank() },
                )
            }.getOrDefault(ChainSettings())
        }
    }
}
