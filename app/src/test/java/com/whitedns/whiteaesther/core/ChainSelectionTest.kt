package com.whitedns.whiteaesther.core

import org.junit.Assert.*
import org.junit.Test

class ChainSelectionTest {
    private val nodes = ChainNodes(listOf(ChainNode("first", "Socks5", null), ChainNode("second", "Socks5", null)))

    @Test
    fun existingManualDefaultsArePreservedAndAutomaticIsExplicit() {
        assertEquals("first", nodes.restoredSelection(null))
        assertEquals("first", nodes.restoredSelection("removed"))
        assertEquals("second", nodes.restoredSelection("second"))
        assertEquals(ChainConfig.AUTOMATIC_GROUP, nodes.restoredSelection(ChainConfig.AUTOMATIC_GROUP))
        assertNull(ChainNodes().restoredSelection(null))
    }
}
