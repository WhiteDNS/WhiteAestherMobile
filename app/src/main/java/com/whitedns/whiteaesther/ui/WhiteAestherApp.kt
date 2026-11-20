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
import com.whitedns.whiteaesther.data.AdvancedGroup
import com.whitedns.whiteaesther.data.UpdateChecker
import com.whitedns.whiteaesther.data.UpdateDownload
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.LogEntry
import com.whitedns.whiteaesther.service.TrafficSample
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import java.io.File

// Three tabs a first-time user can read at a glance. Everything technical lives
// under Settings > Advanced, so nothing here needs explaining.
private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.home_tab_connect, AetherIcons.Power),
    APPS(R.string.apps, AetherIcons.Sliders),
    SETTINGS(R.string.settings, AetherIcons.Settings),
}

/** Sub-screens keep their parent tab selected. */
private enum class Destination(val tab: Tab) {
    HOME(Tab.HOME),
    METHOD(Tab.HOME),
    APPS(Tab.APPS),
    SETTINGS(Tab.SETTINGS),
    ADVANCED(Tab.SETTINGS),
    PROTOCOL(Tab.SETTINGS),
    TWO_HOP(Tab.SETTINGS),
    ENDPOINT(Tab.SETTINGS),
    STEALTH(Tab.SETTINGS),
    CHAIN(Tab.SETTINGS),
    ROUTING_RULES(Tab.SETTINGS),
    NETWORK(Tab.SETTINGS),
    SAFETY(Tab.SETTINGS),
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
    onResetEndpoint: (AppSettings) -> Unit,
    onCancelEndpointScan: () -> Unit,
    onFetchBridges: (String) -> Unit = {},
    bridgesFetching: Boolean = false,
    bridgesMessage: BridgeMessage? = null,
    detectedCountry: String = "",
    psiphonRegions: List<String> = emptyList(),
    onRefreshChainNodes: (Boolean) -> Unit = {},
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
            // A stack saved by an older build can name screens that no longer
            // exist; drop them rather than crash on restore.
            restore = { names ->
                names.mapNotNull { name -> Destination.entries.firstOrNull { it.name == name } }
                    .ifEmpty { listOf(Destination.HOME) }
            },
        ),
    ) { mutableStateOf(listOf(Destination.HOME)) }
    val destination = navigation.last()
    val activeTab = destination.tab
    val screenState = rememberSaveableStateHolder()
    val isTelevision = television ?: TvUiPolicy.isTelevision(LocalConfiguration.current.uiMode)
    val inputModeManager = LocalInputModeManager.current
    val connectFocus = remember { FocusRequester() }
    // The row that opened each screen, so Back on a controller lands on it again.
    val rowFocus = remember { Destination.entries.associateWith { FocusRequester() } }
    var returnDestination by remember { mutableStateOf<Destination?>(null) }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }

    fun openDetail(detail: Destination, requester: FocusRequester? = null) {
        if (destination == detail) return
        returnDestination = destination
        returnFocus = requester
        navigation = navigation + detail
    }

    /** Opens [detail] from a row that carries `rowFocus[detail]`. */
    fun openFromRow(detail: Destination) = openDetail(detail, rowFocus.getValue(detail))

    fun openTab(tab: Tab) {
        returnDestination = null
        returnFocus = null
        navigation = listOf(when (tab) {
            Tab.HOME -> Destination.HOME
            Tab.APPS -> Destination.APPS
            Tab.SETTINGS -> Destination.SETTINGS
        })
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

    val methodSupport = MethodSheetSupport(
        psiphonRegions = psiphonRegions,
        onFetchBridges = onFetchBridges,
        bridgesFetching = bridgesFetching,
        bridgesMessage = bridgesMessage,
        detectedCountry = detectedCountry,
        onOpenNodes = { openDetail(Destination.CHAIN) },
        onOpenAether = { openDetail(Destination.PROTOCOL) },
    )

    CompositionLocalProvider(LocalTvMode provides isTelevision) {
        AetherAppShell(
            selected = activeTab,
            isTelevision = isTelevision,
            onControllerBack = ::goBack,
            onSelect = ::openTab,
        ) {
            screenState.SaveableStateProvider(destination.name) {
                when (destination) {
                    Destination.HOME -> HomeScreen(
                        settings = settings,
                        status = engineStatus,
                        addresses = addresses,
                        traffic = traffic,
                        chainSelection = chainState.selected,
                        chainAutomaticPick = chainState.automaticPick,
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
                        onGoToRoutes = { openDetail(Destination.METHOD) },
                        onGoToAdvanced = { openDetail(Destination.ADVANCED) },
                        methodSupport = methodSupport,
                        onGoToChain = { openDetail(Destination.CHAIN) },
                        onOpenAdvancedGroup = { openDetail(it.destination) },
                        onGoToEndpoint = { openDetail(Destination.ENDPOINT) },
                        onGoToTraffic = { openTab(Tab.APPS) },
                        onSettingsChange = onSettingsChange,
                        onGoToApps = { openTab(Tab.APPS) },
                        onGoToDiagnostics = { openDetail(Destination.DIAGNOSTICS) },
                        connectModifier = Modifier.focusRequester(connectFocus),
                        compact = isTelevision,
                    )
                    Destination.APPS -> SplitTunnelScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                    )
                    Destination.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        batteryExempt = batteryExempt,
                        onRequestBatteryExemption = onRequestBatteryExemption,
                        onOpenAppSettings = onOpenAppSettings,
                        onAddTile = onAddTile,
                        onAddWidget = onAddWidget,
                        onGoToAdvanced = { openFromRow(Destination.ADVANCED) },
                        onGoToDiagnostics = { openFromRow(Destination.DIAGNOSTICS) },
                        onGoToAbout = { openFromRow(Destination.ABOUT) },
                        isTelevision = isTelevision,
                        advancedModifier = Modifier.focusRequester(rowFocus.getValue(Destination.ADVANCED)),
                        diagnosticsModifier = Modifier.focusRequester(rowFocus.getValue(Destination.DIAGNOSTICS)),
                        aboutModifier = Modifier.focusRequester(rowFocus.getValue(Destination.ABOUT)),
                    )
                    Destination.ADVANCED -> AdvancedScreen(
                        settings = settings,
                        status = engineStatus,
                        onSettingsChange = onSettingsChange,
                        onOpen = { openFromRow(it.destination) },
                        onOpenIdentity = { openFromRow(Destination.IDENTITY) },
                        rowModifier = { Modifier.focusRequester(rowFocus.getValue(it.destination)) },
                        identityModifier = Modifier.focusRequester(rowFocus.getValue(Destination.IDENTITY)),
                        onBack = ::goBack,
                    )
                    Destination.METHOD -> MethodScreen(
                        settings = settings,
                        support = methodSupport,
                        onSettingsChange = onSettingsChange,
                        onGoToAdvanced = { openDetail(Destination.ADVANCED) },
                        onBack = { goBack() },
                        onOpenAdvancedGroup = { openDetail(it.destination) },
                    )
                    Destination.PROTOCOL -> ProtocolScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onGoToEndpoint = { openFromRow(Destination.ENDPOINT) },
                        onGoToStealth = { openFromRow(Destination.STEALTH) },
                        endpointModifier = Modifier.focusRequester(rowFocus.getValue(Destination.ENDPOINT)),
                        stealthModifier = Modifier.focusRequester(rowFocus.getValue(Destination.STEALTH)),
                        onBack = ::goBack,
                    )
                    Destination.TWO_HOP -> TwoHopScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        psiphonRegions = psiphonRegions,
                        onBack = ::goBack,
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
                    Destination.STEALTH -> StealthScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onBack = ::goBack,
                    )
                    Destination.NETWORK -> NetworkScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onBack = ::goBack,
                    )
                    Destination.SAFETY -> SafetyScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onBack = ::goBack,
                    )
                    Destination.ROUTING_RULES -> RoutingRulesScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onBack = ::goBack,
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
        }
    }
}

/** Where each Advanced hub row leads. */
private val AdvancedGroup.destination: Destination get() = when (this) {
    AdvancedGroup.AETHER -> Destination.PROTOCOL
    AdvancedGroup.NODES -> Destination.CHAIN
    AdvancedGroup.TWO_HOP -> Destination.TWO_HOP
    AdvancedGroup.ROUTING -> Destination.ROUTING_RULES
    AdvancedGroup.NETWORK -> Destination.NETWORK
    AdvancedGroup.SAFETY -> Destination.SAFETY
    AdvancedGroup.DIAGNOSTICS -> Destination.DIAGNOSTICS
}

@Composable
private fun AetherAppShell(
    selected: Tab?,
    isTelevision: Boolean,
    onSelect: (Tab) -> Unit,
    onControllerBack: () -> Boolean,
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
            FamilyHeader()
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
private fun FamilyHeader() {
    val colors = AetherTheme.colors
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
        // Balances the logo, so the name stays centred.
        Spacer(Modifier.size(40.dp))
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
