package com.whitedns.whiteaesther.core

import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.data.WorkerDeployment
import com.whitedns.whiteaesther.data.WorkerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Stage 1 gate for the exit chain: the Go library that carries mihomo loads
 * into this process alongside the Rust engine, and answers.
 *
 * Both together is the part worth testing. Go supports one runtime per process,
 * and the failure mode when that is violated is not a load error -- it survives
 * a couple of calls and then dies inside a cgo callback. So this asserts the
 * engine is up first, then the chain, then that the chain actually replies.
 */
class NativeChainBridgeTest {
    @Test
    fun stoppingWorkerDuringHandshakeKeepsTheAppAlive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ServerSocket(8443, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val deployment = WorkerDeployment("a".repeat(32), "whiteaesther-stop-test", false,
                host = "127.0.0.1", ready = true, options = WorkerOptions(cleanAddresses = emptyList(), ports = listOf(8443)))
            val client = WorkerCarrierClient(context, this, deployment)
            val starting = launch { client.start(20_000) }
            try {
                withContext(Dispatchers.IO) { server.accept() }.use {
                    starting.cancelAndJoin()
                    client.stop()
                    assertEquals(CarrierStage.STOPPED, client.state.value.stage)
                }
            } finally {
                starting.cancelAndJoin()
                client.stop()
            }
        }
    }

    @Test
    fun packagedWorkerEngineRunsOnAndroid() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val executable = java.io.File(context.applicationInfo.nativeLibraryDir, "libwhiteaesther_xray.so")
        assertTrue(executable.canExecute())
        val process = ProcessBuilder(executable.absolutePath, "version").redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertTrue(process.inputStream.bufferedReader().use { it.readText() }.contains("Xray 26.10.31"))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun importedProtocolsCanBeListedWithoutStartingATunnel() {
        val uuid = "132c9dbb-9749-4059-b773-bae5ef515336"
        val vmess = java.util.Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"vmess-test","add":"example.com","port":"443","id":"$uuid","aid":"0","net":"ws","tls":"tls"}""".toByteArray(),
        )
        val profiles = "vless://$uuid@example.com:443?security=tls&type=ws#vless-test\n" +
            "vmess://$vmess\ntrojan://fixture@example.com:443#trojan-test"
        val reply = NativeChainBridge.invoke("previewProxies", profiles)
        assertTrue(reply.error, reply.ok)
        val nodes = reply.data as org.json.JSONArray
        val kinds = (0 until nodes.length()).map { nodes.getJSONObject(it).getString("kind").lowercase() }.toSet()
        assertEquals(setOf("vless", "vmess", "trojan"), kinds)
    }

    @Test
    fun engineAndChainLoadIntoTheSameProcess() {
        assertTrue("the Rust engine did not load", NativeAetherBridge.isLoaded)
        assertNotNull("the engine did not report a version", NativeAetherBridge.versionOrNull())
        assertTrue("the chain library did not load or resolve", NativeChainBridge.isAvailable)
    }

    @Test
    fun chainAnswersAnAction() {
        assertTrue(NativeChainBridge.isAvailable)

        // An unknown method is the cheapest round trip that proves the whole
        // path: our JSON reaches Go, Go dispatches it, and the reply comes back
        // through the callback and the channel rather than hanging. The core
        // answers unknown methods rather than dropping them, which is the only
        // reason this terminates.
        val reply = NativeChainBridge.invoke("wa.probe")
        assertNotNull("the chain returned nothing", reply)
        assertEquals("an unknown method should not report success", false, reply.ok)
    }

    @Test
    fun repeatedActionsDoNotDestabiliseTheRuntime() {
        assertTrue(NativeChainBridge.isAvailable)

        // The two-Go-runtime failure showed up on the third call, not the first.
        // Ten round trips is cheap and would catch a regression of that shape.
        repeat(10) { attempt ->
            val reply = NativeChainBridge.invoke("wa.probe")
            assertNotNull("call $attempt returned nothing", reply)
        }
    }
}
