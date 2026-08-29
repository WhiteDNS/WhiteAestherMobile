package com.whitedns.whiteaesther.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/** True only inside the television branch of the shared UI. */
internal val LocalTvMode = staticCompositionLocalOf { false }

/**
 * Compose clickables already handle D-pad centre and Enter. Gamepads commonly
 * report their primary action as BUTTON_A, so handle that key explicitly and
 * consume it before the clickable can dispatch a duplicate action.
 */
internal fun Modifier.tvControllerActivation(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    if (!LocalTvMode.current || !enabled) return@composed this
    onPreviewKeyEvent { event ->
        val activate = event.type == KeyEventType.KeyUp &&
            event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A
        if (activate) onClick()
        activate
    }
}

/** Treat the conventional gamepad B button as Back on television. */
internal fun Modifier.tvControllerBack(onBack: () -> Boolean): Modifier = composed {
    if (!LocalTvMode.current) return@composed this
    onPreviewKeyEvent { event ->
        val back = event.type == KeyEventType.KeyUp &&
            event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_B
        back && onBack()
    }
}

/**
 * Text fields keep left/right for cursor movement. Up/down leave the field so
 * a remote cannot become trapped, including inside multiline routing rules.
 */
internal fun Modifier.tvTextFieldNavigation(): Modifier = composed {
    if (!LocalTvMode.current) return@composed this
    val focusManager = LocalFocusManager.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.nativeKeyEvent.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
            else -> false
        }
    }
}

/** Give Material text fields the same persistent focus outline as other controls. */
@Composable
internal fun Modifier.tvTextFieldSupport(
    interactionSource: MutableInteractionSource,
): Modifier = controllerFocus(
    interactionSource = interactionSource,
    shape = RoundedCornerShape(14.dp),
).tvTextFieldNavigation()
