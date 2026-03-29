package com.example.roomxxx0102.logic.video

import android.graphics.Bitmap
import kotlin.math.abs

data class FrameSignature(
    val samples: IntArray,
    val summary: String
)

object FrameSignatureUtils {
    private const val SAMPLE_GRID = 8
    private const val SAMPLE_COUNT = SAMPLE_GRID * SAMPLE_GRID
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    fun create(bitmap: Bitmap): FrameSignature {
        val samples = IntArray(SAMPLE_COUNT)
        val stepX = (bitmap.width - 1).coerceAtLeast(1).toFloat() / (SAMPLE_GRID - 1)
        val stepY = (bitmap.height - 1).coerceAtLeast(1).toFloat() / (SAMPLE_GRID - 1)
        var hash = FNV_OFFSET_BASIS
        var index = 0
        for (sy in 0 until SAMPLE_GRID) {
            val py = (sy * stepY).toInt().coerceIn(0, bitmap.height - 1)
            for (sx in 0 until SAMPLE_GRID) {
                val px = (sx * stepX).toInt().coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(px, py)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val gray = (r * 30 + g * 59 + b * 11) / 100
                samples[index++] = gray
                hash = hash xor gray.toLong()
                hash *= FNV_PRIME
            }
        }
        return FrameSignature(
            samples = samples,
            summary = java.lang.Long.toUnsignedString(hash, 16)
        )
    }

    fun differenceScore(anchor: FrameSignature, current: FrameSignature): Float {
        val count = minOf(anchor.samples.size, current.samples.size).coerceAtLeast(1)
        var sum = 0f
        for (i in 0 until count) {
            sum += abs(anchor.samples[i] - current.samples[i]).toFloat() / 255f
        }
        return sum / count
    }
}
