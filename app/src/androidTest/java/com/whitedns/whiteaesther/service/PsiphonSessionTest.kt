package com.whitedns.whiteaesther.service

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.EngineMode
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import android.util.Log
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Brings the Psiphon carrier up on a device and holds it.
 *
 * Live, and therefore opt-in. It reaches Psiphon's real network, takes as long
 * as that network takes, and cannot run on a machine with no way out -- so it
 * is skipped unless asked for:
 *
 *     -Pandroid.testInstrumentationRunnerArguments.psiphon=1
 *
 * with VPN consent already granted, because nothing here can tap a dialog:
 *
 *     adb shell appops set com.whitedns.whiteaesther ACTIVATE_VPN allow
 *
 * `psiphonHold=<seconds>` keeps the tunnel up afterwards. That is not idle
 * time: the containment check cannot be made from in here, because this process
 * is excluded from the interface on purpose, so proving that traffic actually
 * leaves through Psiphon means asking from another uid -- `adb shell curl`
 * while this holds the tunnel open.
 */
class PsiphonSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val requested: Boolean
        get() = InstrumentationRegistry.getArguments().getString("psiphon") == "1"

    private val holdSeconds: Long
        get() = InstrumentationRegistry.getArguments().getString("psiphonHold")?.toLongOrNull() ?: 0L

    /**
     * A clean status before each test, not merely a stopped service.
     *
     * [EngineStatusStore] is an object in the app's process and these tests run
     * in that process, so a failure recorded by one is still standing when the
     * next begins -- and [awaitStage] returns the moment it sees ERROR. Without
     * this, the second test to run reads the first one's refusal, returns
     * immediately, and reports a failure that belongs to a session that has
     * already ended.
     */
    @Before
    fun clearStatus() {
        AetherVpnService.stop(context)
        // Waited for, not slept through. The stop is asynchronous and it writes
        // a status of its own on the way out; a fixed sleep that guesses short
        // lets that write land after the clear and put the previous test's
        // stage straight back -- which the next awaitStage then returns
        // immediately, reporting a failure that belongs to a session already
        // over.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline &&
            EngineStatusStore.status.value.stage != EngineStage.IDLE
        ) {
            Thread.sleep(500)
        }
        // Psiphon is a separate process and its own teardown is not instant
        // either. Starting a second tunnel while the first is still unwinding
        // is a state tunnel-core refuses rather than queues.
        Thread.sleep(3_000)
        EngineStatusStore.update(EngineStatus())
        EngineLog.clear()
    }

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
    }

    @Test
    fun psiphonCarriesTheInterfaceAndHolds() {
        assumeTrue("no psiphon argument, skipping the live carrier test", requested)

        val settings = AppSettings(mode = EngineMode.TUN, carrier = Carrier.PSIPHON)
        AetherVpnService.start(
            context,
            settings.toNativeJson(context),
            settings.chainForService().encode(),
            carrier = Carrier.PSIPHON,
        )

        // Generous, and deliberately longer than tunnel-core's own establish
        // timeout. Psiphon works by trying a ladder of protocols against a
        // network that may be dropping most of them, and a test that gives up
        // first would report the app broken for doing exactly what it should.
        val stage = awaitStage(EngineStage.CONNECTED, timeoutMs = 180_000)
        assertEquals(
            "psiphon did not carry the interface: ${EngineStatusStore.status.value.message}",
            EngineStage.CONNECTED,
            stage,
        )

        // The carrier is another process, and the log line naming its port is
        // the only evidence from in here that the two ever agreed on one. A
        // session that reports CONNECTED without it is mihomo holding an
        // interface pointed at a listener that was never there.
        assertTrue(
            "nothing from the carrier reached the log",
            EngineLog.entries.value.any { it.tag == "carrier" && it.message.contains("is up on") },
        )
        // Waited for rather than read once. mihomo's log is drained on a timer
        // and the first drain lands seconds after the interface is already
        // carrying traffic -- asserting immediately would be testing the
        // drain interval rather than whether the log arrives at all.
        assertTrue(
            "no log from mihomo reached the app",
            awaitLog("chain", timeoutMs = 20_000),
        )

        if (holdSeconds > 0) {
            // Mirrored to logcat while holding, because the point of holding is
            // to make requests from another uid and watch what mihomo does with
            // them -- and its log lives in this process, where a shell cannot
            // reach it.
            var seen = 0
            val deadline = System.currentTimeMillis() + holdSeconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val entries = EngineLog.entries.value
                entries.drop(seen).forEach { Log.i("carrier-diag", "${it.tag}: ${it.message}") }
                seen = entries.size
                Thread.sleep(2_000)
            }
            assertEquals(
                "the carrier did not stay up",
                EngineStage.CONNECTED,
                EngineStatusStore.status.value.stage,
            )
        }
    }

    @Test
    fun theCarriersOwnProxyActuallyCarriesARequest() {
        assumeTrue("no psiphon argument, skipping the live carrier test", requested)

        val settings = AppSettings(mode = EngineMode.TUN, carrier = Carrier.PSIPHON)
        AetherVpnService.start(
            context,
            settings.toNativeJson(context),
            settings.chainForService().encode(),
            carrier = Carrier.PSIPHON,
        )
        assertEquals(
            "psiphon did not carry the interface: ${EngineStatusStore.status.value.message}",
            EngineStage.CONNECTED,
            awaitStage(EngineStage.CONNECTED, timeoutMs = 180_000),
        )

        // Asked from this process on purpose, which is the only reason this
        // test can exist: our uid is excluded from the interface, so a request
        // from here reaches the listener directly instead of being routed back
        // into the tunnel that is carrying it. It separates the two halves --
        // whether Psiphon can carry a request at all, and whether mihomo hands
        // it one -- which otherwise fail identically from outside.
        val port = carrierPort()
        assertTrue("the carrier never reported a port", port > 0)

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        // https, because this app blocks cleartext by policy -- and a test
        // that had to relax that policy would be testing a build nobody
        // ships.
        val body = URL("https://checkip.amazonaws.com/").openConnection(proxy).run {
            connectTimeout = 30_000
            readTimeout = 30_000
            getInputStream().bufferedReader().use { it.readText() }
        }.trim()

        assertTrue("psiphon returned nothing for a plain request", body.isNotBlank())
        EngineLog.record(LogLevel.INFO, "carrier", "psiphon exits at $body")
    }

    @Test
    fun proxyOnlyCoverageIsRefusedRatherThanQuietlyCarryingNothing() {
        assumeTrue("no psiphon argument, skipping the live carrier test", requested)

        // Psiphon's listener is not the one applications are pointed at, and
        // there is no second one to offer. Starting anyway would leave the user
        // looking at a connected screen with a proxy port that answers nothing.
        val settings = AppSettings(mode = EngineMode.PROXY, carrier = Carrier.PSIPHON)
        AetherVpnService.start(
            context,
            settings.toNativeJson(context),
            ChainSettings().encode(),
            carrier = Carrier.PSIPHON,
        )

        val stage = awaitStage(EngineStage.ERROR, timeoutMs = 30_000)
        assertEquals(EngineStage.ERROR, stage)
        assertTrue(
            "the refusal did not say why: ${EngineStatusStore.status.value.message}",
            EngineStatusStore.status.value.message.contains("proxy", ignoreCase = true),
        )
    }

    /** The port the carrier reported, read back out of the log line naming it. */
    private fun carrierPort(): Int =
        EngineLog.entries.value
            .lastOrNull { it.tag == "carrier" && it.message.contains("is up on 127.0.0.1:") }
            ?.message?.substringAfterLast(':')?.trim()?.toIntOrNull()
            ?: 0

    private fun awaitLog(tag: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (EngineLog.entries.value.any { it.tag == tag }) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun awaitStage(target: EngineStage, timeoutMs: Long): EngineStage {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = EngineStatusStore.status.value.stage
            if (current == target || current == EngineStage.ERROR) return current
            Thread.sleep(500)
        }
        return EngineStatusStore.status.value.stage
    }
}
