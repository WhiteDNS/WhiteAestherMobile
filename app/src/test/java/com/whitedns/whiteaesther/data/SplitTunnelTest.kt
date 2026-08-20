package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelTest {
    private val self = "com.whitedns.whiteaesther"

    @Test
    fun everythingIsCarriedUntilAskedOtherwise() {
        val rules = SplitTunnel()

        assertEquals(SplitTunnelMode.ALL, rules.mode)
        assertTrue(rules.isEffectivelyEverything(self))
        assertNull(rules.validationError(self))
    }

    @Test
    fun thisAppCanNeverRouteThroughItsOwnTunnel() {
        // The loop the rest of the design exists to prevent: the engine's
        // sockets to Cloudflare and mihomo's to its nodes captured by the
        // interface they are building. Filtered in the model so no screen, no
        // stored value and no future caller can reach the builder with it.
        val rules = SplitTunnel(
            mode = SplitTunnelMode.ONLY,
            packages = setOf(self, "org.telegram.messenger"),
        )

        assertEquals(setOf("org.telegram.messenger"), rules.effectivePackages(self))
    }

    @Test
    fun anAllowListOfOnlyThisAppIsRefusedRatherThanCarryingNothing() {
        val rules = SplitTunnel(mode = SplitTunnelMode.ONLY, packages = setOf(self))

        // After filtering there is nothing left, and an empty allow list is a
        // tunnel that carries nothing -- which looks exactly like a connection
        // that failed silently.
        assertTrue(rules.effectivePackages(self).isEmpty())
        assertTrue(rules.validationError(self).orEmpty().contains("at least one"))
    }

    @Test
    fun anEmptyDenyListIsJustEveryApp() {
        val rules = SplitTunnel(mode = SplitTunnelMode.EXCEPT, packages = emptySet())

        // Nothing to exclude means nothing to do, so the interface is built
        // without per-app calls at all rather than with an empty deny list.
        assertTrue(rules.isEffectivelyEverything(self))
        assertNull(rules.validationError(self))
    }

    @Test
    fun aDenyListOfOnlyThisAppChangesNothing() {
        // Excluding ourselves is what the chain already does unconditionally, so
        // asking for it explicitly should not turn into a rule.
        val rules = SplitTunnel(mode = SplitTunnelMode.EXCEPT, packages = setOf(self))

        assertTrue(rules.isEffectivelyEverything(self))
    }

    @Test
    fun anAllowListIsNeverTreatedAsEveryApp() {
        // The asymmetry matters: an empty deny list means "carry everything",
        // an allow list never does, however many entries it has.
        val rules = SplitTunnel(mode = SplitTunnelMode.ONLY, packages = setOf("org.telegram.messenger"))

        assertFalse(rules.isEffectivelyEverything(self))
    }

    @Test
    fun theSummarySaysWhichWayRoundTheRuleGoes() {
        val apps = setOf("a", "b")

        assertEquals("Every app on this phone", SplitTunnel().summary())
        assertEquals(
            "2 apps only",
            SplitTunnel(SplitTunnelMode.ONLY, apps).summary(),
        )
        assertEquals(
            "Every app except 2",
            SplitTunnel(SplitTunnelMode.EXCEPT, apps).summary(),
        )
        assertEquals(
            "1 app only",
            SplitTunnel(SplitTunnelMode.ONLY, setOf("a")).summary(),
        )
    }

    @Test
    fun rulesSurviveTheRoundTripToStorage() {
        val original = SplitTunnel(
            mode = SplitTunnelMode.EXCEPT,
            packages = setOf("com.bank.app", "ir.local.app"),
        )

        assertEquals(original, SplitTunnel.decode(original.encode()))
    }

    @Test
    fun storageThatCannotBeReadCarriesEverything() {
        // Corrupt or written by a different build. Defaulting to every app is
        // the safe direction: the alternative is a tunnel that silently carries
        // less than the user believes.
        assertEquals(SplitTunnel(), SplitTunnel.decode("not json"))
        assertEquals(SplitTunnel(), SplitTunnel.decode(null))
        assertEquals(SplitTunnel(), SplitTunnel.decode(""))
    }

    @Test
    fun anUnknownModeFallsBackToEveryApp() {
        val decoded = SplitTunnel.decode("""{"mode":"SOMETHING_ELSE","packages":["a"]}""")

        assertEquals(SplitTunnelMode.ALL, decoded.mode)
    }
}
