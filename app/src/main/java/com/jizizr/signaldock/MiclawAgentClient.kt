package com.jizizr.signaldock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.aios.apptoolsdk.aidl.Attachment
import com.aios.apptoolsdk.aidl.IExternalAgentCallback
import com.aios.apptoolsdk.aidl.IExternalAgentService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Calls Miclaw's exported Agent SDK from the registered application process. */
object MiclawAgentClient {
    private const val TAG = "MiclawAgentClient"
    private const val MICLAW_PACKAGE = "com.aios.osbot"
    private const val MICLAW_SERVICE = "com.aios.osbot.external.ExternalAgentService"
    private const val MICLAW_ACTION = "com.aios.osbot.action.EXTERNAL_AGENT"
    private const val MICLAW_PERMISSION = "com.aios.osbot.permission.EXTERNAL_AGENT"
    private const val MICLAW_AGENT_ID = "osbot.main"
    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val ANALYSIS_TIMEOUT_SECONDS = 90L
    private const val MAX_RESULT_BYTES = 4 * 1024 * 1024

    fun isInstalled(context: Context): Boolean = miclawApplicationInfo(context) != null

    /** Mirrors Miclaw's internal CallerVerifier before attempting a Binder connection. */
    fun isCompatibilitySupported(context: Context): Boolean {
        val miclawInfo = miclawApplicationInfo(context) ?: return false
        val callerInfo = context.applicationInfo
        val platformSigned = context.packageManager.checkSignatures(
            context.packageName,
            "android",
        ) == PackageManager.SIGNATURE_MATCH
        return miclawVerifierAcceptsCaller(
            miclawDebuggable = miclawInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            callerSystemApp = callerInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            platformSigned = platformSigned,
        )
    }

    fun availability(context: Context): String? = when {
        !isInstalled(context) -> "未检测到 Miclaw"
        context.checkSelfPermission(MICLAW_PERMISSION) != PackageManager.PERMISSION_GRANTED ->
            "Miclaw 外部 Agent 权限未生效"
        !isCompatibilitySupported(context) ->
            "官方 Miclaw 拒绝普通应用连接，请使用直连模式"
        else -> null
    }

    private fun miclawApplicationInfo(context: Context): ApplicationInfo? = runCatching {
        context.packageManager.getApplicationInfo(
            MICLAW_PACKAGE,
            PackageManager.ApplicationInfoFlags.of(0),
        )
    }.getOrNull()

    /** Blocks on the background capture thread until Miclaw returns its final text. */
    @Synchronized
    fun analyzeScreenshot(context: Context, bitmap: Bitmap, prompt: String): Result<String> {
        availability(context)?.let { return Result.failure(IllegalStateException(it)) }
        val boundService = connect(context).getOrElse { return Result.failure(it) }
        return try {
            analyzeWithService(context, bitmap, prompt, boundService.remote)
        } finally {
            boundService.close()
        }
    }

    private fun analyzeWithService(
        context: Context,
        bitmap: Bitmap,
        prompt: String,
        remote: IExternalAgentService,
    ): Result<String> {
        val sessionId = try {
            remote.openSession(buildAppMeta(), false)
        } catch (error: Throwable) {
            return Result.failure(IllegalStateException("Miclaw 会话创建失败", error))
        }
        if (sessionId.isNullOrBlank() || sessionId.startsWith("error:")) {
            return Result.failure(IllegalStateException("Miclaw 会话创建失败: $sessionId"))
        }
        Log.i(TAG, "Miclaw session opened: agent=$MICLAW_AGENT_ID")

        val imageFile = File.createTempFile("miclaw-screenshot-", ".jpg", context.cacheDir)
        val imageFd = try {
            bitmap.withUploadSize(MAX_UPLOAD_SIDE) { uploadBitmap ->
                FileOutputStream(imageFile).use { output ->
                    check(uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)) {
                        "截图 JPEG 编码失败"
                    }
                }
            }
            ParcelFileDescriptor.open(imageFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Throwable) {
            imageFile.delete()
            runCatching { remote.closeSession(sessionId) }
            return Result.failure(error)
        }

        val completed = CountDownLatch(1)
        val response = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()
        val callback = object : IExternalAgentCallback.Stub() {
            override fun onTextDelta(sessionId: String?, delta: String?) = Unit

            override fun onComplete(
                sessionId: String?,
                resultJson: String?,
                attachments: MutableList<Attachment>?,
            ) {
                try {
                    response.set(extractResultText(resultJson, attachments))
                    Log.i(TAG, "Miclaw request completed")
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    attachments.orEmpty().forEach { runCatching { it.fd?.close() } }
                    completed.countDown()
                }
            }

            override fun onError(sessionId: String?, errorJson: String?) {
                val message = parseError(errorJson)
                Log.e(TAG, "Miclaw callback error: $message")
                failure.set(IllegalStateException(message))
                completed.countDown()
            }

            override fun onReasoningDelta(sessionId: String?, delta: String?) = Unit
            override fun onToolEvent(sessionId: String?, eventJson: String?) = Unit
            override fun onTtsEvent(sessionId: String?, eventJson: String?) = Unit
        }

        return try {
            val request = JSONObject()
                .put("type", "message")
                .put("text", prompt)
                .toString()
            remote.submit(
                sessionId,
                request,
                listOf(Attachment.fromFd("screenshot.jpg", "image/jpeg", imageFd)),
                callback,
            )
            Log.i(TAG, "Miclaw screenshot submitted: agent=$MICLAW_AGENT_ID")
            imageFd.close()
            imageFile.delete()

            if (!completed.await(ANALYSIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Result.failure(IllegalStateException("Miclaw 响应超时"))
            } else {
                failure.get()?.let(Result.Companion::failure)
                    ?: response.get()
                        ?.takeIf(String::isNotBlank)
                        ?.let(Result.Companion::success)
                    ?: Result.failure(IllegalStateException("Miclaw 返回空结果"))
            }
        } catch (error: Throwable) {
            Result.failure(IllegalStateException("Miclaw 调用失败", error))
        } finally {
            runCatching { imageFd.close() }
            imageFile.delete()
            runCatching { remote.closeSession(sessionId) }
        }
    }

    private fun connect(context: Context): Result<BoundService> {
        val appContext = context.applicationContext
        val remote = AtomicReference<IExternalAgentService?>()
        val connectionError = AtomicReference<String?>()
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote.set(IExternalAgentService.Stub.asInterface(binder))
                Log.i(TAG, "Miclaw external Agent connected: component=$name")
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Miclaw external Agent disconnected: component=$name")
                ready.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                connectionError.set("Miclaw 返回空 Binder")
                Log.e(TAG, "Miclaw returned a null binder: component=$name")
                ready.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                connectionError.set("Miclaw Binder 已失效")
                Log.e(TAG, "Miclaw binding died: component=$name")
                ready.countDown()
            }
        }

        val bound = try {
            val intent = Intent(MICLAW_ACTION)
                .setComponent(ComponentName(MICLAW_PACKAGE, MICLAW_SERVICE))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (error: Throwable) {
            return Result.failure(
                IllegalStateException("Miclaw Binder 连接失败: ${error.message}", error),
            )
        }
        if (!bound) {
            return Result.failure(IllegalStateException("Miclaw Binder 连接失败: bindService=false"))
        }
        if (!ready.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runCatching { appContext.unbindService(connection) }
            return Result.failure(IllegalStateException("Miclaw Binder 连接超时"))
        }
        val connected = remote.get()
        if (connected == null) {
            runCatching { appContext.unbindService(connection) }
            return Result.failure(
                IllegalStateException(connectionError.get() ?: "Miclaw Binder 未返回服务"),
            )
        }
        return Result.success(BoundService(appContext, connection, connected))
    }

    private class BoundService(
        private val context: Context,
        private val connection: ServiceConnection,
        val remote: IExternalAgentService,
    ) : AutoCloseable {
        override fun close() {
            runCatching { context.unbindService(connection) }
                .onFailure { Log.w(TAG, "Miclaw unbind failed", it) }
        }
    }

    private fun buildAppMeta(): String = JSONObject()
        .put("appName", "信岛")
        .put("locale", "zh-CN")
        .put("context", "从当前截图提取灵动岛通知所需的结构化信息")
        .put("targetPackage", MICLAW_AGENT_ID)
        .put("chatId", "signaldock-screenshot")
        .toString()

    private fun extractResultText(
        resultJson: String?,
        attachments: List<Attachment>?,
    ): String {
        resultJson?.takeIf(String::isNotBlank)?.let { raw ->
            val text = runCatching { JSONObject(raw).optString("text") }.getOrDefault("")
            if (text.isNotBlank()) return text
        }
        attachments.orEmpty().forEach { attachment ->
            val fd = attachment.fd ?: return@forEach
            val raw = FileInputStream(fd.fileDescriptor).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= MAX_RESULT_BYTES) { "Miclaw 结果超过 4 MiB" }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
            val text = runCatching { JSONObject(raw).optString("text") }.getOrDefault("")
            return text.ifBlank { raw }
        }
        return resultJson.orEmpty()
    }

    private fun parseError(errorJson: String?): String {
        if (errorJson.isNullOrBlank()) return "Miclaw 返回未知错误"
        return runCatching {
            val obj = JSONObject(errorJson)
            val code = obj.optString("code", "UNKNOWN")
            val message = obj.optString("message", errorJson)
            "$code: $message"
        }.getOrDefault(errorJson)
    }

    private const val MAX_UPLOAD_SIDE = 1600
}

internal fun miclawVerifierAcceptsCaller(
    miclawDebuggable: Boolean,
    callerSystemApp: Boolean,
    platformSigned: Boolean,
): Boolean = miclawDebuggable || callerSystemApp || platformSigned
