package com.whitedns.whiteaesther.core

/**
 * The `torrc` this app writes for tor to read.
 *
 * Short on purpose. tor's defaults are the product of two decades of people
 * attacking it, and every line added here is a way this app can be told apart
 * from every other tor client -- which is the opposite of what tor is for.
 * What is set is what an app embedding tor has to set, and nothing else.
 */
object TorConfig {
    /**
     * Country selection is a preference, never a requirement.
     *
     * `StrictNodes 0` is the difference between "prefer an exit here" and
     * "fail if there is not one here". Several countries have a handful of exit
     * relays and some have none at all, and strict enforcement on those is a
     * tunnel that never builds -- which a user reads as the app being broken
     * rather than as the country being empty.
     */
    fun render(exitCountry: String?): String = buildString {
        // Nothing is served, dialled or published by this client.
        appendLine("SocksPolicy accept 127.0.0.1/8")
        appendLine("SocksPolicy reject *")
        // A client, not a relay and not a directory mirror. Explicit because
        // the defaults are right today and this says they must stay right.
        appendLine("ClientOnly 1")
        appendLine("AvoidDiskWrites 1")
        // The two things this app is not: it never resolves for other apps and
        // never accepts a connection from off the device.
        appendLine("DNSPort 0")
        appendLine("TransPort 0")

        val country = exitCountry?.trim()?.lowercase()?.takeIf { it.length == 2 }
        if (country != null) {
            appendLine("ExitNodes {$country}")
            appendLine("StrictNodes 0")
        }
    }

    /**
     * How long to wait for a circuit before calling it a failure.
     *
     * Longer than Psiphon's, because tor is slower to bootstrap by design: it
     * fetches a consensus, builds a circuit through three relays, and on a
     * filtered network spends much of that discovering which directory
     * authorities it can reach at all.
     */
    const val BOOTSTRAP_TIMEOUT_MS = 240_000L
}
