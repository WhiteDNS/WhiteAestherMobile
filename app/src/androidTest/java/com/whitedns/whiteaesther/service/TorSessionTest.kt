package com.whitedns.whiteaesther.service

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.core.MoatClient
import com.whitedns.whiteaesther.data.AddressReporter
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TorBridge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Brings the Tor carrier up on a device.
 *
 * Live, slow and opt-in. Tor bootstraps a consensus and three relays, and behind
 * a bridge it does that through one more hop it is still discovering, so this
 * takes minutes rather than seconds and cannot run anywhere without a way out:
 *
 *     -Pandroid.testInstrumentationRunnerArguments.tor=1
 *
 * and, because nothing here can tap a dialog:
 *
 *     adb shell appops set com.whitedns.whiteaesther ACTIVATE_VPN allow
 *
 * `torBridge=obfs4|snowflake|meek` picks a transport; the default is direct.
 * Direct is the one to run where Tor is not blocked, because it tests the
 * carrier; a bridge mode is the one to run where it is, because that tests
 * whether the transports were built and launched at all.
 */
class TorSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val requested: Boolean
        get() = InstrumentationRegistry.getArguments().getString("tor") == "1"

    private val bridge: TorBridge
        get() = InstrumentationRegistry.getArguments().getString("torBridge")
            ?.let { name -> TorBridge.entries.firstOrNull { it.wireName == name } }
            ?: TorBridge.NONE

    @Before
    fun clearStatus() {
        AetherVpnService.stop(context)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline &&
            EngineStatusStore.status.value.stage != EngineStage.IDLE
        ) {
            Thread.sleep(500)
        }
        // tor holds a process-wide lock while it unwinds, and a second start
        // before it lets go is refused rather than queued.
        Thread.sleep(4_000)
        EngineStatusStore.update(EngineStatus())
        EngineLog.clear()
    }

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
    }

    @Test
    fun torCarriesTheInterfaceAndTheExitIsNotThisPhone() {
        assumeTrue("no tor argument, skipping the live carrier test", requested)

        val settings = AppSettings(
            mode = EngineMode.TUN,
            carrier = Carrier.TOR,
            torBridge = bridge,
        )
        AetherVpnService.start(
            context,
            settings.toNativeJson(context),
            settings.chainForService().encode(),
            carrier = Carrier.TOR,
            torBridge = bridge,
        )

        val stage = awaitStage(EngineStage.CONNECTED, timeoutMs = 420_000)
        assertEquals(
            "tor did not carry the interface on ${bridge.wireName}: " +
                EngineStatusStore.status.value.message,
            EngineStage.CONNECTED,
            stage,
        )

        val port = EngineStatusStore.status.value.carrierSocksPort
        assertTrue("the session did not publish tor's port", (port ?: 0) > 0)

        // The measurement that says the circuit is real rather than merely
        // built: an exit relay answers, and it is not this phone. Asked through
        // tor's own listener because this process is excluded from the
        // interface on purpose.
        val throughTor = runBlocking { AddressReporter.carrierAddress(port!!) }
        val fromThisProcess = runBlocking { AddressReporter.tunnelAddress(ipv4Only = true) }
        assertTrue("nothing came back through tor", !throughTor.isNullOrBlank())
        assertNotEquals("the exit is this phone's own address", fromThisProcess, throughTor)
        Log.i("carrier-diag", "tor(${bridge.wireName}) exits at $throughTor, phone $fromThisProcess")

        assertTrue(
            "no log from mihomo reached the app",
            awaitLog("chain", timeoutMs = 20_000),
        )
    }

    @Test
    fun bridgesCanBeFetchedFromTorAndThenCarryASession() {
        assumeTrue("no tor argument, skipping the live carrier test", requested)

        // What the one-click button does, end to end: ask Tor what it
        // recommends here, take the first recommendation, and run tor behind
        // it. The fetch is the half that is new; the rest is the same path a
        // pasted bridge takes, which is the point of them sharing one field.
        // Asked about a censored country rather than this machine's, which is
        // the whole point of the feature: Tor answers an uncensored country with
        // an empty list, correctly, and that would test nothing. In the app the
        // country comes from the network the phone is actually on.
        val country = InstrumentationRegistry.getArguments().getString("torCountry") ?: "ir"
        val recommendations = runBlocking { MoatClient.recommendations(country) }
            .getOrElse { error ->
                throw AssertionError("could not reach Tor's bridge service: ${error.message}")
            }
        assertTrue("Tor recommended nothing for $country", recommendations.isNotEmpty())

        val chosen = recommendations.first()
        Log.i("carrier-diag", "tor recommends ${chosen.transport} for $country")

        val settings = AppSettings(
            mode = EngineMode.TUN,
            carrier = Carrier.TOR,
            torBridge = TorBridge.CUSTOM,
            torBridges = chosen.lines.joinToString("\n"),
        )
        AetherVpnService.start(
            context,
            settings.toNativeJson(context),
            settings.chainForService().encode(),
            carrier = Carrier.TOR,
            torBridge = TorBridge.CUSTOM,
            torBridges = settings.torBridges,
        )

        val stage = awaitStage(EngineStage.CONNECTED, timeoutMs = 420_000)
        assertEquals(
            "fetched ${chosen.transport} bridges did not carry: " +
                EngineStatusStore.status.value.message,
            EngineStage.CONNECTED,
            stage,
        )

        val port = EngineStatusStore.status.value.carrierSocksPort
        val exit = runBlocking { AddressReporter.carrierAddress(port!!) }
        assertTrue("nothing came back through tor", !exit.isNullOrBlank())
        Log.i("carrier-diag", "tor(fetched ${chosen.transport}) exits at $exit")
    }

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
