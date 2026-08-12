package com.whitedns.whiteaesther.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG,
}

data class LogEntry(
    val timeMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun formattedTime(): String = TIME_FORMAT.format(Date(timeMillis))

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

/**
 * A bounded in-memory record of what the engine did, for the Diagnostics screen
 * and the report the user can send to the developer. Nothing is written to disk
 * and nothing leaves the process on its own.
 */
object EngineLog {
    private const val CAPACITY = 400

    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    /**
     * Safe to call from any thread. The engine reports through a native
     * callback while the service writes from its own scope, and a plain
     * read-modify-write on [MutableStateFlow.value] loses entries when those
     * two land together -- exactly when a connection is failing and the log
     * matters most.
     */
    fun record(level: LogLevel, tag: String, message: String) {
        if (message.isBlank()) return
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        mutableEntries.update { (it + entry).takeLast(CAPACITY) }
    }

    fun clear() {
        mutableEntries.value = emptyList()
    }
}
