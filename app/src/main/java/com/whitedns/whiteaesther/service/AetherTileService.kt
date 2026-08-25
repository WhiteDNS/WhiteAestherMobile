package com.whitedns.whiteaesther.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.whitedns.whiteaesther.MainActivity
import com.whitedns.whiteaesther.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Connect and disconnect from the notification shade.
 *
 * The tile is the whole interaction for a user who already has the app set up:
 * the settings do not change between sessions, so opening the app to press one
 * button is the only step, and this removes it.
 */
class AetherTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val running = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            .also { scope = it }
        running.launch {
            EngineStatusStore.status.collect { render(it.stage) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (EngineStatusStore.status.value.stage) {
            EngineStage.IDLE, EngineStage.ERROR -> connect()
            // Stopping already, so a second tap has nothing to ask for.
            EngineStage.STOPPING -> Unit
            else -> AetherVpnService.stop(this)
        }
    }

    /**
     * Starts a session, or opens the app when it cannot.
     *
     * `VpnService.prepare` returns an intent the first time, and consent has to
     * be granted by an activity -- a tile cannot show it. Rather than failing
     * quietly in the shade, the app is opened so the user meets the dialog they
     * would have met anyway.
     */
    private fun connect() {
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        val scope = scope ?: return
        scope.launch {
            val settings = SettingsRepository(applicationContext).settings.first()
            AetherVpnService.start(
                applicationContext,
                settings.toNativeJson(applicationContext),
                settings.chain.encode(),
                settings.splitTunnel.encode(),
            )
        }
    }

    // The PendingIntent overload only exists from API 34, and this app runs
    // from 26. Below that the deprecated call is the only one there is, so the
    // warning is about a platform version rather than about this code.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /**
     * Mirrors the engine rather than the last tap.
     *
     * A tile that went active on click would report success for a connection
     * that is still negotiating, and stay active for one that failed.
     */
    private fun render(stage: EngineStage) {
        val tile = qsTile ?: return
        tile.state = when (stage) {
            EngineStage.CONNECTED -> Tile.STATE_ACTIVE
            EngineStage.IDLE, EngineStage.ERROR -> Tile.STATE_INACTIVE
            // Unavailable would grey it out and read as broken; inactive would
            // invite a tap that does nothing. Active-while-working is the
            // closest of the three states the platform allows.
            else -> Tile.STATE_ACTIVE
        }
        // Subtitles arrived in API 29. Below that the tile has its active and
        // inactive states and nothing else, which is the whole answer anyway --
        // the words only add the stage in between.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (stage) {
                EngineStage.IDLE -> "Off"
                EngineStage.PREPARING, EngineStage.CONNECTING -> "Connecting"
                EngineStage.CONNECTED -> "On"
                EngineStage.STOPPING -> "Stopping"
                EngineStage.ERROR -> "Failed"
            }
        }
        tile.updateTile()
    }
}
