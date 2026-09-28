package com.jizizr.signaldock

import android.graphics.Bitmap

/** Owns QR bitmap lifecycles without sending large image data through Binder. */
internal object SessionQrBitmapStore {
    private val pending = mutableMapOf<Int, Bitmap>()
    private val active = mutableMapOf<Int, Bitmap>()

    @Synchronized
    fun stage(sessionId: Int, bitmap: Bitmap) {
        pending.put(sessionId, bitmap)?.takeUnless { it === bitmap }?.recycleSafely()
    }

    @Synchronized
    fun promote(sessionId: Int): Boolean {
        val bitmap = pending.remove(sessionId) ?: return active.containsKey(sessionId)
        active.put(sessionId, bitmap)
            ?.takeUnless { it === bitmap }
            ?.recycleSafely()
        return true
    }

    /** Callers own the copy; the stored bitmap never escapes its lifecycle lock. */
    @Synchronized
    fun copyForSession(sessionId: Int): Bitmap? = (pending[sessionId] ?: active[sessionId])
        ?.let { bitmap ->
            runCatching {
                bitmap.takeUnless(Bitmap::isRecycled)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
        }

    @Synchronized
    fun remove(sessionId: Int) {
        val pendingBitmap = pending.remove(sessionId)
        val activeBitmap = active.remove(sessionId)
        pendingBitmap?.recycleSafely()
        activeBitmap
            ?.takeUnless { it === pendingBitmap }
            ?.recycleSafely()
    }

    @Synchronized
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
