package com.jizizr.signaldock

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.annotation.Keep
import java.io.File
import kotlin.system.exitProcess

/** Root Shizuku UserService that obtains the Xiaomi osbotapi token without starting Miclaw. */
@Keep
class MiclawCredentialService(private val context: Context) : IMiclawCredentialService.Stub() {
    override fun getSessionJson(forceRefresh: Boolean): String {
        check(Process.myUid() == 0) { "读取系统小米账号需要 root 模式的 Shizuku/Sui" }
        val account = readAccountMaterial()
            ?: error("系统小米账号中没有可用登录信息")
        return runCatching { MiclawPassportClient.refresh(account).toJson() }
            .onSuccess { AppLog.i(TAG, "osbotapi token refreshed through Xiaomi Passport") }
            .onFailure { AppLog.w(TAG, "Passport refresh failed: ${it.message}") }
            .getOrElse {
                if (forceRefresh || account.serviceToken.isBlank()) throw it
                account.toJson()
            }
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun readAccountMaterial(): MiclawSession? {
        val dbFile = File("/data/system_ce/0/accounts_ce.db")
        if (!dbFile.isFile) return null
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery(
                """
                SELECT a.name, a.password, t.authtoken,
                       COALESCE((SELECT value FROM extras WHERE accounts_id=a._id AND key='encrypted_user_id'), '')
                FROM accounts a
                JOIN authtokens t ON t.accounts_id=a._id
                WHERE a.type=? AND t.type=?
                LIMIT 1
                """.trimIndent(),
                arrayOf(XIAOMI_ACCOUNT_TYPE, SID),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val password = cursor.getString(1).orEmpty()
                val authToken = cursor.getString(2).orEmpty()
                MiclawSession(
                    userId = cursor.getString(0),
                    passToken = password.substringBefore(','),
                    cUserId = cursor.getString(3).orEmpty(),
                    serviceToken = authToken.substringBeforeLast(','),
                )
            }
        }
    }

    private companion object {
        const val TAG = "MiclawCredentialService"
        const val XIAOMI_ACCOUNT_TYPE = "com.xiaomi"
        const val SID = "osbotapi"
    }
}
