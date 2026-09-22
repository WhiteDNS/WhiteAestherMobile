package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsServersTest {
    /**
     * The address the report was filed against.
     *
     * A resolver typed into the field never reached the interface, so every
     * lookup went to the hardcoded pair and a leak test reported those instead.
     * This is the case that has to keep working.
     */
    @Test
    fun anAddressTypedIntoTheFieldSurvivesToTheInterface() {
        assertEquals(listOf("77.88.8.1"), DnsServers.parse("77.88.8.1"))
        assertEquals(
            listOf("77.88.8.1", "77.88.8.8"),
            DnsServers.parse(" 77.88.8.1 , 77.88.8.8 "),
        )
    }

    /** Empty means the engine's own, which the caller supplies. */
    @Test
    fun nothingTypedIsNothingReturned() {
        assertEquals(emptyList<String>(), DnsServers.parse(""))
        assertEquals(emptyList<String>(), DnsServers.parse("   ,  ; "))
    }

    /**
     * A hostname is dropped without being looked up.
     *
     * The field's own description says entries that are not an address are
     * ignored. Resolving one to find out would be a DNS lookup on the thread
     * bringing the tunnel up, on a network whose DNS is the problem.
     */
    @Test
    fun anythingThatIsNotAnAddressIsDroppedRatherThanResolved() {
        for (bad in listOf(
            "dns.yandex.ru",
            "localhost",
            "1.1.1",
            "1.1.1.1.1",
            "256.1.1.1",
            "01.1.1.1",
            "1.1.1.-1",
            "1e2.1.1.1",
            "https://1.1.1.1/dns-query",
            "1.1.1.1:53",
            "fe80::1%wlan0",
        )) {
            assertFalse(bad, DnsServers.isAddress(bad))
        }
        // The good ones the bad list must not have taken with it.
        assertEquals(listOf("9.9.9.9"), DnsServers.parse("dns.yandex.ru, 9.9.9.9, 256.1.1.1"))
    }

    /** IPv6 literals are kept, in the spellings people actually type. */
    @Test
    fun ipv6ResolversAreRecognised() {
        for (good in listOf(
            "2606:4700:4700::1111",
            "2001:4860:4860::8888",
            "::1",
            "fe80::1",
            "2a02:6b8::feed:0ff",
        )) {
            assertTrue(good, DnsServers.isAddress(good))
        }
        for (bad in listOf("2606::4700::1111", ":::1", "12345::1", "2606:4700:4700:1111")) {
            assertFalse(bad, DnsServers.isAddress(bad))
        }
    }

    /**
     * A long list is a long wait.
     *
     * Android asks each resolver on the interface in turn, so the tail of a
     * pasted list costs time on every name that reaches it.
     */
    @Test
    fun theListIsCappedAndDeduplicated() {
        assertEquals(listOf("1.1.1.1"), DnsServers.parse("1.1.1.1, 1.1.1.1, 1.1.1.1"))
        assertEquals(4, DnsServers.parse("1.1.1.1 2.2.2.2 3.3.3.3 4.4.4.4 5.5.5.5 6.6.6.6").size)
    }
}
