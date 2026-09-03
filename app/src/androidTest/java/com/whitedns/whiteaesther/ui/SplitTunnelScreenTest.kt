package com.whitedns.whiteaesther.ui

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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
import org.junit.Assume.assumeTrue
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
                    nativeVersion = "1.8.0+android.0.2.0",
                    onSettingsChange = { current = it; saved = it },
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
    fun leanbackOnlyAppsAreIncludedOncePerPackage() {
        val fixturePackage = InstrumentationRegistry.getInstrumentation().context.packageName

        val matches = InstalledApps.launchable(context).filter { it.packageName == fixturePackage }

        assertEquals(1, matches.size)
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
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val target = InstalledApps.launchable(context)
            .first { it.packageName != context.packageName && it.packageName != testPackage }
        setApp(AppSettings(splitTunnel = SplitTunnel(mode = SplitTunnelMode.EXCEPT)))

        compose.onNodeWithTag("split-app-list")
            .performScrollToNode(hasTestTag("split-app-${target.packageName}"))
        compose.onNodeWithTag("split-app-${target.packageName}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick()

        assertTrue(saved?.splitTunnel?.packages?.contains(target.packageName) == true)
        assertFalse(saved?.splitTunnel?.packages?.contains(context.packageName) == true)
    }

    @Test
    fun tvAppRowIsOneRemoteToggleTarget() {
        assumeTrue(TvUiPolicy.isTelevision(context.resources.configuration.uiMode))
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val target = InstalledApps.launchable(context)
            .first { it.packageName != context.packageName && it.packageName != testPackage }
        setApp(AppSettings(splitTunnel = SplitTunnel(mode = SplitTunnelMode.EXCEPT)))

        compose.onNodeWithTag("split-app-list")
            .performScrollToNode(hasTestTag("split-app-${target.packageName}"))
        compose.onNodeWithTag("split-app-${target.packageName}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }

        assertTrue(saved?.splitTunnel?.packages?.contains(target.packageName) == true)
    }

    @Test
    fun dpadFocusScrollsThroughTheLazyAppList() {
        assumeTrue(TvUiPolicy.isTelevision(context.resources.configuration.uiMode))
        val apps = InstalledApps.launchable(context).filterNot { it.packageName == context.packageName }
        val first = apps.first()
        val last = apps.last()
        setApp(AppSettings(splitTunnel = SplitTunnel(mode = SplitTunnelMode.EXCEPT)))

        compose.onNodeWithTag("split-app-list")
            .performScrollToNode(hasTestTag("split-app-${first.packageName}"))
        compose.onNodeWithTag("split-app-${first.packageName}")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it.invoke() }
            .performKeyInput {
                repeat(apps.lastIndex) { pressKey(Key.DirectionDown) }
            }

        compose.onNodeWithTag("split-app-${last.packageName}")
            .assertIsFocused()
            .assertIsDisplayed()
    }
}

class LeanbackFixtureOneActivity : Activity()

class LeanbackFixtureTwoActivity : Activity()
