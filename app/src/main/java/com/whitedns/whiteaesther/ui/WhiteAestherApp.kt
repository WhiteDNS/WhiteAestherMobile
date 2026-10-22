package com.whitedns.whiteaesther.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.AddressPair
import com.whitedns.whiteaesther.ChainState
import com.whitedns.whiteaesther.BridgeMessage
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.IdentityMessage
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.UpdateChecker
import com.whitedns.whiteaesther.data.UpdateDownload
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.LogEntry
import com.whitedns.whiteaesther.service.TrafficSample
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import java.io.File

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.home_tab_connect, AetherIcons.Power),
    ROUTES(R.string.home_tab_methods, AetherIcons.Routes),
    TRAFFIC(R.string.home_tab_tools, AetherIcons.Sliders),
    LOGS(R.string.home_tab_logs, AetherIcons.Pulse),
}

/** Sub-screens keep their parent tab selected. */
private enum class Destination(val tab: Tab?) {
    HOME(Tab.HOME),
    ROUTES(Tab.ROUTES),
    ENDPOINT(Tab.ROUTES),
    CHAIN(Tab.ROUTES),
    ROUTING_RULES(Tab.ROUTES),
    TRAFFIC(Tab.TRAFFIC),
    SPLIT_TUNNEL(Tab.TRAFFIC),
    SETTINGS(null),
    DIAGNOSTICS(Tab.LOGS),
    IDENTITY(null),
    ABOUT(null),
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
    onResetEndpoint: (AppSettings) -> Unit,
    onCancelEndpointScan: () -> Unit,
    onFetchBridges: (String) -> Unit = {},
    bridgesFetching: Boolean = false,
    bridgesMessage: BridgeMessage? = null,
    detectedCountry: String = "",
    psiphonRegions: List<String> = emptyList(),
    onRefreshChainNodes: () -> Unit = {},
    onSelectChainNode: (String) -> Unit = {},
    onTestChainNodes: () -> Unit = {},
    onTestChainNodesSelected: (List<String>) -> Unit = {},
    onCancelChainTests: () -> Unit = {},
    identityMessage: IdentityMessage? = null,
    onExportIdentity: () -> Unit = {},
    onImportIdentity: () -> Unit = {},
    onShareReport: (String) -> Unit = {},
    onCopyReport: (String) -> Unit = {},
    onClearLog: () -> Unit = {},
    addresses: AddressPair = AddressPair(),
    traffic: TrafficSample = TrafficSample(),
    update: UpdateChecker.Available? = null,
    updateDownload: UpdateDownload = UpdateDownload.Idle,
    canInstallUpdate: Boolean = false,
    updatesInPlace: Boolean = false,
    onLiftBlock: () -> Unit = {},
    onOpenUpdate: (String) -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: (File) -> Unit = {},
    onCancelUpdateDownload: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    batteryExempt: Boolean = true,
    onRequestBatteryExemption: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onAddTile: () -> Unit = {},
    onAddWidget: () -> Unit = {},
    television: Boolean? = null,
) {
    var navigation by rememberSaveable(
        stateSaver = listSaver<List<Destination>, String>(
            save = { stack -> stack.map { it.name } },
            restore = { names -> names.map { Destination.valueOf(it) } },
        ),
    ) { mutableStateOf(listOf(Destination.HOME)) }
    val destination = navigation.last()
    val activeTab = navigation.asReversed().firstNotNullOfOrNull {
        when (it) {
            Destination.HOME, Destination.ROUTES, Destination.TRAFFIC, Destination.DIAGNOSTICS -> it.tab
            else -> null
        }
    }
    val screenState = rememberSaveableStateHolder()
    val settingsFocus = remember { FocusRequester() }
    val isTelevision = television ?: TvUiPolicy.isTelevision(LocalConfiguration.current.uiMode)
    val inputModeManager = LocalInputModeManager.current
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

    fun openDetail(detail: Destination, requester: FocusRequester? = null) {
        if (destination == detail) return
        returnDestination = destination
        returnFocus = requester
        navigation = navigation + detail
    }

    fun goBack(): Boolean {
        if (navigation.size > 1) {
            navigation = navigation.dropLast(1)
            return true
        }
        if (destination != Destination.HOME) {
            navigation = listOf(Destination.HOME)
            return true
        }
        return false
    }

    LaunchedEffect(isTelevision) {
        if (isTelevision) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (destination == Destination.HOME) connectFocus.requestFocus()
        }
    }

    LaunchedEffect(destination) {
        if (destination == returnDestination) {
            returnFocus?.requestFocus()
            returnDestination = null
            returnFocus = null
        }
    }

    BackHandler(enabled = navigation.size > 1 || destination != Destination.HOME) { goBack() }

    CompositionLocalProvider(LocalTvMode provides isTelevision) {
        AetherAppShell(
            selected = activeTab,
            isTelevision = isTelevision,
            onControllerBack = ::goBack,
            onOpenSettings = {
                if (destination == Destination.SETTINGS) goBack()
                else openDetail(Destination.SETTINGS, settingsFocus)
            },
            settingsModifier = Modifier.focusRequester(settingsFocus),
            onSelect = { tab ->
                returnDestination = null
                returnFocus = null
                navigation = listOf(when (tab) {
                    Tab.HOME -> Destination.HOME
                    Tab.ROUTES -> Destination.ROUTES
                    Tab.TRAFFIC -> Destination.TRAFFIC
                    Tab.LOGS -> Destination.DIAGNOSTICS
                })
            },
        ) {
            screenState.SaveableStateProvider(destination.name) {
                when (destination) {
                    Destination.HOME -> HomeScreen(
                        settings = settings,
                        status = engineStatus,
                        addresses = addresses,
                        traffic = traffic,
                        chainSelection = chainState.selected,
                        update = update,
                        updateDownload = updateDownload,
                        canInstallUpdate = canInstallUpdate,
                        updatesInPlace = updatesInPlace,
                        onLiftBlock = onLiftBlock,
                        onOpenUpdate = onOpenUpdate,
                        onDownloadUpdate = onDownloadUpdate,
                        onInstallUpdate = onInstallUpdate,
                        onCancelUpdateDownload = onCancelUpdateDownload,
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
                        onGoToRoutes = { openDetail(Destination.ROUTES) },
                        onGoToEndpoint = {
                            openDetail(Destination.ENDPOINT)
                        },
                        onGoToTraffic = { openDetail(Destination.TRAFFIC) },
                        onSettingsChange = onSettingsChange,
                        onGoToApps = { openDetail(Destination.SPLIT_TUNNEL) },
                        onGoToDiagnostics = { openDetail(Destination.DIAGNOSTICS) },
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
                        onFetchBridges = onFetchBridges,
                        bridgesFetching = bridgesFetching,
                        bridgesMessage = bridgesMessage,
                        detectedCountry = detectedCountry,
                        psiphonRegions = psiphonRegions,
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
                        onTestSelected = onTestChainNodesSelected,
                        onCancelTests = onCancelChainTests,
                        onBack = ::goBack,
                    )
                    Destination.ENDPOINT -> EndpointScreen(
                        settings = settings,
                        status = engineStatus,
                        scannerState = endpointScannerState,
                        onSettingsChange = onSettingsChange,
                        onScanEndpoints = onScanEndpoints,
                        onTestEndpoint = onTestEndpoint,
                        onResetEndpoint = onResetEndpoint,
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
                        onGoToEndpoint = { openDetail(Destination.ENDPOINT) },
                        onGoToChain = { openDetail(Destination.CHAIN) },
                        onGoToRoutingRules = { openDetail(Destination.ROUTING_RULES) },
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
                        onAddWidget = onAddWidget,
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
                        onBack = ::goBack,
                    )
                    Destination.DIAGNOSTICS -> DiagnosticsScreen(
                        settings = settings,
                        status = engineStatus,
                        nativeVersion = nativeVersion,
                        entries = logEntries,
                        onBack = if (navigation.size > 1) ({ goBack() }) else null,
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
        }
    }
}

@Composable
private fun AetherAppShell(
    selected: Tab?,
    isTelevision: Boolean,
    onSelect: (Tab) -> Unit,
    onControllerBack: () -> Boolean,
    onOpenSettings: () -> Unit,
    settingsModifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AetherTheme.colors
    val expanded = isTelevision || LocalConfiguration.current.screenWidthDp >= 600
    Row(
        Modifier.fillMaxSize().background(colors.ink1).statusBarsPadding()
            .then(if (isTelevision) Modifier.padding(
                horizontal = TvUiPolicy.safeHorizontalInset,
                vertical = TvUiPolicy.safeVerticalInset,
            ) else Modifier)
            .tvControllerBack(onControllerBack),
    ) {
        if (expanded) TvNavigationRail(selected, onSelect)
        Column(
            Modifier.weight(1f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FamilyHeader(onOpenSettings, settingsModifier)
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.widthIn(max = 520.dp).fillMaxSize(), content = content)
            }
            if (!expanded) TabBar(selected, onSelect)
        }
    }
}

@Composable
private fun FamilyHeader(onOpenSettings: () -> Unit, settingsModifier: Modifier) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.mipmap.ic_launcher_foreground), null,
            Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape))
        Text(
            stringResource(R.string.app_name), style = AetherTheme.type.RowTitle,
            color = colors.text, modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box(
            settingsModifier.size(48.dp).clip(shape)
                .tvControllerActivation(onClick = onOpenSettings)
                .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onOpenSettings)
                .controllerFocus(interaction, shape).testTag("tab-settings"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AetherIcons.Settings, stringResource(R.string.settings), Modifier.size(22.dp), colors.text2)
        }
    }
}

@Composable
private fun TvNavigationRail(selected: Tab?, onSelect: (Tab) -> Unit) {
    val colors = AetherTheme.colors
    Column(
        modifier = Modifier
            .widthIn(min = 122.dp, max = 148.dp)
            .fillMaxSize()
            .background(colors.ink1)
            .border(1.dp, colors.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 20.dp)
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AetherIcons.Globe, null, Modifier.size(34.dp), colors.brand)
        Spacer(Modifier.height(18.dp))
        Tab.entries.forEach { tab ->
            val active = tab == selected
            val interaction = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (active) colors.brand.copy(alpha = 0.12f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) colors.brand.copy(alpha = 0.38f) else Color.Transparent,
                        shape,
                    )
                    .tvControllerActivation { onSelect(tab) }
                    .selectable(selected = active, role = Role.Tab, interactionSource = interaction, indication = LocalIndication.current, onClick = { onSelect(tab) })
                    .controllerFocus(interaction, shape)
                    .testTag("tab-${tab.name.lowercase()}")
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    tab.icon,
                    null,
                    Modifier.size(24.dp),
                    if (active) colors.brand else colors.text3,
                )
                Text(
                    stringResource(tab.label),
                    style = AetherTheme.type.Small,
                    color = if (active) colors.text else colors.text3,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun TabBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    val colors = AetherTheme.colors
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)).background(colors.ink2).selectableGroup(),
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val interaction = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(16.dp)
                val largeText = LocalConfiguration.current.fontScale > 1.5f
                val label = stringResource(tab.label)
                Box(
                    Modifier.weight(if (largeText && active) 1.5f else 1f)
                        .heightIn(min = 72.dp)
                        .selectable(
                            selected = active, role = Role.Tab,
                            interactionSource = interaction, indication = LocalIndication.current,
                            onClick = { onSelect(tab) },
                        )
                        .controllerFocus(interaction, shape)
                        .testTag("tab-${tab.name.lowercase()}")
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(tab.icon, label, Modifier.size(22.dp), if (active) colors.brand else colors.text2)
                        if (!largeText || active) {
                            Text(label, style = AetherTheme.type.Small,
                                color = if (active) colors.brand else colors.text2,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (active) Box(
                        Modifier.align(Alignment.BottomCenter).width(28.dp).height(3.dp)
                            .background(colors.brand, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}
