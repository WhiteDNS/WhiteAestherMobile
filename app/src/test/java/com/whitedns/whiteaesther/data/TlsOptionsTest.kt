package com.whitedns.whiteaesther.data

import com.whitedns.whiteaesther.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TlsOptionsTest {
    @Test fun newApiAndTlsOptionsSurviveStorageAndAutomaticRecipeSelection() {
        val options = TlsOptions(
            enrollAddress = "192.0.2.10:8443", apiHost = "api.example.test",
            apiSni = "api.example.test", masqueSni = "www.example.test",
            tls13Ciphers = "TLS_AES_128_GCM_SHA256", tlsKeyShares = "X25519",
            tlsCertCompression = "brotli", enableEchGrease = true,
        )
        assertEquals(options, TlsOptions.decode(options.encode()))
        val json = JSONObject().put("fragmentTls", true)
        options.applyTo(json)
        val recovered = JSONObject(AutoPlanner.engineConfig(json.toString(), AutoRoute.AETHER_H2_RECORDS, false))
        assertEquals(options.apiHost, recovered.getString("apiHost"))
        assertEquals(options.masqueSni, recovered.getString("masqueSni"))
        assertEquals(options.tls13Ciphers, recovered.getString("tls13Ciphers"))
        assertTrue(recovered.getBoolean("enableEchGrease"))
        assertEquals(R.string.tls_options_error, options.copy(apiHost = "192.0.2.1").validationError(false, false))
    }
    @Test fun newTlsOptionsSurviveStorageAndAutomaticH2Recovery() {
        val options = TlsOptions(echDns = "https://1.1.1.1/dns-query", echDomain = "cloudflare-ech.com",
            tlsCiphers = "ECDHE-RSA-AES128-GCM-SHA256", disableGrease = true, enrollAddress = "188.114.97.6:8443")
        assertEquals(options, TlsOptions.decode(options.encode()))
        val config = JSONObject().put("encryptedHello", true)
        options.applyTo(config)
        val recovered = JSONObject(AutoPlanner.engineConfig(config.toString(), AutoRoute.AETHER_H2_ECH, false))
        assertEquals("h2", recovered.getString("transport"))
        assertEquals(options.echDns, recovered.getString("echDns"))
        assertEquals(options.tlsCiphers, recovered.getString("tlsCiphers"))
        assertTrue(recovered.getBoolean("disableGrease"))
        assertEquals(AutoRoute.AETHER_H2_ECH, AutoRoute.ofEngineConfig(recovered.toString()))
        assertEquals(45_000L, AutoPlanner.budgetMs(AutoRoute.AETHER_H2_ECH))
        val shaped = JSONObject(recovered.toString()).put("helloShape", "records").put("fragmentMode", "records")
        val remembered = AutoRoute.ofEngineConfig(shaped.toString())
        assertEquals(AutoRoute.AETHER_H2_RECORDS, remembered)
        val replayed = JSONObject(AutoPlanner.engineConfig(shaped.toString(), remembered, false))
        assertEquals("records", replayed.getString("helloShape"))
        assertTrue(replayed.getBoolean("encryptedHello"))
    }

    @Test fun echResolverAndDomainAreValidatedOnlyWhenEnabled() {
        val invalid = TlsOptions(echDns = "http://resolver.example", echDomain = "not a domain")
        assertNull(invalid.validationError(false, false))
        assertEquals(R.string.tls_ech_domain_error, invalid.validationError(false, true))
        assertEquals(R.string.tls_ech_dns_error, invalid.copy(echDomain = "").validationError(false, true))
        for (resolver in listOf("udp://1.1.1.1", "tcp://[2606:4700:4700::1111]:53", "https://1.1.1.1/dns-query")) {
            assertNull(TlsOptions(echDns = resolver).validationError(false, true))
        }
    }
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
