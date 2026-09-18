package com.whitedns.whiteaesther.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.WidgetActionActivity
import com.whitedns.whiteaesther.core.AppLocale

/**
 * Connect and disconnect from the home screen.
 *
 * The same argument as the quick settings tile: for a user who has the app set
 * up, opening it to press one button is the only step left, and this removes
 * it. The widget is the version of that for people who do not use the shade.
 */
class AetherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
    ) {
        val stage = EngineStatusStore.status.value.stage
        widgetIds.forEach { manager.updateAppWidget(it, draw(context, stage)) }
    }

    companion object {
        /**
         * Redraws every widget on the home screen.
         *
         * Called when the engine's stage moves, which is the only time this has
         * anything new to say. The provider's own update period is zero for
         * that reason: the platform's schedule is half-hourly at best and would
         * wake the device to redraw something unchanged.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(
                    ComponentName(context, AetherWidgetProvider::class.java),
                )
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val views = draw(context, EngineStatusStore.status.value.stage)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun draw(context: Context, stage: EngineStage): RemoteViews {
            val face = WidgetFaces.of(stage)
            // Resolved per draw, not once: a provider is not kept alive between
            // updates, and the language the user chose in the app is not the
            // launcher's. Reading it here is what stops a Persian user seeing
            // an English widget beside a Persian app.
            val words = AppLocale.wrap(context.applicationContext)
            return RemoteViews(context.packageName, R.layout.widget_aether).apply {
                setTextViewText(R.id.widget_state, words.getString(face.state))
                setTextViewText(R.id.widget_detail, words.getString(R.string.app_name))
                setTextViewText(R.id.widget_action, words.getString(face.action))
                setInt(
                    R.id.widget_dot,
                    "setColorFilter",
                    ContextCompat.getColor(context, face.dot),
                )
                setInt(R.id.widget_action, "setBackgroundResource", face.actionBackground)
                setTextColor(
                    R.id.widget_action,
                    ContextCompat.getColor(context, face.actionText),
                )
                setOnClickPendingIntent(R.id.widget_action, tap(context, face.tap))
                // The card behind the button always opens the app, whatever the
                // button is doing. A widget with one target and no way back to
                // the app is a worse trade than a second tap target.
                setOnClickPendingIntent(R.id.widget_root, tap(context, null))
            }
        }

        /**
         * What a tap runs.
         *
         * Through an activity rather than straight to the service: starting a
         * foreground service from a broadcast is refused on recent Android, and
         * the first connection needs `VpnService.prepare`, whose consent dialog
         * only an activity can show. The activity is invisible and finishes at
         * once.
         */
        private fun tap(context: Context, action: WidgetAction?): PendingIntent? {
            if (action == WidgetAction.NONE) return null
            val intent = Intent(context, WidgetActionActivity::class.java)
                .setAction(
                    when (action) {
                        WidgetAction.CONNECT -> WidgetActionActivity.ACTION_CONNECT
                        WidgetAction.STOP -> WidgetActionActivity.ACTION_STOP
                        else -> WidgetActionActivity.ACTION_OPEN
                    },
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context,
                // One per action, or the second would silently reuse the
                // first's intent and every button would do the same thing.
                (action?.ordinal ?: -1) + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
