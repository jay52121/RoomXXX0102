package com.example.roomxxx0102.logic.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.roomxxx0102.logic.pointing.HandLandmarkerPointingAdapter
import com.example.roomxxx0102.logic.pointing.HandObservation
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.sqrt

class HandSmokeTester(context: Context) {

    data class HandPoint(
        val x: Float,
        val y: Float,
        val z: Float,
        val confidence: Float? = null
    )

    companion object {
        private const val TAG = "HandSmokeTester"
        private const val SUMMARY_TAG = "HandPointConfidenceSummary"
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"
        private const val LANDMARK_INDEX_MCP = 5
        private const val LANDMARK_INDEX_TIP = 8
        private const val PROBE_DURATION_MS = 5000L
        private val CORE_POINT_INDICES = intArrayOf(5, 6, 8, 9, 10, 12)
    }

    private data class PointMetricAccumulator(
        var presenceExistsFrames: Int = 0,
        var visibilityExistsFrames: Int = 0,
        var presenceSum: Float = 0f,
        var visibilitySum: Float = 0f,
        var presenceValueCount: Int = 0,
        var visibilityValueCount: Int = 0,
        var presenceMin: Float = Float.POSITIVE_INFINITY,
        var presenceMax: Float = Float.NEGATIVE_INFINITY,
        var visibilityMin: Float = Float.POSITIVE_INFINITY,
        var visibilityMax: Float = Float.NEGATIVE_INFINITY
    ) {
        fun record(presence: Float?, visibility: Float?) {
            if (presence != null) {
                presenceExistsFrames += 1
                presenceSum += presence
                presenceValueCount += 1
                presenceMin = minOf(presenceMin, presence)
                presenceMax = maxOf(presenceMax, presence)
            }
            if (visibility != null) {
                visibilityExistsFrames += 1
                visibilitySum += visibility
                visibilityValueCount += 1
                visibilityMin = minOf(visibilityMin, visibility)
                visibilityMax = maxOf(visibilityMax, visibility)
            }
        }
    }

    private data class ConfidenceProbeSession(
        val startedAtMs: Long,
        val endAtMs: Long,
        var totalResultFrames: Int = 0,
        var noHandFrames: Int = 0,
        var validHandFrames: Int = 0,
        val pointMetrics: MutableMap<Int, PointMetricAccumulator> = CORE_POINT_INDICES.associateWith {
            PointMetricAccumulator()
        }.toMutableMap(),
        val handednessCounts: MutableMap<String, Int> = mutableMapOf(),
        var handednessScoreSum: Float = 0f,
        var handednessScoreCount: Int = 0,
        var firstTimestampMs: Long? = null,
        var lastTimestampMs: Long? = null
    )

    private data class FrameContext(
        val fullWidth: Int,
        val fullHeight: Int,
        val roi: RectF?
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeLock = Any()
    private val frameInfoLock = Any()
    private var handLandmarker: HandLandmarker? = null
    private var lastTimestampMs: Long = 0L
    private var confidenceProbeSession: ConfidenceProbeSession? = null
    private val pendingFrameContexts = LinkedHashMap<Long, FrameContext>()
    private val finishProbeRunnable = Runnable { finishConfidenceProbeSession() }
    var onHandsResult: ((List<List<HandPoint>>, Int?) -> Unit)? = null
    var onPointingObservation: ((HandObservation) -> Unit)? = null

    init {
        setupHandLandmarker()
    }

    fun detect(bitmap: Bitmap, roi: RectF? = null) {
        val detector = handLandmarker ?: return
        val timestampMs = nextTimestampMs()
        val inputBitmap = cropBitmapIfNeeded(bitmap, roi)
        val mpImage = BitmapImageBuilder(inputBitmap).build()
        rememberFrameContext(
            timestampMs = timestampMs,
            fullWidth = bitmap.width,
            fullHeight = bitmap.height,
            roi = roi
        )
        Log.i(
            TAG,
            "HSMOKE|CALL|bitmap=${inputBitmap.width}x${inputBitmap.height}|full=${bitmap.width}x${bitmap.height}|roi=${formatRoi(roi)}|ts=$timestampMs"
        )
        try {
            detector.detectAsync(mpImage, timestampMs)
        } catch (t: Throwable) {
            mpImage.close()
            forgetFrameContext(timestampMs)
            Log.e(TAG, "HSMOKE|ERROR|detectAsync failed|ts=$timestampMs", t)
        }
    }

    fun close() {
        mainHandler.removeCallbacks(finishProbeRunnable)
        try {
            handLandmarker?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        } finally {
            handLandmarker = null
            synchronized(frameInfoLock) {
                pendingFrameContexts.clear()
            }
        }
    }

    fun startConfidenceProbeSession() {
        val now = SystemClock.elapsedRealtime()
        synchronized(probeLock) {
            confidenceProbeSession = ConfidenceProbeSession(
                startedAtMs = now,
                endAtMs = now + PROBE_DURATION_MS
            )
        }
        mainHandler.removeCallbacks(finishProbeRunnable)
        mainHandler.postDelayed(finishProbeRunnable, PROBE_DURATION_MS)
        Log.i(
            SUMMARY_TAG,
            "probeStarted durationMs=$PROBE_DURATION_MS corePoints=${CORE_POINT_INDICES.joinToString(",")}"
        )
    }

    private fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this::onLiveStreamResult)
                .setErrorListener(this::onLiveStreamError)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(appContext, options)
            Log.i(TAG, "HSMOKE|INIT|model=$MODEL_ASSET_PATH|runningMode=LIVE_STREAM|numHands=2")
        } catch (t: Throwable) {
            Log.e(TAG, "HSMOKE|ERROR|init failed|model=$MODEL_ASSET_PATH", t)
            handLandmarker = null
        }
    }

    private fun onLiveStreamResult(result: HandLandmarkerResult, inputImage: MPImage) {
        try {
            val hands = result.landmarks()
            val frameContext = consumeFrameContext(result.timestampMs())
            val imageWidth = frameContext?.fullWidth ?: 0
            val imageHeight = frameContext?.fullHeight ?: 0
            val mappedHands = hands.map { hand ->
                hand.map { landmark ->
                    val mapped = mapToFullFrame(landmark, frameContext?.roi)
                    HandPoint(
                        x = mapped.x,
                        y = mapped.y,
                        z = landmark.z()
                    )
                }
            }
            val selectedHandIndex = selectHigherHandIndex(mappedHands)
            onPointingObservation?.invoke(
                HandLandmarkerPointingAdapter.toObservation(
                    timestampMs = result.timestampMs(),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    landmarks = mappedHands.getOrNull(selectedHandIndex).orEmpty(),
                    handCount = hands.size,
                    handedness = result.handednesses().getOrNull(selectedHandIndex)?.firstOrNull()
                )
            )
            sampleConfidenceProbe(result, hands)
            Log.i(TAG, "HSMOKE|RESULT|hands=${hands.size}")
            onHandsResult?.invoke(mappedHands, selectedHandIndex.takeIf { mappedHands.isNotEmpty() })
            val firstHand = hands.firstOrNull()
            if (firstHand == null) {
                Log.i(TAG, "HSMOKE|RESULT|firstHandLandmarks=0")
                return
            }
            Log.i(TAG, "HSMOKE|RESULT|firstHandLandmarks=${firstHand.size}")
            if (firstHand.size <= LANDMARK_INDEX_TIP) {
                Log.w(TAG, "HSMOKE|WARN|not enough landmarks|size=${firstHand.size}")
                return
            }
            val indexMcp = firstHand[LANDMARK_INDEX_MCP]
            val indexTip = firstHand[LANDMARK_INDEX_TIP]
            val vector = normalize(indexTip, indexMcp)
            Log.i(
                TAG,
                "HSMOKE|RESULT|indexVectorNorm=(x=${format3(vector[0])}, y=${format3(vector[1])}, z=${format3(vector[2])})"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "HSMOKE|ERROR|result callback failed", t)
        } finally {
            inputImage.close()
        }
    }

    private fun onLiveStreamError(error: RuntimeException) {
        Log.e(TAG, "HSMOKE|ERROR|landmarker error", error)
    }

    private fun sampleConfidenceProbe(
        result: HandLandmarkerResult,
        hands: List<List<NormalizedLandmark>>
    ) {
        synchronized(probeLock) {
            val session = confidenceProbeSession ?: return
            session.totalResultFrames += 1
            val timestampMs = result.timestampMs()
            if (session.firstTimestampMs == null) {
                session.firstTimestampMs = timestampMs
            }
            session.lastTimestampMs = timestampMs

            if (hands.isEmpty()) {
                session.noHandFrames += 1
                return
            }

            session.validHandFrames += 1
            val firstHand = hands.first()
            val handedness = result.handednesses().firstOrNull()?.firstOrNull()
            val handednessLabel = handedness?.categoryName().orEmpty().ifEmpty { "UNKNOWN" }
            session.handednessCounts[handednessLabel] =
                (session.handednessCounts[handednessLabel] ?: 0) + 1
            handedness?.score()?.let { score ->
                session.handednessScoreSum += score
                session.handednessScoreCount += 1
            }

            for (index in CORE_POINT_INDICES) {
                val point = firstHand.getOrNull(index) ?: continue
                val presence = point.presence().orElse(null)
                val visibility = point.visibility().orElse(null)
                session.pointMetrics.getValue(index).record(presence, visibility)
            }
        }
    }

    private fun finishConfidenceProbeSession() {
        val session = synchronized(probeLock) {
            val current = confidenceProbeSession ?: return
            confidenceProbeSession = null
            current
        }
        emitConfidenceSummary(session)
    }

    private fun emitConfidenceSummary(session: ConfidenceProbeSession) {
        Log.i(SUMMARY_TAG, "summaryBegin")
        Log.i(SUMMARY_TAG, "totalResultFrames=${session.totalResultFrames}")
        Log.i(SUMMARY_TAG, "noHandFrames=${session.noHandFrames}")
        Log.i(SUMMARY_TAG, "validHandFrames=${session.validHandFrames}")
        Log.i(
            SUMMARY_TAG,
            "timestampRange=${session.firstTimestampMs ?: -1}..${session.lastTimestampMs ?: -1}"
        )
        Log.i(
            SUMMARY_TAG,
            "handednessCounts=${session.handednessCounts} handednessScoreMean=${formatNullable(meanOrNull(session.handednessScoreSum, session.handednessScoreCount))}"
        )

        val validFrames = session.validHandFrames.coerceAtLeast(1)
        var presenceCoverageHits = 0
        var visibilityCoverageHits = 0
        var usableCoverageHits = 0
        var hasNonZeroMean = false

        for (index in CORE_POINT_INDICES) {
            val metric = session.pointMetrics.getValue(index)
            val presenceExistsRatio = metric.presenceExistsFrames.toFloat() / validFrames.toFloat()
            val visibilityExistsRatio = metric.visibilityExistsFrames.toFloat() / validFrames.toFloat()
            val presenceMean = meanOrNull(metric.presenceSum, metric.presenceValueCount)
            val visibilityMean = meanOrNull(metric.visibilitySum, metric.visibilityValueCount)
            if (presenceExistsRatio >= 0.8f) presenceCoverageHits += 1
            if (visibilityExistsRatio >= 0.8f) visibilityCoverageHits += 1
            if (maxOf(presenceExistsRatio, visibilityExistsRatio) >= 0.8f) usableCoverageHits += 1
            if ((presenceMean ?: 0f) > 0f || (visibilityMean ?: 0f) > 0f) {
                hasNonZeroMean = true
            }

            Log.i(
                SUMMARY_TAG,
                buildString {
                    append("point[$index] ")
                    append("presenceExistsRatio=${format3(presenceExistsRatio)} ")
                    append("visibilityExistsRatio=${format3(visibilityExistsRatio)} ")
                    append("presenceMean=${formatNullable(presenceMean)} ")
                    append("visibilityMean=${formatNullable(visibilityMean)} ")
                    append("presenceMin=${formatNullable(finiteOrNull(metric.presenceMin))} ")
                    append("presenceMax=${formatNullable(finiteOrNull(metric.presenceMax))} ")
                    append("visibilityMin=${formatNullable(finiteOrNull(metric.visibilityMin))} ")
                    append("visibilityMax=${formatNullable(finiteOrNull(metric.visibilityMax))}")
                }
            )
        }

        val corePointsPresenceCoverage = presenceCoverageHits.toFloat() / CORE_POINT_INDICES.size.toFloat()
        val corePointsVisibilityCoverage = visibilityCoverageHits.toFloat() / CORE_POINT_INDICES.size.toFloat()
        val usableCoverageRatio = usableCoverageHits.toFloat() / CORE_POINT_INDICES.size.toFloat()

        Log.i(SUMMARY_TAG, "corePointsPresenceCoverage=${format3(corePointsPresenceCoverage)}")
        Log.i(SUMMARY_TAG, "corePointsVisibilityCoverage=${format3(corePointsVisibilityCoverage)}")

        val conclusion = when {
            usableCoverageRatio >= 0.67f && hasNonZeroMean -> {
                "结论：Hand Landmarker 的单点 confidence 字段在当前项目中可稳定获取，可以作为业务评分的辅助特征，但不建议作为唯一判定依据。建议权重不超过 20%。"
            }
            usableCoverageRatio >= 0.3f -> {
                "结论：单点 confidence 字段可以拿到，但不稳定，适合做降权或兜底信号，不适合作为主判定依据。"
            }
            else -> {
                "结论：当前项目里的 Hand Landmarker 单点 confidence 字段基本不可用，不要依赖它做最终判定，应改用几何稳定性和时序稳定性构造自定义 confidence。"
            }
        }
        Log.i(SUMMARY_TAG, conclusion)
        Log.i(SUMMARY_TAG, "summaryEnd")
    }

    private fun nextTimestampMs(): Long {
        val now = SystemClock.uptimeMillis()
        lastTimestampMs = if (now > lastTimestampMs) now else lastTimestampMs + 1L
        return lastTimestampMs
    }

    private fun cropBitmapIfNeeded(bitmap: Bitmap, roi: RectF?): Bitmap {
        val normalizedRoi = roi ?: return bitmap
        val left = (normalizedRoi.left.coerceIn(0f, 1f) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (normalizedRoi.top.coerceIn(0f, 1f) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (normalizedRoi.right.coerceIn(0f, 1f) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (normalizedRoi.bottom.coerceIn(0f, 1f) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            (right - left).coerceAtLeast(1),
            (bottom - top).coerceAtLeast(1)
        )
    }

    private fun mapToFullFrame(landmark: NormalizedLandmark, roi: RectF?): PointF {
        if (roi == null) {
            return PointF(landmark.x(), landmark.y())
        }
        return PointF(
            roi.left + landmark.x() * roi.width(),
            roi.top + landmark.y() * roi.height()
        )
    }

    private fun selectHigherHandIndex(hands: List<List<HandPoint>>): Int {
        if (hands.isEmpty()) return 0
        return hands.indices.minByOrNull { index ->
            val hand = hands[index]
            if (hand.isEmpty()) {
                Float.POSITIVE_INFINITY
            } else {
                hand.sumOf { it.y.toDouble() }.toFloat() / hand.size.toFloat()
            }
        } ?: 0
    }

    private fun formatRoi(roi: RectF?): String {
        if (roi == null) return "-"
        return String.format(
            Locale.US,
            "(%.3f,%.3f,%.3f,%.3f)",
            roi.left,
            roi.top,
            roi.right,
            roi.bottom
        )
    }

    private fun rememberFrameContext(timestampMs: Long, fullWidth: Int, fullHeight: Int, roi: RectF?) {
        synchronized(frameInfoLock) {
            pendingFrameContexts[timestampMs] = FrameContext(
                fullWidth = fullWidth,
                fullHeight = fullHeight,
                roi = roi?.let { RectF(it) }
            )
            while (pendingFrameContexts.size > 24) {
                val oldestKey = pendingFrameContexts.entries.firstOrNull()?.key ?: break
                pendingFrameContexts.remove(oldestKey)
            }
        }
    }

    private fun forgetFrameContext(timestampMs: Long) {
        synchronized(frameInfoLock) {
            pendingFrameContexts.remove(timestampMs)
        }
    }

    private fun consumeFrameContext(timestampMs: Long): FrameContext? {
        synchronized(frameInfoLock) {
            pendingFrameContexts.remove(timestampMs)?.let { return it }
            var fallback: FrameContext? = null
            val iterator = pendingFrameContexts.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key <= timestampMs) {
                    fallback = entry.value
                    iterator.remove()
                }
            }
            return fallback
        }
    }

    private fun normalize(tip: NormalizedLandmark, mcp: NormalizedLandmark): FloatArray {
        val dx = tip.x() - mcp.x()
        val dy = tip.y() - mcp.y()
        val dz = tip.z() - mcp.z()
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length <= 1e-6f) {
            return floatArrayOf(0f, 0f, 0f)
        }
        return floatArrayOf(dx / length, dy / length, dz / length)
    }

    private fun meanOrNull(sum: Float, count: Int): Float? = if (count > 0) sum / count.toFloat() else null

    private fun finiteOrNull(value: Float): Float? = if (value.isFinite()) value else null

    private fun format3(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun formatNullable(value: Float?): String = value?.let { format3(it) } ?: "null"
}
