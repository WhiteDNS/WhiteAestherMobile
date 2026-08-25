package com.whitedns.whiteaesther.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun aLaterReleaseIsOffered() {
        assertTrue(UpdateChecker.isNewer("v1.3.0", "1.2.1"))
        assertTrue(UpdateChecker.isNewer("v1.2.2", "1.2.1"))
        assertTrue(UpdateChecker.isNewer("v2.0.0", "1.9.9"))
    }

    @Test
    fun theSameReleaseIsNotAnUpdate() {
        assertFalse(UpdateChecker.isNewer("v1.2.1", "1.2.1"))
        assertFalse(UpdateChecker.isNewer("v1.2.0", "1.2.1"))
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.2.1"))
    }

    @Test
    fun tenComesAfterNineRatherThanBeforeIt() {
        // As text "1.10.0" sorts below "1.9.0", and the tenth release of a
        // minor line is exactly when that would first be noticed -- by nobody
        // being offered the update.
        assertTrue(UpdateChecker.isNewer("v1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("v1.9.0", "1.10.0"))
    }

    @Test
    fun aDevelopmentBuildIsNotOfferedTheReleaseItAlreadyContains() {
        // Builds off a checkout are named by git describe: the last tag plus
        // the distance from it. Comparing the tail as well would offer 1.2.1
        // to a build that is five commits past it.
        assertFalse(UpdateChecker.isNewer("v1.2.1", "1.2.1-5-g53ea192"))
        assertFalse(UpdateChecker.isNewer("v1.2.1", "1.2.1-preview"))
        assertTrue(UpdateChecker.isNewer("v1.3.0", "1.2.1-5-g53ea192"))
    }

    @Test
    fun anUnreadableVersionOffersNothing() {
        // A checker that guesses when it cannot parse would interrupt sessions
        // to announce updates that may not exist.
        assertFalse(UpdateChecker.isNewer("", "1.2.1"))
        assertFalse(UpdateChecker.isNewer("latest", "1.2.1"))
        assertFalse(UpdateChecker.isNewer("v1.3.0", "unknown"))
    }
}
