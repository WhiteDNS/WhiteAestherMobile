package com.whitedns.whiteaesther.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The HTTP this app had to write by hand.
 *
 * It is written by hand for one reason -- every client that would do it for us
 * resolves the host on this device first, which is the thing being routed
 * around -- and none of that reason is "we wanted to parse HTTP". So the parts
 * that are ordinary HTTP are tested, because they are the parts most likely to
 * be quietly wrong.
 */
class CarriedSocketTest {
    private val crlf = "\r\n"

    /**
     * A SOCKS5 listener that wants [expected], records what it was asked to
     * reach, and grants it. Returns the port and a slot for the asked name.
     */
    private fun listener(expected: SocksAuth?, asked: Array<String?>): Int {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.use {
                it.accept().use { sock ->
                    val input = DataInputStream(sock.getInputStream())
                    val out = sock.getOutputStream()
                    input.readUnsignedByte()
                    val offered = ByteArray(input.readUnsignedByte()).also(input::readFully)
                    val wanted = if (expected == null) 0x00 else 0x02
                    if (offered.none { b -> b.toInt() == wanted }) {
                        out.write(byteArrayOf(0x05, 0xFF.toByte()))
                        return@thread
                    }
                    out.write(byteArrayOf(0x05, wanted.toByte()))
                    if (expected != null) {
                        input.readUnsignedByte()
                        val user = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                        val password = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                        val ok = user == expected.user && password == expected.password
                        out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                        if (!ok) return@thread
                    }
                    repeat(3) { input.readUnsignedByte() }
                    check(input.readUnsignedByte() == 0x03) { "the name was not sent as a name" }
                    asked[0] = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                    input.readFully(ByteArray(2))
                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    out.flush()
                }
            }
        }
        return server.localPort
    }

    private fun handshake(port: Int, auth: SocksAuth?) = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 2_000
        CarriedSocket.connect(socket, "www.example.org", 443, auth)
    }

    @Test
    fun aListenerWithAPasswordIsReachedWithIt() {
        val auth = SocksAuth("user-token", "password-token")
        val asked = arrayOfNulls<String>(1)
        handshake(listener(auth, asked), auth)
        assertEquals("www.example.org", asked[0])
    }

    @Test
    fun aListenerWithoutAPasswordIsStillReached() {
        val asked = arrayOfNulls<String>(1)
        handshake(listener(null, asked), null)
        assertEquals("www.example.org", asked[0])
    }

    @Test
    fun wrongCredentialsAreAFailureNotAConnection() {
        val port = listener(SocksAuth("user-token", "password-token"), arrayOfNulls(1))
        assertThrows(IOException::class.java) { handshake(port, SocksAuth("user-token", "guess")) }
    }

    @Test
    fun aListenerThatWantsAPasswordRefusesAnAppWithout() {
        val port = listener(SocksAuth("user-token", "password-token"), arrayOfNulls(1))
        assertThrows(IOException::class.java) { handshake(port, null) }
    }

    @Test
    fun aPlainAnswerIsSplitIntoItsStatusAndItsBody() {
        val raw = "HTTP/1.1 200 OK" + crlf +
            "Content-Type: text/plain" + crlf + crlf +
            "ip=203.0.113.9" + crlf + "loc=NL"

        val answer = CarriedSocket.parseAnswer(raw, "example.invalid")

        assertEquals(200, answer.status)
        assertEquals("ip=203.0.113.9" + crlf + "loc=NL", answer.body)
    }

    @Test
    fun aFailureStatusIsReportedRatherThanThrown() {
        // The caller decides what a 403 means. Through a carrier it usually
        // means the exit is one that site refuses, which is not the same as the
        // carrier being broken.
        val raw = "HTTP/1.1 403 Forbidden" + crlf + crlf + "no"

        assertEquals(403, CarriedSocket.parseAnswer(raw, "example.invalid").status)
    }

    @Test
    fun aChunkedAnswerIsJoinedBackTogether() {
        // An edge uses chunked whatever we ask for, and a caller parsing JSON
        // gets nothing useful from a body with the sizes still in it.
        val raw = "HTTP/1.1 200 OK" + crlf +
            "Transfer-Encoding: chunked" + crlf + crlf +
            "7" + crlf + """{"a":1,""" + crlf +
            "7" + crlf + """"b":22}""" + crlf +
            "0" + crlf + crlf

        val answer = CarriedSocket.parseAnswer(raw, "example.invalid")

        assertEquals("""{"a":1,"b":22}""", answer.body)
    }

    @Test
    fun aChunkSizeWithAnExtensionIsStillASize() {
        assertEquals("hello", CarriedSocket.dechunk("5;name=value" + crlf + "hello" + crlf + "0" + crlf + crlf))
    }

    @Test
    fun aBodyThatStopsPartWayThroughGivesBackWhatArrived() {
        // Truncation is not worth an error of its own: whoever asked is about
        // to fail to parse this and can say what it wanted in its own terms.
        assertEquals("abc", CarriedSocket.dechunk("A" + crlf + "abc"))
    }

    @Test
    fun anAnswerWithNoHeaderAtAllIsAFailure() {
        assertThrows(IOException::class.java) {
            CarriedSocket.parseAnswer("HTTP/1.1 200 OK", "example.invalid")
        }
    }

    @Test
    fun anAnswerWithNoStatusIsAFailure() {
        assertThrows(IOException::class.java) {
            CarriedSocket.parseAnswer("garbage" + crlf + crlf + "body", "example.invalid")
        }
    }
}
