package com.whitedns.whiteaesther.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.IdentityMessage
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The identity backup is the answer to an address Cloudflare has stopped issuing
 * identities to, so it has to be findable before that happens rather than after.
 */
class IdentityBackupTest {
    @get:Rule
    val compose = createComposeRule()

    private var exported = 0
    private var imported = 0

    private fun setApp(message: IdentityMessage? = null) {
        compose.setContent {
            WhiteAestherTheme {
                WhiteAestherApp(
                    settings = AppSettings(),
                    engineStatus = EngineStatus(),
                    endpointScannerState = EndpointScannerState(),
                    nativeVersion = "1.7.0+android.0.2.0",
                    onSettingsChange = {},
                    onConnect = {},
                    onStop = {},
                    onScanEndpoints = {},
                    onTestEndpoint = {},
                    onCancelEndpointScan = {},
                    identityMessage = message,
                    onExportIdentity = { exported++ },
                    onImportIdentity = { imported++ },
                    batteryExempt = true,
                )
            }
        }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithText("Identity & access").performScrollTo().performClick()
    }

    @Test
    fun bothHalvesAreOnTheIdentityScreen() {
        setApp()

        compose.onNodeWithText("Save a backup").performScrollTo().performClick()
        assertEquals(1, exported)

        compose.onNodeWithText("Restore from a backup").performScrollTo().performClick()
        assertEquals(1, imported)
    }

    @Test
    fun theScreenSaysWhyABackupMattersBeforeItIsNeeded() {
        setApp()

        // A user who reads this before reinstalling never hits the problem. One
        // who reads it after has already lost the identity.
        compose.onNodeWithText("Reinstalling throws", substring = true)
            .performScrollTo().assertExists()
    }

    @Test
    fun theScreenSaysTheFileIsAsSensitiveAsAPassword() {
        setApp()

        // It is an unencrypted private key. Saying so is the whole mitigation.
        compose.onNodeWithText("like a password", substring = true)
            .performScrollTo().assertExists()
    }

    @Test
    fun aFailureIsShownWhereTheActionWas() {
        setApp(IdentityMessage("Disconnect before importing an identity", isError = true))

        compose.onNodeWithText("Disconnect before importing an identity")
            .performScrollTo().assertExists()
    }

    @Test
    fun successIsShownTheSameWay() {
        setApp(IdentityMessage("Identity saved. Keep it somewhere safe."))

        compose.onNodeWithText("Identity saved. Keep it somewhere safe.")
            .performScrollTo().assertExists()
    }
}
