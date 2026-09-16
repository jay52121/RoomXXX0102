package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.graphics.Bitmap
import android.graphics.Color
import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video
import kotlin.math.*

internal data class GateEventTileView(
    val gate: String,
    val box: FlowBox,
    val contours: List<List<FlowPoint>>,
    val foreground: Int,
    val owned: Int,
    val historyFrames: Int,
    val phase: GateSensorPhase,
    val owner: Int?,
    val referenceKnown: Boolean,
)

internal data class GateEventVisionResult(
    val flows: Map<Int, FlowEvidence>,
    val tiles: List<GateEventTileView>,
    val samples: List<FlowSample>,
    val healthy: Boolean,
    val costMs: Long,
    val points: Int,
    val activeGates: Int,
    val historyBytes: Long,
    val notes: List<String>,
    val origin: Pair<Int,String>? = null,
)

/**
 * Portal V4.1 visual backend.
 *
 * Important scheduling invariant: every portal only keeps a short local pre-roll. Difference,
 * MOG2 and LK are executed exclusively for ACTIVE/HOLD gates selected by GateActivityScheduler.
 * No whole-frame gray image, whole-frame difference or inactive-gate background model exists.
 */
internal class GateEventVision private constructor(
    private val gates: List<FlowGate>,
    private val cfg: GateConfig,
) : AutoCloseable {
    companion object {
        fun create(gates: List<FlowGate>, config: GateConfig): GateEventVision? = try {
            if (OpenCVLoader.initLocal()) GateEventVision(gates, config) else null
        } catch (_: LinkageError) { null } catch (_: RuntimeException) { null }
    }

    private data class HistoryFrame(val timeMs: Long, val gray: Mat)

    private class PortalState(val gate: FlowGate, val rect: Rect, val mask: Mat) {
        val history = ArrayDeque<HistoryFrame>()
        val clear = GateClearEvidence()
        val contacted = mutableSetOf<Int>()
        var owner: Int? = null
        var phase = GateSensorPhase.OFF
        var reference = Mat()
        var previous = Mat()
        var owned = Mat()
        var referenceKnown = false
        var acquiredWhileVisible = false
        var lastProcessed = -1L
        var mog: BackgroundSubtractorMOG2? = null

        fun resetEpisode(clearHistory: Boolean = false) {
            owner = null
            phase = GateSensorPhase.OFF
            reference.release(); reference = Mat()
            previous.release(); previous = Mat()
            owned.release(); owned = Mat()
            referenceKnown = false
            acquiredWhileVisible = false
            lastProcessed = -1L
            contacted.clear(); clear.reset()
            mog?.clear(); mog = null
            if (clearHistory) {
                history.forEach { it.gray.release() }
                history.clear()
            }
        }

        fun release() {
            resetEpisode(true)
            mask.release()
        }
    }

    private val scheduler = GateActivityScheduler(cfg)
    private val lk = if (cfg.method == GateMethod.OPTICAL_FLOW) GateEventLk(cfg) else null
    private val states = linkedMapOf<String, PortalState>()
    private val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0,3.0))
    private val growKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0,3.0))
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var previousTime = -1L
    private var sceneSamples: IntArray? = null
    private var blockedUntil = -1L
    private val originTried = mutableMapOf<Int,Long>()

    fun update(
        source: Bitmap,
        timeMs: Long,
        detections: List<FlowDetection>,
        people: List<FlowPersonView>,
        coverage: FlowBox?,
    ): GateEventVisionResult {
        val started = System.nanoTime()
        val notes = mutableListOf<String>()
        val flows = linkedMapOf<Int, FlowEvidence>()
        val views = mutableListOf<GateEventTileView>()
        val samples = mutableListOf<FlowSample>()
        var origin: Pair<Int,String>? = null
        try {
            ensureGeometry(source.width, source.height)
            val gap = previousTime >= 0 && (timeMs <= previousTime || timeMs - previousTime > cfg.maxGapMs)
            if (gap) {
                states.values.forEach { it.resetEpisode(clearHistory = true) }
                scheduler.reset(); lk?.reset()
                notes += "VISUAL_GAP_RESET_LOCAL_HISTORY"
            }
            if (sceneChanged(source)) {
                blockedUntil = timeMs + 350
                states.values.forEach { it.resetEpisode(clearHistory = true) }
                scheduler.reset(); lk?.reset()
                notes += "SCENE_CHANGED_SPARSE_GLOBAL_GUARD"
            }
            val healthy = timeMs >= blockedUntil
            val decisions = scheduler.update(timeMs, detections, gates).associateBy { it.gateId }
            val accepted = people.filter { it.accepted }.associateBy { it.track }

            // Crop + gray conversion is the only always-on portal work. It feeds the one-second
            // pre-roll. No diff/background/LK is executed for OFF/ARMED gates.
            val current = linkedMapOf<String,Mat>()
            for ((id,state) in states) {
                current[id] = extractGray(source, state.rect)
            }

            if (healthy) {
                for ((id,state) in states) {
                    val decision = decisions[id] ?: continue
                    val gray = current[id] ?: continue
                    val oldPhase = state.phase
                    state.phase = decision.phase
                    if (!decision.shouldProcessPixels) {
                        if (oldPhase == GateSensorPhase.ACTIVE || oldPhase == GateSensorPhase.HOLD) {
                            state.resetEpisode(clearHistory = false)
                            state.phase = decision.phase
                            lk?.dropGate(id)
                        }
                        views += viewIdle(state, decision)
                        continue
                    }
                    val owner = decision.ownerTrack ?: continue
                    if (state.owner != owner || oldPhase !in setOf(GateSensorPhase.ACTIVE, GateSensorPhase.HOLD)) {
                        startEpisode(state, owner, timeMs)
                    }
                    val detection = detections.firstOrNull { it.id == owner }
                    val visual = processActive(state, gray, detection, decision, timeMs, coverage, started)
                    views += visual.view
                    val base = flows[owner]
                    flows[owner] = merge(base, visual.evidence)
                    samples += visual.evidence.samples
                    if (visual.note != null) notes += visual.note
                }

                // A newly credible person may have emerged from a room before YOLO could lock it.
                // Only the geometrically selected current gate is replayed; the other six histories
                // remain untouched.
                for (d in detections) {
                    if (origin != null || d.id in accepted || !d.locked || !d.bodyValid()) continue
                    if (timeMs - (originTried[d.id] ?: -10000L) < 700) continue
                    val chosen = decisions.values.filter { it.ownerTrack == d.id && it.phase == GateSensorPhase.ACTIVE }
                    if (chosen.size != 1) continue
                    val state = states[chosen.single().gateId] ?: continue
                    originTried[d.id] = timeMs
                    val gray = current[state.gate.id] ?: continue
                    val body = bodyMask(d, state)
                    try {
                        val diffOk = replayEmergence(state, gray)
                        val lkOk = if (cfg.method == GateMethod.OPTICAL_FLOW && diffOk) {
                            lk?.verifyReverseEmergence(state.gate, gray, body,
                                state.history.map { it.timeMs to it.gray }, state.rect,
                                sourceWidth, sourceHeight, started) == true
                        } else true
                        if (diffOk && lkOk) origin = d.id to state.gate.id
                    } finally { body.release() }
                }
            } else {
                decisions.values.filter { it.phase != GateSensorPhase.OFF }.forEach {
                    states[it.gateId]?.let { s -> views += viewIdle(s,it) }
                }
            }

            // Append after analysis, so retro replay cannot use the answer frame as its own history.
            for ((id,state) in states) {
                val gray = current[id] ?: continue
                state.history.add(HistoryFrame(timeMs, gray.clone()))
                while (state.history.isNotEmpty() && timeMs - state.history.first().timeMs > cfg.historyMs) {
                    state.history.removeFirst().gray.release()
                }
            }
            current.values.forEach { it.release() }
            previousTime = timeMs
            if (originTried.size > 64) originTried.clear()
            val historyBytes = states.values.sumOf { s -> s.history.sumOf { it.gray.total() * it.gray.elemSize().toLong() } }
            return GateEventVisionResult(flows, views, samples, healthy, elapsed(started), lk?.pointCount ?: 0,
                decisions.values.count { it.shouldProcessPixels }, historyBytes, notes, origin)
        } catch (e: Exception) {
            return GateEventVisionResult(emptyMap(), views, emptyList(), false, elapsed(started), 0, 0,
                historyBytes(), listOf("EVENT_VISION_ERROR:${e.javaClass.simpleName}"), null)
        }
    }

    private data class ActiveResult(val view: GateEventTileView, val evidence: FlowEvidence, val note: String?)

    private fun processActive(
        state: PortalState,
        gray: Mat,
        detection: FlowDetection?,
        decision: GateSensorDecision,
        timeMs: Long,
        coverage: FlowBox?,
        started: Long,
    ): ActiveResult {
        val raw = Mat(); val foreground = Mat(); val diff = Mat(); val motion = Mat()
        var ownership: Mat? = null
        try {
            if (state.referenceKnown && !state.reference.empty()) {
                Core.absdiff(gray, state.reference, diff)
                Imgproc.threshold(diff, raw, cfg.pixelThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                Core.bitwise_and(raw, state.mask, raw)
            } else {
                raw.create(gray.size(), CvType.CV_8UC1); raw.setTo(Scalar(0.0))
            }
            if (cfg.method == GateMethod.MOG2) {
                val model = state.mog ?: createMog(state).also { state.mog = it }
                model.apply(gray, foreground, 0.0)
                Imgproc.threshold(foreground, foreground, 254.0, 255.0, Imgproc.THRESH_BINARY)
                Core.bitwise_and(foreground, state.mask, foreground)
            } else raw.copyTo(foreground)
            Imgproc.morphologyEx(foreground, foreground, Imgproc.MORPH_OPEN, openKernel)

            if (!state.previous.empty()) {
                Core.absdiff(gray, state.previous, motion)
                Imgproc.threshold(motion, motion, cfg.pixelThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                Core.bitwise_and(motion, state.mask, motion)
            } else {
                motion.create(gray.size(), CvType.CV_8UC1); motion.setTo(Scalar(0.0))
            }

            val owner = state.owner ?: decision.ownerTrack ?: -1
            var ownCount = if (!state.owned.empty()) Core.countNonZero(state.owned) else 0
            var pixelChange: Double? = null
            if (detection != null) {
                ownership = bodyMask(detection, state)
                val bodyPixels = Core.countNonZero(ownership).coerceAtLeast(1)
                val movingBody = Mat()
                try {
                    Core.bitwise_and(ownership, motion, movingBody)
                    pixelChange = Core.countNonZero(movingBody).toDouble() / bodyPixels
                } finally { movingBody.release() }
                Core.bitwise_and(ownership, foreground, ownership)
                ownCount = Core.countNonZero(ownership)
                if (ownCount >= 8) {
                    ownership.copyTo(state.owned)
                    state.contacted.addAll(occupiedFineCells(ownership))
                    state.acquiredWhileVisible = true
                }
            } else if (!state.owned.empty()) {
                val expanded = Mat()
                try {
                    Imgproc.dilate(state.owned, expanded, growKernel)
                    Core.bitwise_and(expanded, foreground, state.owned)
                    ownCount = Core.countNonZero(state.owned)
                } finally { expanded.release() }
            }

            val fg = Core.countNonZero(foreground)
            val rawCount = Core.countNonZero(raw)
            val remaining = max(fg, rawCount)
            val inspected = state.owner?.let { id ->
                val box = detection?.takeIf { it.id == id }?.box
                box == null || coverage == null || coverage.contains(box.center)
            } ?: true
            val clearFor = state.clear.observe(timeMs, remaining, ownCount,
                state.referenceKnown && state.acquiredWhileVisible && inspected,
                cfg.maxGapMs, cfg.clearRatio)
            val window = FlowWindowEvidence(state.gate.id, state.contacted.size, state.clear.peak, remaining,
                clearFor, state.referenceKnown, true, state.acquiredWhileVisible, timeMs)
            var evidence = FlowEvidence(owner, imageAvailable = true, frameHealthy = true,
                pixelChange = pixelChange, windowEvidence = listOf(window), reason = "PORTAL_LOCAL_EVENT_DIFF")

            if (cfg.method == GateMethod.OPTICAL_FLOW) {
                val seed = when {
                    ownership != null && !ownership.empty() -> ownership
                    !state.owned.empty() -> state.owned
                    else -> state.mask
                }
                val flow = lk?.update(state.gate, owner, state.previous.takeUnless { it.empty() }, gray, seed,
                    state.rect, sourceWidth, sourceHeight, timeMs, started)
                if (flow != null) evidence = flow.copy(pixelChange = pixelChange, windowEvidence = listOf(window))
            }
            gray.copyTo(state.previous); state.lastProcessed = timeMs
            val contours = contourView(foreground, state.rect)
            val view = GateEventTileView(state.gate.id, rectBox(state.rect), contours, remaining, ownCount,
                state.history.size, decision.phase, state.owner, state.referenceKnown)
            return ActiveResult(view, evidence, if (lk?.budgetExceeded == true) "OPTIONAL_LK_BUDGET_REACHED" else null)
        } finally {
            ownership?.release(); raw.release(); foreground.release(); diff.release(); motion.release()
        }
    }

    private fun startEpisode(state: PortalState, owner: Int, timeMs: Long) {
        state.resetEpisode(clearHistory = false)
        state.owner = owner
        state.phase = GateSensorPhase.ACTIVE
        val reference = state.history.lastOrNull { timeMs - it.timeMs >= 120 } ?: state.history.firstOrNull()
        if (reference != null) {
            reference.gray.copyTo(state.reference)
            state.referenceKnown = true
        }
        if (cfg.method == GateMethod.MOG2 && state.referenceKnown) state.mog = createMog(state)
    }

    private fun createMog(state: PortalState): BackgroundSubtractorMOG2 {
        val model = Video.createBackgroundSubtractorMOG2(120, cfg.mogVariance, true)
        // Warm only from the already-recorded pre-roll. Inactive gates never execute apply().
        state.history.takeLast(10).forEach { model.apply(it.gray, Mat().also { m -> m.release() }, cfg.backgroundRate) }
        return model
    }

    private fun replayEmergence(state: PortalState, current: Mat): Boolean {
        if (state.history.size < 4) return false
        val reference = state.history.first().gray
        val frames = state.history.takeLast(8).map { it.gray } + current
        val counts = mutableListOf<Int>()
        for (frame in frames) {
            val diff = Mat(); val fg = Mat()
            try {
                Core.absdiff(frame, reference, diff)
                Imgproc.threshold(diff, fg, cfg.pixelThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                Core.bitwise_and(fg, state.mask, fg)
                Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_OPEN, openKernel)
                counts += Core.countNonZero(fg)
            } finally { diff.release(); fg.release() }
        }
        if (counts.size < 4) return false
        val final = counts.last()
        val early = counts.take(max(1, counts.size/3)).average()
        val rises = counts.zipWithNext().count { (a,b) -> b > a + max(3, (final * 0.04).toInt()) }
        return final >= 20 && early <= final * 0.40 && rises >= 2
    }

    private fun merge(a: FlowEvidence?, b: FlowEvidence): FlowEvidence {
        if (a == null) return b
        val best = if (b.reliable || !a.reliable) b else a
        return best.copy(
            samples = a.samples + b.samples,
            pixelChange = b.pixelChange ?: a.pixelChange,
            windowEvidence = a.windowEvidence + b.windowEvidence,
            imageAvailable = a.imageAvailable || b.imageAvailable,
            frameHealthy = a.frameHealthy && b.frameHealthy,
        )
    }

    private fun ensureGeometry(w: Int, h: Int) {
        if (w == sourceWidth && h == sourceHeight && states.isNotEmpty()) return
        states.values.forEach { it.release() }; states.clear(); scheduler.reset(); lk?.reset()
        sourceWidth = w; sourceHeight = h; previousTime = -1L; sceneSamples = null
        for (g in gates) {
            if (g.isBlind || g.aperture.size < 3) continue
            val rect = cropRect(g,w,h)
            val mask = Mat.zeros(rect.height,rect.width,CvType.CV_8UC1)
            val polygon = MatOfPoint(*g.aperture.map { p ->
                Point(p.x*w-rect.x, p.y*h-rect.y)
            }.toTypedArray())
            try { Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0)) } finally { polygon.release() }
            states[g.id] = PortalState(g,rect,mask)
        }
    }

    private fun cropRect(g: FlowGate, w: Int, h: Int): Rect {
        val px = (cfg.cropPadding*w).roundToInt()
        val py = (cfg.cropPadding*h).roundToInt()
        val l = (floor(g.aperture.minOf { it.x }*w).toInt()-px).coerceIn(0,w-2)
        val t = (floor(g.aperture.minOf { it.y }*h).toInt()-py).coerceIn(0,h-2)
        val r = (ceil(g.aperture.maxOf { it.x }*w).toInt()+px).coerceIn(l+2,w)
        val b = (ceil(g.aperture.maxOf { it.y }*h).toInt()+py).coerceIn(t+2,h)
        return Rect(l,t,r-l,b-t)
    }

    private fun extractGray(source: Bitmap, rect: Rect): Mat {
        val crop = Bitmap.createBitmap(source, rect.x, rect.y, rect.width, rect.height)
        val rgba = Mat(); val gray = Mat()
        try {
            Utils.bitmapToMat(crop, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return gray
        } finally {
            rgba.release(); crop.recycle()
        }
    }

    private fun bodyMask(d: FlowDetection, state: PortalState): Mat {
        val m = Mat.zeros(state.rect.height,state.rect.width,CvType.CV_8UC1)
        fun p(q: FlowPoint)=Point(q.x*sourceWidth-state.rect.x,q.y*sourceHeight-state.rect.y)
        val torso = listOf(5,6,12,11).mapNotNull { d.joint(it,0.25) }
        if (torso.size == 4) {
            val poly=MatOfPoint(*torso.map(::p).toTypedArray())
            try { Imgproc.fillConvexPoly(m,poly,Scalar(255.0)) } finally { poly.release() }
        }
        val thickness=max(3,(d.box.width*sourceWidth*.12).roundToInt())
        for((a,b) in listOf(5 to 7,7 to 9,6 to 8,8 to 10,11 to 13,13 to 15,12 to 14,14 to 16)) {
            val x=d.joint(a,0.22)?:continue; val y=d.joint(b,0.22)?:continue
            Imgproc.line(m,p(x),p(y),Scalar(255.0),thickness)
        }
        d.joint(0,0.25)?.let { Imgproc.circle(m,p(it),max(3,thickness),Scalar(255.0),-1) }
        if (Core.countNonZero(m) < 12) {
            val l=(d.box.left*sourceWidth-state.rect.x).roundToInt().coerceIn(0,state.rect.width-1)
            val r=(d.box.right*sourceWidth-state.rect.x).roundToInt().coerceIn(l+1,state.rect.width)
            val top=((d.box.top+d.box.height*.18)*sourceHeight-state.rect.y).roundToInt().coerceIn(0,state.rect.height-1)
            val bottom=(d.box.bottom*sourceHeight-state.rect.y).roundToInt().coerceIn(top+1,state.rect.height)
            Imgproc.rectangle(m,Point(l.toDouble(),top.toDouble()),Point((r-1).toDouble(),(bottom-1).toDouble()),Scalar(255.0),-1)
        }
        Core.bitwise_and(m,state.mask,m)
        return m
    }

    private fun occupiedFineCells(mask: Mat): Set<Int> {
        val bytes=ByteArray(mask.rows()*mask.cols()); mask.get(0,0,bytes)
        val counts=IntArray(96)
        val w=mask.cols(); val h=mask.rows()
        for(y in 0 until h) for(x in 0 until w) if((bytes[y*w+x].toInt() and 255)!=0) {
            val cx=(x*8/w.coerceAtLeast(1)).coerceIn(0,7)
            val cy=(y*12/h.coerceAtLeast(1)).coerceIn(0,11)
            counts[cy*8+cx]++
        }
        return counts.indices.filter { counts[it] >= 3 }.toSet()
    }

    private fun contourView(mask: Mat, rect: Rect): List<List<FlowPoint>> {
        val work=mask.clone(); val hierarchy=Mat(); val contours=mutableListOf<MatOfPoint>()
        try {
            Imgproc.findContours(work,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
            return contours
                .filter { Imgproc.contourArea(it) >= cfg.contourMinArea }
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(10)
                .map { c ->
                    val a=c.toArray(); val stride=max(1,a.size/48)
                    a.filterIndexed { i,_ -> i%stride==0 }.map { q ->
                        FlowPoint((q.x+rect.x)/sourceWidth,(q.y+rect.y)/sourceHeight)
                    }
                }.filter { it.size>=3 }
        } finally {
            contours.forEach { it.release() }; hierarchy.release(); work.release()
        }
    }

    private fun viewIdle(state: PortalState, decision: GateSensorDecision)=GateEventTileView(
        state.gate.id, rectBox(state.rect), emptyList(), 0, if(state.owned.empty()) 0 else Core.countNonZero(state.owned),
        state.history.size, decision.phase, decision.ownerTrack, state.referenceKnown)

    private fun rectBox(r: Rect)=FlowBox(r.x.toDouble()/sourceWidth,r.y.toDouble()/sourceHeight,
        (r.x+r.width).toDouble()/sourceWidth,(r.y+r.height).toDouble()/sourceHeight)

    private fun sceneChanged(source: Bitmap): Boolean {
        val samples=IntArray(24); var i=0
        for (gy in 1..4) for (gx in 1..6) {
            val x=(source.width*gx/7).coerceIn(0,source.width-1)
            val y=(source.height*gy/5).coerceIn(0,source.height-1)
            val c=source.getPixel(x,y)
            samples[i++]=(Color.red(c)*77+Color.green(c)*150+Color.blue(c)*29) shr 8
        }
        val old=sceneSamples; sceneSamples=samples
        if(old==null) return false
        val deltas=samples.indices.map { samples[it]-old[it] }
        val changed=deltas.count { abs(it)>max(18,cfg.pixelThreshold) }
        return changed>=16 || abs(median(deltas.map { it.toDouble() }))>18
    }

    private fun historyBytes():Long=states.values.sumOf { s -> s.history.sumOf { it.gray.total()*it.gray.elemSize().toLong() } }
    private fun elapsed(started:Long)=(System.nanoTime()-started)/1_000_000L

    override fun close() {
        states.values.forEach { it.release() }; states.clear(); scheduler.reset(); lk?.reset()
        openKernel.release(); growKernel.release(); sceneSamples=null
    }
}
