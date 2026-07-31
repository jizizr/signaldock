package com.jizizr.signaldock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticLogExporter {
    private const val EXPORT_DIRECTORY = "diagnostics/exports"
    private const val STAGING_DIRECTORY = "diagnostics/staging"
    private const val MAX_EXPORT_FILES = 3
    private val fileNameFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    fun createArchive(context: Context): File {
        val appContext = context.applicationContext
        AppLog.i("DiagnosticExport", "Diagnostic export requested")
        val timestamp = fileNameFormatter.format(Instant.now())
        val stagingRoot = File(appContext.cacheDir, STAGING_DIRECTORY).apply { mkdirs() }
        val staging = File(stagingRoot, "$timestamp-${System.nanoTime()}").apply { mkdirs() }
        val exportDirectory = File(appContext.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val destination = uniqueFile(exportDirectory, "SignalDock-diagnostics-$timestamp.zip")

        return try {
            DiagnosticLogStore.snapshotTo(File(staging, "logs"))
            File(staging, "device-info.txt").writeText(buildDeviceInfo(appContext))
            zipDirectory(staging, destination)
            cleanOldExports(exportDirectory)
            destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    fun share(context: Context, archive: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            archive,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, archive.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.diagnostic_share_title)),
        )
    }

    fun clear(context: Context): Boolean {
        val logsCleared = DiagnosticLogStore.clear()
        val exports = File(context.cacheDir, EXPORT_DIRECTORY)
        val staging = File(context.cacheDir, STAGING_DIRECTORY)
        val exportsCleared = runCatching { exports.deleteRecursively() }.getOrDefault(false)
        val stagingCleared = runCatching { staging.deleteRecursively() }.getOrDefault(false)
        return logsCleared && exportsCleared && stagingCleared
    }

    private fun buildDeviceInfo(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val runtime = Runtime.getRuntime()
        val notificationGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val shizukuAvailable = runCatching { AppShell.isShizukuAvailable }.getOrDefault(false)
        val shizukuUid = if (shizukuAvailable) {
            runCatching { AppShell.shizukuUid }.getOrDefault(-1)
        } else {
            -1
        }
        val shizukuMode = when (shizukuUid) {
            0 -> "root"
            2000 -> "shell"
            else -> "unavailable"
        }
        val memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val memoryMaxMb = runtime.maxMemory() / (1024 * 1024)
        val autoPageProfiles = AutoPageProfileStore.loadAll()

        return buildString {
            appendLine("SignalDock diagnostic package")
            appendLine("generatedAt=${Instant.now()}")
            appendLine("package=${context.packageName}")
            appendLine("versionName=${packageInfo.versionName.orEmpty()}")
            appendLine("versionCode=${packageInfo.longVersionCode}")
            appendLine("sourceWindowDiagnostics=${BuildConfig.SOURCE_WINDOW_DIAGNOSTICS}")
            appendLine("processId=${Process.myPid()}")
            appendLine("processUptimeMs=${SystemClock.elapsedRealtime()}")
            appendLine("memoryUsedMb=$memoryUsedMb")
            appendLine("memoryMaxMb=$memoryMaxMb")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            appendLine("androidSdk=${Build.VERSION.SDK_INT}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("notificationGranted=$notificationGranted")
            appendLine("shizukuAvailable=$shizukuAvailable")
            appendLine("shizukuMode=$shizukuMode")
            appendLine("accessibilityServiceReady=${AccessibilityScreenshotService.instance != null}")
            appendLine("aiProvider=${AiSettingsStore.selectedPreset.name}")
            appendLine("autoPageEnabled=${AutoPageProfileStore.enabled}")
            appendLine("autoPageProfileCount=${autoPageProfiles.size}")
            appendLine("autoPageEnabledProfileCount=${autoPageProfiles.count { it.enabled }}")
            appendLine("diagnosticLoggingEnabled=${DiagnosticLogStore.enabled}")
            appendLine("droppedLogEntries=${DiagnosticLogStore.droppedCount}")
            appendLine()
            appendLine("Privacy: screenshots, QR content, API keys, cookies, account credentials, and AI responses are not included.")
        }
    }

    private fun zipDirectory(source: File, destination: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            source.walkTopDown()
                .filter(File::isFile)
                .sortedBy { it.relativeTo(source).invariantSeparatorsPath }
                .forEach { file ->
                    val entryName = file.relativeTo(source).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun cleanOldExports(directory: File) {
        directory.listFiles { file -> file.isFile && file.extension == "zip" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_EXPORT_FILES)
            ?.forEach(File::delete)
    }

    private fun uniqueFile(directory: File, name: String): File {
        val initial = File(directory, name)
        if (!initial.exists()) return initial
        return File(directory, "${initial.nameWithoutExtension}-${System.nanoTime()}.zip")
    }
}
