package com.whitedns.whiteaesther.data

import org.junit.Assert.*
import org.junit.Test

class WorkerOptionsTest {
    @Test fun settingsRoundTripWithoutDroppingAdvancedOptions() {
        val options = WorkerOptions(mode = WorkerMode.FRAGMENT, cleanAddresses = listOf("2001:db8::1", "edge.example.com"),
            ports = listOf(443, 8443), ech = true, customCdn = true, cdnAddresses = listOf("edge.example.com"),
            cdnHost = "cdn.example.com", cdnSni = "tls.example.com", upstreamProxy = "relay.example.com:443",
            proxyIps = listOf("proxy.example.com:8443"), fallbackUrl = "https://example.com/", tcpFastOpen = true, probeInterval = 20)
        options.validate()
        assertEquals(options, WorkerOptions.fromJson(options.toJson()))
    }
    @Test fun invalidInputIsRejectedBeforeSaving() {
        listOf(WorkerOptions(ports = emptyList()), WorkerOptions(ports = listOf(80)), WorkerOptions(cleanAddresses = listOf("host/path")),
            WorkerOptions(remoteDns = "https://1.1.1.1/dns-query"), WorkerOptions(fingerprint = "unknown"),
            WorkerOptions(fragments = listOf(WorkerFragment(lengths = "200-100"))), WorkerOptions(customCdn = true),
            WorkerOptions(upstreamProxy = "host"), WorkerOptions(fallbackUrl = "http://example.com"), WorkerOptions(probeInterval = 1)
        ).forEach { options -> assertThrows(IllegalArgumentException::class.java) { options.validate() } }
    }
}
