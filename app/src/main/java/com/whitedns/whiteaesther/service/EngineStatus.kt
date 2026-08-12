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
    val message: String = "Ready",
)

object EngineStatusStore {
    private val mutableStatus = MutableStateFlow(EngineStatus())
    val status = mutableStatus.asStateFlow()

    fun update(status: EngineStatus) {
        val previous = mutableStatus.value
        mutableStatus.value = status
        if (previous.stage != status.stage || previous.message != status.message) {
            EngineLog.record(
                level = if (status.stage == EngineStage.ERROR) LogLevel.ERROR else LogLevel.INFO,
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
