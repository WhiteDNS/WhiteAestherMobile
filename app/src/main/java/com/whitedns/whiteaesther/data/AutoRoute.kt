package com.whitedns.whiteaesther.data

import org.json.JSONObject
import java.security.MessageDigest

/**
 * One way out that Automatic knows how to try.
 *
 * Finer than [Carrier], because for Tor the carrier is not the whole answer: the
 * same network can block Tor outright and let snowflake through, so each bridge
 * mode is a route of its own -- tried, failed and remembered separately.
 */
enum class AutoRoute(val wireName: String, val carrier: Carrier, val torBridge: TorBridge? = null) {
    AETHER("aether", Carrier.AETHER),
    PSIPHON("psiphon", Carrier.PSIPHON),
    TOR_CUSTOM("tor-custom", Carrier.TOR, TorBridge.CUSTOM),
    TOR_SNOWFLAKE("tor-snowflake", Carrier.TOR, TorBridge.SNOWFLAKE),
    TOR_OBFS4("tor-obfs4", Carrier.TOR, TorBridge.OBFS4),
    TOR_DIRECT("tor-direct", Carrier.TOR, TorBridge.NONE),
    ;

    companion object {
        fun fromWire(name: String?): AutoRoute? = entries.firstOrNull { it.wireName == name }
    }
}

/** One stage of an automatic connect. */
sealed interface AutoStep {
    /**
     * The engine on the interface, exactly as a session carried by Aether alone
     * has always run -- given a budget instead of its usual eight retries.
     *
     * Not raced against the carriers. Once the engine's interface is up it
     * carries this package's other processes too, and Psiphon and Tor run in
     * two of them: a carrier started beside it would be dialling out through a
     * tunnel that does not work yet.
     */
    data class Engine(val budgetMs: Long, val deep: Boolean) : AutoStep

    /** Carriers tried side by side; the first one to carry traffic wins. */
    data class Race(val lanes: List<Lane>) : AutoStep
}

/**
 * Routes tried one after another, starting [startAfterMs] into the race -- or
 * sooner, the moment every lane already running has run out.
 *
 * Lanes rather than one list because Psiphon and Tor run in processes of their
 * own and can be tried at once, while two Tor routes cannot: there is one tor.
 */
data class Lane(val routes: List<AutoRoute>, val startAfterMs: Long)

/** What this phone and this build can try at all. */
data class AutoOptions(
    /** Psiphon and Tor carry the whole device or nothing. */
    val wholeDevice: Boolean,
    /** Without mihomo nothing can route the interface into a carrier. */
    val chainAvailable: Boolean,
    /** snowflake and lyrebird are in this build. */
    val transportsAvailable: Boolean,
    /** The user has bridges of their own saved. */
    val hasCustomBridges: Boolean,
    /** The engine's transport has a longer search worth spending time on. */
    val engineCanSearchDeeper: Boolean,
    /** The engine has connected on this phone before, on some network. */
    val engineWorkedBefore: Boolean = false,
)

/**
 * The order Automatic tries things in.
 *
 * Aether first, because where it works it is the fastest route by a distance
 * and the one most sessions already use. Then Psiphon, with Tor joining it
 * shortly after rather than waiting its turn: on the networks where Aether
 * fails, Psiphon can need minutes, and a user watching a spinner is not helped
 * by Tor idling for all of them.
 *
 * Whatever worked on a network goes first next time on that network. That is
 * what makes the second connect quick, and it is only a starting point -- the
 * rest of the plan is still there behind it.
 *
 * No chains. A chain gets out exactly when its first hop does, so it can never
 * connect where that carrier on its own would not -- trying one only adds time.
 */
object AutoPlanner {
    /*
     * The engine's budgets are the one place this plan can do worse than no
     * plan at all. Aether on its own has always waited as long as its search
     * took, and on the filtered networks that need it most that search takes
     * minutes. 1.6.0 gave it sixty seconds, and people who had connected every
     * day stopped connecting: Automatic walked away from Aether just before it
     * would have found a way out.
     */

    /** Aether with nothing known about it: its quick probes, and time for one full search. */
    const val ENGINE_QUICK_MS = 90_000L

    /**
     * Where Aether has connected before -- on this network, or anywhere on this
     * phone when the network is new: near enough the patience it always had.
     */
    const val ENGINE_REMEMBERED_MS = 240_000L

    /** Aether after the carriers have failed, when it has the whole ladder to climb. */
    const val ENGINE_LATE_MS = 240_000L

    /** The last thing left: Aether's full search on both framings. */
    const val ENGINE_DEEP_MS = 300_000L

    /** How long the first lane runs alone before the second joins it. */
    const val SECOND_LANE_AFTER_MS = 45_000L

    fun plan(remembered: AutoRoute?, options: AutoOptions): List<AutoStep> {
        val offered = offeredRoutes(options)
        // A route remembered from a build or a setup that can no longer offer
        // it -- bridges since deleted, say -- is a memory of nothing.
        val known = remembered?.takeIf { it in offered }
        val deep = AutoStep.Engine(ENGINE_DEEP_MS, deep = true).takeIf { options.engineCanSearchDeeper }
        // What this network is remembered for outranks the phone's history: a
        // carrier that worked here says Aether did not.
        val aetherLikely = known == AutoRoute.AETHER || (known == null && options.engineWorkedBefore)
        val engine = AutoStep.Engine(
            if (aetherLikely) ENGINE_REMEMBERED_MS else ENGINE_QUICK_MS,
            deep = false,
        )
        if (!options.wholeDevice || !options.chainAvailable) return listOfNotNull(engine, deep)

        val tor = offered.filter { it.carrier == Carrier.TOR }
        val psiphon = listOf(AutoRoute.PSIPHON)
        // Where a carrier worked before, Aether has most likely already failed
        // here, so it goes after the race -- with one budget large enough for
        // its whole ladder, rather than a quick step and a deep one back to
        // back, which would put two engine sessions next to each other.
        val late = AutoStep.Engine(ENGINE_LATE_MS, deep = false)
        return when (known?.carrier) {
            null, Carrier.AETHER -> listOfNotNull(engine, race(psiphon, tor), deep)
            Carrier.PSIPHON -> listOf(race(psiphon, tor), late)
            Carrier.TOR -> listOf(race(listOfNotNull(known) + tor.filter { it != known }, psiphon), late)
        }
    }

    /**
     * Every route this phone could try, Tor's in the order worth trying them.
     *
     * Bridges the user was given first: handed out one at a time, they are the
     * only kind with a real chance where Tor is properly blocked. Then
     * snowflake, which survives a great deal; then the public obfs4 bridges,
     * which are the first a censor lists; then Tor with no bridge at all.
     */
    fun offeredRoutes(options: AutoOptions): List<AutoRoute> = buildList {
        add(AutoRoute.AETHER)
        if (!options.wholeDevice || !options.chainAvailable) return@buildList
        add(AutoRoute.PSIPHON)
        if (options.transportsAvailable) {
            if (options.hasCustomBridges) add(AutoRoute.TOR_CUSTOM)
            add(AutoRoute.TOR_SNOWFLAKE)
            add(AutoRoute.TOR_OBFS4)
        }
        add(AutoRoute.TOR_DIRECT)
    }

    /**
     * How long one carrier route gets before it counts as failed.
     *
     * Psiphon keeps tunnel-core's own window: it races a dozen protocols and
     * on a hostile network that race is minutes, which is exactly the network
     * this is for. Tor gets less than when chosen by hand, because here it is
     * one of several things being tried and not the only one -- and direct Tor
     * least of all, since where it is blocked it is blocked at once.
     */
    fun budgetMs(route: AutoRoute): Long = when (route) {
        AutoRoute.AETHER -> ENGINE_QUICK_MS
        AutoRoute.PSIPHON -> 330_000L
        AutoRoute.TOR_CUSTOM, AutoRoute.TOR_SNOWFLAKE -> 180_000L
        AutoRoute.TOR_OBFS4 -> 150_000L
        AutoRoute.TOR_DIRECT -> 90_000L
    }

    private fun race(first: List<AutoRoute>, second: List<AutoRoute>) = AutoStep.Race(
        listOf(Lane(first, 0L), Lane(second, SECOND_LANE_AFTER_MS)).filter { it.routes.isNotEmpty() },
    )
}

/**
 * Which route last carried traffic, per network.
 *
 * Stored as JSON in one preference: a handful of entries that are read on every
 * connect and written on every success, which is not worth a database.
 */
object RouteMemory {
    /** For a network that could not be told apart from any other. */
    const val ANY_NETWORK = "*"

    /** Enough for home, work and a few places in between. */
    const val MAX_NETWORKS = 32

    /**
     * Networks change what they block. A route that worked a fortnight ago is a
     * guess, and a guess that goes first costs its whole budget when it is wrong.
     */
    const val FORGET_AFTER_MS = 14L * 24 * 60 * 60 * 1_000

    data class Entry(val route: AutoRoute, val atMs: Long)

    fun recall(stored: String?, network: String, nowMs: Long): AutoRoute? {
        val entry = decode(stored)[network] ?: return null
        return entry.route.takeIf { nowMs - entry.atMs <= FORGET_AFTER_MS }
    }

    /** [stored] with [route] recorded for [network], keeping the most recent networks. */
    fun remember(stored: String?, network: String, route: AutoRoute, nowMs: Long): String {
        val entries = decode(stored) + (network to Entry(route, nowMs))
        val json = JSONObject()
        entries.entries
            .sortedByDescending { it.value.atMs }
            .take(MAX_NETWORKS)
            .forEach { (key, entry) ->
                json.put(key, JSONObject().put("route", entry.route.wireName).put("at", entry.atMs))
            }
        return json.toString()
    }

    fun decode(stored: String?): Map<String, Entry> {
        if (stored.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(stored)
            json.keys().asSequence().mapNotNull { key ->
                val item = json.optJSONObject(key) ?: return@mapNotNull null
                // A route this build no longer knows is dropped rather than
                // guessed at; the next success writes a real one.
                val route = AutoRoute.fromWire(item.optString("route")) ?: return@mapNotNull null
                key to Entry(route, item.optLong("at"))
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}

/**
 * A name for the network the phone is on, stable across connects.
 *
 * Not the Wi-Fi name: reading it needs the location permission, and asking a
 * user for their location so that a VPN can connect faster is not a trade worth
 * offering. What the network hands out instead -- its gateway, its resolvers,
 * its search domain -- tells one network from another well enough, since a
 * wrong guess costs only a starting position.
 *
 * Hashed, so a diagnostics report that quotes the key does not also carry the
 * addresses of somebody's home network.
 */
object NetworkKey {
    /** A mobile network, by operator code: `cell:43211` is one operator, `cell:43235` another. */
    fun cellular(operator: String?): String =
        operator?.filter(Char::isDigit)?.takeIf { it.length in 5..6 }?.let { "cell:$it" } ?: "cell"

    fun local(kind: String, gateway: String?, dns: List<String>, domains: String?): String {
        val material = listOf(gateway.orEmpty(), dns.sorted().joinToString(","), domains.orEmpty())
        if (material.all(String::isEmpty)) return kind
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.joinToString("|").toByteArray())
        return kind + ":" + digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
