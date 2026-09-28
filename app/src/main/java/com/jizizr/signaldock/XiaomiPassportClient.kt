package com.jizizr.signaldock

import android.content.Context
import android.util.Base64
import dalvik.system.PathClassLoader
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** User-initiated Passport service login. No Shizuku, AccountManager, or private app files. */
internal object XiaomiPassportClient {
    const val LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin?sid=ai-service&_locale=zh_CN&_snsNone=true"
    private const val CLIENT_ID = "326813440150602752"
    private const val BASE = "https://account.xiaomi.com/oauth2/user-credentials/"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    /** Versioned adapter for the authentication configuration in the installed, public APK. */
    private fun clientSecret(context: Context): String {
        val info = context.packageManager.getPackageInfo("com.miui.voiceassist", 0)
        check(info.longVersionCode == 508002062L) { "此超级小爱版本尚未适配网页登录" }
        val app = checkNotNull(info.applicationInfo)
        check(app.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            "网页登录适配仅支持预装的系统小爱"
        }
        val paths = (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).joinToString(java.io.File.pathSeparator)
        val loader = PathClassLoader(paths, context.classLoader)
        val config = loader.loadClass("s31.d")
        val decode = loader.loadClass("s31.b").getMethod("c", String::class.java)
        fun value(field: String): String = decode.invoke(null, config.getField(field).get(null)) as String
        // Use DEX field names; the f227492h/f227497m names shown by JADX are aliases.
        check(value("h") == CLIENT_ID) { "小爱账号配置不匹配" }
        return value("m").also { check(it.isNotBlank()) { "小爱账号配置不可用" } }
    }

    fun checkSupported(context: Context) { clientSecret(context) }

    fun exchange(context: Context, credential: XiaomiWebServiceCredential): XiaomiSession {
        val secret = clientSecret(context)
        val deviceId = UUID.randomUUID().toString().replace("-", "")
        val state = MessageDigest.getInstance("SHA-256").digest("d=$deviceId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cookie = "ai-service_serviceToken=${credential.token};userId=${credential.userId}"
        val scopeUrl = (BASE + "scopes").toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID).addQueryParameter("sid", "ai-service")
            .addQueryParameter("pt", "1").addQueryParameter("state", state).build()
        val scope = request(Request.Builder().url(scopeUrl).header("Cookie", cookie).build(), "账号授权")
        check(scope.optString("status") == "ok" && scope.optString("code").isNotBlank()) {
            "小米账号未授予小爱服务权限（${scope.optInt("error", -1)}）"
        }
        val scopeData = Base64.encodeToString(JSONObject().put("d", deviceId).toString().toByteArray(), Base64.NO_WRAP)
        val body = FormBody.Builder().add("grant_type", "password").add("client_id", CLIENT_ID)
            .add("client_secret", secret).add("sid", "ai-service").add("code", scope.getString("code"))
            .add("user_id", credential.userId).add("pt", "1").add("scope_data", scopeData).build()
        val token = request(Request.Builder().url(BASE + "issued-token").header("Cookie", cookie)
            .post(body).build(), "识别凭据换取")
        val accessToken = token.optString("access_token")
        val expiresIn = token.optLong("expires_in")
        check(accessToken.isNotBlank() && expiresIn in 61..315_360_000L) {
            "小米账号未返回有效识别凭据（${token.optInt("code", -1)}）"
        }
        return XiaomiSession(accessToken, deviceId, System.currentTimeMillis() / 1000 + expiresIn,
            userId = credential.userId, independentDevice = true, refreshToken = token.optString("refresh_token"))
    }

    private fun request(request: Request, stage: String): JSONObject = http.newCall(request).execute().use { response ->
        AppLog.i("XiaomiPassport", "$stage HTTP ${response.code}")
        check(response.isSuccessful) { "$stage 失败（HTTP ${response.code}）" }
        val body = checkNotNull(response.body) { "$stage 未返回数据" }
        check(body.contentLength() <= 131072) { "$stage 响应过大" }
        val bytes = body.byteStream().readNBytes(131073)
        check(bytes.size <= 131072) { "$stage 响应过大" }
        runCatching { JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("&&&START&&&")) }
            .getOrElse { error("$stage 返回的数据无法解析") }
    }
}
