package com.whitedns.whiteaesther.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.ThemeMode
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProxyModeNoticeTest {
    @get:Rule val compose = createComposeRule()

    private fun verify(language: String, dark: Boolean, width: Int, fontScale: Float = 1f) {
        val address = if (fontScale > 1f) "[2001:db8:1234:5678::1]:1819" else "127.0.0.1:1819"
        var copies = 0
        compose.setContent {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale(language))
                screenWidthDp = width
            }
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
                LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                WhiteAestherTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Surface {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ProxyModeNotice(address, onCopy = { copies++ }, modifier = Modifier.width(width.dp))
                        }
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("home-proxy-notice")
        val copy = compose.onNodeWithTag("proxy-copy-address")
        val listener = compose.onNodeWithTag("proxy-listener-address")
        card.assertIsDisplayed()
        listener.assertIsDisplayed()
        copy.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val cardBounds = card.fetchSemanticsNode().boundsInRoot
        val addressBounds = listener.fetchSemanticsNode().boundsInRoot
        val copyBounds = copy.fetchSemanticsNode().boundsInRoot
        assertTrue(addressBounds.left >= cardBounds.left && addressBounds.right <= cardBounds.right)
        assertTrue(addressBounds.right <= copyBounds.left)
        assertTrue(copyBounds.bottom <= cardBounds.bottom)
        val bitmap = card.captureToImage().asAndroidBitmap()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "proxy-card-$language-${if (dark) "dark" else "light"}-$width-$fontScale.png"
        File(target.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        copy.performClick()
        compose.runOnIdle { assertEquals(1, copies) }
        copy.assertContentDescriptionEquals(if (language == "fa") "آدرس پروکسی کپی شد" else "Proxy address copied")
    }

    @Test fun englishDarkPhone() = verify("en", true, 375)
    @Test fun persianDarkPhone() = verify("fa", true, 375)
    @Test fun englishLargeTextAndIpv6OnNarrowPhone() = verify("en", false, 320, 1.6f)
    @Test fun persianLargeTextAndIpv6OnNarrowPhone() = verify("fa", false, 320, 1.6f)
    @Test fun wideCard() = verify("en", true, 768)
}
