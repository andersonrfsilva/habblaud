package br.com.xlinx.habblaud

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.concurrent.CountDownLatch

class TestVpnService : VpnService() {
    inner class LocalBinder : Binder() { val service get() = this@TestVpnService }
    private val binder = LocalBinder()
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var reading = false
    private var reader: Thread? = null
    val packetReceived = CountDownLatch(1)
    val readinessPacket = CountDownLatch(1)

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    fun establishTunnel() {
        tunnel = Builder().setSession("Habblaud instrumentation")
            .addAddress("10.251.0.1", 32)
            .addRoute("100.64.0.1", 32)
            .establish() ?: error("VPN permission not granted or tunnel could not be established")
        val descriptor = tunnel ?: error("VPN tunnel missing")
        reading = true
        reader = Thread {
            val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            val packet = ByteArray(32767)
            try {
                while (reading) {
                    if (Os.poll(arrayOf(poll), 100) <= 0 || !reading) continue
                    val length = Os.read(descriptor.fileDescriptor, packet, 0, packet.size)
                    if (length >= 24 && packet[16] == 100.toByte() && packet[17] == 64.toByte() &&
                        packet[18] == 0.toByte() && packet[19] == 1.toByte()) {
                        val headerSize = (packet[0].toInt() and 0x0f) * 4
                        if (length >= headerSize + 4) {
                            val port = (packet[headerSize + 2].toInt() and 0xff) * 256 + (packet[headerSize + 3].toInt() and 0xff)
                            if (packet[9].toInt() == 17 && port == 14749) readinessPacket.countDown()
                            if (packet[9].toInt() == 6 && port == 14748) packetReceived.countDown()
                        }
                    }
                }
            } catch (_: android.system.ErrnoException) { }
        }.apply { isDaemon = true; start() }
    }

    fun closeTunnel() {
        reading = false
        reader?.join(2_000)
        reader = null
        tunnel?.close()
        tunnel = null
    }
    override fun onDestroy() { closeTunnel(); super.onDestroy() }
}
