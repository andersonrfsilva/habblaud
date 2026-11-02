package br.com.xlinx.habblaud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.xlinx.habblaud.data.VpnGuard
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VpnTunnelInstrumentedTest {
    private fun awaitVpnGone(manager: ConnectivityManager) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("VPN remained active after cleanup", manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        })
    }

    @Test fun activeVpnClientUsesActualNetworkSocketFactory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertFalse("Disconnect other VPNs before this test", manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        })
        assertNull("Authorize the target package as a VPN before running", VpnService.prepare(context))
        val bound = CountDownLatch(1)
        var service: TestVpnService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as TestVpnService.LocalBinder).service
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        assertTrue(context.bindService(Intent(context, TestVpnService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("VPN service did not bind", bound.await(10, TimeUnit.SECONDS))
            requireNotNull(service).establishTunnel()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var network = manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            while (network == null && System.nanoTime() < deadline) {
                Thread.sleep(50)
                network = manager.allNetworks.firstOrNull {
                    manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            }
            assertNotNull("VPN network not published", network)
            VpnGuard(instrumentation.targetContext).use { guard ->
                val client = guard.client(OkHttpClient())
                assertNotNull("Active VPN was not seeded during initialization", client)
                assertNotSame(OkHttpClient().socketFactory, client!!.socketFactory)
                assertEquals(network!!.socketFactory.javaClass, client.socketFactory.javaClass)
                val call = client!!.newCall(Request.Builder().url("http://100.64.0.1:14748/slow").build())
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { }
                    override fun onResponse(call: Call, response: Response) { response.close() }
                })
                try {
                    assertTrue("The request never entered the VPN tunnel", requireNotNull(service).packetReceived.await(10, TimeUnit.SECONDS))
                } finally {
                    call.cancel()
                }
            }
        } finally {
            try {
                service?.closeTunnel()
            } finally {
                context.unbindService(connection)
                awaitVpnGone(manager)
            }
        }
    }

    @Test fun realTunnelBindsSocketAndLossCancelsPendingCall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertFalse("Disconnect other VPNs before this test", manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        })
        assertNull("Authorize the target package as a VPN before running", VpnService.prepare(context))
        val bound = CountDownLatch(1)
        var service: TestVpnService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as TestVpnService.LocalBinder).service
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        assertTrue(context.bindService(Intent(context, TestVpnService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("VPN service did not bind", bound.await(10, TimeUnit.SECONDS))
            val vpn = requireNotNull(service)
            vpn.establishTunnel()
            VpnGuard(instrumentation.targetContext).use { guard ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val base = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                    if (chain.request().url.encodedPath == "/blocked") {
                        entered.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                    chain.proceed(chain.request())
                }.build()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var client = guard.client(base)
                while (client == null && System.nanoTime() < deadline) {
                    Thread.sleep(50)
                    client = guard.client(base)
                }
                val network = manager.allNetworks.firstOrNull {
                    manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                assertNotNull("VPN network not published", network)
                assertNotNull("VPN guard did not select the tunnel", client)
                val call = client!!.newCall(Request.Builder().url("http://100.64.0.1:14748/slow").build())
                val finished = CountDownLatch(1)
                var failed = false
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { failed = true; finished.countDown() }
                    override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
                })
                assertTrue("The request never entered the VPN tunnel", vpn.packetReceived.await(10, TimeUnit.SECONDS))
                val pending = client.newCall(Request.Builder().url("http://100.64.0.1:14748/blocked").build())
                val pendingFinished = CountDownLatch(1)
                pending.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { pendingFinished.countDown() }
                    override fun onResponse(call: Call, response: Response) { response.close(); pendingFinished.countDown() }
                })
                assertTrue("Second call did not enter the dispatcher", entered.await(10, TimeUnit.SECONDS))
                try {
                    vpn.closeTunnel()
                    val lostDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (guard.client(base) != null && System.nanoTime() < lostDeadline) Thread.sleep(50)
                    assertNull("Guard must not fall back to the default network", guard.client(base))
                    assertTrue("VPN loss must cancel calls still pending", pending.isCanceled())
                } finally {
                    release.countDown()
                }
                assertTrue("Pending call did not finish", pendingFinished.await(10, TimeUnit.SECONDS))
                assertTrue("Call did not finish after VPN loss", finished.await(10, TimeUnit.SECONDS))
                assertTrue("Call unexpectedly received a response", failed)
            }
        } finally {
            try {
                service?.closeTunnel()
            } finally {
                context.unbindService(connection)
                awaitVpnGone(manager)
            }
        }
    }
}
