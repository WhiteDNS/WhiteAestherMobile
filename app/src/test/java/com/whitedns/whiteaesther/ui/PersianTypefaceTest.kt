package com.whitedns.whiteaesther.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * That the Persian interface has a face that can draw it.
 *
 * Inter has no Persian glyphs at all. With it set explicitly, Persian was drawn
 * by whatever the system substituted and measured badly enough that "تنظیمات"
 * broke across two lines -- a connected script split into two fragments that no
 * longer join. The fix is a font, not a size, so what is checked here is that
 * the font is actually in the build.
 *
 * A resource directory rather than a font family with both faces in it: Compose
 * resolves a family to one typeface per weight, not per glyph, so listing Inter
 * and Vazirmatn together would mean one of them winning everywhere.
 */
class PersianTypefaceTest {
    private companion object {
        val LATIN = File("src/main/res/font")
        val PERSIAN = File("src/main/res/font-fa")

        /** Every weight the type scale asks for. */
        val WEIGHTS = listOf("ui_regular", "ui_medium", "ui_semibold", "ui_bold")
    }

    @Test
    fun everyWeightExistsInBothScripts() {
        // A weight present in one and missing in the other does not fail to
        // build: the resource system falls back to the default, and that weight
        // silently reverts to a face with no Persian in it.
        for (weight in WEIGHTS) {
            assertTrue("$weight is missing from font/", File(LATIN, "$weight.ttf").isFile)
            assertTrue("$weight is missing from font-fa/", File(PERSIAN, "$weight.ttf").isFile)
        }
    }

    @Test
    fun theTwoDirectoriesHoldDifferentTypefaces() {
        // The whole point. Identical files would mean the Persian qualifier was
        // resolving to Inter again, which is the state this was meant to leave.
        for (weight in WEIGHTS) {
            val latin = File(LATIN, "$weight.ttf").readBytes()
            val persian = File(PERSIAN, "$weight.ttf").readBytes()

            assertTrue(weight, !latin.contentEquals(persian))
        }
    }

    @Test
    fun theMonoFaceIsNotDuplicatedForPersian() {
        // It is used for addresses, ports, round-trips and log rows, which are
        // Latin and digits in either language. A Persian counterpart would be
        // weight in the APK carrying nothing.
        assertTrue(File(LATIN, "plex_mono_regular.ttf").isFile)
        assertTrue(!File(PERSIAN, "plex_mono_regular.ttf").exists())
    }

    @Test
    fun onlyTheInterfaceFaceIsQualified() {
        // Anything else appearing here would be a font shipped twice.
        val qualified = PERSIAN.listFiles().orEmpty().map { it.name }.sorted()

        assertEquals(WEIGHTS.sorted().map { "$it.ttf" }, qualified)
    }
}
