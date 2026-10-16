package com.whitedns.whiteaesther.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.EndpointOperation
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.core.DiscoveryUpdate
import com.whitedns.whiteaesther.core.DiscoveryStage
import com.whitedns.whiteaesther.core.EndpointProbeProfile
import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointDiscoveryMemory
import com.whitedns.whiteaesther.data.TlsFragmentMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EndpointDiscoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val profile = EndpointProbeProfile(TunnelProtocol.H2, true, false, TlsFragmentMode.RECORDS)
    private val hit = EndpointScanResult("162.159.197.3:443", 210, profile, 2, 2, 18)
    private val tag = "endpoint-result-${hit.peer}-h2"

    private fun screen(busy: Boolean = false, persian: Boolean = false, onSettings: (AppSettings) -> Unit = {}) {
        compose.setContent {
            val context = LocalContext.current
            val config = android.content.res.Configuration(LocalConfiguration.current).apply {
                setLocale(Locale(if (persian) "fa" else "en"))
            }
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(config),
                LocalLayoutDirection provides if (persian) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteAestherTheme {
                    EndpointScreen(
                        AppSettings(), EngineStatus(),
                        EndpointScannerState(
                            operation = if (busy) EndpointOperation.SCANNING else null,
                            results = listOf(hit),
                            message = if (persian) "۱ مسیر پیدا شد · ۱ مسیر آزمون اینترنت را گذراند" else "1 route found · 1 passed the internet check",
                            discovery = DiscoveryUpdate(
                                stage = if (busy) DiscoveryStage.SEARCHING else DiscoveryStage.COMPLETE,
                                profile = profile, pass = 2, completed = 84, total = 420,
                                elapsedSeconds = 23, results = listOf(hit),
                                diagnostics = listOf("h3: no answer=40"),
                            ),
                        ),
                        onSettingsChange = onSettings, onScanEndpoints = {}, onTestEndpoint = {},
                        onResetEndpoint = {}, onCancelEndpointScan = {}, onBack = {},
                    )
                }
            }
        }
    }

    @Test fun selectingAResultAppliesItsTransportAndTlsSettings() {
        var selected: AppSettings? = null
        screen(onSettings = { selected = it })
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(TunnelProtocol.H2, selected?.transport)
            assertEquals(TlsFragmentMode.RECORDS, selected?.tlsOptions?.fragmentMode)
            assertEquals(true, selected?.fragmentTls)
        }
    }

    @Test fun partialResultsCannotChangeSettingsWhileNativeDiscoveryIsRunning() {
        var selections = 0
        screen(busy = true, onSettings = { selections++ })
        compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, selections) }
    }

    @Test fun persianResultsRenderWithProgressAndStability() {
        screen(persian = true)
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "endpoint-discovery-fa.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText(hit.peer).assertExists()
    }

    @Test fun memoryIsScopedToNetworkAndSecuritySettingsAndOnlyStoresConfirmedHints() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = EndpointDiscoveryMemory(context)
        val settings = AppSettings()
        val network = "test-network-${System.nanoTime()}"
        try {
            memory.write(network, settings, listOf(hit, hit.copy(peer = "162.159.197.9:443", successfulChecks = 0)))
            val saved = memory.read(network, settings)
            assertEquals(1, saved.size)
            assertEquals(hit.profile, saved.single().profile)
            assertEquals(0, saved.single().successfulChecks)
            assertTrue(memory.read("different-$network", settings).isEmpty())
            assertTrue(memory.read(network, settings.copy(tlsGroups = "X25519")).isEmpty())
            memory.forget(network, settings)
            assertTrue(memory.read(network, settings).isEmpty())
        } finally {
            memory.forget(network, settings)
        }
    }

    @Test fun nativeProgressUsesTheBundledJniInterface() {
        assertTrue(NativeAetherBridge.isLoaded)
        assertNotNull(NativeAetherBridge.scanProgress())
    }
}
