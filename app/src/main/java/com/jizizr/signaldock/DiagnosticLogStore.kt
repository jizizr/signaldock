package com.jizizr.signaldock

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Bounded, asynchronous diagnostic log storage inspired by InstallerX. */
object DiagnosticLogStore {
    private const val TAG = "DiagnosticLogStore"
    private const val PREFS_NAME = "diagnostic_logging"
    private const val KEY_ENABLED = "enabled"
    private const val LOG_DIRECTORY = "diagnostics/logs"
    private const val LOG_SUFFIX = ".log"
    private const val MAX_LOG_FILES = 3
    private const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILE_AGE_MS = 24L * 60 * 60 * 1000
    private const val QUEUE_CAPACITY = 512
    private const val MAX_MESSAGE_CHARS = 16 * 1024
    private const val MAX_STACK_CHARS = 32 * 1024

    private val timestampFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    private val fileNameFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())
    private val bearerPattern = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val groqKeyPattern = Regex("\\bgsk_[A-Za-z0-9_-]{8,}")
    private val cookiePattern = Regex("(?i)(Cookie\\s*[:=]\\s*)[^\\r\\n]+")
    private val namedSecretPattern = Regex(
        "(?i)\\b(api[_-]?key|service[_-]?token|pass[_-]?token|access[_-]?token|" +
            "refresh[_-]?token|password|authorization|user[_-]?id|cuser[_-]?id)\\b" +
            "(\\s*[\"']?\\s*[:=]\\s*[\"']?|\\s+)([^,\\s\"'}]+)",
    )
    private val querySecretPattern = Regex(
        "(?i)([?&](?:key|api_key|token|access_token|refresh_token|signature|sig)=)[^&\\s]+",
    )
    private val emailPattern = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val phonePattern = Regex("(?<!\\d)1\\d{10}(?!\\d)")

    private val queue = LinkedBlockingDeque<Command>(QUEUE_CAPACITY)
    private val started = AtomicBoolean(false)
    private val droppedEntries = AtomicLong(0)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var fileLoggingEnabled = true

    private var currentFile: File? = null
    private var currentWriter: BufferedWriter? = null
    private var currentFileCreatedAtMs = 0L
    private var writesSinceFlush = 0

    private sealed interface Command {
        data class Entry(
            val timestampMs: Long,
            val priority: Int,
            val tag: String,
            val threadName: String,
            val message: String,
            val throwable: Throwable?,
        ) : Command

        data class Flush(val latch: CountDownLatch) : Command
        data class Clear(val latch: CountDownLatch, val success: AtomicBoolean) : Command
        data class Snapshot(
            val directory: File,
            val latch: CountDownLatch,
            val result: AtomicReference<List<File>>,
        ) : Command
    }

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val contextRef = context.applicationContext
            appContext = contextRef
            fileLoggingEnabled = contextRef
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
            if (fileLoggingEnabled) startWriterIfNeeded()
        }
    }

    val enabled: Boolean
        get() = fileLoggingEnabled

    val droppedCount: Long
        get() = droppedEntries.get()

    fun setEnabled(enabled: Boolean) {
        val context = appContext ?: return
        if (fileLoggingEnabled == enabled) return
        if (!enabled) {
            record(Log.INFO, TAG, "File logging disabled", null)
            flush(500)
        }
        fileLoggingEnabled = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) {
            record(Log.INFO, TAG, "File logging enabled", null)
        }
    }

    fun record(priority: Int, tag: String, message: String, throwable: Throwable?) {
        if (!fileLoggingEnabled || appContext == null) return
        startWriterIfNeeded()
        val entry = Command.Entry(
            timestampMs = System.currentTimeMillis(),
            priority = priority,
            tag = tag.take(48),
            threadName = Thread.currentThread().name.take(48),
            message = message,
            throwable = throwable,
        )
        if (queue.offerLast(entry)) return

        val oldest = queue.peekFirst()
        if (oldest is Command.Entry && queue.removeFirstOccurrence(oldest)) {
            droppedEntries.incrementAndGet()
            if (!queue.offerLast(entry)) droppedEntries.incrementAndGet()
        } else {
            droppedEntries.incrementAndGet()
        }
    }

    fun flush(timeoutMs: Long = 1000): Boolean {
        if (appContext == null) return false
        val latch = CountDownLatch(1)
        if (!offerControl(Command.Flush(latch), timeoutMs)) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun clear(timeoutMs: Long = 2000): Boolean {
        if (appContext == null) return false
        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        if (!offerControl(Command.Clear(latch, success), timeoutMs)) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && success.get()
    }

    fun snapshotTo(directory: File, timeoutMs: Long = 3000): List<File> {
        if (appContext == null) return emptyList()
        val latch = CountDownLatch(1)
        val result = AtomicReference<List<File>>(emptyList())
        if (!offerControl(Command.Snapshot(directory, latch, result), timeoutMs)) {
            return emptyList()
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else emptyList()
    }

    fun sanitizeForExport(value: String): String = sanitize(value)

    private fun startWriterIfNeeded() {
        if (!started.compareAndSet(false, true)) return
        Thread(::writerLoop, "SignalDock-LogWriter").apply {
            isDaemon = true
            start()
        }
    }

    private fun offerControl(command: Command, timeoutMs: Long): Boolean {
        // Clear/export must also work when logging was disabled before process startup.
        startWriterIfNeeded()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (queue.offerLast(command, 50, TimeUnit.MILLISECONDS)) return true
            val oldest = queue.peekFirst()
            if (oldest is Command.Entry && queue.removeFirstOccurrence(oldest)) {
                droppedEntries.incrementAndGet()
            }
        }
        return false
    }

    private fun writerLoop() {
        while (true) {
            when (val command = queue.takeFirst()) {
                is Command.Entry -> writeEntry(command)
                is Command.Flush -> {
                    runCatching { currentWriter?.flush() }
                    command.latch.countDown()
                }
                is Command.Clear -> {
                    command.success.set(runCatching { clearFiles() }.isSuccess)
                    command.latch.countDown()
                }
                is Command.Snapshot -> {
                    command.result.set(runCatching { copySnapshot(command.directory) }
                        .getOrDefault(emptyList()))
                    command.latch.countDown()
                }
            }
        }
    }

    private fun writeEntry(entry: Command.Entry) {
        runCatching {
            val stack = entry.throwable
                ?.let(Log::getStackTraceString)
                ?.take(MAX_STACK_CHARS)
                .orEmpty()
            val message = sanitize(entry.message.take(MAX_MESSAGE_CHARS))
            val content = buildString(message.length + stack.length + 96) {
                append(timestampFormatter.format(Instant.ofEpochMilli(entry.timestampMs)))
                append(' ')
                append(priorityName(entry.priority))
                append('/')
                append(entry.tag)
                append(" [")
                append(entry.threadName)
                append("]: ")
                append(message)
                append('\n')
                if (stack.isNotEmpty()) {
                    append(sanitize(stack))
                    if (!stack.endsWith('\n')) append('\n')
                }
            }
            ensureWriter(content.toByteArray(StandardCharsets.UTF_8).size.toLong())
            currentWriter?.write(content)
            writesSinceFlush++
            if (entry.priority >= Log.ERROR || writesSinceFlush >= 16) {
                currentWriter?.flush()
                writesSinceFlush = 0
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to write diagnostic log", error)
        }
    }

    private fun ensureWriter(incomingBytes: Long) {
        val now = System.currentTimeMillis()
        val file = currentFile
        val shouldRotate = file == null ||
            !file.exists() ||
            file.length() + incomingBytes > MAX_FILE_SIZE_BYTES ||
            now - currentFileCreatedAtMs > MAX_FILE_AGE_MS
        if (!shouldRotate) return

        closeWriter()
        val directory = logDirectory().apply { mkdirs() }
        val baseName = "signaldock-${fileNameFormatter.format(Instant.ofEpochMilli(now))}"
        var candidate = File(directory, "$baseName$LOG_SUFFIX")
        if (candidate.exists()) {
            candidate = File(directory, "$baseName-${System.nanoTime()}$LOG_SUFFIX")
        }
        currentFile = candidate
        currentFileCreatedAtMs = now
        currentWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(candidate, true), StandardCharsets.UTF_8),
            16 * 1024,
        )
        cleanOldFiles()
    }

    private fun closeWriter() {
        runCatching { currentWriter?.flush() }
        runCatching { currentWriter?.close() }
        currentWriter = null
        currentFile = null
        currentFileCreatedAtMs = 0L
        writesSinceFlush = 0
    }

    private fun clearFiles() {
        closeWriter()
        logDirectory().listFiles()?.forEach(File::delete)
        droppedEntries.set(0)
    }

    private fun copySnapshot(directory: File): List<File> {
        currentWriter?.flush()
        directory.mkdirs()
        return logFiles().mapNotNull { source ->
            runCatching {
                File(directory, source.name).also { destination ->
                    source.copyTo(destination, overwrite = true)
                }
            }.getOrNull()
        }
    }

    private fun cleanOldFiles() {
        logFiles()
            .sortedByDescending(File::lastModified)
            .drop(MAX_LOG_FILES)
            .forEach(File::delete)
    }

    private fun logFiles(): List<File> = logDirectory()
        .listFiles { file -> file.isFile && file.name.endsWith(LOG_SUFFIX) }
        ?.toList()
        .orEmpty()

    private fun logDirectory(): File = File(checkNotNull(appContext).cacheDir, LOG_DIRECTORY)

    private fun sanitize(raw: String): String {
        var value = bearerPattern.replace(raw, "$1<redacted>")
        value = groqKeyPattern.replace(value, "<redacted-groq-key>")
        value = cookiePattern.replace(value, "$1<redacted>")
        value = namedSecretPattern.replace(value) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }
        value = querySecretPattern.replace(value, "$1<redacted>")
        value = emailPattern.replace(value, "<redacted-email>")
        return phonePattern.replace(value, "<redacted-phone>")
    }

    private fun priorityName(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }
}
