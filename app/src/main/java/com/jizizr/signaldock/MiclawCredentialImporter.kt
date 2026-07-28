package com.jizizr.signaldock

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object MiclawCredentialImporter {
    fun importFromSystemAccount(context: Context, forceRefresh: Boolean = false): Result<MiclawSession> {
        if (!AppShell.isShizukuAvailable) {
            return Result.failure(IllegalStateException("需要已授权且以 root 模式运行的 Shizuku/Sui"))
        }
        if (runCatching { Shizuku.getUid() }.getOrDefault(-1) != 0) {
            return Result.failure(IllegalStateException("当前 Shizuku 不是 root 模式，无法读取系统账号令牌"))
        }

        val appContext = context.applicationContext
        val args = Shizuku.UserServiceArgs(ComponentName(appContext, MiclawCredentialService::class.java))
            .daemon(false)
            .processNameSuffix("miclaw_credential")
            .debuggable(false)
            .version(1)
        val latch = CountDownLatch(1)
        val serviceRef = AtomicReference<IMiclawCredentialService?>()
        val errorRef = AtomicReference<Throwable?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                serviceRef.set(IMiclawCredentialService.Stub.asInterface(binder))
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (serviceRef.get() == null) {
                    errorRef.set(IllegalStateException("Miclaw 凭据服务意外断开"))
                    latch.countDown()
                }
            }
        }

        return runCatching {
            Shizuku.bindUserService(args, connection)
            check(latch.await(20, TimeUnit.SECONDS)) { "连接 Miclaw 凭据服务超时" }
            errorRef.get()?.let { throw it }
            val service = checkNotNull(serviceRef.get()) { "Miclaw 凭据服务未返回 Binder" }
            val json = JSONObject(service.getSessionJson(forceRefresh))
            val session = MiclawSession(
                serviceToken = json.getString("serviceToken"),
                passToken = json.optString("passToken"),
                cUserId = json.optString("cUserId"),
                userId = json.optString("userId"),
            )
            MiclawSessionStore.save(session)
            session
        }.also {
            runCatching { serviceRef.get()?.destroy() }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
}
