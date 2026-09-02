package com.whitedns.whiteaesther.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the Persian interface has a face that can draw it, and its own name.
 *
 * Inter has no Persian glyphs, so Persian was drawn by whatever the system
 * substituted and measured badly enough that "تنظیمات" broke across two lines.
 *
 * The first attempt shipped Vazirmatn under res/font-fa, letting the resource
 * system choose by locale the way it chooses strings. That looked tidier and
 * was wrong: Compose caches a resolved typeface against the font's resource id
 * for the life of the process, and the cache knows nothing about locale. An app
 * that drew anything in English first had Inter cached under that id, and
 * switching to Persian handed the same Inter back -- so the text looked exactly
 * as it had before the font existed. Only a phone already set to Persian at
 * launch ever saw Vazirmatn, which is why it looked right on a device booted
 * that way and wrong on everybody's.
 *
 * Separate names, chosen in code. These check the files are there and distinct;
 * that the right one is asked for is [TypeScaleTest].
 */
class PersianTypefaceTest {
    private companion object {
        val FONTS = File("src/main/res/font")

        val LATIN = listOf("ui_regular", "ui_medium", "ui_semibold", "ui_bold")
        val PERSIAN = listOf("fa_regular", "fa_medium", "fa_semibold", "fa_bold")
    }

    @Test
    fun everyWeightExistsInBothScripts() {
        // A weight present in one and missing in the other is not a build
        // failure: the family falls back to a neighbouring weight, and that
        // weight quietly reverts to a face with no Persian in it.
        for (weight in LATIN + PERSIAN) {
            assertTrue("$weight.ttf is missing", File(FONTS, "$weight.ttf").isFile)
        }
        assertEquals(LATIN.size, PERSIAN.size)
    }

    @Test
    fun theTwoScriptsAreDifferentTypefaces() {
        // Identical files would mean one of them was copied over the other,
        // which is the state this was meant to leave.
        for ((latin, persian) in LATIN.zip(PERSIAN)) {
            val a = File(FONTS, "$latin.ttf").readBytes()
            val b = File(FONTS, "$persian.ttf").readBytes()

            assertTrue("$latin and $persian are the same file", !a.contentEquals(b))
        }
    }

    @Test
    fun nothingIsShippedTwiceUnderALocaleQualifier() {
        // The arrangement that caused the bug. A qualified directory here means
        // someone has gone back to letting the resource system choose, and the
        // font cache will undo it again.
        val qualified = File("src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("font-") }

        assertEquals(emptyList<File>(), qualified)
    }

    @Test
    fun theMonoFaceHasNoPersianCounterpart() {
        // It is used for addresses, ports, round-trips and log rows, which are
        // Latin and digits in either language. A second copy would be weight in
        // the APK carrying nothing.
        assertTrue(File(FONTS, "plex_mono_regular.ttf").isFile)
        assertTrue(!File(FONTS, "fa_mono_regular.ttf").exists())
    }
}
