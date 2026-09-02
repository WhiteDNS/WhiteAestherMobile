package com.whitedns.whiteaesther.service

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficFormatTest {
    @Test
    fun smallCountsStayInBytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun theDecimalIsDroppedOnceItStopsSayingAnything() {
        // "9.4 MB" is worth the character. "946.2 MB" is not, and a digit that
        // changes every sample makes a number harder to read, not easier.
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("9.8 KB", formatBytes(10_000))
        assertEquals("98 KB", formatBytes(100_000))
    }

    @Test
    fun itClimbsThroughTheUnits() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("1.0 TB", formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun aRateSaysPerSecondOnce() {
        assertEquals("1.0 MB/s", formatRate(1024L * 1024))
        assertEquals("0 B/s", formatRate(0))
    }

    @Test
    fun aMeterThatCannotMeasureSaysSoRatherThanReadingZero() {
        // TrafficStats is allowed to answer UNSUPPORTED. Reporting that as a
        // tunnel carrying nothing would look like a broken connection.
        val unsupported = TrafficSample(supported = false)

        assertEquals(0L, unsupported.received)
        assertEquals(0L, unsupported.downloadPerSecond)
        assert(!unsupported.supported)
    }

    @Test
    fun measurementsKeepLatinDigitsWhateverTheAppIsSetTo() {
        // Formatted against a Persian default these came out in Arabic-Indic
        // digits, which the bidi algorithm classes as an Arabic number -- and a
        // number of that class beside a Latin unit swaps places with it, so
        // "5.1 MB" appeared on screen as "MB ۵٫۱".
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa"))

            assertEquals("5.1 MB", formatBytes(5_347_737))
            assertEquals("665 KB", formatBytes(681_000))
            assertEquals("0 B/s", formatRate(0))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun theUnitFollowsTheNumber() {
        // The order the reader expects, and the order that survives being
        // pasted into a bug report.
        for (bytes in listOf(0L, 900L, 5_347_737L, 9_000_000_000L)) {
            val formatted = formatBytes(bytes)
            val digitsEnd = formatted.indexOfFirst { it == ' ' }

            assertTrue(formatted, digitsEnd > 0)
            assertTrue(formatted, formatted.take(digitsEnd).all { it.isDigit() || it == '.' })
        }
    }
}
