package br.com.xlinx.habblaud.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

class VpnGuard(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var network: Network? = null
    private var client: OkHttpClient? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(this@VpnGuard) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (this@VpnGuard.network != network) {
                        clear()
                        this@VpnGuard.network = network
                    }
                } else if (this@VpnGuard.network == network) clear()
            }
        }
        override fun onLost(network: Network) {
            synchronized(this@VpnGuard) { if (this@VpnGuard.network == network) clear() }
        }
    }

    init {
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), callback)
    }

    @Synchronized fun client(base: OkHttpClient): OkHttpClient? {
        val current = network ?: return null
        return client ?: base.newBuilder().dispatcher(Dispatcher()).connectionPool(ConnectionPool()).socketFactory(current.socketFactory).build().also { client = it }
    }

    private fun clear() {
        client?.dispatcher?.cancelAll()
        client?.connectionPool?.evictAll()
        client = null
        network = null
    }

    override fun close() {
        manager.unregisterNetworkCallback(callback)
        synchronized(this) { clear() }
    }
}
