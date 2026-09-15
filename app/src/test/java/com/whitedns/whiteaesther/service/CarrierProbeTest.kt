package com.whitedns.whiteaesther.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * What the probe asks a carrier to connect to.
 *
 * The question this answers is not whether the probe works -- it cannot, with
 * nothing on the far side of the proxy -- but what it puts on the wire. On a
 * network whose resolver answers with the censor's address for the very sites
 * this probe uses, a request carrying an address resolved on this device is a
 * request to connect to the block page, through a carrier that was working.
 * SOCKS5 has a name form exactly so the far end can do the lookup, and the
 * probe has to use it.
 */
class CarrierProbeTest {
    /** What a SOCKS5 client asked for: the address type byte and the address. */
    private class Asked(val atyp: Int, val address: String)

    private companion object {
        const val ATYP_IPV4 = 0x01
        const val ATYP_DOMAIN = 0x03
        const val ATYP_IPV6 = 0x04
    }

    /**
     * A SOCKS5 listener that records the first request and says no to it.
     *
     * Refusing rather than connecting keeps this a unit test: what the probe
     * does after a successful connect is a matter for the network, and what it
     * asked for is decided before any of that.
     */
    private fun captureFirstRequest(): Pair<Int, AtomicReference<Asked?>> {
        val server = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        val seen = AtomicReference<Asked?>(null)
        val first = CountDownLatch(1)
        thread(isDaemon = true) {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    runCatching {
                        client.use { sock ->
                            val input = DataInputStream(sock.getInputStream())
                            val out = sock.getOutputStream()

                            // Greeting: version, method count, methods.
                            input.readUnsignedByte()
                            val methods = input.readUnsignedByte()
                            repeat(methods) { input.readUnsignedByte() }
                            out.write(byteArrayOf(0x05, 0x00))
                            out.flush()

                            // Request: version, command, reserved, address type.
                            input.readUnsignedByte()
                            input.readUnsignedByte()
                            input.readUnsignedByte()
                            val atyp = input.readUnsignedByte()
                            val address = when (atyp) {
                                ATYP_DOMAIN -> {
                                    val length = input.readUnsignedByte()
                                    val name = ByteArray(length)
                                    input.readFully(name)
                                    String(name, Charsets.US_ASCII)
                                }

                                ATYP_IPV4 -> {
                                    val octets = ByteArray(4)
                                    input.readFully(octets)
                                    java.net.InetAddress.getByAddress(octets).hostAddress.orEmpty()
                                }

                                else -> {
                                    val octets = ByteArray(16)
                                    input.readFully(octets)
                                    java.net.InetAddress.getByAddress(octets).hostAddress.orEmpty()
                                }
                            }
                            seen.compareAndSet(null, Asked(atyp, address))
                            first.countDown()

                            // General failure, so the probe gives up on this
                            // target rather than waiting for bytes that are
                            // never coming.
                            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                            out.flush()
                        }
                    }
                }
                if (first.await(1, TimeUnit.MILLISECONDS)) {
                    // Keep serving: the probe tries more than one target.
                    continue
                }
            }
        }
        return server.localPort to seen
    }

    /**
     * A SOCKS5 listener that says yes to everything and then answers nothing
     * resembling TLS -- a captive portal, a block page, a proxy whose tunnel is
     * not really up.
     */
    private fun acceptEverything(): Int {
        val server = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    runCatching {
                        client.use { sock ->
                            val input = DataInputStream(sock.getInputStream())
                            val out = sock.getOutputStream()
                            input.readUnsignedByte()
                            val methods = input.readUnsignedByte()
                            repeat(methods) { input.readUnsignedByte() }
                            out.write(byteArrayOf(0x05, 0x00))
                            out.flush()
                            input.readUnsignedByte()
                            input.readUnsignedByte()
                            input.readUnsignedByte()
                            when (input.readUnsignedByte()) {
                                ATYP_DOMAIN -> input.readFully(ByteArray(input.readUnsignedByte()))
                                ATYP_IPV4 -> input.readFully(ByteArray(4))
                                else -> input.readFully(ByteArray(16))
                            }
                            input.readFully(ByteArray(2))
                            // Granted.
                            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                            out.flush()
                            // Then a block page, which is not a TLS server.
                            out.write("HTTP/1.1 403 Forbidden\r\n\r\nblocked".toByteArray())
                            out.flush()
                        }
                    }
                }
            }
        }
        return server.localPort
    }

    @Test
    fun aCarrierThatConnectsButCannotProveWhoItReachedDoesNotCount() {
        // The failure this exists to catch is a route that completes every
        // handshake below TLS and still carries nothing to the real internet.
        // Accepting it would have Automatic settle on a tunnel that loads no
        // page, which is worse than reporting that nothing worked.
        assertEquals(false, runBlocking { CarrierProbe.works(acceptEverything()) })
    }

    @Test
    fun theProbeAsksTheCarrierToResolveTheName() {
        val (port, seen) = captureFirstRequest()

        runBlocking { CarrierProbe.works(port) }

        val asked = seen.get()
        requireNotNull(asked) { "the probe never sent a SOCKS5 request" }
        // A name, not an address. An address here is one this device resolved,
        // and a resolver that answers 10.10.34.34 for a blocked name would have
        // the probe check that the carrier can reach the block page.
        assertEquals(
            "the probe resolved ${asked.address} locally instead of sending the name",
            ATYP_DOMAIN,
            asked.atyp,
        )
    }
}
