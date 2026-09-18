package com.whitedns.whiteaesther

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import com.whitedns.whiteaesther.core.AppLocale
import com.whitedns.whiteaesther.data.SettingsRepository
import com.whitedns.whiteaesther.service.AetherVpnService
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Carries out a tap on the home-screen widget, and disappears.
 *
 * An activity rather than the provider itself for two reasons that are not
 * about taste: starting a foreground service straight from a broadcast is
 * refused on recent Android, and the first connection needs the system's VPN
 * consent dialog, which only an activity can show. This one draws nothing and
 * finishes before it would be seen.
 */
class WidgetActionActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_STOP -> stop()
            else -> openApp()
        }
        // The theme already turns the window animation off, which is what keeps
        // this from showing as a flash on the home screen.
        finish()
    }

    private fun connect() {
        // Read from the engine, not from what the widget was showing. A widget
        // is redrawn on a schedule the launcher owns, so the picture a user
        // tapped may be a stage or two behind, and starting a second session
        // over a live one is worse than doing nothing.
        if (EngineStatusStore.status.value.stage !in setOf(EngineStage.IDLE, EngineStage.ERROR)) {
            return
        }
        if (VpnService.prepare(this) != null) {
            // Consent is the app's to ask for, and only once. Opening it puts
            // the user in front of the dialog they would have met anyway.
            openApp()
            return
        }
        val application = applicationContext
        // Deliberately not tied to this activity -- it is finishing, and a
        // scope cancelled with it would cancel the settings read along with it.
        work.launch {
            val settings = SettingsRepository(application).settings.first()
            AetherVpnService.start(
                application,
                settings.toNativeJson(application),
                settings.chainForService().encode(),
                settings.splitTunnel.encode(),
                settings.killSwitch,
                settings.strictKillSwitch,
                settings.carrier,
                settings.secondCarrier,
                settings.torBridge,
                settings.torBridges,
                settings.psiphonRegion,
                settings.automaticCarrier,
            )
        }
    }

    private fun stop() {
        if (EngineStatusStore.status.value.stage == EngineStage.STOPPING) return
        AetherVpnService.stop(applicationContext)
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        /** Outlives the activity, which exists for about one frame. */
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        const val ACTION_CONNECT = "com.whitedns.whiteaesther.widget.CONNECT"
        const val ACTION_STOP = "com.whitedns.whiteaesther.widget.STOP"
        const val ACTION_OPEN = "com.whitedns.whiteaesther.widget.OPEN"
    }
}
