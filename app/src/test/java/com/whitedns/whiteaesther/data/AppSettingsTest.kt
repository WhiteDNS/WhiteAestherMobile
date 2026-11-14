package com.whitedns.whiteaesther.data

import com.whitedns.whiteaesther.R
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
        assertEquals(AddressFamily.DUAL, settings.addresses)
        assertTrue(settings.validationEnabled)
        assertEquals(EndpointMode.AUTOMATIC, settings.endpointMode)
    }

    @Test
    fun anOlderSavedAddressChoiceKeepsItsMeaning() {
        assertEquals(AddressFamily.DUAL, AddressFamily.fromStored(null, null))
        assertEquals(AddressFamily.DUAL, AddressFamily.fromStored(null, true))
        assertEquals(AddressFamily.V4, AddressFamily.fromStored(null, false))
        // The new key wins once it is written.
        assertEquals(AddressFamily.V6, AddressFamily.fromStored("v6", true))
        assertEquals(AddressFamily.V4, AddressFamily.fromStored("v4", true))
        assertEquals(AddressFamily.DUAL, AddressFamily.fromStored("nonsense", true))
    }

    @Test
    fun eachAddressChoiceReachesTheEngineAndRestartsAConnection() {
        // The values prober.rs IpScan::parse accepts.
        assertEquals(listOf("both", "v4", "v6"), AddressFamily.entries.map { it.wireName })
        AddressFamily.entries.forEach { assertEquals(it, AddressFamily.fromWire(it.wireName)) }
        val previous = AppSettings()
        assertTrue(previous.copy(addresses = AddressFamily.V6).connectionChoiceChanged(previous))
    }

    @Test
    fun aCustomEndpointMustMatchTheChosenAddressFamily() {
        fun error(family: AddressFamily, endpoint: String) = AppSettings(
            endpointMode = EndpointMode.CUSTOM_ONLY,
            customEndpoint = endpoint,
            addresses = family,
        ).endpointValidationError()
        assertEquals(R.string.endpoint_error_family, error(AddressFamily.V6, "162.159.192.1:443"))
        assertEquals(R.string.endpoint_error_family, error(AddressFamily.V4, "[2606:4700:d0::1]:443"))
        assertNull(error(AddressFamily.V6, "[2606:4700:d0::1]:443"))
        assertNull(error(AddressFamily.V4, "162.159.192.1:443"))
        assertNull(error(AddressFamily.DUAL, "162.159.192.1:443"))
        assertNull(error(AddressFamily.DUAL, "[2606:4700:d0::1]:443"))
    }

    @Test
    fun engineSettingsApplyOnlyWhenTheEngineIsInTheConnection() {
        assertTrue(AppSettings(carrier = Carrier.AETHER, automaticCarrier = false).engineSettingsApply)
        assertTrue(AppSettings(carrier = Carrier.PSIPHON, automaticCarrier = true).engineSettingsApply)
        // Aether behind another carrier still dials with them.
        assertTrue(AppSettings(carrier = Carrier.PSIPHON, secondCarrier = Carrier.AETHER, automaticCarrier = false).engineSettingsApply)
        listOf(Carrier.PSIPHON, Carrier.TOR, Carrier.WORKER, Carrier.PROXY).forEach {
            assertFalse("$it", AppSettings(carrier = it, automaticCarrier = false).engineSettingsApply)
        }
    }

    @Test
    fun aWorkerModeThatCannotOfferEchDoesNotKeepItSwitchedOn() {
        val normal = WorkerOptions(mode = WorkerMode.NORMAL, ech = true)
        assertFalse(normal.withMode(WorkerMode.FRAGMENT).ech)
        assertFalse(normal.withMode(WorkerMode.BROWSER).ech)
        assertTrue(normal.withMode(WorkerMode.NORMAL).ech)
        // Not switched back on by itself on the way back.
        assertFalse(normal.withMode(WorkerMode.FRAGMENT).withMode(WorkerMode.NORMAL).ech)
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
        assertEquals(R.string.lan_no_password, notice.text)
    }

    @Test
    fun halfACredentialPairIsNotAWeakerPassword() {
        val half = AppSettings(mode = EngineMode.PROXY, lanSharing = true, lanUsername = "phone")

        // It is no password at all, and the engine refuses the pair outright,
        // so this one really does have to be fixed before connecting.
        val notice = half.lanSharingNotice()!!
        assertEquals(LanNoticeLevel.PROBLEM, notice.level)
        assertFalse(half.lanCredentialsUsable())
        assertEquals(R.string.lan_half_filled, notice.text)
    }

    @Test
    fun sharingIsPointedAtTheOneModeThatHasAListener() {
        val wholeDevice = AppSettings(mode = EngineMode.TUN, lanSharing = true)

        // Whole-device mode is an interface, not a port: there is nothing for
        // another machine to connect to, so the switch would silently do
        // nothing.
        val notice = wholeDevice.lanSharingNotice()!!
        assertEquals(LanNoticeLevel.PROBLEM, notice.level)
        assertEquals(R.string.lan_no_proxy_to_share, notice.text)
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
            R.string.endpoint_error_empty,
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
