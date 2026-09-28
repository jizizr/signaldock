package com.jizizr.signaldock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

data class RecognitionHistoryRecord(
    val id: String,
    val createdAtMs: Long,
    val analysisDurationMs: Long,
    val screenshotWidth: Int,
    val screenshotHeight: Int,
    val sourcePackage: String,
    val sourceTaskId: Int,
    val providerName: String,
    val title: String,
    val body: String,
    val infoLines: List<String>,
    val qrFound: Boolean,
    val content: String,
    val iconType: String,
    val buttonText: String,
    val price: String,
    val item: String,
    val itemDetail: String,
    val merchant: String,
    val error: String,
    val hasScreenshot: Boolean,
    val hasQrImage: Boolean,
    val hasSourceIcon: Boolean,
    val sourceActivityClass: String = "",
    val miniProgramLabel: String = "",
    val miniProgramIconHash: String = "",
    val pageStableKeywords: List<String> = emptyList(),
)

internal fun recognitionHistorySummary(record: RecognitionHistoryRecord): String {
    val product = listOf(record.merchant, record.item)
        .filter(String::isNotBlank)
        .joinToString(" · ")
    val credential = listOf(record.content, record.title)
        .filter(String::isNotBlank)
        .joinToString(" ")
    return listOf(product, credential, record.infoLines.firstOrNull().orEmpty(), record.body)
        .firstOrNull(String::isNotBlank)
        ?: "未识别到有效内容"
}

object RecognitionHistoryStore {
    private const val TAG = "RecognitionHistory"
    private const val DIRECTORY = "recognition_history"
    private const val RECORD_FILE = "record.json"
    private const val SCREENSHOT_FILE = "screenshot.jpg"
    private const val SOURCE_ICON_FILE = "source-icon.png"
    private const val QR_FILE = "qr.png"
    private const val MAX_RECORDS = 100
    private const val MAX_STORAGE_BYTES = 160L * 1024 * 1024
    private const val MAX_SCREENSHOT_PIXELS = 4_500_000L
    private const val STALE_TEMP_AGE_MS = 60L * 60 * 1000
    private val pruneLock = Any()
    private val safeIdPattern = Regex("[A-Za-z0-9-]{8,80}")

    fun save(
        context: Context,
        sessionId: Int,
        screenshot: Bitmap,
        data: RustBridge.NotificationData,
        analysisDurationMs: Long,
        pageObservation: PageObservationSnapshot? = null,
        providerName: String,
    ): RecognitionHistoryRecord {
        val createdAtMs = System.currentTimeMillis()
        val id = "$createdAtMs-${UUID.randomUUID()}"
        val root = rootDirectory(context).apply { mkdirs() }
        val temporaryDirectory = File(root, ".$id.tmp")
        val finalDirectory = File(root, id)
        val sourceSnapshot = SourceIconCache.snapshotForHistory(sessionId)
        val qrBitmap = SessionQrBitmapStore.copyForSession(sessionId)

        return try {
            temporaryDirectory.mkdirs()
            val screenshotSaved = saveScreenshot(screenshot, File(temporaryDirectory, SCREENSHOT_FILE))
            val sourceIconSaved = sourceSnapshot?.bitmap?.let { bitmap ->
                saveBitmap(bitmap, File(temporaryDirectory, SOURCE_ICON_FILE), Bitmap.CompressFormat.PNG, 100)
            } == true
            val qrSaved = qrBitmap?.let { bitmap ->
                saveBitmap(bitmap, File(temporaryDirectory, QR_FILE), Bitmap.CompressFormat.PNG, 100)
            } == true

            val record = RecognitionHistoryRecord(
                id = id,
                createdAtMs = createdAtMs,
                analysisDurationMs = analysisDurationMs,
                screenshotWidth = screenshot.width,
                screenshotHeight = screenshot.height,
                sourcePackage = sourceSnapshot?.packageName.orEmpty(),
                sourceTaskId = sourceSnapshot?.taskId ?: -1,
                providerName = providerName,
                title = data.title,
                body = data.body,
                infoLines = data.infoLines,
                qrFound = data.qrFound,
                content = data.content,
                iconType = data.iconType,
                buttonText = data.buttonText,
                price = data.price,
                item = data.item,
                itemDetail = data.itemDetail,
                merchant = data.merchant,
                error = data.error,
                hasScreenshot = screenshotSaved,
                hasQrImage = qrSaved,
                hasSourceIcon = sourceIconSaved,
                sourceActivityClass = pageObservation?.activityClassName.orEmpty(),
                miniProgramLabel = pageObservation?.miniProgramLabel.orEmpty(),
                miniProgramIconHash = pageObservation?.miniProgramIconHash.orEmpty(),
                pageStableKeywords = pageObservation?.stableKeywords.orEmpty(),
            )
            File(temporaryDirectory, RECORD_FILE).writeText(record.toJson().toString())
            check(temporaryDirectory.renameTo(finalDirectory)) {
                "Unable to finalize recognition history record"
            }
            synchronized(pruneLock) { prune(context) }
            AppLog.i(TAG, "Saved history id=$id qr=$qrSaved sourceIcon=$sourceIconSaved")
            record
        } catch (error: Throwable) {
            temporaryDirectory.deleteRecursively()
            AppLog.e(TAG, "Unable to save recognition history", error)
            throw error
        } finally {
            sourceSnapshot?.bitmap?.recycleSafely()
            qrBitmap?.recycleSafely()
        }
    }

    fun loadAll(context: Context): List<RecognitionHistoryRecord> = rootDirectory(context)
        .listFiles(File::isDirectory)
        ?.asSequence()
        ?.filterNot { it.name.startsWith('.') }
        ?.mapNotNull(::readRecord)
        ?.sortedByDescending(RecognitionHistoryRecord::createdAtMs)
        ?.toList()
        .orEmpty()

    fun load(context: Context, id: String): RecognitionHistoryRecord? =
        safeRecordDirectory(context, id)?.let(::readRecord)

    fun delete(context: Context, id: String): Boolean {
        val directory = safeRecordDirectory(context, id) ?: return false
        val deleted = directory.deleteRecursively()
        AppLog.i(TAG, "Deleted history id=$id success=$deleted")
        return deleted
    }

    fun screenshotFile(context: Context, id: String): File? =
        historyFile(context, id, SCREENSHOT_FILE)

    fun replay(context: Context, record: RecognitionHistoryRecord): Result<Unit> {
        val appContext = context.applicationContext
        val sessionId = LiveUpdateService.newSessionId()
        return runCatching {
            val sourceIcon = historyFile(appContext, record.id, SOURCE_ICON_FILE)
                ?.absolutePath
                ?.let(BitmapFactory::decodeFile)
            SourceIconCache.restoreFromHistory(
                sessionId = sessionId,
                packageName = record.sourcePackage.ifBlank { null },
                bitmap = sourceIcon,
                taskId = record.sourceTaskId.takeIf { it >= 0 },
            )
            val qrBitmap = historyFile(appContext, record.id, QR_FILE)
                ?.absolutePath
                ?.let(BitmapFactory::decodeFile)
            if (qrBitmap != null) {
                SessionQrBitmapStore.stage(sessionId, qrBitmap)
                SessionQrBitmapStore.promote(sessionId)
            }

            val provider: SessionNotificationManager = if (
                HyperIslandHelper.shouldUseSuperIsland(appContext)
            ) {
                SuperIslandManager
            } else {
                StandardNotificationManager
            }
            provider.ensureChannels(appContext)
            provider.sendResultNotification(
                context = appContext,
                sessionId = sessionId,
                result = SessionNotificationResult(
                    title = record.title,
                    details = record.infoLines.joinToString("\n").ifBlank { record.body },
                    label = record.content,
                    price = record.price,
                    actionText = record.buttonText.ifBlank { "已完成" },
                    item = record.item,
                    itemDetail = record.itemDetail,
                    merchant = record.merchant,
                    hasQrBitmap = qrBitmap != null,
                ),
                dismissIntent = replayDismissIntent(appContext, sessionId),
            )
            AppLog.i(TAG, "Replayed history id=${record.id} as session=$sessionId")
        }.onFailure {
            SourceIconCache.remove(sessionId)
            SessionQrBitmapStore.remove(sessionId)
            AppLog.e(TAG, "Unable to replay history id=${record.id}", it)
        }
    }

    private fun replayDismissIntent(context: Context, sessionId: Int): PendingIntent =
        PendingIntent.getService(
            context,
            sessionId,
            Intent(context, LiveUpdateService::class.java).apply {
                action = LiveUpdateService.ACTION_STOP
                putExtra(LiveUpdateService.EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun saveScreenshot(bitmap: Bitmap, destination: File): Boolean {
        val pixels = bitmap.width.toLong() * bitmap.height
        if (pixels <= MAX_SCREENSHOT_PIXELS) {
            return saveBitmap(bitmap, destination, Bitmap.CompressFormat.JPEG, 88)
        }
        val scale = sqrt(MAX_SCREENSHOT_PIXELS.toDouble() / pixels.toDouble()).toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = bitmap.scale(width, height)
        return try {
            saveBitmap(scaled, destination, Bitmap.CompressFormat.JPEG, 88)
        } finally {
            if (scaled !== bitmap) scaled.recycleSafely()
        }
    }

    private fun saveBitmap(
        bitmap: Bitmap,
        destination: File,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): Boolean = destination.outputStream().buffered().use { output ->
        bitmap.compress(format, quality, output)
    }

    private fun prune(context: Context) {
        val root = rootDirectory(context)
        val staleBefore = System.currentTimeMillis() - STALE_TEMP_AGE_MS
        root.listFiles(File::isDirectory)
            ?.filter { it.name.startsWith('.') && it.lastModified() < staleBefore }
            ?.forEach(File::deleteRecursively)
        val directories = root
            .listFiles(File::isDirectory)
            ?.filterNot { it.name.startsWith('.') }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
            .toMutableList()
        directories.drop(MAX_RECORDS).forEach { directory ->
            directory.deleteRecursively()
            directories.remove(directory)
        }
        var totalBytes = directories.sumOf(::directorySize)
        directories.asReversed().toList().forEach { directory ->
            if (totalBytes <= MAX_STORAGE_BYTES || directories.size <= 1) return@forEach
            val size = directorySize(directory)
            if (directory.deleteRecursively()) {
                totalBytes -= size
                directories.remove(directory)
            }
        }
    }

    private fun readRecord(directory: File): RecognitionHistoryRecord? = runCatching {
        JSONObject(File(directory, RECORD_FILE).readText()).toHistoryRecord()
    }.onFailure { error ->
        AppLog.w(TAG, "Ignoring unreadable history directory=${directory.name}", error)
    }.getOrNull()

    private fun rootDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DIRECTORY)

    private fun safeRecordDirectory(context: Context, id: String): File? =
        id.takeIf(safeIdPattern::matches)?.let { File(rootDirectory(context), it) }

    private fun historyFile(context: Context, id: String, name: String): File? =
        safeRecordDirectory(context, id)
            ?.let { File(it, name) }
            ?.takeIf(File::isFile)

    private fun directorySize(directory: File): Long = directory.walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)

    private fun RecognitionHistoryRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAtMs", createdAtMs)
        put("analysisDurationMs", analysisDurationMs)
        put("screenshotWidth", screenshotWidth)
        put("screenshotHeight", screenshotHeight)
        put("sourcePackage", sourcePackage)
        put("sourceTaskId", sourceTaskId)
        put("providerName", providerName)
        put("title", title)
        put("body", body)
        put("infoLines", JSONArray(infoLines))
        put("qrFound", qrFound)
        put("content", content)
        put("iconType", iconType)
        put("buttonText", buttonText)
        put("price", price)
        put("item", item)
        put("itemDetail", itemDetail)
        put("merchant", merchant)
        put("error", error)
        put("hasScreenshot", hasScreenshot)
        put("hasQrImage", hasQrImage)
        put("hasSourceIcon", hasSourceIcon)
        put("sourceActivityClass", sourceActivityClass)
        put("miniProgramLabel", miniProgramLabel)
        put("miniProgramIconHash", miniProgramIconHash)
        put("pageStableKeywords", JSONArray(pageStableKeywords))
    }

    private fun JSONObject.toHistoryRecord(): RecognitionHistoryRecord {
        val lines = optJSONArray("infoLines")?.let { array ->
            (0 until array.length()).map(array::optString)
        }.orEmpty()
        return RecognitionHistoryRecord(
            id = getString("id"),
            createdAtMs = optLong("createdAtMs"),
            analysisDurationMs = optLong("analysisDurationMs"),
            screenshotWidth = optInt("screenshotWidth"),
            screenshotHeight = optInt("screenshotHeight"),
            sourcePackage = optString("sourcePackage"),
            sourceTaskId = optInt("sourceTaskId", -1),
            providerName = optString("providerName"),
            title = optString("title"),
            body = optString("body"),
            infoLines = lines,
            qrFound = optBoolean("qrFound"),
            content = optString("content"),
            iconType = optString("iconType"),
            buttonText = optString("buttonText"),
            price = optString("price"),
            item = optString("item"),
            itemDetail = optString("itemDetail"),
            merchant = optString("merchant"),
            error = optString("error"),
            hasScreenshot = optBoolean("hasScreenshot"),
            hasQrImage = optBoolean("hasQrImage"),
            hasSourceIcon = optBoolean("hasSourceIcon"),
            sourceActivityClass = optString("sourceActivityClass"),
            miniProgramLabel = optString("miniProgramLabel"),
            miniProgramIconHash = optString("miniProgramIconHash"),
            pageStableKeywords = optJSONArray("pageStableKeywords").toStringList(),
        )
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }
}
