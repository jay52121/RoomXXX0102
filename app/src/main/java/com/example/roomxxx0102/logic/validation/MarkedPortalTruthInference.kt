package com.example.roomxxx0102.logic.validation

import android.graphics.PointF
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.logic.presence.PresenceRoomSnapshot
import kotlin.math.abs

/**
 * 人工事件的“门位真值候选”。
 *
 * 这里只允许使用人工事件时刻附近的原始 Pose 人体框/关键点和静态房门(Portal)几何；
 * 严禁读取 V4 的候选门、锁门结果、FSM、Ledger 或最终房间结果，避免循环验证。
 */
internal data class InferredPortalTruth(
    val portalRoomId: String,
    val portalName: String,
    val poseId: Int,
    val score: Double,
    /** intersection(person bbox, portal) / person bbox area：用户关心的“人体框有多少被门覆盖”。 */
    val personCoverage: Double,
    /** intersection(person bbox, portal) / portal area：只作为小权重辅助，不能主导小门判断。 */
    val portalCoverage: Double,
    /** 可靠 Pose 关键点中落入 Portal 的比例。 */
    val keypointCoverage: Double,
    val visibleKeypoints: Int,
    val insideKeypoints: Int,
    val sampleTimeMs: Long,
    val supportFrames: Int = 1,
    val postSupportFrames: Int = 0,
    val firstEvidenceTimeMs: Long = sampleTimeMs,
    val lastEvidenceTimeMs: Long = sampleTimeMs,
)

internal object MarkedPortalTruthInference {
    const val KEYPOINT_MIN_CONFIDENCE = 0.25f
    const val PERSON_WEIGHT = 0.45
    const val KEYPOINT_WEIGHT = 0.45
    const val PORTAL_WEIGHT = 0.10

    internal data class Region(
        val roomId: String,
        val roomName: String,
        val polygon: List<PointF>,
        val area: Double,
    )

    internal data class Coverage(
        val person: Double,
        val portal: Double,
    )

    /** 保留给静态 RoomConfig/离线工具使用，名称与运行态 Snapshot 版本刻意区分以避开 JVM 泛型擦除。 */
    fun regionsFromRoomConfigs(rooms: List<RoomConfig>): List<Region> = rooms
        .asSequence()
        .filter { !it.isSovereignTerritory && !it.isLivingBlindZone }
        .mapNotNull { room ->
            val polygon = room.boundaryPoints
            val area = polygonAreaF(polygon)
            if (polygon.size < 3 || area <= 1e-8) null
            else Region(room.id, room.name, polygon.map { PointF(it.x, it.y) }, area)
        }
        .toList()

    /** 正常播放逐帧使用的 Portal 几何。 */
    fun regions(rooms: List<PresenceRoomSnapshot>): List<Region> = rooms
        .asSequence()
        .filter { !it.isLivingRoom && !it.isBlindZone }
        .mapNotNull { room ->
            val polygon = room.polygon.map { PointF(it.x.toFloat(), it.y.toFloat()) }
            val area = polygonAreaF(polygon)
            if (polygon.size < 3 || area <= 1e-8) null
            else Region(room.roomId, room.roomName, polygon, area)
        }
        .toList()

    /**
     * 每一帧对每个 Portal 只返回一个最佳人体候选，便于跨帧累计 supportFrames。
     * 只要 bbox 有实际交集，或至少一个可靠关键点已经落入 Portal，就保留候选；
     * 这是诊断旁路，不直接驱动人数切换，因此宁可保留低分候选供后续比较，也不在这里硬阈值截断。
     */
    fun inferAll(
        poses: List<PoseResult>,
        regions: List<Region>,
        timeMs: Long,
    ): List<InferredPortalTruth> {
        val bestByPortal = linkedMapOf<String, InferredPortalTruth>()
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
            val reliableKeypoints = pose.keypoints.filter { point ->
                point.conf >= KEYPOINT_MIN_CONFIDENCE &&
                    point.x.isFinite() && point.y.isFinite()
            }

            for (region in regions) {
                val intersection = intersectionArea(region.polygon, left, top, right, bottom)
                val insideKeypoints = reliableKeypoints.count { point ->
                    pointInPolygon(point.x.toDouble(), point.y.toDouble(), region.polygon)
                }
                if (intersection <= 1e-10 && insideKeypoints == 0) continue

                val coverage = coverageFromAreas(intersection, bodyArea, region.area)
                val keypointCoverage = if (reliableKeypoints.isNotEmpty()) {
                    insideKeypoints.toDouble() / reliableKeypoints.size.toDouble()
                } else {
                    0.0
                }
                val score = score(
                    personCoverage = coverage.person,
                    portalCoverage = coverage.portal,
                    keypointCoverage = keypointCoverage,
                    hasReliableKeypoints = reliableKeypoints.isNotEmpty(),
                )
                val candidate = InferredPortalTruth(
                    portalRoomId = region.roomId,
                    portalName = region.roomName,
                    poseId = pose.id,
                    score = score,
                    personCoverage = coverage.person,
                    portalCoverage = coverage.portal,
                    keypointCoverage = keypointCoverage,
                    visibleKeypoints = reliableKeypoints.size,
                    insideKeypoints = insideKeypoints,
                    sampleTimeMs = timeMs,
                    postSupportFrames = 0,
                )
                val previous = bestByPortal[region.roomId]
                if (previous == null || candidate.score > previous.score) {
                    bestByPortal[region.roomId] = candidate
                }
            }
        }
        return bestByPortal.values.sortedByDescending { it.score }
    }

    fun infer(
        poses: List<PoseResult>,
        regions: List<Region>,
        timeMs: Long,
    ): InferredPortalTruth? = inferAll(poses, regions, timeMs).firstOrNull()

    /** 明确两个“重叠百分比”的分母，避免再把小门被填满误读成人已进入门。 */
    internal fun coverageFromAreas(
        intersectionArea: Double,
        personArea: Double,
        portalArea: Double,
    ): Coverage {
        val intersection = intersectionArea.coerceAtLeast(0.0)
        val person = if (personArea > 1e-12) (intersection / personArea).coerceIn(0.0, 1.0) else 0.0
        val portal = if (portalArea > 1e-12) (intersection / portalArea).coerceIn(0.0, 1.0) else 0.0
        return Coverage(person, portal)
    }

    /**
     * 人体覆盖与关键点吸收各占 45%，门填充仅占 10%。
     * 没有可靠关键点时，不让“缺失关键点=0分”惩罚候选，而退化为 80% 人体覆盖 + 20% 门填充。
     */
    internal fun score(
        personCoverage: Double,
        portalCoverage: Double,
        keypointCoverage: Double,
        hasReliableKeypoints: Boolean,
    ): Double {
        val person = personCoverage.coerceIn(0.0, 1.0)
        val portal = portalCoverage.coerceIn(0.0, 1.0)
        val keypoints = keypointCoverage.coerceIn(0.0, 1.0)
        return if (hasReliableKeypoints) {
            PERSON_WEIGHT * person + KEYPOINT_WEIGHT * keypoints + PORTAL_WEIGHT * portal
        } else {
            0.80 * person + 0.20 * portal
        }
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

    private fun pointInPolygon(x: Double, y: Double, polygon: List<PointF>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[j]
            val b = polygon[i]
            val ax = a.x.toDouble()
            val ay = a.y.toDouble()
            val bx = b.x.toDouble()
            val by = b.y.toDouble()
            if (pointOnSegment(x, y, ax, ay, bx, by)) return true
            val crosses = (by > y) != (ay > y)
            if (crosses) {
                val hitX = (ax - bx) * (y - by) / (ay - by) + bx
                if (x < hitX) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun pointOnSegment(
        x: Double,
        y: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Boolean {
        val cross = (x - ax) * (by - ay) - (y - ay) * (bx - ax)
        if (abs(cross) > 1e-9) return false
        return x >= minOf(ax, bx) - 1e-9 && x <= maxOf(ax, bx) + 1e-9 &&
            y >= minOf(ay, by) - 1e-9 && y <= maxOf(ay, by) + 1e-9
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

/** 仅用于人工核对的第二条提示信息；与 V4/Presence 输出提示完全分离。 */
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
