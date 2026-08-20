package com.whitedns.whiteaesther.core

import org.json.JSONObject

fun interface NativeSocketProtector {
    fun protectSocket(fd: Int): Boolean
}

fun interface NativeEngineListener {
    fun onNativeReady()
}

data class PreparedEngine(
    val ipv4: String,
    val ipv6: String,
    val peer: String,
)

data class NativeResult(
    val ok: Boolean,
    val error: String? = null,
)

data class EndpointScanResult(
    val peer: String,
    val rttMillis: Long,
)

object NativeAetherBridge {
    val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("whiteaesther_core") }
    }

    val isLoaded: Boolean
        get() = loadResult.isSuccess

    fun versionOrNull(): String? = loadResult.mapCatching { nativeVersion() }.getOrNull()

    /**
     * Packages this install's identity so it survives a reinstall.
     *
     * Uninstalling takes the identity with it, and Cloudflare rate-limits device
     * registrations per address -- so a few reinstalls can leave the address
     * refused outright, which looks exactly like a broken app.
     */
    fun exportIdentity(configPath: String): Result<String> =
        call { nativeExportIdentity(configPath) }.mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Nothing to export") }
            json.getString("payload")
        }

    /**
     * Restores an identity produced by [exportIdentity].
     *
     * Everything is checked before anything is written: a half-finished import
     * would leave the device holding an identity Cloudflare does not recognise,
     * with the working one already gone.
     */
    fun importIdentity(configPath: String, payload: String): NativeResult =
        call { nativeImportIdentity(configPath, payload) }.toResult()

    /**
     * Takes the engine's own log since the last call.
     *
     * The engine explains itself as it works -- which endpoint it is testing,
     * which obfuscation profile answered, why a handshake failed. Without this
     * none of it reaches the diagnostics report, so a user whose phone will not
     * connect has nothing to send that says why, and the developer is left
     * guessing on hardware that works.
     */
    fun drainLog(): List<String> {
        if (!isLoaded) return emptyList()
        return runCatching { nativeDrainLog()?.filterNotNull().orEmpty() }.getOrDefault(emptyList())
    }

    fun validate(configJson: String): NativeResult = call { validateConfig(configJson) }.toResult()

    fun prepare(configJson: String): Result<PreparedEngine> = call { nativePrepare(configJson) }
        .fold(
            onSuccess = { raw ->
                runCatching {
                    val json = JSONObject(raw)
                    check(json.optBoolean("ok")) { json.optString("error", "Preparation failed") }
                    PreparedEngine(
                        ipv4 = json.getString("ipv4"),
                        ipv6 = json.getString("ipv6"),
                        peer = json.getString("peer"),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )

    fun scan(configJson: String): Result<List<EndpointScanResult>> = call { nativeScan(configJson) }
        .mapCatching { raw ->
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { json.optString("error", "Endpoint scan failed") }
            val results = json.getJSONArray("results")
            List(results.length()) { index -> results.getJSONObject(index).toEndpointScanResult() }
        }

    fun testEndpoint(configJson: String): Result<EndpointScanResult> = call {
        nativeTestEndpoint(configJson)
    }.mapCatching { raw ->
        val json = JSONObject(raw)
        check(json.optBoolean("ok")) { json.optString("error", "Endpoint test failed") }
        json.toEndpointScanResult()
    }

    fun cancelScan(): Boolean = isLoaded && nativeCancelScan()

    fun run(
        configJson: String,
        preparedPeer: String,
        tunFd: Int,
        listener: NativeEngineListener,
    ): NativeResult = call {
        nativeRun(configJson, preparedPeer, tunFd, listener)
    }.toResult()

    fun stop(): Boolean = isLoaded && nativeStop()

    fun setSocketProtector(protector: NativeSocketProtector?) {
        loadResult.getOrThrow()
        nativeSetSocketProtector(protector)
    }

    private inline fun call(block: () -> String): Result<String> = loadResult.fold(
        onSuccess = { runCatching(block) },
        onFailure = { Result.failure(it) },
    )

    private fun Result<String>.toResult(): NativeResult = fold(
        onSuccess = { raw ->
            runCatching {
                val json = JSONObject(raw)
                NativeResult(
                    ok = json.optBoolean("ok"),
                    error = json.optString("error").takeIf(String::isNotBlank),
                )
            }.getOrElse { NativeResult(false, "Invalid native response: ${it.message}") }
        },
        onFailure = { NativeResult(false, "Native core unavailable: ${it.message}") },
    )

    private fun JSONObject.toEndpointScanResult() = EndpointScanResult(
        peer = getString("peer"),
        rttMillis = getLong("rttMs"),
    )

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun validateConfig(configJson: String): String
    private external fun nativeDrainLog(): Array<String?>?
    private external fun nativeExportIdentity(configPath: String): String
    private external fun nativeImportIdentity(configPath: String, payload: String): String

    @JvmStatic
    private external fun nativePrepare(configJson: String): String

    @JvmStatic
    private external fun nativeScan(configJson: String): String

    @JvmStatic
    private external fun nativeTestEndpoint(configJson: String): String

    @JvmStatic
    private external fun nativeCancelScan(): Boolean

    @JvmStatic
    private external fun nativeRun(
        configJson: String,
        preparedPeer: String,
        tunFd: Int,
        listener: NativeEngineListener,
    ): String

    @JvmStatic
    private external fun nativeStop(): Boolean

    @JvmStatic
    private external fun nativeSetSocketProtector(protector: NativeSocketProtector?)
}
