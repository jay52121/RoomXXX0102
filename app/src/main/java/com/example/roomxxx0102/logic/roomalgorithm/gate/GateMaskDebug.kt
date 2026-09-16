package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.opencv.core.Mat
import org.opencv.core.Rect
import java.util.concurrent.ConcurrentHashMap

/** One horizontal run of source pixels. Coordinates are normalized against the source frame. */
internal data class GatePixelRun(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

internal data class GateEncodedMask(
    val runs: List<GatePixelRun>,
    val pixels: Int,
)

/**
 * Converts a binary one-channel mask into exact horizontal pixel runs.
 * Gaps stay gaps: this never closes contours or fills pixels that are zero in the source mask.
 */
internal fun encodeGateMaskRuns(
    bytes: ByteArray,
    width: Int,
    height: Int,
    rectX: Int,
    rectY: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): GateEncodedMask {
    if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0 || bytes.size < width * height) {
        return GateEncodedMask(emptyList(), 0)
    }
    val runs = ArrayList<GatePixelRun>()
    var pixels = 0
    for (y in 0 until height) {
        var x = 0
        val row = y * width
        while (x < width) {
            while (x < width && (bytes[row + x].toInt() and 0xff) == 0) x++
            if (x >= width) break
            val start = x
            while (x < width && (bytes[row + x].toInt() and 0xff) != 0) x++
            val endExclusive = x
            pixels += endExclusive - start
            runs += GatePixelRun(
                (rectX + start).toDouble() / sourceWidth,
                (rectY + y).toDouble() / sourceHeight,
                (rectX + endExclusive).toDouble() / sourceWidth,
                (rectY + y + 1).toDouble() / sourceHeight,
            )
        }
    }
    return GateEncodedMask(runs, pixels)
}

internal data class GateDebugMaskSnapshot(
    val motionRuns: List<GatePixelRun>,
    val backgroundRuns: List<GatePixelRun>,
    val ownedRuns: List<GatePixelRun>,
    val motionPixels: Int,
    val backgroundPixels: Int,
    val ownedPixels: Int,
)

/** Visualization-only bridge. These masks must never feed transition decisions. */
internal object GateMaskDebug {
    private val latest = ConcurrentHashMap<String, GateDebugMaskSnapshot>()

    fun publish(
        gateId: String,
        rect: Rect,
        sourceWidth: Int,
        sourceHeight: Int,
        motion: Mat,
        backgroundDiff: Mat,
        owned: Mat?,
    ) {
        val motionMask = encodeMat(motion, rect, sourceWidth, sourceHeight)
        val backgroundMask = encodeMat(backgroundDiff, rect, sourceWidth, sourceHeight)
        val ownedMask = if (owned == null || owned.empty()) GateEncodedMask(emptyList(), 0)
            else encodeMat(owned, rect, sourceWidth, sourceHeight)
        latest[gateId] = GateDebugMaskSnapshot(
            motionMask.runs,
            backgroundMask.runs,
            ownedMask.runs,
            motionMask.pixels,
            backgroundMask.pixels,
            ownedMask.pixels,
        )
    }

    fun snapshot(gateId: String): GateDebugMaskSnapshot? = latest[gateId]
    fun clearGate(gateId: String) { latest.remove(gateId) }
    fun clear() { latest.clear() }

    private fun encodeMat(mask: Mat, rect: Rect, sourceWidth: Int, sourceHeight: Int): GateEncodedMask {
        if (mask.empty()) return GateEncodedMask(emptyList(), 0)
        val width = mask.cols()
        val height = mask.rows()
        val bytes = ByteArray(width * height)
        mask.get(0, 0, bytes)
        return encodeGateMaskRuns(bytes, width, height, rect.x, rect.y, sourceWidth, sourceHeight)
    }
}
