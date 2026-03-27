package com.example.roomxxx0102.logic.tracker

import android.util.Log
import android.graphics.RectF

import com.example.roomxxx0102.data.model.POSE_HIGH_CONFIDENCE_THRESHOLD
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class SimpleTrackerEngine : TrackerEngine {

    private data class TrackedSubjectHistory(
        var centerX: Float,
        var centerY: Float,
        var consecutiveMissingFrames: Int = 0,
        var maxHistoricalScore: Float = 0f,
        var hasEverMoved: Boolean = false,
        var hasPassedIntegrityCheck: Boolean = false,
        var wasConfirmed: Boolean = false,
        var lowShoulderFrames: Int = 0,
        val recentPositions: ArrayDeque<Pair<Float, Float>> = ArrayDeque(3),
        val recentKeypoints: ArrayDeque<List<com.example.roomxxx0102.data.model.Keypoint>> = ArrayDeque(4)
    )

    private data class CooldownZone(var rect: RectF, var ttl: Int)




    companion object {
        private const val MIN_DISPLAY_SCORE_THRESHOLD = 0.5f
        private const val MIN_SCORE_FOR_LOCKING = 0.6f
        private const val MAX_MISSING_FRAMES_TOLERANCE = 60
    }

    private val activeTrackersMap = ConcurrentHashMap<Int, TrackedSubjectHistory>()
    private var nextSubjectId = 0
    private val cooldownZones = mutableListOf<CooldownZone>()
    private var lastShieldZones: List<RectF> = emptyList()
    private var lastUnlockMessage: String? = null

    override fun reset() {
        activeTrackersMap.clear()
        nextSubjectId = 0
        cooldownZones.clear()
        lastShieldZones = emptyList()
        lastUnlockMessage = null
    }


    private fun isPoseStagnant(
        recent: ArrayDeque<List<com.example.roomxxx0102.data.model.Keypoint>>,
        tolerancePx: Float
    ): Boolean {
        if (recent.size < 4) return false
        val list = recent.toList()
        for (i in 0 until list.size - 1) {
            val a = list[i]
            val b = list[i + 1]
            if (a.size != b.size || a.isEmpty()) return false
            for (k in a.indices) {
                val dx = kotlin.math.abs(a[k].x - b[k].x)
                val dy = kotlin.math.abs(a[k].y - b[k].y)
                if (dx > tolerancePx || dy > tolerancePx) {
                    return false
                }
            }
        }
        return true
    }

    override fun track(
        detections: List<TrackDetection>,
        frameWidth: Int,
        frameHeight: Int,
        temporalAdvanced: Boolean,
        suppressStagnantUnlock: Boolean
    ): List<TrackResult> {
        val minSide = min(frameWidth, frameHeight).coerceAtLeast(1).toFloat()
        if (detections.isEmpty()) {
            updateCooldownZones(emptyList())
            lastShieldZones = cooldownZones.map { it.rect }
            if (temporalAdvanced) {
                decayMissingFrames(emptySet())
            }
            return emptyList()
        }

        val matchThreshold = minSide * 0.15f
        val minMovementDistance = minSide * 0.01f
        val mutualExclusionThreshold = minSide * 0.003f

        data class CandidateState(
            val trackId: Int,
            val box: RectF,
            val keypoints: List<com.example.roomxxx0102.data.model.Keypoint>,
            val score: Float,
            val isMoving: Boolean,
            val movementOk: Boolean,
            val upperCount: Int,
            val anklesBelow: Boolean,
            val integrityOk: Boolean,
            val shouldersTrusted: Boolean,
            val history: TrackedSubjectHistory,
            val wouldLock: Boolean
        )

        val candidates = ArrayList<CandidateState>()
        val matchedTrackerIds = HashSet<Int>()

        for (candidate in detections) {
            var bestMatchId = -1
            var minDistance = Float.MAX_VALUE
            val candidateCx = candidate.box.centerX()
            val candidateCy = candidate.box.centerY()

            for ((id, history) in activeTrackersMap) {
                if (id in matchedTrackerIds) continue
                val distance = kotlin.math.sqrt((candidateCx - history.centerX).pow(2) + (candidateCy - history.centerY).pow(2))
                if (distance < matchThreshold && distance < minDistance) {
                    minDistance = distance
                    bestMatchId = id
                }
            }

            val currentId: Int
            val history: TrackedSubjectHistory
            if (bestMatchId != -1) {
                currentId = bestMatchId
                history = activeTrackersMap[bestMatchId]!!
            } else {
                currentId = nextSubjectId++
                history = TrackedSubjectHistory(candidateCx, candidateCy)
                activeTrackersMap[currentId] = history
            }

            matchedTrackerIds.add(currentId)

            val moveDistance = kotlin.math.sqrt((candidateCx - history.centerX).pow(2) + (candidateCy - history.centerY).pow(2))
            val isMovingNow = moveDistance > minMovementDistance

            if (temporalAdvanced) {
                history.recentPositions.addLast(Pair(candidateCx, candidateCy))
                if (history.recentPositions.size > 3) {
                    history.recentPositions.removeFirst()
                }
                history.recentKeypoints.addLast(candidate.keypoints)
                if (history.recentKeypoints.size > 4) {
                    history.recentKeypoints.removeFirst()
                }
            }
            val movementOk = hasRecentMovement(history.recentPositions, mutualExclusionThreshold)
            if (temporalAdvanced && movementOk) {
                history.hasEverMoved = true
            }

            if (temporalAdvanced) {
                history.maxHistoricalScore = max(history.maxHistoricalScore, candidate.score)
            }
            val upperCount = countUpperBodyPoints(candidate.keypoints)
            val anklesBelow = areAnklesBelowUpperBody(candidate.keypoints)
            val integrityOk = upperCount >= 5 && anklesBelow
            val shouldersTrusted = isShouldersTrusted(candidate.keypoints)
            if (history.wasConfirmed) {
                if (temporalAdvanced) {
                    if (shouldersTrusted) {
                        history.lowShoulderFrames = 0
                    } else {
                        history.lowShoulderFrames += 1
                        if (history.lowShoulderFrames >= 10) {
                            history.wasConfirmed = false
                            history.lowShoulderFrames = 0
                            lastUnlockMessage = "unlock: ShoulderLow id=$currentId frames=10"
                            Log.i("RoomLockDiag", "tracker=simple ${lastUnlockMessage}")
                        }
                    }
                }
                if (
                    temporalAdvanced &&
                    !suppressStagnantUnlock &&
                    history.wasConfirmed &&
                    isPoseStagnant(history.recentKeypoints, 0.001f)
                ) {
                    history.wasConfirmed = false
                    lastUnlockMessage = "unlock: PoseStagnant id=$currentId window=4 tol=0.001"
                    Log.i("RoomLockDiag", "tracker=simple ${lastUnlockMessage}")
                }
            } else {
                history.lowShoulderFrames = 0
            }
            if (temporalAdvanced && !history.hasPassedIntegrityCheck && integrityOk) {
                history.hasPassedIntegrityCheck = true
            }

            if (temporalAdvanced) {
                history.centerX = candidateCx
                history.centerY = candidateCy
            }
            history.consecutiveMissingFrames = 0

            val wouldLock = shouldLock(
                history.maxHistoricalScore,
                candidate.score,
                movementOk,
                integrityOk,
                shouldersTrusted
            )

            candidates.add(
                CandidateState(
                    trackId = currentId,
                    box = candidate.box,
                    keypoints = candidate.keypoints,
                    score = candidate.score,
                    isMoving = isMovingNow,
                    movementOk = movementOk,
                    upperCount = upperCount,
                    anklesBelow = anklesBelow,
                    integrityOk = integrityOk,
                    shouldersTrusted = shouldersTrusted,
                    history = history,
                    wouldLock = wouldLock
                )
            )
        }

        val strongBoxes = candidates.filter { it.history.wasConfirmed }.map { it.box }
        val currentZones = strongBoxes.map { expandBox(it, 0.05f * minSide) }
        updateCooldownZones(currentZones)
        lastShieldZones = currentZones + cooldownZones.map { it.rect }

        val results = ArrayList<TrackResult>()
        for (candidate in candidates) {
            val inCurrent = intersectsAny(candidate.box, currentZones)
            val inCooldown = intersectsAny(candidate.box, cooldownZones.map { it.rect })
            val isBlocked = candidate.wouldLock && !candidate.history.wasConfirmed && (inCurrent || inCooldown)

            var isConfirmed = candidate.history.wasConfirmed
            var isShielded = false
            if (!candidate.history.wasConfirmed && candidate.wouldLock) {
                if (isBlocked) {
                    isShielded = true
                } else {
                    isConfirmed = true
                    candidate.history.wasConfirmed = true
                    val upperCount = candidate.upperCount
                    val upperOk = upperCount >= 5
                    val anklesBelow = candidate.anklesBelow
                    val shouldersTrusted = candidate.shouldersTrusted
                    val maxScore = candidate.history.maxHistoricalScore
                    val maxScoreOk = maxScore >= MIN_SCORE_FOR_LOCKING
                    val currentScoreOk = candidate.score >= 0.5f
                    val movedNow = candidate.isMoving
                    val movedOk = candidate.movementOk
                    val movedHistory = candidate.history.hasEverMoved
                    val integrityOk = candidate.integrityOk
                    val wouldLockNow = shouldLock(maxScore, candidate.score, movedOk, integrityOk, shouldersTrusted)
                    Log.d(
                        "lockAdd",
                        "local id=${candidate.trackId} score=${candidate.score} maxScore=$maxScore minScore=$MIN_SCORE_FOR_LOCKING maxScoreOk=$maxScoreOk currentScoreOk=$currentScoreOk movedNow=$movedNow movedOk=$movedOk movedHistory=$movedHistory integrity=$integrityOk upperCount=$upperCount upperOk=$upperOk anklesBelow=$anklesBelow shouldersTrusted=$shouldersTrusted inCurrent=$inCurrent inCooldown=$inCooldown isBlocked=$isBlocked wouldLock=$wouldLockNow"
                    )
                }
            }

            if (candidate.score > MIN_DISPLAY_SCORE_THRESHOLD || isConfirmed || isShielded) {
                results.add(
                    TrackResult(
                        trackId = candidate.trackId,
                        box = candidate.box,
                        keypoints = candidate.keypoints,
                        score = candidate.score,
                        isMoving = candidate.isMoving,
                        isConfirmed = isConfirmed,
                        isRemote = false,
                        isShielded = isShielded
                    )
                )
            }
        }

        if (temporalAdvanced) {
            decayMissingFrames(matchedTrackerIds)
        }
        return results
    }

    override fun getShieldZones(): List<RectF> = lastShieldZones

    override fun consumeUnlockMessage(): String? {
        val msg = lastUnlockMessage
        lastUnlockMessage = null
        return msg
    }

    private fun updateCooldownZones(currentZones: List<RectF>) {
        val refreshed = mutableSetOf<Int>()
        for (zone in currentZones) {
            var merged = false
            for (i in cooldownZones.indices) {
                if (RectF.intersects(cooldownZones[i].rect, zone)) {
                    cooldownZones[i].rect = unionRect(cooldownZones[i].rect, zone)
                    cooldownZones[i].ttl = 5
                    refreshed.add(i)
                    merged = true
                    break
                }
            }
            if (!merged) {
                cooldownZones.add(CooldownZone(RectF(zone), 5))
                refreshed.add(cooldownZones.size - 1)
            }
        }

        for (i in cooldownZones.indices.reversed()) {
            if (!refreshed.contains(i)) {
                cooldownZones[i].ttl -= 1
            }
            if (cooldownZones[i].ttl <= 0) {
                cooldownZones.removeAt(i)
            }
        }
    }

    private fun intersectsAny(box: RectF, zones: List<RectF>): Boolean {
        for (zone in zones) {
            if (RectF.intersects(zone, box)) return true
        }
        return false
    }

    private fun expandBox(box: RectF, padding: Float): RectF {
        return RectF(box.left - padding, box.top - padding, box.right + padding, box.bottom + padding)
    }

    private fun unionRect(a: RectF, b: RectF): RectF {
        return RectF(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    }

    private fun decayMissingFrames(matchedTrackerIds: Set<Int>) {
        val iterator = activeTrackersMap.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!matchedTrackerIds.contains(entry.key)) {
                entry.value.consecutiveMissingFrames++
                if (entry.value.consecutiveMissingFrames > MAX_MISSING_FRAMES_TOLERANCE) {
                    iterator.remove()
                }
            }
        }
    }

    private fun hasRecentMovement(
        recentPositions: ArrayDeque<Pair<Float, Float>>,
        threshold: Float
    ): Boolean {
        if (recentPositions.size < 3) return false
        val points = recentPositions.toList()
        for (i in 0 until points.size) {
            for (j in i + 1 until points.size) {
                val dx = points[i].first - points[j].first
                val dy = points[i].second - points[j].second
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < threshold) return false
            }
        }
        return true
    }

    private fun isPointPresent(point: com.example.roomxxx0102.data.model.Keypoint): Boolean {
        return point.x != 0f || point.y != 0f
    }

    private fun countUpperBodyPoints(keypoints: List<com.example.roomxxx0102.data.model.Keypoint>): Int {
        var count = 0
        val upperEnd = min(10, keypoints.size - 1)
        for (i in 0..upperEnd) {
            if (isPointPresent(keypoints[i])) {
                count += 1
            }
        }
        return count
    }

    private fun areAnklesBelowUpperBody(keypoints: List<com.example.roomxxx0102.data.model.Keypoint>): Boolean {
        if (keypoints.size <= 6) return false
        var maxUpperY: Float? = null
        val upperEnd = min(6, keypoints.size - 1)
        for (i in 0..upperEnd) {
            val kp = keypoints[i]
            if (!isPointPresent(kp)) continue
            maxUpperY = if (maxUpperY == null) kp.y else max(maxUpperY!!, kp.y)
        }
        var hasAnkle = false
        val ankleIndices = listOf(15, 16)
        for (idx in ankleIndices) {
            if (idx >= keypoints.size) continue
            val ankle = keypoints[idx]
            if (!isPointPresent(ankle)) continue
            hasAnkle = true
            if (maxUpperY == null) return false
            if (ankle.y <= maxUpperY!!) return false
        }
        return !hasAnkle || maxUpperY != null
    }

    private fun shouldLock(
        maxScore: Float,
        currentScore: Float,
        movementOk: Boolean,
        integrityOk: Boolean,
        shouldersTrusted: Boolean
    ): Boolean {
        return (maxScore >= MIN_SCORE_FOR_LOCKING) &&
            (currentScore >= 0.5f) &&
            movementOk &&
            integrityOk &&
            shouldersTrusted
    }

    private fun isShouldersTrusted(keypoints: List<com.example.roomxxx0102.data.model.Keypoint>): Boolean {
        if (keypoints.size <= 6) return false
        val left = keypoints[5]
        val right = keypoints[6]
        return left.conf >= POSE_HIGH_CONFIDENCE_THRESHOLD &&
            right.conf >= POSE_HIGH_CONFIDENCE_THRESHOLD
    }

    private fun checkPoseIntegrity(candidate: TrackDetection): Boolean {
        val upperCount = countUpperBodyPoints(candidate.keypoints)
        if (upperCount < 5) return false
        return areAnklesBelowUpperBody(candidate.keypoints)
    }
}








