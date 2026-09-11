package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlannerTest {
    private val everything = AutoOptions(
        wholeDevice = true,
        chainAvailable = true,
        transportsAvailable = true,
        hasCustomBridges = false,
        engineCanSearchDeeper = true,
    )

    @Test
    fun aNewNetworkTriesAetherThenRacesTheCarriersThenSearchesHarder() {
        val plan = AutoPlanner.plan(null, everything)

        assertEquals(3, plan.size)
        val first = plan[0] as AutoStep.Engine
        assertEquals(AutoPlanner.ENGINE_QUICK_MS, first.budgetMs)
        assertFalse(first.deep)

        val race = plan[1] as AutoStep.Race
        assertEquals(listOf(AutoRoute.PSIPHON), race.lanes[0].routes)
        assertEquals(0L, race.lanes[0].startAfterMs)
        assertEquals(
            listOf(AutoRoute.TOR_SNOWFLAKE, AutoRoute.TOR_OBFS4, AutoRoute.TOR_DIRECT),
            race.lanes[1].routes,
        )
        // Joining Psiphon, not waiting for it: Psiphon alone can take minutes.
        assertEquals(AutoPlanner.SECOND_LANE_AFTER_MS, race.lanes[1].startAfterMs)

        assertTrue((plan[2] as AutoStep.Engine).deep)
    }

    @Test
    fun whereAetherWorkedBeforeItIsGivenLonger() {
        val first = AutoPlanner.plan(AutoRoute.AETHER, everything)[0] as AutoStep.Engine

        assertEquals(AutoPlanner.ENGINE_REMEMBERED_MS, first.budgetMs)
    }

    @Test
    fun whereACarrierWorkedBeforeTheRaceComesFirst() {
        val plan = AutoPlanner.plan(AutoRoute.PSIPHON, everything)

        val race = plan[0] as AutoStep.Race
        assertEquals(listOf(AutoRoute.PSIPHON), race.lanes[0].routes)
        // Aether has most likely failed here already, so it goes after, with
        // a budget large enough for its whole ladder.
        assertEquals(AutoStep.Engine(AutoPlanner.ENGINE_LATE_MS, deep = false), plan[1])
        assertEquals(2, plan.size)
    }

    @Test
    fun aRememberedTorRouteLeadsItsLaneAndPsiphonJoinsLater() {
        val race = AutoPlanner.plan(AutoRoute.TOR_OBFS4, everything)[0] as AutoStep.Race

        assertEquals(
            listOf(AutoRoute.TOR_OBFS4, AutoRoute.TOR_SNOWFLAKE, AutoRoute.TOR_DIRECT),
            race.lanes[0].routes,
        )
        assertEquals(listOf(AutoRoute.PSIPHON), race.lanes[1].routes)
        assertEquals(AutoPlanner.SECOND_LANE_AFTER_MS, race.lanes[1].startAfterMs)
    }

    @Test
    fun bridgesTheUserWasGivenComeBeforeAnyPublicOne() {
        val race = AutoPlanner.plan(null, everything.copy(hasCustomBridges = true))[1] as AutoStep.Race

        assertEquals(AutoRoute.TOR_CUSTOM, race.lanes[1].routes.first())
    }

    @Test
    fun withoutTheTransportsTorCanOnlyGoDirect() {
        val race = AutoPlanner.plan(
            null,
            everything.copy(transportsAvailable = false, hasCustomBridges = true),
        )[1] as AutoStep.Race

        assertEquals(listOf(AutoRoute.TOR_DIRECT), race.lanes[1].routes)
    }

    @Test
    fun proxyOnlyCanOnlyTryTheEngine() {
        val options = everything.copy(wholeDevice = false)

        assertEquals(listOf(AutoRoute.AETHER), AutoPlanner.offeredRoutes(options))
        assertTrue(AutoPlanner.plan(null, options).all { it is AutoStep.Engine })
    }

    @Test
    fun withoutTheChainLibraryOnlyTheEngineCanRun() {
        val options = everything.copy(chainAvailable = false)

        assertTrue(AutoPlanner.plan(AutoRoute.PSIPHON, options).all { it is AutoStep.Engine })
    }

    @Test
    fun aRememberedRouteThatIsNoLongerOfferedIsForgotten() {
        // Bridges since deleted: remembering them would lead with a route that
        // cannot start.
        assertEquals(
            AutoPlanner.plan(null, everything),
            AutoPlanner.plan(AutoRoute.TOR_CUSTOM, everything),
        )
    }

    @Test
    fun theEngineIsNeverRacedAndNoCarrierRunsTwiceAtOnce() {
        everyPlan { plan ->
            plan.filterIsInstance<AutoStep.Race>().forEach { race ->
                val carriers = race.lanes.map { lane -> lane.routes.map { it.carrier }.toSet() }
                assertTrue(carriers.none { Carrier.AETHER in it })
                // One tor and one Psiphon: lanes run side by side, so two lanes
                // sharing a carrier would start it twice.
                assertTrue(carriers.all { it.size == 1 })
                assertEquals(carriers.size, carriers.flatten().toSet().size)
            }
        }
    }

    @Test
    fun twoEngineStepsNeverFollowEachOther() {
        // Back to back, the second engine session can start while the first is
        // still inside a call the leash could not interrupt. The only pair
        // allowed is a quick search then a thorough one, and only where there
        // is no race to put between them.
        everyPlan { plan ->
            plan.zipWithNext()
                .filter { (a, b) -> a is AutoStep.Engine && b is AutoStep.Engine }
                .forEach { (a, b) ->
                    assertFalse((a as AutoStep.Engine).deep)
                    assertTrue((b as AutoStep.Engine).deep)
                    assertTrue(plan.none { it is AutoStep.Race })
                }
        }
    }

    @Test
    fun everyPlanTriesSomethingAndEveryRouteHasABudget() {
        everyPlan { plan -> assertTrue(plan.isNotEmpty()) }
        AutoRoute.entries.forEach { assertTrue(AutoPlanner.budgetMs(it) > 0) }
        // tunnel-core's own window, which users watched Psiphon's app need.
        assertTrue(AutoPlanner.budgetMs(AutoRoute.PSIPHON) >= 300_000L)
    }

    private fun everyPlan(check: (List<AutoStep>) -> Unit) {
        val flags = listOf(true, false)
        for (wholeDevice in flags) for (chain in flags) for (transports in flags)
            for (bridges in flags) for (deeper in flags) {
                val options = AutoOptions(wholeDevice, chain, transports, bridges, deeper)
                (AutoRoute.entries + listOf(null)).forEach { remembered ->
                    check(AutoPlanner.plan(remembered, options))
                }
            }
    }
}
