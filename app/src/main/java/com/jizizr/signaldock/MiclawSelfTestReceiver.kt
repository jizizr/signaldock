package com.jizizr.signaldock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ADB-triggered, DUMP-protected recognition regression test (legacy component name). */
@Keep
class MiclawSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.startForegroundService(Intent(context, MiclawSelfTestService::class.java).putExtras(intent))
        } catch (error: IllegalStateException) {
            AppLog.w(TAG, "Unable to start diagnostic service: ${error.javaClass.simpleName}")
            File(context.cacheDir, RESULT_FILE_NAME).writeText(JSONObject()
                .put("passed", false).put("error", "请通过 ADB start-foreground-service 启动识别测试").toString())
        }
    }

    internal fun runTest(context: Context, intent: Intent) {
        val resultFile = File(context.cacheDir, RESULT_FILE_NAME)
        try {
            resultFile.delete()
            val imageFile = resolveImageFile(context, intent)
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: error("无法解码测试截图: ${imageFile.absolutePath}")

            val settings = AiSettingsStore.runtimeSnapshot().let { saved ->
                saved.copy(
                    transport = intent.getStringExtra("transport")?.let(AiTransport::valueOf) ?: saved.transport,
                    xiaoAiMode = intent.getStringExtra("mode")?.let(XiaoAiMode::valueOf) ?: saved.xiaoAiMode,
                )
            }
            if (settings.transport in setOf(AiTransport.SUPER_XIAOAI, AiTransport.XIAOMI_PICKUP) &&
                intent.getBooleanExtra("import_system_account", false)) {
                XiaomiCredentialImporter.importFromXiaoAi(context).getOrThrow()
            }
            val parsed = try {
                RustBridge.analyzeScreenshot(context.applicationContext, bitmap, settings)
            } finally {
                bitmap.recycle()
            }
            val searchable = buildString {
                append(parsed.title)
                append('\n')
                append(parsed.body)
                append('\n')
                append(parsed.infoLines.joinToString("\n"))
                append('\n')
                append(parsed.content)
            }
            val expectedCode = intent.getStringExtra("expected_code") ?: "5312"
            val expectedItem = intent.getStringExtra("expected_item") ?: "芭乐奶绿"
            val pickupCodeFound = parsed.title == expectedCode
            val orderContentFound = searchable.contains(expectedItem)
            val verifyFixture = intent.getBooleanExtra("verify_fixture", true)
            val expectNoVoucher = intent.getBooleanExtra("expect_no_voucher", false)
            val passed = if (expectNoVoucher) {
                parsed.error.startsWith("此截图未识别到可上岛凭证")
            } else parsed.error.isBlank() && parsed.title.isNotBlank() &&
                (!verifyFixture || (pickupCodeFound && orderContentFound))

            val result = JSONObject()
                .put("passed", passed)
                .put("expectedNoVoucher", expectNoVoucher)
                .put("pickupCodeFound", pickupCodeFound)
                .put("orderContentFound", orderContentFound)
                .put("transport", settings.transport.name)
                .put("mode", settings.xiaoAiMode.name)
                .put("independentSession", XiaomiSessionStore.load()?.independentDevice == true)
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
            if (passed) AppLog.i(TAG, "RECOGNITION_SELF_TEST_PASS result=${resultFile.absolutePath}")
            else AppLog.w(TAG, "RECOGNITION_SELF_TEST_FAIL result=${resultFile.absolutePath}")
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            AppLog.e(TAG, "RECOGNITION_SELF_TEST_FAIL", error)
            resultFile.writeText(
                JSONObject()
                    .put("passed", false)
                    .put("independentSession", XiaomiSessionStore.load()?.independentDevice == true)
                    .put("error", error.message ?: error.javaClass.simpleName)
                    .toString(2),
            )
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
        const val DEFAULT_IMAGE_FILE_NAME = "miclaw-self-test.png"
        const val RESULT_FILE_NAME = "miclaw-self-test-result.json"
    }
}
