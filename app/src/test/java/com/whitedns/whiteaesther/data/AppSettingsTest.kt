package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaultsToWholeDeviceValidatedDualStack() {
        val settings = AppSettings()

        assertEquals(EngineMode.TUN, settings.mode)
        assertEquals(1819, settings.proxyPort)
        assertEquals(TunnelProtocol.H3, settings.transport)
        assertTrue(settings.dualStack)
        assertTrue(settings.validationEnabled)
        assertEquals(EndpointMode.AUTOMATIC, settings.endpointMode)
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
