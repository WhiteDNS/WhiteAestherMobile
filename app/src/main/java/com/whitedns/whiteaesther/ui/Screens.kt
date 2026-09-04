package com.whitedns.whiteaesther.ui

import androidx.annotation.StringRes
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedns.whiteaesther.AddressPair
import com.whitedns.whiteaesther.EndpointOperation
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.IdentityMessage
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.data.AppLanguage
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.data.EndpointAddress
import com.whitedns.whiteaesther.data.EndpointFamily
import com.whitedns.whiteaesther.data.EndpointMode
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.data.LanNoticeLevel
import com.whitedns.whiteaesther.data.LocalAddress
import com.whitedns.whiteaesther.data.ScanStrategy
import com.whitedns.whiteaesther.data.ThemeMode
import com.whitedns.whiteaesther.data.TunnelProtocol
import com.whitedns.whiteaesther.data.UpdateChecker
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.LogEntry
import com.whitedns.whiteaesther.service.LogLevel
import com.whitedns.whiteaesther.service.TrafficSample
import com.whitedns.whiteaesther.service.formatBytes
import com.whitedns.whiteaesther.service.formatRate
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
    // Resource ids rather than strings: an enum is built once, before there is
    // any composition to read resources from, and its labels have to be able to
    // change language along with everything else.
    @StringRes val label: Int,
    @StringRes val description: Int,
    @StringRes val tag: Int?,
    val scan: ScanStrategy?,
    val transport: TunnelProtocol?,
) {
    ADAPTIVE(
        R.string.profile_adaptive,
        R.string.works_on_most_networks_start_here,
        R.string.profile_recommended,
        ScanStrategy.BALANCED,
        TunnelProtocol.AUTO,
    ),
    PATCHY(
        R.string.patchy_signal,
        R.string.for_mobile_data_that_keeps_dropping,
        null,
        ScanStrategy.THOROUGH,
        TunnelProtocol.AUTO,
    ),
    STRICT(
        R.string.strict_network,
        R.string.for_wi_fi_that_blocks_a_lot,
        null,
        ScanStrategy.STEALTH,
        TunnelProtocol.H2,
    ),
    MANUAL(R.string.profile_manual, R.string.you_choose_every_setting_yourself, null, null, null),
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

@Composable
fun AppSettings.endpointSummary(): String = when (endpointMode) {
    EndpointMode.AUTOMATIC -> stringResource(R.string.chosen_automatically)
    else -> EndpointAddress.normalize(customEndpoint)?.let {
        if (endpointMode == EndpointMode.CUSTOM_FIRST) it else stringResource(R.string.it_no_fallback, it)
    } ?: stringResource(R.string.specific_address_not_set_yet)
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
            // The launcher icon itself, not a drawn stand-in. Image rather than
            // Icon because it carries its own colour and tinting it would flatten
            // it to a silhouette, and larger than the 30dp it replaces because
            // the launcher foreground is a 108dp canvas holding a 72dp mark --
            // sized to the canvas, the mark itself would come out smaller than
            // what it replaced.
            Image(
                painterResource(R.mipmap.ic_launcher_foreground),
                null,
                Modifier.size(44.dp),
            )
            Text(stringResource(R.string.app_name), style = AetherTheme.type.CardTitle, color = colors.text)
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectOrb(
                state = state,
                modifier = connectModifier,
                diameter = if (compact) 210.dp else 262.dp,
                enabled = status.stage != EngineStage.STOPPING,
                caption = when (status.stage) {
                    EngineStage.IDLE -> stringResource(R.string.tap_to_connect)
                    EngineStage.PREPARING, EngineStage.CONNECTING -> stringResource(R.string.tap_to_cancel)
                    EngineStage.CONNECTED -> stringResource(R.string.tap_to_stop)
                    EngineStage.STOPPING -> stringResource(R.string.stage_stopping)
                    EngineStage.ERROR -> stringResource(R.string.tap_to_retry)
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
                        EngineStage.IDLE -> stringResource(R.string.not_connected)
                        EngineStage.PREPARING -> stringResource(R.string.stage_preparing)
                        EngineStage.CONNECTING -> stringResource(R.string.stage_connecting)
                        EngineStage.CONNECTED -> stringResource(R.string.stage_connected)
                        EngineStage.STOPPING -> stringResource(R.string.stage_stopping)
                        EngineStage.ERROR -> stringResource(R.string.not_connected)
                    },
                    style = AetherTheme.type.Label,
                    color = signal,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (status.stage) {
                    EngineStage.IDLE -> stringResource(R.string.ready_when_you_are)
                    EngineStage.PREPARING, EngineStage.CONNECTING ->
                        if (status.message.contains("retry")) stringResource(R.string.still_trying) else stringResource(R.string.finding_a_working_route)
                    EngineStage.CONNECTED -> stringResource(R.string.you_re_connected)
                    EngineStage.STOPPING -> stringResource(R.string.status_disconnecting)
                    EngineStage.ERROR -> stringResource(R.string.couldn_t_connect)
                },
                style = AetherTheme.type.StatusHead,
                color = colors.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            // The engine's own words -- never a number this app invented.
            Text(
                status.message.ifBlank { stringResource(R.string.status_ready) },
                style = AetherTheme.type.Body,
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
            AetherCard(
                modifier = Modifier.testTag("home-connected-for"),
                tvFocusable = true,
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    SectionLabel(stringResource(R.string.connected_for))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        String.format(
                            // Latin digits and Latin order: a clock read against
                            // a Persian default came out in Arabic-Indic digits,
                            // which reorder around the colons.
                            java.util.Locale.ROOT,
                            stringResource(R.string.elapsed_clock),
                            elapsed / 3600,
                            (elapsed % 3600) / 60,
                            elapsed % 60,
                        ),
                        style = AetherTheme.type.DataLarge,
                        color = colors.text,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Only route out of a blocked phone. Reached from the notification
        // too, but a user who opens the app first should not have to find the
        // notification again to undo something the app is doing.
        if (status.message == stringResource(R.string.traffic_is_blocked)) {
            AetherCard {
                CardHead(
                    stringResource(R.string.traffic_is_blocked),
                    stringResource(R.string.nothing_reaches_the_internet_until_you_connect),
                )
                Box(Modifier.padding(11.dp)) {
                    OutlineButton(
                        text = stringResource(R.string.lift_the_block),
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
                    stringResource(R.string.version_update_version_is_out, update.version),
                    stringResource(R.string.you_are_on_com_whitedns_whiteaesther_buildconfig, com.whitedns.whiteaesther.BuildConfig.VERSION_NAME),
                )
                Column(
                    Modifier.padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.open_the_download_page),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("update-open-button"),
                        onClick = { onOpenUpdate(update.url) },
                    )
                    OutlineButton(
                        text = stringResource(R.string.not_now),
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
            AetherCard(
                modifier = Modifier.testTag("home-address"),
                tvFocusable = true,
            ) {
                CardHead(stringResource(R.string.your_address))
                Spacer(Modifier.height(6.dp))
                FactRow(
                    stringResource(R.string.without_the_tunnel),
                    // Absent until the app has been open while disconnected:
                    // reading it during a session would send the real address
                    // out past the thing hiding it.
                    addresses.real ?: stringResource(R.string.not_measured_yet),
                    mono = addresses.real != null,
                )
                Divider()
                val viaChain = settings.chain.enabled && settings.mode == EngineMode.TUN
                FactRow(
                    stringResource(R.string.seen_by_websites),
                    when {
                        status.stage != EngineStage.CONNECTED -> stringResource(R.string.not_connected_3)
                        // The chain's exit cannot be measured from here: this
                        // process is kept off its own interface while mihomo
                        // runs, so a probe leaves by the physical network and
                        // would report the address the tunnel hides.
                        viaChain -> chainSelection ?: stringResource(R.string.your_exit_chain_node)
                        addresses.tunnel != null -> addresses.tunnel
                        else -> stringResource(R.string.fact_checking)
                    },
                    mono = !viaChain && addresses.tunnel != null,
                )
                if (viaChain) {
                    Text(
                        stringResource(R.string.traffic_leaves_through_your_exit_chain_so),
                        style = AetherTheme.type.Small,
                        color = colors.text3,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (status.stage == EngineStage.CONNECTED || traffic.received > 0 || traffic.sent > 0) {
            AetherCard(
                modifier = Modifier.testTag("home-session"),
                tvFocusable = true,
            ) {
                CardHead(stringResource(R.string.this_session))
                Spacer(Modifier.height(6.dp))
                if (!traffic.supported) {
                    Text(
                        stringResource(R.string.this_phone_does_not_keep_per_app),
                        style = AetherTheme.type.Small,
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
                            label = stringResource(R.string.download),
                            rate = formatRate(traffic.downloadPerSecond),
                            total = formatBytes(traffic.received),
                            tint = colors.signalLive,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("traffic-download"),
                        )
                        RateColumn(
                            label = stringResource(R.string.upload),
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
                        stringResource(R.string.measured_on_the_encrypted_side_which_is),
                        style = AetherTheme.type.Small,
                        color = colors.text3,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        AetherCard(
            modifier = Modifier.testTag("home-connection-details"),
            tvFocusable = true,
        ) {
            if (status.stage == EngineStage.CONNECTED) {
                FactRow(stringResource(R.string.fact_transport), stringResource(settings.transport.label))
                Divider()
                FactRow(stringResource(R.string.fact_gateway), status.peer ?: stringResource(R.string.fact_negotiating), mono = true)
                Divider()
                FactRow(stringResource(R.string.fact_coverage), settings.coverageSummary())
                Divider()
                FactRow(stringResource(R.string.fact_addresses), if (settings.dualStack) stringResource(R.string.ipv4_ipv6) else stringResource(R.string.ipv4_only))
                if (settings.mode == EngineMode.PROXY) {
                    Divider()
                    FactRow(stringResource(R.string.local_proxy), settings.proxyBindLabel(), mono = true)
                }
            } else {
                FactRow(stringResource(R.string.profile), stringResource(settings.activeProfile().label))
                Divider()
                FactRow(
                    stringResource(R.string.endpoint),
                    settings.endpointSummary(),
                    mono = settings.endpointMode != EndpointMode.AUTOMATIC,
                )
                Divider()
                FactRow(stringResource(R.string.fact_coverage), settings.coverageSummary())
            }
        }
        Note(
            if (status.stage == EngineStage.CONNECTED) {
                stringResource(R.string.live_values_change_them_under_routes_and)
            } else {
                stringResource(R.string.what_will_be_used_when_you_connect)
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
            title = stringResource(R.string.connected_to_a_different_endpoint),
            body = stringResource(R.string.fallback_substituted, pinned, status.peer.orEmpty()),
            actions = listOf(stringResource(R.string.endpoint_settings) to AttentionTarget.ENDPOINT),
        )
        // The service retries a failed session forever on a fixed delay. Without
        // saying so, an endless spinner looks the same as normal progress.
        retrying -> Attention(
            tone = colors.signalWorking,
            title = stringResource(R.string.the_engine_keeps_retrying),
            body = status.message.substringBefore(stringResource(R.string.retry)).ifBlank { status.message } +
                ". " + status.message.substringAfter(" · ", "").replaceFirstChar(Char::uppercase) + ".",
            actions = listOf(stringResource(R.string.change_profile) to AttentionTarget.ROUTES, stringResource(R.string.pin_an_endpoint) to AttentionTarget.ENDPOINT),
        )
        status.stage == EngineStage.ERROR -> Attention(
            tone = colors.signalFailed,
            title = stringResource(R.string.the_last_attempt_failed),
            body = status.message.ifBlank { stringResource(R.string.the_engine_stopped_without_a_working_route) },
            actions = listOf(stringResource(R.string.change_profile) to AttentionTarget.ROUTES, stringResource(R.string.pin_an_endpoint) to AttentionTarget.ENDPOINT),
        )
        validationError != null -> Attention(
            tone = colors.signalWorking,
            title = stringResource(R.string.endpoint_address_is_incomplete),
            body = stringResource(validationError),
            actions = listOf(stringResource(R.string.finish_setting_it) to AttentionTarget.ENDPOINT),
        )
        // Ranked above the quieter warnings below because the consequence is the
        // same as not being connected, for every app outside the rule -- and
        // nothing else on this screen would tell the user that.
        settings.coverageIsRestricted() -> Attention(
            tone = colors.cyan,
            title = stringResource(R.string.only_some_apps_are_going_through),
            body = "A per-app rule is limiting this to ${settings.coverageSummary().lowercase()}. " +
                stringResource(R.string.everything_else_real_address),
            actions = listOf(stringResource(R.string.change_which_apps) to AttentionTarget.TRAFFIC),
        )
        settings.mode == EngineMode.PROXY -> Attention(
            tone = colors.cyan,
            title = stringResource(R.string.proxy_only_apps_are_not_routed_for),
            body = stringResource(R.string.proxy_needs_pointing, settings.proxyBindLabel()),
            actions = listOf(stringResource(R.string.change_coverage) to AttentionTarget.TRAFFIC),
        )
        settings.endpointMode == EndpointMode.CUSTOM_ONLY -> Attention(
            tone = colors.signalWorking,
            title = stringResource(R.string.fallback_is_off),
            body = stringResource(R.string.only_the_address_you_pinned_will_be),
            actions = listOf(stringResource(R.string.change_this) to AttentionTarget.ENDPOINT),
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
        CrumbBar(stringResource(R.string.routes))
        PageTitle(stringResource(R.string.how_it_connects), stringResource(R.string.pick_a_profile_and_you_re_done))

        AetherCard {
            CardHead(stringResource(R.string.profile), stringResource(R.string.describes_your_network_whiteaesther_picks_the_re))
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
                                name = stringResource(profile.label),
                                description = stringResource(profile.description),
                                tag = profile.tag?.let { stringResource(it) },
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
            CardHead(
                stringResource(R.string.carrier),
                stringResource(R.string.carrier_subtitle),
            )
            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Carrier.entries.forEach { carrier ->
                    OptionRow(
                        code = carrier.wireName.take(3).uppercase(),
                        title = stringResource(carrier.label),
                        subtitle = when (carrier) {
                            Carrier.AETHER -> stringResource(R.string.carrier_aether_detail)
                            Carrier.PSIPHON -> stringResource(R.string.carrier_psiphon_detail)
                            Carrier.TOR -> stringResource(R.string.carrier_tor_detail)
                        },
                        selected = settings.carrier == carrier,
                        // OptionRow tags itself from the title, so this row is
                        // reachable in a test as option-aether / option-psiphon.
                        onClick = { onSettingsChange(settings.copy(carrier = carrier)) },
                    )
                }
            }
            // Said here rather than left for the user to discover at connect
            // time. Everything below this card -- the endpoint, the protocol,
            // the discovery depth -- describes a search for a Cloudflare
            // gateway, and a carrier that never looks for one makes all of it
            // inert. A screen full of controls that quietly do nothing is worse
            // than a sentence saying so.
            if (!settings.carrier.usesEngine) {
                Note(stringResource(R.string.carrier_not_engine_note))
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            RowCard(
                icon = AetherIcons.Pin,
                title = stringResource(R.string.endpoint),
                subtitle = settings.endpointSummary(),
                iconTint = AetherTheme.colors.cyan,
                modifier = endpointModifier.testTag("routes-endpoint"),
                onClick = onGoToEndpoint,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Globe,
                title = stringResource(R.string.exit_chain),
                subtitle = settings.chainSummary(),
                iconTint = AetherTheme.colors.brand,
                modifier = chainModifier.testTag("routes-chain"),
                onClick = onGoToChain,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Routes,
                title = stringResource(R.string.routing_rules),
                subtitle = settings.routingSummary(),
                iconTint = AetherTheme.colors.cyan,
                modifier = routingRulesModifier.testTag("routes-routing-rules"),
                onClick = onGoToRoutingRules,
            )
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            AdvancedSection(
                badge = stringResource(R.string.transport_discovery),
                expanded = advanced,
                onToggle = { advanced = !advanced },
            ) {
                CardHead(stringResource(R.string.protocol), stringResource(R.string.tried_first_on_every_connect_the_profile))
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TunnelProtocol.entries.forEach { transport ->
                        OptionRow(
                            code = transport.wireName.uppercase(),
                            title = stringResource(transport.label),
                            subtitle = when (transport) {
                                TunnelProtocol.AUTO ->
                                    stringResource(R.string.finds_what_this_network_allows_and_remembers)
                                TunnelProtocol.H3 -> stringResource(R.string.quic_faster_where_udp_gets_through)
                                TunnelProtocol.H2 -> stringResource(R.string.tcp_survives_networks_that_block_udp)
                                // Its own account and its own endpoints, so a
                                // failed MASQUE retry never lands here and the
                                // first connect has a scan of its own to do.
                                TunnelProtocol.WIREGUARD -> stringResource(R.string.udp_with_an_obfuscation_sweep_separate_identity)
                                // Two WARP tunnels, the inner handshaking
                                // through the outer, so what an observer sees is
                                // one session carrying opaque UDP.
                                TunnelProtocol.WARP_IN_WARP -> stringResource(R.string.nested_tunnel_slower_harder_to_classify)
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
                        stringResource(R.string.settings_transport_label_runs_over_udp_on, settings.transport.label),
                    )
                }
                CardHead(stringResource(R.string.discovery_depth), stringResource(R.string.how_hard_to_search_for_a_route))
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ScanStrategy.entries.forEachIndexed { index, strategy ->
                        OptionRow(
                            code = (index + 1).toString(),
                            title = stringResource(strategy.label),
                            subtitle = when (strategy) {
                                ScanStrategy.TURBO -> stringResource(R.string.fastest_fewest_endpoints_tested)
                                ScanStrategy.BALANCED -> stringResource(R.string.default_a_good_result_in_a_few)
                                ScanStrategy.THOROUGH -> stringResource(R.string.tests_more_endpoints_before_choosing)
                                ScanStrategy.STEALTH -> stringResource(R.string.quieter_probing_on_watchful_networks)
                                ScanStrategy.IRONCLAD -> stringResource(R.string.slowest_and_most_stubborn)
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
    onResetEndpoint: (AppSettings) -> Unit,
    onCancelEndpointScan: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AetherTheme.colors
    val custom = settings.endpointMode != EndpointMode.AUTOMATIC
    var endpointText by rememberSaveable { mutableStateOf(settings.customEndpoint) }
    var focused by remember { mutableStateOf(false) }
    val endpointInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(settings.customEndpoint) {
        if (!focused && settings.customEndpoint != endpointText) endpointText = settings.customEndpoint
    }
    val normalized = EndpointAddress.normalize(endpointText)
    val engineBusy = status.stage !in setOf(EngineStage.IDLE, EngineStage.ERROR)

    ScreenColumn {
        CrumbBar(stringResource(R.string.routes_endpoint), onBack = onBack)
        PageTitle(stringResource(R.string.where_it_connects_to), stringResource(R.string.leave_this_automatic_unless_someone_gave_you))

        settings.endpointProtocolMismatch()?.let { pinnedFor ->
            // Without this the connect fails with a message about the address,
            // which reads as a bad address rather than the right address for a
            // protocol that is no longer selected.
            AttentionCard(
                tone = colors.signalFailed,
                title = stringResource(R.string.this_address_is_not_for_settings_transport, settings.transport.label),
                body = stringResource(R.string.pinned_for_other_protocol, stringResource(pinnedFor.label)),
                actions = listOf(
                    stringResource(R.string.use_automatic) to {
                        onSettingsChange(
                            settings.withoutPinnedEndpoint(),
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
                    label = { if (it) stringResource(R.string.specific_address) else "Automatic" },
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
                    SectionLabel(stringResource(R.string.address))
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
                            .tvTextFieldSupport(endpointInteraction)
                            .onFocusChanged { focused = it.isFocused }
                            .testTag("custom-endpoint-field"),
                        interactionSource = endpointInteraction,
                        placeholder = { Text("162.159.197.3:443", style = AetherTheme.type.Data, color = colors.text3) },
                        textStyle = AetherTheme.type.Data.copy(color = colors.text),
                        supportingText = {
                            Text(
                                when {
                                    endpointText.isBlank() -> stringResource(R.string.endpoint_example)
                                    normalized != null -> stringResource(R.string.valid_address)
                                    else -> stringResource(R.string.needs_an_ip_and_a_port)
                                },
                                style = AetherTheme.type.Small,
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
                    title = stringResource(R.string.fall_back_automatically),
                    subtitle = stringResource(R.string.if_this_address_stops_working_search_for),
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
                text = if (scannerState.operation == EndpointOperation.TESTING) stringResource(R.string.testing_ellipsis) else stringResource(R.string.test_this_one),
                modifier = Modifier.weight(1f),
                enabled = custom && normalized != null && scannerState.operation == null && !engineBusy,
                onClick = { onTestEndpoint(settings.copy(customEndpoint = endpointText)) },
            )
            PrimaryButton(
                text = when (scannerState.operation) {
                    EndpointOperation.SCANNING -> stringResource(R.string.stop)
                    EndpointOperation.CANCELLING -> stringResource(R.string.stopping_ellipsis)
                    else -> stringResource(R.string.find_endpoints)
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

        // Separate from "Find endpoints" above, which searches while leaving a
        // pinned address in place. This drops the pin first, so a phone that
        // keeps returning to an address that no longer answers has a way out
        // that does not involve clearing the field by hand and knowing to.
        Spacer(Modifier.height(9.dp))
        OutlineButton(
            text = stringResource(R.string.forget_and_search_again),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reset-endpoint-button"),
            enabled = scannerState.operation == null && !engineBusy,
            onClick = {
                endpointText = ""
                onResetEndpoint(settings)
            },
        )

        Spacer(Modifier.height(12.dp))
        AetherCard {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                SectionLabel(stringResource(R.string.endpoints_that_worked))
                Spacer(Modifier.height(4.dp))
                Text(
                    scannerState.error
                        ?: scannerState.message
                        ?: stringResource(R.string.not_searched_yet),
                    style = AetherTheme.type.Data,
                    color = if (scannerState.error != null) colors.signalFailed else colors.text2,
                )
            }
            scannerState.results.forEach { result ->
                Divider()
                val selected = custom && normalized == result.peer
                val interaction = remember(result.peer) { MutableInteractionSource() }
                val shape = RoundedCornerShape(14.dp)
                val selectResult = {
                    endpointText = result.peer
                    onSettingsChange(
                        settings.copy(
                            endpointMode = EndpointMode.CUSTOM_FIRST,
                            customEndpoint = result.peer,
                            customEndpointProtocol = settings.transport,
                        ),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) colors.brand.copy(alpha = 0.08f) else Color.Transparent)
                        .tvControllerActivation(onClick = selectResult)
                        .clickable(interaction, LocalIndication.current, onClick = selectResult)
                        .controllerFocus(interaction, shape)
                        .padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            result.peer,
                            style = AetherTheme.type.Data,
                            color = if (selected) colors.brand else colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (selected) {
                                stringResource(R.string.validated_route_pinned, stringResource(settings.transport.probedAs.label))
                            } else {
                                stringResource(R.string.validated_route, stringResource(settings.transport.probedAs.label))
                            },
                            style = AetherTheme.type.Small,
                            color = colors.text3,
                        )
                    }
                    Text(stringResource(R.string.result_rttmillis_ms, result.rttMillis), style = AetherTheme.type.Data, color = colors.brand)
                }
            }
        }
        Note(
            stringResource(R.string.only_endpoints_that_pass_the_settings_transport, settings.transport.probedAs.label),
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
        CrumbBar(stringResource(R.string.traffic))
        PageTitle(stringResource(R.string.what_is_protected), stringResource(R.string.choose_how_much_of_the_device_is))

        if (settings.mode == EngineMode.TUN) {
            AetherCard {
                RowCard(
                    icon = AetherIcons.Sliders,
                    title = stringResource(R.string.apps),
                    subtitle = settings.splitTunnel.summary(),
                    iconTint = AetherTheme.colors.cyan,
                    modifier = appsModifier.testTag("traffic-apps"),
                    onClick = onGoToSplitTunnel,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        AetherCard {
            CardHead(stringResource(R.string.coverage), stringResource(R.string.android_asks_permission_the_first_time_you))
            Row(
                modifier = Modifier
                    .padding(11.dp)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceCard(
                    icon = AetherIcons.Shield,
                    name = stringResource(R.string.whole_device),
                    description = stringResource(R.string.every_app_is_covered_what_most_people),
                    tag = stringResource(R.string.profile_recommended),
                    selected = settings.mode == EngineMode.TUN,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onClick = { if (!engineBusy) onSettingsChange(settings.copy(mode = EngineMode.TUN)) },
                )
                ChoiceCard(
                    icon = AetherIcons.Proxy,
                    name = stringResource(R.string.proxy_only),
                    description = stringResource(R.string.just_apps_you_point_at_the_local),
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
            CardHead(stringResource(R.string.addresses), stringResource(R.string.turn_off_ipv6_if_a_network_handles))
            Box(Modifier.padding(11.dp)) {
                SegGroup(
                    options = listOf(true, false),
                    selected = settings.dualStack,
                    label = { if (it) stringResource(R.string.ipv4_ipv6) else stringResource(R.string.ipv4_only) },
                    onSelect = { onSettingsChange(settings.copy(dualStack = it)) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            AdvancedSection(
                badge = stringResource(R.string.obfuscation_blocking_port),
                expanded = advanced,
                onToggle = { advanced = !advanced },
            ) {
                CardHead(stringResource(R.string.obfuscation), stringResource(R.string.makes_tunnel_traffic_harder_to_fingerprint_costs))
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NOIZE_PROFILES.forEach { (value, title, subtitle) ->
                        OptionRow(
                            code = null,
                            title = stringResource(title),
                            subtitle = stringResource(subtitle),
                            selected = settings.noizeProfile == value,
                            onClick = { onSettingsChange(settings.copy(noizeProfile = value)) },
                        )
                    }
                }

                Divider()
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    val portInteraction = remember { MutableInteractionSource() }
                    Text(stringResource(R.string.local_proxy_port), style = AetherTheme.type.RowTitle, color = colors.text)
                    Text(
                        stringResource(R.string.where_proxy_only_mode_listens_on_this),
                        style = AetherTheme.type.Small,
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
                            .tvTextFieldSupport(portInteraction)
                            .testTag("proxy-port-field"),
                        interactionSource = portInteraction,
                        textStyle = AetherTheme.type.Data.copy(color = colors.text),
                        supportingText = { Text(stringResource(R.string.between_1024_and_65535), style = AetherTheme.type.Small) },
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
                    title = stringResource(R.string.share_with_this_network),
                    subtitle = stringResource(R.string.lets_other_devices_on_the_same_wi),
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
                                stringResource(R.string.point_devices_at, address, settings.proxyPort)
                            } else {
                                stringResource(R.string.join_a_wi_fi_network_to_get)
                            },
                            style = AetherTheme.type.Small,
                            color = colors.text2,
                            modifier = Modifier.testTag("lan-sharing-address"),
                        )

                        settings.lanSharingNotice()?.let { notice ->
                            Text(
                                text = stringResource(notice.text),
                                style = AetherTheme.type.Small,
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
                            text = stringResource(R.string.a_password_is_optional_leave_both_boxes),
                            style = AetherTheme.type.Small,
                            color = colors.text2,
                        )
                        LanCredentialField(
                            label = stringResource(R.string.username_optional),
                            value = lanUserText,
                            tag = "lan-username-field",
                            onValueChange = { entered ->
                                lanUserText = entered
                                onSettingsChange(settings.copy(lanUsername = entered))
                            },
                        )
                        LanCredentialField(
                            label = stringResource(R.string.password_optional),
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
                    Text(stringResource(R.string.dns_inside_the_tunnel), style = AetherTheme.type.RowTitle, color = colors.text)
                    Text(
                        stringResource(R.string.comma_separated_leave_empty_for_the_engine),
                        style = AetherTheme.type.Small,
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
                    Text(stringResource(R.string.dial_out_through_a_proxy), style = AetherTheme.type.RowTitle, color = colors.text)
                    Text(
                        stringResource(R.string.send_everything_the_tunnel_dials_through_a),
                        style = AetherTheme.type.Small,
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
                    Text(stringResource(R.string.wireguard_keepalive), style = AetherTheme.type.RowTitle, color = colors.text)
                    Text(
                        stringResource(R.string.seconds_between_the_packets_that_hold_the),
                        style = AetherTheme.type.Small,
                        color = colors.text2,
                    )
                    Spacer(Modifier.height(9.dp))
                    SegGroup(
                        options = listOf(5, 15, 25),
                        selected = settings.wgKeepalive,
                        label = { stringResource(R.string.it_s, it) },
                        onSelect = { onSettingsChange(settings.copy(wgKeepalive = it)) },
                    )
                }

                Divider()
                ToggleSettingRow(
                    title = stringResource(R.string.block_traffic_if_the_tunnel_fails),
                    subtitle = stringResource(R.string.when_every_retry_is_spent_hold_a),
                    checked = settings.killSwitch,
                    onCheckedChange = { onSettingsChange(settings.copy(killSwitch = it)) },
                    modifier = Modifier.testTag("kill-switch"),
                )

                if (settings.killSwitch) {
                    Divider()
                    ToggleSettingRow(
                        title = stringResource(R.string.keep_blocking_after_you_disconnect),
                        subtitle = stringResource(R.string.nothing_reaches_the_internet_between_sessions_un),
                        checked = settings.strictKillSwitch,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(strictKillSwitch = it))
                        },
                        modifier = Modifier.testTag("strict-kill-switch"),
                    )
                }

                Divider()
                ToggleSettingRow(
                    title = stringResource(R.string.match_rules_on_domain_names),
                    subtitle = stringResource(R.string.reads_the_name_from_a_connection_s),
                    checked = settings.routeSniff,
                    onCheckedChange = { onSettingsChange(settings.copy(routeSniff = it)) },
                    modifier = Modifier.testTag("route-sniff-switch"),
                )

                Divider()
                ToggleSettingRow(
                    title = stringResource(R.string.replace_a_refused_identity),
                    subtitle = stringResource(R.string.if_cloudflare_stops_accepting_the_saved_identity),
                    checked = settings.autoReprovision,
                    onCheckedChange = { onSettingsChange(settings.copy(autoReprovision = it)) },
                    modifier = Modifier.testTag("auto-reprovision-switch"),
                )

                Divider()
                ToggleSettingRow(
                    title = stringResource(R.string.check_the_connection_works),
                    subtitle = stringResource(R.string.sends_one_test_request_after_connecting_leave),
                    checked = settings.validationEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(validationEnabled = it)) },
                    modifier = Modifier.testTag("validation-switch"),
                )

                Divider()
                ToggleSettingRow(
                    title = stringResource(R.string.split_the_tls_handshake),
                    subtitle = stringResource(R.string.sends_the_first_packet_in_pieces_so),
                    checked = settings.fragmentTls,
                    onCheckedChange = { onSettingsChange(settings.copy(fragmentTls = it)) },
                    modifier = Modifier.testTag("fragment-tls-switch"),
                )

                Divider()
                ToggleSettingRow(
                    title = stringResource(R.string.encrypted_client_hello),
                    subtitle = stringResource(R.string.hides_which_site_is_being_reached_only),
                    checked = settings.encryptedHello,
                    onCheckedChange = { onSettingsChange(settings.copy(encryptedHello = it)) },
                    modifier = Modifier.testTag("ech-switch"),
                )

                Divider()
                // Read-only on purpose: the resolvers are fixed inside the engine
                // and there is no config key to change them.
                SettingRow(
                    title = stringResource(R.string.dns_resolvers),
                    subtitle = stringResource(R.string.fixed_in_the_engine_not_configurable_yet),
                ) {
                    Text("1.1.1.1", style = AetherTheme.type.Data, color = colors.text2)
                }
            }
        }
    }
}

/** The three the engine's `from_profile` actually distinguishes. */
// The wire name the engine wants, then the two resource ids the row shows.
// Built once at class-load, so it holds ids rather than resolved text.
private val NOIZE_PROFILES = listOf(
    Triple("firewall", R.string.noize_firewall, R.string.default_padding_tuned_for_ordinary_filtering),
    Triple("gfw", R.string.noize_aggressive, R.string.heavier_padding_for_networks_that_inspect_closel),
    Triple("none", R.string.noize_off, R.string.no_obfuscation_faster_but_easier_to_block),
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
    val interaction = remember { MutableInteractionSource() }
    OutlinedTextField(
        value = value,
        // Whitespace is dropped as it is typed rather than trimmed on save: a
        // trailing space in a password is invisible on screen and rejected by
        // the engine, which looks like the password itself being wrong.
        onValueChange = { entered -> onValueChange(entered.filterNot(Char::isWhitespace)) },
        modifier = Modifier
            .fillMaxWidth()
            .tvTextFieldSupport(interaction)
            .testTag(tag),
        interactionSource = interaction,
        textStyle = AetherTheme.type.Data.copy(color = colors.text),
        label = { Text(label, style = AetherTheme.type.Small) },
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
            style = AetherTheme.type.DataLarge,
            color = tint,
            // One line, always. Two columns share the width, and a rate that
            // wrapped would push the total below the card's own edge.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            total,
            style = AetherTheme.type.Small,
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
    val interaction = remember { MutableInteractionSource() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .tvTextFieldSupport(interaction)
            .testTag(tag),
        interactionSource = interaction,
        textStyle = AetherTheme.type.Data.copy(color = colors.text),
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
        CrumbBar(stringResource(R.string.routes_rules), onBack = onBack)
        PageTitle(
            stringResource(R.string.routing_rules),
            stringResource(R.string.everything_not_named_here_goes_through_the),
        )

        if (!settings.routeSniff) {
            AetherCard {
                CardHead(
                    stringResource(R.string.domain_rules_will_not_match),
                    stringResource(R.string.this_app_is_always_a_tun_front),
                )
                Spacer(Modifier.height(11.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        AetherCard {
            CardHead(
                stringResource(R.string.never_connect),
                stringResource(R.string.refused_before_anything_is_dialled_one_rule),
            )
            Box(Modifier.padding(11.dp)) {
                RuleField(
                    value = blockText,
                    tag = "route-block-field",
                    placeholder = stringResource(R.string.ads_example_com_nkeyword_tracker_nport_25),
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
                stringResource(R.string.skip_the_tunnel),
                stringResource(R.string.reached_with_this_device_s_real_address),
            )
            Box(Modifier.padding(11.dp)) {
                RuleField(
                    value = directText,
                    tag = "route-direct-field",
                    placeholder = stringResource(R.string.bank_example_ir_nprivate_ncidr_10_0),
                    onValueChange = { entered ->
                        directText = entered
                        onSettingsChange(settings.copy(routeDirect = entered))
                    },
                )
            }
        }

        Note(
            stringResource(R.string.a_plain_name_matches_it_and_everything),
        )

        if (settings.routeDirect.isNotBlank()) {
            Text(
                stringResource(R.string.anything_under_skip_the_tunnel_leaves_with),
                style = AetherTheme.type.Small,
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
    val interaction = remember { MutableInteractionSource() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 108.dp)
            .tvTextFieldSupport(interaction)
            .testTag(tag),
        interactionSource = interaction,
        textStyle = AetherTheme.type.Data.copy(color = colors.text),
        placeholder = {
            Text(placeholder, style = AetherTheme.type.Data, color = colors.text3)
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

/**
 * Which language the app speaks.
 *
 * Each option is written in its own language rather than translated into the
 * current one. Someone who has landed in a language they cannot read needs a
 * way back out, and "Persian" is no help to a reader who does not read English
 * -- but the word in Persian is.
 *
 * Changing this rebuilds the screen. Resources are resolved when an activity is
 * created, so nothing already drawn can change language in place.
 */
@Composable
private fun LanguageSetting(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    Column {
        CardHead(stringResource(R.string.language), stringResource(R.string.the_app_s_language_whatever_your_phone))
        Box(Modifier.padding(11.dp)) {
            SegGroup(
                options = AppLanguage.entries,
                selected = selected,
                label = { language ->
                    when (language) {
                        AppLanguage.SYSTEM -> "System"
                        AppLanguage.ENGLISH -> "English"
                        AppLanguage.PERSIAN -> "فارسی"
                    }
                },
                onSelect = onSelect,
            )
        }
    }
}

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
        CrumbBar(stringResource(R.string.settings))
        PageTitle(stringResource(R.string.settings), stringResource(R.string.the_app_itself_nothing_here_changes_how))

        AetherCard {
            CardHead(stringResource(R.string.appearance), stringResource(R.string.system_follows_your_phone_s_light_or))
            Box(Modifier.padding(11.dp)) {
                SegGroup(
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    label = { stringResource(it.label) },
                    onSelect = { onSettingsChange(settings.copy(themeMode = it)) },
                )
            }
            Divider()
            LanguageSetting(
                selected = settings.language,
                onSelect = { onSettingsChange(settings.copy(language = it)) },
            )
            Divider()
            ToggleSettingRow(
                title = stringResource(R.string.show_advanced_controls),
                subtitle = stringResource(R.string.opens_every_advanced_section_by_default_across),
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
                    title = stringResource(R.string.add_a_quick_settings_tile),
                    subtitle = stringResource(R.string.connect_and_disconnect_from_the_notification_sha),
                ) {
                    OutlineButton(
                        text = stringResource(R.string.add),
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
                        stringResource(R.string.set_this_in_your_phone_s_settings),
                        stringResource(R.string.this_phone_keeps_its_own_battery_rules),
                    )
                    Column(
                        Modifier.padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.open_app_settings),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("battery-app-settings-button"),
                            onClick = onOpenAppSettings,
                        )
                        OutlineButton(
                            text = stringResource(R.string.i_ve_done_this),
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
                        stringResource(R.string.keep_running_in_the_background),
                        stringResource(R.string.android_is_allowed_to_suspend_whiteaesther_while),
                    )
                    Box(Modifier.padding(11.dp)) {
                        PrimaryButton(
                            text = stringResource(R.string.allow_background_running),
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
                title = stringResource(R.string.identity_access),
                subtitle = stringResource(R.string.the_device_identity_this_app_was_issued),
                modifier = identityModifier.testTag("settings-identity"),
                onClick = onGoToIdentity,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Pulse,
                title = stringResource(R.string.diagnostics_logs),
                subtitle = stringResource(R.string.see_what_happened_and_send_a_report),
                modifier = diagnosticsModifier.testTag("settings-diagnostics"),
                onClick = onGoToDiagnostics,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Info,
                title = stringResource(R.string.about_whiteaesther),
                subtitle = stringResource(R.string.version_engine_build_and_licences),
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
    val openCommunity = {
        unavailable = runCatching { uriHandler.openUri("https://t.me/whitedns") }.isFailure
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.cyan.copy(alpha = 0.10f))
                .border(1.dp, colors.cyan.copy(alpha = 0.34f), CircleShape)
                .tvControllerActivation(onClick = openCommunity)
                .clickable(interaction, LocalIndication.current, onClick = openCommunity)
                .controllerFocus(interaction, CircleShape)
                .testTag("telegram-link")
                .padding(start = 16.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AetherIcons.Telegram, null, Modifier.size(19.dp), colors.cyan)
            Text(stringResource(R.string.join_us_on_telegram), style = AetherTheme.type.RowTitle, color = colors.cyan)
        }
        if (unavailable) {
            Text(
                stringResource(R.string.no_app_on_this_device_can_open),
                style = AetherTheme.type.Small,
                color = colors.signalFailed,
                modifier = Modifier.padding(top = 8.dp).testTag("telegram-unavailable"),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.t_me_whitedns),
            style = AetherTheme.type.Data.copy(fontSize = 12.5f.sp),
            color = colors.text3,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.news_releases_and_help),
            style = AetherTheme.type.Small,
            color = colors.text3,
        )
        Spacer(Modifier.height(18.dp))
        Icon(AetherIcons.Globe, null, Modifier.size(22.dp), colors.text3.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.whiteaesther_com_whitedns_whiteaesther_buildconf, com.whitedns.whiteaesther.BuildConfig.VERSION_NAME),
            style = AetherTheme.type.Small.copy(fontSize = 12.sp),
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
        CrumbBar(stringResource(R.string.settings_identity), onBack = onBack)
        PageTitle(
            stringResource(R.string.identity_access),
            stringResource(R.string.whiteaesther_issues_this_device_its_own_identity),
        )
        AetherCard {
            FactRow(stringResource(R.string.fact_identity), stringResource(R.string.generated_on_first_connect))
            Divider()
            FactRow(stringResource(R.string.private_key), stringResource(R.string.app_private_storage))
            Divider()
            FactRow(stringResource(R.string.leaves_this_device), stringResource(R.string.only_if_you_save_a_backup))
            Divider()
            FactRow(stringResource(R.string.organisation_team), stringResource(R.string.not_configured))
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead(
                stringResource(R.string.back_up_your_identity),
                stringResource(R.string.uninstalling_deletes_it_and_a_new_one),
            )
            Divider()
            RowCard(
                icon = AetherIcons.Send,
                title = stringResource(R.string.save_a_backup),
                subtitle = stringResource(R.string.write_this_device_s_identity_to_a),
                iconTint = colors.cyan,
                trailing = false,
                onClick = onExport,
            )
            Divider()
            RowCard(
                icon = AetherIcons.Key,
                title = stringResource(R.string.restore_from_a_backup),
                subtitle = stringResource(R.string.use_an_identity_saved_from_this_or),
                iconTint = colors.brand,
                trailing = false,
                onClick = onImport,
            )
        }

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            AttentionCard(
                tone = if (message.isError) colors.signalFailed else colors.brand,
                title = if (message.isError) stringResource(R.string.that_did_not_work) else stringResource(R.string.done),
                body = message.text,
            )
        }

        Note(
            stringResource(R.string.cloudflare_limits_how_many_identities_one_networ),
        )
        Note(
            stringResource(R.string.treat_the_file_like_a_password_anyone),
        )
    }
}

@Composable
fun AboutScreen(nativeVersion: String?, settings: AppSettings, onBack: () -> Unit) {
    ScreenColumn {
        CrumbBar(stringResource(R.string.settings_about), onBack = onBack)
        PageTitle(stringResource(R.string.about_whiteaesther))
        AetherCard {
            FactRow(
                stringResource(R.string.app_version),
                "${com.whitedns.whiteaesther.BuildConfig.VERSION_NAME} (${com.whitedns.whiteaesther.BuildConfig.VERSION_CODE})",
                mono = true,
            )
            Divider()
            FactRow(stringResource(R.string.fact_engine), nativeVersion ?: stringResource(R.string.fact_unavailable), mono = true)
            Divider()
            FactRow(stringResource(R.string.fact_package), com.whitedns.whiteaesther.BuildConfig.APPLICATION_ID, mono = true)
        }
        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead(stringResource(R.string.privacy_by_default))
            Spacer(Modifier.height(6.dp))
            FactRow(stringResource(R.string.proxy_bind), settings.proxyBindLabel(), mono = true)
            Divider()
            FactRow(stringResource(R.string.fact_backups), stringResource(R.string.fact_disabled))
            Divider()
            FactRow(stringResource(R.string.cleartext_traffic), stringResource(R.string.fact_blocked))
        }
        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead(stringResource(R.string.licence_and_source))
            Spacer(Modifier.height(6.dp))
            FactRow(stringResource(R.string.fact_licence), "AGPL-3.0")
            Divider()
            FactRow(stringResource(R.string.fact_source), "github.com/WhiteDNS/WhiteAestherMobile", mono = true)
        }
        Note(
            stringResource(R.string.whiteaesthermobile_and_the_aether_engine_it_embe),
        )
    }
}

// ----------------------------------------------------------- diagnostics ----

enum class LogDetail(@StringRes val label: Int) {
    BASIC(R.string.detail_basic),
    VERBOSE(R.string.detail_verbose),
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
        CrumbBar(stringResource(R.string.settings_diagnostics), onBack = onBack)
        PageTitle(stringResource(R.string.diagnostics), stringResource(R.string.if_something_is_broken_send_this_to))

        AetherCard {
            CardHead(stringResource(R.string.detail_level), stringResource(R.string.turn_verbose_on_reproduce_the_problem_then))
            Box(Modifier.padding(11.dp)) {
                SegGroup(
                    options = LogDetail.entries,
                    selected = detail,
                    label = { stringResource(it.label) },
                    onSelect = { detail = it },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                SectionLabel(stringResource(R.string.activity))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.shown_size_events, shown.size), style = AetherTheme.type.Data, color = colors.text2)
            }
            if (shown.isEmpty()) {
                Divider()
                Text(
                    stringResource(R.string.nothing_recorded_yet_connect_once_and_the),
                    style = AetherTheme.type.Small,
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
                            Text(entry.formattedTime(), style = AetherTheme.type.LogLine, color = colors.text3)
                            Text(
                                entry.level.name,
                                style = AetherTheme.type.LogLine.copy(fontWeight = FontWeight.Medium),
                                color = when (entry.level) {
                                    LogLevel.ERROR -> colors.signalFailed
                                    LogLevel.WARN -> colors.signalWorking
                                    LogLevel.INFO -> colors.brand
                                    LogLevel.DEBUG -> colors.text3
                                },
                            )
                            Text(entry.tag, style = AetherTheme.type.LogLine, color = colors.cyan)
                        }
                        Text(entry.message, style = AetherTheme.type.LogLine, color = colors.text2)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        AetherCard {
            CardHead(stringResource(R.string.send_to_the_developer), stringResource(R.string.nothing_leaves_your_phone_until_you_tap))
            Spacer(Modifier.height(6.dp))
            CheckRow(stringResource(R.string.app_and_engine_version), stringResource(R.string.always_included_a_report_without_it_cannot), true, null)
            Divider()
            CheckRow(stringResource(R.string.phone_model_and_android_version), deviceLine(), includeDevice) { includeDevice = it }
            Divider()
            CheckRow(stringResource(R.string.connection_log), stringResource(R.string.the_events_listed_above), includeEvents) { includeEvents = it }
            Divider()
            CheckRow(stringResource(R.string.your_settings), stringResource(R.string.profile_transport_coverage_and_port), includeSettings) { includeSettings = it }
            Divider()
            ToggleSettingRow(
                title = stringResource(R.string.hide_ip_addresses),
                subtitle = stringResource(R.string.replaces_them_with_placeholders_most_problems_ca),
                checked = redact,
                onCheckedChange = { redact = it },
                modifier = Modifier.testTag("redact-switch"),
            )
            Divider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                SectionLabel(stringResource(R.string.exactly_what_will_be_sent))
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
                    Text(report, style = AetherTheme.type.LogLine, color = colors.text2)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlineButton(
                text = stringResource(R.string.copy),
                icon = AetherIcons.Copy,
                modifier = Modifier.weight(1f),
                onClick = { onCopy(report) },
            )
            PrimaryButton(
                text = stringResource(R.string.send),
                icon = AetherIcons.Send,
                modifier = Modifier
                    .weight(1f)
                    .testTag("send-report-button"),
                onClick = { onShare(report) },
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlineButton(text = stringResource(R.string.clear_log), modifier = Modifier.fillMaxWidth(), onClick = onClear)
        Note(stringResource(R.string.reports_are_only_used_to_fix_problems))
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
                        .tvControllerActivation { onCheckedChange(!checked) }
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
                style = AetherTheme.type.RowTitle,
                color = if (onCheckedChange == null) colors.text2 else colors.text,
            )
            Text(subtitle, style = AetherTheme.type.Small, color = colors.text2)
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
            "settings profile=${settings.activeProfile().name.lowercase()} transport=${settings.transport.wireName} " +
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
