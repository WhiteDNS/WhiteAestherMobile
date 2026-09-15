package com.whitedns.whiteaesther.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

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
