package br.com.xlinx.habblaud.data
internal actual class SessionLock actual constructor() {
    actual fun lock() { TODO() }
    actual fun unlock() { TODO() }
}
internal actual class SecureSessionStorage actual constructor() {
    actual fun read(): String? = TODO()
    actual fun write(value: String) { TODO() }
    actual fun clear() { TODO() }
}
