package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RoamingTest {
    /**
     * The callback fires for a great deal that is not a move.
     *
     * Signal strength, metering and validation all arrive as capability
     * changes, and restarting a search for one of those would be worse than
     * missing a real move.
     */
    @Test
    fun aChangeThatIsNotAMoveChangesNothing() {
        assertEquals(
            RoamAction.Ignore,
            Roaming.actionFor("wifi:abc", "wifi:abc", connected = false, searching = true),
        )
        assertEquals(
            RoamAction.Ignore,
            Roaming.actionFor("wifi:abc", "", connected = false, searching = true),
        )
    }

    /**
     * A working tunnel is not torn down to fix a plan nobody is using.
     *
     * Tunnels often survive a roam. Losing one on purpose costs the user the
     * connection they have; the key what they learn is recorded against is the
     * part that still has to be right.
     */
    @Test
    fun aCarryingSessionIsLeftAloneAndOnlyTheKeyMoves() {
        assertEquals(
            RoamAction.RecordOnly,
            Roaming.actionFor("wifi:abc", "cell:mci", connected = true, searching = true),
        )
    }

    /**
     * A search in progress belongs to a network that is no longer there.
     *
     * Its remembered route, its endpoint cache and its choice of framing were
     * all decided somewhere else, and it has minutes left to run on them.
     */
    @Test
    fun aSearchInProgressIsReplannedForWhereThePhoneIsNow() {
        assertEquals(
            RoamAction.Replan,
            Roaming.actionFor("wifi:abc", "cell:mci", connected = false, searching = true),
        )
    }

    /** Nothing in flight: there is nothing to replan, but the key still moves. */
    @Test
    fun anIdleServiceJustRecordsWhereItIs() {
        assertEquals(
            RoamAction.RecordOnly,
            Roaming.actionFor("wifi:abc", "cell:mci", connected = false, searching = false),
        )
    }
}
