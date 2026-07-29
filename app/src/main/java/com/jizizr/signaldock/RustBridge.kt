package com.jizizr.signaldock

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * JNI bridge to the Rust `liveupdate_core` shared library.
 *
 * Single interface: Android captures screenshot  converts to RGBA bytes
 * [analyzeScreenshot]  Rust handles OCR, QR detection, returns [NotificationData].
 */
object RustBridge {

    private const val TAG = "RustBridge"
    private val preprocessingExecutor = Executors.newFixedThreadPool(2)

    init {
        try {
            System.loadLibrary("liveupdate_core")
            Log.i(TAG, "liveupdate_core loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load liveupdate_core: ${e.message}")
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
    fun analyzeScreenshot(context: android.content.Context, bitmap: Bitmap): NotificationData {
        val settings = AiSettingsStore.runtimeSnapshot()
        val preprocessingStartedAtMs = SystemClock.elapsedRealtime()
        val nativeImageFuture = CompletableFuture.supplyAsync(
            bitmap::toNativeScreenshot,
            preprocessingExecutor,
        )
        return try {
            val json = if (settings.usesMiclaw) {
                if (settings.miclawUseExternalAgent) {
                    Log.i(TAG, "AI request: provider=Miclaw, transport=external-agent")
                    val qrFuture = nativeImageFuture.thenApplyAsync(
                        { image -> detectQrNative(image.rgba, image.width, image.height) },
                        preprocessingExecutor,
                    )
                    val rawText = MiclawAgentClient.analyzeScreenshot(
                        context.applicationContext,
                        bitmap,
                        miclawPrompt(),
                    ).getOrThrow()
                    analyzeMiclawResultNative(rawText, qrFuture.join())
                } else {
                    val jpegFuture = CompletableFuture.supplyAsync(
                        { bitmap.toUploadJpegBase64() },
                        preprocessingExecutor,
                    )
                    var session = MiclawSessionStore.load()
                        ?: error("Miclaw 尚未登录，请先在 AI 服务中登录")
                    if (session.serviceToken.isBlank()) {
                        session = MiclawPassportClient.refresh(session)
                        MiclawSessionStore.save(session)
                    }
                    Log.i(
                        TAG,
                        "AI request: provider=Miclaw, transport=direct-api, thinking=${settings.miclawThinkingEnabled}",
                    )
                    val image = nativeImageFuture.join()
                    val jpegB64 = jpegFuture.join()
                    Log.i(
                        TAG,
                        "Image preprocessing ready: ${SystemClock.elapsedRealtime() - preprocessingStartedAtMs}ms",
                    )
                    var directJson = analyzeMiclawDirectNative(
                        image.rgba, image.width, image.height,
                        session.serviceToken,
                        session.cUserId,
                        settings.miclawThinkingEnabled,
                        jpegB64,
                    )
                    if (JSONObject(directJson).optString("error").contains("Miclaw HTTP 401")) {
                        session = if (session.canRefresh) {
                            Log.i(TAG, "Miclaw token expired; refreshing with encrypted passToken")
                            MiclawPassportClient.refresh(session).also(MiclawSessionStore::save)
                        } else if (AppShell.isShizukuRoot) {
                            Log.i(TAG, "Miclaw token expired; refreshing through system Xiaomi account")
                            MiclawCredentialImporter.importFromSystemAccount(
                                context.applicationContext,
                                forceRefresh = true,
                            ).getOrThrow()
                        } else {
                            error("Miclaw 登录已失效，请重新登录")
                        }
                        directJson = analyzeMiclawDirectNative(
                            image.rgba, image.width, image.height,
                            session.serviceToken,
                            session.cUserId,
                            settings.miclawThinkingEnabled,
                            jpegB64,
                        )
                    }
                    directJson
                }
            } else {
                val jpegFuture = CompletableFuture.supplyAsync(
                    { bitmap.toUploadJpegBase64() },
                    preprocessingExecutor,
                )
                val connection = settings.connection
                Log.i(TAG, "AI request: model=${connection.modelId}, endpoint=${connection.baseUrl}")
                val image = nativeImageFuture.join()
                val jpegB64 = jpegFuture.join()
                Log.i(
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
            parseNotificationData(json)
        } catch (e: Exception) {
            Log.e(TAG, "analyzeScreenshot failed: ${e.message}")
            NotificationData(title = "截图分析失败", body = e.message ?: "")
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
            Log.e(TAG, "Failed to parse AI response (${json.length} chars)", e)
            NotificationData(title = "", body = "")
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
