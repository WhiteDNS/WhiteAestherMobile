package com.whitedns.whiteaesther.data

import org.json.JSONObject
import java.security.MessageDigest

/**
 * One way out that Automatic knows how to try.
 *
 * Finer than [Carrier], because for Tor the carrier is not the whole answer: the
 * same network can block Tor outright and let snowflake through, so each bridge
 * mode is a route of its own -- tried, failed and remembered separately. The
 * same goes for Aether's two framings: the network one user sent a log from
 * reached Cloudflare over QUIC and not over TCP, and another, on mobile data,
 * the other way round.
 */
enum class AutoRoute(
    val wireName: String,
    val carrier: Carrier,
    val torBridge: TorBridge? = null,
    /** For the engine racing as a carrier: which framing, or null for the user's own. */
    val engineTransport: String? = null,
    /** For the engine racing as a carrier: the user's search depth rather than the quick one. */
    val fullSearch: Boolean = false,
    /**
     * Split the TLS ClientHello, or null to leave the user's setting alone.
     *
     * A tactic, not a framing. Inspection that blocks on the SNI generally reads
     * only the first segment, so splitting it is what gets a handshake past a
     * network filtering by hostname -- and it applies to the TCP framing, which
     * is the one that has a ClientHello to split.
     */
    val fragmentTls: Boolean? = null,
    /**
     * Ask for an Encrypted Client Hello, or null to leave the user's setting.
     *
     * The other half of the same problem: fragmentation hides the SNI from an
     * inspector that reassembles nothing, ECH hides it from one that does.
     */
    val encryptedHello: Boolean? = null,
) {
    /**
     * The engine on the interface, as a session carried by Aether alone has
     * always run. Never raced -- see [AutoStep.Engine] -- and what every Aether
     * route is remembered as, so the next connect on that network takes the
     * direct path.
     */
    AETHER("aether", Carrier.AETHER),

    // The engine as one runner in a race: a listener on loopback behind the
    // race's interface, like the carriers beside it. One framing and one depth
    // each, so a network that answers only one of them is found in the time
    // it takes to search that one.
    AETHER_H3_QUICK("aether-h3-quick", Carrier.AETHER, engineTransport = "h3"),
    AETHER_H2_QUICK("aether-h2-quick", Carrier.AETHER, engineTransport = "h2"),

    // The same two framings, carrying the tactic that framing has.
    //
    // These replace a second pass at greater search depth, and the trade is
    // deliberate. Depth buys a larger share of a pool of addresses; since the
    // engine started trying the endpoint Cloudflare assigns before searching at
    // all, that pool is rarely where the answer is. What was untried was the
    // handshake itself -- and the settings that change it sat in Advanced,
    // waiting for a user to guess, which is exactly the decision Automatic
    // exists to take away from them.
    AETHER_H2_FRAGMENT(
        "aether-h2-fragment",
        Carrier.AETHER,
        engineTransport = "h2",
        fragmentTls = true,
    ),
    AETHER_H3_ECH(
        "aether-h3-ech",
        Carrier.AETHER,
        engineTransport = "h3",
        encryptedHello = true,
    ),

    /** One deep search, kept for the network whose answer really is in the pool. */
    AETHER_H2_FULL("aether-h2-full", Carrier.AETHER, engineTransport = "h2", fullSearch = true),

    /**
     * Two nested MASQUE hops, for a network that has learnt to recognise one.
     *
     * A quick search for the outer edge rather than a deep one: the outer hop
     * dials the same addresses every other MASQUE lane does, and what this
     * route costs is the inner handshakes through it, not the search.
     */
    AETHER_MIM("aether-mim", Carrier.AETHER, engineTransport = "mim"),

    /** The engine racing on a transport the user fixed -- WireGuard, say -- searched as they set it. */
    AETHER_AS_SET("aether-as-set", Carrier.AETHER, fullSearch = true),

    PSIPHON("psiphon", Carrier.PSIPHON),
    TOR_CUSTOM("tor-custom", Carrier.TOR, TorBridge.CUSTOM),
    TOR_SNOWFLAKE("tor-snowflake", Carrier.TOR, TorBridge.SNOWFLAKE),
    TOR_OBFS4("tor-obfs4", Carrier.TOR, TorBridge.OBFS4),
    TOR_DIRECT("tor-direct", Carrier.TOR, TorBridge.NONE),
    ;

    /** True for the engine running as a carrier in a race rather than on the interface. */
    val racesEngine: Boolean get() = carrier == Carrier.AETHER && this != AETHER

    /** What a win is remembered as: any Aether route is Aether, next time on the direct path. */
    val remembersAs: AutoRoute get() = if (carrier == Carrier.AETHER) AETHER else this

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
     * tunnel that does not work yet. Inside a race the engine runs as a carrier
     * instead, behind the race's interface like the others.
     */
    data class Engine(val budgetMs: Long, val deep: Boolean) : AutoStep

    /** Routes tried side by side; the first one to carry traffic wins. */
    data class Race(val lanes: List<Lane>) : AutoStep
}

/**
 * Routes tried one after another, starting [startAfterMs] into the race -- or
 * sooner, the moment every lane already running has run out.
 *
 * Lanes rather than one list because different carriers can be tried at once,
 * while two routes of one carrier cannot: there is one engine and one tor.
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
    /**
     * The engine's transport is one of the two MASQUE framings or Automatic,
     * so both framings can be tried. False for WireGuard and WARP-in-WARP.
     */
    val engineCanSearchDeeper: Boolean,
    /** The engine has connected on this phone before, on some network. */
    val engineWorkedBefore: Boolean = false,
    /** The framing that last connected on this phone, when it was one of the two. */
    val provenFraming: String? = null,
    /** On mobile data rather than Wi-Fi or a cable. */
    val onMobileData: Boolean = false,
    /**
     * What the engine said the last time it failed on this network.
     *
     * Read for one purpose: a failure that names its own remedy should move the
     * rung carrying that remedy to the front. Everything else about it is left
     * alone -- guessing from a message is how a planner ends up with rules
     * nobody can predict.
     */
    val lastEngineFailure: String? = null,
    /**
     * The engine went first on this network recently and did not connect.
     *
     * The only thing this suppresses is going first. The engine still races in
     * the lane beside the carriers, so nothing is given up -- what is given up
     * is spending two and a half minutes on it before anything else starts.
     */
    val engineFailedHere: Boolean = false,
)

/**
 * The order Automatic tries things in.
 *
 * Everything at once, from the tap. 1.6.0 went one step at a time -- Aether,
 * then the carriers, then Aether again -- and a log from Iran showed where that
 * leads: Aether spent its minute on the one framing that network did not
 * carry, Psiphon started a minute late with no tactics yet, and the user
 * stopped it at two. So Aether races in both framings beside Psiphon, Tor joins
 * shortly after, and the first route that carries traffic wins.
 *
 * Except where Aether has already worked: there the engine goes first, on the
 * interface directly, which is the fastest and most direct session this app
 * has. It comes back to the race if it does not connect.
 *
 * Whatever worked on a network goes first next time on that network. That is
 * what makes the second connect quick, and it is only a starting point -- the
 * rest of the plan is still behind it.
 *
 * No chains. A chain gets out exactly when its first hop does, so it can never
 * connect where that carrier on its own would not -- trying one only adds time.
 */
object AutoPlanner {
    /** The engine on its own where there is nothing to race it against: one quick search. */
    const val ENGINE_QUICK_MS = 60_000L

    /**
     * The engine first, where it has worked before: the framing that connected
     * last, at the user's search depth. A balanced search is two minutes on its
     * own (`budget=120s`), so this is that and the connect after it.
     */
    const val ENGINE_REMEMBERED_MS = 150_000L

    /** The engine on its own as the last thing left: its full searches, one per framing. */
    const val ENGINE_DEEP_MS = 300_000L

    /** How long Tor waits for the lanes ahead of it before it joins them. */
    const val SECOND_LANE_AFTER_MS = 45_000L

    /**
     * The longest one pass of [plan] can take.
     *
     * Lanes run beside each other, so a pass lasts as long as its slowest lane
     * and not as long as all of them added up. Computed rather than written
     * down, because a number written down drifts the moment a rung is added and
     * the thing it guards is how long a person is asked to wait.
     */
    /**
     * Whether another pass can *finish* inside what this search is allowed.
     *
     * Not whether the ceiling has already been passed, which is the question
     * that looks equivalent and is not: the longest pass this plan can make is
     * under the ceiling by itself, so that test never fires and a second full
     * pass runs to its own end. The search then takes twice what it was
     * allowed, which is the thing the ceiling exists to prevent.
     *
     * A pass that failed quickly does leave room, and gets one -- every route
     * refusing in seconds is a different situation from every route using its
     * whole window, and only one of them is worth trying again.
     */
    fun hasRoomForAnotherPass(spentMs: Long, passMs: Long, ceilingMs: Long): Boolean =
        spentMs + passMs <= ceilingMs

    fun longestPassMs(remembered: AutoRoute?, options: AutoOptions): Long =
        plan(remembered, options).sumOf { step ->
            when (step) {
                is AutoStep.Engine -> step.budgetMs
                is AutoStep.Race -> step.lanes.maxOfOrNull { lane ->
                    lane.startAfterMs + lane.routes.sumOf { budgetMs(it) }
                } ?: 0L
            }
        }

    fun plan(remembered: AutoRoute?, options: AutoOptions): List<AutoStep> {
        val offered = offeredRoutes(options)
        // A route remembered from a build or a setup that can no longer offer
        // it -- bridges since deleted, say -- is a memory of nothing.
        val known = remembered?.remembersAs?.takeIf { it in offered }
        // Going first is a bet that costs ENGINE_REMEMBERED_MS when it loses,
        // and it used to be placed on evidence that never expired: the engine
        // connecting once, anywhere, set a flag for the life of the install. A
        // phone that had connected at home then opened every session on a
        // filtered mobile network by waiting two and a half minutes for a
        // tunnel that network does not carry -- twice, once per pass -- before
        // trying anything that would have worked.
        val aetherLikely = !options.engineFailedHere &&
            (known == AutoRoute.AETHER || (known == null && options.engineWorkedBefore))

        if (!options.wholeDevice || !options.chainAvailable) {
            return listOfNotNull(
                AutoStep.Engine(if (aetherLikely) ENGINE_REMEMBERED_MS else ENGINE_QUICK_MS, deep = false),
                AutoStep.Engine(ENGINE_DEEP_MS, deep = true).takeIf { options.engineCanSearchDeeper },
            )
        }

        val aether = aetherLane(options)
        val tor = offered.filter { it.carrier == Carrier.TOR }
        val psiphon = listOf(AutoRoute.PSIPHON)
        val race = if (known?.carrier == Carrier.TOR) {
            // Tor worked here, so it goes at once; Psiphon, which evidently did
            // not, after it.
            AutoStep.Race(
                listOf(
                    Lane(listOfNotNull(known) + tor.filter { it != known }, 0L),
                    Lane(aether, 0L),
                    Lane(psiphon, SECOND_LANE_AFTER_MS),
                ),
            )
        } else {
            AutoStep.Race(
                listOf(Lane(aether, 0L), Lane(psiphon, 0L), Lane(tor, SECOND_LANE_AFTER_MS))
                    .filter { it.routes.isNotEmpty() },
            )
        }
        return if (aetherLikely) {
            listOf(AutoStep.Engine(ENGINE_REMEMBERED_MS, deep = false), race)
        } else {
            listOf(race)
        }
    }

    /**
     * The engine's runs in a race, in the order worth trying them.
     *
     * Quick searches first, one per framing, then the full ones: a network
     * that carries either framing at all usually shows it within a quick
     * search. The framing that connected last goes first; with nothing to go
     * on, H2 first on mobile data -- where operators have dropped QUIC for
     * weeks at a time -- and H3 first elsewhere, which is what the Wi-Fi in
     * that log needed.
     */
    /**
     * The order to try the MASQUE framings in, and the only place that decides.
     *
     * There were three answers to this and they disagreed: the race preferred
     * H3 on Wi-Fi and H2 on mobile data, the retry ladder always went H2 first,
     * and the endpoint scanner did too. Three rules that can drift is worse than
     * any one of them being wrong, because nothing ever notices -- a user gets a
     * different order depending on which part of the app is asking, and the one
     * that connects for them may be the one their path never reaches.
     *
     * The rule: whatever connected here last goes first, because it is evidence
     * and the rest is inference. With nothing to go on, H2 leads on mobile data
     * -- operators have dropped QUIC for weeks at a time -- and H3 leads
     * elsewhere, which is what the Wi-Fi in the log that prompted this needed.
     *
     * `provenFraming` is per network. A framing proven on another network says
     * nothing about this one, and treating it as evidence is how a phone that
     * connected at home opens every session on mobile data with the wrong guess.
     */
    fun framingOrder(provenFraming: String?, onMobileData: Boolean): List<String> {
        val h3First = when (provenFraming) {
            "h3" -> true
            "h2" -> false
            else -> !onMobileData
        }
        return if (h3First) listOf("h3", "h2") else listOf("h2", "h3")
    }

    fun aetherLane(options: AutoOptions): List<AutoRoute> {
        if (!options.engineCanSearchDeeper) return listOf(AutoRoute.AETHER_AS_SET)
        val h3First =
            framingOrder(options.provenFraming, options.onMobileData).first() == "h3"
        // Nested MASQUE last, always. It is the slowest thing the engine can
        // do -- an outer tunnel plus up to six inner handshakes through it --
        // and on most networks one of the framings above gets out first. But it
        // is in the lane rather than only in the picker, because the network it
        // is for is one where every single-hop lane above has already failed,
        // and nobody reaches into Advanced to find it.
        // Plain first, in the order this network suggests; then the same two
        // framings carrying the tactic that framing has; then one deep search;
        // then the nested tunnel. Each rung changes one thing, so whatever
        // answers says which thing mattered.
        val plain = if (h3First) {
            listOf(AutoRoute.AETHER_H3_QUICK, AutoRoute.AETHER_H2_QUICK)
        } else {
            listOf(AutoRoute.AETHER_H2_QUICK, AutoRoute.AETHER_H3_QUICK)
        }
        val tactics = if (h3First) {
            listOf(AutoRoute.AETHER_H3_ECH, AutoRoute.AETHER_H2_FRAGMENT)
        } else {
            listOf(AutoRoute.AETHER_H2_FRAGMENT, AutoRoute.AETHER_H3_ECH)
        }
        val lane = plain + tactics + listOf(AutoRoute.AETHER_H2_FULL, AutoRoute.AETHER_MIM)
        return preferredFor(lane, options.lastEngineFailure)
    }

    /**
     * The lane, reordered by what the last failure actually said.
     *
     * A gateway that demands an Encrypted Client Hello says so in the close it
     * sends -- TLS alert 121, which the engine now names rather than reporting
     * as a stop with no reason. Moving the rung that carries ECH to the front
     * is the whole point of having the reason: without it Automatic alternates
     * framings, and the one thing that would have worked sits fourth in a
     * queue with a seventy-five second budget in front of it.
     */
    fun preferredFor(lane: List<AutoRoute>, failure: String?): List<AutoRoute> {
        val wanted = when {
            failure == null -> return lane
            failure.contains("ECH", ignoreCase = true) -> AutoRoute.AETHER_H3_ECH
            failure.contains("unrecognised name", ignoreCase = true) ||
                failure.contains("unrecognized name", ignoreCase = true) ->
                AutoRoute.AETHER_H2_FRAGMENT
            else -> return lane
        }
        if (!lane.contains(wanted) || lane.firstOrNull() == wanted) return lane
        return listOf(wanted) + lane.filterNot { it == wanted }
    }

    /**
     * Every carrier route this phone could try, Tor's in the order worth trying them.
     *
     * Bridges the user was given first: handed out one at a time, they are the
     * only kind with a real chance where Tor is properly blocked. Then
     * snowflake, which survives a great deal; then the public obfs4 bridges,
     * which are the first a censor lists; then Tor with no bridge at all.
     */
    fun offeredRoutes(options: AutoOptions): List<AutoRoute> = buildList {
        add(AutoRoute.AETHER)
        if (!options.wholeDevice || !options.chainAvailable) return@buildList
        addAll(aetherLane(options))
        add(AutoRoute.PSIPHON)
        if (options.transportsAvailable) {
            if (options.hasCustomBridges) add(AutoRoute.TOR_CUSTOM)
            add(AutoRoute.TOR_SNOWFLAKE)
            add(AutoRoute.TOR_OBFS4)
        }
        add(AutoRoute.TOR_DIRECT)
    }

    /**
     * How long one route gets before it counts as failed.
     *
     * The engine's from its own search deadlines -- 45 s quick, 120 s balanced
     * -- plus registration and the connect after the search. Psiphon keeps
     * tunnel-core's own window: it races a dozen protocols and, on a first run
     * with no tactics stored, needs them to open its in-proxy path at all.
     * Tor gets less than when chosen by hand, because here it is one of
     * several things being tried; direct Tor least of all, since where it is
     * blocked it is blocked at once.
     */
    fun budgetMs(route: AutoRoute): Long = when (route) {
        AutoRoute.AETHER -> ENGINE_QUICK_MS
        AutoRoute.AETHER_H3_QUICK, AutoRoute.AETHER_H2_QUICK -> 75_000L
        // A tactic rung is a different handshake, not a deeper search, so it
        // costs what a quick rung costs.
        AutoRoute.AETHER_H2_FRAGMENT, AutoRoute.AETHER_H3_ECH -> 75_000L
        AutoRoute.AETHER_H2_FULL -> 180_000L
        // An outer tunnel, then up to six inner handshakes at twelve seconds
        // each. The search for the outer edge is the quick one, so this is
        // mostly the inner tries.
        AutoRoute.AETHER_MIM -> 180_000L
        AutoRoute.AETHER_AS_SET -> 300_000L
        AutoRoute.PSIPHON -> 330_000L
        AutoRoute.TOR_CUSTOM, AutoRoute.TOR_SNOWFLAKE -> 180_000L
        AutoRoute.TOR_OBFS4 -> 150_000L
        AutoRoute.TOR_DIRECT -> 90_000L
    }
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

    /**
     * How long the engine is left out of the lead after it failed here.
     *
     * Shorter than [FORGET_AFTER_MS] by a long way, and deliberately so: this
     * is a negative, and a negative held for a fortnight would keep the fastest
     * path out of the lead on a network that came good the same afternoon. Six
     * hours is long enough to cover the session someone is actually having.
     */
    const val ENGINE_RETRY_AFTER_MS = 6L * 60 * 60 * 1_000

    /**
     * What is known about one network.
     *
     * [route] is null for a network where nothing has worked yet but the engine
     * has already been tried and failed -- which is a thing worth remembering
     * on its own, and the reason this is not simply a route.
     */
    data class Entry(val route: AutoRoute?, val atMs: Long, val engineFailedAtMs: Long = 0L)

    fun recall(stored: String?, network: String, nowMs: Long): AutoRoute? {
        val entry = decode(stored)[network] ?: return null
        return entry.route?.takeIf { nowMs - entry.atMs <= FORGET_AFTER_MS }
    }

    /** Whether the engine went first here recently and did not connect. */
    fun engineFailedRecently(stored: String?, network: String, nowMs: Long): Boolean {
        val entry = decode(stored)[network] ?: return false
        if (entry.engineFailedAtMs <= 0L) return false
        return nowMs - entry.engineFailedAtMs <= ENGINE_RETRY_AFTER_MS
    }

    /** [stored] with [route] recorded for [network], keeping the most recent networks. */
    fun remember(stored: String?, network: String, route: AutoRoute, nowMs: Long): String {
        val entries = decode(stored)
        val before = entries[network]
        // The engine winning is the answer to the engine having failed, so the
        // mark goes when it does. Another carrier winning says nothing about
        // the engine and leaves it alone.
        val engineFailedAt = if (route.remembersAs == AutoRoute.AETHER) {
            0L
        } else {
            before?.engineFailedAtMs ?: 0L
        }
        return encode(entries + (network to Entry(route.remembersAs, nowMs, engineFailedAt)))
    }

    /** [stored] with the engine noted as having failed on [network] just now. */
    fun rememberEngineFailure(stored: String?, network: String, nowMs: Long): String {
        val entries = decode(stored)
        val before = entries[network]
        return encode(
            entries + (network to Entry(before?.route, before?.atMs ?: 0L, nowMs)),
        )
    }

    private fun encode(entries: Map<String, Entry>): String {
        val json = JSONObject()
        entries.entries
            // By whichever of the two is more recent, so a network known only
            // for a failure is not the first one evicted.
            .sortedByDescending { maxOf(it.value.atMs, it.value.engineFailedAtMs) }
            .take(MAX_NETWORKS)
            .forEach { (key, entry) ->
                val item = JSONObject().put("at", entry.atMs)
                entry.route?.let { item.put("route", it.wireName) }
                if (entry.engineFailedAtMs > 0L) item.put("engineFailedAt", entry.engineFailedAtMs)
                json.put(key, item)
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
                val route = AutoRoute.fromWire(item.optString("route"))
                val engineFailedAt = item.optLong("engineFailedAt")
                // Neither half means anything: not an entry.
                if (route == null && engineFailedAt <= 0L) return@mapNotNull null
                key to Entry(route, item.optLong("at"), engineFailedAt)
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

    /** True for a key [cellular] produced. */
    fun isCellular(key: String): Boolean = key == "cell" || key.startsWith("cell:")
}
