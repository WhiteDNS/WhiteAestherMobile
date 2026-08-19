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
            listOf("h3", "h2", "wg"),
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
        assertTrue(TunnelProtocol.H3.hasSibling)
        assertTrue(TunnelProtocol.H2.hasSibling)
        assertFalse(TunnelProtocol.WIREGUARD.hasSibling)
    }

    @Test
    fun noProfilePresetsWireGuard() {
        // Switching protocol means provisioning a second account and scanning a
        // different set of endpoints. That is worth choosing knowingly, under
        // Manual, rather than something a friendly-sounding preset does for you.
        assertTrue(
            com.whitedns.whiteaesther.ui.ConnectionProfile.entries
                .none { it.transport == TunnelProtocol.WIREGUARD },
        )
    }
}
