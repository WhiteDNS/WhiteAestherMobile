package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The notice offering to exclude the app from battery optimisation.
 *
 * Tested here because the failure it exists for is not reproducible in CI: on
 * a Samsung the standard request is honoured and the notice clears itself, and
 * on a Xiaomi the same request changes nothing and the platform keeps
 * answering false however well the user has excluded the app. A test that only
 * ran the working path would have shipped the phone that gets stuck.
 */
class BatteryNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private var saved: AppSettings? = null

    private fun setApp(initial: AppSettings, batteryExempt: Boolean) {
        saved = null
        compose.setContent {
            WhiteAestherTheme {
                var current by remember { mutableStateOf(initial) }
                WhiteAestherApp(
                    settings = current,
                    engineStatus = EngineStatus(),
                    endpointScannerState = EndpointScannerState(),
                    nativeVersion = "1.7.0+android.0.2.0",
                    onSettingsChange = { current = it; saved = it },
                    onConnect = {},
                    onStop = {},
                    onScanEndpoints = {},
                    onTestEndpoint = {},
                    onCancelEndpointScan = {},
                    batteryExempt = batteryExempt,
                )
            }
        }
        compose.onNodeWithTag("tab-settings").performClick()
    }

    @Test
    fun anExemptPhoneIsNotAskedForAnything() {
        setApp(AppSettings(), batteryExempt = true)

        compose.onNodeWithTag("battery-exemption-button").assertDoesNotExist()
        compose.onNodeWithTag("battery-app-settings-button").assertDoesNotExist()
    }

    @Test
    fun thePlainRequestComesFirst() {
        setApp(AppSettings(), batteryExempt = false)

        // The dialog is one tap and works on most phones. Sending everyone to
        // hunt through system settings instead would be worse for all of them.
        compose.onNodeWithTag("battery-exemption-button").performScrollTo().assertExists()
        compose.onNodeWithTag("battery-app-settings-button").assertDoesNotExist()
    }

    @Test
    fun aPhoneThatIgnoredTheRequestIsNotAskedTheSameThingAgain() {
        setApp(AppSettings(batteryRequestIgnored = true), batteryExempt = false)

        // Repeating the dialog is the one action already known to do nothing
        // here, so it is not what the card offers.
        compose.onNodeWithTag("battery-exemption-button").assertDoesNotExist()
        compose.onNodeWithTag("battery-app-settings-button").performScrollTo().assertExists()
    }

    @Test
    fun theUserCanEndANoticeTheirPhoneWillNeverClear() {
        setApp(AppSettings(batteryRequestIgnored = true), batteryExempt = false)

        compose.onNodeWithTag("battery-dismiss-button").performScrollTo().performClick()

        // Persisted rather than remembered: the platform answer stays false on
        // this phone, so a notice cleared only in memory returns on next launch
        // and the user is asked forever.
        assertTrue("dismissing must be saved", saved?.batteryNoticeDismissed == true)
        compose.onNodeWithTag("battery-app-settings-button").assertDoesNotExist()
    }
}
