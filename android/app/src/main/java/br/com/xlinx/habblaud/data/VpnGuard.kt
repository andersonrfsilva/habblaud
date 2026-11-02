package br.com.xlinx.habblaud.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.OkHttpClient

class VpnGuard(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val clients = VpnClients()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) clients.available(network, network.socketFactory)
            else clients.lost(network)
        }
        override fun onLost(network: Network) { clients.lost(network) }
    }
    init { manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), callback) }
    fun client(base: OkHttpClient): OkHttpClient? = clients.client(base)
    fun cancelCalls() = clients.cancelCalls()
    override fun close() { manager.unregisterNetworkCallback(callback); clients.clear() }
}
