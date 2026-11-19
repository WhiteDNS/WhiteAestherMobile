package com.whitedns.whiteaesther.service

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.RoutingPreset
import com.whitedns.whiteaesther.data.RuleAction
import com.whitedns.whiteaesther.data.RuleListStore
import com.whitedns.whiteaesther.data.SplitTunnel
import com.whitedns.whiteaesther.data.SplitTunnelMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Every kind of routing rule, checked from outside this app on a live session.
 *
 * Whole-device: this process is kept off the interface whenever mihomo owns it,
 * so asking from here proves nothing. The shell user is inside the VPN like any
 * app, so the requests go from there, through scripts pushed beforehand:
 *
 *     app/src/androidTest/routing-probe/matrix.sh pushes them and runs every case.
 *
 * Proxy coverage: asked from here through the SOCKS5 port, as an app would.
 *
 * Arguments (all optional except routing=true):
 *     carrier=aether|psiphon|tor|auto   second=psiphon|tor
 *     transport=auto|h3|h2|wg|wiw|mim|wom   mode=tun|proxy   split=except
 */
class RoutingRulesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val store = RuleListStore.of(context)

    @After
    fun tearDown() {
        AetherVpnService.stop(context)
        store.lists().forEach { store.delete(it.id) }
        Thread.sleep(3_000)
    }

    @Test
    fun everyRuleKindGoesWhereItSays() {
        assumeTrue("needs a network and VPN consent", arguments.getString("routing") == "true")
        val proxyMode = arguments.getString("mode") == "proxy"

        // Before the session, so these are the real answers and the real address.
        val checkip = InetAddress.getByName("checkip.amazonaws.com").hostAddress!!
        val ident = InetAddress.getAllByName("ident.me").first { it.address.size == 4 }.hostAddress!!
        val ipify = InetAddress.getAllByName("api.ipify.org").first { it.address.size == 4 }.hostAddress!!
        val real = shellHttp("icanhazip.com")
        check(real.isNotEmpty()) { "no network before connecting" }
        // The phone's own addresses, IPv6 included: a dual-stack service may
        // answer a direct connection on either.
        val reals = setOf(real, shellHttp("api6.ipify.org")) - ""
        // IPv6 privacy addresses change per connection; the /64 is the phone's.
        val isReal: (String) -> Boolean = { ip ->
            ip in reals || (':' in ip && reals.any { ':' in it && it.split(':').take(4) == ip.split(':').take(4) })
        }

        store.add("imported", RuleAction.DIRECT, listOf("wtfismyip.com"))
        val auto = arguments.getString("carrier") == "auto"
        val carrier = when (arguments.getString("carrier")) {
            "psiphon" -> Carrier.PSIPHON
            "tor" -> Carrier.TOR
            else -> Carrier.AETHER
        }
        val settings = AppSettings(
            mode = if (proxyMode) EngineMode.PROXY else EngineMode.TUN,
            automaticCarrier = auto,
            carrier = carrier,
            secondCarrier = when (arguments.getString("second")) {
                "psiphon" -> Carrier.PSIPHON
                "tor" -> Carrier.TOR
                else -> null
            },
            transport = TunnelProtocol.entries.firstOrNull { it.wireName == arguments.getString("transport") }
                ?: TunnelProtocol.AUTO,
            routeDirect = "https://api.ipify.org/\n*.ipinfo.io\nkeyword:ifconfig\nfull:checkip.amazonaws.com\ncidr:$ident/32",
            routeBlock = "ipecho.net",
            routingPresets = setOf(RoutingPreset.COMMON_ADS),
            splitTunnel = if (arguments.getString("split") == "except") {
                SplitTunnel(SplitTunnelMode.EXCEPT, setOf("com.android.chrome"))
            } else {
                SplitTunnel()
            },
            importedRulesVersion = store.signature(),
        )
        AetherVpnService.start(
            context, settings.toNativeJson(context), settings.chainForService().encode(), settings.splitTunnel.encode(),
            carrier = settings.carrier, secondCarrier = settings.secondCarrier, automatic = settings.automaticCarrier,
        )
        awaitConnected()
        Thread.sleep(3_000)

        val get: (String, String?, String) -> String =
            if (proxyMode) { host, ip, path -> socksHttp(settings.proxyPort, host, ip, path) }
            else { host, ip, path -> shellHttp(host, ip, path) }
        val results = linkedMapOf(
            "url rule (https://api.ipify.org/)" to (get("api.ipify.org", null, "/") to real),
            "wildcard rule (*.ipinfo.io)" to (get("ipinfo.io", null, "/ip") to real),
            "keyword rule (keyword:ifconfig)" to (get("ifconfig.me", null, "/ip") to real),
            "full rule, bare address, sniffed" to (get("checkip.amazonaws.com", checkip, "/") to real),
            "cidr rule, bare address" to (get("ident.me", ident, "/") to real),
            "imported direct list" to (get("wtfismyip.com", null, "/text") to real),
            "block rule (ipecho.net)" to (get("ipecho.net", null, "/plain") to ""),
            "ads preset (doubleclick.net)" to (get("ad.doubleclick.net", null, "/") to ""),
        )
        val tunnel = get("icanhazip.com", null, "/")

        val tls = if (proxyMode) {
            isReal(socksHttps(settings.proxyPort, "api.ipify.org"))
        } else {
            shell("sh /data/local/tmp/tls.sh $ipify /data/local/tmp/hello_api.ipify.org.bin")
            Thread.sleep(3_000)
            EngineLog.entries.value.any { ":443 match DomainSuffix(api.ipify.org) using DIRECT" in it.message }
        }

        val failures = mutableListOf<String>()
        results.forEach { (name, outcome) ->
            val (got, wanted) = outcome
            val ok = if (wanted.isEmpty()) got.isEmpty() else isReal(got)
            Log.i(TAG, "${if (ok) "PASS" else "FAIL"} $name: got '$got' wanted '${wanted.ifEmpty { "refused" }}'")
            if (!ok) failures += name
        }
        val tunnelOk = tunnel.isNotEmpty() && !isReal(tunnel)
        Log.i(TAG, "${if (tunnelOk) "PASS" else "FAIL"} unruled goes through the tunnel: got '$tunnel' real '$real'")
        if (!tunnelOk) failures += "unruled through tunnel"
        Log.i(TAG, "${if (tls) "PASS" else "FAIL"} direct rule over TLS")
        if (!tls) failures += "tls"
        EngineLog.entries.value.filter { it.tag in setOf("route", "carrier", "auto", "split") || ":443" in it.message }
            .forEach { Log.i(TAG, "log ${it.tag}: ${it.message}") }
        Log.i(TAG, "SUMMARY ${arguments.keySet().sorted().joinToString(",") { "$it=${arguments.getString(it)}" }}: " +
            if (failures.isEmpty()) "ALL PASS" else "FAILED $failures")
        org.junit.Assert.assertTrue("failed: $failures", failures.isEmpty())
    }

    private fun awaitConnected() {
        val deadline = System.currentTimeMillis() + 600_000
        while (System.currentTimeMillis() < deadline) {
            when (EngineStatusStore.status.value.stage) {
                EngineStage.CONNECTED -> return
                EngineStage.ERROR -> throw AssertionError("failed: ${EngineStatusStore.status.value.message}")
                else -> Thread.sleep(500)
            }
        }
        throw AssertionError("never connected")
    }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    /** Body of a plain-HTTP request made by the shell user, which is inside the VPN. */
    private fun shellHttp(host: String, connectTo: String? = null, path: String = "/"): String =
        bodyIp(shell("sh /data/local/tmp/probe.sh $host ${connectTo ?: host} $path"))

    private fun socksHttp(port: Int, host: String, connectTo: String?, path: String): String = runCatching {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress.createUnresolved(connectTo ?: host, 80), 15_000)
            socket.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: curl/8\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            bodyIp(socket.getInputStream().bufferedReader().readText())
        }
    }.getOrDefault("")

    private fun socksHttps(port: Int, host: String): String = runCatching {
        val raw = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        raw.connect(InetSocketAddress.createUnresolved(host, 443), 15_000)
        (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
            .createSocket(raw, host, 443, true).use { socket ->
                socket.soTimeout = 15_000
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                bodyIp(socket.getInputStream().bufferedReader().readText())
            }
    }.getOrDefault("")

    private fun bodyIp(response: String): String =
        response.substringAfter("\r\n\r\n", "").lines().map(String::trim)
            .lastOrNull { Regex("""[0-9a-fA-F:.]{7,}""").matches(it) }.orEmpty()

    private companion object {
        const val TAG = "RoutingRulesTest"
    }
}
