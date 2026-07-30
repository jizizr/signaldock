package com.jizizr.signaldock

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object DiagnosticCrashHandler : Thread.UncaughtExceptionHandler {
    private val initialized = AtomicBoolean(false)
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        AppLog.e("CRASH", "Uncaught exception on ${thread.name}", error)
        DiagnosticLogStore.flush(750)
        defaultHandler?.uncaughtException(thread, error) ?: run {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
