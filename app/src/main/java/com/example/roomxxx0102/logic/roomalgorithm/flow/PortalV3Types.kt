package com.example.roomxxx0102.logic.roomalgorithm.flow

import kotlin.math.*

internal data class FlowPoint(val x: Double, val y: Double) {
    operator fun plus(p: FlowPoint) = FlowPoint(x + p.x, y + p.y)
    operator fun minus(p: FlowPoint) = FlowPoint(x - p.x, y - p.y)
    operator fun times(k: Double) = FlowPoint(x * k, y * k)
    fun distance(p: FlowPoint, aspect: Double = 1.0) = hypot((x - p.x) * aspect, y - p.y)
    fun finite() = x.isFinite() && y.isFinite()
}
internal data class FlowBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val center get() = FlowPoint((left + right) / 2, (top + bottom) / 2)
    val foot get() = FlowPoint((left + right) / 2, bottom)
    fun contains(p: FlowPoint, margin: Double = 0.0) = p.x >= left - margin && p.x <= right + margin && p.y >= top - margin && p.y <= bottom + margin
    fun moved(d: FlowPoint) = FlowBox(left + d.x, top + d.y, right + d.x, bottom + d.y)
    fun iou(b: FlowBox): Double {
        val area = max(0.0, min(right, b.right) - max(left, b.left)) * max(0.0, min(bottom, b.bottom) - max(top, b.top))
        return area / (width * height + b.width * b.height - area).coerceAtLeast(1e-9)
    }
}
internal data class FlowJoint(val point: FlowPoint, val score: Double)
internal data class FlowDetection(val id: Int, val box: FlowBox, val joints: List<FlowJoint>, val score: Double, val locked: Boolean, val shielded: Boolean, val originGate: String? = null) {
    fun joint(i: Int, threshold: Double = 0.55): FlowPoint? = joints.getOrNull(i)?.takeIf {
        it.score >= threshold && it.point.finite() && box.contains(it.point, 0.025) && !(it.point.x == 0.0 && it.point.y == 0.0)
    }?.point
    fun bodyValid(): Boolean {
        if (!score.isFinite() || score < 0.5 || shielded || box.height !in 0.035..1.4 || box.width !in 0.008..0.9) return false
        val ls = joint(5, 0.7) ?: return false
        val rs = joint(6, 0.7) ?: return false
        val upper = (0..10).count { joint(it, 0.45) != null }
        val hip = listOfNotNull(joint(11), joint(12))
        return upper >= 5 && ls.distance(rs) > 0.004 && hip.isNotEmpty() && hip.all { it.y > min(ls.y, rs.y) }
    }
}
internal data class FlowGround(val point: FlowPoint, val strong: Boolean, val uncertainty: Double, val source: String)
internal data class FlowSample(val previous: FlowPoint, val current: FlowPoint?, val cell: Int, val age: Int, val terminal: Boolean = false)
internal data class FlowEvidence(
    val id: Int,
    val delta: FlowPoint = FlowPoint(0.0, 0.0),
    val samples: List<FlowSample> = emptyList(),
    val reliable: Boolean = false,
    val imageAvailable: Boolean = true,
    val frameHealthy: Boolean = true,
    val backgroundReturn: Boolean = false,
    val reason: String = "",
) {
    val live get() = samples.filter { it.current != null && it.age >= 2 }
    val cells get() = live.map { it.cell }.distinct().size
    val moved get() = reliable && delta.distance(FlowPoint(0.0, 0.0)) > 0.0015
}
internal data class FlowGate(
    val id: String, val room: String, val a: FlowPoint, val b: FlowPoint,
    val aperture: List<FlowPoint>, val insideSign: Double, val aspect: Double,
    val isExterior: Boolean = false, val isBlind: Boolean = false,
) {
    private val dx get() = (b.x - a.x) * aspect
    private val dy get() = b.y - a.y
    val length get() = hypot(dx, dy).coerceAtLeast(1e-8)
    // Signed distance in image-height units. Positive is the local living-room side.
    fun side(p: FlowPoint) = insideSign * (dx * (p.y - a.y) - dy * (p.x - a.x) * aspect) / length
    fun along(p: FlowPoint) = ((p.x - a.x) * aspect * dx + (p.y - a.y) * dy) / (length * length)
    fun distance(p: FlowPoint): Double {
        val t = along(p).coerceIn(0.0, 1.0)
        return p.distance(a + (b - a) * t, aspect)
    }
    fun intersects(from: FlowPoint, to: FlowPoint): Boolean {
        val s0 = side(from); val s1 = side(to)
        if (s0 * s1 > 0.0 || abs(s0 - s1) < 1e-9) return false
        val hit = from + (to - from) * (s0 / (s0 - s1))
        return along(hit) in -0.04..1.04
    }
    fun containsBody(p: FlowPoint) = inPolygon(p, aperture)
    companion object {
        fun create(id: String, room: String, a: FlowPoint, b: FlowPoint, aperture: List<FlowPoint>, living: List<FlowPoint>, aspect: Double, exterior: Boolean, blind: Boolean): FlowGate? {
            val dx = (b.x - a.x) * aspect; val dy = b.y - a.y; val len = hypot(dx, dy)
            if (len < 0.002 || living.size < 3) return null
            val mid = (a + b) * 0.5
            // Test local sides, not the centroid of a concave room.
            for (eps in listOf(0.001, 0.003, 0.008)) {
                val n = FlowPoint(-dy / len / aspect * eps, dx / len * eps)
                val plus = inPolygon(mid + n, living); val minus = inPolygon(mid - n, living)
                if (plus != minus) return FlowGate(id, room, a, b, aperture, if (plus) 1.0 else -1.0, aspect, exterior, blind)
            }
            return null // An invalid calibration is not an excuse to guess an inward normal.
        }
    }
}
internal fun inPolygon(p: FlowPoint, polygon: List<FlowPoint>): Boolean {
    if (polygon.size < 3 || !p.finite()) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]; val b = polygon[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}
internal fun median(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sorted()[values.size / 2]

/** Confidence-aware feet; a changing/truncated bbox never becomes a new ground observation. */
internal class FlowGroundEstimator {
    private var referenceHeight: Double? = null
    private var previousBox: FlowBox? = null
    private var stableFullFrames = 0
    fun measure(d: FlowDetection, roi: FlowBox?): FlowGround? {
        if (d.shielded || d.box.height <= 0.0) return null
        val bottomClipped = d.box.bottom >= 0.99 || (roi != null && d.box.bottom >= roi.bottom - 0.008)
        val hips = listOfNotNull(d.joint(11), d.joint(12))
        val hipsY = hips.maxOfOrNull { it.y } ?: d.box.top + d.box.height * 0.35
        val feet = listOfNotNull(d.joint(15, 0.60), d.joint(16, 0.60)).filter {
            it.y > hipsY && it.y >= d.box.top + d.box.height * 0.65 && abs(it.y - d.box.bottom) <= d.box.height * 0.22
        }
        val box = d.box
        val prev = previousBox
        previousBox = box
        if (feet.isNotEmpty() && !bottomClipped) {
            val point = FlowPoint(feet.map { it.x }.average(), feet.map { it.y }.average())
            val full = feet.size == 2 && d.bodyValid() && d.joint(13) != null && d.joint(14) != null
            if (full) {
                referenceHeight = referenceHeight?.let { 0.9 * it + 0.1 * box.height } ?: box.height
                stableFullFrames++
            }
            return FlowGround(point, feet.size == 2, if (feet.size == 2) 0.004 else 0.012, if (feet.size == 2) "FEET" else "ONE_FOOT")
        }
        val ref = referenceHeight ?: return null
        val lowerVisible = d.joint(11) != null && d.joint(12) != null && (d.joint(13) != null || d.joint(14) != null)
        val heightOk = box.height / ref in 0.85..1.18
        val stepOk = prev != null && box.height / prev.height.coerceAtLeast(0.001) in 0.88..1.14
        return if (!bottomClipped && stableFullFrames >= 3 && heightOk && stepOk && lowerVisible && d.bodyValid()) {
            FlowGround(box.foot, true, 0.012, "CALIBRATED_BOX_BOTTOM")
        } else null
    }
}
internal data class FlowEvent(val person: Int, val track: Int, val from: String, val to: String, val gate: String, val timeMs: Long, val inferred: Boolean)
internal data class FlowPersonView(val person: Int, val track: Int, val room: String?, val ground: FlowGround?, val box: FlowBox, val status: String, val candidates: Set<String>, val accepted: Boolean)
internal data class FlowDecision(val counts: Map<String, Int>, val lower: Map<String, Int>, val upper: Map<String, Int>, val unknown: Int, val events: List<FlowEvent>, val people: List<FlowPersonView>, val notes: List<String>)
