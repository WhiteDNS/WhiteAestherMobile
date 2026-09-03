package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.espresso.Espresso.pressBack
import com.whitedns.whiteaesther.AddressPair
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.TrafficSample
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
        onReset: () -> Unit = {},
        onSettings: (AppSettings) -> Unit = {},
        onConnect: () -> Unit = {},
        television: Boolean? = null,
        engineStatus: EngineStatus = EngineStatus(),
        addresses: AddressPair = AddressPair(),
        traffic: TrafficSample = TrafficSample(),
    ) {
        compose.setContent {
            WhiteAestherTheme {
                var current by remember { mutableStateOf(initial) }
                WhiteAestherApp(
                    settings = current,
                    engineStatus = engineStatus,
                    endpointScannerState = EndpointScannerState(),
                    nativeVersion = "1.8.0+android.0.2.0",
                    onSettingsChange = { current = it; onSettings(it) },
                    onConnect = { onConnect() },
                    onStop = {},
                    onScanEndpoints = { onScan() },
                    onTestEndpoint = {},
                    onResetEndpoint = { onReset() },
                    onCancelEndpointScan = {},
                    addresses = addresses,
                    traffic = traffic,
                    batteryExempt = true,
                    television = television,
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
    fun forgettingTheEndpointClearsTheFieldAndAsksForAFreshSearch() {
        var resetRequested = false
        setApp(
            initial = AppSettings(
                endpointMode = EndpointMode.CUSTOM_FIRST,
                customEndpoint = "162.159.197.3:443",
            ),
            onReset = { resetRequested = true },
        )

        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithText("Endpoint").performScrollTo().performClick()
        compose.onNodeWithTag("custom-endpoint-field").assertTextContains("162.159.197.3:443")
        compose.onNodeWithTag("reset-endpoint-button").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(resetRequested) }
        // The field is cleared with the setting, not left showing an address
        // the app has just been told to forget.
        compose.onNodeWithTag("custom-endpoint-field").assertTextContains("")
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
            assertEquals(com.whitedns.whiteaesther.data.TunnelProtocol.H2, saved?.transport)
        }
    }

    @Test
    fun tvStartsOnTheConnectionOrbAndCentreConnectsOnce() {
        var requests = 0
        setApp(television = true, onConnect = { requests++ })

        compose.onNodeWithTag("connect-orb")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun tvCanFocusAndScrollPastConnectedTimeToTheLastHomeCard() {
        setApp(
            television = true,
            engineStatus = EngineStatus(
                stage = EngineStage.CONNECTED,
                message = "Whole-device traffic is protected",
                connectedAtMillis = System.currentTimeMillis() - 65_000,
            ),
            addresses = AddressPair(real = "198.51.100.10", tunnel = "203.0.113.20"),
            traffic = TrafficSample(received = 4_096, sent = 2_048),
        )

        compose.onNodeWithTag("connect-orb")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("home-connected-for").assertIsFocused()

        compose.onNodeWithTag("home-connected-for")
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("home-address").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("home-session").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("home-connection-details")
            .assertIsFocused()
            .assertIsDisplayed()
    }

    @Test
    fun tvTabSelectionKeepsRemoteFocusOnTheSelectedTab() {
        setApp(television = true)

        compose.onNodeWithTag("tab-home")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("tab-routes")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.onNodeWithTag("tab-routes").assertIsFocused()
        compose.onNodeWithText("How it connects").assertExists()

        compose.onNodeWithTag("tab-home")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
            .assertIsFocused()
        compose.onNodeWithText("Ready when you are").assertExists()
    }

    @Test
    fun tvProfileAndCompositeSwitchActivateFromOneRemoteTarget() {
        var writes = 0
        setApp(television = true, onSettings = { writes++ })
        compose.onNodeWithTag("tab-routes").performClick()

        compose.onNodeWithTag("choice-strict-network")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("show-advanced-switch")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.runOnIdle { assertEquals(2, writes) }
        compose.onNodeWithTag("show-advanced-switch").assertIsOn()
    }

    @Test
    fun tvBackReturnsToTheOriginatingDetailRow() {
        setApp(television = true)
        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithTag("routes-endpoint")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .performKeyInput { pressKey(Key.DirectionCenter) }

        compose.onNodeWithText("Where it connects to").assertExists()
        pressBack()

        compose.onNodeWithTag("routes-endpoint").assertIsFocused()
    }

    @Test
    fun tvFocusBringsLongSettingsContentIntoViewAndHidesTileSetup() {
        setApp(initial = AppSettings(showAdvanced = true), television = true)
        compose.onNodeWithTag("tab-traffic").performClick()

        compose.onNodeWithTag("fragment-tls-switch")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .assertIsFocused()

        compose.onNodeWithTag("fragment-tls-switch")
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("ech-switch")
            .assertIsFocused()
            .assertIsDisplayed()

        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("add-tile-button").assertDoesNotExist()
    }

    @Test
    fun tvGamepadButtonsActivateAndNavigateBack() {
        var requests = 0
        setApp(television = true, onConnect = { requests++ })

        compose.onNodeWithTag("connect-orb")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.ButtonA) }
        compose.runOnIdle { assertEquals(1, requests) }

        compose.onNodeWithTag("tab-routes")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .performKeyInput { pressKey(Key.ButtonA) }
        compose.onNodeWithTag("routes-endpoint")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .performKeyInput { pressKey(Key.ButtonA) }
        compose.onNodeWithText("Where it connects to").assertExists()

        compose.onNodeWithTag("back-button")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .performKeyInput { pressKey(Key.ButtonB) }
        compose.onNodeWithTag("routes-endpoint").assertIsFocused()
    }

    @Test
    fun tvRoutingRuleFieldCanMoveDownWithoutTouch() {
        setApp(television = true)

        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithTag("routes-routing-rules").performScrollTo().performClick()
        compose.onNodeWithTag("route-block-field")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }

        compose.onNodeWithTag("route-block-field").assertIsNotFocused()
        compose.onNodeWithTag("route-direct-field").assertIsFocused()
    }
}
