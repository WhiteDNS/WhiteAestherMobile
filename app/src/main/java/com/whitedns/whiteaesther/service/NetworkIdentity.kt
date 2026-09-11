package com.whitedns.whiteaesther.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.whitedns.whiteaesther.data.NetworkKey

/**
 * The network underneath the tunnel: whether there is one, and which it is.
 *
 * Underneath, not the default. While a session is up the default network is this
 * app's own interface, which says nothing about the Wi-Fi or the operator a route
 * has to get out of.
 */
object NetworkIdentity {
    data class Snapshot(
        /** Something other than a VPN offers the internet. Not whether it works. */
        val online: Boolean,
        /** See [NetworkKey]; null when the network could not be named. */
        val key: String?,
    )

    fun current(context: Context): Snapshot {
        // Unknown is not offline. A phone that will not say is given the
        // benefit of the doubt, because the alternative is refusing to connect.
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return Snapshot(online = true, key = null)
        val network = underlying(connectivity) ?: return Snapshot(online = false, key = null)
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return Snapshot(online = true, key = null)
        val key = runCatching { keyFor(context, connectivity, network, capabilities) }.getOrNull()
        return Snapshot(online = true, key = key)
    }

    /**
     * Not validated: Android validates against Google, and on a network where
     * the international link is filtered that check fails while the network is
     * exactly the one the user needs a route out of.
     */
    private fun underlying(connectivity: ConnectivityManager): Network? {
        fun usable(network: Network): Boolean =
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        connectivity.activeNetwork?.takeIf(::usable)?.let { return it }
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull(::usable)
    }

    private fun keyFor(
        context: Context,
        connectivity: ConnectivityManager,
        network: Network,
        capabilities: NetworkCapabilities,
    ): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            NetworkKey.cellular(operatorOf(context))
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            local("wifi", connectivity.getLinkProperties(network))
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            local("ethernet", connectivity.getLinkProperties(network))
        else -> "other"
    }

    /**
     * The operator carrying mobile data, not whichever SIM is first: on a
     * dual-SIM phone the two can be different operators that block different
     * things. Needs no permission.
     */
    private fun operatorOf(context: Context): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        val data = SubscriptionManager.getDefaultDataSubscriptionId()
        val scoped = if (data != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            telephony.createForSubscriptionId(data)
        } else {
            telephony
        }
        return scoped.networkOperator
    }

    private fun local(kind: String, link: LinkProperties?): String = NetworkKey.local(
        kind = kind,
        gateway = link?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress,
        dns = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress },
        domains = link?.domains,
    )
}
