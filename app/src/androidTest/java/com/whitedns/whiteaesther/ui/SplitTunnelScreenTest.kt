package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.InstalledApps
import com.whitedns.whiteaesther.data.SplitTunnel
import com.whitedns.whiteaesther.data.SplitTunnelMode
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SplitTunnelScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var saved: AppSettings? = null

    private fun setApp(initial: AppSettings = AppSettings()) {
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
                    batteryExempt = true,
                )
            }
        }
        compose.onNodeWithTag("tab-traffic").performClick()
        compose.onNodeWithText("Apps").performScrollTo().performClick()
    }

    @Test
    fun theDeviceReportsAppsToChooseFrom() {
        // The whole feature depends on package visibility. Without the queries
        // element in the manifest this returns only our own package on Android
        // 11 and later, and the screen would be permanently empty.
        val apps = InstalledApps.launchable(context)

        assertTrue("no launchable apps visible; check the manifest queries", apps.size > 1)
        assertTrue("every app should have a name", apps.all { it.label.isNotBlank() })
    }

    @Test
    fun thisAppIsNeverOfferedAsSomethingToRoute() {
        setApp(AppSettings(splitTunnel = SplitTunnel(mode = SplitTunnelMode.ONLY)))

        // Routing WhiteAesther through its own tunnel is the loop the design
        // exists to prevent, so it is not a choice the user can make by mistake.
        compose.onNodeWithTag("split-app-${context.packageName}").assertDoesNotExist()
    }

    @Test
    fun theAppListStaysHiddenWhileEverythingIsCarried() {
        setApp()

        // Nothing to pick when the answer is "all of them". Showing a list here
        // invites choosing from it and believing the choice took effect.
        compose.onNodeWithTag("split-search").assertDoesNotExist()
    }

    @Test
    fun choosingAModeRevealsTheList() {
        setApp()
        compose.onNodeWithText("All except these").performScrollTo().performClick()

        assertEquals(SplitTunnelMode.EXCEPT, saved?.splitTunnel?.mode)
        compose.onNodeWithTag("split-search").performScrollTo().assertExists()
    }

    @Test
    fun anEmptyAllowListSaysNothingWouldBeCarried() {
        setApp(AppSettings(splitTunnel = SplitTunnel(mode = SplitTunnelMode.ONLY)))

        // Otherwise this connects and carries nothing, which is
        // indistinguishable from a connection that failed.
        compose.onNodeWithText("Nothing would be carried").performScrollTo().assertExists()
    }

    @Test
    fun pickingAnAppRecordsIt() {
        val target = InstalledApps.launchable(context)
            .first { it.packageName != context.packageName }
        setApp(AppSettings(splitTunnel = SplitTunnel(mode = SplitTunnelMode.EXCEPT)))

        compose.onNodeWithTag("split-app-${target.packageName}").performScrollTo().performClick()

        assertTrue(saved?.splitTunnel?.packages?.contains(target.packageName) == true)
        assertFalse(saved?.splitTunnel?.packages?.contains(context.packageName) == true)
    }
}
