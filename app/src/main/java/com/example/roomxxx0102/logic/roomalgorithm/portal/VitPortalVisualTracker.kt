package com.example.roomxxx0102.logic.roomalgorithm.portal

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.example.roomxxx0102.data.model.PoseResult
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import org.opencv.video.TrackerVit
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VitPortalVisualTracker : PortalVisualTracker {
    override val trackerId = PortalVisualTrackerRegistry.OPENCV_VIT_ID

    private var openCvReady: Boolean? = null
    private var net: Net? = null
    private var tracker: TrackerVit? = null
    private var trackId: Int? = null
    private var lastBox: RectF? = null
    private var lastRefreshSeq = -1L
    private var suspectFrames = 0
    private var stuckFrames = 0
    private var motionMemory = 0
    private var lastDetectorCenter: PointF? = null
    private var lastModelStatus: String? = null

    override fun track(input: PortalVisualTrackerFrameInput): List<PortalVisualTrack> {
        VitTrackModelRepository.requestIfNeeded()
        if (tracker == null && !input.allowInitialization) {
            logModelStatus()
            return emptyList()
        }
        val bitmap = input.bitmap ?: return emptyList()
        if (!ensureReady()) {
            logModelStatus()
            return emptyList()
        }

        val rgba = Mat()
        val bgr = Mat()
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            if (tracker == null) {
                val seed = seedPose(input) ?: return emptyList()
                if (!reseed(bgr, seed, bitmap.width, bitmap.height, input.frameSeq)) return emptyList()
                rememberDetector(seed)
                return listOf(result(seed.id, lastBox!!, PortalVisualTrackState.TRACKING, 1f, started, "init"))
            }

            val id = trackId ?: return lost()
            val previous = lastBox?.let(::RectF) ?: return lost()
            val pixelBox = toPixel(previous, bitmap.width, bitmap.height)
            val detector = input.poses.firstOrNull { it.id == id && it.isConfirmed && !it.isShielded }
            val located = tracker?.update(bgr, pixelBox) == true
            val score = tracker?.getTrackingScore() ?: 0f

            if (!located || score < HARD_LOST_SCORE) {
                if (detector != null && reseed(bgr, detector, bitmap.width, bitmap.height, input.frameSeq)) {
                    rememberDetector(detector)
                    return listOf(result(id, lastBox!!, PortalVisualTrackState.TRACKING, score, started, "reseed_lost"))
                }
                return lost()
            }

            val current = fromPixel(pixelBox, bitmap.width, bitmap.height)
            val visualMove = centerDistance(previous, current)
            val detectorIou = detector?.let { iou(current, normalize(it.box)) }

            if (detector != null) {
                val detectorMove = rememberDetector(detector)
                motionMemory = if (detectorMove >= DETECTOR_MOVE) MOTION_MEMORY_FRAMES else (motionMemory - 1).coerceAtLeast(0)
                if (detectorIou != null && detectorIou < DETECTOR_RESEED_IOU) {
                    if (reseed(bgr, detector, bitmap.width, bitmap.height, input.frameSeq)) {
                        return listOf(result(id, lastBox!!, PortalVisualTrackState.TRACKING, score, started, "reseed_conflict"))
                    }
                }
            } else if (motionMemory > 0) {
                motionMemory -= 1
            }

            stuckFrames = if (motionMemory > 0 && visualMove <= STUCK_MOVE) stuckFrames + 1 else 0
            if (stuckFrames >= HARD_STUCK_FRAMES) return lost()

            val geometryOk = geometryOk(previous, current)
            val conflict = detectorIou != null && detectorIou < DETECTOR_CONFLICT_IOU
            val state = if (geometryOk && !conflict && score >= GOOD_SCORE && stuckFrames < SUSPECT_STUCK_FRAMES) {
                PortalVisualTrackState.TRACKING
            } else {
                PortalVisualTrackState.SUSPECT
            }
            suspectFrames = if (state == PortalVisualTrackState.SUSPECT) suspectFrames + 1 else 0
            lastBox = RectF(current)

            var note = when {
                !geometryOk -> "geometry"
                conflict -> "conflict"
                stuckFrames >= SUSPECT_STUCK_FRAMES -> "stuck"
                score < GOOD_SCORE -> "weak_score"
                else -> "update"
            }

            if (detector != null && detectorIou != null && detectorIou >= REFRESH_IOU &&
                input.frameSeq - lastRefreshSeq >= REFRESH_INTERVAL
            ) {
                if (reseed(bgr, detector, bitmap.width, bitmap.height, input.frameSeq)) {
                    note = "refresh"
                }
            }

            val output = result(
                id,
                lastBox ?: current,
                state,
                score,
                started,
                "$note score=${fmt(score)} move=${fmt(visualMove)} stuck=$stuckFrames" +
                    (detectorIou?.let { " iou=${fmt(it)}" } ?: "")
            )
            if (suspectFrames >= MAX_SUSPECT_FRAMES) clearActive()
            return listOf(output)
        } catch (t: Throwable) {
            Log.w(TAG, "ViTTrack failed: ${t.message}", t)
            clearActive()
            return emptyList()
        } finally {
            bgr.release()
            rgba.release()
        }
    }

    override fun reset() = clearActive()

    private fun ensureReady(): Boolean {
        openCvReady?.let { if (!it) return false }
        if (openCvReady == null) {
            openCvReady = try { OpenCVLoader.initLocal() } catch (_: Throwable) { false }
            if (openCvReady != true) return false
        }
        if (net != null) return true
        val bytes = VitTrackModelRepository.readyBytes() ?: return false
        return try {
            val buffer = MatOfByte(*bytes)
            try {
                net = Dnn.readNetFromONNX(buffer).also {
                    it.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
                    it.setPreferableTarget(Dnn.DNN_TARGET_CPU)
                }
            } finally {
                buffer.release()
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load model failed", t)
            false
        }
    }

    private fun reseed(frame: Mat, pose: PoseResult, w: Int, h: Int, seq: Long): Boolean {
        val model = net ?: return false
        return try {
            val box = normalize(pose.box)
            if (box.width() < MIN_SIDE || box.height() < MIN_SIDE) return false
            val next = TrackerVit.create(
                model,
                Scalar(0.485, 0.456, 0.406),
                Scalar(0.229, 0.224, 0.225),
                INTERNAL_SCORE_THRESHOLD
            )
            next.init(frame, toPixel(box, w, h))
            tracker = next
            trackId = pose.id
            lastBox = box
            lastRefreshSeq = seq
            suspectFrames = 0
            stuckFrames = 0
            true
        } catch (t: Throwable) {
            Log.w(TAG, "reseed failed: ${t.message}", t)
            clearActive()
            false
        }
    }

    private fun seedPose(input: PortalVisualTrackerFrameInput): PoseResult? {
        val preferred = input.preferredTrackId?.let { id -> input.poses.firstOrNull { it.id == id && reliable(it) } }
        return preferred ?: input.poses.filter(::reliable).maxByOrNull { it.score }
    }

    private fun reliable(pose: PoseResult): Boolean {
        val b = normalize(pose.box)
        return pose.isConfirmed && !pose.isShielded && b.width() >= MIN_SIDE && b.height() >= MIN_SIDE
    }

    private fun rememberDetector(pose: PoseResult): Float {
        val b = normalize(pose.box)
        val center = PointF(b.centerX(), b.centerY())
        val old = lastDetectorCenter
        lastDetectorCenter = center
        return if (old == null) 0f else hypot((center.x - old.x).toDouble(), (center.y - old.y).toDouble()).toFloat()
    }

    private fun result(id: Int, box: RectF, state: PortalVisualTrackState, score: Float, started: Long, note: String) =
        PortalVisualTrack(id, RectF(box), state, score,
            ((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L).coerceAtLeast(0L), note)

    private fun lost(): List<PortalVisualTrack> {
        clearActive()
        return emptyList()
    }

    private fun clearActive() {
        tracker = null
        trackId = null
        lastBox = null
        lastRefreshSeq = -1L
        suspectFrames = 0
        stuckFrames = 0
        motionMemory = 0
        lastDetectorCenter = null
    }

    private fun logModelStatus() {
        val s = VitTrackModelRepository.statusLabel()
        if (s != lastModelStatus) {
            lastModelStatus = s
            Log.i(TAG, "model=$s")
        }
    }

    private fun normalize(r: RectF): RectF = RectF(
        min(r.left, r.right).coerceIn(0f, 1f),
        min(r.top, r.bottom).coerceIn(0f, 1f),
        max(r.left, r.right).coerceIn(0f, 1f),
        max(r.top, r.bottom).coerceIn(0f, 1f)
    )

    private fun toPixel(r: RectF, w: Int, h: Int): Rect {
        val sw = w.coerceAtLeast(2); val sh = h.coerceAtLeast(2); val n = normalize(r)
        val l = (n.left * sw).roundToInt().coerceIn(0, sw - 2)
        val t = (n.top * sh).roundToInt().coerceIn(0, sh - 2)
        val rr = (n.right * sw).roundToInt().coerceIn(l + 2, sw)
        val bb = (n.bottom * sh).roundToInt().coerceIn(t + 2, sh)
        return Rect(l, t, rr - l, bb - t)
    }

    private fun fromPixel(r: Rect, w: Int, h: Int): RectF {
        val wf = w.coerceAtLeast(1).toFloat(); val hf = h.coerceAtLeast(1).toFloat()
        return normalize(RectF(r.x / wf, r.y / hf, (r.x + r.width) / wf, (r.y + r.height) / hf))
    }

    private fun centerDistance(a: RectF, b: RectF) = hypot(
        (a.centerX() - b.centerX()).toDouble(), (a.centerY() - b.centerY()).toDouble()
    ).toFloat()

    private fun geometryOk(a: RectF, b: RectF): Boolean {
        if (b.width() < MIN_SIDE || b.height() < MIN_SIDE) return false
        val ratio = b.width() * b.height() / (a.width() * a.height()).coerceAtLeast(1e-6f)
        return ratio in MIN_AREA_RATIO..MAX_AREA_RATIO && centerDistance(a, b) <= MAX_JUMP
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bb = min(a.bottom, b.bottom)
        if (r <= l || bb <= t) return 0f
        val inter = (r - l) * (bb - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.3f", v)

    companion object {
        private const val TAG = "PortalVisualVit"
        private const val MIN_SIDE = 0.015f
        private const val MIN_AREA_RATIO = 0.20f
        private const val MAX_AREA_RATIO = 5.0f
        private const val MAX_JUMP = 0.35f
        private const val INTERNAL_SCORE_THRESHOLD = 0.12f
        private const val HARD_LOST_SCORE = 0.18f
        private const val GOOD_SCORE = 0.32f
        private const val DETECTOR_RESEED_IOU = 0.12f
        private const val DETECTOR_CONFLICT_IOU = 0.20f
        private const val REFRESH_IOU = 0.45f
        private const val REFRESH_INTERVAL = 12L
        private const val DETECTOR_MOVE = 0.012f
        private const val STUCK_MOVE = 0.0025f
        private const val MOTION_MEMORY_FRAMES = 8
        private const val SUSPECT_STUCK_FRAMES = 3
        private const val HARD_STUCK_FRAMES = 6
        private const val MAX_SUSPECT_FRAMES = 6
    }
}
