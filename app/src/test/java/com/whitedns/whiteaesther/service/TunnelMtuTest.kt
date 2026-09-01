package com.whitedns.whiteaesther.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the interface may advertise, given the tunnel underneath it.
 *
 * There is no test for the service's own mtuFor because it is private and
 * Android-bound, so the numbers themselves are pinned here instead. They have
 * to match the engine: the system tells apps how large a packet may be, the
 * tunnel decides what it can actually carry, and a gap between the two is
 * silently dropped traffic.
 *
 * This is not hypothetical. v1.2.2 grouped WARP-in-WARP with WireGuard and told
 * the system it could send 1340 bytes into a tunnel carrying 1200. Small
 * requests survived, anything larger did not, and the result read as a
 * connection that worked until you opened a website.
 */
class TunnelMtuTest {
    private companion object {
        /** Cloudflare's cap on MASQUE. Not ours to raise. */
        const val MASQUE = 1280

        /** What the path allows WireGuard: 1340 inner leaves 1400 on the wire. */
        const val WIREGUARD = 1340

        /** The inner hop of WARP-in-WARP, mirroring INNER_MTU in the engine. */
        const val WARP_IN_WARP = 1200

        /** IPv6's minimum, which Android enforces on the interface. */
        const val IPV6_FLOOR = 1280
    }

    @Test
    fun warpInWarpIsNotSizedLikeWireGuard() {
        // It is built out of two WireGuard hops, which is exactly what made the
        // mistake easy: the apps talk to the inner one, and the inner one is
        // sized for what fits inside the outer.
        assertTrue(WARP_IN_WARP < WIREGUARD)
    }

    @Test
    fun theInnerHopFitsInsideTheOuterOne() {
        // WireGuard adds 32 bytes of its own, and the datagram carrying it adds
        // a 28-byte header. Both have to fit in what the outer hop carries, or
        // every full-sized packet is dropped at the boundary.
        val innerOnTheWire = WARP_IN_WARP + 32 + 28

        assertTrue("inner $innerOnTheWire must fit in outer $WIREGUARD", innerOnTheWire <= WIREGUARD)
    }

    @Test
    fun wireGuardLeavesRoomForAQuicPayload() {
        // The reason 1340 was chosen: hysteria2 and tuic need a 1280-byte UDP
        // payload, and 1280 inner left only 1252 of it.
        assertTrue(WIREGUARD - 28 >= 1280)
    }

    @Test
    fun masqueStaysAtCloudflaresCap() {
        // Not a number this project gets to pick, which is why QUIC-based nodes
        // remain impossible on that transport however the others are tuned.
        assertEquals(1280, MASQUE)
    }

    @Test
    fun anMtuBelowTheIpv6FloorCannotCarryAnIpv6Address() {
        // IPv6 requires 1280 and Android enforces it: an interface with an
        // IPv6 address and a smaller MTU is refused, and the refusal arrives as
        // an exception, so it crashed the app on connect rather than failing.
        //
        // WARP-in-WARP's inner hop is 1200, which is under that floor -- so the
        // very fix that stopped it dropping packets stopped it connecting at
        // all. It is IPv4 only.
        assertTrue(WARP_IN_WARP < IPV6_FLOOR)
        assertTrue(WIREGUARD >= IPV6_FLOOR)
        assertTrue(MASQUE >= IPV6_FLOOR)
    }

    @Test
    fun onlyWarpInWarpGivesUpIpv6() {
        // Worth pinning: if another transport ever drops below the floor it
        // silently loses IPv6 too, and that should be a decision somebody makes
        // rather than something a number change does quietly.
        val belowFloor = listOf(MASQUE, WIREGUARD, WARP_IN_WARP).filter { it < IPV6_FLOOR }

        assertEquals(listOf(WARP_IN_WARP), belowFloor)
    }
}
