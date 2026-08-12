package com.whitedns.whiteaesther.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.data.AppSettings
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
    TRAFFIC(Tab.TRAFFIC),
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
    nativeVersion: String?,
    logEntries: List<LogEntry> = emptyList(),
    onSettingsChange: (AppSettings) -> Unit,
    onConnect: (AppSettings) -> Unit,
    onStop: () -> Unit,
    onScanEndpoints: (AppSettings) -> Unit,
    onTestEndpoint: (AppSettings) -> Unit,
    onCancelEndpointScan: () -> Unit,
    onShareReport: (String) -> Unit = {},
    onCopyReport: (String) -> Unit = {},
    onClearLog: () -> Unit = {},
    batteryExempt: Boolean = true,
    onRequestBatteryExemption: () -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    val colors = AetherTheme.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ink1)
            .statusBarsPadding(),
    ) {
        Box(Modifier.weight(1f)) {
            when (destination) {
                Destination.HOME -> HomeScreen(
                    settings = settings,
                    status = engineStatus,
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
                    onGoToEndpoint = { destination = Destination.ENDPOINT },
                    onGoToTraffic = { destination = Destination.TRAFFIC },
                )
                Destination.ROUTES -> RoutesScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onGoToEndpoint = { destination = Destination.ENDPOINT },
                )
                Destination.ENDPOINT -> EndpointScreen(
                    settings = settings,
                    status = engineStatus,
                    scannerState = endpointScannerState,
                    onSettingsChange = onSettingsChange,
                    onScanEndpoints = onScanEndpoints,
                    onTestEndpoint = onTestEndpoint,
                    onCancelEndpointScan = onCancelEndpointScan,
                    onBack = { destination = Destination.ROUTES },
                )
                Destination.TRAFFIC -> TrafficScreen(
                    settings = settings,
                    status = engineStatus,
                    onSettingsChange = onSettingsChange,
                )
                Destination.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    batteryExempt = batteryExempt,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onGoToDiagnostics = { destination = Destination.DIAGNOSTICS },
                    onGoToAbout = { destination = Destination.ABOUT },
                    onGoToIdentity = { destination = Destination.IDENTITY },
                )
                Destination.DIAGNOSTICS -> DiagnosticsScreen(
                    settings = settings,
                    status = engineStatus,
                    nativeVersion = nativeVersion,
                    entries = logEntries,
                    onBack = { destination = Destination.SETTINGS },
                    onShare = onShareReport,
                    onCopy = onCopyReport,
                    onClear = onClearLog,
                )
                Destination.IDENTITY -> IdentityScreen(
                    settings = settings,
                    onBack = { destination = Destination.SETTINGS },
                )
                Destination.ABOUT -> AboutScreen(
                    nativeVersion = nativeVersion,
                    settings = settings,
                    onBack = { destination = Destination.SETTINGS },
                )
            }
        }

        TabBar(
            selected = destination.tab,
            onSelect = { tab ->
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

/**
 * The selected pill is derived from the measured cell rather than a fixed inset,
 * so the icon and its label share the cell's exact centre line.
 */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = AetherTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.ink1)
            .navigationBarsPadding(),
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
            val indicatorOffset by animateDpAsState(
                targetValue = cellWidth * Tab.entries.indexOf(selected) + 4.dp,
                animationSpec = tween(320),
                label = "tab-indicator",
            )
            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .width(cellWidth - 8.dp)
                    .fillMaxSize()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.brand.copy(alpha = 0.12f))
                    .border(1.dp, colors.brand.copy(alpha = 0.26f), RoundedCornerShape(14.dp)),
            )
            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEach { tab ->
                    val active = tab == selected
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(interaction, indication = null) { onSelect(tab) }
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
