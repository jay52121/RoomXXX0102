package com.example.roomxxx0102.logic.tracker

import android.graphics.RectF
import android.util.Log
import com.example.roomxxx0102.data.model.Keypoint
import com.example.roomxxx0102.data.model.POSE_HIGH_CONFIDENCE_THRESHOLD
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class RemoteByteTrackEngine(
    private val fallback: TrackerEngine,
    private val baseUrl: String = "http://192.168.50.161:8000",
    private val cameraId: String = "livingroom_phone"
) : TrackerEngine {
    private data class TrackState(
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

    private data class PendingRequest(
        val detections: List<TrackDetection>,
        val frameWidth: Int,
        val frameHeight: Int,
        val temporalAdvanced: Boolean,
        val suppressStagnantUnlock: Boolean
    )

    private data class CooldownZone(var rect: RectF, var ttl: Int)

    companion object {
        private const val TAG = "ByteTrack"
        private const val MIN_SCORE_FOR_LOCKING = 0.6f
        private const val MAX_MISSING_FRAMES_TOLERANCE = 60
        private const val FAIL_COOLDOWN_MS = 3000L
    }

    private val client = TrackClient(baseUrl, cameraId)
    private val trackStates = ConcurrentHashMap<Int, TrackState>()
    private val latestTracks = AtomicReference<List<TrackResult>>(emptyList())
    private val inFlight = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0L)
    private val pendingRequest = AtomicReference<PendingRequest?>(null)
    private val cooldownZones = mutableListOf<CooldownZone>()
    private var lastShieldZones: List<RectF> = emptyList()
    private var lastUnlockMessage: String? = null
    private var emptyDetectionsStreak = 0
    private var remoteDisabledUntil = 0L

    override fun reset() {
        trackStates.clear()
        emptyDetectionsStreak = 0
        latestTracks.set(emptyList())
        pendingRequest.set(null)
        frameCounter.set(0L)
        cooldownZones.clear()
        lastShieldZones = emptyList()
        lastUnlockMessage = null
        fallback.reset()
        client.enqueueReset { ok ->
            if (!ok) {
                Log.w(TAG, "reset failed, fallback cooldown")
                remoteDisabledUntil = System.currentTimeMillis() + FAIL_COOLDOWN_MS
            }
        }
    }

    override fun track(
        detections: List<TrackDetection>,
        frameWidth: Int,
        frameHeight: Int,
        temporalAdvanced: Boolean,
        suppressStagnantUnlock: Boolean
    ): List<TrackResult> {
        val now = System.currentTimeMillis()
        if (baseUrl.isBlank() || now < remoteDisabledUntil) {
            if (now < remoteDisabledUntil) {
                Log.w(TAG, "remote disabled, use fallback")
            }
            return fallback.track(
                detections,
                frameWidth,
                frameHeight,
                temporalAdvanced,
                suppressStagnantUnlock
            )
        }

        val cached = latestTracks.get()
        if (detections.isEmpty()) {
            emptyDetectionsStreak += 1
            if (emptyDetectionsStreak > 2) {
                latestTracks.set(emptyList())
                return emptyList()
            }
        } else {
            emptyDetectionsStreak = 0
        }
        if (inFlight.compareAndSet(false, true)) {
            sendTrackRequest(
                PendingRequest(
                    detections,
                    frameWidth,
                    frameHeight,
                    temporalAdvanced,
                    suppressStagnantUnlock
                )
            )
        } else {
            pendingRequest.set(
                PendingRequest(
                    detections,
                    frameWidth,
                    frameHeight,
                    temporalAdvanced,
                    suppressStagnantUnlock
                )
            )
        }

        return if (cached.isNotEmpty()) {
            cached
        } else {
            fallback.track(
                detections,
                frameWidth,
                frameHeight,
                temporalAdvanced,
                suppressStagnantUnlock
            )
        }
    }

    private fun sendTrackRequest(pending: PendingRequest) {
        val frameId = frameCounter.incrementAndGet()
        Log.d(TAG, "request in-flight: frame=$frameId")
        client.enqueueTrack(
            frameId,
            pending.detections,
            pending.frameWidth,
            pending.frameHeight
        ) { response ->
            if (response == null) {
                Log.w(TAG, "request failed: frame=$frameId")
                remoteDisabledUntil = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                inFlight.set(false)
                pendingRequest.getAndSet(null)
                return@enqueueTrack
            }
            val tracks = buildTrackResults(response.tracks, pending.detections)
            val updated = applyTrackingState(
                tracks,
                pending.frameWidth,
                pending.frameHeight,
                pending.temporalAdvanced,
                pending.suppressStagnantUnlock
            )
            latestTracks.set(updated)
            Log.d(TAG, "request done: frame=$frameId tracks=${updated.size}")
            inFlight.set(false)
            val next = pendingRequest.getAndSet(null)
            if (next != null && System.currentTimeMillis() >= remoteDisabledUntil) {
                if (inFlight.compareAndSet(false, true)) {
                    sendTrackRequest(next)
                }
            }
        }
    }

    private fun buildTrackResults(
        tracks: List<TrackOut>,
        detections: List<TrackDetection>
    ): List<TrackResult> {
        val results = ArrayList<TrackResult>(tracks.size)
        for (track in tracks) {
            if (track.xyxy.size < 4) continue
            val left = track.xyxy[0]
            val top = track.xyxy[1]
            val right = track.xyxy[2]
            val bottom = track.xyxy[3]
            val matched = findBestMatch(left, top, right, bottom, detections)
            val keypoints = matched?.keypoints ?: emptyList()
            val score = if (track.conf > 0f) track.conf else matched?.score ?: 0f
            results.add(
                TrackResult(
                    trackId = track.trackId,
                    box = RectF(left, top, right, bottom),
                    keypoints = keypoints,
                    score = score,
                    isMoving = false,
                    isConfirmed = false,
                    isRemote = true
                )
            )
        }
        return results
    }


    private fun isPoseStagnant(
        recent: ArrayDeque<List<Keypoint>>,
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

    private fun applyTrackingState(
        tracks: List<TrackResult>,
        frameWidth: Int,
        frameHeight: Int,
        temporalAdvanced: Boolean,
        suppressStagnantUnlock: Boolean
    ): List<TrackResult> {
        val minSide = min(frameWidth, frameHeight).coerceAtLeast(1).toFloat()
        if (tracks.isEmpty()) {
            updateCooldownZones(emptyList())
            lastShieldZones = cooldownZones.map { it.rect }
            if (temporalAdvanced) {
                decayMissingFrames(emptySet())
            }
            return emptyList()
        }

        val minMovementDistance = minSide * 0.01f
        val mutualExclusionThreshold = minSide * 0.003f

        data class CandidateState(
            val trackId: Int,
            val box: RectF,
            val keypoints: List<Keypoint>,
            val score: Float,
            val history: TrackState,
            val isMoving: Boolean,
            val movementOk: Boolean,
            val upperCount: Int,
            val anklesBelow: Boolean,
            val integrityOk: Boolean,
            val shouldersTrusted: Boolean,
            val wouldLock: Boolean
        )

        val candidates = ArrayList<CandidateState>(tracks.size)
        val seenTrackIds = HashSet<Int>()

        for (track in tracks) {
            val trackId = track.trackId
            val cx = track.box.centerX()
            val cy = track.box.centerY()
            val state = trackStates.getOrPut(trackId) { TrackState(cx, cy) }

            val moveDistance = sqrt((cx - state.centerX).pow(2) + (cy - state.centerY).pow(2))
            val isMoving = moveDistance > minMovementDistance

            if (temporalAdvanced) {
                state.recentPositions.addLast(Pair(cx, cy))
                if (state.recentPositions.size > 3) {
                    state.recentPositions.removeFirst()
                }
                state.recentKeypoints.addLast(track.keypoints)
                if (state.recentKeypoints.size > 4) {
                    state.recentKeypoints.removeFirst()
                }
            }
            val movementOk = hasRecentMovement(state.recentPositions, mutualExclusionThreshold)
            if (temporalAdvanced && movementOk) {
                state.hasEverMoved = true
            }

            if (temporalAdvanced) {
                state.maxHistoricalScore = max(state.maxHistoricalScore, track.score)
            }
            val upperCount = countUpperBodyPoints(track.keypoints)
            val anklesBelow = areAnklesBelowUpperBody(track.keypoints)
            val integrityOk = upperCount >= 5 && anklesBelow
            val shouldersTrusted = isShouldersTrusted(track.keypoints)

            if (state.wasConfirmed) {
                if (temporalAdvanced) {
                    if (shouldersTrusted) {
                        state.lowShoulderFrames = 0
                    } else {
                        state.lowShoulderFrames += 1
                        if (state.lowShoulderFrames >= 10) {
                            state.wasConfirmed = false
                            state.lowShoulderFrames = 0
                            lastUnlockMessage = "unlock: ShoulderLow id=$trackId frames=10"
                            Log.i("RoomLockDiag", "tracker=remote ${lastUnlockMessage}")
                        }
                    }
                }
                if (
                    temporalAdvanced &&
                    !suppressStagnantUnlock &&
                    state.wasConfirmed &&
                    isPoseStagnant(state.recentKeypoints, 0.001f)
                ) {
                    state.wasConfirmed = false
                    lastUnlockMessage = "unlock: PoseStagnant id=$trackId window=4 tol=0.001"
                    Log.i("RoomLockDiag", "tracker=remote ${lastUnlockMessage}")
                }
            } else {
                state.lowShoulderFrames = 0
            }

            if (temporalAdvanced && !state.hasPassedIntegrityCheck && integrityOk) {
                state.hasPassedIntegrityCheck = true
            }

            if (temporalAdvanced) {
                state.centerX = cx
                state.centerY = cy
            }
            state.consecutiveMissingFrames = 0
            seenTrackIds.add(trackId)

            val wouldLock = shouldLock(
                state.maxHistoricalScore,
                track.score,
                movementOk,
                integrityOk,
                shouldersTrusted
            )

            candidates.add(
                CandidateState(
                    trackId = trackId,
                    box = track.box,
                    keypoints = track.keypoints,
                    score = track.score,
                    history = state,
                    isMoving = isMoving,
                    movementOk = movementOk,
                    upperCount = upperCount,
                    anklesBelow = anklesBelow,
                    integrityOk = integrityOk,
                    shouldersTrusted = shouldersTrusted,
                    wouldLock = wouldLock
                )
            )
        }

        val strongBoxes = candidates.filter { it.history.wasConfirmed }.map { it.box }
        val currentZones = strongBoxes.map { expandBox(it, 0.05f * minSide) }
        updateCooldownZones(currentZones)
        lastShieldZones = currentZones + cooldownZones.map { it.rect }

        val results = ArrayList<TrackResult>(candidates.size)
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
                        "remote id=${candidate.trackId} score=${candidate.score} maxScore=$maxScore minScore=$MIN_SCORE_FOR_LOCKING maxScoreOk=$maxScoreOk currentScoreOk=$currentScoreOk movedNow=$movedNow movedOk=$movedOk movedHistory=$movedHistory integrity=$integrityOk upperCount=$upperCount upperOk=$upperOk anklesBelow=$anklesBelow shouldersTrusted=$shouldersTrusted inCurrent=$inCurrent inCooldown=$inCooldown isBlocked=$isBlocked wouldLock=$wouldLockNow"
                    )
                }
            }

            results.add(
                TrackResult(
                    trackId = candidate.trackId,
                    box = candidate.box,
                    keypoints = candidate.keypoints,
                    score = candidate.score,
                    isMoving = candidate.isMoving,
                    isConfirmed = isConfirmed,
                    isRemote = true,
                    isShielded = isShielded
                )
            )
        }

        if (temporalAdvanced) {
            decayMissingFrames(seenTrackIds)
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

    private fun decayMissingFrames(seenTrackIds: Set<Int>) {
        val iterator = trackStates.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!seenTrackIds.contains(entry.key)) {
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
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < threshold) return false
            }
        }
        return true
    }

    private fun isPointPresent(point: Keypoint): Boolean {
        return point.x != 0f || point.y != 0f
    }

    private fun countUpperBodyPoints(keypoints: List<Keypoint>): Int {
        var count = 0
        val upperEnd = min(10, keypoints.size - 1)
        for (i in 0..upperEnd) {
            if (isPointPresent(keypoints[i])) {
                count += 1
            }
        }
        return count
    }

    private fun areAnklesBelowUpperBody(keypoints: List<Keypoint>): Boolean {
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

    private fun isShouldersTrusted(keypoints: List<Keypoint>): Boolean {
        if (keypoints.size <= 6) return false
        val left = keypoints[5]
        val right = keypoints[6]
        return left.conf >= POSE_HIGH_CONFIDENCE_THRESHOLD &&
            right.conf >= POSE_HIGH_CONFIDENCE_THRESHOLD
    }

    private fun checkPoseIntegrity(keypoints: List<Keypoint>): Boolean {
        val upperCount = countUpperBodyPoints(keypoints)
        if (upperCount < 5) return false
        return areAnklesBelowUpperBody(keypoints)
    }

    private fun findBestMatch(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        detections: List<TrackDetection>
    ): TrackDetection? {
        var best: TrackDetection? = null
        var bestIou = 0f
        for (det in detections) {
            val iou = iou(left, top, right, bottom, det.box)
            if (iou > bestIou) {
                bestIou = iou
                best = det
            }
        }
        return if (bestIou >= 0.3f) best else null
    }

    private fun iou(left: Float, top: Float, right: Float, bottom: Float, other: RectF): Float {
        val interLeft = max(left, other.left)
        val interTop = max(top, other.top)
        val interRight = min(right, other.right)
        val interBottom = min(bottom, other.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val unionArea = (right - left) * (bottom - top) + other.width() * other.height() - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}
