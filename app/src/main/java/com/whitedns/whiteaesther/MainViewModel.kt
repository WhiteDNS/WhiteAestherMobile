package com.whitedns.whiteaesther

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.MasqueTransport
import com.whitedns.whiteaesther.data.SettingsRepository
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EndpointOperation {
    SCANNING,
    TESTING,
    CANCELLING,
}

data class EndpointScannerState(
    val operation: EndpointOperation? = null,
    val results: List<EndpointScanResult> = emptyList(),
    val message: String? = null,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)

    val settings = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )
    val engineStatus = EngineStatusStore.status
    private val mutableEndpointScannerState = MutableStateFlow(EndpointScannerState())
    val endpointScannerState = mutableEndpointScannerState.asStateFlow()
    private var endpointJob: Job? = null

    fun save(settings: AppSettings) {
        viewModelScope.launch { repository.save(settings) }
    }

    fun scanEndpoints(settings: AppSettings) {
        if (!canRunEndpointOperation()) return
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.SCANNING,
                results = mutableEndpointScannerState.value.results,
                message = "Scanning validated MASQUE routes…",
            )
            // The prober picks TCP or UDP from the transport it is handed: H2
            // probes over TCP, anything else over QUIC. Scanning with the
            // configured transport therefore searches UDP only on the default
            // profile, and a network that blocks UDP returns nothing at all --
            // which is what Iranian mobile users were seeing. Sweep the other
            // transport too rather than reporting an empty network.
            val base = settings.copy(endpointMode = EndpointMode.AUTOMATIC, customEndpoint = "")
            val other = if (base.transport == MasqueTransport.H3) MasqueTransport.H2 else MasqueTransport.H3
            var result = withContext(Dispatchers.IO) {
                NativeAetherBridge.scan(base.toNativeJson(getApplication()))
            }
            if (result.getOrNull()?.isEmpty() != false &&
                mutableEndpointScannerState.value.operation == EndpointOperation.SCANNING
            ) {
                mutableEndpointScannerState.value = mutableEndpointScannerState.value.copy(
                    message = "Nothing over ${base.transport.label}, trying ${other.label}…",
                )
                result = withContext(Dispatchers.IO) {
                    NativeAetherBridge.scan(base.copy(transport = other).toNativeJson(getApplication()))
                }
            }
            result.fold(
                onSuccess = { endpoints ->
                    if (mutableEndpointScannerState.value.operation == EndpointOperation.CANCELLING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            message = "Scan cancelled",
                        )
                    } else {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = endpoints,
                            message = "${endpoints.size} validated endpoint${if (endpoints.size == 1) "" else "s"} found",
                        )
                    }
                },
                onFailure = { error ->
                    if (mutableEndpointScannerState.value.operation == EndpointOperation.CANCELLING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            message = "Scan cancelled",
                        )
                    } else if (mutableEndpointScannerState.value.operation == EndpointOperation.SCANNING) {
                        mutableEndpointScannerState.value = EndpointScannerState(
                            results = mutableEndpointScannerState.value.results,
                            error = error.message ?: "Endpoint scan failed",
                        )
                    }
                },
            )
        }
    }

    fun testEndpoint(settings: AppSettings) {
        val validationError = settings.copy(endpointMode = EndpointMode.CUSTOM_ONLY)
            .endpointValidationError()
        if (validationError != null) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                error = validationError,
            )
            return
        }
        if (!canRunEndpointOperation()) return
        endpointJob = viewModelScope.launch {
            mutableEndpointScannerState.value = EndpointScannerState(
                operation = EndpointOperation.TESTING,
                results = mutableEndpointScannerState.value.results,
                message = "Testing custom endpoint…",
            )
            val config = settings.copy(endpointMode = EndpointMode.CUSTOM_ONLY)
                .toNativeJson(getApplication())
            val result = withContext(Dispatchers.IO) { NativeAetherBridge.testEndpoint(config) }
            result.fold(
                onSuccess = { endpoint ->
                    mutableEndpointScannerState.value = EndpointScannerState(
                        results = mutableEndpointScannerState.value.results,
                        message = "${endpoint.peer} validated in ${endpoint.rttMillis} ms",
                    )
                },
                onFailure = { error ->
                    mutableEndpointScannerState.value = EndpointScannerState(
                        results = mutableEndpointScannerState.value.results,
                        error = error.message ?: "Endpoint test failed",
                    )
                },
            )
        }
    }

    fun cancelEndpointScan() {
        if (mutableEndpointScannerState.value.operation != EndpointOperation.SCANNING) return
        NativeAetherBridge.cancelScan()
        mutableEndpointScannerState.value = EndpointScannerState(
            operation = EndpointOperation.CANCELLING,
            results = mutableEndpointScannerState.value.results,
            message = "Cancelling scan…",
        )
    }

    private fun canRunEndpointOperation(): Boolean {
        if (endpointJob?.isCompleted == false) return false
        if (EngineStatusStore.status.value.stage !in setOf(EngineStage.IDLE, EngineStage.ERROR)) {
            mutableEndpointScannerState.value = EndpointScannerState(
                results = mutableEndpointScannerState.value.results,
                error = "Disconnect before testing or scanning endpoints",
            )
            return false
        }
        return true
    }

    override fun onCleared() {
        NativeAetherBridge.cancelScan()
        endpointJob?.cancel()
        super.onCleared()
    }
}
