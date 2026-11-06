package com.whitedns.whiteaesther.core

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PsiphonSocksProxyTest {
    @Test
    fun udpCodecRejectsFragmentsReservedBytesAndTruncation() {
        val packet = byteArrayOf(0, 0, 0, 1, 1, 2, 3, 4, 0, 53, 9)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), PsiphonSocksProxy.decodeDatagram(packet)?.address)
        assertNull(PsiphonSocksProxy.decodeDatagram(packet.copyOf(9)))
        assertNull(PsiphonSocksProxy.decodeDatagram(packet.copyOf().also { it[0] = 1 }))
        assertNull(PsiphonSocksProxy.decodeDatagram(packet.copyOf().also { it[2] = 1 }))
        val numeric = "1.2.3.4".toByteArray()
        val domain = byteArrayOf(0, 0, 0, 3, numeric.size.toByte()) + numeric + byteArrayOf(0, 53, 9)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), PsiphonSocksProxy.decodeDatagram(domain)?.address)
    }

    @Test
    fun concurrentUdpAssociationsShareOneGatewayAndEndWithTheirControlSockets() {
        FakePsiphon().use { upstream ->
            PsiphonSocksProxy(upstream.port).use { proxy ->
                val first = association(proxy.port)
                val second = association(proxy.port)
                DatagramSocket(0, InetAddress.getLoopbackAddress()).use { a ->
                    DatagramSocket(0, InetAddress.getLoopbackAddress()).use { b ->
                        a.soTimeout = 3000
                        b.soTimeout = 3000
                        val one = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53, 11)
                        val two = one.copyOf().also { it[it.lastIndex] = 22 }
                        a.send(DatagramPacket(one, one.size, first.second))
                        assertArrayEquals(one, receive(a))
                        b.send(DatagramPacket(two, two.size, second.second))
                        assertArrayEquals(two, receive(b))
                        assertEquals(1, upstream.gatewayCount.get())
                        first.first.close()
                        assertTrue(proxy.awaitAssociations(1))
                        b.send(DatagramPacket(two, two.size, second.second))
                        assertArrayEquals(two, receive(b))
                        second.first.close()
                        assertTrue(proxy.awaitAssociations(0))
                    }
                }
            }
        }
    }

    @Test
    fun tcpNamesArePassedToTheNativeProxyWithoutDeviceDns() {
        FakePsiphon().use { upstream ->
            PsiphonSocksProxy(upstream.port).use { proxy ->
                Socket("127.0.0.1", proxy.port).use { client ->
                    client.soTimeout = 3000
                    greet(client)
                    val host = "does-not-resolve.invalid".toByteArray()
                    client.outputStream.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(1, -69))
                    val input = DataInputStream(client.inputStream)
                    assertEquals(5, input.readUnsignedByte())
                    assertEquals(0, input.readUnsignedByte())
                    input.readFully(ByteArray(8))
                    client.outputStream.write(byteArrayOf(42))
                    assertEquals(42, input.readUnsignedByte())
                    assertTrue(upstream.targets.contains("does-not-resolve.invalid:443"))
                }
            }
        }
    }

    @Test
    fun unavailableGatewayRefusesTheAssociationRatherThanPromisingUdp() {
        FakePsiphon(allowGateway = false).use { upstream ->
            PsiphonSocksProxy(upstream.port).use { proxy ->
                Socket("127.0.0.1", proxy.port).use { client ->
                    client.soTimeout = 3000
                    greet(client)
                    client.outputStream.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                    assertEquals(5, client.inputStream.read())
                    assertNotEquals(0, client.inputStream.read())
                }
            }
        }
    }

    @Test
    fun gatewayIdsWrapWithoutReplacingLiveDestinations() {
        FakePsiphon().use { upstream ->
            PsiphonSocksProxy(upstream.port).use { proxy ->
                val association = association(proxy.port)
                association.first.use {
                    DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { udp ->
                        udp.soTimeout = 3000
                        fun exchange(port: Int) {
                            val bytes = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, (port shr 8).toByte(), port.toByte(), 11)
                            udp.send(DatagramPacket(bytes, bytes.size, association.second))
                            assertArrayEquals(bytes, receive(udp))
                        }
                        exchange(53)
                        // Advance the private counter instead of opening 65,534 destination sockets.
                        val field = proxy.javaClass.getDeclaredField("gateway").apply { isAccessible = true }
                        val gateway = field.get(proxy)
                        gateway.javaClass.getDeclaredField("nextId").apply { isAccessible = true }.setInt(gateway, 65535)
                        exchange(54)
                        exchange(55)
                        exchange(53)
                        assertEquals(listOf(1, 65535, 2, 1), upstream.ids)
                        assertEquals(listOf(2, 2, 2, 0), upstream.flags)
                        assertEquals(1, upstream.gatewayCount.get())
                    }
                }
            }
        }
    }

    private fun receive(socket: DatagramSocket): ByteArray {
        val reply = DatagramPacket(ByteArray(1024), 1024)
        socket.receive(reply)
        return reply.data.copyOf(reply.length)
    }

    private fun association(port: Int): Pair<Socket, InetSocketAddress> {
        val client = Socket("127.0.0.1", port)
        client.soTimeout = 3000
        greet(client)
        client.outputStream.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
        val input = DataInputStream(client.inputStream)
        assertEquals(5, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
        assertEquals(1, input.readUnsignedByte())
        val address = ByteArray(4).also(input::readFully)
        return client to InetSocketAddress(InetAddress.getByAddress(address), input.readUnsignedShort())
    }

    private fun greet(client: Socket) {
        client.outputStream.write(byteArrayOf(5, 1, 0))
        assertEquals(5, client.inputStream.read())
        assertEquals(0, client.inputStream.read())
    }

    private fun PsiphonSocksProxy.awaitAssociations(count: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (associationCount == count) return true
            Thread.sleep(5)
        }
        return false
    }

    private class FakePsiphon(private val allowGateway: Boolean = true) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val clients = CopyOnWriteArrayList<Socket>()
        val port get() = listener.localPort
        val gatewayCount = AtomicInteger()
        val targets = CopyOnWriteArrayList<String>()
        val ids = CopyOnWriteArrayList<Int>()
        val flags = CopyOnWriteArrayList<Int>()

        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val client = runCatching { listener.accept() }.getOrNull() ?: break
                    clients += client
                    thread(isDaemon = true) {
                        client.use {
                            runCatching {
                                val input = DataInputStream(client.inputStream)
                                assertEquals(5, input.readUnsignedByte())
                                input.readFully(ByteArray(input.readUnsignedByte()))
                                client.outputStream.write(byteArrayOf(5, 0))
                                assertEquals(5, input.readUnsignedByte())
                                assertEquals(1, input.readUnsignedByte())
                                input.readUnsignedByte()
                                val host = when (input.readUnsignedByte()) {
                                    1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress!!
                                    3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                                    else -> error("unexpected address")
                                }
                                val port = input.readUnsignedShort()
                                targets += "$host:$port"
                                val gateway = host == "127.0.0.1" && port == 7300
                                client.outputStream.write(byteArrayOf(5, if (gateway && !allowGateway) 1 else 0, 0, 1, 0, 0, 0, 0, 0, 0))
                                if (gateway && !allowGateway) return@use
                                if (gateway) {
                                    gatewayCount.incrementAndGet()
                                    while (true) {
                                        val low = input.readUnsignedByte()
                                        val high = input.readUnsignedByte()
                                        val body = ByteArray(low or (high shl 8)).also(input::readFully)
                                        if (body[0].toInt() and 1 == 0) {
                                            flags += body[0].toInt()
                                            ids += (body[1].toInt() and 255) or ((body[2].toInt() and 255) shl 8)
                                        }
                                        client.outputStream.write(byteArrayOf(low.toByte(), high.toByte()) + body)
                                    }
                                } else {
                                    input.copyTo(client.outputStream)
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            clients.forEach { runCatching { it.close() } }
        }
    }
}
