package com.example.roomxxx0102.logic.pointing

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.acos
import kotlin.math.hypot

class DeviceTriggeredPointingResolver(
    private val config: PointingConfig = PointingConfig(),
    private val scorer: DevicePointingScorer = DevicePointingScorer(),
    private val windowJudge: DevicePointingWindowJudge = DevicePointingWindowJudge(forceOutputOnLowConfidence = false)
) {
    private var active = false
    private var baseTargets: List<DevicePointingTarget> = emptyList()
    private var preparedTargets: List<DevicePreparedTarget> = emptyList()
    private var startTimestampMs: Long = 0L
    private var prevOrigin: PointF? = null
    private var prevDir: PointF? = null
    private var latestDebugSnapshot: PointingDebugSnapshot? = null
    private val validFrames = mutableListOf<DeviceFrameEvaluation>()
    private var noHandFrames = 0
    private var lastImageWidth = 0
    private var lastImageHeight = 0
    private var lastHandednessLabel: String? = null
    private var lastHandednessScore: Float? = null

    fun startSession(
        targets: List<DevicePointingTarget>,
        imageWidth: Int,
        imageHeight: Int,
        startTimestampMs: Long
    ) {
        baseTargets = targets.filter { it.polygon.size == 4 }
        preparedTargets = scorer.prepareTargets(baseTargets, imageWidth, imageHeight)
        this.startTimestampMs = startTimestampMs
        prevOrigin = null
        prevDir = null
        validFrames.clear()
        noHandFrames = 0
        lastImageWidth = imageWidth
        lastImageHeight = imageHeight
        lastHandednessLabel = null
        lastHandednessScore = null
        active = true
        latestDebugSnapshot = PointingDebugSnapshot(
            isActive = true,
            elapsedMs = 0L,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            originRaw = null,
            fingerDirRaw = null,
            smoothedOrigin = null,
            smoothedDir = null,
            tipCenter = null,
            dipCenter = null,
            pipCenter = null,
            mcpCenter = null,
            frameQuality = 0f,
            bestTargetId = null,
            bestScore = 0f,
            secondScore = 0f,
            acceptPath = AcceptPath.PENDING,
            validFrames = 0,
            noHandFrames = 0,
            targets = preparedTargets.map {
                PointingTargetDebugInfo(it.id, RectF(it.boundsPx), RectF(it.boundsPx), 0f)
            },
            top3Targets = emptyList()
        )
    }

    fun submitFrame(observation: HandObservation?): PointingDecision {
        if (!active) return PointingDecision.Pending
        val timestampMs = observation?.timestampMs ?: fallbackTimestampMs()
        val elapsedMs = (timestampMs - startTimestampMs).coerceAtLeast(0L)
        if (observation == null || observation.landmarksPx.size <= 17 || observation.imageWidth <= 0 || observation.imageHeight <= 0 || observation.handCount <= 0) {
            noHandFrames += 1
            latestDebugSnapshot = PointingDebugSnapshot(
                isActive = active,
                elapsedMs = elapsedMs,
                imageWidth = lastImageWidth,
                imageHeight = lastImageHeight,
                originRaw = null,
                fingerDirRaw = null,
                smoothedOrigin = prevOrigin,
                smoothedDir = prevDir,
                tipCenter = null,
                dipCenter = null,
                pipCenter = null,
                mcpCenter = null,
                frameQuality = 0f,
                bestTargetId = null,
                bestScore = 0f,
                secondScore = 0f,
                acceptPath = AcceptPath.PENDING,
                validFrames = validFrames.size,
                noHandFrames = noHandFrames,
                targets = preparedTargets.map {
                    PointingTargetDebugInfo(it.id, RectF(it.boundsPx), RectF(it.boundsPx), 0f)
                },
                top3Targets = emptyList()
            )
            return if (elapsedMs >= config.timeoutMs) finalizeDecision(elapsedMs) else PointingDecision.Pending
        }

        ensurePreparedTargets(observation.imageWidth, observation.imageHeight)
        lastImageWidth = observation.imageWidth
        lastImageHeight = observation.imageHeight
        lastHandednessLabel = observation.handednessLabel
        lastHandednessScore = observation.handednessScore

        val lm = observation.landmarksPx
        val p5 = lm[5]; val p6 = lm[6]; val p7 = lm[7]; val p8 = lm[8]
        val p9 = lm[9]; val p10 = lm[10]; val p11 = lm[11]; val p12 = lm[12]
        val p17 = lm[17]

        val tipCenter = midpoint(p8, p12)
        val dipCenter = midpoint(p7, p11)
        val pipCenter = midpoint(p6, p10)
        val mcpCenter = midpoint(p5, p9)

        val originRaw = lerp(dipCenter, tipCenter, config.originLerpFactor)
        val dirDistal = normalizeOrNull(subtract(tipCenter, dipCenter))
        val dirProximal = normalizeOrNull(subtract(tipCenter, pipCenter))
        val dirOverall = normalizeOrNull(subtract(tipCenter, mcpCenter))
        val fingerDirRaw = normalizeOrNull(
            weightedSum(
                config.distalWeight to dirDistal,
                config.proximalWeight to dirProximal,
                config.overallWeight to dirOverall
            )
        ) ?: prevDir ?: PointF(1f, 0f)

        val smoothedOrigin = prevOrigin?.let { lerp(it, originRaw, config.smoothingAlpha) } ?: originRaw
        val smoothedDir = prevDir?.let {
            normalizeOrNull(add(scale(fingerDirRaw, config.smoothingAlpha), scale(it, 1f - config.smoothingAlpha)))
        } ?: fingerDirRaw

        val indexStraightScore = angleToStraightScore(angleDeg(p5, p6, p8), config.indexStraightLowDeg, config.indexStraightHighDeg)
        val middleStraightScore = angleToStraightScore(angleDeg(p9, p10, p12), config.middleStraightLowDeg, config.middleStraightHighDeg)
        val parallelScore = parallelScore(subtract(p8, p6), subtract(p12, p10), config.parallelHighDeg, config.parallelLowDeg)
        val palmWidth = distance(p5, p17).coerceAtLeast(1e-3f)
        val gapNorm = distance(p8, p12) / palmWidth
        val gapScore = gapScore(gapNorm, config.gapKeepHighMax, config.gapDropLowMin)
        val temporalStabilityScore = temporalStabilityScore(smoothedDir, prevDir)
        val frameQuality = (
            0.28f * indexStraightScore +
                0.28f * middleStraightScore +
                0.20f * parallelScore +
                0.12f * gapScore +
                0.12f * temporalStabilityScore
            ).coerceIn(0f, 1f)

        prevOrigin = smoothedOrigin
        prevDir = smoothedDir

        val frameScores = scorer.scoreFrame(smoothedOrigin, smoothedDir, frameQuality, preparedTargets)
        scorer.logFrameScores(validFrames.size + 1, frameQuality, frameScores)
        validFrames += DeviceFrameEvaluation(
            timestampMs = timestampMs,
            rayConfidence = frameQuality,
            frameScores = frameScores
        )

        val best = frameScores.maxByOrNull { it.totalScore }
        val second = frameScores.sortedByDescending { it.totalScore }.getOrNull(1)
        latestDebugSnapshot = PointingDebugSnapshot(
            isActive = active,
            elapsedMs = elapsedMs,
            imageWidth = observation.imageWidth,
            imageHeight = observation.imageHeight,
            originRaw = originRaw,
            fingerDirRaw = fingerDirRaw,
            smoothedOrigin = smoothedOrigin,
            smoothedDir = smoothedDir,
            tipCenter = tipCenter,
            dipCenter = dipCenter,
            pipCenter = pipCenter,
            mcpCenter = mcpCenter,
            frameQuality = frameQuality,
            bestTargetId = best?.deviceId,
            bestScore = best?.totalScore ?: 0f,
            secondScore = second?.totalScore ?: 0f,
            acceptPath = AcceptPath.PENDING,
            validFrames = validFrames.size,
            noHandFrames = noHandFrames,
            targets = preparedTargets.map { target ->
                val score = frameScores.firstOrNull { it.deviceId == target.id }?.totalScore ?: 0f
                PointingTargetDebugInfo(target.id, RectF(target.boundsPx), RectF(target.boundsPx), score)
            },
            top3Targets = frameScores.sortedByDescending { it.totalScore }.take(3).map { it.deviceId to it.totalScore }
        )

        return if (elapsedMs >= config.timeoutMs) finalizeDecision(elapsedMs) else PointingDecision.Pending
    }

    fun latestDebugSnapshot(): PointingDebugSnapshot? = latestDebugSnapshot

    fun isActive(): Boolean = active

    fun cancelSession() {
        active = false
        baseTargets = emptyList()
        preparedTargets = emptyList()
        validFrames.clear()
        prevOrigin = null
        prevDir = null
        noHandFrames = 0
    }

    private fun finalizeDecision(elapsedMs: Long): PointingDecision {
        val result = windowJudge.judge(preparedTargets, validFrames)
        val bestScore = result.winnerStats?.finalScore ?: 0f
        val secondScore = result.secondStats?.finalScore ?: 0f
        val top3 = result.sortedStats.take(3).map { it.deviceId to it.finalScore }
        latestDebugSnapshot = latestDebugSnapshot?.copy(
            isActive = false,
            elapsedMs = elapsedMs,
            bestTargetId = result.winnerId,
            bestScore = bestScore,
            secondScore = secondScore,
            acceptPath = AcceptPath.DEVICE_WINDOW,
            validFrames = validFrames.size,
            noHandFrames = noHandFrames,
            targets = preparedTargets.map { target ->
                val score = result.sortedStats.firstOrNull { it.deviceId == target.id }?.finalScore ?: 0f
                PointingTargetDebugInfo(target.id, RectF(target.boundsPx), RectF(target.boundsPx), score)
            },
            top3Targets = top3
        )
        active = false
        val diagnostics = PointingDiagnostics(
            validFrames = validFrames.size,
            noHandFrames = noHandFrames,
            elapsedMs = elapsedMs,
            chosenTargetId = result.winnerId,
            bestScore = bestScore,
            secondScore = secondScore,
            meanFrameQuality = result.weightedAverageRayConfidence,
            acceptPath = AcceptPath.DEVICE_WINDOW,
            top3Targets = top3,
            lastHandednessLabel = lastHandednessLabel,
            lastHandednessScore = lastHandednessScore,
            confidenceStatus = result.confidenceStatus,
            dynamicFinalThreshold = result.dynamicFinalThreshold,
            finalLeadRatio = result.finalLeadRatio
        )
        return when (result.confidenceStatus) {
            PointingConfidenceStatus.HIGH_CONFIDENCE -> PointingDecision.Recognized(
                targetId = result.winnerId ?: "",
                score = bestScore,
                elapsedMs = elapsedMs,
                diagnostics = diagnostics
            )
            PointingConfidenceStatus.LOW_CONFIDENCE -> PointingDecision.Recognized(
                targetId = result.winnerId ?: "",
                score = bestScore,
                elapsedMs = elapsedMs,
                diagnostics = diagnostics
            )
            PointingConfidenceStatus.UNDETERMINED -> PointingDecision.Unrecognized(
                reason = when {
                    preparedTargets.isEmpty() -> UnrecognizedReason.NO_TARGETS
                    validFrames.isEmpty() -> UnrecognizedReason.NO_VALID_HAND
                    result.finalLeadRatio < 1.20f -> UnrecognizedReason.AMBIGUOUS
                    else -> UnrecognizedReason.LOW_EVIDENCE
                },
                score = bestScore,
                elapsedMs = elapsedMs,
                diagnostics = diagnostics
            )
        }
    }

    private fun ensurePreparedTargets(imageWidth: Int, imageHeight: Int) {
        if (imageWidth == lastImageWidth && imageHeight == lastImageHeight && preparedTargets.isNotEmpty()) return
        preparedTargets = scorer.prepareTargets(baseTargets, imageWidth, imageHeight)
    }

    private fun fallbackTimestampMs(): Long = if (startTimestampMs == 0L) 0L else startTimestampMs + config.timeoutMs

    private fun midpoint(a: PointF, b: PointF): PointF = PointF((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

    private fun lerp(a: PointF, b: PointF, t: Float): PointF = PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun subtract(a: PointF, b: PointF): PointF = PointF(a.x - b.x, a.y - b.y)

    private fun add(a: PointF, b: PointF): PointF = PointF(a.x + b.x, a.y + b.y)

    private fun scale(a: PointF, s: Float): PointF = PointF(a.x * s, a.y * s)

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

    private fun dot(a: PointF, b: PointF): Float = a.x * b.x + a.y * b.y

    private fun normalizeOrNull(v: PointF?): PointF? {
        if (v == null) return null
        val len = hypot(v.x, v.y)
        if (len < 1e-6f) return null
        return PointF(v.x / len, v.y / len)
    }

    private fun weightedSum(vararg items: Pair<Float, PointF?>): PointF {
        var x = 0f
        var y = 0f
        items.forEach { (weight, vector) ->
            if (vector != null) {
                x += vector.x * weight
                y += vector.y * weight
            }
        }
        return PointF(x, y)
    }

    private fun angleDeg(a: PointF, b: PointF, c: PointF): Float {
        val ab = subtract(a, b)
        val cb = subtract(c, b)
        val denom = (hypot(ab.x, ab.y) * hypot(cb.x, cb.y)).coerceAtLeast(1e-6f)
        val cos = (dot(ab, cb) / denom).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    private fun angleBetweenDeg(a: PointF, b: PointF): Float {
        val na = normalizeOrNull(a) ?: return 180f
        val nb = normalizeOrNull(b) ?: return 180f
        val cos = dot(na, nb).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    private fun angleToStraightScore(angleDeg: Float, lowDeg: Float, highDeg: Float): Float =
        smoothStep(lowDeg, highDeg, angleDeg)

    private fun parallelScore(a: PointF, b: PointF, highDeg: Float, lowDeg: Float): Float {
        val angle = angleBetweenDeg(a, b)
        return inverseSmoothStep(highDeg, lowDeg, angle)
    }

    private fun gapScore(gapNorm: Float, keepHighMax: Float, dropLowMin: Float): Float {
        return when {
            gapNorm <= keepHighMax -> 1f
            gapNorm >= dropLowMin -> 0f
            else -> 1f - smoothStep(keepHighMax, dropLowMin, gapNorm)
        }.coerceIn(0f, 1f)
    }

    private fun temporalStabilityScore(current: PointF, prev: PointF?): Float {
        val p = prev ?: return 1f
        val angle = angleBetweenDeg(current, p)
        return inverseSmoothStep(config.temporalStableHighDeg, config.temporalStableLowDeg, angle)
    }

    private fun smoothStep(low: Float, high: Float, x: Float): Float {
        if (high <= low) return if (x >= high) 1f else 0f
        val t = ((x - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun inverseSmoothStep(highScoreAt: Float, lowScoreAt: Float, x: Float): Float =
        (1f - smoothStep(highScoreAt, lowScoreAt, x)).coerceIn(0f, 1f)
}
