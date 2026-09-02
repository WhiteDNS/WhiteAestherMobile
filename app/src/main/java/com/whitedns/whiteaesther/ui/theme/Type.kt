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
 * The Latin interface face.
 *
 * Two families with different resource ids, chosen explicitly, rather than one
 * name resolved per locale. A locale-qualified res/font-fa looked tidier and
 * was wrong: Compose caches a resolved typeface against the font's resource id
 * for the life of the process, and that cache knows nothing about locale. An
 * app that drew anything in English first had Inter cached under ui_regular,
 * and switching to Persian handed back the same Inter -- which has no Persian
 * in it, so the system substituted something and the text looked exactly as it
 * had before the font was ever added.
 *
 * Different ids cannot collide in that cache, and picking the family is then a
 * decision this code makes rather than one it hopes the resource system will
 * make on its behalf.
 */
val LatinFamily = FontFamily(
    Font(R.font.ui_regular, FontWeight.Normal),
    Font(R.font.ui_medium, FontWeight.Medium),
    Font(R.font.ui_semibold, FontWeight.SemiBold),
    Font(R.font.ui_bold, FontWeight.Bold),
)

/**
 * The Persian interface face: Vazirmatn's UI cut.
 *
 * Deliberately not its Farsi-Digits cut, which draws 0-9 as Persian digits and
 * would undo the choice to keep addresses, ports and measurements in Latin.
 */
val PersianFamily = FontFamily(
    Font(R.font.fa_regular, FontWeight.Normal),
    Font(R.font.fa_medium, FontWeight.Medium),
    Font(R.font.fa_semibold, FontWeight.SemiBold),
    Font(R.font.fa_bold, FontWeight.Bold),
)

/** What the design was drawn in, and what AetherType is declared against. */
val InterFamily = LatinFamily

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

/**
 * Material's own scale, pointed at this design's faces.
 *
 * Lazy, and it has to be. AetherType needs the font families, which live at
 * file scope; this needs AetherType. Two initialisers that need each other
 * work only while something touches the file before the object -- and whichever
 * runs second sees the other half-built, reading its fields as null. That is a
 * NullPointerException inside a static initialiser, which surfaces as
 * ExceptionInInitializerError and takes the app down on its first frame.
 *
 * It survived for as long as it did because MaterialTheme was always the first
 * thing to be touched. Adding a type scale that reads AetherType directly
 * reversed the order and the cycle closed. Deferring this one breaks it
 * outright rather than depending on who asks first.
 */
internal fun aetherTypography(scale: TypeScale, family: FontFamily): Typography =
    Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = scale.PageTitle,
        titleMedium = scale.CardTitle,
        titleSmall = scale.RowTitle,
        bodyLarge = scale.Body.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = scale.Body,
        bodySmall = scale.Small,
        labelLarge = scale.RowTitle.copy(fontSize = 14.sp),
        labelMedium = scale.Small.copy(fontWeight = FontWeight.Medium),
        labelSmall = scale.Label,
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
        fun adjusted(leading: Float, tracking: Float, family: FontFamily): TypeScale {
            if (leading == 1f && tracking == 1f && family == LatinFamily) return Designed
            return TypeScale(
                AetherType.PageTitle.forScript(leading, tracking, family),
                AetherType.StatusHead.forScript(leading, tracking, family),
                AetherType.CardTitle.forScript(leading, tracking, family),
                AetherType.RowTitle.forScript(leading, tracking, family),
                AetherType.Body.forScript(leading, tracking, family),
                AetherType.Small.forScript(leading, tracking, family),
                AetherType.Label.forScript(leading, tracking, family),
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
fun TextStyle.forScript(leading: Float, tracking: Float, family: FontFamily): TextStyle = copy(
    fontFamily = family,
    lineHeight = if (lineHeight.isSpecified) lineHeight * leading else lineHeight,
    letterSpacing = if (letterSpacing.isSpecified) letterSpacing * tracking else letterSpacing,
)

val LocalAetherType = staticCompositionLocalOf { TypeScale.Designed }
