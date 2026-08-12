package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WhiteAestherAppTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setApp(
        initial: AppSettings = AppSettings(),
        onScan: () -> Unit = {},
        onSettings: (AppSettings) -> Unit = {},
    ) {
        compose.setContent {
            WhiteAestherTheme {
                var current by remember { mutableStateOf(initial) }
                WhiteAestherApp(
                    settings = current,
                    engineStatus = EngineStatus(),
                    endpointScannerState = EndpointScannerState(),
                    nativeVersion = "1.5.0+android.0.2.0",
                    onSettingsChange = { current = it; onSettings(it) },
                    onConnect = {},
                    onStop = {},
                    onScanEndpoints = { onScan() },
                    onTestEndpoint = {},
                    onCancelEndpointScan = {},
                    batteryExempt = true,
                )
            }
        }
    }

    @Test
    fun homeShowsStatusAndNoSettingsControls() {
        setApp()

        compose.onNodeWithTag("connect-orb").assertExists()
        compose.onNodeWithText("Ready when you are").assertExists()
        // Home reports the profile but offers no way to change it.
        compose.onNodeWithText("Adaptive").assertExists()
        compose.onNodeWithTag("choice-adaptive").assertDoesNotExist()
    }

    @Test
    fun endpointLivesUnderRoutesAndAcceptsAnAddress() {
        setApp(AppSettings(endpointMode = EndpointMode.CUSTOM_FIRST))

        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithText("Endpoint").performScrollTo().performClick()
        compose.onNodeWithTag("custom-endpoint-field").performScrollTo().performTextInput("162.159.197.3:443")
        compose.onNodeWithTag("custom-endpoint-field").assertTextContains("162.159.197.3:443")
        // The field reports its own validation, which is the point of typing a valid one.
        compose.onNodeWithText("Valid address").assertExists()
    }

    @Test
    fun endpointScanCanBeStarted() {
        var scanRequested = false
        setApp(onScan = { scanRequested = true })

        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithText("Endpoint").performScrollTo().performClick()
        compose.onNodeWithTag("scan-endpoints-button").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(scanRequested) }
    }

    @Test
    fun advancedTrafficControlsAreBehindTheDisclosure() {
        setApp()

        compose.onNodeWithTag("tab-traffic").performClick()
        compose.onNodeWithTag("proxy-port-field").assertDoesNotExist()
        compose.onNodeWithTag("advanced-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("validation-switch").performScrollTo().assertIsOn()
        compose.onNodeWithTag("proxy-port-field").assertExists()
    }

    @Test
    fun profileSelectionWritesRealEngineSettings() {
        var saved: AppSettings? = null
        setApp(onSettings = { saved = it })

        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithTag("choice-strict-network").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(com.whitedns.whiteaesther.data.ScanStrategy.STEALTH, saved?.scanStrategy)
            assertEquals(com.whitedns.whiteaesther.data.MasqueTransport.H2, saved?.transport)
        }
    }
}
