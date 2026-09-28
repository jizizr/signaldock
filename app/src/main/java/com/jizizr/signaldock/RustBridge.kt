package com.jizizr.signaldock

import android.graphics.Bitmap
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

/**
 * JNI bridge to the Rust `liveupdate_core` shared library.
 *
 * Single interface: Android captures screenshot  converts to RGBA bytes
 * [analyzeScreenshot]  Rust handles OCR, QR detection, returns [NotificationData].
 */
object RustBridge {

    private const val TAG = "RustBridge"
    private val preprocessingExecutor = newIdleExecutor(
        "SignalDock-Image",
        2,
        android.os.Process.THREAD_PRIORITY_BACKGROUND,
    )

    init {
        try {
            System.loadLibrary("liveupdate_core")
            AppLog.i(TAG, "liveupdate_core loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e(TAG, "Failed to load liveupdate_core: ${e.message}")
        }
    }

    //  JNI declaration

    @JvmStatic
    private external fun analyzeScreenshotNative(
        rgbaBytes: ByteArray,
        width: Int,
        height: Int,
        apiKey: String,
        baseUrl: String,
        modelId: String,
        reasoningEffort: String,
        jpegB64: String
    ): String

    @JvmStatic
    private external fun miclawPromptNative(): String

    @JvmStatic
    private external fun analyzeMiclawResultNative(
        rawText: String,
        qrJson: String,
    ): String

    @JvmStatic
    private external fun detectQrNative(
        rgbaBytes: ByteArray,
        width: Int,
        height: Int,
    ): String

    @JvmStatic
    private external fun analyzeMiclawDirectNative(
        rgbaBytes: ByteArray,
        width: Int,
        height: Int,
        serviceToken: String,
        cUserId: String,
        enableThinking: Boolean,
        jpegB64: String,
    ): String

    //  Public API

    /**
     * Analyse a screenshot [Bitmap].
     * Converts to RGBA bytes and delegates all processing to Rust.
     */
    fun analyzeScreenshot(
        context: android.content.Context,
        bitmap: Bitmap,
        settings: AiRuntimeSettings = AiSettingsStore.runtimeSnapshot(),
    ): NotificationData {
        val preprocessingStartedAtMs = SystemClock.elapsedRealtime()
        val nativeImageFuture = CompletableFuture.supplyAsync(
            bitmap::toNativeScreenshot,
            preprocessingExecutor,
        )
        var uploadFuture: CompletableFuture<String>? = null
        return try {
            val json = if (settings.transport == AiTransport.XIAOMI_PICKUP || settings.transport == AiTransport.SUPER_XIAOAI) {
                val qrFuture = nativeImageFuture.thenApplyAsync(
                    { image -> detectQrNative(image.rgba, image.width, image.height) },
                    preprocessingExecutor,
                )
                AppLog.i(TAG, "AI request: provider=${settings.transport}, mode=${settings.xiaoAiMode}")
                val rawText = XiaomiRecognitionClient.analyze(
                    context.applicationContext, bitmap,
                    pickup = settings.transport == AiTransport.XIAOMI_PICKUP,
                    mode = settings.xiaoAiMode,
                    prompt = miclawPrompt(),
                )
                analyzeMiclawResultNative(rawText, qrFuture.join())
            } else {
                val jpegFuture = CompletableFuture.supplyAsync(
                    { bitmap.toUploadJpegBase64() },
                    preprocessingExecutor,
                ).also { uploadFuture = it }
                val connection = settings.connection
                AppLog.i(TAG, "AI request: provider=Custom, transport=openai-compatible")
                val image = nativeImageFuture.join()
                val jpegB64 = jpegFuture.join()
                AppLog.i(
                    TAG,
                    "Image preprocessing ready: ${SystemClock.elapsedRealtime() - preprocessingStartedAtMs}ms",
                )
                analyzeScreenshotNative(
                    image.rgba, image.width, image.height,
                    settings.apiKey,
                    connection.baseUrl,
                    connection.modelId,
                    connection.reasoningEffort,
                    jpegB64,
                )
            }
            parseNotificationData(json).also { result ->
                AppLog.i(
                    TAG,
                    "AI result: success=${result.error.isBlank()} qrFound=${result.qrFound} " +
                        "hasPrice=${result.price.isNotBlank()} " +
                        "hasItemDetail=${result.itemDetail.isNotBlank()}",
                )
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "analyzeScreenshot failed: ${e.message}")
            NotificationData(
                title = "截图分析失败",
                body = e.message ?: "",
                error = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            // The caller recycles bitmap after return, including login/network failures.
            // Finish every bitmap reader before allowing that ownership transfer.
            runCatching { nativeImageFuture.join() }
            uploadFuture?.let { runCatching { it.join() } }
        }
    }

    internal fun miclawPrompt(): String = miclawPromptNative()

    internal fun parseMiclawResult(bitmap: Bitmap, rawText: String): NotificationData {
        val image = bitmap.toNativeScreenshot()
        return parseNotificationData(
            analyzeMiclawResultNative(
                rawText,
                detectQrNative(image.rgba, image.width, image.height),
            ),
        )
    }

    //  Private helpers

    private fun parseNotificationData(json: String): NotificationData {
        return try {
            val obj = JSONObject(json)
            obj.optString("debugError")
                .takeIf(String::isNotBlank)
                ?.let { diagnosticError ->
                    AppLog.e(TAG, "AI technical failure: $diagnosticError")
                }
            NotificationData(
                title          = obj.optString("title", ""),
                body           = obj.optString("body", ""),
                infoLines      = obj.optJSONArray("infoLines")
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: listOf(obj.optString("body", "")).filter { it.isNotEmpty() },
                qrFound        = obj.optBoolean("qr_found", false),
                qrRegionPngB64 = obj.optString("qr_region_png_base64", ""),
                content        = obj.optString("content", ""),
                iconType       = obj.optString("iconType", ""),
                buttonText     = obj.optString("buttonText", ""),
                price          = obj.optString("price", ""),
                item           = obj.optString("item", ""),
                itemDetail     = obj.optString("itemDetail", ""),
                merchant       = obj.optString("merchant", ""),
                error          = obj.optString("error", "")
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to parse AI response (${json.length} chars)", e)
            NotificationData(
                title = "识别结果解析失败",
                body = e.message.orEmpty(),
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    //  Data class

    data class NotificationData(
        val title: String,
        /** 辅助详情第一行，适合通知单行紧凑展示 */
        val body: String,
        /** 辅助详情所有行（按 \n 拆分），供灵动岛富界面按行渲染 */
        val infoLines: List<String> = emptyList(),
        val qrFound: Boolean = false,
        val qrRegionPngB64: String = "",
        val content: String = "",
        val iconType: String = "",
        val buttonText: String = "",
        val price: String = "",
        val item: String = "",
        val itemDetail: String = "",
        val merchant: String = "",
        val error: String = ""
    )
}

internal fun isMiclawUnauthorizedDiagnostic(diagnosticError: String): Boolean =
    diagnosticError == "miclaw_http_401"
