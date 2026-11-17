package com.whitedns.whiteaesther.ui

import com.whitedns.whiteaesther.data.AddressReporter
import com.whitedns.whiteaesther.data.TunnelExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitFactsTest {
    @Test
    fun theTraceGivesTheAddressAndTheCountry() {
        val body = "fl=12f\nh=www.cloudflare.com\nip=203.0.113.20\nts=1.2\nloc=DE\ntls=TLSv1.3\n"

        assertEquals(TunnelExit("203.0.113.20", "DE", 180), AddressReporter.parseTrace(body, 180))
    }

    @Test
    fun anAnswerWithoutAnAddressIsNoAnswer() {
        assertNull(AddressReporter.parseTrace("loc=DE\n", 10))
        assertNull(AddressReporter.parseTrace("ip=\nloc=DE\n", 10)?.ip)
    }

    @Test
    fun aCountryWithoutLocIsUnknownNotEmpty() {
        assertNull(AddressReporter.parseTrace("ip=203.0.113.20\n", 10)?.country)
    }

    @Test
    fun onlyRealCountryCodesBecomeFlags() {
        assertEquals("🇩🇪", countryFlag("de"))
        // Cloudflare's own codes: unknown, and Tor.
        assertNull(countryFlag("XX"))
        assertNull(countryFlag("T1"))
        assertNull(countryFlag(null))
        assertNull(countryFlag("DEU"))
    }
}
