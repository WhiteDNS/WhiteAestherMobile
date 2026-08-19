package com.whitedns.whiteaesther.core

import android.content.Context
import android.os.Build
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.service.EngineLog
import com.whitedns.whiteaesther.service.LogLevel
import org.json.JSONObject
import java.io.File

/**
 * Drives mihomo: writes its configuration, applies it, and attaches the tunnel
 * interface.
 *
 * There is one mihomo per process, not one per instance of this class. The
 * service and the UI both hold one and both talk to the same engine -- reading
 * the node list from the screen while the service carries traffic is the point,
 * not an accident.
 *
 * Order matters here and is the whole of Stage 2. mihomo must have its rules
 * before it is given the interface, because a mihomo with no configuration
 * routes everything DIRECT -- and a DIRECT route from this process is excluded
 * from the interface, so it would leave in the clear. Attaching last means
 * packets have nowhere to go until the rules exist, which is the safe failure.
 */
class ChainController(private val context: Context) {
    private val home: File get() = File(context.filesDir, "chain")

    val isAvailable: Boolean get() = NativeChainBridge.isAvailable

    /**
     * Applies the configuration and attaches [tunFd].
     *
     * @param socksPort Aether's SOCKS5 port, or null to dial nodes directly.
     *   When set, the listener must already be up: the provider fetch that
     *   happens inside this call travels through it.
     * @return null on success, or why it failed.
     */
    fun start(settings: ChainSettings, socksPort: Int?, tunFd: Int): String? {
        if (!isAvailable) return "The exit chain is not available in this build"
        settings.startupError()?.let { return it }

        return runCatching {
            prepareHome(settings, socksPort)

            initialise()?.let { return it }
            // Fetches every provider, so it reaches the network and can take a
            // while on a slow tunnel. It answers with an empty string when the
            // config was accepted and the reason when it was not.
            applyConfig()?.let { return it }
            selectNode(settings.node)
            startLogging()

            NativeChainBridge.startTun(
                fd = tunFd,
                stack = ChainConfig.TUN_STACK,
                address = "${ChainConfig.TUN_IPV4},${ChainConfig.TUN_IPV6}",
                dns = ChainConfig.TUN_DNS,
            ).failureText()
        }.getOrElse { error ->
            error.message ?: "The exit chain did not start"
        }
    }

    fun stop() {
        if (!isAvailable) return
        // Drained first: the lines explaining why a session ended are written
        // during teardown, and shutdown discards anything still buffered.
        collectEvents()
        NativeChainBridge.invoke("stopLog")
        NativeChainBridge.stopTun()
        // Closes listeners and drops the parsed config. Without it a later start
        // inherits the previous run's providers and selected node.
        NativeChainBridge.invoke("shutdown")
    }

    /**
     * The nodes mihomo knows about, and which one is carrying traffic.
     *
     * Only answers while the chain is running: the list comes from the live
     * engine, not from the subscription file, because until a provider has been
     * fetched and parsed there is nothing to list. That is why the screen asks
     * the user to connect first rather than showing an empty list.
     */
    fun nodes(): ChainNodes {
        val reply = NativeChainBridge.invoke("getProxies")
        val data = reply.data as? JSONObject ?: return ChainNodes()
        val proxies = data.optJSONObject("proxies") ?: return ChainNodes()
        val group = proxies.optJSONObject(ChainConfig.EXIT_GROUP) ?: return ChainNodes()
        val members = group.optJSONArray("all") ?: return ChainNodes()

        val nodes = buildList {
            for (index in 0 until members.length()) {
                val name = members.optString(index).takeIf { it.isNotBlank() } ?: continue
                val entry = proxies.optJSONObject(name)
                add(
                    ChainNode(
                        name = name,
                        kind = entry?.optString("type").orEmpty().ifBlank { "Unknown" },
                        delay = entry?.optJSONArray("history")
                            ?.let { history ->
                                history.optJSONObject(history.length() - 1)?.optInt("delay", 0)
                            }
                            ?.takeIf { it > 0 },
                    ),
                )
            }
        }
        return ChainNodes(nodes, group.optString("now").takeIf { it.isNotBlank() })
    }

    /**
     * Switches the live chain to [node].
     *
     * Takes effect on the next connection rather than needing a reconnect, which
     * is why this is worth having at all: picking a node is otherwise a
     * disconnect, a settings change and a reconnect.
     *
     * @return null on success, or why it failed.
     */
    fun select(node: String): String? {
        if (!isAvailable) return "The exit chain is not available in this build"
        return NativeChainBridge.invoke(
            "changeProxy",
            JSONObject().put("group-name", ChainConfig.EXIT_GROUP).put("proxy-name", node),
        ).failureText()
    }

    /**
     * Measures each node, through whatever the chain is dialling through.
     *
     * The engine answers each test on its own goroutine and reports the result
     * as a delay event, so the numbers arrive through the log stream rather than
     * from here -- this only asks.
     */
    fun testNodes(nodes: List<String>) {
        if (!isAvailable) return
        nodes.forEach { node ->
            NativeChainBridge.invoke(
                "asyncTestDelay",
                JSONObject()
                    .put("proxy-name", node)
                    .put("test-url", "http://www.gstatic.com/generate_204")
                    .put("timeout", DELAY_TEST_TIMEOUT_MS),
            )
        }
    }

    /**
     * Moves mihomo's log into the app's, where the diagnostics report can reach
     * it. Best effort: losing the log is not a reason to refuse to connect.
     */
    private fun startLogging() {
        if (!NativeChainBridge.listenForEvents()) return
        NativeChainBridge.invoke("startLog")
    }

    /**
     * Copies buffered events into [EngineLog].
     *
     * Only the log ones. The stream also carries a traffic sample and a record
     * of every connection, which on a phone is thousands of lines an hour and
     * would name every host the user visited in a report they might send us.
     */
    fun collectEvents() {
        if (!isAvailable) return
        NativeChainBridge.drainEvents().forEach { batch ->
            runCatching {
                val messages = JSONObject(batch).optJSONArray("arguments") ?: return@runCatching
                for (index in 0 until messages.length()) {
                    val message = messages.optJSONObject(index) ?: continue
                    if (message.optString("type") != "log") continue
                    val data = message.optJSONObject("data") ?: continue
                    val payload = data.optString("payload").ifBlank { data.toString() }
                    EngineLog.record(levelOf(data.optString("logLevel")), "chain", payload)
                }
            }
        }
    }

    private fun levelOf(level: String): LogLevel = when (level.lowercase()) {
        "error" -> LogLevel.ERROR
        "warning", "warn" -> LogLevel.WARN
        "debug" -> LogLevel.DEBUG
        else -> LogLevel.INFO
    }

    private fun prepareHome(settings: ChainSettings, socksPort: Int?) {
        home.mkdirs()
        File(home, "providers").mkdirs()
        File(home, "config.yaml").writeText(ChainConfig.render(settings, socksPort))
        // Only when there is something to write. The renderer declares the file
        // provider on the same condition, so a missing file and a missing
        // provider always agree -- a provider pointing at a path that is not
        // there is a config mihomo rejects outright.
        if (settings.manual.isNotBlank()) {
            File(home, "providers/manual.txt").writeText(settings.manual.trim() + "\n")
        }
    }

    private fun initialise(): String? {
        val reply = NativeChainBridge.invoke(
            "initClash",
            JSONObject()
                .put("home-dir", home.absolutePath)
                // mihomo uses this to decide whether it can read socket owners
                // from the kernel or has to walk /proc. We ask it not to look at
                // all, but it is read before that setting is applied.
                .put("version", Build.VERSION.SDK_INT),
        )
        return reply.failureText()
    }

    private fun applyConfig(): String? {
        val reply = NativeChainBridge.invoke(
            "setupConfig",
            JSONObject()
                .put("selected-map", JSONObject())
                .put("test-url", "http://www.gstatic.com/generate_204"),
        )
        return reply.failureText()?.let { "The chain rejected its configuration: $it" }
    }

    /**
     * Restores the node the user last chose.
     *
     * Failure is not fatal and is deliberately not propagated: a subscription
     * that dropped or renamed that node should leave the chain running on
     * whichever node the group defaults to, not refuse to start.
     */
    private fun selectNode(node: String?) {
        if (node.isNullOrBlank()) return
        select(node)?.let {
            EngineLog.record(LogLevel.WARN, "chain", "could not select $node: $it")
        }
    }

    private companion object {
        const val DELAY_TEST_TIMEOUT_MS = 5_000L
    }
}

/** What the running chain reports about its nodes. */
data class ChainNodes(
    val nodes: List<ChainNode> = emptyList(),
    /** The one carrying traffic, which is not always the one the user picked. */
    val selected: String? = null,
)

data class ChainNode(
    val name: String,
    val kind: String,
    /** Milliseconds through the tunnel, or null when the last test failed. */
    val delay: Int?,
)
