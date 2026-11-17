package com.whitedns.whiteaesther.ui

import com.whitedns.whiteaesther.data.Carrier
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.data.AdvancedGroup
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.AetherTheme

/**
 * Settings > Advanced: every expert control, one topic per page.
 *
 * Each row says whether anything on its page differs from a fresh install,
 * so a user who tuned the app long ago can find what they changed.
 */
@Composable
fun AdvancedScreen(
    settings: AppSettings,
    status: EngineStatus,
    onSettingsChange: (AppSettings) -> Unit,
    onOpen: (AdvancedGroup) -> Unit,
    onOpenIdentity: () -> Unit,
    rowModifier: (AdvancedGroup) -> Modifier = { Modifier },
    identityModifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val editable = status.stage == EngineStage.IDLE || status.stage == EngineStage.ERROR
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    val manual = !settings.automaticCarrier

    @Composable
    fun Group(group: AdvancedGroup, current: Boolean = false) {
        val (icon, title, subtitle) = group.presentation(settings)
        AdvancedRow(
            icon = icon, title = title, subtitle = subtitle,
            changed = !group.isDefault(settings), current = current,
            modifier = rowModifier(group).testTag("advanced-${group.name.lowercase()}"),
            onClick = { onOpen(group) },
        )
    }

    ScreenColumn {
        CrumbBar(stringResource(R.string.settings), onBack = onBack)
        PageTitle(stringResource(R.string.advanced), stringResource(R.string.advanced_hub_detail))

        // The method and its plain settings are in the connect screen's sheet;
        // what is here is for someone tuning past that.
        Note(stringResource(R.string.advanced_methods_hint))
        SectionLabel(stringResource(R.string.advanced_section_connection), Modifier.padding(start = 4.dp, bottom = 6.dp))
        AetherCard {
            Group(AdvancedGroup.AETHER, current = manual && Carrier.AETHER in settings.carrierPath)
            Divider()
            Group(AdvancedGroup.NODES, current = manual && (settings.carrier == Carrier.PROXY || settings.chain.enabled))
            // An exit chain with nothing in it carries nothing, and this is
            // where the user looks for it.
            if (settings.chain.enabled && !settings.chain.hasNodes) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Note(stringResource(R.string.empty_exit_chain_hint))
                    OutlineButton(
                        text = stringResource(R.string.use_carriers_only),
                        modifier = Modifier.fillMaxWidth().testTag("use-carriers-only"),
                        onClick = { onSettingsChange(settings.copy(chain = settings.chain.copy(enabled = false))) },
                    )
                }
            }
            Divider()
            Group(AdvancedGroup.TWO_HOP)
        }
        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.advanced_section_general), Modifier.padding(start = 4.dp, bottom = 6.dp))
        AetherCard {
            Group(AdvancedGroup.ROUTING)
            Divider()
            Group(AdvancedGroup.NETWORK)
            Divider()
            Group(AdvancedGroup.SAFETY)
        }
        Spacer(Modifier.height(12.dp))
        AetherCard {
            Group(AdvancedGroup.DIAGNOSTICS)
            Divider()
            AdvancedRow(
                icon = AetherIcons.Key,
                title = stringResource(R.string.identity_access),
                subtitle = stringResource(R.string.the_device_identity_this_app_was_issued),
                changed = null,
                modifier = identityModifier.testTag("settings-identity"),
                onClick = onOpenIdentity,
            )
        }

        if (AdvancedGroup.entries.any { !it.isDefault(settings) }) {
            Spacer(Modifier.height(12.dp))
            OutlineButton(
                text = stringResource(R.string.advanced_reset_all),
                modifier = Modifier.fillMaxWidth().testTag("advanced-reset-all"),
                enabled = editable,
                onClick = { confirmReset = true },
            )
            if (!editable) Note(stringResource(R.string.home_disconnect_to_edit))
        }
    }

    if (confirmReset && editable) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.advanced_reset_title)) },
            text = { Text(stringResource(R.string.advanced_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSettingsChange(AdvancedGroup.resetAll(settings))
                        confirmReset = false
                    },
                    modifier = Modifier.testTag("advanced-reset-confirm"),
                ) { Text(stringResource(R.string.advanced_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            },
            containerColor = AetherTheme.colors.ink2,
        )
    }
}

@Composable
private fun AdvancedGroup.presentation(settings: AppSettings): Triple<ImageVector, String, String> = when (this) {
    AdvancedGroup.AETHER -> Triple(Carrier.AETHER.simpleIcon,
        stringResource(R.string.carrier_aether), stringResource(R.string.advanced_aether_detail))
    // Named for what it is now: an exit chain behind Aether is not "proxy nodes".
    AdvancedGroup.NODES -> Triple(
        AetherIcons.Globe,
        stringResource(if (!settings.automaticCarrier && settings.carrier == Carrier.PROXY) R.string.carrier_proxy else R.string.routes_exit_chain),
        settings.chainSummary(),
    )
    AdvancedGroup.TWO_HOP -> Triple(AetherIcons.Routes, stringResource(R.string.home_multi_hop),
        if (settings.secondCarrier != null && !settings.automaticCarrier) settings.methodSummary()
        else stringResource(R.string.advanced_off))
    AdvancedGroup.ROUTING -> Triple(AetherIcons.Routes, stringResource(R.string.routing_rules), settings.routingSummary())
    AdvancedGroup.NETWORK -> Triple(AetherIcons.Traffic,
        stringResource(R.string.advanced_network), stringResource(R.string.advanced_network_detail))
    AdvancedGroup.SAFETY -> Triple(AetherIcons.ShieldCheck,
        stringResource(R.string.advanced_safety), stringResource(R.string.advanced_safety_detail))
    AdvancedGroup.DIAGNOSTICS -> Triple(AetherIcons.Pulse,
        stringResource(R.string.diagnostics_logs), stringResource(R.string.see_what_happened_and_send_a_report))
}

/** A hub row: [changed] null shows nothing, for a page with no settings of its own; [current] marks the method in use. */
@Composable
private fun AdvancedRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    changed: Boolean?,
    modifier: Modifier,
    current: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth()
            .tvControllerActivation(onClick = onClick)
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerFocus(interaction, shape)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(colors.ink3)
                .border(1.dp, colors.line, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(18.dp), colors.brand)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = AetherTheme.type.RowTitle, color = colors.text)
                if (current) Text(
                    stringResource(R.string.method_current), style = AetherTheme.type.Small,
                    color = colors.onBrand,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.brand)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(subtitle, style = AetherTheme.type.Small, color = colors.text2)
        }
        when (changed) {
            true -> Text(
                stringResource(R.string.advanced_changed), style = AetherTheme.type.Small,
                color = colors.signalWorking,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(colors.signalWorking.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            false -> Text(stringResource(R.string.advanced_default), style = AetherTheme.type.Small, color = colors.text2)
            null -> Unit
        }
        Icon(AetherIcons.Chevron, null, Modifier.size(17.dp), colors.text3)
    }
}
