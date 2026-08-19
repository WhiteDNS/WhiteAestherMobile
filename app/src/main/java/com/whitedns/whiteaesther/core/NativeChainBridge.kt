package com.whitedns.whiteaesther.core

import org.json.JSONObject

/**
 * The exit chain's half of the native layer.
 *
 * The chain runs mihomo, which is Go, and it is loaded at run time rather than
 * linked. That makes it optional: a build that does not ship the library still
 * starts, and [isAvailable] answers false rather than the app failing to load.
 *
 * The entry points live in the same Rust library as the engine. That is not a
 * tidiness choice -- Go supports one runtime per process, so the Go side has to
 * be exactly one shared library, and Rust is what reaches it.
 */
object NativeChainBridge {
    /**
     * True when the chain library is present and its symbols resolved.
     *
     * Checked before offering the feature at all, so the UI never shows a switch
     * that is guaranteed to fail.
     */
    val isAvailable: Boolean by lazy {
        NativeAetherBridge.isLoaded && runCatching { nativeAvailable() }.getOrDefault(false)
    }

    /**
     * Sends one action to mihomo and returns the parsed reply.
     *
     * This goes through the action protocol rather than mihomo's HTTP control
     * API. That is deliberate: the control API is Go's `net/http`, which frames
     * any reply too large to buffer as chunked, and the desktop client shipped a
     * version that read `/version` at 35 bytes correctly and then failed on the
     * node list -- the one reply that is always large -- with
     * `expected value at line 1 column 1`.
     */
    fun invoke(method: String, arguments: Any? = null): ChainReply {
        if (!isAvailable) return ChainReply(false, error = "The exit chain is not available in this build")
        val params = JSONObject()
            .put("id", "wa")
            .put("method", method)
            .apply { if (arguments != null) put("arguments", arguments) }
            .toString()
        return runCatching { ChainReply.parse(nativeInvoke(params)) }
            .getOrElse { ChainReply(false, error = it.message ?: "The chain did not answer") }
    }

    /**
     * Hands mihomo the tun. Ownership of the descriptor passes to the chain, so
     * the caller must not close it.
     */
    fun startTun(fd: Int, stack: String, address: String, dns: String): ChainReply {
        if (!isAvailable) return ChainReply(false, error = "The exit chain is not available in this build")
        return runCatching { ChainReply.parse(nativeStartTun(fd, stack, address, dns)) }
            .getOrElse { ChainReply(false, error = it.message ?: "The chain refused the tun") }
    }

    fun stopTun() {
        if (isAvailable) runCatching { nativeStopTun() }
    }

    /**
     * Gives the chain a way to reach `VpnService.protect()`.
     *
     * Must be set before the chain starts. mihomo opens its own sockets -- to
     * the node, for DNS, for subscriptions and health checks -- and the tun
     * carries a default route, so any of those left unprotected is captured by
     * the tunnel mihomo is building.
     */
    fun setSocketProtector(protector: NativeSocketProtector?) {
        if (NativeAetherBridge.isLoaded) runCatching { nativeSetSocketProtector(protector) }
    }

    private external fun nativeAvailable(): Boolean
    private external fun nativeInvoke(params: String): String
    private external fun nativeStartTun(fd: Int, stack: String, address: String, dns: String): String
    private external fun nativeStopTun()
    private external fun nativeSetSocketProtector(protector: NativeSocketProtector?)
}

/**
 * A reply from the chain. Failures reaching mihomo and failures reported by it
 * arrive in the same shape, so there is one thing to handle either way.
 */
data class ChainReply(
    val ok: Boolean,
    val data: Any? = null,
    val error: String? = null,
) {
    companion object {
        fun parse(raw: String): ChainReply {
            if (raw.isBlank()) return ChainReply(false, error = "The chain returned nothing")
            val json = JSONObject(raw)
            // Two shapes arrive here. The core answers {result, error?}, where
            // error is an object; our own bridge answers {ok:false, error} as a
            // string when it could not reach the core at all.
            val error = json.opt("error")
            if (error != null && error != JSONObject.NULL) {
                val message = (error as? JSONObject)?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: error.toString()
                return ChainReply(false, error = message)
            }
            if (json.has("ok") && !json.optBoolean("ok", false)) {
                return ChainReply(false, error = "The chain reported a failure")
            }
            return ChainReply(true, data = json.opt("result"))
        }
    }

    /**
     * Several handlers report trouble as a plain string in `result`, empty when
     * all is well, rather than through `error`. Reading only `error` there would
     * take a config mihomo rejected for a config it accepted.
     */
    fun failureText(): String? = when {
        !ok -> error ?: "The chain reported a failure"
        else -> (data as? String)?.takeIf { it.isNotBlank() }
    }
}
