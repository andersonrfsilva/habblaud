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

data class ConnectionSettings(val origin: String)
data class MessageDraft(val text: String = "", val revision: Long = 0, val attachments: List<Attachment> = emptyList())
data class MessageOperation(val id: String? = null, val status: String = "unknown", val revision: Long = 0, val error: String? = null)
data class MessageLocalState(val draft: MessageDraft = MessageDraft(), val operation: MessageOperation? = null, val control: MessageOperation? = null)
interface MessageStore {
    suspend fun message(key: SessionKey): MessageLocalState
    suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState
}

private fun messageKey(key: SessionKey) = stringPreferencesKey("message_" + java.security.MessageDigest.getInstance("SHA-256").digest(key.serialize().toByteArray()).joinToString("") { "%02x".format(it) })
private fun decodeMessage(value: String?): MessageLocalState {
    if (value == null) return MessageLocalState()
    val json = org.json.JSONObject(value)
    val attachments = json.optJSONArray("attachments")?.let { array -> (0 until array.length()).mapNotNull { i ->
        runCatching { array.getJSONObject(i).let { Attachment(it.getString("uri"), it.getString("name"), it.getString("mime"), if (it.isNull("size")) null else it.getLong("size"), it.optBoolean("persistent")) } }.getOrNull()
    } } ?: emptyList()
    val draft = MessageDraft(json.optString("text", ""), json.optLong("revision", 0), attachments)
    val op = json.optJSONObject("operation")?.let { MessageOperation(it.optString("id").ifEmpty { null }, it.optString("status", "unknown"), it.optLong("revision", 0)) }
    val control = json.optJSONObject("control")?.let { MessageOperation(it.optString("id").ifEmpty { null }, it.optString("status", "unknown"), it.optLong("revision", 0), it.optString("error").ifEmpty { null }) }
    return MessageLocalState(draft, op, control)
}
private fun encodeMessage(state: MessageLocalState): String {
    val json = org.json.JSONObject().put("text", state.draft.text).put("revision", state.draft.revision)
    json.put("attachments", org.json.JSONArray().also { array -> state.draft.attachments.forEach { attachment ->
        array.put(org.json.JSONObject().put("uri", attachment.uri).put("name", attachment.name).put("mime", attachment.mime).put("size", attachment.size ?: org.json.JSONObject.NULL).put("persistent", attachment.persistent))
    } })
    state.operation?.let { json.put("operation", org.json.JSONObject().put("id", it.id).put("status", it.status).put("revision", it.revision)) }
    state.control?.let { json.put("control", org.json.JSONObject().put("id", it.id).put("status", it.status).put("revision", it.revision).put("error", it.error)) }
    return json.toString()
}

class LocalStateStore(private val context: Context) : MessageStore {
    suspend fun usesAttachment(uri: String): Boolean = context.localState.data.first().asMap().any { (key, value) ->
        key.name.startsWith("message_") && value is String && runCatching { decodeMessage(value).draft.attachments.any { it.uri == uri } }.getOrDefault(false)
    }
    private val originKey = stringPreferencesKey("origin")
    // Chaves do antigo consentimento de HTTP pelo Tailscale: só para apagar o que ficou salvo.
    private val legacyKeys = listOf(booleanPreferencesKey("http_consent"), stringPreferencesKey("confirmed_http_host"))
    private val sessionKey = stringPreferencesKey("last_session")

    override suspend fun message(key: SessionKey): MessageLocalState = decodeMessage(context.localState.data.first()[messageKey(key)])
    override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState {
        var result = MessageLocalState()
        context.localState.edit { data ->
            check(data[originKey] == key.origin) { "Connection changed" }
            val k = messageKey(key)
            result = update(decodeMessage(data[k]))
            data[k] = encodeMessage(result)
        }
        return result
    }
    suspend fun lastSession(): SessionKey? = context.localState.data.first()[sessionKey]?.let { runCatching { SessionKey.deserialize(it) }.getOrNull() }
    suspend fun setLastSession(key: SessionKey?) {
        context.localState.edit { if (key == null) it.remove(sessionKey) else it[sessionKey] = key.serialize() }
    }
    private fun retryKey(origin: String) = longPreferencesKey("retry_" + java.security.MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) })

    suspend fun retryUntil(origin: String): Long = context.rateState.data.first()[retryKey(origin)] ?: 0

    suspend fun setRetryUntil(origin: String, until: Long) {
        context.rateState.edit { data -> data[retryKey(origin)] = until }
    }

    suspend fun connection(): ConnectionSettings? {
        val data = context.localState.data.first()
        return data[originKey]?.let { ConnectionSettings(it) }
    }

    suspend fun setConnection(origin: String) {
        context.localState.edit { data ->
            if (data[originKey] != origin) data.clear()
            data[originKey] = origin
            legacyKeys.forEach { data.remove(it) }
        }
    }

    suspend fun clear() { context.localState.edit { it.clear() } }
}
