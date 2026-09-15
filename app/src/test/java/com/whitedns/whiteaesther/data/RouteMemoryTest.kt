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

/**
 * What is remembered about the engine having failed, and for how long.
 *
 * The engine going first is worth two and a half minutes when it works and
 * costs the same when it does not, so the evidence for placing that bet has to
 * be about this network and has to go stale. It used to be a single flag set
 * the first time the engine ever connected anywhere, which never expired.
 */
class EngineFailureMemoryTest {
    private val now = 1_700_000_000_000L
    private val network = "cell:43211"

    @Test
    fun aFreshFailureKeepsTheEngineOutOfTheLead() {
        val stored = RouteMemory.rememberEngineFailure(null, network, now)

        assertTrue(RouteMemory.engineFailedRecently(stored, network, now))
        assertTrue(RouteMemory.engineFailedRecently(stored, network, now + 60_000))
    }

    @Test
    fun theEngineGetsTheLeadBackAfterAWhile() {
        val stored = RouteMemory.rememberEngineFailure(null, network, now)

        // A network that came good the same afternoon has to be able to say so,
        // which is why this expires far sooner than a remembered success.
        assertFalse(
            RouteMemory.engineFailedRecently(
                stored,
                network,
                now + RouteMemory.ENGINE_RETRY_AFTER_MS + 1,
            ),
        )
    }

    @Test
    fun aFailureOnOneNetworkSaysNothingAboutAnother() {
        val stored = RouteMemory.rememberEngineFailure(null, network, now)

        assertFalse(RouteMemory.engineFailedRecently(stored, "wifi:abc123", now))
    }

    @Test
    fun theEngineConnectingHereClearsIt() {
        var stored = RouteMemory.rememberEngineFailure(null, network, now)
        stored = RouteMemory.remember(stored, network, AutoRoute.AETHER_H2_QUICK, now + 1_000)

        // Any Aether route is remembered as the engine, so a win in the race
        // answers the failure that kept it out of the lead.
        assertFalse(RouteMemory.engineFailedRecently(stored, network, now + 1_000))
        assertEquals(AutoRoute.AETHER, RouteMemory.recall(stored, network, now + 1_000))
    }

    @Test
    fun anotherCarrierWinningLeavesTheMarkAlone() {
        var stored = RouteMemory.rememberEngineFailure(null, network, now)
        stored = RouteMemory.remember(stored, network, AutoRoute.PSIPHON, now + 1_000)

        // Psiphon winning says nothing about whether the engine would have.
        assertTrue(RouteMemory.engineFailedRecently(stored, network, now + 1_000))
        assertEquals(AutoRoute.PSIPHON, RouteMemory.recall(stored, network, now + 1_000))
    }

    @Test
    fun aFailureSurvivesBesideARememberedRoute() {
        var stored = RouteMemory.remember(null, network, AutoRoute.PSIPHON, now)
        stored = RouteMemory.rememberEngineFailure(stored, network, now + 1_000)

        assertEquals(AutoRoute.PSIPHON, RouteMemory.recall(stored, network, now + 1_000))
        assertTrue(RouteMemory.engineFailedRecently(stored, network, now + 1_000))
    }

    @Test
    fun aNetworkKnownOnlyForAFailureIsStillAnEntry() {
        val stored = RouteMemory.rememberEngineFailure(null, network, now)

        assertNull(RouteMemory.recall(stored, network, now))
        assertTrue(RouteMemory.engineFailedRecently(stored, network, now))
    }
}

class AutomaticCarrierDefaultTest {
    @Test
    fun aPhoneThatHasNotChosenGetsEveryWayOut() {
        // Opt-in after 1.6.0, where Automatic on by default did worse than
        // 1.5.0. Both reasons are gone: the sequential plan that gave the
        // engine sixty seconds became a race that starts everything at once,
        // and the check deciding which route wins no longer resolves its
        // targets on the phone -- which on a network with a hijacked resolver
        // had it discard carriers that were working.
        //
        // A default of one carrier is a default that hides two, on a phone
        // whose owner has not said which they want.
        val settings = AppSettings()

        assertTrue(settings.automaticCarrier)
    }

    @Test
    fun choosingOneCarrierStillMeansOneCarrier() {
        // Turning Automatic off has to leave the session exactly as 1.5.0 ran
        // it, because that is what the person who turned it off asked for.
        val settings = AppSettings(automaticCarrier = false)

        assertFalse(settings.automaticCarrier)
        assertEquals(listOf(Carrier.AETHER), settings.carrierPath)
    }
}
