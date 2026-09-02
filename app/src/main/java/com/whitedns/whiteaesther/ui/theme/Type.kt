package com.whitedns.whiteaesther.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.integerResource
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * The same style, with the tracking this script wants.
 *
 * Negative tracking is a Latin display convention: at large sizes the gaps
 * between letterforms open up, and closing them a little is what stops a
 * headline reading as loose. A connected script has no such gaps to close --
 * the letters already join -- so the same adjustment works against the joins
 * instead of the spacing, and squeezes a word into looking like a mistake.
 *
 * Applied to the display sizes only, where the tracking is large enough to see.
 */
@Composable
fun TextStyle.tracked(): TextStyle {
    val percent = integerResource(R.integer.type_tracking_percent)
    return if (percent == 100) this else copy(letterSpacing = letterSpacing * (percent / 100f))
}
