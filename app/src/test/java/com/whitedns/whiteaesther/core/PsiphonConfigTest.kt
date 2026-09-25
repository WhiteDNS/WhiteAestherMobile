package com.whitedns.whiteaesther.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tunnel-core's own establish window, which has to fit the wait above it.
 *
 * Shorter than the wait, and tunnel-core gives up partway through a search
 * that is still going and has to be started again from nothing. Longer, and it
 * is cut off without saying why.
 */
class PsiphonConfigTest {
    @Test
    fun aCarrierChosenByHandKeepsItsFiveMinutes() {
        // The manual path waits 330 seconds, and always gave tunnel-core 300.
        assertEquals(300, PsiphonConfig.establishTimeoutSeconds(330_000L))
    }

    @Test
    fun aLongerWaitGetsALongerWindowThatEndsInsideIt() {
        val wait = 15 * 60 * 1_000L
        val window = PsiphonConfig.establishTimeoutSeconds(wait)

        assertTrue("$window s is no longer than before", window > PsiphonConfig.ESTABLISH_TIMEOUT_SECONDS)
        assertTrue("$window s outlasts the wait", window * 1_000L < wait)
    }

    @Test
    fun neverUnderTheWindowItAlwaysHad() {
        assertEquals(
            PsiphonConfig.ESTABLISH_TIMEOUT_SECONDS,
            PsiphonConfig.establishTimeoutSeconds(10_000L),
        )
    }
}
