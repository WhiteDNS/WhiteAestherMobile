package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaultsToWholeDeviceValidatedDualStack() {
        val settings = AppSettings()

        assertEquals(EngineMode.TUN, settings.mode)
        assertEquals(1819, settings.proxyPort)
        // Automatic, because which framing works depends on the network and
        // the user has no way to know it.
        assertEquals(TunnelProtocol.AUTO, settings.transport)
        assertTrue(settings.dualStack)
        assertTrue(settings.validationEnabled)
        assertEquals(EndpointMode.AUTOMATIC, settings.endpointMode)
    }

    @Test
    fun theProxyIsPrivateUntilSharingIsTurnedOn() {
        val settings = AppSettings()

        assertFalse(settings.lanSharing)
        assertEquals("127.0.0.1:1819", settings.proxyBindLabel())
        assertNull(settings.lanSharingNotice())
    }

    @Test
    fun sharingWithoutAPasswordIsAllowedToStand() {
        val shared = AppSettings(mode = EngineMode.PROXY, lanSharing = true)

        // A caution, not a problem. Reported as a failure it read as a field
        // the user had forgotten to fill, which is how "optional" turned into
        // "the app will not let me continue".
        val notice = shared.lanSharingNotice()!!
        assertEquals(LanNoticeLevel.CAUTION, notice.level)
        assertFalse(shared.lanCredentialsUsable())
        assertTrue(notice.text.contains("without a password"))
    }

    @Test
    fun halfACredentialPairIsNotAWeakerPassword() {
        val half = AppSettings(mode = EngineMode.PROXY, lanSharing = true, lanUsername = "phone")

        // It is no password at all, and the engine refuses the pair outright,
        // so this one really does have to be fixed before connecting.
        val notice = half.lanSharingNotice()!!
        assertEquals(LanNoticeLevel.PROBLEM, notice.level)
        assertFalse(half.lanCredentialsUsable())
        assertTrue(notice.text.contains("both"))
    }

    @Test
    fun sharingIsPointedAtTheOneModeThatHasAListener() {
        val wholeDevice = AppSettings(mode = EngineMode.TUN, lanSharing = true)

        // Whole-device mode is an interface, not a port: there is nothing for
        // another machine to connect to, so the switch would silently do
        // nothing.
        val notice = wholeDevice.lanSharingNotice()!!
        assertEquals(LanNoticeLevel.PROBLEM, notice.level)
        assertTrue(notice.text.contains("Proxy"))
        assertEquals("127.0.0.1:1819", wholeDevice.proxyBindLabel())
    }

    @Test
    fun everyModeHasStableWireName() {
        assertEquals(listOf("tun", "proxy"), EngineMode.entries.map(EngineMode::wireName))
    }

    @Test
    fun endpointParserAcceptsNumericAddressesOnly() {
        assertEquals("162.159.197.3:443", EndpointAddress.normalize(" 162.159.197.3:443 "))
        assertTrue(EndpointAddress.normalize("[2606:4700:102::1]:443")?.endsWith(":443") == true)
        assertNull(EndpointAddress.normalize("consumer-masque.cloudflareclient.com:443"))
        assertNull(EndpointAddress.normalize("162.159.197.3:0"))
        assertNull(EndpointAddress.normalize("2606:4700:102::1:443"))
    }

    @Test
    fun customModeRequiresValidEndpoint() {
        assertEquals(
            "Enter a custom endpoint",
            AppSettings(endpointMode = EndpointMode.CUSTOM_ONLY).endpointValidationError(),
        )
        assertNull(
            AppSettings(
                endpointMode = EndpointMode.CUSTOM_FIRST,
                customEndpoint = "162.159.197.3:443",
            ).endpointValidationError(),
        )
    }
}
