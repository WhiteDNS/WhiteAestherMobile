package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import com.whitedns.whiteaesther.AddressPair
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.ChainSettings
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
    fun homeOffersOneChoiceAndKeepsTheTechnicalRowsElsewhere() {
        setApp()
        compose.onNodeWithTag("home-method").assertExists()
        compose.onNodeWithTag("home-mode").assertExists()
        listOf("home-transport", "home-apps", "home-tools", "home-exit-node")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test
    fun modeStaysOnHomeAndTheAppsTabSaysWhenItDoesNotApply() {
        setApp()
        compose.onNodeWithTag("home-mode-proxy").performClick()
        compose.runOnIdle { assertEquals(EngineMode.PROXY, saved.mode) }
        compose.openAdvanced("network")
        compose.onNodeWithTag("choice-proxy-only").assertDoesNotExist()
        compose.onNodeWithTag("tab-apps").performClick()
        compose.onNodeWithTag("apps-proxy-note").assertExists()
    }

    @Test
    fun workerKeepsItsOwnSettingsAndDoesNotResetThePreviousExit() {
        setApp(AppSettings(chain = ChainSettings(enabled = true, manual = "fixture", node = "My exit")))
        compose.onNodeWithTag("home-method").performScrollTo().performClick()
        compose.onNodeWithTag("option-worker").assertDoesNotExist()
        compose.onNodeWithTag("option-own-server").performScrollTo().performClick()
        compose.onNodeWithTag("option-worker").performScrollTo().performClick()
        compose.onNodeWithTag("home-method").assertTextContains("VLESS", substring = true)
        compose.onNodeWithTag("home-worker-settings").assertExists()
        compose.onNodeWithTag("home-exit-node").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(Carrier.WORKER, saved.carrier)
            assertEquals("My exit", saved.chain.node)
            assertTrue(saved.chain.enabled)
            assertFalse(saved.chainForService().enabled)
        }
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
        compose.onNodeWithTag("method-done").performScrollTo().performClick()
        compose.onNodeWithTag("home-method").performClick()
        compose.onNodeWithTag("option-automatic").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(saved.automaticCarrier)
            assertEquals(Carrier.PSIPHON, saved.carrier)
        }
    }

    @Test
    fun aFailedAttemptOffersAnotherWayStraightFromHome() {
        status = EngineStatus(EngineStage.ERROR)
        setApp()
        compose.onNodeWithText("Try another way").performScrollTo().performClick()
        compose.onNodeWithTag("option-automatic").assertExists()
    }

    @Test
    fun busySessionsDisableHomeEditsAndStoppingDisablesTheConnectionAction() {
        setApp()
        listOf(EngineStage.PREPARING, EngineStage.CONNECTING, EngineStage.CONNECTED, EngineStage.STOPPING)
            .forEach { stage ->
                compose.runOnIdle { status = EngineStatus(stage = stage) }
                compose.onNodeWithTag("home-mode-proxy").assertIsNotEnabled()
                compose.onNodeWithTag("home-method").assertIsNotEnabled()
            }
        compose.onNodeWithTag("connect-orb").assertIsNotEnabled()
    }

    @Test
    fun nestedAdvancedPagesReturnToTheirActualOrigin() {
        setApp()
        compose.onNodeWithTag("tab-apps").performClick()
        pressBack()
        compose.onNodeWithTag("home-method").assertExists()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("advanced-aether").performScrollTo().performClick()
        compose.onNodeWithTag("routes-endpoint").performScrollTo().performClick()
        compose.onNodeWithText("Where it connects to").assertExists()
        pressBack()
        compose.onNodeWithTag("routes-endpoint").assertExists()
        pressBack()
        compose.onNodeWithTag("advanced-aether").assertExists()
        pressBack()
        compose.onNodeWithTag("settings-advanced").assertExists()
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
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-diagnostics").performScrollTo().performClick()
        compose.onAllNodesWithText("Connection logs").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("home-address").assertExists()
    }

    @Test
    fun advancedPreferenceOpensTheFoldedTlsValues() {
        setApp()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("advanced-aether").performScrollTo().performClick()
        compose.onNodeWithTag("aether-stealth").performScrollTo().performClick()
        compose.onNodeWithTag("tls-groups-field").assertDoesNotExist()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("show-advanced-switch").performScrollTo().performClick()
        compose.onNodeWithTag("settings-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("advanced-aether").performScrollTo().performClick()
        compose.onNodeWithTag("aether-stealth").performScrollTo().performClick()
        compose.onNodeWithTag("tls-groups-field").assertExists()
    }

    @Test
    fun identityAndLogsStayReachableFromSettings() {
        setApp()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("settings-identity").performScrollTo().performClick()
        pressBack()
        compose.onNodeWithTag("settings-identity").assertExists()
        pressBack()
        compose.onNodeWithTag("settings-diagnostics").performScrollTo().performClick()
        compose.onAllNodesWithText("Connection logs").onFirst().assertIsDisplayed()
        pressBack()
        pressBack()
        compose.onNodeWithTag("home-method").assertExists()
    }

    @Test
    fun aMethodsSettingsOpenInsideItsOwnCard() {
        setApp(AppSettings(automaticCarrier = false, carrier = Carrier.PSIPHON))
        compose.onNodeWithTag("home-method").performClick()
        compose.onNodeWithTag("psiphon-countries").assertExists()
        compose.onNodeWithTag("option-tor").performScrollTo().performClick()
        compose.onNodeWithTag("psiphon-countries").assertDoesNotExist()
        compose.onNodeWithTag("option-snowflake").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Carrier.TOR, saved.carrier)
            assertEquals(com.whitedns.whiteaesther.data.TorBridge.SNOWFLAKE, saved.torBridge)
        }
        compose.onNodeWithTag("method-done").performScrollTo().performClick()
        compose.onNodeWithTag("option-tor").assertDoesNotExist()
    }

    @Test
    fun ownServersOpenUnderTheirCardNotBesideIt() {
        setApp()
        compose.onNodeWithTag("home-method").performClick()
        compose.onNodeWithTag("option-proxy").assertDoesNotExist()
        compose.onNodeWithTag("option-own-server").performScrollTo().performClick()
        compose.onNodeWithTag("option-proxy").performScrollTo().performClick()
        compose.onNodeWithTag("own-server-nodes").assertExists()
        compose.runOnIdle { assertEquals(Carrier.PROXY, saved.carrier) }
    }

    @Test
    fun aConnectedSessionShowsPingAndTheExitCountry() {
        status = EngineStatus(EngineStage.CONNECTED)
        setApp(addresses = AddressPair(tunnel = "203.0.113.20", tunnelCountry = "DE", tunnelLatencyMs = 180))
        // The row itself has no text; its cells do.
        compose.onNodeWithTag("home-exit-facts").assertExists()
        compose.onNodeWithText("180 ms").assertExists()
        compose.onNodeWithText("\uD83C\uDDE9\uD83C\uDDEA", substring = true).assertExists()
    }

    @Test
    fun twoHopsShowTheFirstMethodAndOfferOnlyTheSecond() {
        setApp(AppSettings(automaticCarrier = false, carrier = Carrier.AETHER))
        compose.openAdvanced("two_hop")
        compose.onNodeWithTag("carrier-first-aether").assertDoesNotExist()
        compose.onNodeWithTag("carrier-second-tor").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Carrier.AETHER, saved.carrier)
            assertEquals(Carrier.TOR, saved.secondCarrier)
        }
    }

    @Test
    fun theHubMarksAChangedPageAndResetPutsItBack() {
        setApp(AppSettings(fragmentTls = true, routeBlock = "ads.example.com"))
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("advanced-aether").assertTextContains("Changed", substring = true)
        compose.onNodeWithTag("advanced-safety").assertTextContains("Default", substring = true)
        compose.onNodeWithTag("advanced-reset-all").performScrollTo().performClick()
        compose.onNodeWithTag("advanced-reset-confirm").performClick()
        compose.runOnIdle {
            assertFalse(saved.fragmentTls)
            assertEquals("ads.example.com", saved.routeBlock)
        }
        compose.onNodeWithTag("advanced-reset-all").assertDoesNotExist()
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
