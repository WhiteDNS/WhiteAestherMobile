package com.whitedns.whiteaesther.core

import android.content.Context
import com.whitedns.whiteaesther.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.whitedns.whiteaesther.service.EngineLog
import com.whitedns.whiteaesther.service.LogLevel

class NativeEndpointDiscovery(
    private val context: Context,
    private val networkUnchanged: () -> Boolean = { true },
    private val serialize: (AppSettings) -> String = { it.toNativeJson(context) },
) : EndpointDiscoveryBackend {
    private val cancellationRequested = AtomicBoolean(false)

    override suspend fun scan(
        settings: AppSettings,
        budgetMillis: Long,
        onProgress: (NativeScanProgress) -> Unit,
    ): Result<List<EndpointScanResult>> = coroutineScope {
        val scanId = nextId.incrementAndGet()
        val json = JSONObject(serialize(settings))
            .put("scanBudgetMillis", budgetMillis).put("scanId", scanId).toString()
        val polling = launch {
            // Let nativeScan reset the previous pass before reading its snapshot.
            delay(300)
            while (isActive) {
                if (cancellationRequested.get() || !networkUnchanged()) NativeAetherBridge.cancelScan()
                NativeAetherBridge.scanProgress()?.takeIf { it.scanId == scanId }?.let(onProgress)
                delay(300)
            }
        }
        try {
            withContext(Dispatchers.IO) { NativeAetherBridge.scan(json) }.also { result ->
                result.fold(
                    onSuccess = { EngineLog.record(LogLevel.INFO, "scanner", "${settings.transport.wireName}: ${it.size} authenticated endpoints") },
                    onFailure = { EngineLog.record(LogLevel.WARN, "scanner", "${settings.transport.wireName}: ${it.message ?: "scan failed"}") },
                )
            }
        } finally {
            polling.cancel()
        }
    }

    override suspend fun confirm(settings: AppSettings, budgetMillis: Long): Result<EndpointScanResult> = coroutineScope {
        val polling = launch {
            while (isActive) {
                if (cancellationRequested.get() || !networkUnchanged()) NativeAetherBridge.cancelScan()
                delay(100)
            }
        }
        try {
            val json = JSONObject(serialize(settings)).put("scanBudgetMillis", budgetMillis).toString()
            withContext(Dispatchers.IO) { NativeAetherBridge.testEndpoint(json) }.also { result ->
                result.exceptionOrNull()?.let {
                    EngineLog.record(LogLevel.WARN, "scanner", "${settings.transport.wireName} traffic confirmation: ${it.message ?: "failed"}")
                }
            }
        } finally {
            polling.cancel()
        }
    }

    override fun cancel() {
        cancellationRequested.set(true)
        NativeAetherBridge.cancelScan()
    }

    private companion object { val nextId = AtomicLong(0) }
}
