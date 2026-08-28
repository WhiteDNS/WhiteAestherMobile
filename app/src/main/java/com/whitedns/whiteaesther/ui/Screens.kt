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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedns.whiteaesther.EndpointOperation
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.IdentityMessage
import com.whitedns.whiteaesther.AddressPair
import com.whitedns.whiteaesther.data.UpdateChecker
import com.whitedns.whiteaesther.service.TrafficSample
import com.whitedns.whiteaesther.service.formatBytes
import com.whitedns.whiteaesther.service.formatRate
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.LanNoticeLevel
import com.whitedns.whiteaesther.data.LocalAddress
import com.whitedns.whiteaesther.data.EndpointAddress
import com.whitedns.whiteaesther.data.EndpointFamily
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.data.ScanStrategy
import com.whitedns.whiteaesther.data.ThemeMode
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.LogEntry
import com.whitedns.whiteaesther.service.LogLevel
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.ui.theme.AetherType
import kotlinx.coroutines.delay

// -------------------------------------------------------------- profiles ----

/**
 * A profile is a preset over the settings the engine actually takes.
 *
 * All four preset to MASQUE. WireGuard is deliberately not behind a profile:
 * it uses a separate account and a separate set of endpoints, so the first
 * connect after switching has its own provisioning and its own scan to do.
 * That is a choice worth making knowingly, under Manual, rather than something
 * a friendly-sounding preset does on the user's behalf.
 */
enum class ConnectionProfile(
    val label: String,
    val description: String,
    val tag: String?,
    val scan: ScanStrategy?,
    val transport: TunnelProtocol?,
) {
    ADAPTIVE("Adaptive", "Works on most networks. Start here.", "Recommended", ScanStrategy.BALANCED, TunnelProtocol.AUTO),
    PATCHY("Patchy signal", "For mobile data that keeps dropping.", null, ScanStrategy.THOROUGH, TunnelProtocol.AUTO),
    STRICT("Strict network", "For Wi-Fi that blocks a lot, such as an office.", null, ScanStrategy.STEALTH, TunnelProtocol.H2),
    MANUAL("Manual", "You choose every setting yourself.", null, null, null),
    ;

    val icon: ImageVector
        get() = when (this) {
            ADAPTIVE -> AetherIcons.Sparkle
            PATCHY -> AetherIcons.Pulse
            STRICT -> AetherIcons.Lock
            MANUAL -> AetherIcons.Sliders
        }
}

fun AppSettings.activeProfile(): ConnectionProfile =
    ConnectionProfile.entries.firstOrNull {
        it.scan == scanStrategy && it.transport == transport
    } ?: ConnectionProfile.MANUAL

fun AppSettings.applyProfile(profile: ConnectionProfile): AppSettings =
    if (profile.scan == null || profile.transport == null) {
        this
    } else {
        copy(scanStrategy = profile.scan, transport = profile.transport)
    }

fun AppSettings.endpointSummary(): String = when (endpointMode) {
    EndpointMode.AUTOMATIC -> "Chosen automatically"
    else -> EndpointAddress.normalize(customEndpoint)?.let {
        if (endpointMode == EndpointMode.CUSTOM_FIRST) it else "$it · no fallback"
    } ?: "Specific address · not set yet"
}

fun EngineStage.toConnectState(): ConnectState = when (this) {
    EngineStage.IDLE -> ConnectState.IDLE
    EngineStage.PREPARING, EngineStage.CONNECTING, EngineStage.STOPPING -> ConnectState.WORKING
    EngineStage.CONNECTED -> ConnectState.LIVE
    EngineStage.ERROR -> ConnectState.FAILED
}

/** The scrolling page body every screen sits in. */
@Composable
internal fun ScreenColumn(content: @Composable ColumnScopeAlias.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 26.dp)
            // Inside the scroll, so the keyboard adds room to scroll into rather
            // than shrinking the page. Without it the control below a field is
            // underneath the keyboard the field just opened -- which on the exit
            // chain is the button that saves what was typed.
            .imePadding(),
        content = content,
    )
}

// ------------------------------------------------------------------ home ----

@Composable
fun HomeScreen(
    settings: AppSettings,
    status: EngineStatus,
    addresses: AddressPair,
    traffic: TrafficSample,
    chainSelection: String?,
    update: UpdateChecker.Available?,
    onLiftBlock: () -> Unit,
    onOpenUpdate: (String) -> Unit,
    onDismissUpdate: () -> Unit,
    onToggleConnection: () -> Unit,
    onGoToRoutes: () -> Unit,
    onGoToEndpoint: () -> Unit,
    onGoToTraffic: () -> Unit,
    connectModifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = AetherTheme.colors
    val state = status.stage.toConnectState()

    // Measured from the moment the service recorded, not from when this screen
    // first saw it, so rotating the device or returning to a recreated activity
    // does not restart a tunnel that has been up for an hour.
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(status.connectedAtMillis) {
        val since = status.connectedAtMillis
        if (since == null) {
            elapsed = 0
            return@LaunchedEffect
        }
        while (true) {
            elapsed = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }

    ScreenColumn {
        Row(
            Modifier
                .fillMaxWidth()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(AetherIcons.Globe, null, Modifier.size(30.dp), colors.brand)
            Text("WhiteAesther", style = AetherType.CardTitle, color = colors.text)
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectOrb(
                state = state,
                modifier = connectModifier,
                diameter = if (compact) 210.dp else 262.dp,
                enabled = status.stage != EngineStage.STOPPING,
                caption = when (status.stage) {
                    EngineStage.IDLE -> "Tap to connect"
                    EngineStage.PREPARING, EngineStage.CONNECTING -> "Tap to cancel"
                    EngineStage.CONNECTED -> "Tap to stop"
                    EngineStage.STOPPING -> "Stopping"
                    EngineStage.ERROR -> "Tap to retry"
                },
                onClick = onToggleConnection,
            )
        }

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val signal = when (state) {
                ConnectState.IDLE -> colors.signalIdle
                ConnectState.WORKING -> colors.signalWorking
                ConnectState.LIVE -> colors.signalLive
                ConnectState.FAILED -> colors.signalFailed
            }
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(signal.copy(alpha = 0.09f))
                    .border(1.dp, signal.copy(alpha = 0.34f), CircleShape)
                    .padding(start = 10.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(signal),
                )
                Text(
                    when (status.stage) {
                        EngineStage.IDLE -> "NOT CONNECTED"
                        EngineStage.PREPARING -> "PREPARING"
                        EngineStage.CONNECTING -> "CONNECTING"
                        EngineStage.CONNECTED -> "CONNECTED"
                        EngineStage.STOPPING -> "STOPPING"
                        EngineStage.ERROR -> "NOT CONNECTED"
                    },
                    style = AetherType.Label,
                    color = signal,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (status.stage) {
                    EngineStage.IDLE -> "Ready when you are"
                    EngineStage.PREPARING, EngineStage.CONNECTING ->
                        if (status.message.contains("retry")) "Still trying" else "Finding a working route"
                    EngineStage.CONNECTED -> "You're connected"
                    EngineStage.STOPPING -> "Disconnecting"
                    EngineStage.ERROR -> "Couldn't connect"
                },
                style = AetherType.StatusHead,
                color = colors.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            // The engine's own words -- never a number this app invented.
            Text(
                status.message.ifBlank { "Ready" },
                style = AetherType.Body,
                color = if (status.stage == EngineStage.ERROR) colors.signalFailed else colors.text2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))

        val attention = homeAttention(settings, status)
        if (attention != null) {
            AttentionCard(
                tone = attention.tone,
                title = attention.title,
                body = attention.body,
                actions = attention.actions.map { (label, target) ->
                    label to {
                        when (target) {
                            AttentionTarget.ROUTES -> onGoToRoutes()
                            AttentionTarget.ENDPOINT -> onGoToEndpoint()
                            AttentionTarget.TRAFFIC -> onGoToTraffic()
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        if (status.stage == EngineStage.CONNECTED) {
            AetherCard {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    SectionLabel("Connected for")
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "%02d:%02d:%02d".format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60),
                        style = AetherType.DataLarge,
                        color = colors.text,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Only route out of a blocked phone. Reached from the notification
        // too, but a user who opens the app first should not have to find the
        // notification again to undo something the app is doing.
        if (status.message == "Traffic is blocked") {
            AetherCard {
                CardHead(
                    "Traffic is blocked",
                    "Nothing reaches the internet until you connect again or lift this.",
                )
                Box(Modifier.padding(11.dp)) {
                    OutlineButton(
                        text = "Lift the block",
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lift-block-button"),
                        onClick = onLiftBlock,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (update != null) {
            AetherCard {
                CardHead(
                    "Version ${update.version} is out",
                    "You are on ${com.whitedns.whiteaesther.BuildConfig.VERSION_NAME}.",
                )
                Column(
                    Modifier.padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton(
                        text = "Open the download page",
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("update-open-button"),
                        onClick = { onOpenUpdate(update.url) },
                    )
                    OutlineButton(
                        text = "Not now",
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("update-dismiss-button"),
                        onClick = onDismissUpdate,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (status.stage == EngineStage.CONNECTED || addresses.real != null) {
            AetherCard {
                CardHead("Your address")
                Spacer(Modifier.height(6.dp))
                FactRow(
                    "Without the tunnel",
                    // Absent until the app has been open while disconnected:
                    // reading it during a session would send the real address
                    // out past the thing hiding it.
                    addresses.real ?: "Not measured yet",
                    mono = addresses.real != null,
                )
                Divider()
                val viaChain = settings.chain.enabled && settings.mode == EngineMode.TUN
                FactRow(
                    "Seen by websites",
                    when {
                        status.stage != EngineStage.CONNECTED -> "Not connected"
                        // The chain's exit cannot be measured from here: this
                        // process is kept off its own interface while mihomo
                        // runs, so a probe leaves by the physical network and
                        // would report the address the tunnel hides.
                        viaChain -> chainSelection ?: "Your exit chain node"
                        addresses.tunnel != null -> addresses.tunnel
                        else -> "Checking"
                    },
                    mono = !viaChain && addresses.tunnel != null,
                )
                if (viaChain) {
                    Text(
                        "Traffic leaves through your exit chain, so the address is your " +
                            "node's. The app cannot read it from here without routing its " +
                            "own traffic back into the chain.",
                        style = AetherType.Small,
                        color = colors.text3,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (status.stage == EngineStage.CONNECTED || traffic.received > 0 || traffic.sent > 0) {
            AetherCard {
                CardHead("This session")
                Spacer(Modifier.height(6.dp))
                if (!traffic.supported) {
                    Text(
                        "This phone does not keep per-app byte counters, so there is " +
                            "nothing to measure.",
                        style = AetherType.Small,
                        color = colors.text2,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 15.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(15.dp),
                    ) {
                        RateColumn(
                            label = "Download",
                            rate = formatRate(traffic.downloadPerSecond),
                            total = formatBytes(traffic.received),
                            tint = colors.signalLive,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("traffic-download"),
                        )
                        RateColumn(
                            label = "Upload",
                            rate = formatRate(traffic.uploadPerSecond),
                            total = formatBytes(traffic.sent),
                            tint = colors.cyan,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("traffic-upload"),
                        )
                    }
                    Divider()
                    Text(
                        "Measured on the encrypted side, which is what your data plan is " +
                            "billed for, so it runs a little above what an app reports.",
                        style = AetherType.Small,
                        color = colors.text3,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        AetherCard {
            if (status.stage == EngineStage.CONNECTED) {
                FactRow("Transport", settings.transport.label)
                Divider()
                FactRow("Gateway", status.peer ?: "Negotiating", mono = true)
                Divider()
                FactRow("Coverage", settings.coverageSummary())
                Divider()
                FactRow("Addresses", if (settings.dualStack) "IPv4 + IPv6" else "IPv4 only")
                if (settings.mode == EngineMode.PROXY) {
                    Divider()
                    FactRow("Local proxy", settings.proxyBindLabel(), mono = true)
                }
            } else {
                FactRow("Profile", settings.activeProfile().label)
                Divider()
                FactRow(
                    "Endpoint",
                    settings.endpointSummary(),
                    mono = settings.endpointMode != EndpointMode.AUTOMATIC,
                )
                Divider()
                FactRow("Coverage", settings.coverageSummary())
            }
        }
        Note(
            if (status.stage == EngineStage.CONNECTED) {
                "Live values. Change them under Routes and Traffic."
            } else {
                "What will be used when you connect. Change it under Routes and Traffic."
            },
        )
    }
}

private enum class AttentionTarget { ROUTES, ENDPOINT, TRAFFIC }

private data class Attention(
    val tone: Color,
    val title: String,
    val body: String,
    val actions: List<Pair<String, AttentionTarget>>,
)

@Composable
private fun homeAttention(settings: AppSettings, status: EngineStatus): Attention? {
    val colors = AetherTheme.colors
    val validationError = settings.endpointValidationError()
    // peerFallback lets the engine discard a pinned address and scan instead.
    // It reports the peer it settled on, so the substitution is visible in the
    // facts -- but silently, which reads as the setting being ignored.
    val pinned = if (settings.endpointMode != EndpointMode.AUTOMATIC) {
        EndpointAddress.normalize(settings.customEndpoint)
    } else {
        null
    }
    val substituted = status.stage == EngineStage.CONNECTED &&
        pinned != null && status.peer != null && status.peer != pinned
    val retrying = status.stage == EngineStage.CONNECTING && status.message.contains("retry")
    return when {
        substituted -> Attention(
            tone = colors.cyan,
            title = "Connected to a different endpoint",
            body = "$pinned did not work, so fallback used ${status.peer} instead. " +
                "Turn off Fall back automatically if you would rather it failed than substituted.",
            actions = listOf("Endpoint settings" to AttentionTarget.ENDPOINT),
        )
        // The service retries a failed session forever on a fixed delay. Without
        // saying so, an endless spinner looks the same as normal progress.
        retrying -> Attention(
            tone = colors.signalWorking,
            title = "The engine keeps retrying",
            body = status.message.substringBefore(" · retry").ifBlank { status.message } +
                ". " + status.message.substringAfter(" · ", "").replaceFirstChar(Char::uppercase) + ".",
            actions = listOf("Change profile" to AttentionTarget.ROUTES, "Pin an endpoint" to AttentionTarget.ENDPOINT),
        )
        status.stage == EngineStage.ERROR -> Attention(
            tone = colors.signalFailed,
            title = "The last attempt failed",
            body = status.message.ifBlank { "The engine stopped without a working route." },
            actions = listOf("Change profile" to AttentionTarget.ROUTES, "Pin an endpoint" to AttentionTarget.ENDPOINT),
        )
        validationError != null -> Attention(
            tone = colors.signalWorking,
            title = "Endpoint address is incomplete",
            body = validationError,
            actions = listOf("Finish setting it" to AttentionTarget.ENDPOINT),
        )
        // Ranked above the quieter warnings below because the consequence is the
        // same as not being connected, for every app outside the rule -- and
        // nothing else on this screen would tell the user that.
        settings.coverageIsRestricted() -> Attention(
            tone = colors.cyan,
            title = "Only some apps are going through",
            body = "A per-app rule is limiting this to ${settings.coverageSummary().lowercase()}. " +
                "Everything else is using your real address, connected or not.",
            actions = listOf("Change which apps" to AttentionTarget.TRAFFIC),
        )
        settings.mode == EngineMode.PROXY -> Attention(
            tone = colors.cyan,
            title = "Proxy only: apps are not routed for you",
            body = "Nothing goes through the tunnel unless you point an app at " +
                "${settings.proxyBindLabel()}. Choose Whole device under Traffic to cover everything.",
            actions = listOf("Change coverage" to AttentionTarget.TRAFFIC),
        )
        settings.endpointMode == EndpointMode.CUSTOM_ONLY -> Attention(
            tone = colors.signalWorking,
            title = "Fallback is off",
            body = "Only the address you pinned will be used. If it stops working the connection fails instead of finding another.",
            actions = listOf("Change this" to AttentionTarget.ENDPOINT),
        )
        else -> null
    }
}

// ---------------------------------------------------------------- routes ----

@Composable
fun RoutesScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onGoToEndpoint: () -> Unit,
    onGoToChain: () -> Unit = {},
    onGoToRoutingRules: () -> Unit = {},
    endpointModifier: Modifier = Modifier,
    chainModifier: Modifier = Modifier,
    routingRulesModifier: Modifier = Modifier,
) {
    var advanced by rememberSaveable(settings.showAdvanced) { mutableStateOf(settings.showAdvanced) }
    val active = settings.activeProfile()

    ScreenColumn {
        CrumbBar("Routes")
        PageTitle("How it connects", "Pick a profile and you're done. Everything below is optional.")

        AetherCard {
            CardHead("Profile", "Describes your network. WhiteAesther picks the rest to match.")
            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionProfile.entries.chunked(2).forEach { pair ->
                    // IntrinsicSize.Min makes both cards adopt the taller one's
                    // height, so a card with a tag does not tower over its pair.
                    Row(
                        modifier = Modifier.height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { profile ->
                            ChoiceCard(
                                icon = profile.icon,
                                name = profile.label,
                                description = profile.description,
                                tag = profile.tag,
                                selected = profile == active,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = {
                                    // Manual has no preset to apply -- it means
                                    // "I will set these myself", so it opens the
                                    // controls instead of silently doing nothing.
                                    if (profile == ConnectionProfile.MANUAL) {
                                        advanced = true
                                    } else {
                                        onSettingsChange(settings.applyProfile(profile))
                                    }
                                },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            RowCard(
                icon = AetherIcons.Pin,
                title = "Endpoint",
                subtitle = settings.endpointSummary(),
                iconTint = AetherTheme.colors.cyan,
                modifier = endpointModifier.testTag("routes-endpoint"),
                onClick = onGoToEndpoint,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Globe,
                title = "Exit chain",
                subtitle = settings.chainSummary(),
                iconTint = AetherTheme.colors.brand,
                modifier = chainModifier.testTag("routes-chain"),
                onClick = onGoToChain,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Routes,
                title = "Routing rules",
                subtitle = settings.routingSummary(),
                iconTint = AetherTheme.colors.cyan,
                modifier = routingRulesModifier.testTag("routes-routing-rules"),
                onClick = onGoToRoutingRules,
            )
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            AdvancedSection(
                badge = "Transport · Discovery",
                expanded = advanced,
                onToggle = { advanced = !advanced },
            ) {
                CardHead("Protocol", "Tried first on every connect. The profile already picks a sensible one.")
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TunnelProtocol.entries.forEach { transport ->
                        OptionRow(
                            code = transport.wireName.uppercase(),
                            title = transport.label,
                            subtitle = when (transport) {
                                TunnelProtocol.AUTO ->
                                    "Finds what this network allows, and remembers it"
                                TunnelProtocol.H3 -> "QUIC. Faster where UDP gets through"
                                TunnelProtocol.H2 -> "TCP. Survives networks that block UDP"
                                // Its own account and its own endpoints, so a
                                // failed MASQUE retry never lands here and the
                                // first connect has a scan of its own to do.
                                TunnelProtocol.WIREGUARD -> "UDP, with an obfuscation sweep. Separate identity"
                                // Two WARP tunnels, the inner handshaking
                                // through the outer, so what an observer sees is
                                // one session carrying opaque UDP.
                                TunnelProtocol.WARP_IN_WARP -> "Nested tunnel. Slower, harder to classify"
                            },
                            selected = settings.transport == transport,
                            onClick = { onSettingsChange(settings.copy(transport = transport)) },
                        )
                    }
                }
                // The family, not whether a retry can substitute it. Automatic
                // cannot be substituted either, but it is not a UDP tunnel --
                // saying so would warn about a limitation it does not have.
                if (settings.transport.endpointFamily == EndpointFamily.WARP) {
                    Note(
                        "${settings.transport.label} runs over UDP. On a network that blocks " +
                            "UDP outright it will not connect at all, and MASQUE H2 over TCP is " +
                            "the one to use there.",
                    )
                }
                CardHead("Discovery depth", "How hard to search for a route. Deeper takes longer and uses more data.")
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ScanStrategy.entries.forEachIndexed { index, strategy ->
                        OptionRow(
                            code = (index + 1).toString(),
                            title = strategy.label,
                            subtitle = when (strategy) {
                                ScanStrategy.TURBO -> "Fastest, fewest endpoints tested"
                                ScanStrategy.BALANCED -> "Default. A good result in a few seconds"
                                ScanStrategy.THOROUGH -> "Tests more endpoints before choosing"
                                ScanStrategy.STEALTH -> "Quieter probing on watchful networks"
                                ScanStrategy.IRONCLAD -> "Slowest and most stubborn"
                            },
                            selected = settings.scanStrategy == strategy,
                            onClick = { onSettingsChange(settings.copy(scanStrategy = strategy)) },
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------- endpoint ----

@Composable
fun EndpointScreen(
    settings: AppSettings,
    status: EngineStatus,
    scannerState: EndpointScannerState,
    onSettingsChange: (AppSettings) -> Unit,
    onScanEndpoints: (AppSettings) -> Unit,
    onTestEndpoint: (AppSettings) -> Unit,
    onCancelEndpointScan: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AetherTheme.colors
    val custom = settings.endpointMode != EndpointMode.AUTOMATIC
    var endpointText by rememberSaveable { mutableStateOf(settings.customEndpoint) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(settings.customEndpoint) {
        if (!focused && settings.customEndpoint != endpointText) endpointText = settings.customEndpoint
    }
    val normalized = EndpointAddress.normalize(endpointText)
    val engineBusy = status.stage !in setOf(EngineStage.IDLE, EngineStage.ERROR)

    ScreenColumn {
        CrumbBar("Routes · Endpoint", onBack = onBack)
        PageTitle("Where it connects to", "Leave this automatic unless someone gave you an address to use.")

        settings.endpointProtocolMismatch()?.let { pinnedFor ->
            // Without this the connect fails with a message about the address,
            // which reads as a bad address rather than the right address for a
            // protocol that is no longer selected.
            AttentionCard(
                tone = colors.signalFailed,
                title = "This address is not for ${settings.transport.label}",
                body = "It was found for ${pinnedFor.label}, and endpoints are not shared " +
                    "between protocols. Scan again, or switch Endpoint back to Automatic.",
                actions = listOf(
                    "Use automatic" to {
                        onSettingsChange(
                            settings.copy(
                                endpointMode = EndpointMode.AUTOMATIC,
                                customEndpoint = "",
                                customEndpointProtocol = null,
                            ),
                        )
                    },
                ),
            )
            Spacer(Modifier.height(12.dp))
        }

        AetherCard {
            Box(Modifier.padding(11.dp)) {
                SegGroup(
                    options = listOf(false, true),
                    selected = custom,
                    label = { if (it) "Specific address" else "Automatic" },
                    onSelect = { wantCustom ->
                        onSettingsChange(
                            settings.copy(
                                endpointMode = if (wantCustom) EndpointMode.CUSTOM_FIRST else EndpointMode.AUTOMATIC,
                            ),
                        )
                    },
                )
            }
            if (custom) {
                Column(Modifier.padding(horizontal = 15.dp)) {
                    SectionLabel("Address")
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        value = endpointText,
                        onValueChange = { value ->
                            endpointText = value.take(96)
                            onSettingsChange(
                                settings.copy(
                                    customEndpoint = endpointText,
                                    customEndpointProtocol = settings.transport,
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused }
                            .testTag("custom-endpoint-field"),
                        placeholder = { Text("162.159.197.3:443", style = AetherType.Data, color = colors.text3) },
                        textStyle = AetherType.Data.copy(color = colors.text),
                        supportingText = {
                            Text(
                                when {
                                    endpointText.isBlank() -> "Looks like 162.159.197.3:443"
                                    normalized != null -> "Valid address"
                                    else -> "Needs an IP and a port"
                                },
                                style = AetherType.Small,
                            )
                        },
                        isError = endpointText.isNotBlank() && normalized == null,
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = colors.ink1,
                            unfocusedContainerColor = colors.ink1,
                            errorContainerColor = colors.ink1,
                        ),
                    )
                }
                Divider(Modifier.padding(top = 6.dp))
                ToggleSettingRow(
                    title = "Fall back automatically",
                    subtitle = "If this address stops working, search for another instead of failing.",
                    checked = settings.endpointMode == EndpointMode.CUSTOM_FIRST,
                    onCheckedChange = {
                        onSettingsChange(
                            settings.copy(
                                endpointMode = if (it) EndpointMode.CUSTOM_FIRST else EndpointMode.CUSTOM_ONLY,
                            ),
                        )
                    },
                    modifier = Modifier.testTag("endpoint-fallback-switch"),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlineButton(
                text = if (scannerState.operation == EndpointOperation.TESTING) "Testing…" else "Test this one",
                modifier = Modifier.weight(1f),
                enabled = custom && normalized != null && scannerState.operation == null && !engineBusy,
                onClick = { onTestEndpoint(settings.copy(customEndpoint = endpointText)) },
            )
            PrimaryButton(
                text = when (scannerState.operation) {
                    EndpointOperation.SCANNING -> "Stop"
                    EndpointOperation.CANCELLING -> "Stopping…"
                    else -> "Find endpoints"
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("scan-endpoints-button"),
                enabled = (scannerState.operation == null && !engineBusy) ||
                    scannerState.operation == EndpointOperation.SCANNING,
                onClick = {
                    if (scannerState.operation == EndpointOperation.SCANNING) {
                        onCancelEndpointScan()
                    } else {
                        onScanEndpoints(settings)
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                SectionLabel("Endpoints that worked")
                Spacer(Modifier.height(4.dp))
                Text(
                    scannerState.error
                        ?: scannerState.message
                        ?: "Not searched yet",
                    style = AetherType.Data,
                    color = if (scannerState.error != null) colors.signalFailed else colors.text2,
                )
            }
            scannerState.results.forEach { result ->
                Divider()
                val selected = custom && normalized == result.peer
                val interaction = remember(result.peer) { MutableInteractionSource() }
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) colors.brand.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable(interaction, LocalIndication.current) {
                            endpointText = result.peer
                            onSettingsChange(
                                settings.copy(
                                    endpointMode = EndpointMode.CUSTOM_FIRST,
                                    customEndpoint = result.peer,
                                    customEndpointProtocol = settings.transport,
                                ),
                            )
                        }
                        .controllerFocus(interaction, shape)
                        .padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            result.peer,
                            style = AetherType.Data,
                            color = if (selected) colors.brand else colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (selected) {
                                "Validated ${settings.transport.probedAs.label} route · pinned"
                            } else {
                                "Validated ${settings.transport.probedAs.label} route"
                            },
                            style = AetherType.Small,
                            color = colors.text3,
                        )
                    }
                    Text("${result.rttMillis} ms", style = AetherType.Data, color = colors.brand)
                }
            }
        }
        Note(
            "Only endpoints that pass the ${settings.transport.probedAs.label} check are listed. Tapping " +
                "one pins it and turns fallback on. Endpoints are not shared between protocols.",
        )
    }
}

// --------------------------------------------------------------- traffic ----

@Composable
fun TrafficScreen(
    settings: AppSettings,
    status: EngineStatus,
    onSettingsChange: (AppSettings) -> Unit,
    onGoToSplitTunnel: () -> Unit = {},
    appsModifier: Modifier = Modifier,
) {
    var advanced by rememberSaveable(settings.showAdvanced) { mutableStateOf(settings.showAdvanced) }
    var portText by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
    // Keyed on the switch, not on the stored value. Saving is a round trip
    // through DataStore, so a field bound straight to settings is rewritten
    // with the old text between keystrokes and cannot be typed into.
    var upstreamText by remember(settings.upstreamProxy) { mutableStateOf(settings.upstreamProxy) }
    var dnsText by remember(settings.dnsServers) { mutableStateOf(settings.dnsServers) }
    var lanUserText by remember(settings.lanSharing) { mutableStateOf(settings.lanUsername) }
    var lanPassText by remember(settings.lanSharing) { mutableStateOf(settings.lanPassword) }
    val engineBusy = status.stage !in setOf(EngineStage.IDLE, EngineStage.ERROR)
    val colors = AetherTheme.colors

    ScreenColumn {
        CrumbBar("Traffic")
        PageTitle("What is protected", "Choose how much of the device is covered. The rest has safe defaults.")

        if (settings.mode == EngineMode.TUN) {
            AetherCard {
                RowCard(
                    icon = AetherIcons.Sliders,
                    title = "Apps",
                    subtitle = settings.splitTunnel.summary(),
                    iconTint = AetherTheme.colors.cyan,
                    modifier = appsModifier.testTag("traffic-apps"),
                    onClick = onGoToSplitTunnel,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        AetherCard {
            CardHead("Coverage", "Android asks permission the first time you pick whole-device.")
            Row(
                modifier = Modifier
                    .padding(11.dp)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceCard(
                    icon = AetherIcons.Shield,
                    name = "Whole device",
                    description = "Every app is covered. What most people want.",
                    tag = "Recommended",
                    selected = settings.mode == EngineMode.TUN,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onClick = { if (!engineBusy) onSettingsChange(settings.copy(mode = EngineMode.TUN)) },
                )
                ChoiceCard(
                    icon = AetherIcons.Proxy,
                    name = "Proxy only",
                    description = "Just apps you point at the local proxy.",
                    selected = settings.mode == EngineMode.PROXY,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onClick = { if (!engineBusy) onSettingsChange(settings.copy(mode = EngineMode.PROXY)) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead("Addresses", "Turn off IPv6 if a network handles it badly.")
            Box(Modifier.padding(11.dp)) {
                SegGroup(
                    options = listOf(true, false),
                    selected = settings.dualStack,
                    label = { if (it) "IPv4 + IPv6" else "IPv4 only" },
                    onSelect = { onSettingsChange(settings.copy(dualStack = it)) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            AdvancedSection(
                badge = "Obfuscation · Blocking · Port",
                expanded = advanced,
                onToggle = { advanced = !advanced },
            ) {
                CardHead("Obfuscation", "Makes tunnel traffic harder to fingerprint. Costs a little speed.")
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NOIZE_PROFILES.forEach { (value, title, subtitle) ->
                        OptionRow(
                            code = null,
                            title = title,
                            subtitle = subtitle,
                            selected = settings.noizeProfile == value,
                            onClick = { onSettingsChange(settings.copy(noizeProfile = value)) },
                        )
                    }
                }

                Divider()
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    Text("Local proxy port", style = AetherType.RowTitle, color = colors.text)
                    Text(
                        "Where proxy-only mode listens on this device.",
                        style = AetherType.Small,
                        color = colors.text2,
                    )
                    Spacer(Modifier.height(9.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { value ->
                            if (value.length <= 5 && value.all(Char::isDigit)) {
                                portText = value
                                value.toIntOrNull()
                                    ?.takeIf { it in 1_024..65_535 }
                                    ?.let { onSettingsChange(settings.copy(proxyPort = it)) }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("proxy-port-field"),
                        textStyle = AetherType.Data.copy(color = colors.text),
                        supportingText = { Text("Between 1024 and 65535", style = AetherType.Small) },
                        isError = portText.toIntOrNull() !in 1_024..65_535,
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = colors.ink1,
                            unfocusedContainerColor = colors.ink1,
                            errorContainerColor = colors.ink1,
                        ),
                    )
                }

                Divider()
                ToggleSettingRow(
                    title = "Share with this network",
                    subtitle = "Lets other devices on the same Wi-Fi use this tunnel " +
                        "through the proxy above.",
                    checked = settings.lanSharing,
                    onCheckedChange = { onSettingsChange(settings.copy(lanSharing = it)) },
                    modifier = Modifier.testTag("lan-sharing-switch"),
                )

                if (settings.lanSharing) {
                    Column(
                        Modifier.padding(start = 11.dp, end = 11.dp, bottom = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        // The listener binds to every interface, so the address
                        // a client needs is the phone's own -- and nobody can
                        // guess it from a screen that only shows the port.
                        val address = remember(settings.lanSharing) {
                            LocalAddress.onLocalNetwork()
                        }
                        Text(
                            text = if (address != null) {
                                "Point other devices at $address:${settings.proxyPort} " +
                                    "as a SOCKS5 proxy."
                            } else {
                                "Join a Wi-Fi network to get an address other devices can use."
                            },
                            style = AetherType.Small,
                            color = colors.text2,
                            modifier = Modifier.testTag("lan-sharing-address"),
                        )

                        settings.lanSharingNotice()?.let { notice ->
                            Text(
                                text = notice.text,
                                style = AetherType.Small,
                                color = when (notice.level) {
                                    // Amber, not red: this one is a choice the
                                    // user is allowed to keep, and the failure
                                    // colour read as a field left unfilled.
                                    LanNoticeLevel.CAUTION -> colors.signalWorking
                                    LanNoticeLevel.PROBLEM -> colors.signalFailed
                                },
                                modifier = Modifier.testTag("lan-sharing-notice"),
                            )
                        }

                        Text(
                            text = "A password is optional. Leave both boxes empty to share " +
                                "without one, or fill both to require it.",
                            style = AetherType.Small,
                            color = colors.text2,
                        )
                        LanCredentialField(
                            label = "Username (optional)",
                            value = lanUserText,
                            tag = "lan-username-field",
                            onValueChange = { entered ->
                                lanUserText = entered
                                onSettingsChange(settings.copy(lanUsername = entered))
                            },
                        )
                        LanCredentialField(
                            label = "Password (optional)",
                            value = lanPassText,
                            tag = "lan-password-field",
                            onValueChange = { entered ->
                                lanPassText = entered
                                onSettingsChange(settings.copy(lanPassword = entered))
                            },
                        )
                    }
                }

                Divider()
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    Text("DNS inside the tunnel", style = AetherType.RowTitle, color = colors.text)
                    Text(
                        "Comma separated. Leave empty for the engine's own (1.1.1.1, " +
                            "1.0.0.1). Entries that are not an address are ignored.",
                        style = AetherType.Small,
                        color = colors.text2,
                    )
                    Spacer(Modifier.height(9.dp))
                    PlainField(
                        value = dnsText,
                        tag = "dns-servers-field",
                        onValueChange = { entered ->
                            dnsText = entered
                            onSettingsChange(settings.copy(dnsServers = entered))
                        },
                    )
                }

                Divider()
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    Text("Dial out through a proxy", style = AetherType.RowTitle, color = colors.text)
                    Text(
                        "Send everything the tunnel dials through a proxy already running " +
                            "on this phone: socks5://127.0.0.1:1080, or an http:// one.",
                        style = AetherType.Small,
                        color = colors.text2,
                    )
                    Spacer(Modifier.height(9.dp))
                    PlainField(
                        value = upstreamText,
                        tag = "upstream-proxy-field",
                        onValueChange = { entered ->
                            upstreamText = entered
                            onSettingsChange(settings.copy(upstreamProxy = entered))
                        },
                    )
                }

                Divider()
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    Text("WireGuard keepalive", style = AetherType.RowTitle, color = colors.text)
                    Text(
                        "Seconds between the packets that hold the connection open. " +
                            "25 is the standard; lower survives a network that forgets " +
                            "faster, and costs battery.",
                        style = AetherType.Small,
                        color = colors.text2,
                    )
                    Spacer(Modifier.height(9.dp))
                    SegGroup(
                        options = listOf(5, 15, 25),
                        selected = settings.wgKeepalive,
                        label = { "$it s" },
                        onSelect = { onSettingsChange(settings.copy(wgKeepalive = it)) },
                    )
                }

                Divider()
                ToggleSettingRow(
                    title = "Block traffic if the tunnel fails",
                    subtitle = "When every retry is spent, hold a blocking interface up " +
                        "instead of letting the phone resume unprotected.",
                    checked = settings.killSwitch,
                    onCheckedChange = { onSettingsChange(settings.copy(killSwitch = it)) },
                    modifier = Modifier.testTag("kill-switch"),
                )

                if (settings.killSwitch) {
                    Divider()
                    ToggleSettingRow(
                        title = "Keep blocking after you disconnect",
                        subtitle = "Nothing reaches the internet between sessions until you " +
                            "lift it. The app says so while it is on.",
                        checked = settings.strictKillSwitch,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(strictKillSwitch = it))
                        },
                        modifier = Modifier.testTag("strict-kill-switch"),
                    )
                }

                Divider()
                ToggleSettingRow(
                    title = "Match rules on domain names",
                    subtitle = "Reads the name from a connection's first bytes. Without it a " +
                        "rule written against a domain never matches on this platform.",
                    checked = settings.routeSniff,
                    onCheckedChange = { onSettingsChange(settings.copy(routeSniff = it)) },
                    modifier = Modifier.testTag("route-sniff-switch"),
                )

                Divider()
                ToggleSettingRow(
                    title = "Replace a refused identity",
                    subtitle = "If Cloudflare stops accepting the saved identity, register a " +
                        "fresh one instead of holding a tunnel that carries nothing.",
                    checked = settings.autoReprovision,
                    onCheckedChange = { onSettingsChange(settings.copy(autoReprovision = it)) },
                    modifier = Modifier.testTag("auto-reprovision-switch"),
                )

                Divider()
                ToggleSettingRow(
                    title = "Check the connection works",
                    subtitle = "Sends one test request after connecting. Leave this on.",
                    checked = settings.validationEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(validationEnabled = it)) },
                    modifier = Modifier.testTag("validation-switch"),
                )

                Divider()
                ToggleSettingRow(
                    title = "Split the TLS handshake",
                    subtitle = "Sends the first packet in pieces so filtering that reads the " +
                        "site name cannot see it. Turn this on where connections are blocked.",
                    checked = settings.fragmentTls,
                    onCheckedChange = { onSettingsChange(settings.copy(fragmentTls = it)) },
                    modifier = Modifier.testTag("fragment-tls-switch"),
                )

                Divider()
                ToggleSettingRow(
                    title = "Encrypted Client Hello",
                    subtitle = "Hides which site is being reached. Only works where the network " +
                        "on the other end supports it.",
                    checked = settings.encryptedHello,
                    onCheckedChange = { onSettingsChange(settings.copy(encryptedHello = it)) },
                    modifier = Modifier.testTag("ech-switch"),
                )

                Divider()
                // Read-only on purpose: the resolvers are fixed inside the engine
                // and there is no config key to change them.
                SettingRow(
                    title = "DNS resolvers",
                    subtitle = "Fixed in the engine. Not configurable yet.",
                ) {
                    Text("1.1.1.1", style = AetherType.Data, color = colors.text2)
                }
            }
        }
    }
}

/** The three the engine's `from_profile` actually distinguishes. */
private val NOIZE_PROFILES = listOf(
    Triple("firewall", "Firewall", "Default. Padding tuned for ordinary filtering."),
    Triple("gfw", "Aggressive", "Heavier padding for networks that inspect closely."),
    Triple("none", "Off", "No obfuscation. Faster, but easier to block."),
)

/**
 * One credential row for LAN sharing.
 *
 * Not password-masked. The user is reading this back to type it into another
 * machine, and a field of dots would have to be revealed to be useful -- the
 * threat here is somebody on the network, not somebody holding the phone.
 */
@Composable
private fun LanCredentialField(
    label: String,
    value: String,
    tag: String,
    onValueChange: (String) -> Unit,
) {
    val colors = AetherTheme.colors
    OutlinedTextField(
        value = value,
        // Whitespace is dropped as it is typed rather than trimmed on save: a
        // trailing space in a password is invisible on screen and rejected by
        // the engine, which looks like the password itself being wrong.
        onValueChange = { entered -> onValueChange(entered.filterNot(Char::isWhitespace)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        textStyle = AetherType.Data.copy(color = colors.text),
        label = { Text(label, style = AetherType.Small) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.ink1,
            unfocusedContainerColor = colors.ink1,
            errorContainerColor = colors.ink1,
        ),
    )
}

/**
 * One direction of the live traffic readout.
 *
 * Rate above total, because the rate is what changes and the eye goes to the
 * top of a column. Both use tabular figures so the numbers do not jitter
 * sideways as they tick.
 */
@Composable
private fun RateColumn(
    label: String,
    rate: String,
    total: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val colors = AetherTheme.colors
    // Centred inside its half rather than left-aligned. Two left-aligned
    // columns put both readings in the left two thirds of the card and leave
    // the right third empty, which reads as a layout that has come apart.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(
            rate,
            style = AetherType.DataLarge,
            color = tint,
            // One line, always. Two columns share the width, and a rate that
            // wrapped would push the total below the card's own edge.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            total,
            style = AetherType.Small,
            color = colors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A single-line text field bound to a caller-held value.
 *
 * The caller keeps the text: settings are saved through DataStore, which is a
 * round trip, and a field bound straight to that is rewritten with the previous
 * value between keystrokes and cannot be typed into.
 */
@Composable
private fun PlainField(
    value: String,
    tag: String,
    onValueChange: (String) -> Unit,
) {
    val colors = AetherTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        textStyle = AetherType.Data.copy(color = colors.text),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.ink1,
            unfocusedContainerColor = colors.ink1,
            errorContainerColor = colors.ink1,
        ),
    )
}

/**
 * Which destinations bypass the tunnel, and which are refused outright.
 *
 * Two free-text lists rather than a row editor. The engine's grammar is richer
 * than a row of dropdowns would expose -- suffixes, keywords, regular
 * expressions, CIDR blocks and port ranges -- and a list is also something a
 * user can paste, share and keep, which a table of rows is not.
 */
@Composable
fun RoutingRulesScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AetherTheme.colors
    var blockText by remember(settings.routeBlock) { mutableStateOf(settings.routeBlock) }
    var directText by remember(settings.routeDirect) { mutableStateOf(settings.routeDirect) }

    ScreenColumn {
        CrumbBar("Routes · Rules", onBack = onBack)
        PageTitle(
            "Routing rules",
            "Everything not named here goes through the tunnel.",
        )

        if (!settings.routeSniff) {
            AetherCard {
                CardHead(
                    "Domain rules will not match",
                    "This app is always a tun front end, so a connection arrives as a bare " +
                        "address. Turn on \"Match rules on domain names\" under Traffic, or " +
                        "only the address rules below will do anything.",
                )
                Spacer(Modifier.height(11.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        AetherCard {
            CardHead(
                "Never connect",
                "Refused before anything is dialled. One rule per line.",
            )
            Box(Modifier.padding(11.dp)) {
                RuleField(
                    value = blockText,
                    tag = "route-block-field",
                    placeholder = "ads.example.com\nkeyword:tracker\nport:25",
                    onValueChange = { entered ->
                        blockText = entered
                        onSettingsChange(settings.copy(routeBlock = entered))
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead(
                "Skip the tunnel",
                "Reached with this device's real address. For a bank or a domestic " +
                    "service that refuses foreign ones.",
            )
            Box(Modifier.padding(11.dp)) {
                RuleField(
                    value = directText,
                    tag = "route-direct-field",
                    placeholder = "bank.example.ir\nprivate\ncidr:10.0.0.0/8",
                    onValueChange = { entered ->
                        directText = entered
                        onSettingsChange(settings.copy(routeDirect = entered))
                    },
                )
            }
        }

        Note(
            "A plain name matches it and everything under it. Prefixes narrow that: " +
                "full: exactly, keyword: anywhere in the name, regexp: a pattern, cidr: an " +
                "address block, port: a number or range, private: the local network. " +
                "A line starting with # is a note.",
        )

        if (settings.routeDirect.isNotBlank()) {
            Text(
                "Anything under Skip the tunnel leaves with your real address. That is what " +
                    "it is for, and it is also what makes it worth keeping short.",
                style = AetherType.Small,
                color = colors.signalWorking,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
            )
        }
    }
}

/** A multi-line rule list. Monospaced, because these are patterns, not prose. */
@Composable
private fun RuleField(
    value: String,
    tag: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    val colors = AetherTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 108.dp)
            .testTag(tag),
        textStyle = AetherType.Data.copy(color = colors.text),
        placeholder = {
            Text(placeholder, style = AetherType.Data, color = colors.text3)
        },
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.ink1,
            unfocusedContainerColor = colors.ink1,
            errorContainerColor = colors.ink1,
        ),
    )
}

// -------------------------------------------------------------- settings ----

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    batteryExempt: Boolean,
    onRequestBatteryExemption: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onAddTile: () -> Unit,
    onGoToDiagnostics: () -> Unit,
    onGoToAbout: () -> Unit,
    onGoToIdentity: () -> Unit,
    isTelevision: Boolean = false,
    identityModifier: Modifier = Modifier,
    diagnosticsModifier: Modifier = Modifier,
    aboutModifier: Modifier = Modifier,
) {
    ScreenColumn {
        CrumbBar("Settings")
        PageTitle("Settings", "The app itself — nothing here changes how you connect.")

        AetherCard {
            CardHead("Appearance", "System follows your phone's light or dark setting.")
            Box(Modifier.padding(11.dp)) {
                SegGroup(
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    label = ThemeMode::label,
                    onSelect = { onSettingsChange(settings.copy(themeMode = it)) },
                )
            }
            Divider()
            ToggleSettingRow(
                title = "Show advanced controls",
                subtitle = "Opens every Advanced section by default across the app.",
                checked = settings.showAdvanced,
                onCheckedChange = { onSettingsChange(settings.copy(showAdvanced = it)) },
                modifier = Modifier.testTag("show-advanced-switch"),
            )
        }

        // Only where the platform can ask. Before Android 13 the tile is still
        // there, but the user has to find it in the shade's own edit screen.
        if (!isTelevision && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(12.dp))
            AetherCard {
                SettingRow(
                    title = "Add a quick settings tile",
                    subtitle = "Connect and disconnect from the notification shade.",
                ) {
                    OutlineButton(
                        text = "Add",
                        modifier = Modifier.testTag("add-tile-button"),
                        onClick = onAddTile,
                    )
                }
            }
        }

        // Doze and OEM battery managers drop the tunnel with the screen off.
        // Only offered when it is actually needed, and dropped for good once
        // the user says they have dealt with it -- on a phone that ignores the
        // request there is no platform answer left to wait for.
        if (!batteryExempt && !settings.batteryNoticeDismissed) {
            Spacer(Modifier.height(12.dp))
            AetherCard {
                if (settings.batteryRequestIgnored) {
                    // The dialog was opened and changed nothing, so repeating
                    // it is the one thing already known not to work here.
                    CardHead(
                        "Set this in your phone's settings",
                        "This phone keeps its own battery rules beside Android's, and the " +
                            "prompt did not change them. Open Settings, find Battery for " +
                            "WhiteAesther, and choose the unrestricted option.",
                    )
                    Column(
                        Modifier.padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimaryButton(
                            text = "Open app settings",
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("battery-app-settings-button"),
                            onClick = onOpenAppSettings,
                        )
                        OutlineButton(
                            text = "I've done this",
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("battery-dismiss-button"),
                            onClick = {
                                onSettingsChange(settings.copy(batteryNoticeDismissed = true))
                            },
                        )
                    }
                } else {
                    CardHead(
                        "Keep running in the background",
                        "Android is allowed to suspend WhiteAesther while the screen is off, " +
                            "which drops the connection. Excluding it from battery optimisation stops that.",
                    )
                    Box(Modifier.padding(11.dp)) {
                        PrimaryButton(
                            text = "Allow background running",
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("battery-exemption-button"),
                            onClick = onRequestBatteryExemption,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            RowCard(
                icon = AetherIcons.Key,
                title = "Identity & access",
                subtitle = "The device identity this app was issued",
                modifier = identityModifier.testTag("settings-identity"),
                onClick = onGoToIdentity,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Pulse,
                title = "Diagnostics & logs",
                subtitle = "See what happened, and send a report to the developer",
                modifier = diagnosticsModifier.testTag("settings-diagnostics"),
                onClick = onGoToDiagnostics,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Info,
                title = "About WhiteAesther",
                subtitle = "Version, engine build, and licences",
                modifier = aboutModifier.testTag("settings-about"),
                onClick = onGoToAbout,
            )
        }

        Spacer(Modifier.height(20.dp))
        CommunityFooter()
    }
}

/**
 * Closes the settings list rather than sitting in it as one more row: this is
 * where to go for help or news, not a preference to change.
 */
@Composable
private fun CommunityFooter() {
    val colors = AetherTheme.colors
    val uriHandler = LocalUriHandler.current
    val interaction = remember { MutableInteractionSource() }
    var unavailable by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.cyan.copy(alpha = 0.10f))
                .border(1.dp, colors.cyan.copy(alpha = 0.34f), CircleShape)
                .clickable(interaction, LocalIndication.current) {
                    unavailable = runCatching { uriHandler.openUri("https://t.me/whitedns") }.isFailure
                }
                .controllerFocus(interaction, CircleShape)
                .testTag("telegram-link")
                .padding(start = 16.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AetherIcons.Telegram, null, Modifier.size(19.dp), colors.cyan)
            Text("Join us on Telegram", style = AetherType.RowTitle, color = colors.cyan)
        }
        if (unavailable) {
            Text(
                "No app on this device can open the community link.",
                style = AetherType.Small,
                color = colors.signalFailed,
                modifier = Modifier.padding(top = 8.dp).testTag("telegram-unavailable"),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "t.me/whitedns",
            style = AetherType.Data.copy(fontSize = 12.5f.sp),
            color = colors.text3,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "News, releases and help",
            style = AetherType.Small,
            color = colors.text3,
        )
        Spacer(Modifier.height(18.dp))
        Icon(AetherIcons.Globe, null, Modifier.size(22.dp), colors.text3.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))
        Text(
            "WhiteAesther ${com.whitedns.whiteaesther.BuildConfig.VERSION_NAME}",
            style = AetherType.Small.copy(fontSize = 12.sp),
            color = colors.text3,
        )
    }
}

@Composable
fun IdentityScreen(
    settings: AppSettings,
    message: IdentityMessage? = null,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onBack: () -> Unit,
) {
    val colors = AetherTheme.colors
    ScreenColumn {
        CrumbBar("Settings · Identity", onBack = onBack)
        PageTitle(
            "Identity & access",
            "WhiteAesther issues this device its own identity. There is no account to create.",
        )
        AetherCard {
            FactRow("Identity", "Generated on first connect")
            Divider()
            FactRow("Private key", "App-private storage")
            Divider()
            FactRow("Leaves this device", "Only if you save a backup")
            Divider()
            FactRow("Organisation team", "Not configured")
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead(
                "Back up your identity",
                "Uninstalling deletes it, and a new one is not always free to get.",
            )
            Divider()
            RowCard(
                icon = AetherIcons.Send,
                title = "Save a backup",
                subtitle = "Write this device's identity to a file you keep",
                iconTint = colors.cyan,
                trailing = false,
                onClick = onExport,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Key,
                title = "Restore from a backup",
                subtitle = "Use an identity saved from this or another device",
                iconTint = colors.brand,
                trailing = false,
                onClick = onImport,
            )
        }

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            AttentionCard(
                tone = if (message.isError) colors.signalFailed else colors.brand,
                title = if (message.isError) "That did not work" else "Done",
                body = message.text,
            )
        }

        Note(
            "Cloudflare limits how many identities one network can register. Reinstalling throws " +
                "yours away, and after a few times it can refuse to issue another -- which looks " +
                "exactly like the app being broken. A backup skips all of that.",
        )
        Note(
            "Treat the file like a password: anyone who has it can present as this device. It is " +
                "not encrypted, so keep it somewhere you would keep a password, and do not send " +
                "it over a channel you would not send one.",
        )
    }
}

@Composable
fun AboutScreen(nativeVersion: String?, settings: AppSettings, onBack: () -> Unit) {
    ScreenColumn {
        CrumbBar("Settings · About", onBack = onBack)
        PageTitle("About WhiteAesther")
        AetherCard {
            FactRow(
                "App version",
                "${com.whitedns.whiteaesther.BuildConfig.VERSION_NAME} (${com.whitedns.whiteaesther.BuildConfig.VERSION_CODE})",
                mono = true,
            )
            Divider()
            FactRow("Engine", nativeVersion ?: "Unavailable", mono = true)
            Divider()
            FactRow("Package", com.whitedns.whiteaesther.BuildConfig.APPLICATION_ID, mono = true)
        }
        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead("Privacy by default")
            Spacer(Modifier.height(6.dp))
            FactRow("Proxy bind", settings.proxyBindLabel(), mono = true)
            Divider()
            FactRow("Backups", "Disabled")
            Divider()
            FactRow("Cleartext traffic", "Blocked")
        }
        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead("Licence and source")
            Spacer(Modifier.height(6.dp))
            FactRow("Licence", "AGPL-3.0")
            Divider()
            FactRow("Source", "github.com/WhiteDNS/WhiteAestherMobile", mono = true)
        }
        Note(
            "WhiteAestherMobile and the Aether engine it embeds are free software under " +
                "AGPL-3.0. The complete source for this build, and the notices for the " +
                "third-party code it includes, are published at the address above.",
        )
    }
}

// ----------------------------------------------------------- diagnostics ----

enum class LogDetail(val label: String) {
    BASIC("Basic"),
    VERBOSE("Verbose"),
}

@Composable
fun DiagnosticsScreen(
    settings: AppSettings,
    status: EngineStatus,
    nativeVersion: String?,
    entries: List<LogEntry>,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = AetherTheme.colors
    var detail by rememberSaveable { mutableStateOf(LogDetail.BASIC) }
    var includeDevice by rememberSaveable { mutableStateOf(true) }
    var includeEvents by rememberSaveable { mutableStateOf(true) }
    var includeSettings by rememberSaveable { mutableStateOf(false) }
    var redact by rememberSaveable { mutableStateOf(true) }

    val shown = remember(entries, detail) {
        if (detail == LogDetail.VERBOSE) entries else entries.filter { it.level != LogLevel.DEBUG }
    }
    val report = remember(shown, includeDevice, includeEvents, includeSettings, redact, nativeVersion) {
        buildReport(settings, nativeVersion, shown, includeDevice, includeEvents, includeSettings, redact)
    }

    ScreenColumn {
        CrumbBar("Settings · Diagnostics", onBack = onBack)
        PageTitle("Diagnostics", "If something is broken, send this to the developer — it is how fixes get written.")

        AetherCard {
            CardHead("Detail level", "Turn Verbose on, reproduce the problem, then send the report.")
            Box(Modifier.padding(11.dp)) {
                SegGroup(
                    options = LogDetail.entries,
                    selected = detail,
                    label = LogDetail::label,
                    onSelect = { detail = it },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                SectionLabel("Activity")
                Spacer(Modifier.height(4.dp))
                Text("${shown.size} events", style = AetherType.Data, color = colors.text2)
            }
            if (shown.isEmpty()) {
                Divider()
                Text(
                    "Nothing recorded yet. Connect once and the events show up here.",
                    style = AetherType.Small,
                    color = colors.text3,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                )
            }
            Column(
                Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                shown.takeLast(60).forEach { entry ->
                    Divider()
                    Column(Modifier.padding(horizontal = 15.dp, vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(entry.formattedTime(), style = AetherType.LogLine, color = colors.text3)
                            Text(
                                entry.level.name,
                                style = AetherType.LogLine.copy(fontWeight = FontWeight.Medium),
                                color = when (entry.level) {
                                    LogLevel.ERROR -> colors.signalFailed
                                    LogLevel.WARN -> colors.signalWorking
                                    LogLevel.INFO -> colors.brand
                                    LogLevel.DEBUG -> colors.text3
                                },
                            )
                            Text(entry.tag, style = AetherType.LogLine, color = colors.cyan)
                        }
                        Text(entry.message, style = AetherType.LogLine, color = colors.text2)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead("Send to the developer", "Nothing leaves your phone until you tap Send. Choose what to include.")
            Spacer(Modifier.height(6.dp))
            CheckRow("App and engine version", "Always included — a report without it cannot be read.", true, null)
            Divider()
            CheckRow("Phone model and Android version", deviceLine(), includeDevice) { includeDevice = it }
            Divider()
            CheckRow("Connection log", "The events listed above.", includeEvents) { includeEvents = it }
            Divider()
            CheckRow("Your settings", "Profile, transport, coverage and port.", includeSettings) { includeSettings = it }
            Divider()
            ToggleSettingRow(
                title = "Hide IP addresses",
                subtitle = "Replaces them with placeholders. Most problems can still be diagnosed.",
                checked = redact,
                onCheckedChange = { redact = it },
                modifier = Modifier.testTag("redact-switch"),
            )
            Divider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                SectionLabel("Exactly what will be sent")
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.ink1)
                        .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(report, style = AetherType.LogLine, color = colors.text2)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlineButton(
                text = "Copy",
                icon = AetherIcons.Copy,
                modifier = Modifier.weight(1f),
                onClick = { onCopy(report) },
            )
            PrimaryButton(
                text = "Send",
                icon = AetherIcons.Send,
                modifier = Modifier
                    .weight(1f)
                    .testTag("send-report-button"),
                onClick = { onShare(report) },
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlineButton(text = "Clear log", modifier = Modifier.fillMaxWidth(), onClick = onClear)
        Note("Reports are only used to fix problems. Sending opens your own share sheet, so you choose where it goes.")
    }
}

@Composable
private fun CheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onCheckedChange != null) {
                    Modifier
                        .toggleable(
                            value = checked,
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            role = Role.Checkbox,
                            onValueChange = onCheckedChange,
                        )
                        .controllerFocus(interaction, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 15.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) colors.brand else Color.Transparent)
                .border(
                    1.8.dp,
                    if (checked) colors.brand else colors.line,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(AetherIcons.Check, null, Modifier.size(12.dp), colors.onBrand)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AetherType.RowTitle,
                color = if (onCheckedChange == null) colors.text2 else colors.text,
            )
            Text(subtitle, style = AetherType.Small, color = colors.text2)
        }
    }
}

private fun deviceLine(): String = "${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"

private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b(:\d+)?""")
private val IPV6 = Regex("""\[[0-9a-fA-F:]+](:\d+)?""")

private fun redactAddresses(line: String): String =
    IPV6.replace(IPV4.replace(line, "0.0.0.0:port"), "[ipv6]:port")

private fun buildReport(
    settings: AppSettings,
    nativeVersion: String?,
    entries: List<LogEntry>,
    includeDevice: Boolean,
    includeEvents: Boolean,
    includeSettings: Boolean,
    redact: Boolean,
): String = buildString {
    appendLine("app ${com.whitedns.whiteaesther.BuildConfig.VERSION_NAME} (${com.whitedns.whiteaesther.BuildConfig.VERSION_CODE})")
    appendLine("engine ${nativeVersion ?: "unavailable"}")
    if (includeDevice) appendLine("device ${deviceLine()}")
    if (includeSettings) {
        appendLine(
            "settings profile=${settings.activeProfile().label} transport=${settings.transport.wireName} " +
                "scan=${settings.scanStrategy.wireName} coverage=${settings.mode.wireName} " +
                "noize=${settings.noizeProfile} bind=${settings.proxyBindLabel()} lanAuth=${settings.lanCredentialsUsable()} dualStack=${settings.dualStack}",
        )
    }
    if (includeEvents) {
        appendLine()
        entries.takeLast(120).forEach { entry ->
            val line = "${entry.formattedTime()} ${entry.level} ${entry.tag} ${entry.message}"
            appendLine(if (redact) redactAddresses(line) else line)
        }
    }
    if (redact) {
        appendLine()
        append("# IP addresses replaced")
    }
}
