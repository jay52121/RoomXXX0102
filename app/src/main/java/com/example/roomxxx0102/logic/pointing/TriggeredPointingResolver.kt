package com.example.roomxxx0102.logic.pointing

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class TargetRect(
    val id: String,
    val rect: RectF
)

data class ForearmHint(
    val originPx: PointF,
    val direction: PointF
)

data class HandObservation(
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarksPx: List<PointF>,
    val handCount: Int,
    val handednessLabel: String? = null,
    val handednessScore: Float? = null,
    val forearmHint: ForearmHint? = null
)

data class PointingConfig(
    val originLerpFactor: Float = 0.75f,
    val smoothingAlpha: Float = 0.65f,
    val distalWeight: Float = 0.55f,
    val proximalWeight: Float = 0.30f,
    val overallWeight: Float = 0.15f,
    val indexStraightLowDeg: Float = 140f,
    val indexStraightHighDeg: Float = 180f,
    val middleStraightLowDeg: Float = 140f,
    val middleStraightHighDeg: Float = 180f,
    val parallelHighDeg: Float = 8f,
    val parallelLowDeg: Float = 35f,
    val temporalStableHighDeg: Float = 6f,
    val temporalStableLowDeg: Float = 30f,
    val gapKeepHighMax: Float = 0.35f,
    val gapDropLowMin: Float = 0.75f,
    val angleScoreHighDeg: Float = 8f,
    val angleScoreLowDeg: Float = 35f,
    val baseToleranceRatioOfImageDiagonal: Float = 0.012f,
    val sizeToleranceRatioOfRectDiagonal: Float = 0.10f,
    val distanceSigmaMultiplier: Float = 1.15f,
    val fastAcceptMinElapsedMs: Long = 200L,
    val fastAcceptMinValidFrames: Int = 2,
    val fastAcceptMinAvgScore: Float = 0.90f,
    val fastAcceptMinAvgMargin: Float = 0.25f,
    val fastAcceptMinAvgFrameQuality: Float = 0.65f,
    val normalAcceptMinElapsedMs: Long = 300L,
    val normalAcceptMinValidFrames: Int = 3,
    val normalAcceptWindowSize: Int = 3,
    val normalAcceptMinWins: Int = 2,
    val normalAcceptMinTailStreak: Int = 2,
    val normalAcceptMinAvgScore: Float = 0.82f,
    val normalAcceptMinAvgMargin: Float = 0.18f,
    val normalAcceptMinAvgFrameQuality: Float = 0.55f,
    val timeoutMs: Long = 1000L,
    val timeoutMinValidFrames: Int = 2,
    val timeoutMinBestScore: Float = 0.72f,
    val timeoutMinMargin: Float = 0.12f,
    val timeoutMinMeanFrameQuality: Float = 0.45f,
    val timeoutOldestWeight: Float = 1.0f,
    val timeoutNewestWeight: Float = 1.6f
)

enum class UnrecognizedReason {
    NO_TARGETS,
    NO_VALID_HAND,
    LOW_EVIDENCE,
    AMBIGUOUS,
    TIMEOUT
}

enum class AcceptPath {
    PENDING,
    FAST,
    NORMAL,
    TIMEOUT,
    DEVICE_WINDOW,
    REJECT
}

enum class PointingConfidenceStatus {
    HIGH_CONFIDENCE,
    LOW_CONFIDENCE,
    UNDETERMINED
}

data class PointingTargetDebugInfo(
    val id: String,
    val rect: RectF,
    val expandedRect: RectF,
    val score: Float
)

data class PointingDebugSnapshot(
    val isActive: Boolean,
    val elapsedMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val originRaw: PointF?,
    val fingerDirRaw: PointF?,
    val smoothedOrigin: PointF?,
    val smoothedDir: PointF?,
    val tipCenter: PointF?,
    val dipCenter: PointF?,
    val pipCenter: PointF?,
    val mcpCenter: PointF?,
    val frameQuality: Float,
    val bestTargetId: String?,
    val bestScore: Float,
    val secondScore: Float,
    val acceptPath: AcceptPath,
    val validFrames: Int,
    val noHandFrames: Int,
    val targets: List<PointingTargetDebugInfo>,
    val top3Targets: List<Pair<String, Float>>
)

data class PointingDiagnostics(
    val validFrames: Int,
    val noHandFrames: Int,
    val elapsedMs: Long,
    val chosenTargetId: String?,
    val bestScore: Float,
    val secondScore: Float,
    val meanFrameQuality: Float,
    val acceptPath: AcceptPath,
    val top3Targets: List<Pair<String, Float>>,
    val lastHandednessLabel: String?,
    val lastHandednessScore: Float?,
    val confidenceStatus: PointingConfidenceStatus = PointingConfidenceStatus.UNDETERMINED,
    val dynamicFinalThreshold: Float = 0f,
    val finalLeadRatio: Float = 0f
)

sealed class PointingDecision {
    data object Pending : PointingDecision()

    data class Recognized(
        val targetId: String,
        val score: Float,
        val elapsedMs: Long,
        val diagnostics: PointingDiagnostics
    ) : PointingDecision()

    data class Unrecognized(
        val reason: UnrecognizedReason,
        val score: Float,
        val elapsedMs: Long,
        val diagnostics: PointingDiagnostics
    ) : PointingDecision()
}

private data class Vec2(val x: Float, val y: Float) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Float): Vec2 = Vec2(x * scale, y * scale)
}

private data class TargetScore(val id: String, val score: Float)
private data class TargetComputedScore(
    val id: String,
    val score: Float,
    val rect: RectF,
    val expandedRect: RectF
)

private data class FrameEvidence(
    val timestampMs: Long,
    val validHandFrame: Boolean,
    val noHandFrame: Boolean,
    val frameQuality: Float,
    val smoothedOrigin: Vec2?,
    val smoothedDir: Vec2?,
    val targetScores: List<TargetScore>,
    val bestTargetId: String?,
    val bestScore: Float,
    val secondBestScore: Float,
    val handednessLabel: String?,
    val handednessScore: Float?
)

class TriggeredPointingResolver(
    private val config: PointingConfig = PointingConfig()
) {
    private var active = false
    private var targets: List<TargetRect> = emptyList()
    private var startTimestampMs: Long = 0L
    private var prevOrigin: Vec2? = null
    private var prevDir: Vec2? = null
    private val evidences = mutableListOf<FrameEvidence>()
    private var latestDebugSnapshot: PointingDebugSnapshot? = null

    fun startSession(targets: List<TargetRect>, startTimestampMs: Long) {
        // startTimestampMs 与 HandObservation.timestampMs 必须来自同一单调时钟基准。
        this.targets = targets.filter { it.rect.width() > 0f && it.rect.height() > 0f }
        this.startTimestampMs = startTimestampMs
        prevOrigin = null
        prevDir = null
        evidences.clear()
        active = true
        latestDebugSnapshot = PointingDebugSnapshot(
            isActive = true,
            elapsedMs = 0L,
            imageWidth = 0,
            imageHeight = 0,
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
            targets = this.targets.map {
                PointingTargetDebugInfo(it.id, RectF(it.rect), RectF(it.rect), 0f)
            },
            top3Targets = emptyList()
        )
    }

    fun updateTargets(targets: List<TargetRect>) {
        if (!active) return
        this.targets = targets.filter { it.rect.width() > 0f && it.rect.height() > 0f }
    }

    fun submitFrame(observation: HandObservation?): PointingDecision {
        if (!active) return PointingDecision.Pending
        val timestampMs = observation?.timestampMs ?: fallbackTimestampMs()
        val elapsedMs = (timestampMs - startTimestampMs).coerceAtLeast(0L)
        val evidence = buildEvidence(observation, timestampMs)
        evidences.add(evidence)
        if (targets.isEmpty()) {
            return PointingDecision.Pending
        }

        evaluateFastAccept(elapsedMs)?.let { return finalizeRecognized(it, elapsedMs, AcceptPath.FAST) }
        evaluateNormalAccept(elapsedMs)?.let { return finalizeRecognized(it, elapsedMs, AcceptPath.NORMAL) }

        if (elapsedMs >= config.timeoutMs) {
            return finalizeTimeoutDecision(elapsedMs)
        }
        return PointingDecision.Pending
    }

    fun cancelSession() {
        active = false
        targets = emptyList()
        evidences.clear()
        prevOrigin = null
        prevDir = null
    }

    fun isActive(): Boolean = active
    fun latestDebugSnapshot(): PointingDebugSnapshot? = latestDebugSnapshot

    private fun buildEvidence(observation: HandObservation?, timestampMs: Long): FrameEvidence {
        val invalid = observation == null ||
            observation.landmarksPx.size <= 17 ||
            observation.imageWidth <= 0 ||
            observation.imageHeight <= 0 ||
            observation.handCount <= 0
        if (invalid) {
            val evidence = FrameEvidence(
                timestampMs = timestampMs,
                validHandFrame = false,
                noHandFrame = true,
                frameQuality = 0f,
                smoothedOrigin = null,
                smoothedDir = null,
                targetScores = emptyList(),
                bestTargetId = null,
                bestScore = 0f,
                secondBestScore = 0f,
                handednessLabel = observation?.handednessLabel,
                handednessScore = observation?.handednessScore
            )
            latestDebugSnapshot = PointingDebugSnapshot(
                isActive = active,
                elapsedMs = (timestampMs - startTimestampMs).coerceAtLeast(0L),
                imageWidth = observation?.imageWidth ?: 0,
                imageHeight = observation?.imageHeight ?: 0,
                originRaw = null,
                fingerDirRaw = null,
                smoothedOrigin = prevOrigin?.toPointF(),
                smoothedDir = prevDir?.toPointF(),
                tipCenter = null,
                dipCenter = null,
                pipCenter = null,
                mcpCenter = null,
                frameQuality = 0f,
                bestTargetId = null,
                bestScore = 0f,
                secondScore = 0f,
                acceptPath = AcceptPath.PENDING,
                validFrames = evidences.count { it.validHandFrame },
                noHandFrames = evidences.count { it.noHandFrame } + 1,
                targets = targets.map {
                    PointingTargetDebugInfo(it.id, RectF(it.rect), RectF(it.rect), 0f)
                },
                top3Targets = emptyList()
            )
            return evidence
        }

        val lm = observation.landmarksPx
        val p5 = vec(lm[5]); val p6 = vec(lm[6]); val p7 = vec(lm[7]); val p8 = vec(lm[8])
        val p9 = vec(lm[9]); val p10 = vec(lm[10]); val p11 = vec(lm[11]); val p12 = vec(lm[12])
        val p17 = vec(lm[17])

        val tipCenter = midpoint(p8, p12)
        val dipCenter = midpoint(p7, p11)
        val pipCenter = midpoint(p6, p10)
        val mcpCenter = midpoint(p5, p9)

        val originRaw = lerp(dipCenter, tipCenter, config.originLerpFactor)
        val dirDistal = normalizeOrNull(tipCenter - dipCenter)
        val dirProximal = normalizeOrNull(tipCenter - pipCenter)
        val dirOverall = normalizeOrNull(tipCenter - mcpCenter)
        val fingerDirRaw = normalizeOrNull(
            weightedSum(
                config.distalWeight to dirDistal,
                config.proximalWeight to dirProximal,
                config.overallWeight to dirOverall
            )
        ) ?: prevDir ?: Vec2(1f, 0f)

        val smoothedOrigin = prevOrigin?.let { lerp(it, originRaw, config.smoothingAlpha) } ?: originRaw
        val smoothedDir = prevDir?.let {
            normalizeOrNull(fingerDirRaw * config.smoothingAlpha + it * (1f - config.smoothingAlpha))
        } ?: fingerDirRaw

        val indexStraightScore = angleToStraightScore(
            angleDeg(p5, p6, p8),
            config.indexStraightLowDeg,
            config.indexStraightHighDeg
        )
        val middleStraightScore = angleToStraightScore(
            angleDeg(p9, p10, p12),
            config.middleStraightLowDeg,
            config.middleStraightHighDeg
        )
        val parallelScore = parallelScore(
            p8 - p6,
            p12 - p10,
            config.parallelHighDeg,
            config.parallelLowDeg
        )
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

        val imageDiagonal = hypot(observation.imageWidth.toFloat(), observation.imageHeight.toFloat())
        val targetScores = targets.map { target ->
            val expandedRect = computeExpandedRect(target.rect, imageDiagonal)
            val geom = computeGeomScore(target.rect, smoothedOrigin, smoothedDir, expandedRect)
            val score = (geom * (0.30f + 0.70f * frameQuality)).coerceIn(0f, 1f)
            TargetComputedScore(target.id, score, RectF(target.rect), expandedRect)
        }.sortedByDescending { it.score }

        val best = targetScores.firstOrNull()
        val second = targetScores.getOrNull(1)
        latestDebugSnapshot = PointingDebugSnapshot(
            isActive = active,
            elapsedMs = (timestampMs - startTimestampMs).coerceAtLeast(0L),
            imageWidth = observation.imageWidth,
            imageHeight = observation.imageHeight,
            originRaw = originRaw.toPointF(),
            fingerDirRaw = fingerDirRaw.toPointF(),
            smoothedOrigin = smoothedOrigin.toPointF(),
            smoothedDir = smoothedDir.toPointF(),
            tipCenter = tipCenter.toPointF(),
            dipCenter = dipCenter.toPointF(),
            pipCenter = pipCenter.toPointF(),
            mcpCenter = mcpCenter.toPointF(),
            frameQuality = frameQuality,
            bestTargetId = best?.id,
            bestScore = best?.score ?: 0f,
            secondScore = second?.score ?: 0f,
            acceptPath = AcceptPath.PENDING,
            validFrames = evidences.count { it.validHandFrame } + 1,
            noHandFrames = evidences.count { it.noHandFrame },
            targets = targetScores.map {
                PointingTargetDebugInfo(it.id, it.rect, it.expandedRect, it.score)
            },
            top3Targets = targetScores.take(3).map { it.id to it.score }
        )

        return FrameEvidence(
            timestampMs = timestampMs,
            validHandFrame = true,
            noHandFrame = false,
            frameQuality = frameQuality,
            smoothedOrigin = smoothedOrigin,
            smoothedDir = smoothedDir,
            targetScores = targetScores.map { TargetScore(it.id, it.score) },
            bestTargetId = best?.id,
            bestScore = best?.score ?: 0f,
            secondBestScore = second?.score ?: 0f,
            handednessLabel = observation.handednessLabel,
            handednessScore = observation.handednessScore
        )
    }

    private fun computeExpandedRect(rect: RectF, imageDiagonal: Float): RectF {
        val rectDiagonal = hypot(rect.width(), rect.height())
        val tolerancePx = (
            config.baseToleranceRatioOfImageDiagonal * imageDiagonal +
                config.sizeToleranceRatioOfRectDiagonal * rectDiagonal
            ).coerceAtLeast(1f)
        return RectF(
            rect.left - tolerancePx,
            rect.top - tolerancePx,
            rect.right + tolerancePx,
            rect.bottom + tolerancePx
        )
    }

    private fun computeGeomScore(rect: RectF, origin: Vec2, dir: Vec2, expandedRect: RectF): Float {
        val rectCenter = Vec2(rect.centerX(), rect.centerY())
        val toCenter = normalizeOrNull(rectCenter - origin) ?: Vec2(1f, 0f)
        val angle = angleBetweenDeg(dir, toCenter)
        val angleScore = inverseSmoothStep(config.angleScoreHighDeg, config.angleScoreLowDeg, angle)
        val tolerancePx = ((expandedRect.width() - rect.width()) * 0.5f).coerceAtLeast(1f)
        val rayDistance = rayToRectDistancePx(origin, dir, expandedRect)
        val distanceSigma = (tolerancePx * config.distanceSigmaMultiplier).coerceAtLeast(1f)
        val distanceScore = exp(-(rayDistance * rayDistance) / (2f * distanceSigma * distanceSigma))
            .coerceIn(0f, 1f)
        val hitScore = if (rayIntersectsRect(origin, dir, expandedRect)) 1f else 0f
        return (
            0.55f * angleScore +
                0.30f * distanceScore +
                0.15f * hitScore
            ).coerceIn(0f, 1f)
    }

    private fun evaluateFastAccept(elapsedMs: Long): TargetScore? {
        if (elapsedMs < config.fastAcceptMinElapsedMs) return null
        val validWindow = lastValidFrames(config.fastAcceptMinValidFrames)
        if (validWindow.size < config.fastAcceptMinValidFrames) return null
        val winnerId = validWindow.first().bestTargetId ?: return null
        if (validWindow.any { it.bestTargetId != winnerId }) return null
        val meanScore = validWindow.map { it.bestScore }.average().toFloat()
        val meanMargin = validWindow.map { it.bestScore - it.secondBestScore }.average().toFloat()
        val meanQuality = validWindow.map { it.frameQuality }.average().toFloat()
        if (meanScore < config.fastAcceptMinAvgScore) return null
        if (meanMargin < config.fastAcceptMinAvgMargin) return null
        if (meanQuality < config.fastAcceptMinAvgFrameQuality) return null
        return TargetScore(winnerId, meanScore)
    }

    private fun evaluateNormalAccept(elapsedMs: Long): TargetScore? {
        if (elapsedMs < config.normalAcceptMinElapsedMs) return null
        val validWindow = lastValidFrames(config.normalAcceptWindowSize)
        if (validWindow.size < config.normalAcceptMinValidFrames) return null
        val winnerCounts = validWindow.groupingBy { it.bestTargetId }.eachCount()
        val winner = winnerCounts.maxByOrNull { it.value }?.key ?: return null
        val wins = winnerCounts[winner] ?: 0
        if (wins < config.normalAcceptMinWins) return null
        val streak = trailingWinnerStreak(winner)
        if (streak < config.normalAcceptMinTailStreak) return null
        val winnerFrames = validWindow.filter { it.bestTargetId == winner }
        val meanScore = winnerFrames.map { it.bestScore }.average().toFloat()
        val meanMargin = winnerFrames.map { it.bestScore - it.secondBestScore }.average().toFloat()
        val meanQuality = validWindow.map { it.frameQuality }.average().toFloat()
        if (meanScore < config.normalAcceptMinAvgScore) return null
        if (meanMargin < config.normalAcceptMinAvgMargin) return null
        if (meanQuality < config.normalAcceptMinAvgFrameQuality) return null
        return TargetScore(winner, meanScore)
    }

    private fun finalizeTimeoutDecision(elapsedMs: Long): PointingDecision {
        val validFrames = evidences.filter { it.validHandFrame }
        if (validFrames.size < config.timeoutMinValidFrames) {
            return finalizeUnrecognized(
                reason = if (validFrames.isEmpty()) UnrecognizedReason.NO_VALID_HAND else UnrecognizedReason.TIMEOUT,
                score = 0f,
                elapsedMs = elapsedMs,
                acceptPath = AcceptPath.TIMEOUT
            )
        }

        val recencyScores = recencyWeightedScores(validFrames)
        val best = recencyScores.firstOrNull()
        val second = recencyScores.getOrNull(1)
        val bestScore = best?.score ?: 0f
        val secondScore = second?.score ?: 0f
        val meanQuality = validFrames.map { it.frameQuality }.average().toFloat()
        if (best != null &&
            bestScore >= config.timeoutMinBestScore &&
            (bestScore - secondScore) >= config.timeoutMinMargin &&
            meanQuality >= config.timeoutMinMeanFrameQuality
        ) {
            return finalizeRecognized(best, elapsedMs, AcceptPath.TIMEOUT)
        }

        val reason = when {
            best == null -> UnrecognizedReason.NO_VALID_HAND
            (bestScore - secondScore) < config.timeoutMinMargin -> UnrecognizedReason.AMBIGUOUS
            meanQuality < config.timeoutMinMeanFrameQuality -> UnrecognizedReason.LOW_EVIDENCE
            bestScore < config.timeoutMinBestScore -> UnrecognizedReason.TIMEOUT
            else -> UnrecognizedReason.TIMEOUT
        }
        return finalizeUnrecognized(reason, bestScore, elapsedMs, AcceptPath.TIMEOUT)
    }

    private fun finalizeRecognized(target: TargetScore, elapsedMs: Long, path: AcceptPath): PointingDecision.Recognized {
        val diagnostics = buildDiagnostics(
            chosenTargetId = target.id,
            bestScore = target.score,
            secondScore = currentSecondScore(target.id),
            elapsedMs = elapsedMs,
            acceptPath = path
        )
        latestDebugSnapshot = latestDebugSnapshot?.copy(
            isActive = false,
            elapsedMs = elapsedMs,
            bestTargetId = target.id,
            bestScore = target.score,
            secondScore = currentSecondScore(target.id),
            acceptPath = path,
            validFrames = diagnostics.validFrames,
            noHandFrames = diagnostics.noHandFrames,
            top3Targets = diagnostics.top3Targets
        )
        cancelSession()
        return PointingDecision.Recognized(target.id, target.score, elapsedMs, diagnostics)
    }

    private fun finalizeUnrecognized(
        reason: UnrecognizedReason,
        score: Float,
        elapsedMs: Long,
        acceptPath: AcceptPath
    ): PointingDecision.Unrecognized {
        val diagnostics = buildDiagnostics(
            chosenTargetId = null,
            bestScore = score,
            secondScore = 0f,
            elapsedMs = elapsedMs,
            acceptPath = acceptPath
        )
        latestDebugSnapshot = latestDebugSnapshot?.copy(
            isActive = false,
            elapsedMs = elapsedMs,
            bestTargetId = null,
            bestScore = score,
            secondScore = 0f,
            acceptPath = acceptPath,
            validFrames = diagnostics.validFrames,
            noHandFrames = diagnostics.noHandFrames,
            top3Targets = diagnostics.top3Targets
        )
        cancelSession()
        return PointingDecision.Unrecognized(reason, score, elapsedMs, diagnostics)
    }

    private fun buildDiagnostics(
        chosenTargetId: String?,
        bestScore: Float,
        secondScore: Float,
        elapsedMs: Long,
        acceptPath: AcceptPath
    ): PointingDiagnostics {
        val validFrames = evidences.filter { it.validHandFrame }
        val noHandFrames = evidences.count { it.noHandFrame }
        val meanFrameQuality = validFrames.map { it.frameQuality }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        val top3 = recencyWeightedScores(validFrames).take(3).map { it.id to it.score }
        val lastEvidence = evidences.lastOrNull()
        return PointingDiagnostics(
            validFrames = validFrames.size,
            noHandFrames = noHandFrames,
            elapsedMs = elapsedMs,
            chosenTargetId = chosenTargetId,
            bestScore = bestScore,
            secondScore = secondScore,
            meanFrameQuality = meanFrameQuality,
            acceptPath = acceptPath,
            top3Targets = top3,
            lastHandednessLabel = lastEvidence?.handednessLabel,
            lastHandednessScore = lastEvidence?.handednessScore
        )
    }

    private fun recencyWeightedScores(validFrames: List<FrameEvidence>): List<TargetScore> {
        if (validFrames.isEmpty()) return emptyList()
        val scoreMap = linkedMapOf<String, Float>()
        val n = validFrames.size
        validFrames.forEachIndexed { index, evidence ->
            val fraction = if (n == 1) 1f else index.toFloat() / (n - 1).toFloat()
            val weight = lerp(config.timeoutOldestWeight, config.timeoutNewestWeight, fraction)
            for (targetScore in evidence.targetScores) {
                scoreMap[targetScore.id] = (scoreMap[targetScore.id] ?: 0f) + targetScore.score * weight
            }
        }
        val totalWeight = validFrames.indices.sumOf { index ->
            val fraction = if (n == 1) 1f else index.toFloat() / (n - 1).toFloat()
            lerp(config.timeoutOldestWeight, config.timeoutNewestWeight, fraction).toDouble()
        }.toFloat().coerceAtLeast(1e-6f)
        return scoreMap.entries
            .map { TargetScore(it.key, (it.value / totalWeight).coerceIn(0f, 1f)) }
            .sortedByDescending { it.score }
    }

    private fun currentSecondScore(bestId: String): Float {
        val last = evidences.lastOrNull { it.validHandFrame } ?: return 0f
        val top = last.targetScores.firstOrNull { it.id != bestId }
        return top?.score ?: 0f
    }

    private fun lastValidFrames(count: Int): List<FrameEvidence> =
        evidences.filter { it.validHandFrame }.takeLast(count)

    private fun trailingWinnerStreak(targetId: String): Int {
        var streak = 0
        for (evidence in evidences.asReversed()) {
            if (!evidence.validHandFrame) continue
            if (evidence.bestTargetId == targetId) streak += 1 else break
        }
        return streak
    }

    private fun resolveElapsedMs(observation: HandObservation?): Long {
        val ts = observation?.timestampMs ?: fallbackTimestampMs()
        return (ts - startTimestampMs).coerceAtLeast(0L)
    }

    private fun fallbackTimestampMs(): Long {
        val lastTimestampMs = evidences.lastOrNull()?.timestampMs ?: startTimestampMs
        return max(startTimestampMs, lastTimestampMs)
    }

    private fun vec(point: PointF): Vec2 = Vec2(point.x, point.y)

    private fun midpoint(a: Vec2, b: Vec2): Vec2 = Vec2((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

    private fun lerp(a: Vec2, b: Vec2, t: Float): Vec2 = a * (1f - t) + b * t

    private fun lerp(a: Float, b: Float, t: Float): Float = a * (1f - t) + b * t

    private fun weightedSum(vararg entries: Pair<Float, Vec2?>): Vec2 {
        var x = 0f
        var y = 0f
        for ((weight, vec) in entries) {
            if (vec == null) continue
            x += weight * vec.x
            y += weight * vec.y
        }
        return Vec2(x, y)
    }

    private fun normalizeOrNull(v: Vec2): Vec2? {
        val len = hypot(v.x, v.y)
        if (len <= 1e-6f) return null
        return Vec2(v.x / len, v.y / len)
    }

    private fun distance(a: Vec2, b: Vec2): Float = hypot(a.x - b.x, a.y - b.y)

    private fun dot(a: Vec2, b: Vec2): Float = a.x * b.x + a.y * b.y

    private fun angleBetweenDeg(a: Vec2, b: Vec2): Float {
        val na = normalizeOrNull(a) ?: return 180f
        val nb = normalizeOrNull(b) ?: return 180f
        val cos = dot(na, nb).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    private fun angleDeg(a: Vec2, b: Vec2, c: Vec2): Float =
        angleBetweenDeg(a - b, c - b)

    private fun angleToStraightScore(angleDeg: Float, lowDeg: Float, highDeg: Float): Float =
        smoothStep(lowDeg, highDeg, angleDeg)

    private fun parallelScore(a: Vec2, b: Vec2, highDeg: Float, lowDeg: Float): Float {
        val angle = angleBetweenDeg(a, b)
        return inverseSmoothStep(highDeg, lowDeg, angle)
    }

    private fun gapScore(gapNorm: Float, keepHighMax: Float, dropLowMin: Float): Float {
        return when {
            gapNorm <= keepHighMax -> 1f
            gapNorm >= dropLowMin -> 0f
            else -> inverseSmoothStep(keepHighMax, dropLowMin, gapNorm)
        }
    }

    private fun temporalStabilityScore(current: Vec2, prev: Vec2?): Float {
        if (prev == null) return 0.7f
        val angle = angleBetweenDeg(current, prev)
        return inverseSmoothStep(config.temporalStableHighDeg, config.temporalStableLowDeg, angle)
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun inverseSmoothStep(highScoreAt: Float, lowScoreAt: Float, x: Float): Float =
        (1f - smoothStep(highScoreAt, lowScoreAt, x)).coerceIn(0f, 1f)

    private fun rayIntersectsRect(origin: Vec2, dir: Vec2, rect: RectF): Boolean {
        val invDx = if (kotlin.math.abs(dir.x) > 1e-6f) 1f / dir.x else Float.POSITIVE_INFINITY
        val invDy = if (kotlin.math.abs(dir.y) > 1e-6f) 1f / dir.y else Float.POSITIVE_INFINITY
        var t1 = (rect.left - origin.x) * invDx
        var t2 = (rect.right - origin.x) * invDx
        var t3 = (rect.top - origin.y) * invDy
        var t4 = (rect.bottom - origin.y) * invDy
        if (t1 > t2) {
            val tmp = t1
            t1 = t2
            t2 = tmp
        }
        if (t3 > t4) {
            val tmp = t3
            t3 = t4
            t4 = tmp
        }
        val tMin = max(t1, t3)
        val tMax = min(t2, t4)
        return tMax >= max(0f, tMin)
    }

    private fun rayToRectDistancePx(origin: Vec2, dir: Vec2, rect: RectF): Float {
        if (rayIntersectsRect(origin, dir, rect)) return 0f
        val corners = listOf(
            Vec2(rect.left, rect.top),
            Vec2(rect.right, rect.top),
            Vec2(rect.right, rect.bottom),
            Vec2(rect.left, rect.bottom)
        )
        val edges = listOf(
            corners[0] to corners[1],
            corners[1] to corners[2],
            corners[2] to corners[3],
            corners[3] to corners[0]
        )
        return edges.minOf { (a, b) -> distanceRayToSegment(origin, dir, a, b) }
    }

    private fun distanceRayToSegment(origin: Vec2, dir: Vec2, a: Vec2, b: Vec2): Float {
        val v = b - a
        val w0 = origin - a
        val aCoef = dot(dir, dir)
        val bCoef = dot(dir, v)
        val cCoef = dot(v, v)
        val dCoef = dot(dir, w0)
        val eCoef = dot(v, w0)
        val denom = aCoef * cCoef - bCoef * bCoef

        var u = 0f
        var t = 0f
        if (denom > 1e-6f) {
            t = (bCoef * eCoef - cCoef * dCoef) / denom
            u = (aCoef * eCoef - bCoef * dCoef) / denom
        }
        u = u.coerceIn(0f, 1f)
        t = max(0f, dot(dir, a + v * u - origin))

        val closestRay = origin + dir * t
        val closestSeg = a + v * u
        return distance(closestRay, closestSeg)
    }

    private fun Vec2.toPointF(): PointF = PointF(x, y)
}
