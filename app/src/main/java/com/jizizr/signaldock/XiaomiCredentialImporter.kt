package com.jizizr.signaldock

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object XiaomiCredentialImporter {
    @Synchronized
    fun importFromXiaoAi(context: Context): Result<XiaomiSession> = runCatching {
        check(AppShell.isShizukuRoot) { "连接系统小爱需要已授权且以 root 模式运行的 Shizuku/Sui" }
        val args = Shizuku.UserServiceArgs(ComponentName(context, XiaomiCredentialService::class.java))
            .daemon(false).processNameSuffix("xiaomi_credential").debuggable(false).version(1)
        val ready = CountDownLatch(1)
        val remote = AtomicReference<IXiaomiCredentialService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote.set(IXiaomiCredentialService.Stub.asInterface(binder)); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { ready.countDown() }
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
            override fun onBindingDied(name: ComponentName) { ready.countDown() }
        }
        try {
            Shizuku.bindUserService(args, connection)
            check(ready.await(15, TimeUnit.SECONDS)) { "连接小爱登录服务超时" }
            val service = checkNotNull(remote.get()) { "小爱登录服务已断开" }
            XiaomiSessionStore.saveImported(XiaomiSession.fromJson(service.readSession()))
        } finally {
            runCatching { remote.get()?.destroy() }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
}
