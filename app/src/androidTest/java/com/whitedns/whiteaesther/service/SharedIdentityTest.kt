package com.whitedns.whiteaesther.service

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every protocol has to share one Cloudflare device registration.
 *
 * Cloudflare rate-limits registrations per address. A user who tried MASQUE and
 * then WireGuard used to register twice, and after a few reinstalls the address
 * is refused outright -- which on a network where connecting is already hard
 * reads as the app being broken.
 *
 * Deliberately does not wait for a tunnel. Whether an endpoint answers depends
 * on the network; whether a second device is registered does not, and only the
 * second is what this is about. `identity_tests` in the engine covers the same
 * invariant without a device at all; this is the end-to-end confirmation.
 *
 *     -Pandroid.testInstrumentationRunnerArguments.identity=true
 */
class SharedIdentityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files: File get() = context.filesDir
    private val identity: File get() = File(files, "aether.toml")

    private val enabled: Boolean
        get() = InstrumentationRegistry.getArguments().getString("identity") == "true"

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
        Thread.sleep(3_000)
    }

    @Test
    fun switchingProtocolReusesTheSameRegistration() {
        assumeTrue("needs a network and one registration", enabled)

        start(TunnelProtocol.H2)
        awaitIdentity()
        val registered = deviceId()

        // The file MASQUE used to keep to itself. Nothing should create it now.
        assertFalse(
            "MASQUE provisioned into its own file again",
            File(files, "aether-masque.toml").exists(),
        )

        AetherVpnService.stop(context)
        Thread.sleep(5_000)

        start(TunnelProtocol.WIREGUARD)
        // Long enough for a registration to have happened had one been going to.
        Thread.sleep(20_000)

        assertEquals(
            "WireGuard registered a second device instead of reusing the first",
            registered,
            deviceId(),
        )
        assertFalse(
            "a second identity file appeared",
            File(files, "aether-masque.toml").exists(),
        )
    }

    @Test
    fun eachProtocolRemembersItsOwnEndpointSeparately() {
        assumeTrue("needs a network and one registration", enabled)

        start(TunnelProtocol.H2)
        awaitIdentity()

        // Shared identity, separate endpoint memory: a MASQUE gateway and a
        // WireGuard endpoint are different addresses on different ports, so one
        // offered to the other wastes a validation on every connect.
        assertFalse(
            "MASQUE wrote into WireGuard's endpoint memory",
            File(files, "aether-lastconn.toml").exists(),
        )
    }

    private fun start(protocol: TunnelProtocol) {
        val settings = AppSettings(mode = EngineMode.TUN, transport = protocol)
        AetherVpnService.start(context, settings.toNativeJson(context), null)
    }

    /** Waits for provisioning, which is the only part of a connect this needs. */
    private fun awaitIdentity() {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (identity.exists()) return
            if (EngineStatusStore.status.value.stage == EngineStage.ERROR) {
                throw AssertionError(
                    "the engine stopped before an identity existed: " +
                        EngineStatusStore.status.value.message,
                )
            }
            Thread.sleep(500)
        }
        throw AssertionError("no identity was provisioned")
    }

    /** The Cloudflare device this install is registered as. */
    private fun deviceId(): String =
        identity.readLines()
            .firstOrNull { it.trimStart().startsWith("device_id") }
            ?: throw AssertionError("no device_id in ${identity.name}")
}
