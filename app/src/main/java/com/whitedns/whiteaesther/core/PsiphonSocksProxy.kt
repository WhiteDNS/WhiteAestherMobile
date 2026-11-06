package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.DnsServers
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Adds UDP ASSOCIATE to the stock Psiphon SOCKS listener without another native runtime. */
class PsiphonSocksProxy(private val upstreamPort: Int) : Closeable {
    init { require(upstreamPort in 1..65535) }

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val listener = ServerSocket(0, 32, loopback)
    private val resources = ConcurrentHashMap.newKeySet<Closeable>()
    private val associations = ConcurrentHashMap.newKeySet<Association>()
    private val sessions = Semaphore(128)
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "psiphon-socks").apply { isDaemon = true }
    }
    private val keepalive = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "psiphon-udp-keepalive").apply { isDaemon = true }
    }
    private val gatewayLock = Any()
    @Volatile private var gateway: Gateway? = null
    @Volatile private var closed = false

    val port: Int get() = listener.localPort
    internal val associationCount: Int get() = associations.size

    init {
        workers.execute {
            while (!closed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                if (closed || !sessions.tryAcquire()) {
                    client.close()
                    continue
                }
                resources += client
                try {
                    workers.execute {
                        try {
                            client.use { if (!closed) handle(it) }
                        } catch (_: IOException) {
                            // Closing a tunnel interrupts its outstanding socket reads.
                        } catch (_: RejectedExecutionException) {
                            // Shutdown may race with starting the other half of a relay.
                        } finally {
                            resources -= client
                            sessions.release()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    resources -= client
                    sessions.release()
                    client.close()
                }
            }
        }
        keepalive.scheduleWithFixedDelay({
            val current = gateway ?: return@scheduleWithFixedDelay
            runCatching { current.write(byteArrayOf(3, 0, 1, 0, 0)) }
                .onFailure { current.close() }
        }, 20, 20, TimeUnit.SECONDS)
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = DataInputStream(client.inputStream)
        if (input.readUnsignedByte() != 5) return
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (0.toByte() !in methods) {
            client.outputStream.write(byteArrayOf(5, -1))
            return
        }
        client.outputStream.write(byteArrayOf(5, 0))
        if (input.readUnsignedByte() != 5) return
        val command = input.readUnsignedByte()
        if (input.readUnsignedByte() != 0) return
        val target = readAddress(input)
        when (command) {
            1 -> {
                val remote = try { openStock(target.host, target.port) } catch (_: IOException) {
                    reply(client, 4)
                    return
                }
                resources += remote
                try {
                    remote.use {
                        reply(client, 0)
                        client.soTimeout = 0
                        remote.soTimeout = 0
                        val done = CountDownLatch(1)
                        workers.execute {
                            try { remote.inputStream.copyTo(client.outputStream) } catch (_: IOException) {
                            } finally {
                                runCatching { client.close() }
                                runCatching { remote.close() }
                                done.countDown()
                            }
                        }
                        try {
                            input.copyTo(remote.outputStream)
                            runCatching { remote.shutdownOutput() }
                            done.await()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                } finally { resources -= remote }
            }
            3 -> {
                try { currentGateway() } catch (_: IOException) {
                    reply(client, 1)
                    return
                }
                Association(client, target.port).use { association ->
                    associations += association
                    resources += association
                    try {
                        reply(client, 0, association.socket.localPort)
                        client.soTimeout = 0
                        workers.execute { association.receive() }
                        while (input.read() != -1) { /* The control socket owns the association. */ }
                    } finally {
                        associations -= association
                        resources -= association
                    }
                }
            }
            else -> reply(client, 7)
        }
    }

    private fun reply(client: Socket, code: Int, port: Int = 0) {
        client.outputStream.write(byteArrayOf(5, code.toByte(), 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
    }

    private fun openStock(host: String, port: Int): Socket {
        if (closed) throw IOException("Psiphon proxy is stopped")
        val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(loopback, upstreamPort)))
        resources += socket
        try {
            if (closed) throw IOException("Psiphon proxy is stopped")
            socket.connect(InetSocketAddress.createUnresolved(host, port), 10_000)
            socket.soTimeout = 10_000
            return socket
        } catch (error: IOException) {
            resources -= socket
            socket.close()
            throw error
        }
    }

    private fun currentGateway(): Gateway = synchronized(gatewayLock) {
        gateway?.takeUnless { it.socket.isClosed }?.let { return@synchronized it }
        gateway?.close()
        Gateway(openStock("127.0.0.1", 7300)).also { next ->
            gateway = next
            workers.execute { next.receive() }
        }
    }

    private inner class Association(val control: Socket, private val requestedPort: Int) : Closeable {
        val socket = DatagramSocket(0, loopback)
        @Volatile private var client: InetSocketAddress? = null
        @Volatile var stopped = false
            private set

        fun receive() {
            val packet = DatagramPacket(ByteArray(65535), 65535)
            try {
                while (!closed && !socket.isClosed) {
                    packet.length = packet.data.size
                    socket.receive(packet)
                    val source = packet.socketAddress as InetSocketAddress
                    if (source.address != control.inetAddress || (requestedPort != 0 && source.port != requestedPort)) continue
                    if (client != null && source != client) continue
                    val datagram = decodeDatagram(packet.data.copyOf(packet.length)) ?: continue
                    client = source
                    val address = datagram.address ?: resolve(datagram.host) ?: continue
                    val current = currentGateway()
                    current.send(this, address, datagram.port, datagram.payload)
                }
            } catch (_: IOException) {
                // A broken gateway closes this association so the caller can create a new one.
            } finally { control.close() }
        }

        fun deliver(address: ByteArray, port: Int, payload: ByteArray) {
            val target = client ?: return
            val bytes = byteArrayOf(0, 0, 0, if (address.size == 4) 1 else 4) + address +
                byteArrayOf((port shr 8).toByte(), port.toByte()) + payload
            socket.send(DatagramPacket(bytes, bytes.size, target))
        }

        override fun close() {
            stopped = true
            socket.close()
            synchronized(gatewayLock) {
                gateway?.routes?.entries?.removeAll { it.value.association === this }
                gateway?.ids?.keys?.removeAll { it.first === this }
            }
        }
    }

    private data class Route(val association: Association, val address: ByteArray, val port: Int)

    private inner class Gateway(val socket: Socket) : Closeable {
        private val writeLock = Any()
        val routes = linkedMapOf<Int, Route>()
        val ids = hashMapOf<Pair<Association, InetSocketAddress>, Int>()
        var nextId = 1

        fun send(association: Association, address: ByteArray, port: Int, payload: ByteArray) {
            if (payload.size > 32768) return
            val message = synchronized(gatewayLock) {
                if (association.stopped || closed) return
                if (gateway !== this || socket.isClosed) throw IOException("Psiphon UDP gateway changed")
                val key = association to InetSocketAddress(InetAddress.getByAddress(address), port)
                var id = ids[key]
                val rebind = id == null
                if (id == null) {
                    if (routes.size >= 4096) return
                    while (nextId in routes) nextId = nextId % 65535 + 1
                    id = nextId
                    nextId = nextId % 65535 + 1
                    ids[key] = id
                    routes[id] = Route(association, address, port)
                }
                val size = 3 + address.size + 2 + payload.size
                val bytes = ByteArrayOutputStream(size + 2)
                DataOutputStream(bytes).apply {
                    writeByte(size and 255)
                    writeByte(size shr 8)
                    writeByte((if (address.size == 16) 8 else 0) or (if (rebind) 2 else 0))
                    writeByte(id and 255)
                    writeByte(id shr 8)
                    write(address)
                    writeShort(port)
                    write(payload)
                }
                bytes.toByteArray()
            }
            write(message)
        }

        fun write(message: ByteArray) = synchronized(writeLock) {
            socket.outputStream.write(message)
        }

        fun receive() {
            try {
                socket.soTimeout = 0
                val input = DataInputStream(socket.inputStream)
                while (!closed) {
                    val size = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                    if (size !in 3..32789) throw IOException("Invalid Psiphon UDP frame size")
                    val body = ByteArray(size).also(input::readFully)
                    val flags = body[0].toInt()
                    if (flags and 1 != 0) continue
                    val addressSize = if (flags and 8 != 0) 16 else 4
                    if (size < 5 + addressSize) throw IOException("Truncated Psiphon UDP frame")
                    val id = (body[1].toInt() and 255) or ((body[2].toInt() and 255) shl 8)
                    val address = body.copyOfRange(3, 3 + addressSize)
                    val port = ((body[3 + addressSize].toInt() and 255) shl 8) or (body[4 + addressSize].toInt() and 255)
                    val route = synchronized(gatewayLock) { if (gateway === this) routes[id] else null } ?: continue
                    if (route.port != port || !route.address.contentEquals(address)) continue
                    runCatching { route.association.deliver(address, port, body.copyOfRange(5 + addressSize, size)) }
                }
            } catch (_: IOException) {
            } finally { close() }
        }

        override fun close() {
            socket.close()
            resources -= socket
        }
    }

    private val dnsCache = linkedMapOf<String, Pair<Long, ByteArray>>()

    private fun resolve(host: String): ByteArray? {
        synchronized(dnsCache) {
            dnsCache[host]?.takeIf { it.first > System.nanoTime() }?.let { return it.second }
        }
        // Resolve through Psiphon; asking Android's resolver would expose names outside the tunnel.
        for (type in listOf(1, 28)) {
            val answer = runCatching {
                CarriedSocket.request("1.1.1.1", "/dns-query?name=${URLEncoder.encode(host, "UTF-8")}&type=$type&ct=application/dns-json", upstreamPort, 10_000)
            }.getOrNull() ?: continue
            if (answer.status != 200) continue
            val records = runCatching { JSONObject(answer.body).optJSONArray("Answer") }.getOrNull() ?: continue
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val value = record.optString("data")
                if (record.optInt("type") != type || !DnsServers.isAddress(value)) continue
                val address = InetAddress.getByName(value).address
                synchronized(dnsCache) {
                    if (dnsCache.size >= 128) dnsCache.remove(dnsCache.keys.first())
                    val ttl = record.optInt("TTL", 60).coerceIn(0, 300)
                    dnsCache[host] = (System.nanoTime() + TimeUnit.SECONDS.toNanos(ttl.toLong())) to address
                }
                return address
            }
        }
        return null
    }

    override fun close() {
        closed = true
        listener.close()
        gateway?.close()
        resources.forEach { runCatching { it.close() } }
        resources.clear()
        keepalive.shutdownNow()
        workers.shutdownNow()
    }

    internal data class Datagram(val host: String, val address: ByteArray?, val port: Int, val payload: ByteArray)

    private data class Address(val host: String, val address: ByteArray?, val port: Int)

    companion object {
        internal fun decodeDatagram(bytes: ByteArray): Datagram? = runCatching {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) return null
            val address = readAddress(input)
            if (address.port == 0) return null
            Datagram(address.host, address.address, address.port, ByteArray(input.available()).also(input::readFully))
        }.getOrNull()

        private fun readAddress(input: DataInputStream): Address {
            var address: ByteArray? = null
            val host = when (input.readUnsignedByte()) {
                1 -> { address = ByteArray(4).also(input::readFully); InetAddress.getByAddress(address).hostAddress!! }
                4 -> { address = ByteArray(16).also(input::readFully); InetAddress.getByAddress(address).hostAddress!! }
                3 -> {
                    val size = input.readUnsignedByte()
                    if (size == 0) throw IOException("Empty SOCKS hostname")
                    val value = ByteArray(size).also(input::readFully)
                    if (value.any { it.toInt() !in 33..126 }) throw IOException("Invalid SOCKS hostname")
                    value.toString(Charsets.US_ASCII).also {
                        if (DnsServers.isAddress(it)) address = InetAddress.getByName(it).address
                    }
                }
                else -> throw IOException("Unsupported SOCKS address")
            }
            return Address(host, address, input.readUnsignedShort())
        }
    }
}
