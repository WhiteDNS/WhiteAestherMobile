package com.whitedns.whiteaesther.service

import com.whitedns.whiteaesther.data.EngineMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EngineStage {
    IDLE,
    PREPARING,
    CONNECTING,
    CONNECTED,
    STOPPING,
    ERROR,
}

data class EngineStatus(
    val stage: EngineStage = EngineStage.IDLE,
    val mode: EngineMode? = null,
    val peer: String? = null,
    /**
     * Blank by default, and the screen supplies the word.
     *
     * It used to default to "Ready", which is a sentence rather than a state:
     * every idle status carried an English word that no language setting could
     * reach, and it sat under the translated headline saying the same thing in
     * the language the user had left.
     */
    val message: String = "",
    /** When the tunnel came up, so elapsed time survives the UI being recreated. */
    val connectedAtMillis: Long? = null,
    /**
     * The carrier's loopback SOCKS5 port, when a carrier is running.
     *
     * Here because of what this process is not allowed to be. Everything it
     * opens is excluded from the interface -- that exclusion is what keeps the
     * carrier's own traffic out of the tunnel it is building -- so a request
     * made from here to ask "what does the internet see" leaves by the physical
     * network and answers with the address the tunnel exists to hide. Asking
     * through this port is the only way to get the true answer from inside the
     * app, and it is the carrier's own listener, so it costs nothing extra.
     */
    val carrierSocksPort: Int? = null,
)

object EngineStatusStore {
    private val mutableStatus = MutableStateFlow(EngineStatus())
    val status = mutableStatus.asStateFlow()

    fun update(status: EngineStatus) {
        val previous = mutableStatus.value
        mutableStatus.value = status
        if (previous.stage != status.stage || previous.message != status.message) {
            EngineLog.record(
                level = when {
                    status.stage == EngineStage.ERROR -> LogLevel.ERROR
                    status.message.contains("retry") -> LogLevel.WARN
                    else -> LogLevel.INFO
                },
                tag = "engine",
                message = buildString {
                    append(status.stage.name.lowercase())
                    if (status.message.isNotBlank()) append(": ").append(status.message)
                    status.peer?.let { append(" peer=").append(it) }
                },
            )
        }
    }
}
