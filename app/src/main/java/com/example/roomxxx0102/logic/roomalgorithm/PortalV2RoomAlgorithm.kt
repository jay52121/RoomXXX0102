package com.example.roomxxx0102.logic.roomalgorithm

import android.util.Log
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.logic.presence.PresenceDoorSnapshot
import com.example.roomxxx0102.logic.presence.PresenceEstimatorParams
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTrack
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTracker
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTrackerFrameInput
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTrackerRegistry
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Portal V2 第一阶段实验版。
 *
 * 正式房间人数/切换事件仍完全沿用 Legacy Presence；新增的 Portal Visual Tracker 只作为旁路视觉证据，
 * 用于验证 YOLO 在门口变弱或消失后是否还能继续追住同一个人。
 */
class PortalV2RoomAlgorithm(
    selectedPresenceVersionId: String?,
    presenceParams: PresenceEstimatorParams = PresenceEstimatorParams(),
    visualTrackerId: String? = null
) : RoomAlgorithmEngine {
    private val legacy = LegacyPresenceRoomAlgorithm(selectedPresenceVersionId, presenceParams)
    private val resolvedVisualTrackerId = PortalVisualTrackerRegistry.resolveTrackerId(visualTrackerId)
    private val visualTracker: PortalVisualTracker = PortalVisualTrackerRegistry.create(resolvedVisualTrackerId)

    private var visualEpisodeActive = false
    private var visualEpisodeFrames = 0
    private var visualMissingFrames = 0
    private var detectorFarFrames = 0
    private var lastVisualLogState: String? = null

    override val algorithmId: String = RoomAlgorithmRegistry.PORTAL_V2_ID
    override val runtimeTag: String
        get() = "PORTAL_V2|tracker=$resolvedVisualTrackerId|legacy=${legacy.runtimeTag}"
    override val configurationKey: String = "$algorithmId|$resolvedVisualTrackerId|${legacy.configurationKey}"

    override fun processFrame(input: RoomAlgorithmFrameInput): RoomAlgorithmFrameResult {
        val legacyResult = legacy.processFrame(input)
        val seed = findPortalSeed(input)
        val preferredTrackId = if (!visualEpisodeActive) seed?.pose?.id else null

        val visualTracks = visualTracker.track(
            PortalVisualTrackerFrameInput(
                bitmap = input.bitmap,
                timestampMs = input.timestampMs,
                frameSeq = input.frameSeq,
                poses = input.poses,
                imageWidth = input.imageWidth,
                imageHeight = input.imageHeight,
                allowInitialization = !visualEpisodeActive && seed != null,
                preferredTrackId = preferredTrackId
            )
        )

        if (!visualEpisodeActive && visualTracks.isNotEmpty()) {
            visualEpisodeActive = true
            visualEpisodeFrames = 0
            visualMissingFrames = 0
            detectorFarFrames = 0
        }

        var visibleTrack: PortalVisualTrack? = visualTracks.firstOrNull()
        if (visualEpisodeActive) {
            visualEpisodeFrames += 1
            if (visibleTrack == null) {
                visualMissingFrames += 1
            } else {
                visualMissingFrames = 0
            }

            val trackedId = visibleTrack?.trackId
            val detectorPose = trackedId?.let { id -> input.poses.firstOrNull { it.id == id } }
            if (detectorPose != null && !isPoseNearAnyPortal(detectorPose, input.doors, multiplier = 2.0)) {
                detectorFarFrames += 1
            } else {
                detectorFarFrames = 0
            }

            if (visualMissingFrames >= MAX_VISUAL_MISSING_FRAMES ||
                detectorFarFrames >= RETURNED_SOURCE_FAR_FRAMES ||
                visualEpisodeFrames >= MAX_VISUAL_EPISODE_FRAMES
            ) {
                visualTracker.reset()
                visualEpisodeActive = false
                visualEpisodeFrames = 0
                visualMissingFrames = 0
                detectorFarFrames = 0
                visibleTrack = null
            }
        }

        val visualState = visibleTrack?.state?.name ?: if (visualEpisodeActive) "MISSING" else "IDLE"
        val detectorVisible = visibleTrack?.trackId?.let { id -> input.poses.any { it.id == id } } ?: false
        val visualSummary = buildVisualSummary(
            frameSeq = input.frameSeq,
            track = visibleTrack,
            state = visualState,
            detectorVisible = detectorVisible,
            seed = seed
        )
        maybeLogVisualState(input, visualState, detectorVisible, visibleTrack, seed)

        val debugDetails = linkedMapOf<String, String>()
        debugDetails.putAll(legacyResult.debugInfo.details)
        debugDetails["roomAlgorithm"] = algorithmId
        debugDetails["visualTracker"] = resolvedVisualTrackerId
        debugDetails["visualState"] = visualState
        debugDetails["visualTrackId"] = visibleTrack?.trackId?.toString() ?: "-"
        debugDetails["visualBounds"] = visibleTrack?.bounds?.let(::formatBounds) ?: "-"
        debugDetails["visualNote"] = visibleTrack?.note ?: "-"
        debugDetails["visualEpisodeFrames"] = visualEpisodeFrames.toString()

        // 当前 UI 的判定面板取 rejectedReasons 第一项；仅在 Legacy 本帧没有判定信息时展示旁路状态。
        val rejectedReasons = if (legacyResult.rejectedReasons.isEmpty()) {
            listOf(visualSummary)
        } else {
            legacyResult.rejectedReasons + visualSummary
        }

        return legacyResult.copy(
            rejectedReasons = rejectedReasons,
            debugInfo = RoomAlgorithmDebugInfo(
                summary = visualSummary,
                details = debugDetails
            )
        )
    }

    override fun reset() {
        legacy.reset()
        visualTracker.reset()
        visualEpisodeActive = false
        visualEpisodeFrames = 0
        visualMissingFrames = 0
        detectorFarFrames = 0
        lastVisualLogState = null
    }

    private data class PortalSeed(
        val pose: PoseResult,
        val doorId: String,
        val distance: Double,
        val threshold: Double
    )

    private fun findPortalSeed(input: RoomAlgorithmFrameInput): PortalSeed? {
        if (input.bitmap == null || input.doors.isEmpty()) return null
        var best: PortalSeed? = null
        for (pose in input.poses) {
            if (!pose.isConfirmed || pose.isShielded) continue
            val boxHeight = absHeight(pose).coerceAtLeast(0.01)
            val threshold = (boxHeight * PORTAL_NEAR_BODY_HEIGHT_RATIO)
                .coerceIn(PORTAL_NEAR_MIN, PORTAL_NEAR_MAX)
            val anchorX = pose.box.centerX().toDouble()
            val anchorY = max(pose.box.top, pose.box.bottom).toDouble()
            for (door in input.doors) {
                val distance = pointToSegmentDistance(anchorX, anchorY, door)
                if (distance > threshold) continue
                if (best == null || distance < best.distance) {
                    best = PortalSeed(pose, door.doorId, distance, threshold)
                }
            }
        }
        return best
    }

    private fun isPoseNearAnyPortal(
        pose: PoseResult,
        doors: List<PresenceDoorSnapshot>,
        multiplier: Double
    ): Boolean {
        if (doors.isEmpty()) return false
        val threshold = ((absHeight(pose).coerceAtLeast(0.01) * PORTAL_NEAR_BODY_HEIGHT_RATIO)
            .coerceIn(PORTAL_NEAR_MIN, PORTAL_NEAR_MAX) * multiplier)
            .coerceAtMost(PORTAL_FAR_MAX)
        val x = pose.box.centerX().toDouble()
        val y = max(pose.box.top, pose.box.bottom).toDouble()
        return doors.any { pointToSegmentDistance(x, y, it) <= threshold }
    }

    private fun pointToSegmentDistance(x: Double, y: Double, door: PresenceDoorSnapshot): Double {
        val ax = door.a.x
        val ay = door.a.y
        val bx = door.b.x
        val by = door.b.y
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-12) return hypot(x - ax, y - ay)
        val t = (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return hypot(x - px, y - py)
    }

    private fun absHeight(pose: PoseResult): Double {
        return kotlin.math.abs(pose.box.bottom - pose.box.top).toDouble()
    }

    private fun buildVisualSummary(
        frameSeq: Long,
        track: PortalVisualTrack?,
        state: String,
        detectorVisible: Boolean,
        seed: PortalSeed?
    ): String {
        return buildString {
            append("PORTAL_V2_VISUAL")
            append(" tracker=").append(resolvedVisualTrackerId)
            append(" state=").append(state)
            append(" track=").append(track?.trackId ?: "-")
            append(" detector=").append(detectorVisible)
            append(" box=").append(track?.bounds?.let(::formatBounds) ?: "-")
            append(" updateMs=").append(track?.updateTimeMs ?: -1L)
            append(" seedDoor=").append(seed?.doorId ?: "-")
            append(" frame=").append(frameSeq)
        }
    }

    private fun maybeLogVisualState(
        input: RoomAlgorithmFrameInput,
        state: String,
        detectorVisible: Boolean,
        track: PortalVisualTrack?,
        seed: PortalSeed?
    ) {
        val stateKey = "$state|${track?.trackId ?: -1}|$detectorVisible"
        val shouldLog = stateKey != lastVisualLogState || input.frameSeq % LOG_EVERY_N_FRAMES == 0L
        if (!shouldLog) return
        lastVisualLogState = stateKey
        Log.i(
            TAG,
            "frame=${input.frameSeq} ts=${input.timestampMs} tracker=$resolvedVisualTrackerId " +
                "state=$state track=${track?.trackId ?: -1} detector=$detectorVisible " +
                "box=${track?.bounds?.let(::formatBounds) ?: "-"} note=${track?.note ?: "-"} " +
                "seedDoor=${seed?.doorId ?: "-"} seedDist=${seed?.distance?.let(::format) ?: "-"}"
        )
    }

    private fun formatBounds(bounds: android.graphics.RectF): String {
        return "${format(bounds.left.toDouble())},${format(bounds.top.toDouble())}," +
            "${format(bounds.right.toDouble())},${format(bounds.bottom.toDouble())}"
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    companion object {
        private const val TAG = "PortalV2Visual"
        private const val PORTAL_NEAR_BODY_HEIGHT_RATIO = 0.20
        private const val PORTAL_NEAR_MIN = 0.035
        private const val PORTAL_NEAR_MAX = 0.080
        private const val PORTAL_FAR_MAX = 0.16
        private const val MAX_VISUAL_MISSING_FRAMES = 3
        private const val RETURNED_SOURCE_FAR_FRAMES = 4
        private const val MAX_VISUAL_EPISODE_FRAMES = 45
        private const val LOG_EVERY_N_FRAMES = 10L
    }
}
