package com.whitedns.whiteaesther

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * No icon ships its own square.
 *
 * The desktop client shipped a round mark drawn on an opaque black square:
 * the alpha channel was there and every corner was (0, 0, 0, 255). Launchers
 * drew the square, and on a shell that rounds icon corners it read as a black
 * tile with a logo on it. Fourteen PNGs had it.
 *
 * Android's adaptive icon makes that impossible for the launcher icon — the
 * system masks a transparent foreground over a colour — but nothing stopped a
 * raster icon being added later with the backing baked in, and nothing would
 * have noticed. The two clients draw from the same artwork, so the mistake
 * travels.
 *
 * This reads the files rather than trusting the structure, because the
 * structure is not what broke: the artwork was.
 */
class IconBackingTest {
    /**
     * Files that are meant to be an opaque rectangle, and why.
     *
     * An Android TV banner is full-bleed by definition — the launcher draws it
     * as a tile and a transparent one would be the defect. Anything else added
     * here needs the same kind of reason written beside it.
     */
    private val opaqueOnPurpose = mapOf(
        "tv_banner.png" to "an Android TV banner is a full-bleed rectangle by design",
    )

    @Test
    fun noIconIsDrawnOnItsOwnBackingSquare() {
        val res = File("src/main/res")
        assertTrue("expected to run from the app module, not ${File("").absolutePath}", res.isDirectory)

        val offenders = res.walkTopDown()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .filterNot { it.name in opaqueOnPurpose }
            .filter { hasOpaqueDarkCorners(it) }
            .map { it.relativeTo(res).path }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "these carry an opaque dark backing square:\n  " +
                    offenders.joinToString("\n  ") +
                    "\n\nThe mark is round. Flood the background inward from the four corners " +
                    "rather than keying out black by threshold -- the artwork uses black " +
                    "inside itself, in the meridians and the bar behind the wordmark -- and " +
                    "rebuild the anti-aliased band, or the fade to the old backing is left " +
                    "behind as a grey fringe. Check the result over a strong colour: a grey " +
                    "fringe is invisible on grey.\n" +
                    "If one of these is meant to be a rectangle, add it to opaqueOnPurpose " +
                    "with the reason."
            )
        }
    }

    /** The launcher icon's layers stay transparent at the edges, every density. */
    @Test
    fun everyAdaptiveForegroundLeavesItsCornersEmpty() {
        val res = File("src/main/res")
        val foregrounds = res.walkTopDown()
            .filter { it.isFile && it.name == "ic_launcher_foreground.png" }
            .toList()

        assertTrue("no launcher foreground found under $res", foregrounds.isNotEmpty())
        for (file in foregrounds) {
            val image = ImageIO.read(file)
            for ((x, y) in corners(image)) {
                val alpha = image.getRGB(x, y) ushr 24
                assertTrue(
                    "${file.parentFile.name}/${file.name} is opaque at ($x, $y): the system " +
                        "masks this layer, so anything it draws in a corner is a corner the " +
                        "launcher cannot round away",
                    alpha == 0,
                )
            }
        }
    }

    private fun hasOpaqueDarkCorners(file: File): Boolean {
        val image = ImageIO.read(file) ?: return false
        return corners(image).all { (x, y) ->
            val pixel = image.getRGB(x, y)
            val alpha = pixel ushr 24
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            // Near-black rather than black: the artwork's backing was not always
            // exactly (0, 0, 0) once it had been through a resize.
            alpha == 255 && red < 40 && green < 40 && blue < 40
        }
    }

    private fun corners(image: BufferedImage): List<Pair<Int, Int>> =
        listOf(
            0 to 0,
            image.width - 1 to 0,
            0 to image.height - 1,
            image.width - 1 to image.height - 1,
        )
}
