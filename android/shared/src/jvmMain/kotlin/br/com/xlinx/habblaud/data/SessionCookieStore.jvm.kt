package br.com.xlinx.habblaud.data

import com.github.javakeyring.Keyring
import java.util.concurrent.locks.ReentrantLock

internal actual class SessionLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
internal actual class SecureSessionStorage actual constructor() {
    private companion object {
        const val SERVICE = "br.com.xlinx.zaplaud"
        const val ACCOUNT = "remote-session"
        @Volatile private var memory: String? = null
        private val keyring = if (System.getProperty("zaplaud.keyring") == "memory") null else runCatching { Keyring.create() }.getOrNull()
    }
    actual fun read(): String? = keyring?.let { runCatching { it.getPassword(SERVICE, ACCOUNT) }.getOrNull() } ?: memory
    actual fun write(value: String) {
        memory = value
        keyring?.let { ring ->
            runCatching { ring.setPassword(SERVICE, ACCOUNT, value) }.onFailure {
                System.err.println("Zaplaud: chaveiro indisponível, login só nesta execução")
            }
        }
    }
    actual fun clear() {
        memory = null
        keyring?.let { runCatching { it.deletePassword(SERVICE, ACCOUNT) } }
    }
}
