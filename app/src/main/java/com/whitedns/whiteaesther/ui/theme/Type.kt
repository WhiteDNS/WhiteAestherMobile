package com.whitedns.whiteaesther.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.integerResource
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.whitedns.whiteaesther.R

/**
 * The interface face, whichever script the interface is in.
 *
 * One name, two typefaces: Inter in res/font, Vazirmatn UI in res/font-fa, and
 * the resource system picks between them the same way it picks the strings.
 * Compose resolves a family to one typeface per weight rather than per glyph,
 * so listing both here would have meant one of them winning everywhere --
 * Persian drawn by a face with no Persian in it, which is how "تنظیمات" ended
 * up broken across two lines.
 *
 * Vazirmatn's UI cut, whose vertical metrics are tightened for interface
 * containers, and deliberately not its Farsi-Digits cut: that one draws 0-9 as
 * Persian digits, which would undo the choice to keep addresses, ports and
 * measurements in Latin.
 *
 * IBM Plex Mono stays where it was, reserved for values that have to line up in
 * a column -- addresses, ports, round-trips, log rows. Those are Latin and
 * digits in either language, so it needs no counterpart.
 */
val InterFamily = FontFamily(
    Font(R.font.ui_regular, FontWeight.Normal),
    Font(R.font.ui_medium, FontWeight.Medium),
    Font(R.font.ui_semibold, FontWeight.SemiBold),
    Font(R.font.ui_bold, FontWeight.Bold),
)

val MonoFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

/** Styles the design uses that Material's own scale has no slot for. */
object AetherType {
    val PageTitle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.6).sp,
    )
    val StatusHead = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.7).sp,
    )
    val CardTitle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.2).sp,
    )
    val RowTitle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.15).sp,
    )
    val Body = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    val Small = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Uppercase section label. */
    val Label = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.4.sp,
    )

    /** Values that line up in a column. */
    val Data = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val DataLarge = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    )
    val LogLine = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

internal val AetherTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = InterFamily),
        displayMedium = displayMedium.copy(fontFamily = InterFamily),
        displaySmall = displaySmall.copy(fontFamily = InterFamily),
        headlineLarge = headlineLarge.copy(fontFamily = InterFamily),
        headlineMedium = headlineMedium.copy(fontFamily = InterFamily),
        headlineSmall = headlineSmall.copy(fontFamily = InterFamily),
        titleLarge = AetherType.PageTitle,
        titleMedium = AetherType.CardTitle,
        titleSmall = AetherType.RowTitle,
        bodyLarge = AetherType.Body.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = AetherType.Body,
        bodySmall = AetherType.Small,
        labelLarge = AetherType.RowTitle.copy(fontSize = 14.sp),
        labelMedium = AetherType.Small.copy(fontWeight = FontWeight.Medium),
        labelSmall = AetherType.Label,
    )
}

/**
 * The type scale, adjusted for the script it is being read in.
 *
 * The design was drawn around Inter and English. Persian needs three things
 * from it that a Latin scale does not give:
 *
 * - **Leading.** Persian carries dots below the baseline and loops above it, so
 *   lines set at a Latin ratio crowd into each other. 1.43 is comfortable for
 *   Inter and tight for Vazirmatn.
 * - **No negative tracking.** Closing the space between letters is a Latin
 *   display convention for gaps that open at large sizes; a script whose
 *   letters already join has no such gaps, so it fights the joins instead.
 * - **Not being shrunk.** Which was this project's mistake: the scale was cut
 *   to 0.88 and then 0.94 while Persian was being drawn by a substituted face,
 *   and the shrinking was compensating for that rather than for the script.
 *   Persian is if anything harder to read small than Latin, because what
 *   separates two letters is often one dot.
 *
 * Held in a composition local rather than read at each call site, so the styles
 * stay one set of relationships instead of ten numbers to keep in step.
 */
@Immutable
class TypeScale(
    val PageTitle: TextStyle,
    val StatusHead: TextStyle,
    val CardTitle: TextStyle,
    val RowTitle: TextStyle,
    val Body: TextStyle,
    val Small: TextStyle,
    val Label: TextStyle,
    val Data: TextStyle,
    val DataLarge: TextStyle,
    val LogLine: TextStyle,
) {
    companion object {
        /** The design as drawn, which is what Latin reads in. */
        val Designed = TypeScale(
            AetherType.PageTitle,
            AetherType.StatusHead,
            AetherType.CardTitle,
            AetherType.RowTitle,
            AetherType.Body,
            AetherType.Small,
            AetherType.Label,
            AetherType.Data,
            AetherType.DataLarge,
            AetherType.LogLine,
        )

        /**
         * The same scale with the leading opened up and the tracking released.
         *
         * The mono styles are left alone: Data and LogLine carry addresses,
         * ports and log rows, which are Latin and digits in either language.
         */
        fun adjusted(leading: Float, tracking: Float): TypeScale {
            if (leading == 1f && tracking == 1f) return Designed
            return TypeScale(
                AetherType.PageTitle.forScript(leading, tracking),
                AetherType.StatusHead.forScript(leading, tracking),
                AetherType.CardTitle.forScript(leading, tracking),
                AetherType.RowTitle.forScript(leading, tracking),
                AetherType.Body.forScript(leading, tracking),
                AetherType.Small.forScript(leading, tracking),
                AetherType.Label.forScript(leading, tracking),
                AetherType.Data,
                AetherType.DataLarge,
                AetherType.LogLine,
            )
        }
    }
}

/**
 * One style, opened up for a script that needs more room than Latin.
 *
 * Both units are guarded. A style that never named a letterSpacing carries
 * TextUnit.Unspecified, and multiplying that throws rather than returning
 * itself -- so Body and Small, which set neither, took the whole theme down the
 * first time it was composed, which is the moment the app opens.
 *
 * Its own function rather than a local one, so it can be tested without
 * AetherType, whose initialiser loads fonts and needs a device to do it.
 */
fun TextStyle.forScript(leading: Float, tracking: Float): TextStyle = copy(
    lineHeight = if (lineHeight.isSpecified) lineHeight * leading else lineHeight,
    letterSpacing = if (letterSpacing.isSpecified) letterSpacing * tracking else letterSpacing,
)

val LocalAetherType = staticCompositionLocalOf { TypeScale.Designed }
