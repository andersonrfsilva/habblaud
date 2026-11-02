package br.com.xlinx.habblaud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.vpnNetworkRequest
import br.com.xlinx.habblaud.data.VpnRequiredException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VpnGuardInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private fun vpn() = manager.allNetworks.firstOrNull {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    @Test fun vpnRequestDoesNotExcludeVpn() {
        val request = vpnNetworkRequest()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }

    @Test fun noVpnRejectsLoginBeforeAnyConnection() {
        assumeTrue("Requires a device without VPN", vpn() == null)
        MockWebServer().use { server ->
            server.start()
            HabblaudApi(context, "http://100.64.0.1:${server.port}", true, "100.64.0.1").use { api ->
                try {
                    runBlocking { api.login("instrumentation", "instrumentation") }
                    fail("Login must not be sent without VPN")
                } catch (_: VpnRequiredException) { }
            }
            assertEquals(0, server.requestCount)
            assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
        }
    }

}
