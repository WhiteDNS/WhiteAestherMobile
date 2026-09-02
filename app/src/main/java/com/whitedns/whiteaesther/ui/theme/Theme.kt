package com.whitedns.whiteaesther.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.integerResource
import com.whitedns.whiteaesther.R
import androidx.compose.ui.res.booleanResource
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.whitedns.whiteaesther.data.ThemeMode

/**
 * Tokens Material's scheme has no slot for: the four connection signal hues, the
 * three border weights, and the ink ramp. Both palettes are authored rather than
 * inverted -- light uses a darker emerald so text keeps its contrast on white.
 */
@Immutable
data class AetherColors(
    val ink0: Color,
    val ink1: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val lineSoft: Color,
    val lineStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val brand: Color,
    val onBrand: Color,
    val signalIdle: Color,
    val signalWorking: Color,
    val signalLive: Color,
    val signalFailed: Color,
    val cyan: Color,
    val track: Color,
    val isDark: Boolean,
)

private val DarkAether = AetherColors(
    ink0 = Color(0xFF050908),
    ink1 = Color(0xFF0A100E),
    ink2 = Color(0xFF101715),
    ink3 = Color(0xFF161F1C),
    line = Color(0xFF3A4C45),
    lineSoft = Color(0xFF2A3833),
    lineStrong = Color(0xFF4C6259),
    text = Color(0xFFEAF2EF),
    text2 = Color(0xFF93A29E),
    text3 = Color(0xFF657570),
    brand = Color(0xFF34D1A6),
    onBrand = Color(0xFF052018),
    signalIdle = Color(0xFF7A8D88),
    signalWorking = Color(0xFFF2B544),
    signalLive = Color(0xFF34D1A6),
    signalFailed = Color(0xFFFF7068),
    cyan = Color(0xFF5EC8E5),
    track = Color(0xFF3A4C45),
    isDark = true,
)

private val LightAether = AetherColors(
    ink0 = Color(0xFFE7EDEB),
    ink1 = Color(0xFFF6FAF8),
    ink2 = Color(0xFFFFFFFF),
    ink3 = Color(0xFFEFF5F2),
    line = Color(0xFFD2DEDA),
    lineSoft = Color(0xFFE5EDEA),
    lineStrong = Color(0xFFB6C7C1),
    text = Color(0xFF0B1513),
    text2 = Color(0xFF4C5E59),
    text3 = Color(0xFF74847E),
    brand = Color(0xFF0B8F6C),
    onBrand = Color(0xFFFFFFFF),
    signalIdle = Color(0xFF7C8D88),
    signalWorking = Color(0xFFA06A08),
    signalLive = Color(0xFF0B8F6C),
    signalFailed = Color(0xFFC2372F),
    cyan = Color(0xFF0F7793),
    track = Color(0xFFD2DEDA),
    isDark = false,
)

private fun AetherColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = brand,
        onPrimary = onBrand,
        primaryContainer = ink3,
        onPrimaryContainer = brand,
        secondary = cyan,
        onSecondary = onBrand,
        background = ink1,
        onBackground = text,
        surface = ink2,
        onSurface = text,
        surfaceVariant = ink3,
        onSurfaceVariant = text2,
        outline = line,
        outlineVariant = lineSoft,
        error = signalFailed,
        onError = onBrand,
        errorContainer = ink3,
        onErrorContainer = signalFailed,
    )
} else {
    lightColorScheme(
        primary = brand,
        onPrimary = onBrand,
        primaryContainer = ink3,
        onPrimaryContainer = brand,
        secondary = cyan,
        onSecondary = onBrand,
        background = ink1,
        onBackground = text,
        surface = ink2,
        onSurface = text,
        surfaceVariant = ink3,
        onSurfaceVariant = text2,
        outline = line,
        outlineVariant = lineSoft,
        error = signalFailed,
        onError = onBrand,
        errorContainer = ink3,
        onErrorContainer = signalFailed,
    )
}

val LocalAetherColors = staticCompositionLocalOf { DarkAether }

/** Shorthand for the extended palette: `AetherTheme.colors.brand`. */
object AetherTheme {
    val colors: AetherColors
        @Composable @ReadOnlyComposable get() = LocalAetherColors.current

    /** The type scale for the script the interface is being read in. */
    val type: TypeScale
        @Composable @ReadOnlyComposable get() = LocalAetherType.current
}

@Composable
fun WhiteAestherTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkAether else LightAether
    // The type scale was drawn around Inter and English. A script whose words
    // run longer holds less of it in the same card, so the scale comes from
    // resources and the qualified value applies wherever one exists. Scaling
    // the density rather than every style reaches all of them at once,
    // including Material's own, and keeps the design as one relationship of
    // sizes instead of ten numbers that have to be adjusted together.
    // Size scales the density, which reaches Material's own scale as well as
    // this one and keeps the design as a single set of relationships. Leading
    // and tracking cannot be expressed that way -- both are ratios the density
    // preserves -- so they are carried by the type scale itself.
    val density = LocalDensity.current
    val sized = Density(
        density.density,
        density.fontScale * (integerResource(R.integer.type_scale_percent) / 100f),
    )
    // Chosen here rather than resolved by a locale-qualified font directory.
    // Compose caches a typeface against its resource id for the life of the
    // process, and that cache does not know about locale: one name resolved per
    // locale handed back whichever face had been loaded first.
    val family = if (booleanResource(R.bool.type_persian_face)) PersianFamily else LatinFamily
    val type = TypeScale.adjusted(
        leading = integerResource(R.integer.type_leading_percent) / 100f,
        tracking = integerResource(R.integer.type_tracking_percent) / 100f,
        family = family,
    )
    val typography = remember(type, family) { aetherTypography(type, family) }
    CompositionLocalProvider(
        LocalAetherColors provides colors,
        LocalAetherType provides type,
        LocalDensity provides sized,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = typography,
            content = content,
        )
    }
}
