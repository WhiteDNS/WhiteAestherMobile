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
    fun aNewPhoneRacesEverythingFromTheTap() {
        val plan = AutoPlanner.plan(null, everything)

        // One race, nothing before it: a cold Psiphon gets its whole window
        // from the moment the user taps, not a minute later.
        assertEquals(1, plan.size)
        val race = plan[0] as AutoStep.Race
        assertTrue(race.lanes[0].routes.all { it.racesEngine })
        assertEquals(0L, race.lanes[0].startAfterMs)
        assertEquals(listOf(AutoRoute.PSIPHON), race.lanes[1].routes)
        assertEquals(0L, race.lanes[1].startAfterMs)
        assertEquals(
            listOf(AutoRoute.TOR_SNOWFLAKE, AutoRoute.TOR_OBFS4, AutoRoute.TOR_DIRECT),
            race.lanes[2].routes,
        )
        assertEquals(AutoPlanner.SECOND_LANE_AFTER_MS, race.lanes[2].startAfterMs)
    }

    @Test
    fun nestedMasqueIsRacedLastRatherThanOnlyOffered() {
        // The network it is for is one where every single-hop lane has already
        // failed, and nobody opens Advanced to find it -- so being in the
        // picker alone would mean the people who need it never reach it. Last,
        // because it is the slowest thing the engine can do.
        listOf(everything, everything.copy(onMobileData = true)).forEach { options ->
            val lane = AutoPlanner.aetherLane(options)

            assertEquals(AutoRoute.AETHER_MIM, lane.last())
            assertEquals(1, lane.count { it == AutoRoute.AETHER_MIM })
        }

        // And it is a route Automatic knows about at all.
        assertTrue(AutoRoute.AETHER_MIM in AutoPlanner.offeredRoutes(everything))
    }

    @Test
    fun nestedMasqueGetsTimeForItsInnerHandshakes() {
        // An outer tunnel plus up to six inner handshakes through it. A budget
        // sized like a quick lane would cut it off mid-search every time.
        assertTrue(
            AutoPlanner.budgetMs(AutoRoute.AETHER_MIM) >=
                AutoPlanner.budgetMs(AutoRoute.AETHER_H3_QUICK) * 2,
        )
    }

    @Test
    fun theEngineStopsLeadingOnANetworkWhereItJustFailed() {
        val knownGood = everything.copy(engineWorkedBefore = true)
        // Connected somewhere once, so without this the engine leads every
        // session on every network for the life of the install.
        assertTrue(AutoPlanner.plan(null, knownGood).first() is AutoStep.Engine)

        val plan = AutoPlanner.plan(null, knownGood.copy(engineFailedHere = true))

        // Straight to the race -- and the engine is still in it, in its own
        // lane, so nothing has been given up except the wait in front.
        assertEquals(1, plan.size)
        val race = plan[0] as AutoStep.Race
        assertTrue(race.lanes[0].routes.all { it.racesEngine })
    }

    @Test
    fun aRememberedAetherAlsoStandsDownAfterAFreshFailure() {
        val options = everything.copy(engineFailedHere = true)

        // Remembered from before is still evidence, but it is older evidence
        // than the failure that just happened on this same network.
        val plan = AutoPlanner.plan(AutoRoute.AETHER, options)

        assertEquals(1, plan.size)
        assertTrue(plan[0] is AutoStep.Race)
    }

    @Test
    fun aetherRacesInBothFramingsQuickFirst() {
        // The log that prompted this: a Wi-Fi network that carried QUIC and
        // not TCP, where 1.6.0 tried only H2 before giving up on Aether.
        assertEquals(
            listOf(
                AutoRoute.AETHER_H3_QUICK,
                AutoRoute.AETHER_H2_QUICK,
                AutoRoute.AETHER_H3_FULL,
                AutoRoute.AETHER_H2_FULL,
                AutoRoute.AETHER_MIM,
            ),
            AutoPlanner.aetherLane(everything),
        )
    }

    @Test
    fun onMobileDataH2GoesFirst() {
        assertEquals(
            AutoRoute.AETHER_H2_QUICK,
            AutoPlanner.aetherLane(everything.copy(onMobileData = true)).first(),
        )
    }

    @Test
    fun theFramingThatConnectedLastGoesFirstWherever() {
        assertEquals(
            AutoRoute.AETHER_H2_QUICK,
            AutoPlanner.aetherLane(everything.copy(provenFraming = "h2")).first(),
        )
        assertEquals(
            AutoRoute.AETHER_H3_QUICK,
            AutoPlanner.aetherLane(everything.copy(provenFraming = "h3", onMobileData = true)).first(),
        )
    }

    @Test
    fun aFixedTransportRacesAsTheUserSetIt() {
        assertEquals(
            listOf(AutoRoute.AETHER_AS_SET),
            AutoPlanner.aetherLane(everything.copy(engineCanSearchDeeper = false)),
        )
    }

    @Test
    fun whereAetherWorkedTheDirectEngineGoesFirstThenTheRace() {
        val plan = AutoPlanner.plan(AutoRoute.AETHER, everything)

        assertEquals(AutoStep.Engine(AutoPlanner.ENGINE_REMEMBERED_MS, deep = false), plan[0])
        assertTrue(plan[1] is AutoStep.Race)
        assertEquals(2, plan.size)
    }

    @Test
    fun anEngineThatHasConnectedOnThisPhoneGoesFirstOnANewNetwork() {
        val options = everything.copy(engineWorkedBefore = true)

        assertTrue(AutoPlanner.plan(null, options)[0] is AutoStep.Engine)
        // What this network is remembered for still decides.
        assertEquals(1, AutoPlanner.plan(AutoRoute.PSIPHON, options).size)
    }

    @Test
    fun anyAetherWinIsRememberedAsTheDirectEngine() {
        AutoRoute.entries.filter { it.racesEngine }.forEach {
            assertEquals(AutoRoute.AETHER, it.remembersAs)
        }
        val stored = RouteMemory.remember(null, "wifi:a", AutoRoute.AETHER_H3_QUICK, 1L)
        assertEquals(AutoRoute.AETHER, RouteMemory.recall(stored, "wifi:a", 2L))
    }

    @Test
    fun aRememberedTorRouteLeadsAndPsiphonJoinsLater() {
        val race = AutoPlanner.plan(AutoRoute.TOR_OBFS4, everything)[0] as AutoStep.Race

        assertEquals(
            listOf(AutoRoute.TOR_OBFS4, AutoRoute.TOR_SNOWFLAKE, AutoRoute.TOR_DIRECT),
            race.lanes[0].routes,
        )
        assertEquals(listOf(AutoRoute.PSIPHON), race.lanes[2].routes)
        assertEquals(AutoPlanner.SECOND_LANE_AFTER_MS, race.lanes[2].startAfterMs)
    }

    @Test
    fun bridgesTheUserWasGivenComeBeforeAnyPublicOne() {
        val race = AutoPlanner.plan(null, everything.copy(hasCustomBridges = true))[0] as AutoStep.Race

        assertEquals(AutoRoute.TOR_CUSTOM, race.lanes[2].routes.first())
    }

    @Test
    fun withoutTheTransportsTorCanOnlyGoDirect() {
        val race = AutoPlanner.plan(
            null,
            everything.copy(transportsAvailable = false, hasCustomBridges = true),
        )[0] as AutoStep.Race

        assertEquals(listOf(AutoRoute.TOR_DIRECT), race.lanes[2].routes)
    }

    @Test
    fun proxyOnlyCanOnlyRunTheEngineDirectly() {
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
    fun theDirectEngineIsNeverRacedAndNoCarrierRunsTwiceAtOnce() {
        everyPlan { plan ->
            plan.filterIsInstance<AutoStep.Race>().forEach { race ->
                assertTrue(race.lanes.none { AutoRoute.AETHER in it.routes })
                val carriers = race.lanes.map { lane -> lane.routes.map { it.carrier }.toSet() }
                // One engine, one tor, one Psiphon: lanes run side by side, so
                // two lanes sharing a carrier would start it twice.
                assertTrue(carriers.all { it.size == 1 })
                assertEquals(carriers.size, carriers.flatten().toSet().size)
            }
        }
    }

    @Test
    fun twoDirectEngineStepsFollowEachOtherOnlyWhereNothingCanRace() {
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
    fun budgetsCoverTheEnginesOwnSearches() {
        // Its own deadlines: 45 s quick, 120 s balanced, before registration
        // and the connect after. 1.6.0 cut a 300 s thorough search off at
        // 180 s, so its last step could never find anything.
        assertTrue(AutoPlanner.budgetMs(AutoRoute.AETHER_H3_QUICK) >= 60_000L)
        assertTrue(AutoPlanner.budgetMs(AutoRoute.AETHER_H3_FULL) >= 150_000L)
        assertTrue(AutoPlanner.ENGINE_REMEMBERED_MS >= 150_000L)
        // tunnel-core's own window, which a cold Psiphon needs.
        assertTrue(AutoPlanner.budgetMs(AutoRoute.PSIPHON) >= 300_000L)
        AutoRoute.entries.forEach { assertTrue(AutoPlanner.budgetMs(it) > 0) }
    }

    @Test
    fun everyPlanTriesSomething() {
        everyPlan { plan -> assertTrue(plan.isNotEmpty()) }
    }

    private fun everyPlan(check: (List<AutoStep>) -> Unit) {
        val flags = listOf(true, false)
        for (wholeDevice in flags) for (chain in flags) for (transports in flags)
            for (bridges in flags) for (deeper in flags) for (worked in flags)
                for (mobile in flags) for (proven in listOf(null, "h2", "h3")) {
                    val options = AutoOptions(wholeDevice, chain, transports, bridges, deeper, worked, proven, mobile)
                    (AutoRoute.entries + listOf(null)).forEach { remembered ->
                        check(AutoPlanner.plan(remembered, options))
                    }
                }
    }
}
