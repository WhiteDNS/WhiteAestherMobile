package com.whitedns.whiteaesther.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whitedns.whiteaesther.core.WorkerAccount
import com.whitedns.whiteaesther.core.WorkerApi
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.SettingsRepository
import com.whitedns.whiteaesther.data.WorkerDeployment
import com.whitedns.whiteaesther.data.WorkerOptions
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatusStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.util.UUID

class WorkerSetupModel(application: Application) : AndroidViewModel(application) {
    private val storage = application.getSharedPreferences("worker_deployments", Context.MODE_PRIVATE)
    private val settings = SettingsRepository(application)
    var token by mutableStateOf("")
    var relay by mutableStateOf("")
    var pages by mutableStateOf(false)
    var accounts by mutableStateOf(emptyList<WorkerAccount>())
        private set
    var accountId by mutableStateOf("")
    var deployments by mutableStateOf(emptyList<WorkerDeployment>())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pendingConnection: AppSettings? = null
    var usage by mutableStateOf(emptyMap<String, Pair<Long, Long>>())
        private set
    private var storageHealthy = true

    init {
        try {
            deployments = readDeployments()
        } catch (_: Exception) {
            storageHealthy = false
            error = "Saved deployment records could not be read. They have been kept unchanged."
        }
    }

    private fun readDeployments(): List<WorkerDeployment> {
        return WorkerDeployment.saved(getApplication<Application>())
    }

    private fun writeDeployments(rows: List<WorkerDeployment>) {
        if (!storage.edit().putString("deployments", JSONArray().apply { rows.forEach { put(it.toJson()) } }.toString()).commit()) {
            throw IOException("Could not save the deployment record; no further changes were made")
        }
    }

    private fun save(deployment: WorkerDeployment) = writeDeployments(
        readDeployments().filterNot { it.key == deployment.key } + deployment,
    )

    private fun api() = WorkerApi(token, getApplication<Application>().assets.open("worker.js").bufferedReader().use { it.readText() })

    private fun run(action: suspend () -> Unit) {
        if (busy || !storageHealthy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (failure: Exception) {
                error = failure.message?.replace(token.takeIf { it.isNotBlank() } ?: "\u0000", "[token]")
                    ?: "The operation failed"
            } finally {
                runCatching { readDeployments() }
                    .onSuccess { deployments = it }
                    .onFailure { storageHealthy = false; error = "Saved deployment records could not be read. They have been kept unchanged." }
                busy = false
            }
        }
    }

    fun loadAccounts() = run {
        accounts = withContext(Dispatchers.IO) { api().accounts() }
        accountId = accounts.firstOrNull { it.id == accountId }?.id ?: accounts.firstOrNull()?.id.orEmpty()
        if (accounts.isEmpty()) error = "This token has no accessible Cloudflare accounts"
    }

    fun create() = run {
        val deployment = WorkerDeployment(accountId, "whiteaesther-${UUID.randomUUID().toString().replace("-", "")}", pages, relay = relay.trim())
        withContext(Dispatchers.IO) {
            val client = api()
            // Keep the name and identity before the first remote write so a failed setup can be retried or deleted.
            save(deployment)
            client.deploy(deployment, ::save)
        }
    }

    fun update(deployment: WorkerDeployment, relay: String = deployment.relay) = run {
        withContext(Dispatchers.IO) { api().deploy(deployment.copy(relay = relay.trim()), ::save) }
    }

    fun saveOptions(deployment: WorkerDeployment, options: WorkerOptions) = run {
        options.validate()
        val current = settings.settings.first()
        require(current.worker?.key != deployment.key || idle()) { "Disconnect this Worker before changing its settings" }
        val updated = deployment.copy(options = options, relay = "")
        val applied = withContext(Dispatchers.IO) {
            if (updated.environment() != deployment.environment()) {
                require(token.isNotBlank()) { "Paste the API token to update proxy IPs or the fallback website on Cloudflare" }
                api().deploy(updated, ::save)
            } else updated.also(::save)
        }
        if (current.worker?.key == deployment.key) settings.save(current.copy(worker = applied))
    }

    fun attachDomain(deployment: WorkerDeployment, hostname: String) = run {
        require(idle()) { "Disconnect before changing the deployment domain" }
        val updated = withContext(Dispatchers.IO) { api().attachDomain(deployment, hostname, ::save) }
        val current = settings.settings.first()
        if (current.worker?.key == deployment.key) settings.save(current.copy(worker = updated))
    }

    fun detachDomain(deployment: WorkerDeployment) = run {
        require(idle()) { "Disconnect before changing the deployment domain" }
        val updated = withContext(Dispatchers.IO) { api().detachDomain(deployment, ::save) }
        val current = settings.settings.first()
        if (current.worker?.key == deployment.key) settings.save(current.copy(worker = updated))
    }

    fun loadUsage(deployment: WorkerDeployment) = run {
        val result = withContext(Dispatchers.IO) { api().usage(deployment) }
        usage = usage + (deployment.key to result)
    }

    fun delete(deployment: WorkerDeployment) = run {
        val current = settings.settings.first()
        require(idle()) { "Disconnect before deleting a deployment" }
        withContext(Dispatchers.IO) {
            api().delete(deployment)
        }
        val installed = runCatching {
            org.json.JSONObject(current.chain.worker).getJSONArray("proxies").getJSONObject(0).getString("name")
        }.getOrNull() == deployment.nodeName
        if (installed) {
            val chain = current.chain.copy(worker = "", node = current.chain.node.takeUnless { it == deployment.nodeName })
            settings.save(current.copy(chain = chain.copy(enabled = chain.enabled && chain.hasNodes)))
        }
        if (current.worker?.key == deployment.key) settings.save(settings.settings.first().copy(worker = null))
        withContext(Dispatchers.IO) { writeDeployments(readDeployments().filterNot { it.key == deployment.key }) }
    }

    fun connect(deployment: WorkerDeployment, ready: () -> Unit) = run {
        require(deployment.ready) { "Finish deploying before connecting" }
        require(idle()) { "Disconnect the current VPN before switching to a Worker" }
        val configured = deployment.configure(settings.settings.first())
        settings.save(configured)
        pendingConnection = configured
        ready()
    }

    fun reportError(message: String) { error = message }


    private fun idle(): Boolean = EngineStatusStore.status.value.stage in setOf(EngineStage.IDLE, EngineStage.ERROR)

    override fun onCleared() {
        token = ""
        super.onCleared()
    }
}
