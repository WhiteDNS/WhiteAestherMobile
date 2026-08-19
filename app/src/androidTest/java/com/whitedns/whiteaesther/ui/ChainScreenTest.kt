package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.whitedns.whiteaesther.ChainState
import com.whitedns.whiteaesther.EndpointScannerState
import com.whitedns.whiteaesther.core.ChainNode
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.ChainSettings
import com.whitedns.whiteaesther.data.ChainSource
import com.whitedns.whiteaesther.data.EngineMode
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChainScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var saved: AppSettings? = null
    private var selected: String? = null

    private fun setApp(
        initial: AppSettings = AppSettings(),
        status: EngineStatus = EngineStatus(),
        chainState: ChainState = ChainState(),
    ) {
        compose.setContent {
            WhiteAestherTheme {
                var current by remember { mutableStateOf(initial) }
                WhiteAestherApp(
                    settings = current,
                    engineStatus = status,
                    endpointScannerState = EndpointScannerState(),
                    chainState = chainState,
                    nativeVersion = "1.5.0+android.0.2.0",
                    onSettingsChange = { current = it; saved = it },
                    onConnect = {},
                    onStop = {},
                    onScanEndpoints = {},
                    onTestEndpoint = {},
                    onCancelEndpointScan = {},
                    onSelectChainNode = { selected = it },
                    batteryExempt = true,
                )
            }
        }
    }

    private fun openChain() {
        compose.onNodeWithTag("tab-routes").performClick()
        compose.onNodeWithText("Exit chain").performScrollTo().performClick()
    }

    @Test
    fun theChainIsOffAndItsControlsAreHiddenUntilItIsOn() {
        setApp()
        openChain()

        compose.onNodeWithTag("chain-switch").assertExists()
        // Nothing to configure until it is on. Showing a subscription field on a
        // feature that is off invites setting it up and believing it is running.
        compose.onNodeWithTag("chain-source-field").assertDoesNotExist()
        compose.onNodeWithTag("chain-through-tunnel-switch").assertDoesNotExist()
    }

    @Test
    fun turningItOnRevealsTheSourcesAndTheTunnelChoice() {
        setApp()
        openChain()
        compose.onNodeWithTag("chain-switch").performClick()

        assertEquals(true, saved?.chain?.enabled)
        compose.onNodeWithTag("chain-source-field").performScrollTo().assertExists()
        compose.onNodeWithTag("chain-through-tunnel-switch").performScrollTo().assertExists()
        // On by default. It is what hides the node's address from the local
        // network, so it should never be something a user has to discover.
        assertEquals(true, saved?.chain?.throughTunnel)
    }

    @Test
    fun onlyALinkCanBeAddedAsASubscription() {
        setApp(AppSettings(chain = ChainSettings(enabled = true)))
        openChain()

        compose.onNodeWithTag("chain-add-source").performScrollTo().assertIsNotEnabled()

        // mihomo would also accept a file path here, which on a phone is a path
        // the app cannot read -- the config loads and the provider is silently
        // empty for ever.
        compose.onNodeWithTag("chain-source-field").performScrollTo()
            .performTextInput("/sdcard/nodes.yaml")
        compose.onNodeWithTag("chain-add-source").assertIsNotEnabled()
    }

    @Test
    fun addingASubscriptionNamesItAfterItsHost() {
        setApp(AppSettings(chain = ChainSettings(enabled = true)))
        openChain()

        compose.onNodeWithTag("chain-source-field").performScrollTo()
            .performTextInput("https://nodes.example.com:2096/token")
        compose.onNodeWithTag("chain-add-source").performScrollTo().performClick()

        val source = saved?.chain?.sources?.single()
        assertEquals("https://nodes.example.com:2096/token", source?.url)
        assertEquals("nodes.example.com", source?.name)
        assertEquals(true, source?.enabled)
    }

    @Test
    fun withoutAConnectionTheListSaysWhyRatherThanShowingNothing() {
        setApp(
            AppSettings(
                chain = ChainSettings(
                    enabled = true,
                    sources = listOf(ChainSource("Test", "https://example.invalid/sub")),
                ),
            ),
        )
        openChain()

        // An empty list would read as "your subscription has no nodes", which is
        // a different problem with a different fix.
        compose.onNodeWithText("Connect to load your nodes", substring = true)
            .performScrollTo().assertExists()
    }

    @Test
    fun connectedNodesListAndCanBePicked() {
        setApp(
            initial = AppSettings(
                chain = ChainSettings(
                    enabled = true,
                    sources = listOf(ChainSource("Test", "https://example.invalid/sub")),
                ),
            ),
            status = EngineStatus(EngineStage.CONNECTED, EngineMode.TUN),
            chainState = ChainState(
                available = true,
                nodes = listOf(
                    ChainNode("tokyo-01", "Vless", 180),
                    ChainNode("osaka-02", "Trojan", null),
                ),
                selected = "tokyo-01",
            ),
        )
        openChain()

        compose.onNodeWithTag("chain-node-tokyo-01").performScrollTo().assertExists()
        compose.onNodeWithText("180 ms").assertExists()
        compose.onNodeWithTag("chain-node-osaka-02").performScrollTo().performClick()
        assertEquals("osaka-02", selected)
    }

    @Test
    fun aChainOnProxyCoverageSaysSoInsteadOfFailingAtConnectTime() {
        setApp(
            AppSettings(
                mode = EngineMode.PROXY,
                chain = ChainSettings(enabled = true),
            ),
        )
        openChain()

        // The service refuses this combination. Saying so here means the user
        // finds out while looking at the setting, not after a failed connect.
        compose.onNodeWithText("Coverage has to be whole device").assertExists()
    }

    @Test
    fun routesSummarisesTheChainWithoutOpeningIt() {
        setApp(
            AppSettings(
                chain = ChainSettings(
                    enabled = true,
                    throughTunnel = false,
                    sources = listOf(ChainSource("Test", "https://example.invalid/sub")),
                ),
            ),
        )
        compose.onNodeWithTag("tab-routes").performClick()

        compose.onNodeWithText("On, nodes dialled directly").performScrollTo().assertExists()
    }
}
