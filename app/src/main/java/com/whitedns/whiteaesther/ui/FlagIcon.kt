package com.whitedns.whiteaesther.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.whitedns.whiteaesther.ui.theme.AetherTheme

/**
 * A country's flag, drawn from the SVG in assets/flags rather than the emoji.
 *
 * Emoji flags are a font feature, so they render only where the system ships
 * the regional-indicator glyphs, look different on every device, and are simply
 * absent on some. A vector we draw ourselves is the same picture everywhere.
 *
 * The decoded bitmap is cached per code and size: the same flag is asked for on
 * every recomposition of the picker, and re-parsing the SVG each time would be
 * the only work in a list that is otherwise just drawing text.
 */
@Composable
fun FlagIcon(
    countryCode: String?,
    modifier: Modifier = Modifier,
    width: Dp = 22.dp,
) {
    val code = countryCode?.trim()?.uppercase()
    // Cloudflare's own answers (XX unknown, T1 Tor) and anything not two letters
    // have no flag and must not draw a stray box.
    if (code == null || code.length != 2 || !code.all { it in 'A'..'Z' }) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val height = width * 0.75f // the bundled flags are drawn on a 4:3 canvas
    val pxW = remember(density, width) { with(density) { width.roundToPx() } }
    val pxH = remember(density, height) { with(density) { height.roundToPx() } }

    val bitmap = remember(code, pxW, pxH) { loadFlag(context, code, pxW, pxH) } ?: return
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .border(1.dp, AetherTheme.colors.line, shape),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    }
}

private val flagCache = LruCache<String, Bitmap>(200)

private fun loadFlag(context: Context, code: String, pxW: Int, pxH: Int): Bitmap? {
    val key = "$code-$pxW-$pxH"
    flagCache.get(key)?.let { return it }
    val decoded = runCatching {
        context.assets.open("flags/${code.lowercase()}.svg").use { input ->
            val svg = SVG.getFromInputStream(input)
            val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
            svg.setDocumentWidth(pxW.toFloat())
            svg.setDocumentHeight(pxH.toFloat())
            svg.renderToCanvas(Canvas(bmp))
            bmp
        }
    }.getOrNull()
    if (decoded != null) flagCache.put(key, decoded)
    return decoded
}
