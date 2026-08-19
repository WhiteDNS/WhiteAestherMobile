package com.whitedns.whiteaesther

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.service.AetherVpnService
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.EngineLog
import com.whitedns.whiteaesther.service.EngineStatusStore
import com.whitedns.whiteaesther.ui.WhiteAestherApp
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var pendingSettings: AppSettings? = null
    private var batteryExempt by mutableStateOf(true)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        continueConnectionRequest()
    }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val settings = pendingSettings
        pendingSettings = null
        if (result.resultCode == Activity.RESULT_OK && settings != null) {
            startService(settings)
        } else {
            EngineStatusStore.update(
                EngineStatus(EngineStage.ERROR, EngineMode.TUN, message = "VPN permission was denied"),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        batteryExempt = isIgnoringBatteryOptimizations()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) ?: true

    /**
     * Opens the system dialog. The result is not returned to the caller, so the
     * state is re-read in onResume rather than assumed.
     */
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        batteryExempt = isIgnoringBatteryOptimizations()
        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            WhiteAestherTheme(themeMode = settings.themeMode) {
                WhiteAestherApp(
                    settings = settings,
                    engineStatus = viewModel.engineStatus.collectAsStateWithLifecycle().value,
                    endpointScannerState = viewModel.endpointScannerState.collectAsStateWithLifecycle().value,
                    chainState = viewModel.chainState.collectAsStateWithLifecycle().value,
                    nativeVersion = com.whitedns.whiteaesther.core.NativeAetherBridge.versionOrNull(),
                    logEntries = EngineLog.entries.collectAsStateWithLifecycle().value,
                    onSettingsChange = viewModel::save,
                    onConnect = ::requestConnection,
                    onStop = { AetherVpnService.stop(this) },
                    onScanEndpoints = viewModel::scanEndpoints,
                    onTestEndpoint = viewModel::testEndpoint,
                    onCancelEndpointScan = viewModel::cancelEndpointScan,
                    onRefreshChainNodes = { viewModel.refreshChainNodes(settings) },
                    onSelectChainNode = { node -> viewModel.selectChainNode(settings, node) },
                    onTestChainNodes = viewModel::testChainNodes,
                    onShareReport = ::shareReport,
                    onCopyReport = ::copyReport,
                    onClearLog = EngineLog::clear,
                    batteryExempt = batteryExempt,
                    onRequestBatteryExemption = ::requestBatteryExemption,
                )
            }
        }
    }

    private fun requestConnection(settings: AppSettings) {
        if (viewModel.endpointScannerState.value.operation != null) {
            EngineStatusStore.update(
                EngineStatus(
                    EngineStage.ERROR,
                    settings.mode,
                    message = "Cancel the endpoint scan or test before connecting",
                ),
            )
            return
        }
        settings.endpointValidationError()?.let { error ->
            EngineStatusStore.update(
                EngineStatus(EngineStage.ERROR, settings.mode, message = error),
            )
            return
        }
        pendingSettings = settings
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueConnectionRequest()
    }

    private fun continueConnectionRequest() {
        val settings = pendingSettings ?: return
        if (settings.mode == EngineMode.TUN) {
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent != null) {
                vpnPermission.launch(permissionIntent)
                return
            }
        }
        pendingSettings = null
        startService(settings)
    }

    /** Hands the report to the system share sheet -- the user picks where it goes. */
    private fun shareReport(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WhiteAesther diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Send diagnostics"))
    }

    private fun copyReport(report: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("WhiteAesther diagnostics", report))
    }

    private fun startService(settings: AppSettings) {
        AetherVpnService.start(this, settings.toNativeJson(this), settings.chain.encode())
    }
}
