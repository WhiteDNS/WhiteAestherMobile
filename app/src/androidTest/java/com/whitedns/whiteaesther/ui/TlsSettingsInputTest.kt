package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Hold storage acknowledgements until editing has finished, as a slow DataStore can. */
class TlsSettingsInputTest {
    @get:Rule val compose = createComposeRule()

    private val stored = mutableStateOf(AppSettings(showAdvanced = true))
    private var pending: AppSettings? = null

    private fun setTraffic(echEnabled: Boolean = false) {
        stored.value = AppSettings(showAdvanced = true, encryptedHello = echEnabled)
        compose.setContent {
            WhiteAestherTheme {
                TrafficScreen(stored.value, EngineStatus(), onSettingsChange = { pending = it })
            }
        }
    }

    private fun checkEditing(tag: String, text: String, read: (AppSettings) -> String) {
        val field = compose.onNodeWithTag(tag)
        field.performScrollTo().performClick()
        var expected = ""
        for (character in text) {
            expected += character
            field.performTextInput(character.toString())
            field.assertTextContains(expected)
            compose.runOnIdle { assertEquals(expected, read(requireNotNull(pending))) }
        }

        // An older save completes while the user is still editing this field.
        val completed = requireNotNull(pending)
        compose.runOnIdle {
            stored.value = when (tag) {
                "tls-ciphers-field" -> completed.copy(tlsOptions = completed.tlsOptions.copy(tlsCiphers = text.take(2)))
                "tls-groups-field" -> completed.copy(tlsGroups = text.take(2))
                "enroll-address-field" -> completed.copy(tlsOptions = completed.tlsOptions.copy(enrollAddress = text.take(2)))
                "ech-dns-field" -> completed.copy(tlsOptions = completed.tlsOptions.copy(echDns = text.take(2)))
                else -> completed.copy(tlsOptions = completed.tlsOptions.copy(echDomain = text.take(2)))
            }
        }
        field.assertTextContains(text)

        compose.runOnIdle { stored.value = completed }
        field.performTextClearance()
        compose.runOnIdle { assertEquals("", read(requireNotNull(pending))) }
        field.performTextInput(text)
        field.assertTextContains(text)
        compose.runOnIdle {
            assertEquals(text, read(requireNotNull(pending)))
            stored.value = requireNotNull(pending)
        }
    }

    @Test fun cipherListCanBeTypedWhileStorageIsPending() {
        setTraffic()
        checkEditing("tls-ciphers-field", "ECDHE-RSA-AES128-GCM-SHA256") { it.tlsOptions.tlsCiphers }
    }

    @Test fun keyGroupsCanBeTypedWhileStorageIsPending() {
        setTraffic()
        checkEditing("tls-groups-field", "X25519:P-256") { it.tlsGroups }
    }

    @Test fun registrationAddressCanBeTypedWhileStorageIsPending() {
        setTraffic()
        checkEditing("enroll-address-field", "203.0.113.10:443") { it.tlsOptions.enrollAddress }
    }

    @Test fun echFieldsCanBeTypedWhileStorageIsPending() {
        setTraffic(echEnabled = true)
        checkEditing("ech-dns-field", "https://1.1.1.1/dns-query") { it.tlsOptions.echDns }
        checkEditing("ech-domain-field", "cloudflare-ech.com") { it.tlsOptions.echDomain }
    }

    @Test fun storedChangesReachFieldsThatAreNotBeingEdited() {
        setTraffic()
        compose.runOnIdle { stored.value = stored.value.copy(tlsGroups = "P-256:X25519") }
        compose.onNodeWithTag("tls-groups-field").performScrollTo().assertTextContains("P-256:X25519")
    }
}
