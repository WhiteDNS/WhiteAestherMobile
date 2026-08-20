package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelProtocolTest {
    @Test
    fun wireNamesAreWhatTheEngineParses() {
        // The bridge validates against these exact strings, and the engine maps
        // "wg" onto a different tunnel entirely. Renaming one here without the
        // Rust side is a silent downgrade to MASQUE, not a compile error.
        assertEquals(
            listOf("auto", "h3", "h2", "wg", "wiw"),
            TunnelProtocol.entries.map(TunnelProtocol::wireName),
        )
    }

    @Test
    fun onlyTheMasqueFramingsCanSubstituteForEachOther() {
        // H3 and H2 are one protocol over two framings: same account, same
        // endpoints, so a retry may swap them. WireGuard is a different tunnel
        // with its own identity and its own endpoints -- a retry that swapped it
        // in would connect the user to something they did not choose, from a
        // different exit address.
        // Automatic is resolved to a real framing before any of this applies,
        // so it is not itself substitutable.
        assertFalse(TunnelProtocol.AUTO.hasSibling)
        assertTrue(TunnelProtocol.H3.hasSibling)
        assertTrue(TunnelProtocol.H2.hasSibling)
        assertFalse(TunnelProtocol.WIREGUARD.hasSibling)
        assertFalse(TunnelProtocol.WARP_IN_WARP.hasSibling)
    }

    @Test
    fun noProfilePresetsAWarpTunnel() {
        // WireGuard and its nested form each need their own Cloudflare account
        // and their own endpoint search. That is worth choosing knowingly, under
        // Manual, rather than something a friendly-sounding preset does for you.
        // Automatic is fine here: it only ever resolves to a MASQUE framing.
        assertTrue(
            com.whitedns.whiteaesther.ui.ConnectionProfile.entries
                .none { it.transport?.endpointFamily == EndpointFamily.WARP },
        )
    }
}
