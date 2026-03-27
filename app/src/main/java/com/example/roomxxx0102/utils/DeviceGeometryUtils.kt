package com.example.roomxxx0102.utils

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DeviceGeometryUtils {
    const val DEFAULT_DEVICE_WIDTH_PX = 200f
    const val DEFAULT_DEVICE_HEIGHT_PX = 300f
    const val MIN_CREATION_HOTSPOT_MARGIN_PX = 150f

    fun clampCreationHotspot(
        rawPx: PointF,
        frameRect: RectF,
        marginPx: Float = MIN_CREATION_HOTSPOT_MARGIN_PX
    ): PointF {
        if (frameRect.width() <= 0f || frameRect.height() <= 0f) {
            return PointF(rawPx.x, rawPx.y)
        }
        val xMargin = min(marginPx, frameRect.width() / 2f)
        val yMargin = min(marginPx, frameRect.height() / 2f)
        return PointF(
            rawPx.x.coerceIn(frameRect.left + xMargin, max(frameRect.left + xMargin, frameRect.right - xMargin)),
            rawPx.y.coerceIn(frameRect.top + yMargin, max(frameRect.top + yMargin, frameRect.bottom - yMargin))
        )
    }

    fun createDefaultPolygon(
        hotspotPx: PointF,
        frameRect: RectF,
        polygonWidthPx: Float = DEFAULT_DEVICE_WIDTH_PX,
        polygonHeightPx: Float = DEFAULT_DEVICE_HEIGHT_PX
    ): MutableList<PointF> {
        if (frameRect.width() <= 0f || frameRect.height() <= 0f) {
            return mutableListOf(
                PointF(0.4f, 0.35f),
                PointF(0.6f, 0.35f),
                PointF(0.6f, 0.65f),
                PointF(0.4f, 0.65f)
            )
        }
        val halfWidth = polygonWidthPx / 2f
        val halfHeight = polygonHeightPx / 2f
        val leftPx = max(frameRect.left, hotspotPx.x - halfWidth)
        val rightPx = min(frameRect.right, hotspotPx.x + halfWidth)
        val topPx = max(frameRect.top, hotspotPx.y - halfHeight)
        val bottomPx = min(frameRect.bottom, hotspotPx.y + halfHeight)
        val left = ((leftPx - frameRect.left) / frameRect.width()).coerceIn(0f, 1f)
        val right = ((rightPx - frameRect.left) / frameRect.width()).coerceIn(0f, 1f)
        val top = ((topPx - frameRect.top) / frameRect.height()).coerceIn(0f, 1f)
        val bottom = ((bottomPx - frameRect.top) / frameRect.height()).coerceIn(0f, 1f)
        return mutableListOf(
            PointF(left, top),
            PointF(right, top),
            PointF(right, bottom),
            PointF(left, bottom)
        )
    }

    fun copyPoints(points: List<PointF>): MutableList<PointF> {
        return points.map { PointF(it.x, it.y) }.toMutableList()
    }

    fun getBounds(points: List<PointF>): RectF {
        return RectF(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x },
            points.maxOf { it.y }
        )
    }

    fun isPointInQuadrilateral(point: PointF, polygon: List<PointF>): Boolean {
        if (polygon.size != 4) return false
        return GeometryUtils.isPointInPolygon(point, polygon) ||
            GeometryUtils.isPointOnPolygonBoundary(point, polygon)
    }

    fun isConvexQuadrilateral(polygon: List<PointF>): Boolean {
        if (polygon.size != 4) return false
        var sign = 0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val c = polygon[(i + 2) % polygon.size]
            val cross = cross(a, b, c)
            if (abs(cross) < 1e-5f) {
                return false
            }
            val currentSign = if (cross > 0f) 1 else -1
            if (sign == 0) {
                sign = currentSign
            } else if (sign != currentSign) {
                return false
            }
        }
        return true
    }

    fun isValidQuadrilateral(polygon: List<PointF>): Boolean {
        return polygon.size == 4 &&
            GeometryUtils.isPolygonSimple(polygon) &&
            isConvexQuadrilateral(polygon)
    }

    fun polygonContainsHotspot(hotspot: PointF, polygon: List<PointF>): Boolean {
        return isPointInQuadrilateral(hotspot, polygon)
    }

    fun translateHotspotAndPolygon(
        hotspot: PointF,
        polygon: List<PointF>,
        deltaX: Float,
        deltaY: Float
    ): Pair<PointF, MutableList<PointF>> {
        val movedHotspot = PointF(hotspot.x + deltaX, hotspot.y + deltaY)
        val movedPolygon = polygon.map { PointF(it.x + deltaX, it.y + deltaY) }.toMutableList()
        return movedHotspot to movedPolygon
    }

    fun coerceTranslationWithinUnitBounds(
        polygon: List<PointF>,
        deltaX: Float,
        deltaY: Float
    ): PointF {
        if (polygon.isEmpty()) return PointF(0f, 0f)
        val bounds = getBounds(polygon)
        val adjustedDx = when {
            bounds.left + deltaX < 0f -> -bounds.left
            bounds.right + deltaX > 1f -> 1f - bounds.right
            else -> deltaX
        }
        val adjustedDy = when {
            bounds.top + deltaY < 0f -> -bounds.top
            bounds.bottom + deltaY > 1f -> 1f - bounds.bottom
            else -> deltaY
        }
        return PointF(adjustedDx, adjustedDy)
    }

    private fun cross(a: PointF, b: PointF, c: PointF): Float {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    }
}
