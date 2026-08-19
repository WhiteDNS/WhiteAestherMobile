package com.whitedns.whiteaesther.core

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
