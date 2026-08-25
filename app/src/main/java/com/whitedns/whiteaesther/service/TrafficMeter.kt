package com.whitedns.whiteaesther.service

import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much has gone through the tunnel, and how fast it is going now.
 *
 * Counted from this process's own byte counters rather than from the tun
 * interface. That is deliberate: the engine owns the descriptor and the app
 * never sees a packet, so the only number available here is the encrypted one
 * -- which is also the honest one, because it is what the network carried and
 * what a data plan is billed for. It runs a few percent above the plaintext an
 * app would report, and that difference is the tunnel itself.
 */
data class TrafficSample(
    /** Bytes received since the session started. */
    val received: Long = 0,
    /** Bytes sent since the session started. */
    val sent: Long = 0,
    /** Bytes per second, over the last sampling interval. */
    val downloadPerSecond: Long = 0,
    val uploadPerSecond: Long = 0,
    /**
     * False when the device does not keep per-application counters.
     *
     * Rare but real: [TrafficStats] is allowed to answer UNSUPPORTED, and a
     * meter that silently read zero would look like a tunnel carrying nothing.
     */
    val supported: Boolean = true,
)

object TrafficMeter {
    private val mutableSample = MutableStateFlow(TrafficSample())
    val sample = mutableSample.asStateFlow()

    private var baseReceived = 0L
    private var baseSent = 0L
    private var lastReceived = 0L
    private var lastSent = 0L
    private var lastSampledAt = 0L
    private var smoothedDown = 0.0
    private var smoothedUp = 0.0

    /**
     * Marks where this session's counting starts.
     *
     * The platform counters are cumulative since boot, so every figure shown is
     * a difference from here. Called when a session begins rather than when the
     * screen opens, or the totals would restart each time the user looked.
     */
    @Synchronized
    fun start() {
        val received = readReceived()
        val sent = readSent()
        if (received < 0 || sent < 0) {
            mutableSample.value = TrafficSample(supported = false)
            return
        }
        baseReceived = received
        baseSent = sent
        lastReceived = received
        lastSent = sent
        lastSampledAt = System.nanoTime()
        smoothedDown = 0.0
        smoothedUp = 0.0
        mutableSample.value = TrafficSample()
    }

    /**
     * Takes one reading and works out the rate since the previous one.
     *
     * The elapsed time is measured rather than assumed: the caller's interval
     * is a request, not a promise, and dividing by an interval that did not
     * happen is how a paused app reports an implausible burst on resume.
     */
    @Synchronized
    fun sampleNow() {
        if (!mutableSample.value.supported) return
        val received = readReceived()
        val sent = readSent()
        if (received < 0 || sent < 0) {
            mutableSample.value = TrafficSample(supported = false)
            return
        }

        val now = System.nanoTime()
        val elapsedNanos = now - lastSampledAt
        val perSecond = { delta: Long ->
            if (elapsedNanos <= 0) 0L else delta * 1_000_000_000L / elapsedNanos
        }

        // Counters only climb, but a reset elsewhere would make a delta
        // negative, and a negative speed is worse than a missed sample.
        val rawDown = perSecond((received - lastReceived).coerceAtLeast(0))
        val rawUp = perSecond((sent - lastSent).coerceAtLeast(0))

        // Smoothed, because the platform counters do not update on a schedule.
        // Read once a second they arrive in bursts -- nothing, nothing, then a
        // spike -- and a figure that swings between zero and triple the real
        // rate is unreadable however accurate each individual sample is.
        smoothedDown = smooth(smoothedDown, rawDown)
        smoothedUp = smooth(smoothedUp, rawUp)

        mutableSample.value = TrafficSample(
            received = (received - baseReceived).coerceAtLeast(0),
            sent = (sent - baseSent).coerceAtLeast(0),
            downloadPerSecond = smoothedDown.toLong(),
            uploadPerSecond = smoothedUp.toLong(),
        )
        lastReceived = received
        lastSent = sent
        lastSampledAt = now
    }

    /**
     * Weighted towards the new reading, but not entirely.
     *
     * Enough to follow a download starting or stopping within a couple of
     * seconds, enough to stop the number flickering between bursts.
     */
    private fun smooth(previous: Double, sample: Long): Double =
        if (previous <= 0.0) sample.toDouble() else previous * 0.4 + sample * 0.6

    /**
     * Freezes the totals and drops the rates to zero.
     *
     * The session figures stay readable after disconnecting -- a user who wants
     * to know what a session cost asks after it ended, not during.
     */
    @Synchronized
    fun stop() {
        smoothedDown = 0.0
        smoothedUp = 0.0
        mutableSample.value = mutableSample.value.copy(
            downloadPerSecond = 0,
            uploadPerSecond = 0,
        )
    }

    @Synchronized
    fun reset() {
        mutableSample.value = TrafficSample()
    }

    private fun readReceived(): Long = TrafficStats.getUidRxBytes(Process.myUid())

    private fun readSent(): Long = TrafficStats.getUidTxBytes(Process.myUid())
}

/** Formats a byte count the way a person reads one. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    // One decimal below ten, none above: "9.4 MB" is worth the character,
    // "946.2 MB" is not.
    return if (value < 10) "%.1f %s".format(value, units[unit])
    else "%.0f %s".format(value, units[unit])
}

/** Formats a rate. Always per second, so the unit says so once. */
fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"
