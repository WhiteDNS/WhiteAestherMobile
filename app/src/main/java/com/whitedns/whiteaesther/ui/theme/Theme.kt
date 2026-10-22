package com.whitedns.whiteaesther.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.integerResource
import com.whitedns.whiteaesther.R
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
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
    ink0 = Color(0xFF050E09),
    ink1 = Color(0xFF050E09),
    ink2 = Color(0xFF09140F),
    ink3 = Color(0xFF0F1B16),
    line = Color(0xFF273730),
    lineSoft = Color(0xFF1B2A23),
    lineStrong = Color(0xFF3D5147),
    text = Color(0xFFE4EEE9),
    text2 = Color(0xFF9BA8A2),
    text3 = Color(0xFF9BA8A2),
    brand = Color(0xFF3FBE90),
    onBrand = Color(0xFF02130C),
    signalIdle = Color(0xFF89968F),
    signalWorking = Color(0xFFE8AA4E),
    signalLive = Color(0xFF3FBE90),
    signalFailed = Color(0xFFF07F77),
    cyan = Color(0xFF71CDA7),
    track = Color(0xFF273730),
    isDark = true,
)

private val LightAether = AetherColors(
    ink0 = Color(0xFFECF6F1),
    ink1 = Color(0xFFF1F9F5),
    ink2 = Color(0xFFF8FDFB),
    ink3 = Color(0xFFECF6F1),
    line = Color(0xFFB9C8C1),
    lineSoft = Color(0xFFD3E2DB),
    lineStrong = Color(0xFF9CAEA3),
    text = Color(0xFF0A1410),
    text2 = Color(0xFF495650),
    text3 = Color(0xFF495650),
    brand = Color(0xFF007E50),
    onBrand = Color(0xFFF4FAF7),
    signalIdle = Color(0xFF394640),
    signalWorking = Color(0xFF9A6400),
    signalLive = Color(0xFF007E50),
    signalFailed = Color(0xFFCF4040),
    cyan = Color(0xFF007E50),
    track = Color(0xFFB9C8C1),
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()
            activity?.let {
                WindowCompat.getInsetsController(it.window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
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
    val family = PersianFamily
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
