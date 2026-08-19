package com.whitedns.whiteaesther.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    onBack: () -> Unit,
) {
    val colors = AetherTheme.colors
    val chain = settings.chain
    val connected = status.stage == EngineStage.CONNECTED

    // Read back whenever the chain comes up, since that is the moment there is
    // finally something to read.
    LaunchedEffect(connected, chain.enabled) {
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
            SettingRow(
                title = "Exit chain",
                subtitle = if (chain.enabled) {
                    "Traffic leaves from your node."
                } else {
                    "Traffic leaves from Cloudflare, as normal."
                },
            ) {
                AetherSwitch(
                    checked = chain.enabled,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(chain = chain.copy(enabled = it)))
                    },
                    modifier = Modifier.testTag("chain-switch"),
                )
            }
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
            SettingRow(
                title = "Dial nodes through the tunnel",
                subtitle = if (chain.throughTunnel) {
                    "Your network never learns the node's address, and the node never learns yours."
                } else {
                    "Nodes are reached directly. Use this only where the tunnel itself is blocked."
                },
            ) {
                AetherSwitch(
                    checked = chain.throughTunnel,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(chain = chain.copy(throughTunnel = it)))
                    },
                    modifier = Modifier.testTag("chain-through-tunnel-switch"),
                )
            }
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

    AetherCard {
        CardHead("Where your nodes come from", "A subscription link, or nodes pasted by hand.")

        chain.sources.forEachIndexed { index, source ->
            Divider()
            SettingRow(title = source.name, subtitle = source.url) {
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
                            .clickable {
                                onSettingsChange(
                                    settings.copy(
                                        chain = chain.copy(
                                            sources = chain.sources.filterIndexed { at, _ -> at != index },
                                        ),
                                    ),
                                )
                            }
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
                    .testTag("chain-source-field"),
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
                        .testTag("chain-manual-field"),
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
) {
    val colors = AetherTheme.colors

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
                chainState.nodes.forEach { node ->
                    Divider()
                    NodeRow(
                        name = node.name,
                        kind = node.kind,
                        delay = node.delay,
                        selected = node.name == live,
                        onClick = { onSelectNode(node.name) },
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
                OutlineButton(
                    text = if (chainState.busy) "Testing…" else "Test all",
                    modifier = Modifier.weight(1f).testTag("chain-test-nodes"),
                    enabled = !chainState.busy && chainState.nodes.isNotEmpty(),
                    onClick = onTestNodes,
                )
            }
        }
        Unit
    }
}

@Composable
private fun NodeRow(
    name: String,
    kind: String,
    delay: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp)
            .testTag("chain-node-$name"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            Text(kind, style = AetherType.Small, color = colors.text2)
        }
        Text(
            // A node that has never been tested and one that failed its last
            // test are different, and a dash for both would hide the difference.
            when (delay) {
                null -> "—"
                else -> "$delay ms"
            },
            style = AetherType.Data,
            color = when {
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
