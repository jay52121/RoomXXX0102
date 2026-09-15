package com.example.roomxxx0102.logic.roomalgorithm.flow

import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.*

/** Local KLT with spatial quotas, ownership masks, forward/backward checks and background witnesses. */
internal class PortalV3Vision(private val gates: List<FlowGate>) : AutoCloseable {
    private data class Dot(var point: Point, val cell: Int, var age: Int = 0)
    private data class Cloud(val id: Int, var box: FlowBox, val dots: MutableList<Dot>, var seededAt: Long, var movedPx: Double = 0.0)
    private var ready: Boolean? = null
    private var previous: Mat? = null
    private var previousFrame: PortalFrameHub.GrayFrame? = null
    private var background = FloatArray(0)
    private var bgAge = IntArray(0)
    private val clouds = linkedMapOf<Int, Cloud>()
    private var detections = emptyList<FlowDetection>()
    var healthy = true
        private set
    var cameraMoved = false
        private set
    var unavailable = false
        private set
    var lastCostMs = 0L
        private set

    private fun initCv(): Boolean {
        ready?.let { return it }
        val ok = try { OpenCVLoader.initLocal() } catch (e: LinkageError) { false } catch (e: RuntimeException) { false }
        ready = ok; unavailable = !ok
        if (!ok) Log.e("PortalV3Flow", "OpenCV unavailable; no inferred disappearance events")
        return ok
    }
    fun advance(frame: PortalFrameHub.GrayFrame): Map<Int, FlowEvidence> {
        if (!initCv()) return emptyMap()
        val started = System.nanoTime()
        val gray = Mat(frame.height, frame.width, CvType.CV_8UC1)
        gray.put(0, 0, frame.pixels)
        val oldFrame = previousFrame
        val old = previous
        healthy = !cameraMoved
        val results = linkedMapOf<Int, FlowEvidence>()
        try {
            val discontinuity = oldFrame == null || old == null || oldFrame.width != frame.width || oldFrame.height != frame.height ||
                frame.stamp.timestampMs - oldFrame.stamp.timestampMs !in 1..400 || oldFrame.stamp.epoch != frame.stamp.epoch
            if (discontinuity) {
                clouds.clear(); background = FloatArray(frame.pixels.size) { (frame.pixels[it].toInt() and 255).toFloat() }; bgAge = IntArray(frame.pixels.size)
                healthy = oldFrame == null && !cameraMoved
                return emptyMap()
            }
            healthy = !cameraMoved && checkScene(old!!, gray, frame, oldFrame!!)
            for ((id, cloud) in clouds.toMap()) {
                if (frame.stamp.timestampMs - cloud.seededAt > 1600 || cloud.dots.isEmpty()) { clouds.remove(id); continue }
                if (!healthy) { cloud.dots.clear(); continue }
                val starts = cloud.dots.map { it.point }
                val motion = flow(old, gray, starts)
                val dxs = motion.mapIndexedNotNull { i, p -> p?.let { it.x - starts[i].x } }
                val dys = motion.mapIndexedNotNull { i, p -> p?.let { it.y - starts[i].y } }
                val dx = median(dxs); val dy = median(dys)
                val magnitude = hypot(dx, dy)
                val deviation = motion.mapIndexedNotNull { i, p -> p?.let { hypot(it.x - starts[i].x - dx, it.y - starts[i].y - dy) } }
                val cutoff = max(2.5, 3.0 * median(deviation))
                val samples = mutableListOf<FlowSample>()
                val survivors = mutableListOf<Dot>()
                var restoredCells = mutableSetOf<Int>()
                for (i in cloud.dots.indices) {
                    val dot = cloud.dots[i]
                    val next = motion[i]
                    val prevNorm = normalized(dot.point, frame)
                    val inOther = detections.any { it.id != id && it.box.contains(prevNorm) }
                    val coherent = next != null && hypot(next.x - dot.point.x - dx, next.y - dot.point.y - dy) <= cutoff &&
                        !(magnitude > 1.0 && hypot(next.x - dot.point.x, next.y - dot.point.y) < 0.15)
                    if (coherent && !inOther) {
                        val q = next!!
                        samples += FlowSample(prevNorm, normalized(q, frame), dot.cell, dot.age + 1)
                        survivors += Dot(q, dot.cell, dot.age + 1)
                    } else {
                        // A failed corner has no measured new location. Keep its last verified location only.
                        val terminal = !inOther && next == null && dot.age >= 3 && cloud.movedPx > 0.45 &&
                            restoredBackground(dot.point, oldFrame, frame)
                        samples += FlowSample(prevNorm, null, dot.cell, dot.age, terminal)
                        if (terminal) restoredCells.add(dot.cell)
                    }
                }
                val cells = survivors.filter { it.age >= 2 }.map { it.cell }.distinct().size
                val ratio = survivors.size.toDouble() / cloud.dots.size.coerceAtLeast(1)
                val reliable = cells >= 3 && ratio >= 0.35 && magnitude < min(frame.width, frame.height) * 0.13
                results[id] = FlowEvidence(id, FlowPoint(dx / frame.width, dy / frame.height), samples, reliable,
                    frameHealthy = healthy, backgroundReturn = restoredCells.size >= 5, reason = if (reliable) "KLT_FB" else "LOW_VISUAL_SUPPORT")
                cloud.dots.clear(); cloud.dots.addAll(survivors)
                if (reliable) { cloud.box = cloud.box.moved(FlowPoint(dx / frame.width, dy / frame.height)); cloud.movedPx = magnitude }
            }
            updateBackground(frame)
            return results
        } catch (e: Exception) {
            healthy = false; clouds.clear()
            Log.w("PortalV3Flow", "flow failed; not a disappearance", e)
            return emptyMap()
        } finally {
            previous?.release(); previous = gray; previousFrame = frame
            lastCostMs = (System.nanoTime() - started) / 1000000
        }
    }

    private fun flow(from: Mat, to: Mat, points: List<Point>): List<Point?> {
        if (points.isEmpty()) return emptyList()
        val p0 = MatOfPoint2f(*points.toTypedArray()); val p1 = MatOfPoint2f(); val back = MatOfPoint2f()
        val st = MatOfByte(); val sb = MatOfByte(); val er = MatOfFloat(); val eb = MatOfFloat()
        try {
            val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 20, 0.03)
            Video.calcOpticalFlowPyrLK(from, to, p0, p1, st, er, Size(21.0, 21.0), 3, criteria, 0, 1e-4)
            val first = p1.toArray(); val status = st.toArray(); val error = er.toArray()
            if (first.size != points.size) return List(points.size) { null }
            Video.calcOpticalFlowPyrLK(to, from, p1, back, sb, eb, Size(21.0, 21.0), 3, criteria, 0, 1e-4)
            val second = back.toArray(); val statusBack = sb.toArray()
            return points.indices.map { i ->
                val q = first[i]
                if (i >= second.size || status.getOrElse(i) { 0 } == 0.toByte() || statusBack.getOrElse(i) { 0 } == 0.toByte() ||
                    !q.x.isFinite() || !q.y.isFinite() || q.x < 2 || q.y < 2 || q.x >= to.cols() - 2 || q.y >= to.rows() - 2 ||
                    error.getOrElse(i) { Float.MAX_VALUE } > 22f || hypot(second[i].x - points[i].x, second[i].y - points[i].y) > 1.5) null else q
            }
        } finally { listOf(p0, p1, back, st, sb, er, eb).forEach { it.release() } }
    }

    fun observe(current: List<FlowDetection>, timeMs: Long, accepted: Set<Int>) {
        detections = current
        val gray = previous ?: return
        val frame = previousFrame ?: return
        if (!healthy || cameraMoved) return
        val candidates = current.filter { it.locked && it.bodyValid() && !it.shielded }.sortedBy {
            gates.minOfOrNull { gate -> gate.distance(it.box.foot) } ?: Double.MAX_VALUE
        }.take(4)
        for (d in candidates) {
            val near = gates.any { it.distance(d.box.foot) <= (d.box.height * 0.65).coerceIn(0.05, 0.22) || it.containsBody(d.box.center) }
            if (!near && d.id in accepted) continue
            if (d.id !in clouds && clouds.size >= 4) continue
            val cloud = clouds.getOrPut(d.id) { Cloud(d.id, d.box, mutableListOf(), timeMs - 1000) }
            if (timeMs - cloud.seededAt < 300) continue
            if (cloud.box.height > 0 && d.box.height / cloud.box.height !in 0.7..1.4) continue
            if (cloud.box.iou(d.box) < 0.1) cloud.dots.clear()
            cloud.box = d.box
            val mask = bodyMask(d, current, frame)
            try {
                for (cellY in 0 until 8) for (cellX in 0 until 4) {
                    val cell = cellY * 4 + cellX
                    val allowance = min(25 - cloud.dots.count { it.cell == cell }, 1800 - clouds.values.sumOf { it.dots.size })
                    if (allowance <= 0 || cloud.dots.size >= 800) continue
                    val left = ((d.box.left + d.box.width * cellX / 4) * frame.width).toInt().coerceIn(0, frame.width - 1)
                    val right = ((d.box.left + d.box.width * (cellX + 1) / 4) * frame.width).toInt().coerceIn(left + 1, frame.width)
                    val top = ((d.box.top + d.box.height * cellY / 8) * frame.height).toInt().coerceIn(0, frame.height - 1)
                    val bottom = ((d.box.top + d.box.height * (cellY + 1) / 8) * frame.height).toInt().coerceIn(top + 1, frame.height)
                    if (right - left < 5 || bottom - top < 5) continue
                    val rect = Rect(left, top, right - left, bottom - top)
                    val roi = gray.submat(rect); val regionMask = mask.submat(rect); val corners = MatOfPoint()
                    try {
                        Imgproc.goodFeaturesToTrack(roi, corners, allowance, 0.01, 2.5, regionMask, 3, false, 0.04)
                        for (pt in corners.toArray()) {
                            val q = Point(pt.x + left, pt.y + top)
                            if (cloud.dots.none { hypot(it.point.x - q.x, it.point.y - q.y) < 2.5 }) cloud.dots += Dot(q, cell)
                        }
                    } finally { roi.release(); regionMask.release(); corners.release() }
                }
                cloud.seededAt = timeMs
            } finally { mask.release() }
        }
    }

    /** Reverse only already-owned current features; never search for an unknown human in old frames. */
    fun traceOrigin(d: FlowDetection, frames: List<PortalFrameHub.GrayFrame>): String? {
        if (!healthy || cameraMoved || !d.locked || !d.bodyValid()) return null
        val cloud = clouds[d.id] ?: return null
        val ordered = frames.takeLast(7)
        if (ordered.size < 3 || cloud.dots.size < 12) return null
        if (ordered.zipWithNext().any { (a, b) -> b.stamp.timestampMs - a.stamp.timestampMs !in 1..400 }) return null
        var points = cloud.dots.filter { it.cell / 4 <= 5 }.distinctBy { it.cell to (it.point.x.toInt() / 3) }.take(180)
        if (points.map { it.cell }.distinct().size < 4) return null
        val startByCell = points.groupBy { it.cell }.mapValues { (_, ps) -> FlowPoint(median(ps.map { it.point.x }), median(ps.map { it.point.y })) }
        val votes = mutableMapOf<String, MutableSet<Int>>()
        val times = mutableMapOf<String, MutableSet<Long>>()
        for (i in ordered.lastIndex downTo 1) {
            val newer = ordered[i]; val older = ordered[i - 1]
            if (newer.width != older.width || newer.height != older.height) return null
            val a = Mat(newer.height, newer.width, CvType.CV_8UC1)
            val b = Mat(older.height, older.width, CvType.CV_8UC1)
            try {
                a.put(0, 0, newer.pixels); b.put(0, 0, older.pixels)
                val back = flow(a, b, points.map { it.point })
                points = points.mapIndexedNotNull { j, dot -> back[j]?.let { Dot(it, dot.cell, dot.age + 1) } }
                if (points.map { it.cell }.distinct().size < 4) break
                val cellPoints = points.groupBy { it.cell }.mapValues { (_, ps) -> FlowPoint(median(ps.map { it.point.x }) / older.width, median(ps.map { it.point.y }) / older.height) }
                for ((cell, earlier) in cellPoints) {
                    val currentPx = startByCell[cell] ?: continue
                    val current = FlowPoint(currentPx.x / newer.width, currentPx.y / newer.height)
                    val matches = gates.filter { !it.isBlind && it.containsBody(earlier) &&
                        it.side(current) - it.side(earlier) > 0.01 && current.distance(earlier) > 0.012 }
                    if (matches.size == 1) {
                        val id = matches.single().id
                        votes.getOrPut(id) { mutableSetOf() }.add(cell)
                        times.getOrPut(id) { mutableSetOf() }.add(older.stamp.timestampMs)
                    }
                }
            } finally { a.release(); b.release() }
        }
        val ranked = votes.entries.sortedByDescending { it.value.size }
        val winner = ranked.firstOrNull() ?: return null
        if (winner.value.size < 4 || (times[winner.key]?.size ?: 0) < 2) return null
        if (ranked.size > 1 && winner.value.size - ranked[1].value.size < 3) return null
        return winner.key
    }

    private fun bodyMask(d: FlowDetection, all: List<FlowDetection>, f: PortalFrameHub.GrayFrame): Mat {
        val mask = Mat.zeros(f.height, f.width, CvType.CV_8UC1)
        fun px(p: FlowPoint) = Point(p.x * f.width, p.y * f.height)
        val shoulders = listOfNotNull(d.joint(5), d.joint(6))
        val width = if (shoulders.size == 2) shoulders[0].distance(shoulders[1], f.width.toDouble() / f.height) * f.height else d.box.width * f.width * 0.5
        val torso = listOf(5, 6, 12, 11).mapNotNull { d.joint(it) }
        if (torso.size == 4) {
            val center = FlowPoint(torso.map { it.x }.average(), torso.map { it.y }.average())
            val poly = MatOfPoint(*torso.map { px(center + (it - center) * 0.83) }.toTypedArray())
            try { Imgproc.fillConvexPoly(mask, poly, Scalar(255.0)) } finally { poly.release() }
        }
        for ((a, b) in listOf(5 to 7, 7 to 9, 6 to 8, 8 to 10, 11 to 13, 13 to 15, 12 to 14, 14 to 16)) {
            val x = d.joint(a) ?: continue; val y = d.joint(b) ?: continue
            Imgproc.line(mask, px(x), px(y), Scalar(255.0), max(3, (width * 0.22).toInt()))
        }
        d.joint(0)?.let { Imgproc.circle(mask, px(it), max(2, (width * 0.25).toInt()), Scalar(255.0), -1) }
        val allowed = Mat.zeros(f.height, f.width, CvType.CV_8UC1)
        try {
            Imgproc.rectangle(allowed, px(FlowPoint(d.box.left, d.box.top)), px(FlowPoint(d.box.right, d.box.bottom)), Scalar(255.0), -1)
            Core.bitwise_and(mask, allowed, mask)
        } finally { allowed.release() }
        for (other in all.filter { it.id != d.id && it.score >= 0.5 }) {
            Imgproc.rectangle(mask, px(FlowPoint(other.box.left, other.box.top)), px(FlowPoint(other.box.right, other.box.bottom)), Scalar(0.0), -1)
        }
        return mask
    }

    private fun excluded(p: FlowPoint) = detections.any { it.box.contains(p, 0.015) } || clouds.values.any { it.box.contains(p, 0.015) }
    private fun updateBackground(f: PortalFrameHub.GrayFrame) {
        for (y in 0 until f.height) for (x in 0 until f.width) {
            val i = y * f.width + x
            if (excluded(FlowPoint(x.toDouble() / f.width, y.toDouble() / f.height))) continue
            val value = f.pixels[i].toInt() and 255
            val delta = abs(value - background[i])
            if (delta < 12 || bgAge[i] < 3) {
                background[i] = if (bgAge[i] == 0) value.toFloat() else background[i] * 0.95f + value * 0.05f
                bgAge[i] = min(100, bgAge[i] + 1)
            } else { bgAge[i] = 0; background[i] = value.toFloat() }
        }
    }
    private fun restoredBackground(p: Point, old: PortalFrameHub.GrayFrame, now: PortalFrameHub.GrayFrame): Boolean {
        var before = 0.0; var after = 0.0; var n = 0
        val cx = p.x.roundToInt(); val cy = p.y.roundToInt()
        for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) {
            if (x !in 0 until now.width || y !in 0 until now.height) continue
            val i = y * now.width + x
            if (bgAge[i] < 8) continue
            before += abs((old.pixels[i].toInt() and 255) - background[i])
            after += abs((now.pixels[i].toInt() and 255) - background[i]); n++
        }
        return n >= 12 && before / n > 14 && after / n < 9
    }
    private fun checkScene(old: Mat, gray: Mat, now: PortalFrameHub.GrayFrame, before: PortalFrameHub.GrayFrame): Boolean {
        val bgPoints = mutableListOf<Point>()
        var changed = 0; var n = 0
        for (y in 12 until now.height - 12 step 24) for (x in 12 until now.width - 12 step 24) {
            val p = FlowPoint(x.toDouble() / now.width, y.toDouble() / now.height)
            if (excluded(p) || gates.any { it.containsBody(p) }) continue
            val i = y * now.width + x
            if (abs((now.pixels[i].toInt() and 255) - (before.pixels[i].toInt() and 255)) > 35) changed++
            n++; bgPoints += Point(x.toDouble(), y.toDouble())
        }
        if (n >= 20 && changed.toDouble() / n > 0.60) return false
        val tracked = flow(old, gray, bgPoints)
        val deltas = tracked.mapIndexedNotNull { i, q -> q?.let { FlowPoint(it.x - bgPoints[i].x, it.y - bgPoints[i].y) } }
        if (deltas.size >= 15) {
            val mx = median(deltas.map { it.x }); val my = median(deltas.map { it.y })
            val coherent = deltas.count { hypot(it.x - mx, it.y - my) < 2.0 }.toDouble() / deltas.size
            if (hypot(mx, my) > 3.0 && coherent > 0.7) {
                cameraMoved = true
                Log.w("PortalV3Flow", "Camera moved; reset/recalibrate before further room transitions")
                return false
            }
        }
        return true
    }
    private fun normalized(p: Point, f: PortalFrameHub.GrayFrame) = FlowPoint(p.x / f.width, p.y / f.height)
    override fun close() { previous?.release(); previous = null; previousFrame = null; clouds.clear(); background = FloatArray(0); bgAge = IntArray(0) }
}
