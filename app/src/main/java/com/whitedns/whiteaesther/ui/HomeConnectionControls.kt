package com.whitedns.whiteaesther.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.data.AdvancedGroup
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.EngineMode
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

/**
 * The one choice the connect screen offers, and the sheet it opens.
 *
 * Everything else that used to sit here (VPN or proxy, transport, exit node,
 * apps) moved to the Apps tab or Settings > Advanced. What stays is what a
 * first-time user can act on without knowing what a carrier is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeConnectionControls(
    settings: AppSettings,
    status: EngineStatus,
    chooseMethod: Boolean,
    onChooseMethod: (Boolean) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    support: MethodSheetSupport,
    onOpenMethodPage: () -> Unit,
    onGoToAdvanced: () -> Unit,
    onGoToChain: () -> Unit,
    chainSelection: String? = null,
    chainAutomaticPick: String? = null,
    onOpenAdvancedGroup: (AdvancedGroup) -> Unit = {},
) {
    val colors = AetherTheme.colors
    val editable = status.stage == EngineStage.IDLE || status.stage == EngineStage.ERROR
    val television = LocalTvMode.current
    val context = LocalContext.current
    val worker = !settings.automaticCarrier && settings.carrier == Carrier.WORKER
    val profiles = !settings.automaticCarrier && settings.carrier == Carrier.PROXY
    LaunchedEffect(editable) {
        if (!editable) onChooseMethod(false)
    }

    // VPN or proxy-only, always in view under the connect button: it decides
    // whether the phone is covered at all, so it is not tucked away in a menu.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(colors.ink3).selectableGroup().padding(4.dp)
            .testTag("home-mode"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EngineMode.entries.forEach { mode ->
            val selected = settings.mode == mode
            // Proxy-only needs the engine itself: Psiphon, Tor and the
            // standalone methods carry the whole device only.
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
                    color = if (selected) colors.brand else if (available) colors.text2 else colors.text2.copy(alpha = 0.55f),
                    maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    AetherCard {
        HomeMethodRow(settings, editable) {
            if (television) onOpenMethodPage() else onChooseMethod(true)
        }
        // Only the follow-ups a chosen method cannot work without.
        if (worker) {
            Divider()
            val deployment = settings.worker
            val summary = deployment?.takeIf { it.ready }?.let {
                "${stringResource(it.options.mode.label)} · ${it.connectionHost}"
            } ?: stringResource(R.string.home_worker_setup)
            HomeSelectionRow(stringResource(R.string.worker_setup_title), summary, "home-worker-settings", editable) {
                context.startActivity(Intent(context, WorkerSetupActivity::class.java))
            }
        } else if (profiles || settings.chain.enabled) {
            Divider()
            // The running chain's answer while connected, the saved choice otherwise.
            // Nothing saved is automatic, which is what a connect then uses.
            val connected = status.stage == EngineStage.CONNECTED
            val chosen = chainSelection.takeIf { connected } ?: settings.chain.node
            val node = if (chosen == null || chosen == ChainConfig.AUTOMATIC_GROUP) {
                val automatic = stringResource(R.string.chain_automatic)
                chainAutomaticPick?.takeIf { connected }?.let { "$automatic · $it" } ?: automatic
            } else {
                chosen
            }
            HomeSelectionRow(stringResource(if (profiles) R.string.carrier_proxy else R.string.home_exit_node),
                // Open while connected too: picking, testing and updating nodes work live.
                node, "home-exit-node", onClick = onGoToChain)
        }
    }
    if (!editable) {
        Note(stringResource(R.string.home_disconnect_to_edit), Modifier.testTag("home-settings-locked"))
    } else {
        Spacer(Modifier.height(12.dp))
    }

    if (chooseMethod && editable) {
        ModalBottomSheet(
            onDismissRequest = { onChooseMethod(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.ink2,
            contentColor = colors.text,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            ) {
                Text(stringResource(R.string.method_sheet_title), style = AetherTheme.type.PageTitle, color = colors.text)
                Spacer(Modifier.height(12.dp))
                SimpleMethodChoices(settings, support, onChange = onSettingsChange, onDone = { onChooseMethod(false) })
                MoreConnectionSettings(
                    settings,
                    onOpen = { group -> onChooseMethod(false); onOpenAdvancedGroup(group) },
                    onAll = { onChooseMethod(false); onGoToAdvanced() },
                )
            }
        }
    }
}

/** What Automatic means, or which method is in use, with a way to change it. */
@Composable
private fun HomeMethodRow(settings: AppSettings, enabled: Boolean, onClick: () -> Unit) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .tvControllerActivation(enabled, onClick)
            .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerFocus(interaction, shape, enabled).testTag("home-method")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MethodIcon(settings.methodIcon(), selected = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(settings.methodSummary(), style = AetherTheme.type.RowTitle,
                color = if (enabled) colors.text else colors.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (settings.automaticCarrier) R.string.method_auto_simple else R.string.home_method),
                style = AetherTheme.type.Small, color = colors.text2,
            )
        }
        Text(stringResource(R.string.home_method_change), style = AetherTheme.type.Small,
            color = if (enabled) colors.brand else colors.text2)
    }
}

private fun AppSettings.methodIcon(): androidx.compose.ui.graphics.vector.ImageVector = when {
    automaticCarrier -> AetherIcons.Sparkle
    else -> carrier.simpleIcon
}

internal val Carrier.simpleIcon get() = when (this) {
    Carrier.AETHER -> AetherIcons.Pulse
    Carrier.PSIPHON -> AetherIcons.Shield
    Carrier.TOR -> AetherIcons.Lock
    Carrier.WORKER, Carrier.PROXY -> AetherIcons.Globe
}

@Composable
private fun MethodIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean) {
    val colors = AetherTheme.colors
    Box(
        Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (selected) colors.brand.copy(alpha = 0.14f) else colors.ink3),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), if (selected) colors.brand else colors.text2)
    }
}

/**
 * What the method sheet needs from outside to set a method up in place:
 * Psiphon's countries, Tor's bridge fetch, and the way to the proxy nodes.
 */
class MethodSheetSupport(
    val psiphonRegions: List<String> = emptyList(),
    val onFetchBridges: (String) -> Unit = {},
    val bridgesFetching: Boolean = false,
    val bridgesMessage: com.whitedns.whiteaesther.BridgeMessage? = null,
    val detectedCountry: String = "",
    val onOpenNodes: () -> Unit = {},
    val onOpenAether: () -> Unit = {},
)

/**
 * The method sheet in plain words: one recommendation, three methods named by
 * what they are good at, and the bring-your-own servers in one card.
 *
 * Choosing is setting up. A method with something to decide (Psiphon's
 * country, Tor's bridges, which own server) opens its choices inside its own
 * card and keeps the sheet open until Done; one with nothing to decide closes
 * it at once. The settings live here and nowhere else.
 */
@Composable
internal fun SimpleMethodChoices(
    settings: AppSettings,
    support: MethodSheetSupport,
    onChange: (AppSettings) -> Unit,
    onDone: () -> Unit,
) {
    val colors = AetherTheme.colors
    val context = LocalContext.current
    fun pick(carrier: Carrier) = settings.copy(
        automaticCarrier = false, carrier = carrier, secondCarrier = null,
        mode = if (carrier.usesEngine) settings.mode else EngineMode.TUN,
    )
    val single = !settings.automaticCarrier && settings.secondCarrier == null
    fun chosen(carrier: Carrier) = single && settings.carrier == carrier

    MethodOption(
        icon = AetherIcons.Sparkle,
        title = stringResource(R.string.carrier_automatic),
        badge = stringResource(R.string.profile_recommended),
        subtitle = stringResource(R.string.method_auto_simple),
        selected = settings.automaticCarrier,
        tag = "option-automatic",
        onClick = { onChange(settings.copy(automaticCarrier = true)); onDone() },
    )
    Text(stringResource(R.string.method_or_choose), style = AetherTheme.type.Small, color = colors.text2,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
    MethodOption(
        icon = Carrier.AETHER.simpleIcon, title = stringResource(R.string.method_fast),
        badge = stringResource(Carrier.AETHER.label), subtitle = null, meters = 3 to 2,
        selected = chosen(Carrier.AETHER), tag = "option-aether",
        onClick = { if (!chosen(Carrier.AETHER)) onChange(pick(Carrier.AETHER)) },
        content = if (chosen(Carrier.AETHER)) {
            {
                AetherProfiles(settings, onChange)
                // The individual knobs (protocol, endpoint, disguise) stay one
                // step further in, for someone who knows what they are.
                OutlineButton(
                    text = stringResource(R.string.method_aether_expert),
                    modifier = Modifier.fillMaxWidth().testTag("aether-expert"),
                    onClick = { onDone(); support.onOpenAether() },
                )
                DoneButton(onDone)
            }
        } else null,
    )
    MethodOption(
        icon = Carrier.PSIPHON.simpleIcon, title = stringResource(R.string.method_strong),
        badge = stringResource(Carrier.PSIPHON.label), subtitle = null, meters = 2 to 3,
        selected = chosen(Carrier.PSIPHON), tag = "option-psiphon",
        onClick = { if (!chosen(Carrier.PSIPHON)) onChange(pick(Carrier.PSIPHON)) },
        content = if (chosen(Carrier.PSIPHON)) {
            {
                PsiphonCountries(settings, support.psiphonRegions, onChange)
                DoneButton(onDone)
            }
        } else null,
    )
    MethodOption(
        icon = Carrier.TOR.simpleIcon, title = stringResource(R.string.method_anonymous),
        badge = stringResource(Carrier.TOR.label), subtitle = null, meters = 1 to 2,
        selected = chosen(Carrier.TOR), tag = "option-tor",
        onClick = { if (!chosen(Carrier.TOR)) onChange(pick(Carrier.TOR)) },
        content = if (chosen(Carrier.TOR)) {
            {
                Text(stringResource(R.string.tor_bridge), style = AetherTheme.type.Small, color = colors.text2)
                TorBridgeSettings(settings, onChange, support)
                DoneButton(onDone)
            }
        } else null,
    )

    val ownChosen = single && settings.carrier.isStandalone
    var ownOpen by rememberSaveable { mutableStateOf(ownChosen) }
    MethodOption(
        icon = AetherIcons.Globe,
        title = stringResource(R.string.method_own_server),
        subtitle = stringResource(R.string.method_own_server_detail),
        selected = ownChosen,
        tag = "option-own-server",
        expandable = true,
        expanded = ownOpen,
        onClick = { ownOpen = !ownOpen },
        content = if (ownOpen) {
            {
                listOf(Carrier.WORKER, Carrier.PROXY).forEach { carrier ->
                    OptionRow(
                        code = null,
                        title = stringResource(carrier.label),
                        subtitle = stringResource(
                            if (carrier == Carrier.WORKER) R.string.carrier_worker_detail else R.string.carrier_proxy_detail,
                        ),
                        selected = chosen(carrier),
                        tag = "option-${carrier.name.lowercase()}",
                        onClick = { onChange(pick(carrier)) },
                    )
                }
                // The one thing each own server cannot work without, right
                // under the choice instead of a page away.
                when {
                    chosen(Carrier.WORKER) -> OutlineButton(
                        text = stringResource(R.string.worker_setup_title),
                        modifier = Modifier.fillMaxWidth().testTag("own-server-worker-setup"),
                        onClick = { context.startActivity(Intent(context, WorkerSetupActivity::class.java)) },
                    )
                    chosen(Carrier.PROXY) -> OutlineButton(
                        text = stringResource(R.string.method_manage_nodes),
                        modifier = Modifier.fillMaxWidth().testTag("own-server-nodes"),
                        onClick = { onDone(); support.onOpenNodes() },
                    )
                }
                if (ownChosen) DoneButton(onDone)
            }
        } else null,
    )
    if (!settings.automaticCarrier && settings.secondCarrier != null) {
        MethodOption(
            icon = AetherIcons.Routes, title = stringResource(R.string.home_multi_hop),
            subtitle = settings.methodSummary(), selected = true, tag = "option-multi-hop",
            onClick = onDone,
        )
    }
}

/**
 * Aether's network profile as chips. Each one sets the protocol and the search
 * depth together; a combination set by hand shows as Manual and is left alone.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AetherProfiles(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val colors = AetherTheme.colors
    val active = settings.activeProfile()
    Text(stringResource(R.string.profile), style = AetherTheme.type.Small, color = colors.text2)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("aether-profiles"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ConnectionProfile.entries
            .filter { it != ConnectionProfile.MANUAL || active == ConnectionProfile.MANUAL }
            .forEach { profile ->
                Chip(stringResource(profile.label), selected = profile == active, tag = "aether-profile-${profile.name}") {
                    onChange(settings.applyProfile(profile))
                }
            }
    }
    Text(stringResource(active.description), style = AetherTheme.type.Small, color = colors.text2)
}

/** Psiphon's exit countries as chips: every choice in view at once. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PsiphonCountries(settings: AppSettings, regions: List<String>, onChange: (AppSettings) -> Unit) {
    val colors = AetherTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    Text(stringResource(R.string.psiphon_region), style = AetherTheme.type.Small, color = colors.text2)
    if (regions.isEmpty()) Note(stringResource(R.string.psiphon_region_unknown))
    // A country already chosen stays listed even if Psiphon no longer names it:
    // it is what the next attempt asks for, and a hidden choice cannot be undone.
    val chosen = settings.psiphonRegion
    val offered = listOf("") + regions + listOfNotNull(chosen.takeIf { it.isNotEmpty() && it !in regions })
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("psiphon-countries"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        offered.forEach { region ->
            val label = if (region.isEmpty()) {
                stringResource(R.string.psiphon_region_best)
            } else {
                listOfNotNull(countryFlag(region), countryName(region, locale)).joinToString(" ")
            }
            Chip(label, selected = chosen == region, tag = "psiphon-country-${region.ifEmpty { "best" }}") {
                onChange(settings.copy(psiphonRegion = region))
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    // A Box, not padding on the Text: the minimum height leaves room the text
    // would otherwise sit at the top of.
    Box(
        Modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (selected) colors.brand.copy(alpha = 0.12f) else colors.ink2)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.brand else colors.line, shape)
            .tvControllerActivation(onClick = onClick)
            .selectable(selected = selected, role = Role.RadioButton, interactionSource = interaction,
                indication = LocalIndication.current, onClick = onClick)
            .controllerFocus(interaction, shape).testTag(tag)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AetherTheme.type.Small,
            color = if (selected) colors.brand else colors.text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DoneButton(onDone: () -> Unit) {
    PrimaryButton(
        text = stringResource(R.string.method_done),
        modifier = Modifier.fillMaxWidth().testTag("method-done"),
        onClick = onDone,
    )
}

@Composable
private fun MethodOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    selected: Boolean,
    tag: String,
    badge: String? = null,
    meters: Pair<Int, Int>? = null,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onClick: () -> Unit,
    content: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (selected) colors.brand.copy(alpha = 0.07f) else colors.ink3)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.brand else colors.line, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .tvControllerActivation(onClick = onClick)
                .then(
                    if (expandable) {
                        Modifier.clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier.selectable(selected = selected, role = Role.RadioButton, interactionSource = interaction,
                            indication = LocalIndication.current, onClick = onClick)
                    },
                )
                .controllerFocus(interaction, shape).testTag(tag)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MethodIcon(icon, selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = AetherTheme.type.RowTitle, color = colors.text)
                    if (badge != null) Text(badge, style = AetherTheme.type.Small, color = colors.text2)
                }
                if (subtitle != null) Text(subtitle, style = AetherTheme.type.Small, color = colors.text2)
                if (meters != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Meter(stringResource(R.string.method_speed), meters.first)
                        Meter(stringResource(R.string.method_reach), meters.second)
                    }
                }
            }
            when {
                expandable -> Icon(AetherIcons.ChevronDown, null,
                    Modifier.size(20.dp).rotate(if (expanded) 180f else 0f), colors.text2)
                selected -> Icon(AetherIcons.Check, null, Modifier.size(20.dp), colors.brand)
            }
        }
        if (content != null) {
            // Indented under the title, so it reads as this method's own and not
            // as another method beside it.
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** Three dots, so the comparison reads without reading. */
@Composable
private fun Meter(label: String, filled: Int) {
    val colors = AetherTheme.colors
    val description = stringResource(R.string.method_meter, label, filled)
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, style = AetherTheme.type.Small, color = colors.text2)
        repeat(3) { index ->
            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (index < filled) colors.brand else colors.line))
        }
    }
}

/**
 * The method chooser as a page, for a TV remote: the same choices as the sheet,
 * which a D-pad cannot reliably reach inside a bottom sheet.
 */
@Composable
internal fun MethodScreen(
    settings: AppSettings,
    support: MethodSheetSupport,
    onSettingsChange: (AppSettings) -> Unit,
    onGoToAdvanced: () -> Unit,
    onBack: () -> Unit,
    onOpenAdvancedGroup: (AdvancedGroup) -> Unit = {},
) {
    ScreenColumn {
        CrumbBar(stringResource(R.string.home_tab_connect), onBack = onBack)
        PageTitle(stringResource(R.string.method_sheet_title))
        SimpleMethodChoices(settings, support, onChange = onSettingsChange, onDone = onBack)
        MoreConnectionSettings(settings, onOpen = onOpenAdvancedGroup, onAll = onGoToAdvanced)
    }
}

/**
 * The settings people ask after, named where the method is chosen.
 *
 * Was one outline button at the foot of the sheet, and "where did the exit
 * chain go" was the question it failed to answer. The two most asked for get
 * their own rows with their state; everything else stays one row away.
 */
@Composable
private fun MoreConnectionSettings(
    settings: AppSettings,
    onOpen: (AdvancedGroup) -> Unit,
    onAll: () -> Unit,
) {
    Spacer(Modifier.height(20.dp))
    SectionLabel(stringResource(R.string.home_method_settings), Modifier.padding(start = 4.dp, bottom = 6.dp))
    AetherCard {
        listOf(AdvancedGroup.NODES, AdvancedGroup.TWO_HOP).forEach { group ->
            val (icon, title, subtitle) = group.presentation(settings)
            RowCard(icon, title, subtitle, Modifier.testTag("method-more-${group.name.lowercase()}"), onClick = { onOpen(group) })
            Divider()
        }
        RowCard(
            AetherIcons.Sliders,
            stringResource(R.string.method_all_advanced),
            stringResource(R.string.method_all_advanced_detail),
            Modifier.testTag("method-more-settings"),
            onClick = onAll,
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
internal fun HomeTrafficStrip(
    traffic: TrafficSample,
    active: Boolean,
    addresses: com.whitedns.whiteaesther.AddressPair = com.whitedns.whiteaesther.AddressPair(),
) {
    val locale = LocalConfiguration.current.locales[0]
    val country = addresses.tunnelCountry?.let { code ->
        countryFlag(code)?.let { "$it ${countryName(code, locale)}" } ?: code
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("home-traffic-strip"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StripRow(
            R.string.download to (if (active && traffic.supported) formatRate(traffic.downloadPerSecond) else "—"),
            R.string.upload to (if (active && traffic.supported) formatRate(traffic.uploadPerSecond) else "—"),
        )
        // Only once connected: before that there is no exit to describe, and
        // two more dashes would only make the screen busier.
        if (active) {
            StripRow(
                // Latin digits: a technical value, as elsewhere in the app.
                R.string.home_ping to (addresses.tunnelLatencyMs
                    ?.let { String.format(java.util.Locale.ROOT, "%d ms", it) } ?: "—"),
                R.string.home_location to (country ?: "—"),
                tag = "home-exit-facts",
            )
        }
    }
}

@Composable
private fun StripRow(vararg cells: Pair<Int, String>, tag: String? = null) {
    val colors = AetherTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        cells.forEach { (label, value) ->
            Column(
                Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(stringResource(label), style = AetherTheme.type.Small, color = colors.text2)
                Text(value, style = AetherTheme.type.Data, color = colors.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
