package com.jizizr.signaldock

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Exchanges a long-lived Xiaomi passToken for a short-lived osbotapi serviceToken. */
object MiclawPassportClient {
    fun refresh(session: MiclawSession): MiclawSession {
        check(session.canRefresh) { "自动续期需要 passToken 和 userId" }
        val deviceId = "pc_" + digestHex("MD5", session.userId)
        val uDevId = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((session.userId + deviceId).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        val cookie = buildString {
            append("passToken=").append(session.passToken)
            append("; userId=").append(session.userId)
            if (session.cUserId.isNotBlank()) append("; cUserId=").append(session.cUserId)
            append("; deviceId=").append(deviceId)
            append("; uDevId=").append(uDevId)
            append("; uLocale=zh_CN; pass_ua=pc")
        }

        val phase1 = request(
            url = "https://account.xiaomi.com/pass/serviceLogin?_locale=zh_CN&_snsNone=true&sid=osbotapi&_json=true",
            cookie = cookie,
        )
        check(phase1.code in 200..299) { "Passport 换票阶段 1 HTTP ${phase1.code}" }
        val rawJson = phase1.body.removePrefix("&&&START&&&")
        val json = JSONObject(rawJson)
        check(json.optInt("code", -1) == 0) {
            "Passport 换票失败: ${json.optString("description", "code=${json.optInt("code", -1)}")}"
        }
        val location = json.getString("location")
        val ssecurity = json.optString("ssecurity")
        val nonce = NONCE_REGEX.find(rawJson)?.groupValues?.get(1)
            ?: error("Passport 响应缺少 nonce")
        val signatureInput = if (ssecurity.isBlank()) "nonce=$nonce" else "nonce=$nonce&$ssecurity"
        val signature = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest(signatureInput.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        val separator = if ('?' in location) '&' else '?'
        val phase2Url = "$location${separator}clientSign=${URLEncoder.encode(signature, Charsets.UTF_8)}"
        var phase2 = request(phase2Url)
        var token = phase2.serviceToken()
        if (token == null && phase2.code in 300..399 && phase2.location != null) {
            phase2 = request(URI(phase2Url).resolve(phase2.location).toString())
            token = phase2.serviceToken()
        }
        check(!token.isNullOrBlank() && token != "EXPIRED") {
            "Passport 换票阶段 2 未返回 serviceToken (HTTP ${phase2.code})"
        }
        return session.copy(serviceToken = token, savedAt = System.currentTimeMillis())
    }

    private fun request(url: String, cookie: String? = null): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", PC_USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            HttpResult(
                code = code,
                body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
                setCookies = connection.headerFields
                    .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                    .values
                    .flatten(),
                location = connection.getHeaderField("Location"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun digestHex(algorithm: String, text: String): String =
        MessageDigest.getInstance(algorithm)
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class HttpResult(
        val code: Int,
        val body: String,
        val setCookies: List<String>,
        val location: String?,
    ) {
        fun serviceToken(): String? = setCookies.firstNotNullOfOrNull { header ->
            header.split(';').firstOrNull()
                ?.takeIf { it.startsWith("serviceToken=") }
                ?.substringAfter('=')
        }
    }

    private val NONCE_REGEX = Regex("""\"nonce\"\s*:\s*(-?\d+)""")
    private const val PC_USER_AGENT = "miNative PC/Normal(Apple Mac16,10) Darwin/25.6.0 SDKV/0.0.1 MK/TWFjLW1pbmkubGFu L/zh-CN DEVT/UEM= DEVS/TWFj BRA/QXBwbGU= APP/Xiaomi miclaw APPV/0.0.1-beta.114+a3e3203f Chrome/144.0.7559.111"
}
