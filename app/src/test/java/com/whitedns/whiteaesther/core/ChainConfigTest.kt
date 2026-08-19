package com.whitedns.whiteaesther.core

import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.ChainSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChainConfigTest {
    private val settings = ChainSettings(
        enabled = true,
        sources = listOf(ChainSource("Test", "https://example.invalid/sub#token", true)),
    )

    @Test
    fun everySourceIsDialledThroughTheTunnel() {
        val config = ChainConfig.render(settings, socksPort = 1819)

        // The one line that makes a subscription of any size inherit the tunnel
        // without us parsing a single entry. Losing it is the failure that would
        // not look like a failure -- the chain would still work, and every node
        // would be dialled straight off the local network.
        assertTrue(config.contains("dialer-proxy: ${ChainConfig.TUNNEL_PROXY}"))
        assertTrue(
            config.contains(
                "- {name: ${ChainConfig.TUNNEL_PROXY}, type: socks5, " +
                    "server: 127.0.0.1, port: 1819, udp: true}",
            ),
        )
    }

    @Test
    fun resolversAreDialledThroughTheTunnelToo() {
        val config = ChainConfig.render(settings, socksPort = 1819)

        // A query that escapes names the destination even when the traffic does
        // not, and on this platform it would be answered by the interface that
        // asked it.
        assertTrue(config.contains("- https://1.1.1.1/dns-query#${ChainConfig.TUNNEL_PROXY}"))
        assertTrue(config.contains("- https://dns.google/dns-query#${ChainConfig.TUNNEL_PROXY}"))
    }

    @Test
    fun nothingIsRoutedDirect() {
        val config = ChainConfig.render(settings, socksPort = 1819)

        assertTrue(config.contains("- MATCH,${ChainConfig.EXIT_GROUP}"))
        // Exactly one rule. Any second one is a way for traffic to reach the
        // local network in the clear.
        assertEquals(1, config.lines().count { it.trimStart().startsWith("- MATCH") })
        assertFalse(config.contains(",DIRECT"))
    }

    @Test
    fun withoutATunnelNothingClaimsToUseOne() {
        val config = ChainConfig.render(settings, socksPort = null)

        // A socks5 proxy pointing at a port nothing is listening on would fail
        // every node it fronted, so with the tunnel off it is not declared at
        // all rather than declared and dead.
        assertFalse(config.contains("dialer-proxy"))
        assertFalse(config.contains("type: socks5"))
        assertFalse(config.contains("dns-query#"))
    }

    @Test
    fun subscriptionUrlsSurviveYaml() {
        val config = ChainConfig.render(settings, socksPort = 1819)

        // Unquoted, the '#' in a subscription URL starts a YAML comment and the
        // provider silently loses everything after it.
        assertTrue(config.contains("url: \"https://example.invalid/sub#token\""))
    }

    @Test
    fun pastedNodesBecomeAFileProvider() {
        val config = ChainConfig.render(
            settings.copy(sources = emptyList(), manual = "vless://example"),
            socksPort = 1819,
        )

        assertTrue(config.contains("${ChainConfig.MANUAL_PROVIDER}:"))
        assertTrue(config.contains("type: file"))
        assertTrue(config.contains("use: [${ChainConfig.MANUAL_PROVIDER}]"))
    }

    @Test
    fun noSourcesIsRefusedBeforeItReachesMihomo() {
        // An empty group is a config mihomo rejects, and the reason it gives
        // names YAML rather than the empty subscription behind it.
        val error = runCatching {
            ChainConfig.render(ChainSettings(enabled = true), socksPort = 1819)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun theInterfaceDoesNotOverlapAHomeNetwork() {
        // 198.18/15 is benchmarking space. RFC1918 would be the obvious choice
        // and the wrong one: a phone's own network is very likely on 192.168/16
        // or 10/8, and an overlap there captures the local subnet.
        assertTrue(ChainConfig.TUN_IPV4.startsWith("198.18."))
        assertTrue(ChainConfig.TUN_DNS.startsWith("198.18."))
        assertTrue(ChainConfig.TUN_IPV6.startsWith("fdfe:"))
    }

    /**
     * Writes what the app would hand mihomo, so it can be checked with a real
     * `mihomo -t`. Assertions above describe the file; only mihomo can say
     * whether it parses.
     */
    @Test
    fun renderedConfigIsWrittenForExternalValidation() {
        val target = File("build/chain-config/config.yaml")
        target.parentFile?.mkdirs()
        target.writeText(ChainConfig.render(settings, socksPort = 1819))
        assertTrue(target.length() > 0)
    }
}
