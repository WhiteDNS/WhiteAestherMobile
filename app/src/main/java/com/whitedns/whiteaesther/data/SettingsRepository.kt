package com.whitedns.whiteaesther.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "whiteaesther_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            mode = enumValueOrDefault(preferences[MODE], EngineMode.TUN),
            proxyPort = preferences[PROXY_PORT]?.coerceIn(1_024, 65_535) ?: 1819,
            transport = enumValueOrDefault(preferences[TRANSPORT], TunnelProtocol.AUTO),
            scanStrategy = enumValueOrDefault(preferences[SCAN], ScanStrategy.BALANCED),
            dualStack = preferences[DUAL_STACK] ?: true,
            validationEnabled = preferences[VALIDATION] ?: true,
            noizeProfile = preferences[NOIZE] ?: "firewall",
            endpointMode = enumValueOrDefault(preferences[ENDPOINT_MODE], EndpointMode.AUTOMATIC),
            customEndpoint = preferences[CUSTOM_ENDPOINT].orEmpty(),
            customEndpointProtocol = preferences[CUSTOM_ENDPOINT_PROTOCOL]
                ?.let { name -> TunnelProtocol.entries.firstOrNull { it.name == name } },
            themeMode = enumValueOrDefault(preferences[THEME_MODE], ThemeMode.SYSTEM),
            showAdvanced = preferences[SHOW_ADVANCED] ?: false,
            fragmentTls = preferences[FRAGMENT_TLS] ?: false,
            encryptedHello = preferences[ENCRYPTED_HELLO] ?: false,
            chain = ChainSettings.decode(preferences[CHAIN]),
            splitTunnel = SplitTunnel.decode(preferences[SPLIT_TUNNEL]),
        )
    }

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences[MODE] = settings.mode.name
            preferences[PROXY_PORT] = settings.proxyPort
            preferences[TRANSPORT] = settings.transport.name
            preferences[SCAN] = settings.scanStrategy.name
            preferences[DUAL_STACK] = settings.dualStack
            preferences[VALIDATION] = settings.validationEnabled
            preferences[NOIZE] = settings.noizeProfile
            preferences[ENDPOINT_MODE] = settings.endpointMode.name
            preferences[CUSTOM_ENDPOINT] = settings.customEndpoint
            preferences[CUSTOM_ENDPOINT_PROTOCOL] = settings.customEndpointProtocol?.name.orEmpty()
            preferences[THEME_MODE] = settings.themeMode.name
            preferences[SHOW_ADVANCED] = settings.showAdvanced
            preferences[FRAGMENT_TLS] = settings.fragmentTls
            preferences[ENCRYPTED_HELLO] = settings.encryptedHello
            preferences[CHAIN] = settings.chain.encode()
            preferences[SPLIT_TUNNEL] = settings.splitTunnel.encode()
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val TRANSPORT = stringPreferencesKey("transport")
        val SCAN = stringPreferencesKey("scan")
        val DUAL_STACK = booleanPreferencesKey("dual_stack")
        val VALIDATION = booleanPreferencesKey("validation")
        val NOIZE = stringPreferencesKey("noize")
        val ENDPOINT_MODE = stringPreferencesKey("endpoint_mode")
        val CUSTOM_ENDPOINT = stringPreferencesKey("custom_endpoint")
        val CUSTOM_ENDPOINT_PROTOCOL = stringPreferencesKey("custom_endpoint_protocol")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHOW_ADVANCED = booleanPreferencesKey("show_advanced")
        val FRAGMENT_TLS = booleanPreferencesKey("fragment_tls")
        val ENCRYPTED_HELLO = booleanPreferencesKey("encrypted_hello")
        // Stored whole rather than spread across keys: the shape is a list
        // of sources, which preferences have no type for.
        val CHAIN = stringPreferencesKey("chain")
        val SPLIT_TUNNEL = stringPreferencesKey("split_tunnel")
    }
}
