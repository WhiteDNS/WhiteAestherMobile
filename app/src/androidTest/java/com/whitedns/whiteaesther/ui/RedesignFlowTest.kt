package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import com.whitedns.whiteaesther.AddressPair
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RedesignFlowTest {
    @get:Rule val compose = createComposeRule()
    private var saved = AppSettings()
    private var status by mutableStateOf(EngineStatus())

    private fun setApp(initial: AppSettings = AppSettings(), addresses: AddressPair = AddressPair()) {
        saved = initial
        compose.setContent {
            var current by remember { mutableStateOf(initial) }
            WhiteAestherTheme {
                WhiteAestherApp(
                    settings = current, engineStatus = status,
                    endpointScannerState = EndpointScannerState(), nativeVersion = "test",
                    onSettingsChange = { current = it; saved = it },
                    onConnect = {}, onStop = {}, onScanEndpoints = {}, onTestEndpoint = {},
                    onResetEndpoint = {}, onCancelEndpointScan = {}, addresses = addresses,
                    batteryExempt = true,
                )
            }
        }
    }

    @Test
    fun modeCanBeChangedFromHomeAndProxyExplainsItsCoverage() {
        setApp()
        compose.onNodeWithTag("home-mode-proxy").performClick()
        compose.runOnIdle { assertEquals(EngineMode.PROXY, saved.mode) }
        compose.onNodeWithTag("home-apps").assertIsNotEnabled()
        compose.onNodeWithTag("home-mode-tun").performClick()
        compose.runOnIdle { assertEquals(EngineMode.TUN, saved.mode) }
    }

    @Test
    fun homeMethodPickerWritesRealSettingsAndPreservesTheManualChoiceForAutomatic() {
        setApp()
        compose.onNodeWithTag("home-method").performScrollTo().performClick()
        compose.onNodeWithTag("option-psiphon").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(saved.automaticCarrier)
            assertEquals(Carrier.PSIPHON, saved.carrier)
        }
        compose.onNodeWithTag("home-method").assertTextContains("Psiphon")
        compose.onNodeWithTag("home-method").performClick()
        compose.onNodeWithTag("option-automatic").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(saved.automaticCarrier)
            assertEquals(Carrier.PSIPHON, saved.carrier)
        }
    }

    @Test
    fun busySessionsDisableHomeEditsAndStoppingDisablesTheConnectionAction() {
        setApp()
        listOf(EngineStage.PREPARING, EngineStage.CONNECTING, EngineStage.CONNECTED, EngineStage.STOPPING)
            .forEach { stage ->
                compose.runOnIdle { status = EngineStatus(stage = stage) }
                compose.onNodeWithTag("home-mode-proxy").assertIsNotEnabled()
                compose.onNodeWithTag("home-method").assertIsNotEnabled()
                compose.onNodeWithTag("home-apps").assertIsNotEnabled()
            }
        compose.onNodeWithTag("connect-orb").assertIsNotEnabled()
    }

    @Test
    fun nestedToolsAndAppSelectionReturnToTheirActualOrigin() {
        setApp()
        compose.onNodeWithTag("home-apps").performScrollTo().performClick()
        pressBack()
        compose.onNodeWithTag("home-method").assertExists()
        compose.onNodeWithTag("home-tools").performScrollTo().performClick()
        compose.onNodeWithTag("tools-endpoint").performScrollTo().performClick()
        compose.onNodeWithText("Where it connects to").assertExists()
        pressBack()
        compose.onNodeWithTag("tools-endpoint").assertExists()
        pressBack()
        compose.onNodeWithTag("home-method").assertExists()
    }

    @Test
    fun detailsAreOptionalAndKeepTheirStateAcrossTabs() {
        status = EngineStatus(EngineStage.CONNECTED, message = "fixture engine detail")
        setApp(addresses = AddressPair(real = "198.51.100.10", tunnel = "203.0.113.20"))
        compose.onNodeWithTag("home-address").assertDoesNotExist()
        compose.onNodeWithText("fixture engine detail").assertDoesNotExist()
        compose.onNodeWithTag("home-details-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("home-address").assertExists()
        compose.onNodeWithText("fixture engine detail").assertExists()
        compose.onNodeWithTag("tab-logs").performClick()
        compose.onNodeWithText("Connection logs").assertIsDisplayed()
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("home-address").assertExists()
    }

    @Test
    fun advancedPreferenceAppliesToPreviouslyVisitedScreens() {
        setApp()
        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithTag("choice-strict-network").assertDoesNotExist()
        compose.onNodeWithTag("tab-traffic").performClick()
        compose.onNodeWithTag("proxy-port-field").assertDoesNotExist()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("show-advanced-switch").performScrollTo().performClick()
        compose.onNodeWithTag("back-button").performClick()
        compose.onNodeWithTag("proxy-port-field").assertExists()
        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithTag("choice-strict-network").assertExists()
    }

    @Test
    fun settingsGearAndLogsStayReachableWithoutAddingAnotherBottomTab() {
        setApp()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-identity").performScrollTo().performClick()
        pressBack()
        compose.onNodeWithTag("settings-identity").assertExists()
        pressBack()
        compose.onNodeWithTag("home-method").assertExists()
        compose.onNodeWithTag("tab-logs").performClick()
        compose.onNodeWithText("Connection logs").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("home-method").assertExists()
    }

    @Test
    fun proxyCardCopiesTheUsableLocalAddressEvenWhenLanSharingIsEnabled() {
        setApp(AppSettings(mode = EngineMode.PROXY, lanSharing = true, proxyPort = 2819))
        compose.onNodeWithTag("home-proxy-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("proxy-listener-address").assertTextContains("127.0.0.1:2819")
        compose.onNodeWithTag("proxy-copy-address").performClick()
        compose.runOnIdle {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("127.0.0.1:2819", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
