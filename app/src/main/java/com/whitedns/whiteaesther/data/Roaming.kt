package com.whitedns.whiteaesther.data

/**
 * What a change in the network underneath the tunnel means for a search.
 *
 * A rule rather than a reflex, because the callback that feeds it fires for a
 * great deal that is not a move -- signal strength, metering, validation -- and
 * because the two wrong answers cost different things. Ignoring a real move
 * leaves a search planned against a network the phone has left, leading with an
 * endpoint proven somewhere else. Acting on a change that is not a move tears
 * down a tunnel that was working.
 */
enum class RoamAction {
    /** Nothing has moved, or nothing is affected by it. */
    Ignore,

    /**
     * Note the new network, and leave the session alone.
     *
     * A tunnel often survives a roam, and tearing down a working one to re-plan
     * would cost the user the connection they have in order to fix a plan they
     * are not using. What still has to be corrected is the key that what they
     * learn gets recorded against.
     */
    RecordOnly,

    /**
     * Note it, and throw away the plan.
     *
     * A search in progress is being made against a network that is no longer
     * there: its remembered route, its endpoint cache and its choice of which
     * framing to lead with all belong to somewhere else.
     */
    Replan,
}

object Roaming {
    /**
     * @param was the network the current search was planned for
     * @param now the network underneath the tunnel right now
     * @param connected whether a route is carrying the user's traffic
     * @param searching whether a plan is part-way through
     */
    fun actionFor(was: String, now: String, connected: Boolean, searching: Boolean): RoamAction =
        when {
            now.isEmpty() || now == was -> RoamAction.Ignore
            connected -> RoamAction.RecordOnly
            searching -> RoamAction.Replan
            else -> RoamAction.RecordOnly
        }
}
