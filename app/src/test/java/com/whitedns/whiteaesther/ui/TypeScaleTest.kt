package com.whitedns.whiteaesther.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.whitedns.whiteaesther.ui.theme.AetherType
import com.whitedns.whiteaesther.ui.theme.LatinFamily
import com.whitedns.whiteaesther.ui.theme.PersianFamily
import com.whitedns.whiteaesther.ui.theme.TypeScale
import com.whitedns.whiteaesther.ui.theme.forScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the script adjustment survives every style it is handed.
 *
 * It did not. A style that never named a letterSpacing carries
 * TextUnit.Unspecified, and multiplying that throws rather than returning
 * itself -- so Body and Small, which set neither, took the theme down the first
 * time it was composed, which is the moment the app opens.
 *
 * Nothing caught it because nothing ran the adjustment: the tests knew the
 * numbers in the scale and not what was done to them. The styles are rebuilt
 * here rather than read from AetherType, whose initialiser loads fonts and
 * needs a device to do it -- which is also why testing it in place was never
 * going to happen.
 */
class TypeScaleTest {
    private companion object {
        /** What values-fa asks for. */
        const val LEADING = 1.22f
        const val TRACKING = 0f

        /** Shaped like Body and Small: a size and a leading, no tracking. */
        val UNTRACKED = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

        /** Shaped like PageTitle: a display size with Latin tightening. */
        val TRACKED = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.6).sp)
    }

    @Test
    fun aStyleWithoutTrackingSurvivesTheAdjustment() {
        // The crash, in one line: this threw rather than returning a style.
        val adjusted = UNTRACKED.forScript(LEADING, TRACKING, PersianFamily)

        assertEquals(20f * LEADING, adjusted.lineHeight.value, 0.01f)
    }

    @Test
    fun aStyleWithoutTrackingKeepsNotHavingAny() {
        val adjusted = UNTRACKED.forScript(LEADING, TRACKING, PersianFamily)

        // Not zero, and not an exception: unspecified is a real state meaning
        // "whatever the platform does", and turning it into a number would be a
        // decision nobody made.
        assertTrue(!adjusted.letterSpacing.isSpecified)
    }

    @Test
    fun theLeadingOpensUpAndTheTrackingGoesFlat() {
        val adjusted = TRACKED.forScript(LEADING, TRACKING, PersianFamily)

        assertEquals(28f * LEADING, adjusted.lineHeight.value, 0.01f)
        assertEquals(0f, adjusted.letterSpacing.value, 0.01f)
    }

    @Test
    fun theSizeItselfIsNeverTouched() {
        // Density carries the size, so it reaches Material's own scale too
        // rather than only these ten styles. Scaling it here as well would
        // apply it twice.
        assertEquals(24f, TRACKED.forScript(LEADING, TRACKING, PersianFamily).fontSize.value, 0.01f)
        assertEquals(14f, UNTRACKED.forScript(LEADING, TRACKING, PersianFamily).fontSize.value, 0.01f)
    }

    @Test
    fun aScriptThatNeedsNothingChangesNothing() {
        // The Latin path, and the one every non-Persian install takes.
        assertEquals(TRACKED.copy(fontFamily = LatinFamily), TRACKED.forScript(1f, 1f, LatinFamily))
        assertEquals(UNTRACKED.copy(fontFamily = LatinFamily), UNTRACKED.forScript(1f, 1f, LatinFamily))
    }

    @Test
    fun theScaleCanBeTheFirstThingAnythingTouches() {
        // The crash on open, and the one this file explained away once already.
        //
        // AetherType needs the font families, which live at file scope; the file
        // scope needs AetherType for Material's typography. Two initialisers
        // that need each other work only while something touches the file
        // first -- and whichever runs second reads the other half-built, as
        // null. That is a NullPointerException inside a static initialiser.
        //
        // Reaching TypeScale before anything else is exactly the order the theme
        // uses, and exactly the order that used to fail. An earlier version of
        // this test hit it and the failure was read as "fonts need a device".
        // It was not. It was this.
        assertEquals(
            AetherType.Body.lineHeight.value * LEADING,
            TypeScale.adjusted(LEADING, TRACKING, PersianFamily).Body.lineHeight.value,
            0.01f,
        )
    }


    @Test
    fun theTwoScriptsAreDrawnByDifferentResources() {
        // The whole reason the families are separate objects with separate
        // resource ids. Compose caches a resolved typeface against the font's
        // id for the life of the process and that cache knows nothing about
        // locale -- so one name resolved per locale gave back whichever face
        // had been loaded first. An app opened in English kept Inter after the
        // switch, and Persian drawn in a face with no Persian in it falls back
        // to whatever the system substitutes: exactly what it looked like
        // before the font was added at all.
        assertTrue(LatinFamily != PersianFamily)
    }

    @Test
    fun thePersianScaleAsksForThePersianFace() {
        val scale = TypeScale.adjusted(LEADING, TRACKING, PersianFamily)

        assertEquals(PersianFamily, scale.Body.fontFamily)
        assertEquals(PersianFamily, scale.PageTitle.fontFamily)
    }

    @Test
    fun theMonoStylesKeepTheirOwnFace() {
        val scale = TypeScale.adjusted(LEADING, TRACKING, PersianFamily)

        // Addresses, ports and log rows line up in a column because they are
        // set in a monospaced face, and they are Latin in either language.
        assertEquals(AetherType.Data.fontFamily, scale.Data.fontFamily)
        assertEquals(AetherType.LogLine.fontFamily, scale.LogLine.fontFamily)
    }
}
