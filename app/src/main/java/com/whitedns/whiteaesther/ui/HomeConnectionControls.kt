package com.whitedns.whiteaesther.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.WorkerSetupActivity
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import com.whitedns.whiteaesther.core.ChainConfig
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.TrafficSample
import com.whitedns.whiteaesther.service.formatRate
import com.whitedns.whiteaesther.ui.theme.AetherTheme

@Composable
internal fun AppSettings.methodSummary(): String = when {
    automaticCarrier -> stringResource(R.string.carrier_automatic)
    carrier == Carrier.WORKER -> "${stringResource(carrier.label)} · VLESS"
    secondCarrier != null -> stringResource(
        R.string.home_method_pair, stringResource(carrier.label), stringResource(secondCarrier.label),
    )
    else -> stringResource(carrier.label)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeConnectionControls(
    settings: AppSettings,
    status: EngineStatus,
    onSettingsChange: (AppSettings) -> Unit,
    onGoToRoutes: () -> Unit,
    onGoToChain: () -> Unit,
    onGoToApps: () -> Unit,
    onGoToTools: () -> Unit,
) {
    val colors = AetherTheme.colors
    val editable = status.stage == EngineStage.IDLE || status.stage == EngineStage.ERROR
    val television = LocalTvMode.current
    val context = LocalContext.current
    val worker = !settings.automaticCarrier && settings.carrier == Carrier.WORKER
    val profiles = !settings.automaticCarrier && settings.carrier == Carrier.PROXY
    var chooseMethod by rememberSaveable { mutableStateOf(false) }
    var chooseTransport by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editable) {
        if (!editable) {
            chooseMethod = false
            chooseTransport = false
        }
    }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(colors.ink3).selectableGroup().padding(4.dp)
            .testTag("home-mode"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EngineMode.entries.forEach { mode ->
            val selected = settings.mode == mode
            val available = editable && (mode == EngineMode.TUN || settings.automaticCarrier ||
                settings.carrier.usesEngine && settings.secondCarrier == null)
            val label = stringResource(if (mode == EngineMode.TUN) R.string.home_mode_vpn else R.string.home_mode_proxy)
            val interaction = remember(mode) { MutableInteractionSource() }
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                    .background(if (selected) colors.ink2 else androidx.compose.ui.graphics.Color.Transparent)
                    .tvControllerActivation(enabled = available) { onSettingsChange(settings.copy(mode = mode)) }
                    .selectable(
                        selected = selected, enabled = available, role = Role.RadioButton,
                        interactionSource = interaction, indication = LocalIndication.current,
                        onClick = { onSettingsChange(settings.copy(mode = mode)) },
                    )
                    .controllerFocus(interaction, shape, available)
                    .testTag("home-mode-${mode.wireName}")
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = AetherTheme.type.RowTitle,
                    color = if (selected) colors.brand else if (available) colors.text2 else colors.text2.copy(alpha = 0.55f), maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    AetherCard {
        HomeSelectionRow(
            stringResource(R.string.home_method), settings.methodSummary(),
            "home-method", editable, onClick = {
                if (television) onGoToRoutes() else chooseMethod = true
            },
        )
        if (worker) {
            Divider()
            val deployment = settings.worker
            val summary = deployment?.takeIf { it.ready }?.let {
                "${stringResource(it.options.mode.label)} · ${it.connectionHost}"
            } ?: stringResource(R.string.home_worker_setup)
            HomeSelectionRow(stringResource(R.string.worker_setup_title), summary, "home-worker-settings", editable) {
                context.startActivity(Intent(context, WorkerSetupActivity::class.java))
            }
        } else if (!settings.automaticCarrier && settings.carrier == Carrier.AETHER && settings.secondCarrier == null) {
            Divider()
            HomeSelectionRow(stringResource(R.string.home_transport), stringResource(settings.transport.label), "home-transport", editable) {
                chooseTransport = true
            }
        }
        if (!worker && (profiles || !settings.automaticCarrier || settings.chain.enabled) && settings.mode == EngineMode.TUN) {
            Divider()
            val node = when {
                !profiles && !settings.chain.enabled -> stringResource(R.string.home_default_exit)
                settings.chain.node == ChainConfig.AUTOMATIC_GROUP -> stringResource(R.string.chain_automatic)
                settings.chain.node != null -> settings.chain.node
                else -> stringResource(R.string.home_choose_node)
            }
            HomeSelectionRow(stringResource(if (profiles) R.string.carrier_proxy else R.string.home_exit_node),
                node, "home-exit-node", editable, onClick = onGoToChain)
        }
        Divider()
        HomeSelectionRow(
            stringResource(R.string.apps), settings.coverageSummary(),
            "home-apps", editable && settings.mode == EngineMode.TUN, onClick = onGoToApps,
        )
        Divider()
        HomeSelectionRow(
            stringResource(R.string.home_more_options), stringResource(R.string.home_more_options_summary),
            "home-tools", true, onClick = onGoToTools,
        )
    }
    if (!editable) {
        Note(stringResource(R.string.home_disconnect_to_edit), Modifier.testTag("home-settings-locked"))
    } else {
        Spacer(Modifier.height(12.dp))
    }

    if (chooseMethod && editable) {
        ModalBottomSheet(
            onDismissRequest = { chooseMethod = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.ink2,
            contentColor = colors.text,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            ) {
                Text(stringResource(R.string.home_method), style = AetherTheme.type.PageTitle, color = colors.text)
                Spacer(Modifier.height(12.dp))
                MethodChoices(settings) {
                    onSettingsChange(it)
                    chooseMethod = false
                }
                Spacer(Modifier.height(8.dp))
                OutlineButton(
                    text = stringResource(R.string.home_method_settings),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { chooseMethod = false; onGoToRoutes() },
                )
            }
        }
    }
    if (chooseTransport && editable) {
        ModalBottomSheet(
            onDismissRequest = { chooseTransport = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.ink2,
            contentColor = colors.text,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(stringResource(R.string.home_transport), style = AetherTheme.type.PageTitle)
                TunnelProtocol.entries.forEach { protocol ->
                    OptionRow(null, stringResource(protocol.label), "", settings.transport == protocol,
                        tag = "home-transport-${protocol.wireName}") {
                        onSettingsChange(settings.copy(transport = protocol))
                        chooseTransport = false
                    }
                }
            }
        }
    }
}

@Composable
internal fun MethodChoices(settings: AppSettings, onSelect: (AppSettings) -> Unit) {
    OptionRow(
        code = "AUTO", title = stringResource(R.string.carrier_automatic),
        subtitle = stringResource(R.string.home_automatic_description),
        selected = settings.automaticCarrier,
        onClick = { onSelect(settings.copy(automaticCarrier = true)) },
    )
    Carrier.entries.forEach { carrier ->
        OptionRow(
            code = when (carrier) {
                Carrier.WORKER -> "VLESS"
                Carrier.PSIPHON -> "SSH"
                Carrier.TOR -> "ONION"
                else -> null
            },
            title = stringResource(carrier.label),
            subtitle = stringResource(when (carrier) {
                Carrier.AETHER -> R.string.carrier_aether_detail
                Carrier.PSIPHON -> R.string.carrier_psiphon_detail
                Carrier.TOR -> R.string.carrier_tor_detail
                Carrier.WORKER -> R.string.carrier_worker_detail
                Carrier.PROXY -> R.string.carrier_proxy_detail
            }),
            selected = !settings.automaticCarrier && settings.carrier == carrier && settings.secondCarrier == null,
            onClick = { onSelect(settings.copy(automaticCarrier = false, carrier = carrier, secondCarrier = null,
                mode = if (carrier.usesEngine) settings.mode else EngineMode.TUN)) },
        )
    }
    if (!settings.automaticCarrier && settings.secondCarrier != null) {
        OptionRow(
            code = null, title = stringResource(R.string.home_multi_hop),
            subtitle = settings.methodSummary(), selected = true,
            onClick = { onSelect(settings) },
        )
    }
}

@Composable
internal fun HomeSelectionRow(
    title: String,
    value: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    val configuration = LocalConfiguration.current
    val stacked = configuration.fontScale > 1.3f || configuration.screenWidthDp < 360
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .tvControllerActivation(enabled, onClick)
            .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerFocus(interaction, shape, enabled).testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (stacked) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = AetherTheme.type.Small, color = colors.text2)
                Text(value, style = AetherTheme.type.RowTitle, color = if (enabled) colors.text else colors.text2)
            }
        } else {
            Text(title, style = AetherTheme.type.Small, color = colors.text2, modifier = Modifier.weight(0.9f))
            Text(
                value, style = AetherTheme.type.RowTitle,
                color = if (enabled) colors.text else colors.text2,
                modifier = Modifier.weight(1.1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AetherIcons.Chevron, null, Modifier.size(16.dp), colors.text2)
    }
}

@Composable
internal fun HomeTrafficStrip(traffic: TrafficSample, active: Boolean) {
    val colors = AetherTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("home-traffic-strip"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            R.string.download to traffic.downloadPerSecond,
            R.string.upload to traffic.uploadPerSecond,
        ).forEach { (label, rate) ->
            Column(
                Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(stringResource(label), style = AetherTheme.type.Small, color = colors.text2)
                Text(
                    if (active && traffic.supported) formatRate(rate) else "—",
                    style = AetherTheme.type.Data, color = colors.text,
                )
            }
        }
    }
}
