package com.jizizr.signaldock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** ADB-triggered, DUMP-protected end-to-end Miclaw regression test. */
@Keep
class MiclawSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        thread(name = "miclaw-self-test") {
            val resultFile = File(context.cacheDir, RESULT_FILE_NAME)
            try {
                resultFile.delete()
                val imageFile = resolveImageFile(context, intent)
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    ?: error("无法解码测试截图: ${imageFile.absolutePath}")

                if (!AiSettingsStore.miclawUseExternalAgent) {
                    var session = MiclawSessionStore.load()
                        ?: MiclawCredentialImporter.importFromSystemAccount(context).getOrThrow()
                    if (intent.getBooleanExtra(EXTRA_FORCE_PASS_TOKEN_REFRESH, false)) {
                        check(session.canRefresh) { "测试登录信息缺少 passToken 或 userId" }
                        session = session.copy(serviceToken = "")
                        MiclawSessionStore.save(session)
                    }
                }
                val parsed = RustBridge.analyzeScreenshot(context.applicationContext, bitmap)
                val searchable = buildString {
                    append(parsed.title)
                    append('\n')
                    append(parsed.body)
                    append('\n')
                    append(parsed.infoLines.joinToString("\n"))
                    append('\n')
                    append(parsed.content)
                }
                val pickupCodeFound = searchable.contains("5312")
                val orderContentFound = searchable.contains("重庆邮电大学") ||
                    searchable.contains("芭乐奶绿")
                val passed = parsed.title != "截图分析失败" &&
                    pickupCodeFound && orderContentFound

                val result = JSONObject()
                    .put("passed", passed)
                    .put("pickupCodeFound", pickupCodeFound)
                    .put("orderContentFound", orderContentFound)
                    .put("transport", if (AiSettingsStore.miclawUseExternalAgent) "external-agent" else "direct-api")
                    .put(
                        "credentialMode",
                        if (intent.getBooleanExtra(EXTRA_FORCE_PASS_TOKEN_REFRESH, false)) {
                            "pass-token-refresh"
                        } else {
                            "stored-service-token"
                        },
                    )
                    .put(
                        "notification",
                        JSONObject()
                            .put("title", parsed.title)
                            .put("body", parsed.body)
                            .put("infoLines", JSONArray(parsed.infoLines))
                            .put("content", parsed.content)
                            .put("qrFound", parsed.qrFound)
                            .put("error", parsed.error),
                    )
                resultFile.writeText(result.toString(2))
                check(passed) { "截图内容校验失败: $searchable" }
                AppLog.i(TAG, "MICLAW_SELF_TEST_PASS result=${resultFile.absolutePath}")
            } catch (error: Throwable) {
                AppLog.e(TAG, "MICLAW_SELF_TEST_FAIL", error)
                resultFile.writeText(
                    JSONObject()
                        .put("passed", false)
                        .put("error", error.message ?: error.javaClass.simpleName)
                        .toString(2),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun resolveImageFile(context: Context, intent: Intent): File {
        val cacheRoot = context.cacheDir.canonicalFile
        val requested = intent.getStringExtra(EXTRA_IMAGE_PATH)
            ?.let(::File)
            ?: File(cacheRoot, DEFAULT_IMAGE_FILE_NAME)
        val imageFile = requested.canonicalFile
        check(imageFile.path.startsWith(cacheRoot.path + File.separator)) {
            "测试图片必须位于应用缓存目录"
        }
        check(imageFile.isFile) { "测试截图不存在: ${imageFile.absolutePath}" }
        return imageFile
    }

    private companion object {
        const val TAG = "MiclawSelfTest"
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_FORCE_PASS_TOKEN_REFRESH = "force_pass_token_refresh"
        const val DEFAULT_IMAGE_FILE_NAME = "miclaw-self-test.png"
        const val RESULT_FILE_NAME = "miclaw-self-test-result.json"
    }
}
