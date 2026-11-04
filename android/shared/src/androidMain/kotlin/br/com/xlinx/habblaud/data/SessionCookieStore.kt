package br.com.xlinx.habblaud.data
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
internal actual class SessionLock actual constructor() {
    private val delegate = java.util.concurrent.locks.ReentrantLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
internal actual class SecureSessionStorage actual constructor() {
    private val file = storageContext.filesDir.resolve("remote-session")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("habblaud-remote-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("habblaud-remote-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    actual fun read(): String? = try {
        if (!file.exists()) null else {
            val bytes = file.readBytes(); require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    } catch (_: Exception) { file.delete(); null }
    actual fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        file.writeBytes(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    actual fun clear() { check(!file.exists() || file.delete()) { "Could not clear session" } }
}
