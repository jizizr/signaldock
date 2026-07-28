package com.jizizr.signaldock

import android.content.Context
import org.json.JSONObject

data class MiclawSession(
    val serviceToken: String = "",
    val passToken: String = "",
    val cUserId: String = "",
    val userId: String = "",
    val savedAt: Long = System.currentTimeMillis(),
) {
    val canRefresh: Boolean get() = passToken.isNotBlank() && userId.isNotBlank()
    val isUsable: Boolean get() = serviceToken.isNotBlank() || canRefresh

    fun toJson(): String = JSONObject()
        .put("serviceToken", serviceToken)
        .put("passToken", passToken)
        .put("cUserId", cUserId)
        .put("userId", userId)
        .put("savedAt", savedAt)
        .toString()
}

/** Stores Miclaw cookies encrypted by a non-exportable Android Keystore key. */
object MiclawSessionStore {
    private const val PREFS = "miclaw_session"
    private const val KEY_BLOB = "encrypted_session"
    private const val KEY_ALIAS = "signaldock_miclaw_session_v1"

    private lateinit var store: EncryptedValueStore

    fun init(context: Context) {
        store = EncryptedValueStore(context, PREFS, KEY_ALIAS)
    }

    fun load(): MiclawSession? {
        val rawJson = store.get(KEY_BLOB) ?: return null
        return runCatching {
            val json = JSONObject(rawJson)
            MiclawSession(
                serviceToken = json.optString("serviceToken"),
                passToken = json.optString("passToken"),
                cUserId = json.optString("cUserId"),
                userId = json.optString("userId"),
                savedAt = json.optLong("savedAt", 0L),
            ).takeIf(MiclawSession::isUsable)
        }.getOrNull()
    }

    fun save(session: MiclawSession) {
        require(session.isUsable) { "请填写 serviceToken，或同时填写 passToken 和 userId" }
        val plaintext = JSONObject()
            .put("serviceToken", session.serviceToken)
            .put("passToken", session.passToken)
            .put("cUserId", session.cUserId)
            .put("userId", session.userId)
            .put("savedAt", session.savedAt)
            .toString()
        store.put(KEY_BLOB, plaintext)
    }

    fun clear() {
        store.remove(KEY_BLOB)
    }
}
