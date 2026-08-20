package com.whitedns.whiteaesther.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's own log has to reach the app.
 *
 * Without it a diagnostics report says only that a connect failed, never why --
 * and every problem on hardware the developer does not hold becomes guesswork
 * against an emulator that works. This is the one test that would have caught
 * that gap.
 */
class EngineLogReachesDiagnosticsTest {
    @Test
    fun theEngineLogIsReadableFromKotlin() {
        assertTrue("the engine did not load", NativeAetherBridge.isLoaded)

        // Any call installs the logger and writes at least its own banner.
        NativeAetherBridge.versionOrNull()
        val lines = NativeAetherBridge.drainLog()

        assertTrue(
            "nothing from the engine reached the app; diagnostics would be empty",
            lines.isNotEmpty(),
        )
    }

    @Test
    fun drainingTakesLinesRatherThanRepeatingThem() {
        NativeAetherBridge.versionOrNull()
        NativeAetherBridge.drainLog()

        // Re-reporting the same lines on every pump would fill the report with
        // copies of one connect attempt.
        assertTrue(NativeAetherBridge.drainLog().isEmpty())
    }
}
