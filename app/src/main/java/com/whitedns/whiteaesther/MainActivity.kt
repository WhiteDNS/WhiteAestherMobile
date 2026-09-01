package com.whitedns.whiteaesther

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import com.whitedns.whiteaesther.ui.TvUiPolicy
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var pendingSettings: AppSettings? = null
    private var batteryExempt by mutableStateOf(true)

    /**
     * True between opening the exemption dialog and coming back from it.
     *
     * The dialog reports nothing, so returning while still not exempt is the
     * only evidence that this phone did not act on the request. Without it,
     * a user who simply declined would be told their phone is at fault.
     */
    private var batteryRequestOpen = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        continueConnectionRequest()
    }

    /** Held between asking for a destination and having one to write to. */
    private var pendingIdentityExport: String? = null

    private val identityExportTarget = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/toml"),
    ) { uri ->
        val payload = pendingIdentityExport
        pendingIdentityExport = null
        if (uri == null || payload == null) return@registerForActivityResult
        writeIdentityBackup(uri, payload)
    }

    private val identityImportSource = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        readIdentityBackup(uri)
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
        // Only acts while nothing is connected, which is the whole point: the
        // address without the tunnel can only be read when there is no tunnel.
        viewModel.captureRealAddressIfIdle()
        if (batteryRequestOpen) {
            batteryRequestOpen = false
            // Exempt now means the standard path worked here, which is worth
            // recording too: a phone that once honoured the request should not
            // be accused later if the user revokes the exemption by hand.
            val settings = viewModel.settings.value
            val ignored = !batteryExempt
            if (settings.batteryRequestIgnored != ignored) {
                viewModel.save(settings.copy(batteryRequestIgnored = ignored))
            }
        }
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
        batteryRequestOpen = true
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure {
                    batteryRequestOpen = false
                    explainUnavailable("Battery settings are not available on this device.")
                }
        }
    }

    /**
     * This app's own page in system settings, where every manufacturer puts
     * its battery policy somewhere.
     *
     * Deliberately not an OEM-specific screen. The activities those live
     * behind are internal, unstable across versions, and reached by class
     * name; one that has been renamed throws, and one that has been removed
     * opens nothing. This intent is part of the platform and always resolves.
     */
    /**
     * Hands the release page to a browser.
     *
     * Deliberately not a download: an app that fetches and installs its own
     * replacement is the shape of the thing this app exists to be trusted
     * against, and the user should see where the file comes from.
     */
    private fun openReleasePage(url: String) {
        openExternal(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            "No app on this device can open the download page.",
        )
    }

    /**
     * Offers to put the tile in the shade, on the versions that can ask.
     *
     * Android 13 and later let an app request it; before that the only route is
     * the user finding it in the shade's own edit screen, which nobody does
     * without being told the tile exists.
     */
    private fun offerQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(android.app.StatusBarManager::class.java)
        if (manager == null) {
            explainUnavailable("This device could not open the tile setup.")
            return
        }
        runCatching {
            manager.requestAddTileService(
                android.content.ComponentName(
                    this,
                    com.whitedns.whiteaesther.service.AetherTileService::class.java,
                ),
                getString(R.string.app_name),
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                mainExecutor,
                { result ->
                    if (
                        result != android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                        result != android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                    ) {
                        explainUnavailable("This device could not add the tile.")
                    }
                },
            )
        }.onFailure { explainUnavailable("This device could not open the tile setup.") }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        openExternal(intent, "App settings are not available on this device.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TvUiPolicy.isTelevision(resources.configuration.uiMode)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
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
                    onTestChainNodes = { viewModel.testChainNodes() },
                    onTestChainNodesSelected = { viewModel.testChainNodes(it) },
                    identityMessage = viewModel.identityMessage.collectAsStateWithLifecycle().value,
                    onExportIdentity = ::exportIdentity,
                    onImportIdentity = {
                        // Any type: pickers vary in whether they know .toml, and
                        // a file the user cannot select is worse than one the
                        // engine rejects with a reason.
                        val type = arrayOf("*/*")
                        val intent = ActivityResultContracts.OpenDocument().createIntent(this, type)
                        if (intent.resolveActivity(packageManager) == null) {
                            explainUnavailable("No file picker is available on this device.")
                        } else {
                            identityImportSource.launch(type)
                        }
                    },
                    onShareReport = ::shareReport,
                    onCopyReport = ::copyReport,
                    onClearLog = EngineLog::clear,
                    addresses = viewModel.addresses.collectAsStateWithLifecycle().value,
                    traffic = viewModel.traffic.collectAsStateWithLifecycle().value,
                    update = viewModel.update.collectAsStateWithLifecycle().value,
                    onLiftBlock = { AetherVpnService.liftBlock(this) },
                    onOpenUpdate = ::openReleasePage,
                    onDismissUpdate = viewModel::dismissUpdate,
                    batteryExempt = batteryExempt,
                    onRequestBatteryExemption = ::requestBatteryExemption,
                    onOpenAppSettings = ::openAppSettings,
                    onAddTile = ::offerQuickSettingsTile,
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
        if (intent.resolveActivity(packageManager) == null) {
            explainUnavailable("No app on this device can send the report.")
            return
        }
        openExternal(
            Intent.createChooser(intent, "Send diagnostics"),
            "No app on this device can send the report.",
        )
    }

    /**
     * Asks where to save the identity, but only once there is one to save.
     *
     * Opening a picker first and failing afterwards would leave the user with an
     * empty file named as though it held their identity -- which they might then
     * rely on.
     */
    private fun exportIdentity() {
        val payload = viewModel.exportIdentity() ?: return
        val name = "whiteaesther-identity.toml"
        val intent = ActivityResultContracts.CreateDocument("application/toml")
            .createIntent(this, name)
        if (intent.resolveActivity(packageManager) == null) {
            explainUnavailable("No file picker is available on this device.")
            return
        }
        pendingIdentityExport = payload
        identityExportTarget.launch(name)
    }

    private fun writeIdentityBackup(uri: Uri, payload: String) {
        val written = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload.toByteArray()) }
                ?: error("could not open that location")
        }
        viewModel.reportIdentityWrite(written.exceptionOrNull()?.message)
    }

    private fun readIdentityBackup(uri: Uri) {
        val payload = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("could not read that file")
        }
        payload.fold(
            onSuccess = viewModel::importIdentity,
            onFailure = { viewModel.reportIdentityWrite(it.message) },
        )
    }

    private fun copyReport(report: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("WhiteAesther diagnostics", report))
    }

    private fun openExternal(intent: Intent, unavailable: String) {
        if (intent.resolveActivity(packageManager) == null) {
            explainUnavailable(unavailable)
            return
        }
        runCatching { startActivity(intent) }.onFailure { explainUnavailable(unavailable) }
    }

    private fun explainUnavailable(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun startService(settings: AppSettings) {
        AetherVpnService.start(
            this,
            settings.toNativeJson(this),
            settings.chainForService().encode(),
            settings.splitTunnel.encode(),
            settings.killSwitch,
            settings.strictKillSwitch,
        )
    }
}
