package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.VpnClients
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class VpnGuardTest {
    @Test fun noNetworkMeansNoClient() {
        val clients = VpnClients()
        assertNull(clients.client(OkHttpClient()))
    }

    @Test fun lossCancelsInFlightAndPreventsNewCalls() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val clients = VpnClients()
            val network = Any()
            clients.available(network, SocketFactory.getDefault())
            val finished = CountDownLatch(1)
            val call = clients.client(OkHttpClient())!!.newCall(Request.Builder().url(server.url("/api/remote/login")).build())
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { finished.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
            })
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            clients.lost(network)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(call.isCanceled())
            assertNull(clients.client(OkHttpClient()))
        }
    }

    @Test fun lostNetworkSocketCannotFallBackToDefault() {
        val clients = VpnClients()
        val network = Any()
        val failing = object : SocketFactory() {
            override fun createSocket(): Socket = throw IOException("VPN lost")
            override fun createSocket(host: String?, port: Int): Socket = throw IOException("VPN lost")
            override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = throw IOException("VPN lost")
            override fun createSocket(host: InetAddress?, port: Int): Socket = throw IOException("VPN lost")
            override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = throw IOException("VPN lost")
        }
        clients.available(network, failing)
        val client = clients.client(OkHttpClient())!!
        assertSame(failing, client.socketFactory)
        clients.lost(network)
        try { client.newCall(Request.Builder().url("http://127.0.0.1:1/api/remote/login").build()).execute(); fail() } catch (_: IOException) { }
    }
}
