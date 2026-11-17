package com.whitedns.whiteaesther.data

/**
 * The pages of Settings > Advanced, and which settings each one owns.
 *
 * The method itself (Automatic, or which carrier), its plain settings
 * (Psiphon's country, Tor's bridges) and VPN or proxy-only are chosen on the
 * connect screen and nowhere here, so no page owns them and reset leaves them alone.
 *
 * [isDefault] drives the "changed" badge on the hub, so someone who tuned the
 * app months ago can see where. [reset] puts a page's choices back. It leaves
 * alone anything the user typed or collected (bridge lines, subscriptions,
 * rules, a custom endpoint address, LAN credentials, the Worker), because
 * losing those is not undone by choosing the option again.
 */
enum class AdvancedGroup {
    AETHER,
    NODES,
    TWO_HOP,
    ROUTING,
    NETWORK,
    SAFETY,
    DIAGNOSTICS;

    fun isDefault(settings: AppSettings): Boolean = reset(settings) == settings

    fun reset(settings: AppSettings): AppSettings = when (this) {
        AETHER -> settings.copy(
            transport = DEFAULT.transport, scanStrategy = DEFAULT.scanStrategy,
            endpointMode = DEFAULT.endpointMode,
            noizeProfile = DEFAULT.noizeProfile, fragmentTls = DEFAULT.fragmentTls,
            encryptedHello = DEFAULT.encryptedHello, tlsOptions = DEFAULT.tlsOptions,
            tlsGroups = DEFAULT.tlsGroups, wgKeepalive = DEFAULT.wgKeepalive,
        )
        NODES -> settings.copy(
            chain = settings.chain.copy(
                enabled = DEFAULT.chain.enabled, throughTunnel = DEFAULT.chain.throughTunnel,
            ),
        )
        TWO_HOP -> settings.copy(secondCarrier = DEFAULT.secondCarrier)
        ROUTING -> settings.copy(
            routingPresets = DEFAULT.routingPresets, routeSniff = DEFAULT.routeSniff,
        )
        NETWORK -> settings.copy(
            proxyPort = DEFAULT.proxyPort, lanSharing = DEFAULT.lanSharing,
            addresses = DEFAULT.addresses, dnsServers = DEFAULT.dnsServers,
            upstreamProxy = DEFAULT.upstreamProxy,
        )
        SAFETY -> settings.copy(
            killSwitch = DEFAULT.killSwitch, strictKillSwitch = DEFAULT.strictKillSwitch,
            validationEnabled = DEFAULT.validationEnabled, autoReprovision = DEFAULT.autoReprovision,
        )
        DIAGNOSTICS -> settings.copy(engineLogLevel = DEFAULT.engineLogLevel)
    }

    companion object {
        private val DEFAULT = AppSettings()

        fun resetAll(settings: AppSettings): AppSettings =
            entries.fold(settings) { current, group -> group.reset(current) }
    }
}
