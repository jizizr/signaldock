package com.jizizr.signaldock

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.util.Xml
import androidx.annotation.Keep
import org.xmlpull.v1.XmlPullParser
import java.io.File
import kotlin.system.exitProcess

/** Read-only Shizuku service. Never imports account passwords or modifies XiaoAi's files. */
@Keep
class XiaomiCredentialService(@Suppress("UNUSED_PARAMETER") context: Context) : IXiaomiCredentialService.Stub() {
    override fun readSession(): String {
        check(Process.myUid() == 0) { "连接系统小爱需要 root 模式的 Shizuku/Sui" }
        val root = File("/data/user/0/com.miui.voiceassist/shared_prefs")
        val auth = readStrings(File(root, "aivs_user_data.xml.xml"))
        val device = readStrings(File(root, "aivs_did.xml.xml"))
        val account = readExpertToken()
        return XiaomiSession(
            accessToken = auth["production_DO-TOKEN-V1_access_token"].orEmpty(),
            deviceId = device["device_id"].orEmpty(),
            expiresAtSeconds = auth["production_DO-TOKEN-V1_expire_at"]?.toLongOrNull() ?: 0,
            expertToken = account?.get(0).orEmpty(),
            userId = account?.get(1).orEmpty(),
            cUserId = account?.get(2).orEmpty(),
        ).also { check(it.isUsable) { "请先在超级小爱中登录并完成一次对话，再重新连接" } }.toJson()
    }

    private fun readExpertToken(): List<String>? = runCatching {
        SQLiteDatabase.openDatabase("/data/system_ce/0/accounts_ce.db", null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""
                SELECT t.authtoken, a.name,
                    COALESCE((SELECT value FROM extras WHERE accounts_id=a._id AND key='encrypted_user_id'), '')
                FROM accounts a JOIN authtokens t ON t.accounts_id=a._id
                WHERE a.type=? AND t.type=? LIMIT 1
            """.trimIndent(), arrayOf("com.xiaomi", "miclaw")).use { cursor ->
                if (!cursor.moveToFirst()) null else listOf(
                    cursor.getString(0).orEmpty().substringBeforeLast(','),
                    cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                )
            }
        }
    }.getOrNull()

    private fun readStrings(file: File): Map<String, String> {
        check(file.isFile) { "未找到超级小爱登录信息，请先打开小爱" }
        return file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            buildMap {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string") {
                        val name = parser.getAttributeValue(null, "name")
                        val value = parser.nextText()
                        if (name != null) put(name, value)
                    }
                    parser.next()
                }
            }
        }
    }

    override fun destroy() = exitProcess(0)
}
