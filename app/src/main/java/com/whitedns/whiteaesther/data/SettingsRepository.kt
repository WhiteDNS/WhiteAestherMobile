package com.whitedns.whiteaesther.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.whitedns.whiteaesther.core.AppLocale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "whiteaesther_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            mode = enumValueOrDefault(preferences[MODE], EngineMode.TUN),
            proxyPort = preferences[PROXY_PORT]?.coerceIn(1_024, 65_535) ?: 1819,
            transport = enumValueOrDefault(preferences[TRANSPORT], TunnelProtocol.AUTO),
            carrier = enumValueOrDefault(preferences[CARRIER], Carrier.AETHER),
            scanStrategy = enumValueOrDefault(preferences[SCAN], ScanStrategy.BALANCED),
            dualStack = preferences[DUAL_STACK] ?: true,
            validationEnabled = preferences[VALIDATION] ?: true,
            noizeProfile = preferences[NOIZE] ?: "firewall",
            endpointMode = enumValueOrDefault(preferences[ENDPOINT_MODE], EndpointMode.AUTOMATIC),
            customEndpoint = preferences[CUSTOM_ENDPOINT].orEmpty(),
            customEndpointProtocol = preferences[CUSTOM_ENDPOINT_PROTOCOL]
                ?.let { name -> TunnelProtocol.entries.firstOrNull { it.name == name } },
            themeMode = enumValueOrDefault(preferences[THEME_MODE], ThemeMode.SYSTEM),
            language = enumValueOrDefault(preferences[LANGUAGE], AppLanguage.SYSTEM),
            showAdvanced = preferences[SHOW_ADVANCED] ?: false,
            fragmentTls = preferences[FRAGMENT_TLS] ?: false,
            encryptedHello = preferences[ENCRYPTED_HELLO] ?: false,
            chain = ChainSettings.decode(preferences[CHAIN]),
            splitTunnel = SplitTunnel.decode(preferences[SPLIT_TUNNEL]),
            lanSharing = preferences[LAN_SHARING] ?: false,
            lanUsername = preferences[LAN_USERNAME].orEmpty(),
            lanPassword = preferences[LAN_PASSWORD].orEmpty(),
            routeBlock = preferences[ROUTE_BLOCK].orEmpty(),
            routeDirect = preferences[ROUTE_DIRECT].orEmpty(),
            killSwitch = preferences[KILL_SWITCH] ?: false,
            strictKillSwitch = preferences[STRICT_KILL_SWITCH] ?: false,
            wgKeepalive = preferences[WG_KEEPALIVE]?.coerceIn(0, 300) ?: 25,
            upstreamProxy = preferences[UPSTREAM_PROXY].orEmpty(),
            dnsServers = preferences[DNS_SERVERS].orEmpty(),
            routeSniff = preferences[ROUTE_SNIFF] ?: true,
            autoReprovision = preferences[AUTO_REPROVISION] ?: true,
            engineLogLevel = preferences[ENGINE_LOG_LEVEL].orEmpty(),
            tlsGroups = preferences[TLS_GROUPS].orEmpty(),
            batteryRequestIgnored = preferences[BATTERY_REQUEST_IGNORED] ?: false,
            batteryNoticeDismissed = preferences[BATTERY_NOTICE_DISMISSED] ?: false,
        )
    }

    suspend fun save(settings: AppSettings) {
        // Mirrored out to SharedPreferences because the language has to be
        // readable before the activity exists, where nothing can suspend.
        AppLocale.remember(context, settings.language)
        context.settingsDataStore.edit { preferences ->
            preferences[MODE] = settings.mode.name
            preferences[PROXY_PORT] = settings.proxyPort
            preferences[TRANSPORT] = settings.transport.name
            preferences[CARRIER] = settings.carrier.name
            preferences[SCAN] = settings.scanStrategy.name
            preferences[DUAL_STACK] = settings.dualStack
            preferences[VALIDATION] = settings.validationEnabled
            preferences[NOIZE] = settings.noizeProfile
            preferences[ENDPOINT_MODE] = settings.endpointMode.name
            preferences[CUSTOM_ENDPOINT] = settings.customEndpoint
            preferences[CUSTOM_ENDPOINT_PROTOCOL] = settings.customEndpointProtocol?.name.orEmpty()
            preferences[THEME_MODE] = settings.themeMode.name
            preferences[LANGUAGE] = settings.language.name
            preferences[SHOW_ADVANCED] = settings.showAdvanced
            preferences[FRAGMENT_TLS] = settings.fragmentTls
            preferences[ENCRYPTED_HELLO] = settings.encryptedHello
            preferences[CHAIN] = settings.chain.encode()
            preferences[SPLIT_TUNNEL] = settings.splitTunnel.encode()
            preferences[LAN_SHARING] = settings.lanSharing
            preferences[LAN_USERNAME] = settings.lanUsername
            preferences[LAN_PASSWORD] = settings.lanPassword
            preferences[ROUTE_BLOCK] = settings.routeBlock
            preferences[ROUTE_DIRECT] = settings.routeDirect
            preferences[KILL_SWITCH] = settings.killSwitch
            preferences[STRICT_KILL_SWITCH] = settings.strictKillSwitch
            preferences[WG_KEEPALIVE] = settings.wgKeepalive
            preferences[UPSTREAM_PROXY] = settings.upstreamProxy
            preferences[DNS_SERVERS] = settings.dnsServers
            preferences[ROUTE_SNIFF] = settings.routeSniff
            preferences[AUTO_REPROVISION] = settings.autoReprovision
            preferences[ENGINE_LOG_LEVEL] = settings.engineLogLevel
            preferences[TLS_GROUPS] = settings.tlsGroups
            preferences[BATTERY_REQUEST_IGNORED] = settings.batteryRequestIgnored
            preferences[BATTERY_NOTICE_DISMISSED] = settings.batteryNoticeDismissed
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val TRANSPORT = stringPreferencesKey("transport")
        val CARRIER = stringPreferencesKey("carrier")
        val SCAN = stringPreferencesKey("scan")
        val DUAL_STACK = booleanPreferencesKey("dual_stack")
        val VALIDATION = booleanPreferencesKey("validation")
        val NOIZE = stringPreferencesKey("noize")
        val ENDPOINT_MODE = stringPreferencesKey("endpoint_mode")
        val CUSTOM_ENDPOINT = stringPreferencesKey("custom_endpoint")
        val CUSTOM_ENDPOINT_PROTOCOL = stringPreferencesKey("custom_endpoint_protocol")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val SHOW_ADVANCED = booleanPreferencesKey("show_advanced")
        val FRAGMENT_TLS = booleanPreferencesKey("fragment_tls")
        val ENCRYPTED_HELLO = booleanPreferencesKey("encrypted_hello")
        // Stored whole rather than spread across keys: the shape is a list
        // of sources, which preferences have no type for.
        val CHAIN = stringPreferencesKey("chain")
        val SPLIT_TUNNEL = stringPreferencesKey("split_tunnel")
        val ROUTE_BLOCK = stringPreferencesKey("route_block")
        val ROUTE_DIRECT = stringPreferencesKey("route_direct")
        val KILL_SWITCH = booleanPreferencesKey("kill_switch")
        val STRICT_KILL_SWITCH = booleanPreferencesKey("strict_kill_switch")

        // Seven settings the engine has always read and the app never sent.
        val WG_KEEPALIVE = intPreferencesKey("wg_keepalive")
        val UPSTREAM_PROXY = stringPreferencesKey("upstream_proxy")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val ROUTE_SNIFF = booleanPreferencesKey("route_sniff")
        val AUTO_REPROVISION = booleanPreferencesKey("auto_reprovision")
        val ENGINE_LOG_LEVEL = stringPreferencesKey("engine_log_level")
        val TLS_GROUPS = stringPreferencesKey("tls_groups")

        val LAN_SHARING = booleanPreferencesKey("lan_sharing")
        // Stored as the user typed them: the engine needs the password itself
        // to answer a client, so a hash here would be a hash it cannot use.
        val LAN_USERNAME = stringPreferencesKey("lan_username")
        val LAN_PASSWORD = stringPreferencesKey("lan_password")
        // Both survive a restart deliberately. The first is a fact about this
        // phone that re-asking cannot establish a second time, and the second
        // is an answer the user should not have to give again.
        val BATTERY_REQUEST_IGNORED = booleanPreferencesKey("battery_request_ignored")
        val BATTERY_NOTICE_DISMISSED = booleanPreferencesKey("battery_notice_dismissed")
    }
}
