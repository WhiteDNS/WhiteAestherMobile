package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Offering the local proxy to the rest of the network.
 *
 * The credential fields are the interesting part. They are bound to settings
 * that are saved asynchronously, and the first version wrote each keystroke
 * straight to that round trip -- so the field was rewritten with the previous
 * text before the next character arrived and could not be typed into at all.
 * Every unit test passed, because the bug was not in the values.
 */
class LanSharingTest {
    @get:Rule
    val compose = createComposeRule()

    private var saved: AppSettings? = null

    /**
     * Mirrors the real save path: a suspending round trip, not an assignment.
     *
     * With the delay removed this test passes against the broken version too,
     * which is what made the bug invisible until it reached a phone.
     */
    private fun setApp(initial: AppSettings) {
        saved = null
        compose.setContent {
            WhiteAestherTheme {
                var current by remember { mutableStateOf(initial) }
                val scope = rememberCoroutineScope()
                WhiteAestherApp(
                    settings = current,
                    engineStatus = EngineStatus(),
                    endpointScannerState = EndpointScannerState(),
                    nativeVersion = "1.8.0+android.0.2.0",
                    onSettingsChange = { updated ->
                        saved = updated
                        scope.launch {
                            delay(SAVE_ROUND_TRIP_MS)
                            current = updated
                        }
                    },
                    onConnect = {},
                    onStop = {},
                    onScanEndpoints = {},
                    onTestEndpoint = {},
                    onResetEndpoint = {},
                    onCancelEndpointScan = {},
                    batteryExempt = true,
                )
            }
        }
        compose.onNodeWithTag("tab-traffic").performClick()
    }

    private fun openSharing() {
        compose.onNodeWithTag("advanced-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("lan-sharing-switch").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 1_000) {
            compose.onAllNodesWithTag("lan-sharing-notice").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theCredentialFieldsAcceptWholeWords() {
        setApp(AppSettings(mode = EngineMode.PROXY))
        openSharing()

        compose.onNodeWithTag("lan-username-field").performScrollTo().performTextInput("phone")
        compose.waitForIdle()

        // The whole word, not the first letter of it.
        compose.onNodeWithTag("lan-username-field").assertTextContains("phone", substring = true)
        assertEquals("phone", saved?.lanUsername)
    }

    @Test
    fun sharingWithoutAPasswordIsNotReportedAsSomethingToFix() {
        setApp(AppSettings(mode = EngineMode.PROXY))
        openSharing()

        // Both fields empty is a supported setup, so the screen has to leave
        // the user able to walk away from them.
        compose.onNodeWithTag("lan-sharing-notice").performScrollTo().assertExists()
        compose.onNodeWithTag("lan-username-field").performScrollTo().assertExists()
    }

    @Test
    fun theAddressToPointClientsAtIsShown() {
        setApp(AppSettings(mode = EngineMode.PROXY))
        openSharing()

        // The listener binds to every interface, and "0.0.0.0" is not
        // something anyone can type into another machine.
        compose.onNodeWithTag("lan-sharing-address").performScrollTo().assertExists()
    }

    private companion object {
        /** Long enough to lose a keystroke, short enough to keep the test quick. */
        const val SAVE_ROUND_TRIP_MS = 40L
    }
}
