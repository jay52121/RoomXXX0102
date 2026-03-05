package com.example.roomxxx0102.logic.presence

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Presence 模块使用的二维点（归一化坐标 0~1）。
 */
data class PresencePoint(
    val x: Double,
    val y: Double
)

/**
 * Presence 模块的几何工具集（纯 Kotlin/JVM）。
 */
object PresenceGeometry {

    /**
     * 点是否在多边形内（边界也视为 true）。
     */
    fun isPointInPolygon(point: PresencePoint, polygon: List<PresencePoint>): Boolean {
        if (polygon.size < 3) return false
        if (isPointOnPolygonBoundary(point, polygon)) return true
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val intersect = ((pi.y > point.y) != (pj.y > point.y)) &&
                (point.x < (pj.x - pi.x) * (point.y - pi.y) / ((pj.y - pi.y).takeIf { abs(it) > 1e-12 } ?: 1e-12) + pi.x)
            if (intersect) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 点到线段的最短距离。
     */
    fun distancePointToSegment(point: PresencePoint, a: PresencePoint, b: PresencePoint): Double {
        val t = projectTParamOnSegment(point, a, b).coerceIn(0.0, 1.0)
        val projX = a.x + t * (b.x - a.x)
        val projY = a.y + t * (b.y - a.y)
        return hypot(point.x - projX, point.y - projY)
    }

    /**
     * 点在线段上的投影参数 t。
     * t=0 落在 a，t=1 落在 b；可小于0或大于1（在线段延长线上）。
     */
    fun projectTParamOnSegment(point: PresencePoint, a: PresencePoint, b: PresencePoint): Double {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val denom = vx * vx + vy * vy
        if (denom <= 1e-12) return 0.0
        return ((point.x - a.x) * vx + (point.y - a.y) * vy) / denom
    }

    /**
     * 点到多边形边界最短距离。
     */
    fun distancePointToPolygonBoundary(point: PresencePoint, polygon: List<PresencePoint>): Double {
        if (polygon.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            best = min(best, distancePointToSegment(point, a, b))
        }
        return best
    }

    private fun isPointOnPolygonBoundary(
        point: PresencePoint,
        polygon: List<PresencePoint>,
        epsilon: Double = 1e-6
    ): Boolean {
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val dist = distancePointToSegment(point, a, b)
            if (dist <= epsilon) {
                val minX = min(a.x, b.x) - epsilon
                val maxX = max(a.x, b.x) + epsilon
                val minY = min(a.y, b.y) - epsilon
                val maxY = max(a.y, b.y) + epsilon
                if (point.x in minX..maxX && point.y in minY..maxY) {
                    return true
                }
            }
        }
        return false
    }
}

