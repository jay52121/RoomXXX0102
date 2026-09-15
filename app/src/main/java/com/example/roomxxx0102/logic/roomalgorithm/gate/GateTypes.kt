package com.example.roomxxx0102.logic.roomalgorithm.gate

import kotlin.math.*

/** Pure data/math shared by the three sensors. An aperture is NOT a ground/depth plane. */
internal data class GP(val x: Double, val y: Double) {
    operator fun plus(p: GP) = GP(x + p.x, y + p.y)
    operator fun minus(p: GP) = GP(x - p.x, y - p.y)
    operator fun times(s: Double) = GP(x * s, y * s)
    fun norm(aspect: Double = 1.0) = hypot(x * aspect, y)
    fun distance(p: GP, aspect: Double = 1.0) = (this - p).norm(aspect)
    fun finite() = x.isFinite() && y.isFinite()
}
internal data class GB(val l: Double, val t: Double, val r: Double, val b: Double) {
    val w get() = r - l
    val h get() = b - t
    val center get() = GP((l + r) / 2, (t + b) / 2)
    val foot get() = GP((l + r) / 2, b)
    fun contains(p: GP, margin: Double = 0.0) = p.x >= l - margin && p.x <= r + margin && p.y >= t - margin && p.y <= b + margin
    fun moved(d: GP) = GB(l + d.x, t + d.y, r + d.x, b + d.y)
    fun intersection(o: GB): Double = max(0.0, min(r, o.r) - max(l, o.l)) * max(0.0, min(b, o.b) - max(t, o.t))
    fun iou(o: GB): Double { val a = intersection(o); return a / (w * h + o.w * o.h - a).coerceAtLeast(1e-9) }
    fun valid() = center.finite() && w > 0.0 && h > 0.0 && w <= 1.5 && h <= 1.5
}
internal data class GJoint(val p: GP, val confidence: Double)
internal data class GDetection(val track: Int, val box: GB, val joints: List<GJoint>, val score: Double, val locked: Boolean, val shielded: Boolean) {
    fun joint(i: Int, min: Double = 0.55): GP? = joints.getOrNull(i)?.takeIf {
        it.confidence.isFinite() && it.confidence >= min && it.p.finite() && box.contains(it.p, 0.02) && !(it.p.x == 0.0 && it.p.y == 0.0)
    }?.p
    fun credible(minScore: Double = 0.5): Boolean {
        if (shielded || !box.valid() || !score.isFinite() || score < minScore) return false
        val a = joint(5, 0.65) ?: return false
        val b = joint(6, 0.65) ?: return false
        val hips = listOfNotNull(joint(11), joint(12))
        return a.distance(b) > 0.003 && (0..10).count { joint(it, 0.45) != null } >= 5 &&
            hips.isNotEmpty() && hips.all { it.y > min(a.y, b.y) }
    }
}
internal data class Ground(val p: GP, val measured: Boolean, val error: Double, val source: String)
internal class GroundEstimator(private val confidence: Double) {
    private var fullHeight: Double? = null
    private var previous: GB? = null
    private var fullFrames = 0
    fun measure(d: GDetection, roi: GB?): Ground? {
        if (d.shielded || !d.box.valid() || !d.score.isFinite() || d.score < 0.3) return null
        val box = d.box
        val before = previous
        previous = box
        val clipped = box.b >= 0.995 || (roi != null && box.b >= roi.b - 0.008)
        val hipY = listOfNotNull(d.joint(11), d.joint(12)).maxOfOrNull { it.y } ?: box.t + box.h * 0.4
        val feet = listOfNotNull(d.joint(15, confidence), d.joint(16, confidence)).filter {
            it.y > hipY && it.y >= box.t + box.h * 0.65 && abs(it.y - box.b) <= box.h * 0.22
        }
        if (!clipped && feet.size == 2 && abs(feet[0].y - feet[1].y) <= box.h * 0.22) {
            if (d.credible() && d.joint(13) != null && d.joint(14) != null) {
                fullHeight = fullHeight?.let { it * 0.9 + box.h * 0.1 } ?: box.h
                fullFrames++
            }
            return Ground(GP(feet.map { it.x }.average(), feet.map { it.y }.average()), true, 0.004, "FEET")
        }
        if (!clipped && feet.size == 1) return Ground(feet[0], false, 0.014, "ONE_FOOT")
        val reference = fullHeight ?: return null
        val lowerVisible = d.joint(11) != null && d.joint(12) != null && (d.joint(13) != null || d.joint(14) != null)
        return if (!clipped && fullFrames >= 3 && before != null && lowerVisible && d.credible() &&
            box.h / reference in 0.85..1.18 && box.h / before.h.coerceAtLeast(1e-5) in 0.9..1.12) {
            Ground(box.foot, true, 0.012, "STABLE_FULL_BODY_BOTTOM")
        } else null
    }
}
internal data class Gate(val id: String, val room: String, val a: GP, val b: GP, val aperture: List<GP>, val sign: Double,
                         val aspect: Double, val exterior: Boolean, val blind: Boolean) {
    private val dx get() = (b.x - a.x) * aspect
    private val dy get() = b.y - a.y
    val length get() = hypot(dx, dy).coerceAtLeast(1e-9)
    fun side(p: GP) = sign * (dx * (p.y - a.y) - dy * (p.x - a.x) * aspect) / length
    fun along(p: GP) = ((p.x - a.x) * aspect * dx + (p.y - a.y) * dy) / (length * length)
    fun distance(p: GP): Double = p.distance(a + (b - a) * along(p).coerceIn(0.0, 1.0), aspect)
    fun intersection(from: GP, to: GP): GP? {
        val s0 = side(from); val s1 = side(to)
        if (s0 * s1 > 0.0 || abs(s0 - s1) < 1e-9) return null
        val hit = from + (to - from) * (s0 / (s0 - s1))
        return hit.takeIf { along(it) in 0.0..1.0 }
    }
    fun contains(p: GP) = polygonContains(p, aperture)
    fun bounds(): GB? = aperture.takeIf { it.size >= 3 }?.let { ps -> GB(ps.minOf { it.x }, ps.minOf { it.y }, ps.maxOf { it.x }, ps.maxOf { it.y }) }
    companion object {
        fun create(id: String, room: String, a: GP, b: GP, aperture: List<GP>, living: List<GP>, aspect: Double,
                   exterior: Boolean = false, blind: Boolean = false): Gate? {
            if (!a.finite() || !b.finite() || !aspect.isFinite() || aspect <= 0.0 || living.size < 3) return null
            val d = b - a; val length = d.norm(aspect)
            if (length < 0.002) return null
            val mid = (a + b) * 0.5
            for (epsilon in listOf(0.001, 0.003, 0.008)) {
                val normal = GP(-d.y / length / aspect * epsilon, d.x * aspect / length * epsilon)
                val plus = polygonContains(mid + normal, living)
                val minus = polygonContains(mid - normal, living)
                if (plus != minus) return Gate(id, room, a, b, aperture, if (plus) 1.0 else -1.0, aspect, exterior, blind)
            }
            return null
        }
    }
}
internal fun polygonContains(p: GP, points: List<GP>): Boolean {
    if (points.size < 3 || !p.finite()) return false
    var inside = false; var j = points.lastIndex
    for (i in points.indices) {
        val a = points[i]; val b = points[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}
internal fun med(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs.sorted()[xs.size / 2]

internal enum class GateMethod(val id: String, val title: String) {
    DIFFERENCE("gate_diff", "\u95e8\u53e3\u5dee\u5206\uff08\u9ad8\u5e27\u7387\uff09"),
    OPTICAL_FLOW("gate_flow", "\u95e8\u53e3\u5149\u6d41\uff08\u7cbe\u5ea6\u4f18\u5148\uff09"),
    MOG2("gate_mog2", "OpenCV MOG2\uff08\u81ea\u9002\u5e94\u524d\u666f\uff09");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } }
}
internal data class GateParams(
    val captureEdge: Int = 1280,
    val sampleHz: Int = 20,
    val imageEdge: Int = 512,
    val frameGapMs: Long = 300,
    val differenceThreshold: Int = 22,
    val warmupMs: Long = 700,
    val backgroundRate: Double = 0.012,
    val contactHeight: Double = 0.10,
    val crossedHoldMs: Long = 120,
    val vanishedHoldMs: Long = 240,
    val coastMs: Long = 1200,
    val minOwnedCells: Int = 4,
    val restoredFraction: Double = 0.88,
    val minForegroundPixels: Int = 10,
    val footConfidence: Double = 0.60,
    val admissionMs: Long = 200,
    val pointsPerPerson: Int = 192,
    val maxPoints: Int = 384,
    val pyramidLevel: Int = 2,
    val lkWindow: Int = 21,
    val fbError: Double = 1.5,
    val workBudgetMs: Long = 24,
    val mogHistory: Int = 120,
    val mogVariance: Double = 16.0,
) {
    fun validated() = copy(captureEdge = captureEdge.coerceIn(640, 2560), sampleHz = sampleHz.coerceIn(5, 30),
        imageEdge = imageEdge.coerceIn(256, 960), frameGapMs = frameGapMs.coerceIn(100, 600),
        differenceThreshold = differenceThreshold.coerceIn(8, 60), warmupMs = warmupMs.coerceIn(300, 4000),
        backgroundRate = backgroundRate.takeIf { it.isFinite() }?.coerceIn(0.001, 0.1) ?: 0.012,
        contactHeight = contactHeight.takeIf { it.isFinite() }?.coerceIn(0.04, 0.18) ?: 0.10,
        crossedHoldMs = crossedHoldMs.coerceIn(60, 600), vanishedHoldMs = vanishedHoldMs.coerceIn(120, 800),
        coastMs = coastMs.coerceIn(500, 2000), minOwnedCells = minOwnedCells.coerceIn(3, 12),
        restoredFraction = restoredFraction.takeIf { it.isFinite() }?.coerceIn(0.75, 0.98) ?: 0.88,
        minForegroundPixels = minForegroundPixels.coerceIn(4, 80), footConfidence = footConfidence.takeIf { it.isFinite() }?.coerceIn(0.45, 0.9) ?: 0.60,
        admissionMs = admissionMs.coerceIn(120, 1000), pointsPerPerson = pointsPerPerson.coerceIn(48, 320),
        maxPoints = maxPoints.coerceIn(96, 640), pyramidLevel = pyramidLevel.coerceIn(1, 3),
        lkWindow = (lkWindow.coerceIn(11, 31) or 1), fbError = fbError.takeIf { it.isFinite() }?.coerceIn(0.5, 3.0) ?: 1.5,
        workBudgetMs = workBudgetMs.coerceIn(8, 60), mogHistory = mogHistory.coerceIn(30, 600),
        mogVariance = mogVariance.takeIf { it.isFinite() }?.coerceIn(8.0, 64.0) ?: 16.0)
    companion object {
        fun defaults(method: GateMethod) = if (method == GateMethod.OPTICAL_FLOW) GateParams(imageEdge = 640) else GateParams()
    }
}

internal data class GateVisual(
    val gate: String, val track: Int, val time: Long,
    val contact: Boolean = false, val backgroundReady: Boolean = false, val valid: Boolean = false,
    val ownedCells: Int = 0, val visibleFraction: Double = 1.0, val restoredFraction: Double = 0.0,
    val backgroundRestored: Boolean = false, val motion: GP? = null, val motionCells: Int = 0,
    val motionVerified: Boolean = false, val ambiguous: Boolean = false, val origin: Boolean = false,
    val reason: String = "", val marks: List<GP> = emptyList(), val trails: List<Pair<GP, GP>> = emptyList(),
)
internal data class GateVisualBatch(val time: Long, val evidence: List<GateVisual> = emptyList(), val healthy: Boolean = true,
                                    val costMs: Double = 0.0, val points: Int = 0, val active: Int = 0,
                                    val skipped: Int = 0, val message: String = "", val gapMs: Long = 0)
internal data class GateEvent(val person: Int, val track: Int, val from: String, val to: String, val gate: String, val time: Long, val inferred: Boolean)
internal data class GatePerson(val number: Int, val track: Int, val room: String?, val ground: Ground?, val box: GB,
                               val status: String, val possible: Set<String>, val accepted: Boolean)
internal data class GateDecision(val counts: Map<String, Int>, val lower: Map<String, Int>, val upper: Map<String, Int>,
                                 val people: List<GatePerson>, val events: List<GateEvent>, val notes: List<String>, val unknown: Int)
