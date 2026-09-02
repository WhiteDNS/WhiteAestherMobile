package com.whitedns.whiteaesther.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
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
        val adjusted = UNTRACKED.forScript(LEADING, TRACKING)

        assertEquals(20f * LEADING, adjusted.lineHeight.value, 0.01f)
    }

    @Test
    fun aStyleWithoutTrackingKeepsNotHavingAny() {
        val adjusted = UNTRACKED.forScript(LEADING, TRACKING)

        // Not zero, and not an exception: unspecified is a real state meaning
        // "whatever the platform does", and turning it into a number would be a
        // decision nobody made.
        assertTrue(!adjusted.letterSpacing.isSpecified)
    }

    @Test
    fun theLeadingOpensUpAndTheTrackingGoesFlat() {
        val adjusted = TRACKED.forScript(LEADING, TRACKING)

        assertEquals(28f * LEADING, adjusted.lineHeight.value, 0.01f)
        assertEquals(0f, adjusted.letterSpacing.value, 0.01f)
    }

    @Test
    fun theSizeItselfIsNeverTouched() {
        // Density carries the size, so it reaches Material's own scale too
        // rather than only these ten styles. Scaling it here as well would
        // apply it twice.
        assertEquals(24f, TRACKED.forScript(LEADING, TRACKING).fontSize.value, 0.01f)
        assertEquals(14f, UNTRACKED.forScript(LEADING, TRACKING).fontSize.value, 0.01f)
    }

    @Test
    fun aScriptThatNeedsNothingChangesNothing() {
        // The Latin path, and the one every non-Persian install takes.
        assertEquals(TRACKED, TRACKED.forScript(1f, 1f))
        assertEquals(UNTRACKED, UNTRACKED.forScript(1f, 1f))
    }
}
