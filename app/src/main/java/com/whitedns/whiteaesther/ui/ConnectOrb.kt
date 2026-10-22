package com.whitedns.whiteaesther.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.ui.theme.AetherTheme

/** What the orb shows. Derived from EngineStage, never set directly. */
enum class ConnectState { IDLE, WORKING, LIVE, FAILED }

/**
 * The WhiteDNS / WhiteVPN animated orb, with Aesther's existing input contract.
 * Compose remains the sole click and focus target, including on Android TV.
 */
@Composable
fun ConnectOrb(
    state: ConnectState,
    caption: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: Dp = 240.dp,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = if (AetherTheme.colors.isDark) ConnectionOrbColors.Dark else ConnectionOrbColors.Light
    val fontResource = R.font.fa_bold
    val orbView = remember(context, colors, diameter, fontResource) {
        ConnectionOrbView(context, colors, diameter.value, fontResource)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val action = stringResource(
        when (state) {
            ConnectState.IDLE -> R.string.orb_connect
            ConnectState.WORKING -> if (enabled) R.string.orb_cancel else R.string.orb_stopping
            ConnectState.LIVE -> R.string.orb_disconnect
            ConnectState.FAILED -> R.string.orb_retry
        },
    )
    val connectionDescription = stringResource(
        when (state) {
            ConnectState.IDLE -> R.string.not_connected
            ConnectState.WORKING -> if (enabled) R.string.stage_connecting else R.string.stage_stopping
            ConnectState.LIVE -> R.string.stage_connected
            ConnectState.FAILED -> R.string.couldn_t_connect
        },
    )
    val activate = {
        hostView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onClick()
    }

    DisposableEffect(orbView, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                orbView.resumeAnimation()
            } else {
                orbView.pauseAnimation()
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            orbView.resumeAnimation()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            orbView.pauseAnimation()
        }
    }

    Box(
        modifier = modifier
            .size(diameter)
            .tvControllerActivation(enabled = enabled, onClick = activate)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = caption,
                role = Role.Button,
                onClick = activate,
            )
            .controllerFocus(interaction, CircleShape, enabled)
            .semantics {
                contentDescription = caption
                stateDescription = connectionDescription
            }
            .testTag("connect-orb"),
    ) {
        key(orbView) {
            AndroidView(
                factory = { orbView },
                modifier = Modifier.matchParentSize(),
                update = { view ->
                    view.setConnectionState(state, action)
                    view.setOrbPressed(enabled && pressed)
                    view.alpha = if (enabled) 1f else 0.7f
                },
            )
        }
    }
}
