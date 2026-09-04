package com.whitedns.whiteaesther.core

import kotlinx.coroutines.flow.StateFlow

/** How far along a carrier is. */
enum class CarrierStage { STOPPED, CONNECTING, CONNECTED, FAILED }

/**
 * What a carrier's process is doing, as seen from this one.
 *
 * [port] can be set before [stage] reaches CONNECTED: a listener exists before
 * the tunnel behind it does, and the two are separate because routing into a
 * listener with nothing behind it drops traffic rather than refusing it.
 */
data class CarrierSnapshot(
    val stage: CarrierStage = CarrierStage.STOPPED,
    val port: Int = 0,
    val failure: String? = null,
)

/**
 * A carrier that is not the engine.
 *
 * Every one of them is the same shape from the service's point of view: it runs
 * somewhere else, it takes a while, and what it finally produces is a SOCKS5
 * port on loopback for mihomo to route the interface into. The differences --
 * which process, which library, whether it can carry a datagram -- belong to the
 * implementations and to [com.whitedns.whiteaesther.data.Carrier], not to the
 * session that uses one.
 */
interface CarrierClient {
    val state: StateFlow<CarrierSnapshot>

    /**
     * Starts the carrier and waits for a usable tunnel.
     *
     * @return the loopback SOCKS5 port, or a failure describing why there is
     *   none. A timeout is a failure rather than a port that might work later:
     *   the caller is about to route an interface into this.
     */
    suspend fun start(timeoutMs: Long): Result<Int>

    fun stop()
}
