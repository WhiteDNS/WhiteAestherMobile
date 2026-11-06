package com.whitedns.whiteaesther.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.whitedns.whiteaesther.data.WorkerDeployment
import com.whitedns.whiteaesther.data.WorkerMode
import com.whitedns.whiteaesther.service.EngineLog
import com.whitedns.whiteaesther.service.CarrierProbe
import com.whitedns.whiteaesther.service.LogLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Xray is a child executable: no second Go runtime or JNI classes enter this process. */
internal class WorkerCarrierClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deployment: WorkerDeployment,
) : CarrierClient {
    private val mutableState = MutableStateFlow(CarrierSnapshot())
    override val state = mutableState
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    private var configuration: File? = null
    private var browser: WebView? = null

    override suspend fun start(timeoutMs: Long): Result<Int> = withContext(Dispatchers.IO) {
        stopping = false
        mutableState.value = CarrierSnapshot(CarrierStage.CONNECTING)
        try {
            val port = freePort()
            val dialerPort = if (deployment.options.mode == WorkerMode.BROWSER) freePort() else 0
            val binary = File(context.applicationInfo.nativeLibraryDir, "libwhiteaesther_xray.so")
            check(binary.isFile && binary.canExecute()) { "The Worker engine is missing from this build" }
            val directory = File(context.noBackupFilesDir, "worker-runtime").apply { mkdirs() }
            val config = File.createTempFile("connection-", ".json", directory).also { configuration = it }
            config.writeText(WorkerConfig.render(deployment, port))
            val builder = ProcessBuilder(binary.absolutePath, "run", "-config", config.absolutePath).redirectErrorStream(true)
            if (dialerPort > 0) builder.environment()["xray.browser.dialer"] = "127.0.0.1:$dialerPort"
            val running = builder.start().also { process = it }
            scope.launch(Dispatchers.IO) {
                try {
                    running.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            EngineLog.record(LogLevel.INFO, "worker", line.replace(deployment.uuid, "[identity]").take(500))
                        }
                    }
                } catch (_: IOException) {
                    // Android may close the pipe while destroy() interrupts a read.
                    if (!stopping && running.isAlive) {
                        EngineLog.record(LogLevel.WARN, "worker", "Could not read Worker engine output")
                    }
                }
            }
            scope.launch(Dispatchers.IO) {
                val exit = running.waitFor()
                if (!stopping && process === running) mutableState.value = CarrierSnapshot(CarrierStage.FAILED, failure = "Worker engine stopped ($exit)")
            }
            val connected = withTimeoutOrNull(timeoutMs) {
                while (!listening(if (dialerPort > 0) dialerPort else port)) {
                    check(running.isAlive && !stopping) { "Worker engine stopped before opening its listener" }
                    delay(100)
                }
                if (dialerPort > 0) withContext(Dispatchers.Main) { openBrowser(dialerPort) }
                var online = false
                while (!online) {
                    check(running.isAlive && !stopping) { "Worker engine stopped during connection testing" }
                    online = CarrierProbe.works(port, listOf("www.google.com", "www.wikipedia.org"))
                    if (!online) delay(500)
                }
                true
            }
            check(connected == true) { "The Worker did not pass its connection test before the timeout" }
            check(running.isAlive && !stopping) { "The Worker stopped during startup" }
            mutableState.value = CarrierSnapshot(CarrierStage.CONNECTED, port)
            Result.success(port)
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        } catch (failure: Exception) {
            stop()
            val reason = failure.message?.replace(deployment.uuid, "[identity]") ?: "The Worker connection failed"
            mutableState.value = CarrierSnapshot(CarrierStage.FAILED, failure = reason)
            Result.failure(java.io.IOException(reason))
        }
    }

    override fun stop() {
        stopping = true
        val running = process
        process = null
        runCatching {
            running?.destroy()
            if (running?.isAlive == true) running.destroyForcibly()
        }
        configuration?.delete()
        configuration = null
        Handler(Looper.getMainLooper()).post {
            val view = browser
            browser = null
            runCatching { view?.stopLoading() }
            runCatching { view?.destroy() }
        }
        mutableState.value = CarrierSnapshot()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openBrowser(port: Int) {
        if (stopping) return
        val origin = "http://127.0.0.1:$port/"
        browser = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.toString() != origin
            }
            onResume()
            resumeTimers()
            loadUrl(origin)
        }
    }

    companion object {
        private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        private fun listening(port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }; true }.getOrDefault(false)
    }
}
