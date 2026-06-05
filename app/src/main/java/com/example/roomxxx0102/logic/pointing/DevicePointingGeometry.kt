package com.example.roomxxx0102.logic.pointing

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object DevicePointingGeometry {
    fun point(x: Float, y: Float): PointF = PointF().apply {
        this.x = x
        this.y = y
    }

    fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF = RectF().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun clamp(value: Float, minValue: Float, maxValue: Float): Float {
        return min(max(value, minValue), maxValue)
    }

    fun dot(a: PointF, b: PointF): Float = a.x * b.x + a.y * b.y

    fun subtract(a: PointF, b: PointF): PointF = point(a.x - b.x, a.y - b.y)

    fun add(a: PointF, b: PointF): PointF = point(a.x + b.x, a.y + b.y)

    fun scale(a: PointF, factor: Float): PointF = point(a.x * factor, a.y * factor)

    fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

    fun normalize(v: PointF): PointF {
        val length = hypot(v.x, v.y)
        if (length < 1e-6f) return point(1f, 0f)
        return point(v.x / length, v.y / length)
    }

    fun pointToRayDistance(point: PointF, rayOrigin: PointF, rayDirection: PointF): Float {
        val dir = normalize(rayDirection)
        val rel = subtract(point, rayOrigin)
        val projection = dot(rel, dir)
        if (projection <= 0f) {
            return distance(point, rayOrigin)
        }
        val closest = add(rayOrigin, scale(dir, projection))
        return distance(point, closest)
    }

    fun polygonBounds(polygon: List<PointF>): RectF {
        return rect(
            polygon.minOf { it.x },
            polygon.minOf { it.y },
            polygon.maxOf { it.x },
            polygon.maxOf { it.y }
        )
    }

    fun rayIntersectsPolygon(rayOrigin: PointF, rayDirection: PointF, polygon: List<PointF>): Boolean {
        return rayPenetrationLength(rayOrigin, rayDirection, polygon) > 1e-4f
    }

    fun rayToPolygonDistance(rayOrigin: PointF, rayDirection: PointF, polygon: List<PointF>): Float {
        if (polygon.size < 2) return Float.MAX_VALUE
        if (rayIntersectsPolygon(rayOrigin, rayDirection, polygon)) return 0f
        var best = Float.MAX_VALUE
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            best = min(best, rayToSegmentDistance(rayOrigin, rayDirection, a, b))
        }
        return best
    }

    fun rayPenetrationLength(rayOrigin: PointF, rayDirection: PointF, polygon: List<PointF>): Float {
        if (polygon.size < 3) return 0f
        val dir = normalize(rayDirection)
        val intersections = mutableListOf<Float>()
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            raySegmentIntersectionT(rayOrigin, dir, a, b)?.let { t ->
                if (t >= 0f) {
                    intersections.add(t)
                }
            }
        }
        val unique = intersections
            .sorted()
            .fold(mutableListOf<Float>()) { acc, value ->
                if (acc.none { abs(it - value) < 1e-3f }) {
                    acc.add(value)
                }
                acc
            }
        if (unique.isEmpty()) return 0f
        val originInside = isPointInPolygon(rayOrigin, polygon)
        return when {
            originInside -> unique.maxOrNull() ?: 0f
            unique.size >= 2 -> (unique.last() - unique.first()).coerceAtLeast(0f)
            else -> 0f
        }
    }

    fun isPointInPolygon(point: PointF, polygon: List<PointF>): Boolean {
        var intersections = 0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if (isPointOnSegment(point, a, b)) return true
            if ((a.y > point.y) != (b.y > point.y)) {
                val xIntersect = (b.x - a.x) * (point.y - a.y) / (b.y - a.y + 1e-6f) + a.x
                if (point.x < xIntersect) intersections += 1
            }
        }
        return intersections % 2 == 1
    }

    private fun rayToSegmentDistance(rayOrigin: PointF, rayDirection: PointF, a: PointF, b: PointF): Float {
        if (raySegmentIntersectionT(rayOrigin, rayDirection, a, b) != null) return 0f
        val endpointMin = min(
            pointToRayDistance(a, rayOrigin, rayDirection),
            pointToRayDistance(b, rayOrigin, rayDirection)
        )
        val originToSegment = pointToSegmentDistance(rayOrigin, a, b)
        return min(endpointMin, originToSegment)
    }

    private fun pointToSegmentDistance(point: PointF, a: PointF, b: PointF): Float {
        val ab = subtract(b, a)
        val abLen2 = dot(ab, ab)
        if (abLen2 < 1e-6f) return distance(point, a)
        val t = clamp(dot(subtract(point, a), ab) / abLen2, 0f, 1f)
        val projection = point(a.x + ab.x * t, a.y + ab.y * t)
        return distance(point, projection)
    }

    private fun raySegmentIntersectionT(
        rayOrigin: PointF,
        rayDirection: PointF,
        a: PointF,
        b: PointF
    ): Float? {
        val r = normalize(rayDirection)
        val s = subtract(b, a)
        val rxs = cross(r, s)
        val qpxr = cross(subtract(a, rayOrigin), r)
        if (abs(rxs) < 1e-6f) {
            return null
        }
        val qp = subtract(a, rayOrigin)
        val t = cross(qp, s) / rxs
        val u = qpxr / rxs
        return if (t >= 0f && u in 0f..1f) t else null
    }

    private fun isPointOnSegment(point: PointF, a: PointF, b: PointF, epsilon: Float = 1e-4f): Boolean {
        val cross = cross(subtract(point, a), subtract(b, a))
        if (abs(cross) > epsilon) return false
        val dot = dot(subtract(point, a), subtract(point, b))
        return dot <= epsilon
    }

    private fun cross(a: PointF, b: PointF): Float = a.x * b.y - a.y * b.x
}
