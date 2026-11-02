package br.com.xlinx.habblaud.data

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import javax.net.SocketFactory

internal class VpnClients {
    private var network: Any? = null
    private var sockets: SocketFactory? = null
    private var client: OkHttpClient? = null

    @Synchronized fun available(network: Any, sockets: SocketFactory) {
        if (this.network != network) {
            clear()
            this.network = network
            this.sockets = sockets
        }
    }

    @Synchronized fun lost(network: Any) { if (this.network == network) clear() }
    @Synchronized fun client(base: OkHttpClient): OkHttpClient? {
        val factory = sockets ?: return null
        return client ?: base.newBuilder().dispatcher(Dispatcher()).connectionPool(ConnectionPool()).socketFactory(factory).build().also { client = it }
    }
    @Synchronized fun cancelCalls() { client?.dispatcher?.cancelAll(); client?.connectionPool?.evictAll() }
    @Synchronized fun clear() { cancelCalls(); client = null; network = null; sockets = null }
}
