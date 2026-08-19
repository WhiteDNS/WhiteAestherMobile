package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointProtocolTest {
    private val pinned = AppSettings(
        endpointMode = EndpointMode.CUSTOM_FIRST,
        customEndpoint = "162.159.197.3:443",
        customEndpointProtocol = TunnelProtocol.H3,
        transport = TunnelProtocol.H3,
    )

    @Test
    fun anAddressPinnedForTheCurrentProtocolIsNotAMismatch() {
        assertNull(pinned.endpointProtocolMismatch())
        assertNull(pinned.copy(transport = TunnelProtocol.H2).endpointProtocolMismatch())
    }

    @Test
    fun wireGuardAndItsNestedFormShareEndpointsToo() {
        // WARP-in-WARP picks its outer hop from the same WireGuard endpoints, so
        // an address found on one fits the other.
        val onWireGuard = pinned.copy(customEndpointProtocol = TunnelProtocol.WIREGUARD)
        assertNull(
            onWireGuard.copy(transport = TunnelProtocol.WARP_IN_WARP).endpointProtocolMismatch(),
        )
    }

    @Test
    fun switchingToADifferentTunnelFlagsThePinnedAddress() {
        // A MASQUE gateway and a WireGuard endpoint are different services on
        // different ports. Carried across a protocol switch, the connect fails
        // with a message about the address -- which reads as a bad address
        // rather than the right one for a protocol no longer selected.
        assertEquals(
            TunnelProtocol.H3,
            pinned.copy(transport = TunnelProtocol.WIREGUARD).endpointProtocolMismatch(),
        )
        assertEquals(
            TunnelProtocol.H3,
            pinned.copy(transport = TunnelProtocol.WARP_IN_WARP).endpointProtocolMismatch(),
        )
    }

    @Test
    fun theTwoMasqueFramingsShareEndpoints() {
        // Same account, same gateways, only the framing differs -- so an address
        // found on one is valid on the other and must not be flagged.
        val onH2 = pinned.copy(customEndpointProtocol = TunnelProtocol.H2)
        assertNull(onH2.copy(transport = TunnelProtocol.H2).endpointProtocolMismatch())
    }

    @Test
    fun automaticDiscoveryIsNeverAMismatch() {
        // Nothing is pinned, so there is nothing to be wrong about.
        assertNull(
            pinned.copy(
                endpointMode = EndpointMode.AUTOMATIC,
                transport = TunnelProtocol.WIREGUARD,
            ).endpointProtocolMismatch(),
        )
    }

    @Test
    fun anAddressFromBeforeThisWasTrackedIsNotFlagged() {
        // Upgrades carry a pinned address with no recorded protocol. Guessing it
        // was MASQUE would be right most of the time and wrong loudly the rest,
        // so it is left alone.
        assertNull(
            pinned.copy(
                customEndpointProtocol = null,
                transport = TunnelProtocol.WIREGUARD,
            ).endpointProtocolMismatch(),
        )
    }
}
