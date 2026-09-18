package com.whitedns.whiteaesther.service

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.whitedns.whiteaesther.R

/** What a tap on the widget asks for. */
enum class WidgetAction {
    CONNECT,
    STOP,

    /**
     * Nothing. The engine is already doing the only thing a tap could ask for,
     * and a second command would queue behind the first and make the wait
     * longer -- the same reason the button is inert during STOPPING in the app.
     */
    NONE,
}

/** Everything the widget draws for one stage, and the tap it offers. */
data class WidgetFace(
    @StringRes val state: Int,
    @ColorRes val dot: Int,
    @StringRes val action: Int,
    @DrawableRes val actionBackground: Int,
    @ColorRes val actionText: Int,
    val tap: WidgetAction,
)

/**
 * The widget's whole appearance, as a function of the engine's stage.
 *
 * Kept apart from the provider so the mapping can be read and tested without a
 * launcher. The one rule worth stating: a widget that changed on tap would
 * report a connection that is still negotiating as done, and stay on for one
 * that failed, so every state here comes from the engine and none from the tap.
 */
object WidgetFaces {
    fun of(stage: EngineStage): WidgetFace = when (stage) {
        EngineStage.IDLE -> WidgetFace(
            state = R.string.tile_off,
            dot = R.color.widget_signal_idle,
            action = R.string.widget_connect,
            actionBackground = R.drawable.widget_button,
            actionText = R.color.widget_on_brand,
            tap = WidgetAction.CONNECT,
        )

        // Offered as a stop, not as nothing. A search that is going nowhere is
        // exactly when a user reaches for this, and leaving them no way out of
        // it from the home screen is what sends them into the app to force-stop
        // the thing.
        EngineStage.PREPARING, EngineStage.CONNECTING -> WidgetFace(
            state = R.string.tile_connecting,
            dot = R.color.widget_signal_working,
            action = R.string.widget_disconnect,
            actionBackground = R.drawable.widget_button_quiet,
            actionText = R.color.widget_text,
            tap = WidgetAction.STOP,
        )

        EngineStage.CONNECTED -> WidgetFace(
            state = R.string.tile_on,
            dot = R.color.widget_signal_live,
            action = R.string.widget_disconnect,
            actionBackground = R.drawable.widget_button_quiet,
            actionText = R.color.widget_text,
            tap = WidgetAction.STOP,
        )

        EngineStage.STOPPING -> WidgetFace(
            state = R.string.tile_stopping,
            dot = R.color.widget_signal_working,
            action = R.string.widget_working,
            actionBackground = R.drawable.widget_button_quiet,
            actionText = R.color.widget_text_dim,
            tap = WidgetAction.NONE,
        )

        // A failure offers the retry, because that is what the user wants from
        // it and the alternative is a widget that only reports bad news.
        EngineStage.ERROR -> WidgetFace(
            state = R.string.tile_failed,
            dot = R.color.widget_signal_failed,
            action = R.string.widget_connect,
            actionBackground = R.drawable.widget_button,
            actionText = R.color.widget_on_brand,
            tap = WidgetAction.CONNECT,
        )
    }
}
