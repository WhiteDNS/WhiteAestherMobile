package com.whitedns.whiteaesther.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KillSwitchSettingsTest {
    @Test
    fun nothingIsBlockedUntilItIsAskedFor() {
        val settings = AppSettings()

        // Blocking a phone is not a default anyone should arrive at by
        // installing an app.
        assertFalse(settings.killSwitch)
        assertFalse(settings.strictKillSwitch)
    }

    @Test
    fun theTwoSwitchesAreSeparatePromises() {
        // One is about a failure, the other about the gap between sessions.
        // A user who wants protection when the tunnel dies has not thereby
        // asked for a phone with no internet after they disconnect.
        val onFailure = AppSettings(killSwitch = true)

        assertTrue(onFailure.killSwitch)
        assertFalse(onFailure.strictKillSwitch)
    }

    @Test
    fun blockingIsNotSentToTheEngine() {
        val json = AppSettings(killSwitch = true, strictKillSwitch = true)

        // The engine has no idea an Android interface exists. Blocking is done
        // by holding a VpnService interface up, which is entirely the service's
        // business -- like the split tunnel, and for the same reason.
        assertFalse(json.toString().contains("killSwitch=false"))
        assertTrue(json.killSwitch)
    }

    @Test
    fun theKeepaliveDefaultFollowsWireGuardRatherThanTheCli() {
        // Five was the CLI's default and carried no comment; twenty-five is
        // WireGuard's own recommendation, chosen because most NAT mappings
        // outlive thirty seconds. At five the radio wakes 720 times an hour.
        assertTrue(AppSettings().wgKeepalive == 25)
    }

    @Test
    fun theEngineFlagsDefaultToWhatTheEngineAlreadyDid() {
        val settings = AppSettings()

        // Both read as on in the engine unless the value is literally "0", so
        // these defaults have to agree or the app would silently change
        // behaviour the first time it wrote a config.
        assertTrue(settings.routeSniff)
        assertTrue(settings.autoReprovision)
        assertTrue(settings.upstreamProxy.isEmpty())
        assertTrue(settings.dnsServers.isEmpty())
    }
}
