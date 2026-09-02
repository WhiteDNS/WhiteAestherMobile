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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.ChainState
import com.whitedns.whiteaesther.R
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
@Composable
fun AppSettings.chainSummary(): String = when {
    !chain.enabled -> stringResource(R.string.off_traffic_leaves_from_cloudflare)
    !chain.hasNodes -> stringResource(R.string.on_but_no_nodes_yet)
    chain.throughTunnel -> stringResource(R.string.on_dialled_through_the_tunnel)
    else -> stringResource(R.string.on_nodes_dialled_directly)
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
    onCancelTests: () -> Unit,
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
        CrumbBar(stringResource(R.string.routes_exit_chain), onBack = onBack)
        PageTitle(
            stringResource(R.string.where_your_traffic_comes_out),
            stringResource(R.string.adds_a_second_hop_after_the_tunnel),
        )

        if (!chainState.available) {
            // The chain is loaded at run time and a build may not ship it. Saying
            // so is the whole point of the check: the alternative is a switch
            // that saves happily and a connect that refuses, with the reason
            // arriving several screens away from the setting that caused it.
            AttentionCard(
                tone = colors.signalFailed,
                title = stringResource(R.string.not_available_in_this_build),
                body = stringResource(R.string.this_copy_of_whiteaesther_does_not_include),
            )
            return@ScreenColumn
        }

        if (chain.enabled && settings.mode != EngineMode.TUN) {
            AttentionCard(
                tone = colors.signalFailed,
                title = stringResource(R.string.coverage_has_to_be_whole_device),
                body = stringResource(R.string.the_chain_routes_the_whole_phone_under),
            )
            Spacer(Modifier.height(12.dp))
        }

        AetherCard {
            ToggleSettingRow(
                title = stringResource(R.string.exit_chain),
                subtitle = if (chain.enabled) {
                    stringResource(R.string.traffic_leaves_from_your_node)
                } else {
                    stringResource(R.string.traffic_leaves_from_cloudflare_as_normal)
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
                stringResource(R.string.with_this_off_whiteaesther_works_exactly_as),
            )
            return@ScreenColumn
        }

        Spacer(Modifier.height(12.dp))
        SourcesCard(settings, onSettingsChange)

        Spacer(Modifier.height(12.dp))
        AetherCard {
            ToggleSettingRow(
                title = stringResource(R.string.dial_nodes_through_the_tunnel),
                subtitle = if (chain.throughTunnel) {
                    stringResource(R.string.your_network_never_learns_the_node_s)
                } else {
                    stringResource(R.string.nodes_are_reached_directly_use_this_only)
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
                stringResource(R.string.dialling_directly_skips_the_tunnel_entirely_whit),
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
            onCancelTests = onCancelTests,
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
        CardHead(stringResource(R.string.where_your_nodes_come_from), stringResource(R.string.a_subscription_link_or_nodes_pasted_by))

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
                        Text(stringResource(R.string.remove), style = AetherType.Small, color = colors.text3)
                    }
                }
            }
        }

        Divider()
        Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
            SectionLabel(stringResource(R.string.add_a_subscription))
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
                    Text(stringResource(R.string.https), style = AetherType.Data, color = colors.text3)
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
                text = stringResource(R.string.add),
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
                Note(stringResource(R.string.a_subscription_is_an_https_link_your))
            }
        }

        Divider()
        RowCard(
            icon = AetherIcons.Copy,
            title = stringResource(R.string.paste_nodes_by_hand),
            subtitle = when (val count = chain.manual.lines().count { it.isNotBlank() }) {
                0 -> stringResource(R.string.none_one_link_per_line_vless_vmess)
                1 -> stringResource(R.string.t_1_node)
                else -> stringResource(R.string.count_nodes, count)
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
                        Text(stringResource(R.string.vless), style = AetherType.Data, color = colors.text3)
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
                    text = stringResource(R.string.save_nodes),
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
    onCancelTests: () -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val colors = AetherTheme.colors
    // Which rows are ticked. Held here and not persisted: a selection is about
    // what the user is doing right now, and one restored from a previous visit
    // would be a set of ticks nobody remembers making.
    var picked by remember { mutableStateOf(setOf<String>()) }
    // A thousand nodes is not a list anybody scrolls. Held here rather than
    // saved: it is a way of finding one node now, not a preference.
    var query by remember { mutableStateOf("") }
    // Remembered, so every row is not handed a freshly built lambda on each
    // recomposition. A new one per row makes every row's inputs look changed,
    // and ticking one box redrew all fifty.
    val togglePick = remember {
        { name: String ->
            picked = if (name in picked) picked - name else picked + name
        }
    }

    AetherCard {
        CardHead(stringResource(R.string.nodes), stringResource(R.string.whichever_one_is_ticked_is_carrying_your))

        when {
            !connected -> {
                Divider()
                Note(
                    stringResource(R.string.connect_to_load_your_nodes_the_list),
                    modifier = Modifier.padding(horizontal = 15.dp),
                )
            }

            chainState.stale -> {
                Divider()
                // The running engine still has the old subscription. Showing its
                // nodes here is what reads as "deleting it did nothing", so say
                // what is actually true instead.
                Note(
                    stringResource(R.string.your_sources_changed_the_chain_is_still),
                    modifier = Modifier.padding(horizontal = 15.dp),
                )
            }

            chainState.nodes.isEmpty() -> {
                Divider()
                Note(
                    if (chainState.busy) {
                        stringResource(R.string.reading_the_node_list)
                    } else {
                        stringResource(R.string.the_chain_is_up_but_reported_no)
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
                NodeSearch(query = query, onQueryChange = { query = it })
                NodeActions(
                    picked = picked,
                    busy = chainState.busy,
                    progress = chainState.testProgress,
                    total = chainState.nodes.count { it.name !in hidden },
                    hiddenCount = hidden.size,
                    onTestAll = onTestNodes,
                    onCancelTests = onCancelTests,
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

                val ordered = remember(chainState.nodes, hidden, query) {
                    chainState.nodes
                        .filterNot { it.name in hidden }
                        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                        .sortedWith(
                            compareBy(
                                { !it.supported },
                                { it.delay == null },
                                { it.delay ?: Int.MAX_VALUE },
                                { it.name },
                            ),
                        )
                }
                // Bounded height and lazy. A subscription can carry a thousand
                // nodes, and a plain Column composes and measures every row
                // whether or not it is on screen -- which is the freeze people
                // hit the moment the list arrived. A fixed height is what lets
                // it be lazy at all: nested inside the page's own scroll, an
                // unbounded LazyColumn has infinite space to fill and would
                // compose everything anyway.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(ordered, key = { it.name }) { node ->
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
                    text = stringResource(R.string.refresh),
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
private fun NodeSearch(query: String, onQueryChange: (String) -> Unit) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Divider()
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 11.dp)
            .testTag("chain-node-search")
            .tvTextFieldSupport(interaction),
        interactionSource = interaction,
        textStyle = AetherType.Data.copy(color = colors.text),
        placeholder = { Text(stringResource(R.string.find_a_node), style = AetherType.Data, color = colors.text3) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.ink1,
            unfocusedContainerColor = colors.ink1,
            errorContainerColor = colors.ink1,
        ),
    )
}

@Composable
private fun NodeActions(
    picked: Set<String>,
    busy: Boolean,
    progress: Pair<Int, Int>?,
    total: Int,
    hiddenCount: Int,
    onTestAll: () -> Unit,
    onCancelTests: () -> Unit,
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
                // Named with the count while running. A thousand nodes take
                // minutes, and a spinner with no number is indistinguishable
                // from a run that has died -- which is what people reported.
                text = when {
                    progress != null -> "Stop (${progress.first}/${progress.second})"
                    busy -> "Testing…"
                    else -> stringResource(R.string.test_all)
                },
                modifier = Modifier.weight(1f).testTag("chain-test-nodes"),
                enabled = progress != null || (!busy && total > 0),
                onClick = if (progress != null) onCancelTests else onTestAll,
            )
            OutlineButton(
                // Named with the count, because "Test selected" on an empty
                // selection is a button that looks available and does nothing.
                text = if (picked.isEmpty()) stringResource(R.string.test_selected) else "Test ${picked.size}",
                modifier = Modifier.weight(1f).testTag("chain-test-selected"),
                enabled = !busy && picked.isNotEmpty(),
                onClick = onTestPicked,
            )
        }

        if (picked.isNotEmpty()) {
            OutlineButton(
                text = stringResource(R.string.remove_picked_size_from_the_list, picked.size),
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
                    stringResource(R.string.hiddencount_removed_from_the_list, hiddenCount),
                    style = AetherType.Small,
                    color = colors.text2,
                )
                OutlineButton(
                    text = stringResource(R.string.restore),
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
        val pickInteraction = remember { MutableInteractionSource() }
        Checkbox(
            checked = picked,
            onCheckedChange = { onPickedChange(name) },
            // The same treatment every other control in this app has. A raw
            // Material checkbox takes D-pad centre but not a gamepad's A
            // button, and shows no focus ring -- on a television that is a
            // control the remote can land on with nothing to say it has.
            modifier = Modifier
                .testTag("chain-pick-$name")
                .tvControllerActivation { onPickedChange(name) }
                .controllerFocus(pickInteraction, RoundedCornerShape(8.dp)),
            interactionSource = pickInteraction,
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
                if (supported) kind else stringResource(R.string.kind_not_supported, kind),
                style = AetherType.Small,
                color = if (supported) colors.text2 else colors.signalWorking,
            )
            if (!supported) {
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.this_build_s_engine_cannot_authenticate_with),
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
                    else -> stringResource(R.string.result_rttmillis_ms, delay)
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
