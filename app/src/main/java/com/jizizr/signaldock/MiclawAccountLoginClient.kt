package com.jizizr.signaldock

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONObject

/** Stateful Xiaomi Passport login flow for non-root users. */
class MiclawAccountLoginClient {
    sealed interface Outcome {
        data class Authenticated(val session: MiclawSession) : Outcome
        data class CaptchaRequired(val imageBytes: ByteArray) : Outcome
        data class TwoFactorRequired(val options: List<Int>) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private var cookieManager = newCookieManager()
    private var pendingAccount = ""
    private var pendingPasswordHash = ""

    fun login(account: String, password: String, captcha: String = ""): Outcome {
        require(account.isNotBlank()) { "请输入小米账号" }
        require(password.isNotBlank()) { "请输入密码" }
        if (captcha.isBlank()) resetTransport()

        pendingAccount = account.trim()
        pendingPasswordHash = md5Upper(password)
        val body = postForm(
            SERVICE_LOGIN_AUTH2_URL,
            linkedMapOf(
                "user" to pendingAccount,
                "hash" to pendingPasswordHash,
                "sid" to LOGIN_SID,
                "_json" to "true",
                "_locale" to "zh_CN",
            ).apply {
                if (captcha.isNotBlank()) put("captCode", captcha.trim())
            },
        ).json()

        body.optString("captchaUrl").takeIf { it.isNotBlank() && it != "null" }?.let { path ->
            AppLog.i(TAG, "Xiaomi login requires captcha")
            val imageUrl = resolve(ACCOUNT_HOST, path)
            return Outcome.CaptchaRequired(request(imageUrl).body)
        }

        body.optString("notificationUrl").takeIf { it.isNotBlank() && it != "null" }?.let { path ->
            val listUrl = resolve(ACCOUNT_HOST, path)
                .replace("fe/service/identity/authStart", "identity/list")
                .replace("fe/service/identityauthStart", "identity/list")
            val list = request(listUrl).json()
            check(!list.optBoolean("twoFactorAuth")) { "当前账号要求硬件二次验证，暂不支持" }
            val options = list.optJSONArray("options")?.let { array ->
                buildList {
                    repeat(array.length()) { index -> add(array.optInt(index)) }
                }.filter { it > 0 }
            }.orEmpty()
            check(options.isNotEmpty()) { "小米账号未返回可用的二次验证方式" }
            AppLog.i(TAG, "Xiaomi login requires 2FA options=$options")
            return Outcome.TwoFactorRequired(options)
        }

        val code = body.optInt("code", -1)
        if (code != 0) {
            AppLog.w(TAG, "Xiaomi login rejected code=$code")
            return Outcome.Failed(errorMessage(body, "登录失败", code))
        }
        AppLog.i(TAG, "Xiaomi password login accepted; exchanging osbotapi token")
        return Outcome.Authenticated(refreshSession(body))
    }

    fun sendTicket(flag: Int) {
        require(flag == PHONE_FLAG || flag == EMAIL_FLAG) { "不支持的验证方式" }
        val response = postForm(
            "$ACCOUNT_HOST${pathsFor(flag).first}?_dc=${System.currentTimeMillis()}",
            linkedMapOf("_json" to "true", "retry" to "0", "icode" to ""),
        ).json()
        val code = response.optInt("code", -1)
        check(code == 0) { errorMessage(response, "发送验证码失败", code) }
        AppLog.i(TAG, "Xiaomi 2FA ticket sent flag=$flag")
    }

    fun verifyTicket(flag: Int, ticket: String): MiclawSession {
        require(ticket.isNotBlank()) { "请输入验证码" }
        check(pendingAccount.isNotBlank() && pendingPasswordHash.isNotBlank()) {
            "登录状态已失效，请重新登录"
        }
        val response = postForm(
            "$ACCOUNT_HOST${pathsFor(flag).second}?_dc=${System.currentTimeMillis()}",
            linkedMapOf(
                "_flag" to flag.toString(),
                "ticket" to ticket.trim(),
                "trust" to "true",
                "_json" to "true",
            ),
        ).json()
        val code = response.optInt("code", -1)
        check(code != 70014 && code != 7014) { "验证码错误" }
        check(code == 0) { errorMessage(response, "验证失败", code) }

        response.optString("location").takeIf(String::isNotBlank)?.let(::followRedirects)
        val authenticated = postForm(
            SERVICE_LOGIN_AUTH2_URL,
            linkedMapOf(
                "user" to pendingAccount,
                "hash" to pendingPasswordHash,
                "sid" to LOGIN_SID,
                "_json" to "true",
                "_locale" to "zh_CN",
            ),
        ).json()
        val authenticatedCode = authenticated.optInt("code", -1)
        check(authenticatedCode == 0) {
            errorMessage(authenticated, "二次验证后的登录失败", authenticatedCode)
        }
        AppLog.i(TAG, "Xiaomi 2FA accepted; exchanging osbotapi token")
        return refreshSession(authenticated)
    }

    fun reset() {
        resetTransport()
        pendingAccount = ""
        pendingPasswordHash = ""
    }

    private fun refreshSession(body: JSONObject): MiclawSession {
        val session = MiclawSession(
            passToken = body.optString("passToken"),
            userId = body.valueAsString("userId"),
            cUserId = body.optString("cUserId"),
        )
        check(session.canRefresh) { "登录成功，但响应缺少 passToken 或 userId" }
        return MiclawPassportClient.refresh(session)
    }

    private fun followRedirects(startUrl: String) {
        var current = startUrl
        repeat(MAX_REDIRECTS) {
            val result = request(current)
            if (result.code !in 300..399 || result.location.isNullOrBlank()) return
            current = resolve(current, result.location)
        }
    }

    private fun postForm(url: String, form: Map<String, String>): HttpResult = request(
        url = url,
        method = "POST",
        contentType = "application/x-www-form-urlencoded; charset=UTF-8",
        body = form.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(Charsets.UTF_8),
    )

    private fun request(
        url: String,
        method: String = "GET",
        contentType: String? = null,
        body: ByteArray? = null,
    ): HttpResult {
        val uri = URI(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")
            cookieManager.get(uri, emptyMap())["Cookie"]
                ?.takeIf(List<String>::isNotEmpty)
                ?.let { connection.setRequestProperty("Cookie", it.joinToString("; ")) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val headers = connection.headerFields.filterKeys { it != null }
            cookieManager.put(uri, headers)
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            HttpResult(
                code = code,
                body = stream?.use { it.readBytes() } ?: byteArrayOf(),
                location = connection.getHeaderField("Location"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun resetTransport() {
        cookieManager = newCookieManager()
    }

    private data class HttpResult(
        val code: Int,
        val body: ByteArray,
        val location: String?,
    ) {
        fun json(): JSONObject {
            check(code in 200..299) { "小米账号服务 HTTP $code" }
            return JSONObject(body.toString(Charsets.UTF_8).removePrefix(JSON_PREFIX))
        }
    }

    private companion object {
        const val ACCOUNT_HOST = "https://account.xiaomi.com"
        const val SERVICE_LOGIN_AUTH2_URL = "$ACCOUNT_HOST/pass/serviceLoginAuth2"
        const val LOGIN_SID = "miclaw"
        const val JSON_PREFIX = "&&&START&&&"
        const val PHONE_FLAG = 4
        const val EMAIL_FLAG = 8
        const val TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 6
        const val USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 16; 2509FPN0BC Build/BP2A.250605.031.A3)"
        const val TAG = "MiclawAccountLogin"

        fun newCookieManager() = CookieManager(null, CookiePolicy.ACCEPT_ALL)

        fun pathsFor(flag: Int): Pair<String, String> = if (flag == PHONE_FLAG) {
            "/identity/auth/sendPhoneTicket" to "/identity/auth/verifyPhone"
        } else {
            "/identity/auth/sendEmailTicket" to "/identity/auth/verifyEmail"
        }

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        fun resolve(base: String, target: String): String = URI(base).resolve(target).toString()

        fun md5Upper(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

        fun errorMessage(body: JSONObject, fallback: String, code: Int): String {
            val messages = listOf("tips", "desc", "description")
                .map(body::optString)
                .filter(String::isNotBlank)
                .distinct()
            return buildString {
                append(messages.joinToString(": ").ifBlank { fallback })
                if (code >= 0) append(" (code=").append(code).append(')')
            }
        }

        fun JSONObject.valueAsString(key: String): String = when (val value = opt(key)) {
            is String -> value
            is Number -> value.toString()
            null -> ""
            else -> ""
        }
    }
}
