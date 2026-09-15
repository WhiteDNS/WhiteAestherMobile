package com.whitedns.whiteaesther.core

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The exit-country cache, which is the only thing the picker has to draw from.
 *
 * On a device rather than the JVM because it is a file in the app's data
 * directory, written by the Psiphon process and read by the screen. Nothing
 * here starts a tunnel: the callback's answer is the input, and what survives
 * on disk is the question.
 */
class PsiphonRegionCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearCache() {
        PsiphonConfig.regionsFile(context).delete()
    }

    @Test
    fun anAnswerIsKeptAndReadBackSorted() {
        PsiphonConfig.rememberRegions(context, listOf("SG", "de", "US"))

        // Sorted and two-letter, because the picker lists them in this order and
        // the settings value is compared against them by equality.
        assertEquals(listOf("SG", "US", "de"), PsiphonConfig.availableRegions(context))
    }

    @Test
    fun anEmptyAnswerDoesNotEraseTheOneBeforeIt() {
        PsiphonConfig.rememberRegions(context, listOf("NL", "US"))
        // tunnel-core can report this mid-session, and it is not news that
        // Psiphon has no countries. Writing it through emptied the picker and
        // took "best available" down with it, so the remedy the error message
        // recommended was the one option the screen no longer offered.
        PsiphonConfig.rememberRegions(context, emptyList())

        assertEquals(listOf("NL", "US"), PsiphonConfig.availableRegions(context))
    }

    @Test
    fun nonsenseIsDroppedRatherThanStored() {
        PsiphonConfig.rememberRegions(context, listOf("US", "", "USA", "f"))

        assertEquals(listOf("US"), PsiphonConfig.availableRegions(context))
    }

    @Test
    fun anAnswerOfOnlyNonsenseLeavesTheListAlone() {
        PsiphonConfig.rememberRegions(context, listOf("CA"))
        PsiphonConfig.rememberRegions(context, listOf("", "nonsense"))

        assertEquals(listOf("CA"), PsiphonConfig.availableRegions(context))
    }

    @Test
    fun nothingIsLeftBehindForTheReaderToFind() {
        PsiphonConfig.rememberRegions(context, listOf("JP"))

        // The write goes through a temporary and is renamed over the real file,
        // so a screen in the other process never reads a half-written list. A
        // staging file still sitting there afterwards would be the rename
        // having silently not happened.
        val staging = PsiphonConfig.regionsFile(context).let {
            java.io.File(it.parentFile, it.name + ".tmp")
        }
        assertTrue(PsiphonConfig.regionsFile(context).exists())
        assertTrue(!staging.exists())
    }

    @Test
    fun anAbsentCacheIsEmptyRatherThanAFailure() {
        assertEquals(emptyList<String>(), PsiphonConfig.availableRegions(context))
    }
}
