package br.com.xlinx.habblaud.data
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
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
    private val atomic = AtomicFile(file)
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
        readSession()
    } catch (_: Exception) {
        // Preserve the file: a transient failure must not erase every server's login.
        // shortcut: uma gravação feita enquanto a leitura falha ainda substitui o arquivo; propagar o erro derrubaria o serviço de avisos
        null
    }

    private fun readSession(): String? {
        val bytes = try {
            readBytes()
        } catch (_: IOException) {
            readBytes()
        } ?: return null

        if (bytes.size < 12 + 16) return discard()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        } catch (_: BadPaddingException) {
            discard()
        } catch (_: KeyPermanentlyInvalidatedException) {
            discard()
        } catch (error: UnrecoverableKeyException) {
            // AndroidKeyStore reports permanent invalidation without a cause.
            if (error.cause != null) throw error
            discard()
        }
    }

    private fun readBytes(): ByteArray? = try {
        atomic.readFully()
    } catch (error: FileNotFoundException) {
        if (file.exists()) throw error
        null
    }

    private fun discard(): String? {
        atomic.delete()
        return null
    }

    actual fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (error: Exception) {
            atomic.failWrite(out)
            throw error
        }
    }
    actual fun clear() {
        atomic.delete()
        check(!file.exists()) { "Could not clear session" }
    }
}
