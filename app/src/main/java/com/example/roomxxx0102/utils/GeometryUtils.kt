package com.example.roomxxx0102.utils

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * **几何算法工具类 (GeometryUtils)**
 *
 * 该类负责处理“主权领土 (Sovereign Territory)”和“传送门陷阱 (Portal Traps)”底层的空间几何计算。
 *
 * 主要功能：
 * 1. **凸包计算 (Convex Hull)**: 用于根据用户在客厅行走的足迹点云，生成客厅的“最大物理边界”。
 * 2. **点在多边形内判定 (Point in Polygon)**: 用于判断目标是否位于客厅内，或是否落入某个房间的“传送门”区域。
 */
object GeometryUtils {

    /**
     * **计算凸包 (Compute Convex Hull)**
     *
     * 使用 Monotone Chain 算法计算一组点的凸包。暂时没用到这个功能.w
     *
     * **物理意义**:
     * 将用户在客厅边缘走动留下的离散“足迹点云 (Footprint Cloud)”，转换为一个包围这些点的最小凸多边形。
     * 这个多边形即代表了“客厅 (Living Room)”的有效活动范围，排除了墙壁、家具内部等不可达区域。
     *
     * @param points 原始的足迹点集合 (例如用户录制时采集的所有脚底坐标)。
     * @return 构成凸包的顶点列表 (按逆时针或顺时针排序)。如果点少于3个，直接返回原列表。
     */
    fun computeConvexHull(points: List<PointF>): List<PointF> {
        if (points.size <= 2) return points

        // 按 X 坐标排序，X 相同则按 Y 排序
        val sortedPoints = points.sortedWith(compareBy({ it.x }, { it.y }))

        val upperHull = ArrayList<PointF>()
        for (p in sortedPoints) {
            while (upperHull.size >= 2) {
                val p1 = upperHull[upperHull.size - 2]
                val p2 = upperHull[upperHull.size - 1]
                // 叉乘判断是否向左转，如果不是则移除栈顶
                if (crossProduct(p1, p2, p) <= 0) {
                    upperHull.removeAt(upperHull.size - 1)
                } else {
                    break
                }
            }
            upperHull.add(p)
        }

        val lowerHull = ArrayList<PointF>()
        for (i in sortedPoints.indices.reversed()) {
            val p = sortedPoints[i]
            while (lowerHull.size >= 2) {
                val p1 = lowerHull[lowerHull.size - 2]
                val p2 = lowerHull[lowerHull.size - 1]
                if (crossProduct(p1, p2, p) <= 0) {
                    lowerHull.removeAt(lowerHull.size - 1)
                } else {
                    break
                }
            }
            lowerHull.add(p)
        }

        // 移除重复的起点/终点
        upperHull.removeAt(upperHull.size - 1)
        lowerHull.removeAt(lowerHull.size - 1)

        val hull = ArrayList<PointF>(upperHull)
        hull.addAll(lowerHull)
        return hull
    }

    /**
     * **判定点是否在多边形内 (Is Point In Polygon)**
     *
     * 使用射线法 (Ray Casting Algorithm) 判断一个坐标是否位于多边形内部。
     *
     * **物理意义**:
     * 1. **主权领土判定**: 判断当前目标的“脚底坐标”是否在“客厅凸包”内。如果在，则认为目标在客厅。
     * 2. **传送门判定**: 当目标从客厅消失时，判断其最后位置是否落在“房间传送门矩形”内。如果是，则认为进入了该房间。
     *
     * @param point 待检测的目标点 (如人体脚底关键点)。
     * @param polygon 多边形的顶点列表 (如客厅凸包或房间区域)。
     * @return true 表示点在多边形内部或边缘，false 表示在外部。
     */
    fun isPointInPolygon(point: PointF, polygon: List<PointF>): Boolean {
        var intersectCount = 0
        for (i in polygon.indices) {
            val p1 = polygon[i]
            val p2 = polygon[(i + 1) % polygon.size]

            // 射线法判断
            // 1. 点的 Y 坐标必须在 p1 和 p2 的 Y 范围之间
            // 2. 且点在 p1 p2 线段的左侧 (射线向右发射)
            if ((p1.y > point.y) != (p2.y > point.y)) {
                val xIntersect = (p2.x - p1.x) * (point.y - p1.y) / (p2.y - p1.y) + p1.x
                if (point.x < xIntersect) {
                    intersectCount++
                }
            }
        }
        return (intersectCount % 2) == 1
    }

    fun getClosestEdgeIndex(point: PointF, polygon: List<PointF>): Int {
        if (polygon.size < 2) return -1
        var bestIndex = -1
        var minDistance = Float.MAX_VALUE
        val size = polygon.size
        for (i in 0 until size) {
            val p1 = polygon[i]
            val p2 = polygon[(i + 1) % size]
            val dist = pointToSegmentDistance(point.x, point.y, p1.x, p1.y, p2.x, p2.y)
            if (dist < minDistance) {
                minDistance = dist
                bestIndex = i
            }
        }
        return bestIndex
    }

    /**
     * 计算向量 (p1->p2) 与 (p1->p3) 的叉乘 (2D)。
     * > 0 : p1->p2->p3 是逆时针转向 (左转)
     * < 0 : p1->p2->p3 是顺时针转向 (右转)
     * = 0 : 三点共线
     */
    private fun crossProduct(p1: PointF, p2: PointF, p3: PointF): Float {
        return (p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x)
    }

    private fun pointToSegmentDistance(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val l2 = (x1 - x2).pow(2) + (y1 - y2).pow(2)
        if (l2 == 0f) return hypot(px - x1, py - y1)
        var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
        t = max(0f, min(1f, t))
        return hypot(px - (x1 + t * (x2 - x1)), py - (y1 + t * (y2 - y1)))
    }
}
