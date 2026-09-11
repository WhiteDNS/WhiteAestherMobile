package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMemoryTest {
    @Test
    fun aRouteIsRecalledOnTheNetworkItWorkedOnAndNowhereElse() {
        val stored = RouteMemory.remember(null, "cell:43211", AutoRoute.PSIPHON, 1_000L)

        assertEquals(AutoRoute.PSIPHON, RouteMemory.recall(stored, "cell:43211", 2_000L))
        assertNull(RouteMemory.recall(stored, "cell:43235", 2_000L))
    }

    @Test
    fun theLatestSuccessIsTheOneRemembered() {
        val first = RouteMemory.remember(null, "wifi:a", AutoRoute.PSIPHON, 1_000L)
        val second = RouteMemory.remember(first, "wifi:a", AutoRoute.AETHER, 2_000L)

        assertEquals(AutoRoute.AETHER, RouteMemory.recall(second, "wifi:a", 3_000L))
    }

    @Test
    fun anOldMemoryIsOnlyAGuessAndIsDropped() {
        val stored = RouteMemory.remember(null, "wifi:a", AutoRoute.TOR_SNOWFLAKE, 0L)

        assertEquals(
            AutoRoute.TOR_SNOWFLAKE,
            RouteMemory.recall(stored, "wifi:a", RouteMemory.FORGET_AFTER_MS),
        )
        assertNull(RouteMemory.recall(stored, "wifi:a", RouteMemory.FORGET_AFTER_MS + 1))
    }

    @Test
    fun onlyTheMostRecentNetworksAreKept() {
        var stored: String? = null
        repeat(RouteMemory.MAX_NETWORKS + 8) { index ->
            stored = RouteMemory.remember(stored, "net$index", AutoRoute.AETHER, index.toLong())
        }

        val kept = RouteMemory.decode(stored)
        assertEquals(RouteMemory.MAX_NETWORKS, kept.size)
        assertFalse("net0" in kept)
        assertTrue("net${RouteMemory.MAX_NETWORKS + 7}" in kept)
    }

    @Test
    fun whatCannotBeReadIsNothingRemembered() {
        assertNull(RouteMemory.recall("not json", "x", 1L))
        assertNull(RouteMemory.recall("""{"x":{"route":"carrier-pigeon","at":1}}""", "x", 2L))
        assertTrue(RouteMemory.decode("").isEmpty())
        assertTrue(RouteMemory.decode(null).isEmpty())
    }
}

class NetworkKeyTest {
    @Test
    fun operatorsAreToldApart() {
        assertEquals("cell:43211", NetworkKey.cellular("43211"))
        assertNotEquals(NetworkKey.cellular("43211"), NetworkKey.cellular("43235"))
        // No operator code is still a mobile network, just an unnamed one.
        assertEquals("cell", NetworkKey.cellular(""))
        assertEquals("cell", NetworkKey.cellular(null))
    }

    @Test
    fun aLocalNetworkIsNamedWithoutItsAddresses() {
        val key = NetworkKey.local("wifi", "192.168.1.1", listOf("192.168.1.1"), null)

        assertTrue(key.startsWith("wifi:"))
        assertFalse(key.contains("192.168"))
        assertEquals(key, NetworkKey.local("wifi", "192.168.1.1", listOf("192.168.1.1"), null))
    }

    @Test
    fun theOrderOfResolversDoesNotMakeANewNetwork() {
        assertEquals(
            NetworkKey.local("wifi", "10.0.0.1", listOf("1.1.1.1", "8.8.8.8"), null),
            NetworkKey.local("wifi", "10.0.0.1", listOf("8.8.8.8", "1.1.1.1"), null),
        )
    }

    @Test
    fun aDifferentGatewayIsADifferentNetwork() {
        assertNotEquals(
            NetworkKey.local("wifi", "10.0.0.1", listOf("10.0.0.1"), null),
            NetworkKey.local("wifi", "10.0.1.1", listOf("10.0.0.1"), null),
        )
    }

    @Test
    fun aNetworkThatSaysNothingIsNamedByItsKindAlone() {
        assertEquals("wifi", NetworkKey.local("wifi", null, emptyList(), null))
    }
}

class AutomaticCarrierDefaultTest {
    @Test
    fun automaticIsOffUntilChosen() {
        // Opt-in until it has been confirmed on the filtered networks where
        // 1.6.0, with it on by default, gave up on routes 1.5.0 got through.
        val settings = AppSettings()

        assertFalse(settings.automaticCarrier)
        // Which leaves the session exactly as 1.5.0 ran it: Aether alone.
        assertEquals(listOf(Carrier.AETHER), settings.carrierPath)
    }
}
