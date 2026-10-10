package com.whitedns.whiteaesther.data

import com.whitedns.whiteaesther.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TlsOptionsTest {
    private val list = Base64.getEncoder().encodeToString(byteArrayOf(0, 5, -2, 13, 0, 1, 1))

    @Test fun legacySettingsRetainAutomaticDiscovery() {
        assertEquals(TlsOptions(), TlsOptions.decode(null))
        val config = JSONObject().put("fragmentTls", true).put("encryptedHello", true)
        TlsOptions().applyTo(config)
        assertEquals("auto", config.getString("fragmentMode"))
        assertEquals("", config.getString("echConfigList"))
    }

    @Test fun manualValuesSurviveSavingAndRecovery() {
        val options = TlsOptions(TlsFragmentMode.CUSTOM, "20-40", "0-1", false, true, list)
        assertEquals(options, TlsOptions.decode(options.encode()))
        val config = JSONObject().put("fragmentTls", true).put("encryptedHello", true)
        options.applyTo(config)
        val recovered = JSONObject(AutoPlanner.engineConfig(config.toString(), AutoRoute.AETHER_H2_RECORDS, false))
        assertEquals("", recovered.getString("helloShape"))
        assertEquals("20-40", recovered.getString("fragmentSize"))
        assertEquals("0-1", recovered.getString("fragmentDelay"))
        assertFalse(recovered.getBoolean("fragmentSni"))
        assertEquals(list, recovered.getString("echConfigList"))
    }

    @Test fun manualPresetIsNotReplacedByAnAutomaticRung() {
        val config = JSONObject().put("fragmentTls", true)
        TlsOptions(fragmentMode = TlsFragmentMode.COMPACT).applyTo(config)
        val recovered = JSONObject(AutoPlanner.engineConfig(config.toString(), AutoRoute.AETHER_H2_RECORDS, false))
        assertEquals("compact", recovered.getString("helloShape"))
    }

    @Test fun pastedEchWhitespaceAndMissingPaddingAreNormalizedForTheNativeDecoder() {
        val options = TlsOptions(customEch = true, echConfigList = " \n${list.trimEnd('=')}\n ")
        assertNull(options.validationError(false, true))
        val config = JSONObject().put("encryptedHello", true)
        options.applyTo(config)
        assertEquals(list, config.getString("echConfigList"))
    }

    @Test fun invalidOrExcessiveInputsAreRejectedBeforeConnecting() {
        for (value in listOf("", "0", "33-16", "1-999999", "1-2-3", "invalid")) {
            assertEquals(R.string.tls_fragment_size_error, TlsOptions(TlsFragmentMode.CUSTOM, value).validationError(true, false))
        }
        assertEquals(R.string.tls_fragment_delay_error, TlsOptions(TlsFragmentMode.CUSTOM, fragmentDelay = "101").validationError(true, false))
        assertEquals(R.string.tls_ech_config_error, TlsOptions(customEch = true, echConfigList = "AAAA").validationError(false, true))
        assertNull(TlsOptions(customEch = true, echConfigList = list).validationError(false, true))
    }

    @Test fun turningOptionsOffRemovesActiveOverridesAndKeepsSavedText() {
        val options = TlsOptions(TlsFragmentMode.CUSTOM, "bad", "bad", false, true, "bad")
        assertNull(options.validationError(false, false))
        val config = JSONObject().put("fragmentTls", false).put("encryptedHello", false)
        options.applyTo(config)
        assertEquals("", config.getString("fragmentSize"))
        assertEquals("", config.getString("echConfigList"))
        assertEquals("bad", options.fragmentSize)
    }

    @Test fun changingProfilesOrCarriersRequiresSessionReplacement() {
        val previous = AppSettings()
        assertTrue(previous.copy(transport = TunnelProtocol.WIREGUARD_OVER_MASQUE).connectionChoiceChanged(previous))
        assertTrue(previous.copy(secondCarrier = Carrier.PSIPHON).connectionChoiceChanged(previous))
        assertTrue(previous.copy(chain = ChainSettings(enabled = true)).connectionChoiceChanged(previous))
        assertFalse(previous.copy(tlsOptions = TlsOptions(fragmentSize = "2")).connectionChoiceChanged(previous))
        assertFalse(previous.copy(showAdvanced = !previous.showAdvanced).connectionChoiceChanged(previous))
    }
}
