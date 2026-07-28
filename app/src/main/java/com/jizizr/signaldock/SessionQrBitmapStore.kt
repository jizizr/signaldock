package com.jizizr.signaldock

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/** Owns QR bitmap lifecycles without sending large image data through Binder. */
internal object SessionQrBitmapStore {
    private val pending = ConcurrentHashMap<Int, Bitmap>()
    private val active = ConcurrentHashMap<Int, Bitmap>()

    fun stage(sessionId: Int, bitmap: Bitmap) {
        pending.put(sessionId, bitmap)?.recycleSafely()
    }

    fun promote(sessionId: Int): Boolean {
        val bitmap = pending.remove(sessionId) ?: return active.containsKey(sessionId)
        active.put(sessionId, bitmap)
            ?.takeUnless { it === bitmap }
            ?.recycleSafely()
        return true
    }

    fun bitmapFor(sessionId: Int): Bitmap? = active[sessionId]

    fun remove(sessionId: Int) {
        val pendingBitmap = pending.remove(sessionId)
        val activeBitmap = active.remove(sessionId)
        pendingBitmap?.recycleSafely()
        activeBitmap
            ?.takeUnless { it === pendingBitmap }
            ?.recycleSafely()
    }

    fun clear() {
        val bitmaps = buildSet {
            addAll(pending.values)
            addAll(active.values)
        }
        pending.clear()
        active.clear()
        bitmaps.forEach { it.recycleSafely() }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }
}
