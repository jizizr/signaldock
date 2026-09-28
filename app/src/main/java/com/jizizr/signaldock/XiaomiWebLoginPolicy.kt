package com.jizizr.signaldock

import java.net.URI

/** Only these first-party hosts may receive this login WebView's top-level navigation. */
internal fun isXiaomiLoginUrlAllowed(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
        uri.port in listOf(-1, 443) && uri.host?.lowercase() in setOf(
            "account.xiaomi.com", "account.ai.xiaomi.com",
        )
}.getOrDefault(false)

internal class XiaomiWebServiceCredential(val token: String, val userId: String)

/** Read only the target service ticket; never collect the account's password/passToken. */
internal fun xiaomiWebServiceCredential(serviceCookies: String?, accountCookies: String?): XiaomiWebServiceCredential? {
    fun cookies(raw: String?): Map<String, String> = raw.orEmpty().split(';').mapNotNull {
        val parts = it.trim().split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
    val service = cookies(serviceCookies)
    val account = cookies(accountCookies)
    val token = service["ai-service_serviceToken"] ?: service["serviceToken"] ?: return null
    val userId = service["userId"] ?: account["userId"] ?: return null
    if (token.isBlank() || token == "EXPIRED" || token.any { it.isWhitespace() || it == ';' } ||
        userId.isEmpty() || !userId.all { it in '0'..'9' }) return null
    return XiaomiWebServiceCredential(token, userId)
}
