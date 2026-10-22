package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectOrbTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun touchReachesComposeOnceAndKeepsWorkingAfterStateChanges() {
        var state by mutableStateOf(ConnectState.IDLE)
        var caption by mutableStateOf("Tap to connect")
        var clicks = 0
        compose.setContent {
            WhiteAestherTheme {
                ConnectOrb(state = state, caption = caption, onClick = { clicks++ })
            }
        }

        val orb = compose.onNodeWithTag("connect-orb")
        orb.assertContentDescriptionEquals("Tap to connect").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, clicks)
            state = ConnectState.WORKING
            caption = "Tap to cancel"
        }
        orb.assertContentDescriptionEquals("Tap to cancel").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(2, clicks)
            state = ConnectState.LIVE
            caption = "Tap to stop"
        }
        orb.assertContentDescriptionEquals("Tap to stop").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(3, clicks)
            state = ConnectState.FAILED
            caption = "Tap to retry"
        }
        orb.assertContentDescriptionEquals("Tap to retry").assertIsEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(4, clicks) }
    }

    @Test
    fun stoppingCannotQueueAnotherConnectionAction() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            WhiteAestherTheme {
                ConnectOrb(
                    state = ConnectState.WORKING,
                    caption = "Stopping",
                    enabled = enabled,
                    onClick = { clicks++ },
                )
            }
        }
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("connect-orb").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.onNodeWithTag("connect-orb").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
        )
    }
}
