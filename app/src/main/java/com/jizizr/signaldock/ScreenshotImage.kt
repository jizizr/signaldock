package com.jizizr.signaldock

import android.graphics.Bitmap
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal data class NativeScreenshot(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

internal fun Bitmap.toNativeScreenshot(): NativeScreenshot {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val rgba = ByteArray(pixels.size * 4)
    var index = 0
    while (index < pixels.size) {
        val argb = pixels[index]
        val offset = index * 4
        rgba[offset] = (argb shr 16).toByte()
        rgba[offset + 1] = (argb shr 8).toByte()
        rgba[offset + 2] = argb.toByte()
        rgba[offset + 3] = (argb shr 24).toByte()
        index++
    }
    return NativeScreenshot(width, height, rgba)
}

internal fun Bitmap.toJpegBytes(quality: Int): ByteArray =
    ByteArrayOutputStream().use { output ->
        check(compress(Bitmap.CompressFormat.JPEG, quality, output)) { "截图 JPEG 编码失败" }
        output.toByteArray()
    }

internal fun Bitmap.toJpegBase64(quality: Int): String =
    Base64.encodeToString(toJpegBytes(quality), Base64.NO_WRAP)

internal fun Bitmap.toUploadJpegBytes(maxSide: Int = 1600, quality: Int = 50): ByteArray =
    withUploadSize(maxSide) { it.toJpegBytes(quality) }

internal fun Bitmap.toUploadJpegBase64(
    maxSide: Int = 1600,
    quality: Int = 50,
): String = withUploadSize(maxSide) { prepared ->
    prepared.toJpegBase64(quality)
}

internal inline fun <T> Bitmap.withUploadSize(
    maxSide: Int,
    block: (Bitmap) -> T,
): T {
    val longestSide = maxOf(width, height)
    if (longestSide <= maxSide) return block(this)

    val ratio = maxSide.toFloat() / longestSide
    val scaled = scale(
        width = (width * ratio).roundToInt().coerceAtLeast(1),
        height = (height * ratio).roundToInt().coerceAtLeast(1),
    )
    return try {
        block(scaled)
    } finally {
        scaled.recycle()
    }
}
