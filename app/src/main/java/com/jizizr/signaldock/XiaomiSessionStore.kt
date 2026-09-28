package com.jizizr.signaldock

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Xiaomi tokens obtained through official web login or explicit system-account import. */
class XiaomiSession(
    val accessToken: String,
    val deviceId: String,
    val expiresAtSeconds: Long,
    val expertToken: String = "",
    val userId: String = "",
    val cUserId: String = "",
    val independentDevice: Boolean = false,
    val refreshToken: String = "",
) {
    val isUsable: Boolean
        get() = accessToken.isNotBlank() && deviceId.isNotBlank() &&
            expiresAtSeconds > System.currentTimeMillis() / 1000 + 60

    fun authorization(): String {
        check(isUsable) { "小爱登录已过期，请在 AI 设置中重新连接" }
        val scope = Base64.encodeToString(
            JSONObject().put("d", deviceId).toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "DO-TOKEN-V1 app_id:326813440150602752,scope_data:$scope,access_token:$accessToken"
    }

    fun toJson(): String = JSONObject()
        .put("accessToken", accessToken).put("deviceId", deviceId)
        .put("expiresAtSeconds", expiresAtSeconds).put("expertToken", expertToken)
        .put("userId", userId).put("cUserId", cUserId)
        .put("independentDevice", independentDevice).put("refreshToken", refreshToken).toString()

    companion object {
        fun fromJson(raw: String): XiaomiSession = JSONObject(raw).let {
            XiaomiSession(it.getString("accessToken"), it.getString("deviceId"),
                it.getLong("expiresAtSeconds"), it.optString("expertToken"),
                it.optString("userId"), it.optString("cUserId"), it.optBoolean("independentDevice"), it.optString("refreshToken"))
        }
    }
}

object XiaomiSessionStore {
    private val sessionChanges = MutableStateFlow(0L)
    internal val changes = sessionChanges.asStateFlow()
    private lateinit var store: EncryptedValueStore
    fun init(context: Context) {
        store = EncryptedValueStore(context, "xiaomi_session", "signaldock_xiaomi_session_v1")
    }
    fun load(): XiaomiSession? = runCatching {
        store.get("session")?.let(XiaomiSession::fromJson)
    }.getOrNull()
    fun save(session: XiaomiSession) {
        check(session.isUsable) { "超级小爱登录信息不可用，请先在小爱中完成登录" }
        store.put("session", session.toJson())
        sessionChanges.update { it + 1 }
    }
    fun clear() {
        store.remove("session")
        sessionChanges.update { it + 1 }
    }
}
