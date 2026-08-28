package com.whitedns.whiteaesther.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.ChainState
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.IdentityMessage
import com.whitedns.whiteaesther.AddressPair
import com.whitedns.whiteaesther.data.UpdateChecker
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.service.TrafficSample
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.LogEntry
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.ui.theme.AetherType

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", AetherIcons.Home),
    ROUTES("Routes", AetherIcons.Routes),
    TRAFFIC("Traffic", AetherIcons.Traffic),
    SETTINGS("Settings", AetherIcons.Settings),
}

/** Sub-screens keep their parent tab selected. */
private enum class Destination(val tab: Tab) {
    HOME(Tab.HOME),
    ROUTES(Tab.ROUTES),
    ENDPOINT(Tab.ROUTES),
    CHAIN(Tab.ROUTES),
    ROUTING_RULES(Tab.ROUTES),
    TRAFFIC(Tab.TRAFFIC),
    SPLIT_TUNNEL(Tab.TRAFFIC),
    SETTINGS(Tab.SETTINGS),
    DIAGNOSTICS(Tab.SETTINGS),
    IDENTITY(Tab.SETTINGS),
    ABOUT(Tab.SETTINGS),
}

@Composable
fun WhiteAestherApp(
    settings: AppSettings,
    engineStatus: EngineStatus,
    endpointScannerState: EndpointScannerState,
    chainState: ChainState = ChainState(),
    nativeVersion: String?,
    logEntries: List<LogEntry> = emptyList(),
    onSettingsChange: (AppSettings) -> Unit,
    onConnect: (AppSettings) -> Unit,
    onStop: () -> Unit,
    onScanEndpoints: (AppSettings) -> Unit,
    onTestEndpoint: (AppSettings) -> Unit,
    onCancelEndpointScan: () -> Unit,
    onRefreshChainNodes: () -> Unit = {},
    onSelectChainNode: (String) -> Unit = {},
    onTestChainNodes: () -> Unit = {},
    identityMessage: IdentityMessage? = null,
    onExportIdentity: () -> Unit = {},
    onImportIdentity: () -> Unit = {},
    onShareReport: (String) -> Unit = {},
    onCopyReport: (String) -> Unit = {},
    onClearLog: () -> Unit = {},
    addresses: AddressPair = AddressPair(),
    traffic: TrafficSample = TrafficSample(),
    update: UpdateChecker.Available? = null,
    onLiftBlock: () -> Unit = {},
    onOpenUpdate: (String) -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    batteryExempt: Boolean = true,
    onRequestBatteryExemption: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onAddTile: () -> Unit = {},
    television: Boolean? = null,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    val colors = AetherTheme.colors
    val isTelevision = television ?: TvUiPolicy.isTelevision(LocalConfiguration.current.uiMode)
    val connectFocus = remember { FocusRequester() }
    val endpointFocus = remember { FocusRequester() }
    val chainFocus = remember { FocusRequester() }
    val rulesFocus = remember { FocusRequester() }
    val appsFocus = remember { FocusRequester() }
    val identityFocus = remember { FocusRequester() }
    val diagnosticsFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    var returnDestination by remember { mutableStateOf<Destination?>(null) }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }

    fun parentOf(detail: Destination): Destination? = when (detail) {
        Destination.CHAIN, Destination.ENDPOINT, Destination.ROUTING_RULES -> Destination.ROUTES
        Destination.SPLIT_TUNNEL -> Destination.TRAFFIC
        Destination.DIAGNOSTICS, Destination.IDENTITY, Destination.ABOUT -> Destination.SETTINGS
        else -> null
    }

    fun openDetail(detail: Destination, requester: FocusRequester) {
        returnDestination = parentOf(detail)
        returnFocus = requester
        destination = detail
    }

    fun goBack() {
        parentOf(destination)?.let { destination = it }
    }

    LaunchedEffect(isTelevision) {
        if (isTelevision && destination == Destination.HOME) connectFocus.requestFocus()
    }

    LaunchedEffect(destination) {
        if (destination == returnDestination) {
            returnFocus?.requestFocus()
            returnDestination = null
            returnFocus = null
        }
    }

    BackHandler(enabled = parentOf(destination) != null, onBack = ::goBack)

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.ink1)
            .then(
                if (isTelevision) {
                    Modifier.padding(
                        horizontal = TvUiPolicy.safeHorizontalInset,
                        vertical = TvUiPolicy.safeVerticalInset,
                    )
                } else {
                    Modifier.statusBarsPadding()
                },
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .then(
                    if (isTelevision) {
                        Modifier.widthIn(max = TvUiPolicy.maxContentWidth).fillMaxSize()
                    } else {
                        Modifier.fillMaxSize()
                    },
                ),
        ) {
            Box(Modifier.weight(1f)) {
                when (destination) {
                Destination.HOME -> HomeScreen(
                    settings = settings,
                    status = engineStatus,
                    addresses = addresses,
                    traffic = traffic,
                    chainSelection = chainState.selected,
                    update = update,
                    onLiftBlock = onLiftBlock,
                    onOpenUpdate = onOpenUpdate,
                    onDismissUpdate = onDismissUpdate,
                    // STOPPING is deliberately not actionable. The service takes
                    // the stop through a mutex and waits on the session job, so a
                    // second tap only queues another command behind the first and
                    // makes the wait longer.
                    onToggleConnection = {
                        when (engineStatus.stage) {
                            EngineStage.IDLE, EngineStage.ERROR -> onConnect(settings)
                            EngineStage.STOPPING -> Unit
                            else -> onStop()
                        }
                    },
                    onGoToRoutes = { destination = Destination.ROUTES },
                    onGoToEndpoint = {
                        openDetail(Destination.ENDPOINT, endpointFocus)
                    },
                    onGoToTraffic = { destination = Destination.TRAFFIC },
                    connectModifier = Modifier.focusRequester(connectFocus),
                    compact = isTelevision,
                )
                Destination.ROUTES -> RoutesScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onGoToEndpoint = {
                        openDetail(Destination.ENDPOINT, endpointFocus)
                    },
                    onGoToChain = { openDetail(Destination.CHAIN, chainFocus) },
                    onGoToRoutingRules = {
                        openDetail(Destination.ROUTING_RULES, rulesFocus)
                    },
                    endpointModifier = Modifier.focusRequester(endpointFocus),
                    chainModifier = Modifier.focusRequester(chainFocus),
                    routingRulesModifier = Modifier.focusRequester(rulesFocus),
                )
                Destination.CHAIN -> ChainScreen(
                    settings = settings,
                    status = engineStatus,
                    chainState = chainState,
                    onSettingsChange = onSettingsChange,
                    onRefreshNodes = onRefreshChainNodes,
                    onSelectNode = onSelectChainNode,
                    onTestNodes = onTestChainNodes,
                    onBack = ::goBack,
                )
                Destination.ENDPOINT -> EndpointScreen(
                    settings = settings,
                    status = engineStatus,
                    scannerState = endpointScannerState,
                    onSettingsChange = onSettingsChange,
                    onScanEndpoints = onScanEndpoints,
                    onTestEndpoint = onTestEndpoint,
                    onCancelEndpointScan = onCancelEndpointScan,
                    onBack = ::goBack,
                )
                Destination.TRAFFIC -> TrafficScreen(
                    settings = settings,
                    status = engineStatus,
                    onSettingsChange = onSettingsChange,
                    onGoToSplitTunnel = {
                        openDetail(Destination.SPLIT_TUNNEL, appsFocus)
                    },
                    appsModifier = Modifier.focusRequester(appsFocus),
                )
                Destination.SPLIT_TUNNEL -> SplitTunnelScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onBack = ::goBack,
                )
                Destination.ROUTING_RULES -> RoutingRulesScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onBack = ::goBack,
                )
                Destination.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    batteryExempt = batteryExempt,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onOpenAppSettings = onOpenAppSettings,
                    onAddTile = onAddTile,
                    onGoToDiagnostics = {
                        openDetail(Destination.DIAGNOSTICS, diagnosticsFocus)
                    },
                    onGoToAbout = {
                        openDetail(Destination.ABOUT, aboutFocus)
                    },
                    onGoToIdentity = {
                        openDetail(Destination.IDENTITY, identityFocus)
                    },
                    isTelevision = isTelevision,
                    identityModifier = Modifier.focusRequester(identityFocus),
                    diagnosticsModifier = Modifier.focusRequester(diagnosticsFocus),
                    aboutModifier = Modifier.focusRequester(aboutFocus),
                )
                Destination.DIAGNOSTICS -> DiagnosticsScreen(
                    settings = settings,
                    status = engineStatus,
                    nativeVersion = nativeVersion,
                    entries = logEntries,
                    onBack = ::goBack,
                    onShare = onShareReport,
                    onCopy = onCopyReport,
                    onClear = onClearLog,
                )
                Destination.IDENTITY -> IdentityScreen(
                    settings = settings,
                    message = identityMessage,
                    onExport = onExportIdentity,
                    onImport = onImportIdentity,
                    onBack = ::goBack,
                )
                Destination.ABOUT -> AboutScreen(
                    nativeVersion = nativeVersion,
                    settings = settings,
                    onBack = ::goBack,
                )
                }
            }

            TabBar(
                selected = destination.tab,
                isTelevision = isTelevision,
                onSelect = { tab ->
                    returnDestination = null
                    returnFocus = null
                    destination = when (tab) {
                        Tab.HOME -> Destination.HOME
                        Tab.ROUTES -> Destination.ROUTES
                        Tab.TRAFFIC -> Destination.TRAFFIC
                        Tab.SETTINGS -> Destination.SETTINGS
                    }
                },
            )
        }
    }
}

/**
 * The selected pill is derived from the measured cell rather than a fixed inset,
 * so the icon and its label share the cell's exact centre line.
 */
@Composable
private fun TabBar(selected: Tab, isTelevision: Boolean, onSelect: (Tab) -> Unit) {
    val colors = AetherTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.ink1)
            .then(if (isTelevision) Modifier else Modifier.navigationBarsPadding()),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .padding(horizontal = 10.dp),
        ) {
            val cellWidth = maxWidth / Tab.entries.size
            // The lambda overload of offset defers the read to the draw phase, so
            // an animating indicator does not recompose the bar every frame.
            val indicatorOffset by animateDpAsState(
                targetValue = cellWidth * Tab.entries.indexOf(selected) + 4.dp,
                animationSpec = tween(320),
                label = "tab-indicator",
            )
            val density = LocalDensity.current
            Box(
                Modifier
                    .offset { IntOffset(with(density) { indicatorOffset.roundToPx() }, 0) }
                    .width(cellWidth - 8.dp)
                    .fillMaxSize()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.brand.copy(alpha = 0.12f))
                    .border(1.dp, colors.brand.copy(alpha = 0.26f), RoundedCornerShape(14.dp)),
            )
            Row(Modifier.fillMaxSize().focusGroup()) {
                Tab.entries.forEach { tab ->
                    val active = tab == selected
                    val interaction = remember { MutableInteractionSource() }
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(interaction, LocalIndication.current) { onSelect(tab) }
                            .controllerFocus(interaction, shape)
                            .testTag("tab-${tab.label.lowercase()}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            tab.icon,
                            null,
                            Modifier.size(22.dp),
                            if (active) colors.brand else colors.text3,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tab.label,
                            style = AetherType.Small.copy(fontSize = AetherType.Label.fontSize),
                            color = if (active) colors.brand else colors.text3,
                        )
                    }
                }
            }
        }
    }
}
