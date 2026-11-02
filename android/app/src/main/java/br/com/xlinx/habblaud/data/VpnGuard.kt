package br.com.xlinx.habblaud.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.OkHttpClient

internal fun vpnNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
    .build()

class VpnGuard(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val clients = VpnClients()
    private val lock = Any()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) clients.available(network, network.socketFactory)
            else clients.lost(network)
        }
        override fun onLost(network: Network) = synchronized(lock) { clients.lost(network) }
    }
    init {
        synchronized(lock) {
            manager.registerNetworkCallback(vpnNetworkRequest(), callback)
            manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }?.let { clients.available(it, it.socketFactory) }
        }
    }
    fun client(base: OkHttpClient): OkHttpClient? = clients.client(base)
    fun cancelCalls() = clients.cancelCalls()
    override fun close() { manager.unregisterNetworkCallback(callback); clients.clear() }
}
