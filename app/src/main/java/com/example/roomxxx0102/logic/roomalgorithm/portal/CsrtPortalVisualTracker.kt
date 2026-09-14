package com.example.roomxxx0102.logic.roomalgorithm.portal

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.example.roomxxx0102.data.model.PoseResult
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import org.opencv.tracking.TrackerCSRT
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Portal 专用短时视觉跟踪实验实现。
 *
 * 只负责“同一视觉目标当前在哪里”，不负责房间切换，也不把 CSRT 的返回值解释为人体置信度。
 */
class CsrtPortalVisualTracker : PortalVisualTracker {
    override val trackerId: String = PortalVisualTrackerRegistry.OPENCV_CSRT_ID

    private var openCvReady: Boolean? = null
    private var tracker: TrackerCSRT? = null
    private var activeTrackId: Int? = null
    private var lastBounds: RectF? = null
    private var lastRefreshFrameSeq: Long = -1L
    private var consecutiveSuspectFrames: Int = 0

    override fun track(input: PortalVisualTrackerFrameInput): List<PortalVisualTrack> {
        // Portal episode 未启动时不做任何 OpenCV 初始化/Bitmap 转换，避免全程白跑 CSRT 前处理。
        if (tracker == null && !input.allowInitialization) return emptyList()
        val bitmap = input.bitmap ?: return emptyList()
        if (!ensureOpenCv()) return emptyList()

        val rgba = Mat()
        val rgb = Mat()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

            if (tracker == null) {
                val seed = selectSeedPose(input) ?: return emptyList()
                if (!initializeFromPose(rgb, seed, bitmap.width, bitmap.height, input.frameSeq)) {
                    return emptyList()
                }
                return listOf(
                    PortalVisualTrack(
                        trackId = seed.id,
                        bounds = RectF(lastBounds!!),
                        state = PortalVisualTrackState.TRACKING,
                        updateTimeMs = elapsedMs(startedNs),
                        note = "init_from_detector"
                    )
                )
            }

            val trackedId = activeTrackId ?: run {
                clearActive()
                return emptyList()
            }
            val previousBounds = lastBounds?.let(::RectF)
            val outputRect = previousBounds?.let {
                normalizedToPixelRect(it, bitmap.width, bitmap.height)
            } ?: run {
                clearActive()
                return emptyList()
            }

            val located = tracker?.update(rgb, outputRect) == true
            val detectorPose = input.poses.firstOrNull { it.id == trackedId && !it.isShielded }
            if (!located) {
                if (detectorPose != null && detectorPose.isConfirmed &&
                    initializeFromPose(rgb, detectorPose, bitmap.width, bitmap.height, input.frameSeq)
                ) {
                    return listOf(
                        PortalVisualTrack(
                            trackId = detectorPose.id,
                            bounds = RectF(lastBounds!!),
                            state = PortalVisualTrackState.TRACKING,
                            updateTimeMs = elapsedMs(startedNs),
                            note = "reseed_after_update_fail"
                        )
                    )
                }
                clearActive()
                return emptyList()
            }

            val currentBounds = pixelToNormalizedRect(outputRect, bitmap.width, bitmap.height)
            val geometryOk = isGeometrySane(previousBounds, currentBounds)
            val detectorIou = detectorPose
                ?.takeIf { it.isConfirmed }
                ?.let { intersectionOverUnion(currentBounds, normalizeRect(it.box)) }

            var state = if (geometryOk && (detectorIou == null || detectorIou >= DETECTOR_CONFLICT_IOU)) {
                PortalVisualTrackState.TRACKING
            } else {
                PortalVisualTrackState.SUSPECT
            }
            var note = when {
                !geometryOk -> "geometry_suspect"
                detectorIou != null && detectorIou < DETECTOR_CONFLICT_IOU -> "detector_conflict"
                else -> "update"
            }

            if (state == PortalVisualTrackState.SUSPECT) {
                consecutiveSuspectFrames += 1
            } else {
                consecutiveSuspectFrames = 0
            }

            lastBounds = RectF(currentBounds)

            // YOLO 重新稳定看到同一 ID 时，周期性用检测框重新锚定，限制 CSRT 长期漂移。
            if (detectorPose != null && detectorPose.isConfirmed &&
                detectorIou != null && detectorIou >= DETECTOR_REFRESH_IOU &&
                input.frameSeq - lastRefreshFrameSeq >= REFRESH_INTERVAL_FRAMES
            ) {
                if (initializeFromPose(rgb, detectorPose, bitmap.width, bitmap.height, input.frameSeq)) {
                    state = PortalVisualTrackState.TRACKING
                    note = "refresh_from_detector"
                    consecutiveSuspectFrames = 0
                }
            }

            val result = PortalVisualTrack(
                trackId = trackedId,
                bounds = RectF(lastBounds ?: currentBounds),
                state = state,
                rawScore = null,
                updateTimeMs = elapsedMs(startedNs),
                note = if (detectorIou != null) "$note iou=${format(detectorIou)}" else note
            )

            if (consecutiveSuspectFrames >= MAX_SUSPECT_FRAMES) {
                clearActive()
            }
            return listOf(result)
        } catch (t: Throwable) {
            Log.w(TAG, "CSRT update failed: ${t.message}", t)
            clearActive()
            return emptyList()
        } finally {
            rgb.release()
            rgba.release()
        }
    }

    override fun reset() {
        clearActive()
    }

    private fun ensureOpenCv(): Boolean {
        openCvReady?.let { return it }
        val ready = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV init failed", t)
            false
        }
        openCvReady = ready
        Log.i(TAG, "OpenCV ready=$ready")
        return ready
    }

    private fun selectSeedPose(input: PortalVisualTrackerFrameInput): PoseResult? {
        val preferred = input.preferredTrackId?.let { id ->
            input.poses.firstOrNull { it.id == id && isReliableSeed(it) }
        }
        return preferred ?: input.poses
            .asSequence()
            .filter(::isReliableSeed)
            .maxByOrNull { it.score }
    }

    private fun isReliableSeed(pose: PoseResult): Boolean {
        val box = normalizeRect(pose.box)
        return pose.isConfirmed && !pose.isShielded &&
            box.width() >= MIN_BOX_SIDE && box.height() >= MIN_BOX_SIDE
    }

    private fun initializeFromPose(
        frame: Mat,
        pose: PoseResult,
        width: Int,
        height: Int,
        frameSeq: Long
    ): Boolean {
        return try {
            val bounds = normalizeRect(pose.box)
            if (bounds.width() < MIN_BOX_SIDE || bounds.height() < MIN_BOX_SIDE) return false
            val next = TrackerCSRT.create()
            next.init(frame, normalizedToPixelRect(bounds, width, height))
            tracker = next
            activeTrackId = pose.id
            lastBounds = bounds
            lastRefreshFrameSeq = frameSeq
            consecutiveSuspectFrames = 0
            true
        } catch (t: Throwable) {
            Log.w(TAG, "CSRT init failed: ${t.message}", t)
            clearActive()
            false
        }
    }

    private fun clearActive() {
        tracker = null
        activeTrackId = null
        lastBounds = null
        lastRefreshFrameSeq = -1L
        consecutiveSuspectFrames = 0
    }

    private fun normalizedToPixelRect(bounds: RectF, width: Int, height: Int): Rect {
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        val normalized = normalizeRect(bounds)
        val left = (normalized.left * safeWidth).roundToInt().coerceIn(0, safeWidth - 2)
        val top = (normalized.top * safeHeight).roundToInt().coerceIn(0, safeHeight - 2)
        val right = (normalized.right * safeWidth).roundToInt().coerceIn(left + 2, safeWidth)
        val bottom = (normalized.bottom * safeHeight).roundToInt().coerceIn(top + 2, safeHeight)
        return Rect(left, top, right - left, bottom - top)
    }

    private fun pixelToNormalizedRect(rect: Rect, width: Int, height: Int): RectF {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        return normalizeRect(
            RectF(
                rect.x / w,
                rect.y / h,
                (rect.x + rect.width) / w,
                (rect.y + rect.height) / h
            )
        )
    }

    private fun normalizeRect(rect: RectF): RectF {
        val left = min(rect.left, rect.right).coerceIn(0f, 1f)
        val right = max(rect.left, rect.right).coerceIn(0f, 1f)
        val top = min(rect.top, rect.bottom).coerceIn(0f, 1f)
        val bottom = max(rect.top, rect.bottom).coerceIn(0f, 1f)
        return RectF(left, top, right, bottom)
    }

    private fun isGeometrySane(previous: RectF?, current: RectF): Boolean {
        if (current.width() < MIN_BOX_SIDE || current.height() < MIN_BOX_SIDE) return false
        val prev = previous ?: return true
        val previousArea = (prev.width() * prev.height()).coerceAtLeast(1e-6f)
        val areaRatio = (current.width() * current.height()) / previousArea
        if (areaRatio !in MIN_AREA_RATIO..MAX_AREA_RATIO) return false
        val jump = hypot(
            (current.centerX() - prev.centerX()).toDouble(),
            (current.centerY() - prev.centerY()).toDouble()
        )
        return jump <= MAX_CENTER_JUMP
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun elapsedMs(startedNs: Long): Long {
        return ((SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun format(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

    companion object {
        private const val TAG = "PortalVisualCSRT"
        private const val MIN_BOX_SIDE = 0.015f
        private const val MIN_AREA_RATIO = 0.20f
        private const val MAX_AREA_RATIO = 5.0f
        private const val MAX_CENTER_JUMP = 0.35
        private const val DETECTOR_CONFLICT_IOU = 0.08f
        private const val DETECTOR_REFRESH_IOU = 0.35f
        private const val REFRESH_INTERVAL_FRAMES = 12L
        private const val MAX_SUSPECT_FRAMES = 5
    }
}
