package com.jizizr.signaldock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses the phone's actual hardware credential; signatures are never persisted. */
internal object XiaomiDeviceSigner {
    private const val SERVICE_DESCRIPTOR = "com.xiaomi.security.devicecredential.ISecurityDeviceCredentialManager"
    private const val CONNECTION_DESCRIPTOR = "android.app.IServiceConnection"

    // App initializes HiddenApiBypass before this privileged, explicitly selected path.
    // The Android 16/17 callback layouts are checked below; unsupported layouts fail closed.
    @android.annotation.SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi")
    fun headers(context: Context, userId: String): Map<String, String> {
        check(AppShell.isShizukuRoot) { "专家模式的设备校验需要 root 模式的 Shizuku/Sui" }
        check(userId.isNotBlank()) { "缺少小米账号，请重新连接超级小爱" }
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null)
        val caller = activityThread.getDeclaredMethod("getApplicationThread").invoke(thread)
        val managerClass = Class.forName("android.app.IActivityManager")
        val manager = Class.forName("android.app.IActivityManager\$Stub")
            .getDeclaredMethod("asInterface", IBinder::class.java).invoke(null,
                ShizukuBinderWrapper(checkNotNull(SystemServiceHelper.getSystemService("activity"))))
        val connectionClass = Class.forName(CONNECTION_DESCRIPTOR)
        val connectionParameters = connectionClass.methods.single { it.name == "connected" }.parameterTypes
        val hasBinderSession = connectionParameters.size == 4 &&
            connectionParameters[2].name == "android.app.IBinderSession"
        check(connectionParameters.size == 3 || hasBinderSession) { "当前系统的设备签名回调协议不受支持" }
        val bind = managerClass.methods.single { it.name == "bindService" && it.parameterCount == 8 }
        val unbind = managerClass.getMethod("unbindService", connectionClass)

        for (packageName in listOf("com.xiaomi.account", "com.xiaomi.finddevice")) {
            val ready = CountDownLatch(1)
            val remote = AtomicReference<IBinder?>()
            val binderSession = AtomicReference<IBinder?>()
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString(CONNECTION_DESCRIPTOR)
                        return true
                    }
                    if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(CONNECTION_DESCRIPTOR)
                    val component = data.readTypedObject(ComponentName.CREATOR)
                    val binder = data.readStrongBinder()
                    // Android 17 inserts an IBinderSession before the existing dead flag.
                    if (hasBinderSession) binderSession.set(data.readStrongBinder())
                    val dead = data.readBoolean()
                    AppLog.i("XiaomiDeviceSigner", "Credential service connected: component=$component binder=${binder != null} dead=$dead")
                    remote.set(binder.takeUnless { dead })
                    ready.countDown()
                    return true
                }
            }
            val connection = Class.forName("android.app.IServiceConnection\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, callback)
            var bound = false
            try {
                val intent = Intent("$packageName.action.BIND_SECURITY_DEVICE_CREDENTIAL").setPackage(packageName)
                // Android requires the real application's thread even when Shizuku forwards the call.
                bound = (bind.invoke(manager, caller, null, intent, null, connection,
                    Context.BIND_AUTO_CREATE.toLong(), context.packageName, 0) as Int) > 0
                if (!bound) continue
                check(ready.await(8, TimeUnit.SECONDS)) { "连接小米设备签名服务超时" }
                val connected = remote.get() ?: continue
                val service = ShizukuBinderWrapper(connected)
                val fid = transact(service, 2, {}, Parcel::readString).orEmpty()
                check(fid.isNotBlank()) { "手机未返回硬件设备凭据" }
                val timestamp = System.currentTimeMillis().toString()
                val signature = transact(service, 3, {
                    writeInt(1) // Device credential, never the financial credential.
                    writeByteArray("$fid&$userId&$timestamp".toByteArray(Charsets.UTF_8))
                    writeInt(0)
                }, Parcel::createByteArray)
                check(signature != null && signature.isNotEmpty()) { "手机硬件签名失败" }
                return mapOf("X-Device-Fid" to fid,
                    "X-Device-Signature" to Base64.encodeToString(signature, Base64.NO_WRAP),
                    "X-Device-Ts" to timestamp, "X-Device-Type" to "phone", "X-Device-Sign-Type" to "sdc")
            } finally {
                if (bound) runCatching { unbind.invoke(manager, connection) }
            }
        }
        error("手机未提供小米硬件签名服务，请使用快速模式")
    }

    private fun <T> transact(binder: IBinder, code: Int, write: Parcel.() -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.write()
            check(binder.transact(code, data, reply, 0)) { "小米硬件签名协议不受支持" }
            reply.readException()
            read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
