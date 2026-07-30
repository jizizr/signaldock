package com.jizizr.signaldock

import android.util.Log

/** Mirrors android.util.Log while also writing to the bounded diagnostic logger. */
object AppLog {
    fun v(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.VERBOSE, tag, message, throwable)

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.ERROR, tag, message, throwable)

    fun wtf(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.ASSERT, tag, message, throwable)

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        Log.println(priority, tag, message)
        if (throwable != null) Log.println(priority, tag, Log.getStackTraceString(throwable))
        DiagnosticLogStore.record(priority, tag, message, throwable)
    }
}
