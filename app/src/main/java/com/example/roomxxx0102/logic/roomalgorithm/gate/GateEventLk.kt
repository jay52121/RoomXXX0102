package com.example.roomxxx0102.logic.roomalgorithm.gate

import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowEvidence
import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowGate
import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowPoint
import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowSample
import com.example.roomxxx0102.logic.roomalgorithm.flow.median
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Optical flow for V4.1. Images are already portal-local high-resolution crops.
 * No full-frame pyramid is ever built and no inactive gate owns LK state.
 */
internal class GateEventLk(private val cfg: GateConfig) {
    private data class Dot(val p: Point, val cell: Int, val age: Int)
    private data class State(
        var owner: Int,
        var time: Long,
        var dots: List<Dot> = emptyList(),
        var seeded: Long = -10000L,
    )

    private val states = linkedMapOf<String, State>()
    var pointCount = 0; private set
    var budgetExceeded = false; private set

    fun reset() {
        states.clear()
        pointCount = 0
        budgetExceeded = false
    }

    fun dropGate(gateId: String) {
        states.remove(gateId)
        pointCount = states.values.sumOf { it.dots.size }
    }

    fun update(
        gate: FlowGate,
        owner: Int,
        previous: Mat?,
        current: Mat,
        seedMask: Mat,
        crop: Rect,
        fullWidth: Int,
        fullHeight: Int,
        timeMs: Long,
        startedNs: Long,
    ): FlowEvidence {
        budgetExceeded = false
        if (elapsed(startedNs) >= cfg.optionalBudgetMs) {
            budgetExceeded = true
            return FlowEvidence(owner, imageAvailable = true, reliable = false, reason = "LK_BUDGET_SKIP")
        }
        val state = states.getOrPut(gate.id) { State(owner, timeMs) }
        if (state.owner != owner || timeMs <= state.time || timeMs - state.time > cfg.maxGapMs) {
            state.owner = owner
            state.dots = emptyList()
            state.seeded = -10000L
        }
        var samples = emptyList<FlowSample>()
        var delta = FlowPoint(0.0, 0.0)
        var reliable = false
        if (previous != null && state.dots.isNotEmpty()) {
            val starts = state.dots.map { it.p }
            val ends = track(previous, current, starts)
            val dx = median(ends.mapIndexedNotNull { i, q -> q?.let { it.x - starts[i].x } })
            val dy = median(ends.mapIndexedNotNull { i, q -> q?.let { it.y - starts[i].y } })
            val residuals = ends.mapIndexedNotNull { i, q -> q?.let { hypot(it.x-starts[i].x-dx, it.y-starts[i].y-dy) } }
            val cutoff = max(1.5, 3.0 * median(residuals))
            val kept = mutableListOf<Dot>()
            val out = mutableListOf<FlowSample>()
            for (i in state.dots.indices) {
                val d = state.dots[i]
                val q = ends.getOrNull(i)?.takeIf { hypot(it.x-d.p.x-dx, it.y-d.p.y-dy) <= cutoff }
                val before = global(d.p, crop, fullWidth, fullHeight)
                if (q != null) {
                    val after = global(q, crop, fullWidth, fullHeight)
                    if (gate.containsBody(after)) {
                        kept += Dot(q, d.cell, d.age + 1)
                        out += FlowSample(before, after, d.cell, d.age + 1)
                    }
                }
            }
            reliable = kept.count { it.age >= 2 } >= 10 && kept.map { it.cell }.distinct().size >= 4 &&
                kept.size >= state.dots.size * 0.38 && hypot(dx,dy) < min(current.cols(), current.rows()) * 0.35
            state.dots = kept
            samples = out
            delta = FlowPoint(dx / fullWidth, dy / fullHeight)
        }

        if ((state.dots.size < cfg.points * 0.65 || timeMs - state.seeded >= 300) && elapsed(startedNs) < cfg.optionalBudgetMs) {
            seed(current, seedMask, state)
            state.seeded = timeMs
        } else if (elapsed(startedNs) >= cfg.optionalBudgetMs) {
            budgetExceeded = true
        }
        state.time = timeMs
        pointCount = states.values.sumOf { it.dots.size }
        return FlowEvidence(owner, delta, samples, reliable, imageAvailable = true,
            frameHealthy = true, reason = "PORTAL_LOCAL_HIGH_RES_LK_FB")
    }

    /**
     * Optional late-exit refinement. The gate is already selected geometrically; reverse LK only
     * verifies that several current body textures existed deeper in that same aperture in pre-roll.
     */
    fun verifyReverseEmergence(
        gate: FlowGate,
        current: Mat,
        currentBodyMask: Mat,
        history: List<Pair<Long, Mat>>,
        crop: Rect,
        fullWidth: Int,
        fullHeight: Int,
        startedNs: Long,
    ): Boolean {
        if (history.size < 3 || elapsed(startedNs) >= cfg.optionalBudgetMs) return false
        val corners = MatOfPoint()
        try {
            Imgproc.goodFeaturesToTrack(current, corners, min(64, cfg.points), 0.015, 3.0, currentBodyMask)
            var points = corners.toArray().toList()
            if (points.size < 10) return false
            var later = current
            var agreeingPairs = 0
            for ((_, older) in history.takeLast(4).asReversed()) {
                if (elapsed(startedNs) >= cfg.optionalBudgetMs) break
                val tracked = track(later, older, points)
                val kept = tracked.indices.filter { tracked[it] != null }
                if (kept.size < 8) break
                val oldPoints = kept.map { tracked[it]!! }
                val newPoints = kept.map { points[it] }
                var agree = 0
                for (i in oldPoints.indices) {
                    val oldGlobal = global(oldPoints[i], crop, fullWidth, fullHeight)
                    val newGlobal = global(newPoints[i], crop, fullWidth, fullHeight)
                    if (gate.containsBody(oldGlobal) && gate.side(newGlobal) > gate.side(oldGlobal) + 0.004) agree++
                }
                if (agree >= max(8, (oldPoints.size * 0.60).toInt())) agreeingPairs++
                points = oldPoints
                later = older
            }
            return agreeingPairs >= 2
        } finally {
            corners.release()
        }
    }

    private fun seed(current: Mat, seedMask: Mat, state: State) {
        val corners = MatOfPoint()
        val mask = seedMask.clone()
        try {
            state.dots.forEach { Imgproc.circle(mask, it.p, 3, Scalar(0.0), -1) }
            Imgproc.goodFeaturesToTrack(current, corners, cfg.points * 2, 0.01, 2.5, mask, 3, false, 0.04)
            val counts = IntArray(96)
            state.dots.forEach { if (it.cell in counts.indices) counts[it.cell]++ }
            val out = state.dots.toMutableList()
            val quota = max(2, (cfg.points + 31) / 32)
            for (q in corners.toArray()) {
                if (out.size >= cfg.points) break
                val cell = cell(q, current.cols(), current.rows())
                if (counts[cell] >= quota) continue
                counts[cell]++
                out += Dot(q, cell, 0)
            }
            state.dots = out
        } finally {
            corners.release(); mask.release()
        }
    }

    private fun track(from: Mat, to: Mat, points: List<Point>): List<Point?> {
        if (points.isEmpty()) return emptyList()
        val a=MatOfPoint2f(*points.toTypedArray()); val b=MatOfPoint2f(); val back=MatOfPoint2f()
        val status=MatOfByte(); val reverseStatus=MatOfByte(); val errors=MatOfFloat(); val reverseErrors=MatOfFloat()
        try {
            val criteria=TermCriteria(TermCriteria.COUNT+TermCriteria.EPS, 14, .03)
            Video.calcOpticalFlowPyrLK(from,to,a,b,status,errors,Size(15.0,15.0),cfg.pyramidLevel,criteria,0,1e-4)
            Video.calcOpticalFlowPyrLK(to,from,b,back,reverseStatus,reverseErrors,Size(15.0,15.0),cfg.pyramidLevel,criteria,0,1e-4)
            val p=b.toArray(); val r=back.toArray(); val s=status.toArray(); val rs=reverseStatus.toArray(); val e=errors.toArray()
            return points.indices.map { i ->
                val q=p.getOrNull(i); val v=r.getOrNull(i)
                if(q==null || v==null || s.getOrElse(i){0}==0.toByte() || rs.getOrElse(i){0}==0.toByte() ||
                    !q.x.isFinite() || !q.y.isFinite() || q.x<1 || q.y<1 || q.x>=to.cols()-1 || q.y>=to.rows()-1 ||
                    e.getOrElse(i){Float.MAX_VALUE}>22 || hypot(v.x-points[i].x,v.y-points[i].y)>cfg.fbError) null else q
            }
        } finally { listOf(a,b,back,status,reverseStatus,errors,reverseErrors).forEach { it.release() } }
    }

    private fun cell(p: Point, w: Int, h: Int): Int {
        val x = (p.x * 8 / w.coerceAtLeast(1)).toInt().coerceIn(0,7)
        val y = (p.y * 12 / h.coerceAtLeast(1)).toInt().coerceIn(0,11)
        return y * 8 + x
    }
    private fun global(p: Point, crop: Rect, w: Int, h: Int) = FlowPoint((p.x+crop.x)/w, (p.y+crop.y)/h)
    private fun elapsed(startedNs: Long)=(System.nanoTime()-startedNs)/1_000_000L
}
