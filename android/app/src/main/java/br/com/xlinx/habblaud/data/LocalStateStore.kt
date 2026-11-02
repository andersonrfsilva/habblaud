package br.com.xlinx.habblaud.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.localState by preferencesDataStore(name = "local_state")
private val Context.rateState by preferencesDataStore(name = "login_rate")

data class ConnectionSettings(val origin: String, val consent: Boolean)

class LocalStateStore(private val context: Context) {
    private val originKey = stringPreferencesKey("origin")
    private val consentKey = booleanPreferencesKey("http_consent")
    private fun retryKey(origin: String) = longPreferencesKey("retry_" + java.security.MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) })

    suspend fun retryUntil(origin: String): Long = context.rateState.data.first()[retryKey(origin)] ?: 0

    suspend fun setRetryUntil(origin: String, until: Long) {
        context.rateState.edit { data -> data[retryKey(origin)] = until }
    }

    suspend fun connection(): ConnectionSettings? {
        val data = context.localState.data.first()
        return data[originKey]?.let { ConnectionSettings(it, data[consentKey] == true) }
    }

    suspend fun setConnection(origin: String, consent: Boolean) {
        context.localState.edit { data ->
            if (data[originKey] != origin) data.clear()
            data[originKey] = origin
            data[consentKey] = consent
        }
    }

    suspend fun clear() { context.localState.edit { it.clear() } }
}
