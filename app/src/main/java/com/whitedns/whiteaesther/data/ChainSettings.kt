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
) {
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

    fun encode(): String = JSONObject()
        .put("enabled", enabled)
        .put("throughTunnel", throughTunnel)
        .put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
        .put("manual", manual)
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
                    node = json.optString("node").takeIf { it.isNotBlank() },
                )
            }.getOrDefault(ChainSettings())
        }
    }
}
