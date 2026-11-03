package br.com.xlinx.habblaud.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the remote session cookie for the selected origin is retained. */
class SessionCookieStore(context: Context, private val origin: String) : CookieJar {
    private val file = context.filesDir.resolve("remote-session")
    private var cookie: Cookie? = read()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("habblaud-remote-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("habblaud-remote-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun read(): Cookie? = try {
        if (!file.exists()) null else {
            val bytes = file.readBytes()
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            if (data.getString("origin") != origin) null else Cookie.parse(origin.toHttpUrl(), data.getString("cookie"))
                ?.takeIf { it.expiresAt > System.currentTimeMillis() }
        }
    } catch (_: Exception) { file.delete(); null }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.origin() != origin) return
        val value = cookies.lastOrNull { it.name == "habblaud_remote" } ?: return
        if (value.expiresAt <= System.currentTimeMillis()) { clear(); return }
        cookie = value
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = JSONObject().put("origin", origin).put("cookie", value.toString()).toString().toByteArray(Charsets.UTF_8)
            file.writeBytes(cipher.iv + cipher.doFinal(data))
        } catch (e: Exception) {
            cookie = null
            file.delete()
            throw IllegalStateException("Não foi possível guardar a sessão com segurança", e)
        }
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val current = cookie ?: return emptyList()
        if (current.expiresAt <= System.currentTimeMillis()) { clear(); return emptyList() }
        return if (url.origin() == origin && current.matches(url)) listOf(current) else emptyList()
    }

    @Synchronized fun clear() { cookie = null; file.delete() }
}

internal fun HttpUrl.origin(): String {
    val defaultPort = if (isHttps) 443 else 80
    val hostPart = if (host.contains(':')) "[$host]" else host
    return "$scheme://$hostPart${if (port == defaultPort) "" else ":$port"}"
}
