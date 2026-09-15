package com.example.roomxxx0102.logic.roomalgorithm.flow

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock

/** Captures source frames before YOLO backpressure. Bytes are owned, immutable, and bounded. */
object PortalFrameHub {
    data class Stamp(val epoch: Long, val sequence: Long, val timestampMs: Long, val width: Int, val height: Int)
    internal data class GrayFrame(val stamp: Stamp, val width: Int, val height: Int, val pixels: ByteArray)
    data class PoseMetadata(val stamp: Stamp, val roi: RectF?, val successful: Boolean)
    @Volatile var enabled: Boolean = false
        private set
    @Volatile var epoch: Long = 1L
        private set
    private var sequence = 0L
    private var last: Stamp? = null
    private val frames = ArrayDeque<GrayFrame>()

    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled != value) { enabled = value; resetSource() }
    }
    @Synchronized fun resetSource() {
        epoch++; frames.clear(); last = null
    }
    @Synchronized fun capture(bitmap: Bitmap, timestampMs: Long = SystemClock.elapsedRealtime(), advanced: Boolean = true): Stamp {
        val time = timestampMs.coerceAtLeast(0)
        val old = last
        if (old != null && time < old.timestampMs) resetSource()
        if (last != null && (!advanced || time == last!!.timestampMs)) return last!!
        val stamp = Stamp(epoch, ++sequence, time, bitmap.width, bitmap.height)
        last = stamp
        if (!enabled) return stamp
        val scale = minOf(1.0, 640.0 / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).toInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        val small = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        try { small.getPixels(pixels, 0, w, 0, 0, w, h) } finally { if (small !== bitmap) small.recycle() }
        val gray = ByteArray(pixels.size) { i ->
            val p = pixels[i]
            (((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 + (p and 255) * 29) shr 8).toByte()
        }
        frames.add(GrayFrame(stamp, w, h, gray))
        while (frames.size > 32) frames.removeFirst()
        return stamp
    }
    @Synchronized internal fun through(after: Long, target: Stamp): List<GrayFrame> = frames.filter {
        it.stamp.epoch == target.epoch && it.stamp.sequence > after && it.stamp.sequence <= target.sequence
    }
    @Synchronized internal fun before(target: Stamp): List<GrayFrame> = frames.filter {
        it.stamp.epoch == target.epoch && it.stamp.sequence <= target.sequence && target.timestampMs - it.stamp.timestampMs <= 900
    }
}
