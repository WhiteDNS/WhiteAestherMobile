package com.whitedns.whiteaesther.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.ChainState
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.ChainSource
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.ui.theme.AetherType

/**
 * A one-line description of the chain for the Routes tab.
 *
 * Says what the user gets, not what is configured: "two subscriptions" tells
 * them nothing about whether their traffic is leaving from somewhere else.
 */
fun AppSettings.chainSummary(): String = when {
    !chain.enabled -> "Off. Traffic leaves from Cloudflare"
    !chain.hasNodes -> "On, but no nodes yet"
    chain.throughTunnel -> "On, dialled through the tunnel"
    else -> "On, nodes dialled directly"
}

@Composable
fun ChainScreen(
    settings: AppSettings,
    status: EngineStatus,
    chainState: ChainState,
    onSettingsChange: (AppSettings) -> Unit,
    onRefreshNodes: () -> Unit,
    onSelectNode: (String) -> Unit,
    onTestNodes: () -> Unit,
    onTestSelected: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AetherTheme.colors
    val chain = settings.chain
    val connected = status.stage == EngineStage.CONNECTED

    // Keyed on the sources too, so editing one re-asks rather than leaving the
    // previous subscription's nodes on screen.
    // Keyed on what changes the node list, and nothing else. It used to key on
    // the whole config fingerprint, which now covers the routing rules -- so
    // every keystroke in a rule box restarted a full node load, and each of
    // those is a JNI call plus a read of every cached subscription.
    LaunchedEffect(connected, chain.enabled, chain.nodeSourceFingerprint()) {
        if (connected && chain.enabled) onRefreshNodes()
    }

    ScreenColumn {
        CrumbBar("Routes · Exit chain", onBack = onBack)
        PageTitle(
            "Where your traffic comes out",
            "Adds a second hop after the tunnel, so sites see your node's address instead of Cloudflare's.",
        )

        if (!chainState.available) {
            // The chain is loaded at run time and a build may not ship it. Saying
            // so is the whole point of the check: the alternative is a switch
            // that saves happily and a connect that refuses, with the reason
            // arriving several screens away from the setting that caused it.
            AttentionCard(
                tone = colors.signalFailed,
                title = "Not available in this build",
                body = "This copy of WhiteAesther does not include the exit chain. " +
                    "Everything else works as normal.",
            )
            return@ScreenColumn
        }

        if (chain.enabled && settings.mode != EngineMode.TUN) {
            AttentionCard(
                tone = colors.signalFailed,
                title = "Coverage has to be whole device",
                body = "The chain routes the whole phone. Under Traffic, set Coverage back to " +
                    "Whole device, or turn the chain off.",
            )
            Spacer(Modifier.height(12.dp))
        }

        AetherCard {
            ToggleSettingRow(
                title = "Exit chain",
                subtitle = if (chain.enabled) {
                    "Traffic leaves from your node."
                } else {
                    "Traffic leaves from Cloudflare, as normal."
                },
                checked = chain.enabled,
                onCheckedChange = {
                    onSettingsChange(settings.copy(chain = chain.copy(enabled = it)))
                },
                modifier = Modifier.testTag("chain-switch"),
            )
        }

        if (!chain.enabled) {
            Note(
                "With this off, WhiteAesther works exactly as it does today. Turn it on if you " +
                    "have a subscription or a node of your own, and want sites to see that " +
                    "address rather than Cloudflare's.",
            )
            return@ScreenColumn
        }

        Spacer(Modifier.height(12.dp))
        SourcesCard(settings, onSettingsChange)

        Spacer(Modifier.height(12.dp))
        AetherCard {
            ToggleSettingRow(
                title = "Dial nodes through the tunnel",
                subtitle = if (chain.throughTunnel) {
                    "Your network never learns the node's address, and the node never learns yours."
                } else {
                    "Nodes are reached directly. Use this only where the tunnel itself is blocked."
                },
                checked = chain.throughTunnel,
                onCheckedChange = {
                    onSettingsChange(settings.copy(chain = chain.copy(throughTunnel = it)))
                },
                modifier = Modifier.testTag("chain-through-tunnel-switch"),
            )
        }
        if (!chain.throughTunnel) {
            Note(
                "Dialling directly skips the tunnel entirely -- WhiteAesther becomes a plain " +
                    "client for your node. That is the right choice on a network that blocks " +
                    "the tunnel outright, and the wrong one everywhere else.",
            )
        }

        Spacer(Modifier.height(12.dp))
        NodesCard(
            settings = settings,
            chainState = chainState,
            connected = connected,
            onRefreshNodes = onRefreshNodes,
            onSelectNode = onSelectNode,
            onTestNodes = onTestNodes,
            onTestSelected = onTestSelected,
            onSettingsChange = onSettingsChange,
        )
    }
}

@Composable
private fun SourcesCard(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    val colors = AetherTheme.colors
    val chain = settings.chain
    var draft by rememberSaveable { mutableStateOf("") }
    var pasting by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable(chain.manual) { mutableStateOf(chain.manual) }
    val sourceInteraction = remember { MutableInteractionSource() }
    val manualInteraction = remember { MutableInteractionSource() }

    AetherCard {
        CardHead("Where your nodes come from", "A subscription link, or nodes pasted by hand.")

        chain.sources.forEachIndexed { index, source ->
            Divider()
            SettingRow(title = source.name, subtitle = source.url) {
                val removeInteraction = remember(source.url) { MutableInteractionSource() }
                val removeSource = {
                    onSettingsChange(
                        settings.copy(
                            chain = chain.copy(
                                sources = chain.sources.filterIndexed { at, _ -> at != index },
                            ),
                        ),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AetherSwitch(
                        checked = source.enabled,
                        onCheckedChange = { enabled ->
                            onSettingsChange(
                                settings.copy(
                                    chain = chain.copy(
                                        sources = chain.sources.toMutableList().also {
                                            it[index] = source.copy(enabled = enabled)
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .border(1.dp, colors.line, CircleShape)
                            .tvControllerActivation(onClick = removeSource)
                            .clickable(
                                removeInteraction,
                                LocalIndication.current,
                                onClick = removeSource,
                            )
                            .controllerFocus(removeInteraction, CircleShape)
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text("Remove", style = AetherType.Small, color = colors.text3)
                    }
                }
            }
        }

        Divider()
        Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
            SectionLabel("Add a subscription")
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(512) },
                modifier = Modifier
                    .fillMaxWidth()
                    .tvTextFieldSupport(sourceInteraction)
                    .testTag("chain-source-field"),
                interactionSource = sourceInteraction,
                placeholder = {
                    Text("https://…", style = AetherType.Data, color = colors.text3)
                },
                textStyle = AetherType.Data.copy(color = colors.text),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.ink1,
                    unfocusedContainerColor = colors.ink1,
                ),
            )
            Spacer(Modifier.height(9.dp))
            PrimaryButton(
                text = "Add",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chain-add-source"),
                enabled = draft.isValidSubscription(),
            ) {
                onSettingsChange(
                    settings.copy(
                        chain = chain.copy(
                            sources = chain.sources + ChainSource(draft.subscriptionName(), draft.trim()),
                        ),
                    ),
                )
                draft = ""
            }
            if (draft.isNotBlank() && !draft.isValidSubscription()) {
                Note("A subscription is an https:// link your provider gave you.")
            }
        }

        Divider()
        RowCard(
            icon = AetherIcons.Copy,
            title = "Paste nodes by hand",
            subtitle = when (val count = chain.manual.lines().count { it.isNotBlank() }) {
                0 -> "None. One link per line: vless, vmess, trojan, ss, hysteria2"
                1 -> "1 node"
                else -> "$count nodes"
            },
            onClick = { pasting = !pasting },
        )
        if (pasting) {
            Column(Modifier.padding(horizontal = 15.dp).padding(bottom = 12.dp)) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it.take(20_000) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .tvTextFieldSupport(manualInteraction)
                        .testTag("chain-manual-field"),
                    interactionSource = manualInteraction,
                    placeholder = {
                        Text("vless://…", style = AetherType.Data, color = colors.text3)
                    },
                    textStyle = AetherType.Data.copy(color = colors.text),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.ink1,
                        unfocusedContainerColor = colors.ink1,
                    ),
                )
                Spacer(Modifier.height(9.dp))
                OutlineButton(
                    text = "Save nodes",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = manual != chain.manual,
                ) {
                    onSettingsChange(settings.copy(chain = chain.copy(manual = manual)))
                    pasting = false
                }
            }
        }
    }
}

@Composable
private fun NodesCard(
    settings: AppSettings,
    chainState: ChainState,
    connected: Boolean,
    onRefreshNodes: () -> Unit,
    onSelectNode: (String) -> Unit,
    onTestNodes: () -> Unit,
    onTestSelected: (List<String>) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val colors = AetherTheme.colors
    // Which rows are ticked. Held here and not persisted: a selection is about
    // what the user is doing right now, and one restored from a previous visit
    // would be a set of ticks nobody remembers making.
    var picked by remember { mutableStateOf(setOf<String>()) }
    // Remembered, so every row is not handed a freshly built lambda on each
    // recomposition. A new one per row makes every row's inputs look changed,
    // and ticking one box redrew all fifty.
    val togglePick = remember {
        { name: String ->
            picked = if (name in picked) picked - name else picked + name
        }
    }

    AetherCard {
        CardHead("Nodes", "Whichever one is ticked is carrying your traffic.")

        when {
            !connected -> {
                Divider()
                Note(
                    "Connect to load your nodes. The list comes from the running chain, and " +
                        "your subscription is fetched through the tunnel -- so there is nothing " +
                        "to read until it is up.",
                    modifier = Modifier.padding(horizontal = 15.dp),
                )
            }

            chainState.stale -> {
                Divider()
                // The running engine still has the old subscription. Showing its
                // nodes here is what reads as "deleting it did nothing", so say
                // what is actually true instead.
                Note(
                    "Your sources changed. The chain is still running the previous ones -- " +
                        "disconnect and connect again to load them.",
                    modifier = Modifier.padding(horizontal = 15.dp),
                )
            }

            chainState.nodes.isEmpty() -> {
                Divider()
                Note(
                    if (chainState.busy) {
                        "Reading the node list…"
                    } else {
                        "The chain is up but reported no nodes. Check that the subscription " +
                            "link is right, and look under Settings · Diagnostics for what " +
                            "it said."
                    },
                    modifier = Modifier.padding(horizontal = 15.dp),
                )
            }

            else -> {
                // The engine's own answer, not the saved preference: a node that
                // was dropped from the subscription leaves the two disagreeing,
                // and what is actually carrying traffic is the true one.
                val live = chainState.selected ?: settings.chain.node
                // Fastest first, then the untested, then the ones this build
                // cannot dial. A subscription arrives in whatever order it was
                // written, which on a list of fifty is no order at all -- and
                // the number the user is choosing by is already on every row.
                val hidden = settings.chain.hiddenNodes.toSet()
                NodeActions(
                    picked = picked,
                    busy = chainState.busy,
                    total = chainState.nodes.count { it.name !in hidden },
                    hiddenCount = hidden.size,
                    onTestAll = onTestNodes,
                    onTestPicked = { onTestSelected(picked.toList()) },
                    onHidePicked = {
                        onSettingsChange(
                            settings.copy(
                                chain = settings.chain.copy(
                                    hiddenNodes = (settings.chain.hiddenNodes + picked).distinct(),
                                ),
                            ),
                        )
                        picked = emptySet()
                    },
                    onRestoreHidden = {
                        onSettingsChange(
                            settings.copy(chain = settings.chain.copy(hiddenNodes = emptyList())),
                        )
                    },
                )

                val ordered = remember(chainState.nodes, hidden) {
                    chainState.nodes
                        .filterNot { it.name in hidden }
                        .sortedWith(
                            compareBy(
                                { !it.supported },
                                { it.delay == null },
                                { it.delay ?: Int.MAX_VALUE },
                                { it.name },
                            ),
                        )
                }
                ordered.forEach { node ->
                    Divider()
                    NodeRow(
                        name = node.name,
                        kind = node.kind,
                        delay = node.delay,
                        selected = node.name == live,
                        supported = node.supported,
                        onClick = { onSelectNode(node.name) },
                        picked = node.name in picked,
                        onPickedChange = togglePick,
                    )
                }
            }
        }

        if (chainState.error != null) {
            Divider()
            Note(chainState.error, modifier = Modifier.padding(horizontal = 15.dp))
        }

        if (connected) {
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(15.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlineButton(
                    text = "Refresh",
                    modifier = Modifier.weight(1f),
                    enabled = !chainState.busy,
                    onClick = onRefreshNodes,
                )
            }
        }
        Unit
    }
}

/**
 * What can be done to the list, above the list.
 *
 * Above rather than below because on a subscription of fifty the buttons were
 * a scroll away from the rows they act on, and a user testing nodes had to
 * travel to the end of the list to press the thing that tests them.
 */
@Composable
private fun NodeActions(
    picked: Set<String>,
    busy: Boolean,
    total: Int,
    hiddenCount: Int,
    onTestAll: () -> Unit,
    onTestPicked: () -> Unit,
    onHidePicked: () -> Unit,
    onRestoreHidden: () -> Unit,
) {
    val colors = AetherTheme.colors
    Divider()
    Column(
        Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlineButton(
                text = if (busy) "Testing…" else "Test all",
                modifier = Modifier.weight(1f).testTag("chain-test-nodes"),
                enabled = !busy && total > 0,
                onClick = onTestAll,
            )
            OutlineButton(
                // Named with the count, because "Test selected" on an empty
                // selection is a button that looks available and does nothing.
                text = if (picked.isEmpty()) "Test selected" else "Test ${picked.size}",
                modifier = Modifier.weight(1f).testTag("chain-test-selected"),
                enabled = !busy && picked.isNotEmpty(),
                onClick = onTestPicked,
            )
        }

        if (picked.isNotEmpty()) {
            OutlineButton(
                text = "Remove ${picked.size} from the list",
                modifier = Modifier.fillMaxWidth().testTag("chain-hide-selected"),
                enabled = !busy,
                onClick = onHidePicked,
            )
        }

        if (hiddenCount > 0) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // Said out loud, because a node missing from a subscription
                    // with no explanation is what a broken link looks like.
                    "$hiddenCount removed from the list",
                    style = AetherType.Small,
                    color = colors.text2,
                )
                OutlineButton(
                    text = "Restore",
                    modifier = Modifier.testTag("chain-restore-hidden"),
                    enabled = !busy,
                    onClick = onRestoreHidden,
                )
            }
        }
    }
}

@Composable
private fun NodeRow(
    name: String,
    kind: String,
    delay: Int?,
    selected: Boolean,
    supported: Boolean,
    onClick: () -> Unit,
    picked: Boolean,
    onPickedChange: (String) -> Unit,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            // Listed but not selectable. Choosing it would start a chain that
            // cannot authenticate, and the failure would look like the node.
            .tvControllerActivation(enabled = supported, onClick = onClick)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = supported,
                onClick = onClick,
            )
            .controllerFocus(interaction, shape, supported)
            .padding(horizontal = 15.dp, vertical = 13.dp)
            .testTag("chain-node-$name"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The tick picks the row for an action; tapping the rest of the row is
        // still what switches the live node. Two different questions, so two
        // different targets rather than one control that means both.
        Checkbox(
            checked = picked,
            onCheckedChange = { onPickedChange(name) },
            modifier = Modifier.testTag("chain-pick-$name"),
            colors = CheckboxDefaults.colors(
                checkedColor = colors.brand,
                uncheckedColor = colors.text3,
                checkmarkColor = colors.onBrand,
            ),
        )
        Icon(
            if (selected) AetherIcons.Check else AetherIcons.Globe,
            null,
            Modifier.size(18.dp),
            if (selected) colors.brand else colors.text3,
        )
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = AetherType.RowTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (supported) kind else "$kind · not supported",
                style = AetherType.Small,
                color = if (supported) colors.text2 else colors.signalWorking,
            )
            if (!supported) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "This build's engine cannot authenticate with REALITY yet. The node " +
                        "is fine and will work here again once the engine can.",
                    style = AetherType.Small,
                    color = colors.text3,
                )
            }
        }
        Text(
            // A node that has never been tested and one that failed its last
            // test are different, and a dash for both would hide the difference.
            when {
                !supported -> "—"
                else -> when (delay) {
                    null -> "—"
                    else -> "$delay ms"
                }
            },
            style = AetherType.Data,
            color = when {
                !supported -> colors.text3
                delay == null -> colors.text3
                delay < 400 -> colors.signalLive
                delay < 1_200 -> colors.cyan
                else -> colors.signalFailed
            },
        )
    }
}

/**
 * Only http(s). mihomo will also take a file path, which on a phone would be a
 * path the app cannot read -- accepting it produces a config that loads and a
 * provider that never has any nodes.
 */
private fun String.isValidSubscription(): Boolean {
    val value = trim()
    return (value.startsWith("https://") || value.startsWith("http://")) && value.length > 10
}

/** The host, so a list of subscriptions is tellable apart at a glance. */
private fun String.subscriptionName(): String = trim()
    .substringAfter("://")
    .substringBefore('/')
    .substringBefore(':')
    .ifBlank { "Subscription" }
