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

    /**
     * The lane varies the handshake, not only the framing.
     *
     * Every rung used to differ in framing or search depth. Depth buys a larger
     * share of an address pool, and since the engine started trying the endpoint
     * Cloudflare assigns before searching, that pool is rarely where the answer
     * is. What was never tried was the handshake -- and the settings that change
     * it sat in Advanced waiting for a user to guess, which is the decision
     * Automatic exists to take away.
     */
    @Test
    fun theEngineLaneTriesTacticsAndNotOnlyFramings() {
        val lane = AutoPlanner.aetherLane(everything)

        assertTrue(
            "no rung carries fragmentation: $lane",
            lane.any { it.fragmentTls == true },
        )
        assertTrue(
            "no rung carries ECH: $lane",
            lane.any { it.encryptedHello == true },
        )
        // Plain first: a network that needs nothing should not pay for a tactic.
        assertEquals(null, lane[0].fragmentTls)
        assertEquals(null, lane[0].encryptedHello)
        // And the nested tunnel stays last, being the slowest thing here.
        assertEquals(AutoRoute.AETHER_MIM, lane.last())
    }

    /**
     * A rung that carries no tactic leaves the user's own setting alone.
     *
     * Someone who turned fragmentation on by hand has said something, and a
     * planner that overwrote it on every rung would be answering a question
     * they had already answered.
     */
    @Test
    fun aRungWithoutATacticDoesNotOverrideTheUsersChoice() {
        val lane = AutoPlanner.aetherLane(everything)
        val plain = lane.first()
        assertEquals(null, plain.fragmentTls)
        assertEquals(null, plain.encryptedHello)
    }

    /**
     * A failure that names its own remedy moves that rung to the front.
     *
     * A gateway demanding an Encrypted Client Hello says so -- TLS alert 121,
     * which the engine now names instead of reporting a stop with no reason.
     * Without acting on it, the rung that would have worked sits fourth behind
     * three budgets.
     */
    @Test
    fun aGatewayAskingForEchIsAnsweredWithTheEchRung() {
        val blind = AutoPlanner.aetherLane(everything)
        assertTrue(blind.first() != AutoRoute.AETHER_H3_ECH)

        val told = AutoPlanner.aetherLane(
            everything.copy(
                lastEngineFailure =
                    "ech: the gateway requires an ECH configuration and refused the one sent",
            ),
        )
        assertEquals(AutoRoute.AETHER_H3_ECH, told.first())
        // Reordered, not rewritten: nothing is lost from the lane.
        assertEquals(blind.toSet(), told.toSet())
        assertEquals(blind.size, told.size)
    }

    /**
     * A failure that names nothing changes nothing.
     *
     * Guessing from a message is how a planner acquires rules nobody can
     * predict, so only the failures that name a remedy move anything.
     */
    @Test
    fun anOrdinaryFailureLeavesTheLaneAlone() {
        val plain = AutoPlanner.aetherLane(everything)
        val after = AutoPlanner.aetherLane(
            everything.copy(lastEngineFailure = "prober: no clean endpoint found"),
        )
        assertEquals(plain, after)
    }

    /**
     * Adding tactics did not make the worst case worse.
     *
     * A tactic rung is a different handshake, not a deeper search, so it is
     * priced like a quick one -- and it replaced a second full search rather
     * than being added beside it.
     */
    @Test
    fun theEngineLaneCostsNoMoreThanItUsedTo() {
        val total = AutoPlanner.aetherLane(everything).sumOf { AutoPlanner.budgetMs(it) }
        assertTrue("the lane grew to ${total / 1000}s", total <= 690_000L)
    }

    /**
     * One pass fits inside the ceiling the service holds the search to.
     *
     * The two numbers live apart -- the plan is here, the ceiling is in the
     * service -- and the failure if they drift is silent and bad: a pass cut in
     * half throws away a carrier that was about to connect. Psiphon's own
     * establish window alone is five and a half minutes.
     *
     * Kept as an assertion rather than a shared constant on purpose. The
     * service's ceiling is about a person waiting; this is about what a pass
     * costs. They should agree, and each should be able to say why it is what
     * it is.
     */
    @Test
    fun onePassFitsInsideTheSearchCeiling() {
        val ceiling = 15 * 60 * 1_000L
        for (remembered in listOf(null, AutoRoute.AETHER, AutoRoute.PSIPHON)) {
            val pass = AutoPlanner.longestPassMs(remembered, everything)
            assertTrue(
                "a pass remembering $remembered takes ${pass / 1000}s, " +
                    "which does not fit in ${ceiling / 1000}s",
                pass <= ceiling,
            )
        }
    }

    /**
     * A full pass leaves no room for a second one.
     *
     * The predicate that enforces this asks whether another pass would *finish*
     * inside the ceiling, not whether the ceiling has already been passed --
     * and the difference is the whole point. The longest pass is under the
     * ceiling by itself, so the second question never fires and two full passes
     * run: twenty-seven minutes, which is what this exists to prevent.
     *
     * Stated here as arithmetic rather than by driving the service, because the
     * service needs an Android runtime and this needs only the numbers.
     */
    @Test
    fun aFullPassLeavesNoRoomForASecond() {
        val ceiling = 15 * 60 * 1_000L
        val worst = listOf(null, AutoRoute.AETHER, AutoRoute.PSIPHON)
            .maxOf { AutoPlanner.longestPassMs(it, everything) }

        assertTrue("one pass must fit: ${worst / 1000}s", worst <= ceiling)
        assertTrue(
            "a second full pass fits inside ${ceiling / 1000}s, so the ceiling never bites",
            worst + worst > ceiling,
        )
    }

    /**
     * The rule the service applies, held to directly.
     *
     * Asking whether the ceiling has already been passed looks equivalent and
     * is not. The longest pass fits under the ceiling on its own, so that
     * question never fires and a second full pass runs -- twice what the search
     * was allowed. This is the difference, stated as the rule rather than as
     * arithmetic about it, so a version that asks the wrong question fails
     * here.
     */
    @Test
    fun anotherPassNeedsRoomToFinishNotJustRoomToStart() {
        val ceiling = 15 * 60 * 1_000L
        val pass = AutoPlanner.longestPassMs(null, everything)

        // A pass has just used its whole window. Nothing has "passed the
        // ceiling" -- and there is still no room, which is the point.
        assertTrue(pass < ceiling)
        assertFalse(AutoPlanner.hasRoomForAnotherPass(pass, pass, ceiling))

        // A pass that failed in seconds leaves room, and gets one.
        assertTrue(AutoPlanner.hasRoomForAnotherPass(30_000L, pass, ceiling))

        // Exactly filling it is still room; a millisecond over is not.
        assertTrue(AutoPlanner.hasRoomForAnotherPass(ceiling - pass, pass, ceiling))
        assertFalse(AutoPlanner.hasRoomForAnotherPass(ceiling - pass + 1, pass, ceiling))
    }

    /**
     * A pass that failed quickly does leave room for another.
     *
     * Which is the reason the ceiling is a wall clock rather than a pass count:
     * every route refusing in a few seconds is a different situation from every
     * route using its whole window, and only one of them is worth a retry.
     */
    @Test
    fun aQuickFailureStillEarnsASecondPass() {
        val ceiling = 15 * 60 * 1_000L
        val another = AutoPlanner.longestPassMs(null, everything)
        val spentFailingFast = 30_000L

        assertTrue(
            "a pass that failed in ${spentFailingFast / 1000}s should leave room",
            spentFailingFast + another <= ceiling,
        )
    }

    /**
     * A pass is as long as its slowest lane, not as long as all of them.
     *
     * The lanes run beside each other. Adding them up would price a pass at
     * something nobody ever waits, and a ceiling set from that number would
     * never bite.
     */
    @Test
    fun aPassIsAsLongAsItsSlowestLane() {
        val everythingAddedUp = AutoPlanner.plan(null, everything).sumOf { step ->
            when (step) {
                is AutoStep.Engine -> step.budgetMs
                is AutoStep.Race -> step.lanes.sumOf { lane ->
                    lane.startAfterMs + lane.routes.sumOf { AutoPlanner.budgetMs(it) }
                }
            }
        }
        val slowestLane = AutoPlanner.longestPassMs(null, everything)
        assertTrue(
            "the pass was priced as the sum of its lanes",
            slowestLane < everythingAddedUp,
        )
    }

    /**
     * One rule decides the framing order, and the race obeys it.
     *
     * There were three answers to this and they disagreed: the race preferred
     * H3 on Wi-Fi, the retry ladder always went H2 first, and the scanner did
     * too. Three rules that can drift is worse than any one of them being
     * wrong, because nothing notices — a user gets a different order depending
     * on which part of the app is asking, and the framing that would have
     * connected may be the one their path never reaches.
     */
    @Test
    fun theRaceTakesItsFramingOrderFromTheOneRule() {
        for (mobile in listOf(false, true)) {
            for (proven in listOf(null, "h2", "h3")) {
                val expected = AutoPlanner.framingOrder(proven, mobile)
                val lane = AutoPlanner.aetherLane(
                    everything.copy(provenFraming = proven, onMobileData = mobile),
                )
                val actual = lane.mapNotNull { it.engineTransport }
                    .filter { it == "h2" || it == "h3" }
                    .distinct()
                assertEquals(
                    "proven=$proven mobile=$mobile",
                    expected,
                    actual,
                )
            }
        }
    }

    /**
     * Evidence beats inference, and the evidence is per network.
     *
     * What connected here last goes first. A framing proven on another network
     * is not evidence about this one — treating it as such is how a phone that
     * connected at home opens every session on mobile data with the wrong guess.
     */
    @Test
    fun whatConnectedHereLastLeadsWhateverTheNetworkIs() {
        assertEquals(listOf("h2", "h3"), AutoPlanner.framingOrder("h2", onMobileData = false))
        assertEquals(listOf("h3", "h2"), AutoPlanner.framingOrder("h3", onMobileData = true))
    }

    /**
     * With nothing proven, the kind of network decides.
     *
     * Operators have dropped QUIC for weeks at a time, so H2 leads on mobile
     * data; H3 leads elsewhere, which is what the Wi-Fi in the log that
     * prompted this needed.
     */
    @Test
    fun withNothingProvenTheNetworkDecides() {
        assertEquals(listOf("h2", "h3"), AutoPlanner.framingOrder(null, onMobileData = true))
        assertEquals(listOf("h3", "h2"), AutoPlanner.framingOrder(null, onMobileData = false))
    }

    /** Both framings are always offered, whichever leads. */
    @Test
    fun neitherFramingIsEverDropped() {
        for (mobile in listOf(false, true)) {
            for (proven in listOf(null, "h2", "h3", "wg", "")) {
                assertEquals(
                    "proven=$proven mobile=$mobile",
                    setOf("h2", "h3"),
                    AutoPlanner.framingOrder(proven, mobile).toSet(),
                )
            }
        }
    }

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
        //
        // Both framings plain first, then the tactic each framing has, then one
        // deep search, then the nested tunnel. The second pass at greater depth
        // became a pass at a different handshake: since the engine tries the
        // endpoint Cloudflare assigns before searching, depth is rarely where
        // the answer is and the handshake was never varied at all.
        assertEquals(
            listOf(
                AutoRoute.AETHER_H3_QUICK,
                AutoRoute.AETHER_H2_QUICK,
                AutoRoute.AETHER_H3_ECH,
                AutoRoute.AETHER_H2_FRAGMENT,
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
        assertTrue(AutoPlanner.budgetMs(AutoRoute.AETHER_H2_FULL) >= 150_000L)
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
