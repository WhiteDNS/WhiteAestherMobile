package com.whitedns.whiteaesther.data

import androidx.annotation.StringRes
import com.whitedns.whiteaesther.R

import org.json.JSONArray
import org.json.JSONObject

enum class SplitTunnelMode(@StringRes val label: Int) {
    /** Everything on the phone goes through the tunnel. */
    ALL(R.string.split_all_apps),

    /** Only the chosen apps do. Anything installed later stays outside. */
    ONLY(R.string.split_only_these),

    /** Everything except the chosen apps. Anything installed later goes in. */
    EXCEPT(R.string.split_all_except),
}

/**
 * Which apps the tunnel carries.
 *
 * Android takes this as either an allow list or a deny list and refuses both at
 * once, so the mode decides which call is made rather than being a filter we
 * apply ourselves.
 */
data class SplitTunnel(
    val mode: SplitTunnelMode = SplitTunnelMode.ALL,
    val packages: Set<String> = emptySet(),
) {
    /**
     * The packages actually handed to Android, with this app removed.
     *
     * Routing ourselves into our own tunnel is the loop the whole design exists
     * to avoid: the engine's sockets to Cloudflare and mihomo's to its nodes
     * would be captured by the interface they are building. Filtered here rather
     * than validated in the UI, so no path can reach the builder with it.
     */
    fun effectivePackages(self: String): Set<String> = packages - self

    /**
     * True when the rules would change nothing, so the tunnel is built without
     * per-app calls at all.
     *
     * An empty allow list is not "no rules" -- it is a tunnel that carries
     * nothing, which looks exactly like a connection that silently failed.
     */
    fun isEffectivelyEverything(self: String): Boolean = when (mode) {
        SplitTunnelMode.ALL -> true
        SplitTunnelMode.ONLY -> false
        SplitTunnelMode.EXCEPT -> effectivePackages(self).isEmpty()
    }

    /** Why this cannot be used, or null. */
    @StringRes
    fun validationError(self: String): Int? = when {
        mode == SplitTunnelMode.ONLY && effectivePackages(self).isEmpty() ->
            R.string.split_choose_one
        else -> null
    }

    /**
     * The same state the screen shows, for the diagnostics log.
     *
     * English on purpose, and separate from what the user reads: this line ends
     * up in a report that someone else has to read next to another report.
     */
    fun summary(): String = when {
        mode == SplitTunnelMode.ALL -> "Every app on this phone"
        packages.isEmpty() && mode == SplitTunnelMode.ONLY -> "No apps chosen yet"
        packages.isEmpty() -> "Every app on this phone"
        mode == SplitTunnelMode.ONLY -> "${packages.size} app${plural()} only"
        else -> "Every app except ${packages.size}"
    }

    private fun plural() = if (packages.size == 1) "" else "s"

    fun encode(): String = JSONObject()
        .put("mode", mode.name)
        .put("packages", JSONArray().apply { packages.sorted().forEach { put(it) } })
        .toString()

    companion object {
        fun decode(raw: String?): SplitTunnel {
            if (raw.isNullOrBlank()) return SplitTunnel()
            return runCatching {
                val json = JSONObject(raw)
                val list = json.optJSONArray("packages")
                SplitTunnel(
                    mode = SplitTunnelMode.entries
                        .firstOrNull { it.name == json.optString("mode") }
                        ?: SplitTunnelMode.ALL,
                    packages = buildSet {
                        for (index in 0 until (list?.length() ?: 0)) {
                            list?.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
                        }
                    },
                )
            }.getOrDefault(SplitTunnel())
        }
    }
}
