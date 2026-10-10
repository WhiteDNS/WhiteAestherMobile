package com.whitedns.whiteaesther.core

/** Owns a tunnel until the native routing engine actually accepts its descriptor. */
internal class TunnelHandoff(
    private val detach: () -> Int,
    private val release: () -> Unit,
) : AutoCloseable {
    private var owned = true

    @Synchronized fun take(): Int {
        check(owned) { "The tunnel descriptor was already released" }
        val fd = detach()
        owned = false
        return fd
    }

    @Synchronized override fun close() {
        if (owned) {
            owned = false
            release()
        }
    }
}
