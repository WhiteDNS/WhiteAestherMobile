package com.whitedns.whiteaesther.service

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * What Automatic tries, and in what order.
 *
 * The bug this exists for was arithmetic, not logic: the first retry repeated
 * the transport that had just failed, so a network blocking UDP spent two full
 * endpoint scans -- about four and a half minutes -- before the framing that
 * works was tried at all. Most people close the app well before that and report
 * it as broken.
 *
 * Reflection, because the ladder lives in the service and the service cannot be
 * constructed in a test. The alternative is duplicating the sequence here, which
 * would pass while the real one drifted.
 */
class AutoTransportTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var service: AetherVpnService

    private fun configFor(attempt: Int, transport: String, scan: String = "balanced"): JSONObject {
        val json = JSONObject()
            .put("transport", transport)
            .put("scanMode", scan)
            .put("mode", "tun")
        val method = AetherVpnService::class.java
            .getDeclaredMethod("configForAttempt", String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        return JSONObject(method.invoke(service, json.toString(), attempt) as String)
    }

    private fun preferences() =
        context.getSharedPreferences("aether_service", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        service = AetherVpnService()
        // The service reads its preferences through the Context it is attached
        // to; in a plain instance that is the test's.
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
        runCatching { attach.invoke(service, context) }
        preferences().edit().remove("last_good_transport").commit()
    }

    @Test
    fun aFailedFramingIsNotRetriedBeforeTheOtherOneIsTried() {
        // The whole defect in one assertion. Retrying h3 after h3 just failed
        // costs another complete scan of a transport the network has already
        // refused.
        assertEquals("h2", configFor(attempt = 1, transport = "h3").getString("transport"))
        assertEquals("h3", configFor(attempt = 1, transport = "h2").getString("transport"))
    }

    @Test
    fun aChosenFramingStillAlternatesRatherThanBeingReplaced() {
        // Someone who picked H3 explicitly gets H3 back on even attempts. The
        // app may try the other one, but it does not quietly change what they
        // asked for.
        assertEquals("h3", configFor(attempt = 2, transport = "h3").getString("transport"))
        assertEquals("h2", configFor(attempt = 3, transport = "h3").getString("transport"))
    }

    @Test
    fun automaticProbesBothFramingsQuicklyBeforeSearchingEitherDeeply() {
        val first = configFor(attempt = 0, transport = "auto")
        val second = configFor(attempt = 1, transport = "auto")

        // A fast failure that moves on beats a thorough one that does not: on a
        // network that blocks UDP, a deep H3 search is minutes spent confirming
        // UDP is blocked.
        assertEquals("h2", first.getString("transport"))
        assertEquals("turbo", first.getString("scanMode"))
        assertEquals("h3", second.getString("transport"))
        assertEquals("turbo", second.getString("scanMode"))

        // H2 first because it is TCP on 443 and looks like ordinary HTTPS, so it
        // is the one more likely to survive a filtered network.
        assertNotEquals(first.getString("transport"), second.getString("transport"))
    }

    @Test
    fun automaticFallsBackToADeepSearchOnceQuickProbesAreExhausted() {
        val third = configFor(attempt = 2, transport = "auto")
        val fourth = configFor(attempt = 3, transport = "auto")

        assertEquals("balanced", third.getString("scanMode"))
        assertEquals("balanced", fourth.getString("scanMode"))
    }

    @Test
    fun automaticStartsFromWhatAlreadyWorkedOnThisNetwork() {
        preferences().edit().putString("last_good_transport", "h3").commit()

        // Climbing the ladder again on every connect would make each one slower
        // than it needs to be, on a network whose answer is already known.
        val first = configFor(attempt = 0, transport = "auto")
        assertEquals("h3", first.getString("transport"))
        assertEquals("balanced", first.getString("scanMode"))
    }

    @Test
    fun automaticNeverHandsTheWordAutoToTheEngine() {
        // The bridge rejects anything that is not a real transport, so this
        // would surface as a connection that fails before it starts.
        for (attempt in 0..8) {
            assertNotEquals(
                "auto",
                configFor(attempt = attempt, transport = "auto").getString("transport"),
            )
        }
    }

    @Test
    fun aNonMasqueProtocolIsNeverSubstituted() {
        // WireGuard is a different tunnel on a different account with a
        // different exit address. Swapping it in on a retry would connect the
        // user to something they did not choose.
        for (attempt in 0..4) {
            assertEquals("wg", configFor(attempt = attempt, transport = "wg").getString("transport"))
        }
    }
}
