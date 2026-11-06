package com.whitedns.whiteaesther

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextFieldDefaults
import com.whitedns.whiteaesther.ui.SectionLabel
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whitedns.whiteaesther.core.AppLocale
import com.whitedns.whiteaesther.core.WorkerApi
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.SettingsRepository
import com.whitedns.whiteaesther.data.WorkerDeployment
import com.whitedns.whiteaesther.service.AetherVpnService
import com.whitedns.whiteaesther.ui.AdvancedSection
import com.whitedns.whiteaesther.ui.AetherCard
import com.whitedns.whiteaesther.ui.CardHead
import com.whitedns.whiteaesther.ui.CrumbBar
import com.whitedns.whiteaesther.ui.Note
import com.whitedns.whiteaesther.ui.OptionRow
import com.whitedns.whiteaesther.ui.OutlineButton
import com.whitedns.whiteaesther.ui.PageTitle
import com.whitedns.whiteaesther.ui.PrimaryButton
import com.whitedns.whiteaesther.ui.ScreenColumn
import com.whitedns.whiteaesther.ui.WorkerSetupModel
import com.whitedns.whiteaesther.ui.WorkerSettingsForm
import com.whitedns.whiteaesther.ui.tvTextFieldSupport
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme

class WorkerSetupActivity : ComponentActivity() {
    private val model by viewModels<WorkerSetupModel>()
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startConnection()
        else {
            model.pendingConnection = null
            model.reportError(getString(R.string.worker_consent_denied))
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val settings by remember { SettingsRepository(this).settings }.collectAsStateWithLifecycle(initialValue = AppSettings())
            WhiteAestherTheme(settings.themeMode) {
                Box(Modifier.fillMaxSize().background(AetherTheme.colors.ink1).padding(WindowInsets.systemBars.asPaddingValues())) {
                    WorkerSetupContent(model, onBack = { finish() }, pickOnly = intent.getBooleanExtra("pick_worker", false),
                        onConnect = { deployment ->
                        model.connect(deployment) {
                            val consent = VpnService.prepare(this@WorkerSetupActivity)
                            if (consent == null) startConnection() else permission.launch(consent)
                        }
                    })
                }
            }
        }
    }

    private fun startConnection() {
        val settings = model.pendingConnection ?: return
        model.pendingConnection = null
        try {
            AetherVpnService.start(
                this, settings.toNativeJson(this), settings.chainForService().encode(), settings.splitTunnel.encode(),
                settings.killSwitch, settings.strictKillSwitch, settings.carrier, settings.secondCarrier,
                settings.torBridge, settings.torBridges, settings.psiphonRegion, settings.automaticCarrier,
            )
        } catch (_: Exception) {
            model.reportError(getString(R.string.worker_connection_failed))
            return
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}

@Composable
internal fun WorkerSetupContent(
    model: WorkerSetupModel, onBack: () -> Unit, onConnect: (WorkerDeployment) -> Unit,
    pickOnly: Boolean = false,
) {
    val uriHandler = LocalUriHandler.current
    val browserFailure = stringResource(R.string.worker_browser_failed)
    var setupExpanded by remember { mutableStateOf(model.deployments.isEmpty()) }
    var deleting by remember { mutableStateOf<WorkerDeployment?>(null) }
    ScreenColumn {
        CrumbBar(stringResource(R.string.worker_setup_title), onBack = onBack)
        if (pickOnly) {
            PageTitle(stringResource(R.string.worker_pick), stringResource(R.string.worker_vless_only))
            model.deployments.filter { it.ready }.forEach { deployment ->
                OptionRow(null, deployment.connectionHost, "VLESS · WebSocket · TLS", false) {
                    if (!model.busy) onConnect(deployment)
                }
            }
            model.error?.let { Note(it) }
            return@ScreenColumn
        }
        PageTitle(stringResource(R.string.worker_setup_title), stringResource(R.string.worker_setup_summary))
        if (model.busy) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("worker-progress"))
        }
        model.error?.let {
            Spacer(Modifier.height(12.dp))
            Note(it, Modifier.testTag("worker-error"))
        }
        model.deployments.forEach { deployment ->
            var domain by remember(deployment.key, deployment.customDomain) { mutableStateOf(deployment.customDomain) }
            Spacer(Modifier.height(12.dp))
            AetherCard {
                CardHead(deployment.name, deployment.host.ifBlank { stringResource(R.string.worker_incomplete) })
                Column(Modifier.padding(15.dp)) {
                    Note(stringResource(if (deployment.pages) R.string.worker_pages_summary else R.string.worker_workers_summary))
                    Note(stringResource(R.string.worker_connection_warning))
                    PrimaryButton(stringResource(R.string.worker_connect), Modifier.fillMaxWidth(), enabled = !model.busy && deployment.ready) {
                        onConnect(deployment)
                    }
                    WorkerSettingsForm(deployment, model.busy, { model.saveOptions(deployment, it) }, model::reportError)
                    WorkerField(domain, { domain = it }, R.string.worker_custom_domain, "worker-domain-${deployment.name}", model.busy)
                    Note(stringResource(R.string.worker_domain_summary))
                    if (deployment.domainPending) Note(stringResource(R.string.worker_domain_pending))
                    OutlineButton(stringResource(R.string.worker_attach_domain), Modifier.fillMaxWidth(), enabled = !model.busy && model.token.isNotBlank() && domain.isNotBlank()) {
                        model.attachDomain(deployment, domain)
                    }
                    if (deployment.customDomain.isNotBlank()) OutlineButton(stringResource(R.string.worker_detach_domain), Modifier.fillMaxWidth(), enabled = !model.busy && model.token.isNotBlank()) {
                        model.detachDomain(deployment)
                    }
                    if (!deployment.pages) {
                        model.usage[deployment.key]?.let { (total, worker) -> Note(stringResource(R.string.worker_usage_values, total, worker)) }
                        OutlineButton(stringResource(R.string.worker_usage), Modifier.fillMaxWidth(), enabled = !model.busy && model.token.isNotBlank()) { model.loadUsage(deployment) }
                    }
                    OutlineButton(stringResource(if (deployment.ready) R.string.worker_update else R.string.worker_retry), Modifier.fillMaxWidth(), enabled = !model.busy && model.token.isNotBlank()) {
                        model.update(deployment)
                    }
                    OutlineButton(stringResource(R.string.worker_delete), Modifier.fillMaxWidth(), enabled = !model.busy && model.token.isNotBlank()) {
                        deleting = deployment
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AdvancedSection(stringResource(R.string.worker_add_deployment), setupExpanded, { setupExpanded = !setupExpanded }) {
            AetherCard {
                CardHead(stringResource(R.string.worker_account), stringResource(R.string.worker_token_summary))
                Column(Modifier.padding(15.dp)) {
                    WorkerField(model.token, { model.token = it }, R.string.worker_token, "worker-token", model.busy, secret = true)
                    OutlineButton(stringResource(R.string.worker_token_create), Modifier.fillMaxWidth(), enabled = !model.busy) {
                        runCatching { uriHandler.openUri(WorkerApi.tokenTemplateUrl) }
                            .onFailure { model.reportError(browserFailure) }
                    }
                    Note(stringResource(R.string.worker_permissions))
                    PrimaryButton(stringResource(R.string.worker_load_accounts), Modifier.fillMaxWidth(), enabled = !model.busy && model.token.isNotBlank()) {
                        model.loadAccounts()
                    }
                    model.accounts.forEach { account ->
                        OptionRow(null, account.name, account.id, account.id == model.accountId, tag = "worker-account-${account.id}") {
                            if (!model.busy) model.accountId = account.id
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            AetherCard {
                CardHead(stringResource(R.string.worker_new_deployment))
                Column(Modifier.padding(15.dp)) {
                    OptionRow(null, "Workers", stringResource(R.string.worker_workers_summary), !model.pages) { if (!model.busy) model.pages = false }
                    OptionRow(null, "Pages", stringResource(R.string.worker_pages_summary), model.pages) { if (!model.busy) model.pages = true }
                    WorkerField(model.relay, { model.relay = it }, R.string.worker_relay, "worker-relay", model.busy)
                    Note(stringResource(R.string.worker_relay_summary))
                    PrimaryButton(stringResource(R.string.worker_deploy), Modifier.fillMaxWidth(), enabled = !model.busy && model.accountId.isNotBlank()) {
                        model.create()
                    }
                }
            }
        }
        Note(stringResource(R.string.worker_local_records))
    }
    deleting?.let { deployment ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.worker_delete)) },
            text = { Text(stringResource(R.string.worker_delete_confirm, deployment.name)) },
            confirmButton = { TextButton(onClick = { deleting = null; model.delete(deployment) }) { Text(stringResource(R.string.worker_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
internal fun WorkerField(value: String, changed: (String) -> Unit, label: Int, tag: String, busy: Boolean, secret: Boolean = false, multiline: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val colors = AetherTheme.colors
    // Same shape as the focus ring tvTextFieldSupport draws, so focus shows one box, not two.
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionLabel(stringResource(label))
        OutlinedTextField(
            value = value, onValueChange = { changed(it.take(4096)) }, enabled = !busy, singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 5 else 1,
            textStyle = AetherTheme.type.Data.copy(color = colors.text),
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.ink1,
                unfocusedContainerColor = colors.ink1,
                disabledContainerColor = colors.ink1,
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().tvTextFieldSupport(interaction).testTag(tag), interactionSource = interaction,
        )
    }
}
