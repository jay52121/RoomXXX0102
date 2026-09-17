package com.example.roomxxx0102.logic.validation

import android.graphics.PointF
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.data.model.RoomConfig
import kotlin.math.abs

/**
 * 人工事件的“门位真值候选”。
 *
 * 这里只允许使用人工事件时刻附近的原始 Pose 人体框和静态房门(Portal)几何；
 * 严禁读取 V4 的候选门、锁门结果、FSM、Ledger 或最终房间结果，避免循环验证。
 */
internal data class InferredPortalTruth(
    val portalRoomId: String,
    val portalName: String,
    val poseId: Int,
    val score: Double,
    val personCoverage: Double,
    val portalCoverage: Double,
    val sampleTimeMs: Long,
)

internal object MarkedPortalTruthInference {
    internal data class Region(
        val roomId: String,
        val roomName: String,
        val polygon: List<PointF>,
        val area: Double,
    )

    fun regions(rooms: List<RoomConfig>): List<Region> = rooms
        .asSequence()
        .filter { !it.isSovereignTerritory }
        .mapNotNull { room ->
            val polygon = room.boundaryPoints
            val area = polygonAreaF(polygon)
            if (polygon.size < 3 || area <= 1e-8) null
            else Region(room.id, room.name, polygon.map { PointF(it.x, it.y) }, area)
        }
        .toList()

    fun infer(
        poses: List<PoseResult>,
        regions: List<Region>,
        timeMs: Long,
    ): InferredPortalTruth? {
        var best: InferredPortalTruth? = null
        for (pose in poses) {
            val box = pose.box
            val left = box.left.toDouble()
            val top = box.top.toDouble()
            val right = box.right.toDouble()
            val bottom = box.bottom.toDouble()
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) continue
            val width = right - left
            val height = bottom - top
            if (width <= 0.0 || height <= 0.0) continue
            val bodyArea = (width * height).coerceAtLeast(1e-9)
            for (region in regions) {
                val intersection = intersectionArea(region.polygon, left, top, right, bottom)
                if (intersection <= 1e-10) continue
                val personCoverage = (intersection / bodyArea).coerceIn(0.0, 1.0)
                val portalCoverage = (intersection / region.area).coerceIn(0.0, 1.0)
                // 门洞往往只覆盖人体的一部分，所以门洞被人体覆盖的比例略高权重；
                // 人体进入门洞的比例同时抑制“超大框只擦到门边”的情况。
                val score = 0.60 * portalCoverage + 0.40 * personCoverage
                val candidate = InferredPortalTruth(
                    portalRoomId = region.roomId,
                    portalName = region.roomName,
                    poseId = pose.id,
                    score = score,
                    personCoverage = personCoverage,
                    portalCoverage = portalCoverage,
                    sampleTimeMs = timeMs,
                )
                if (best == null || candidate.score > best.score) best = candidate
            }
        }
        return best
    }

    /** Sutherland-Hodgman：把任意 Portal 多边形裁剪到人体矩形，得到精确重叠面积。 */
    private fun intersectionArea(
        polygon: List<PointF>,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Double {
        if (polygon.size < 3) return 0.0
        var clipped = polygon.map { DPoint(it.x.toDouble(), it.y.toDouble()) }
        clipped = clip(clipped, Axis.LEFT, left)
        clipped = clip(clipped, Axis.RIGHT, right)
        clipped = clip(clipped, Axis.TOP, top)
        clipped = clip(clipped, Axis.BOTTOM, bottom)
        return polygonAreaD(clipped)
    }

    private data class DPoint(val x: Double, val y: Double)
    private enum class Axis { LEFT, RIGHT, TOP, BOTTOM }

    private fun clip(input: List<DPoint>, axis: Axis, value: Double): List<DPoint> {
        if (input.isEmpty()) return emptyList()
        val out = ArrayList<DPoint>(input.size + 2)
        var previous = input.last()
        var previousInside = inside(previous, axis, value)
        for (current in input) {
            val currentInside = inside(current, axis, value)
            if (currentInside != previousInside) {
                intersect(previous, current, axis, value)?.let(out::add)
            }
            if (currentInside) out.add(current)
            previous = current
            previousInside = currentInside
        }
        return out
    }

    private fun inside(point: DPoint, axis: Axis, value: Double): Boolean = when (axis) {
        Axis.LEFT -> point.x >= value
        Axis.RIGHT -> point.x <= value
        Axis.TOP -> point.y >= value
        Axis.BOTTOM -> point.y <= value
    }

    private fun intersect(a: DPoint, b: DPoint, axis: Axis, value: Double): DPoint? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return when (axis) {
            Axis.LEFT, Axis.RIGHT -> {
                if (abs(dx) < 1e-12) null
                else {
                    val t = ((value - a.x) / dx).coerceIn(0.0, 1.0)
                    DPoint(value, a.y + dy * t)
                }
            }
            Axis.TOP, Axis.BOTTOM -> {
                if (abs(dy) < 1e-12) null
                else {
                    val t = ((value - a.y) / dy).coerceIn(0.0, 1.0)
                    DPoint(a.x + dx * t, value)
                }
            }
        }
    }

    private fun polygonAreaF(points: List<PointF>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(sum) * 0.5
    }

    private fun polygonAreaD(points: List<DPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) * 0.5
    }
}

/**
 * 仅用于人工核对的第二条提示信息；与 V4/Presence 输出提示完全分离。
 */
internal object MarkedPortalInferenceOverlayBus {
    private data class Snapshot(val message: String, val expiresAtMs: Long)

    @Volatile
    private var latest: Snapshot? = null

    fun publish(message: String, durationMs: Long = 2800L) {
        latest = Snapshot(message, System.currentTimeMillis() + durationMs.coerceAtLeast(0L))
    }

    fun snapshot(nowMs: Long): String? {
        val value = latest ?: return null
        if (nowMs > value.expiresAtMs) {
            if (latest === value) latest = null
            return null
        }
        return value.message
    }

    fun clear() {
        latest = null
    }
}
