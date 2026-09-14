package com.example.roomxxx0102.logic.roomalgorithm

import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmEngine
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceEstimatorParams
import com.example.roomxxx0102.logic.presence.PresenceKeypoint
import com.example.roomxxx0102.logic.presence.PresencePoint
import com.example.roomxxx0102.logic.presence.PresenceRect
import com.example.roomxxx0102.logic.presence.PresenceStrength
import com.example.roomxxx0102.logic.presence.PresenceTrackObservation

class LegacyPresenceRoomAlgorithm(
    selectedPresenceVersionId: String?,
    params: PresenceEstimatorParams = PresenceEstimatorParams()
) : RoomAlgorithmEngine {
    private val presenceVersionId = PresenceAlgorithmRegistry.resolveVersionId(selectedPresenceVersionId)
    private val delegate: PresenceAlgorithmEngine = PresenceAlgorithmRegistry.create(
        selectedId = selectedPresenceVersionId,
        params = params
    )

    override val algorithmId: String = RoomAlgorithmRegistry.LEGACY_PRESENCE_ID
    override val runtimeTag: String get() = delegate.runtimeTag
    override val configurationKey: String = "$algorithmId|$presenceVersionId"

    override fun processFrame(input: RoomAlgorithmFrameInput): RoomAlgorithmFrameResult {
        val observations = input.poses.map { pose -> pose.toPresenceObservation(input.timestampMs) }
        val result = delegate.processFrame(
            rooms = input.rooms,
            doors = input.doors,
            observations = observations,
            outsideMode = input.sceneInfo.outsideMode
        )
        return RoomAlgorithmFrameResult(
            observedCounts = result.observedCounts,
            roomCounts = result.presenceCounts,
            events = result.events,
            pendingDoorCounters = result.pendingDoorCounters,
            rejectedReasons = result.rejectedReasons,
            trackSwitchScores = result.trackSwitchScores,
            debugInfo = RoomAlgorithmDebugInfo(
                summary = result.rejectedReasons.firstOrNull() ?: "NO_DECISION",
                details = mapOf(
                    "algorithmId" to algorithmId,
                    "runtimeTag" to runtimeTag,
                    "frameSeq" to input.frameSeq.toString(),
                    "timestampMs" to input.timestampMs.toString(),
                    "imageSize" to "${input.imageWidth}x${input.imageHeight}"
                )
            )
        )
    }

    override fun reset() = delegate.reset()

    private fun PoseResult.toPresenceObservation(timestampMs: Long): PresenceTrackObservation {
        val normalizedBox = box
        return PresenceTrackObservation(
            trackId = id,
            landingPoint = PresencePoint(landingPoint.x.toDouble(), landingPoint.y.toDouble()),
            strength = toPresenceStrength(),
            timestampMs = timestampMs,
            groundConfidence = estimateGroundConfidence(),
            personBox = PresenceRect(
                left = minOf(normalizedBox.left, normalizedBox.right).toDouble(),
                top = minOf(normalizedBox.top, normalizedBox.bottom).toDouble(),
                right = maxOf(normalizedBox.left, normalizedBox.right).toDouble(),
                bottom = maxOf(normalizedBox.top, normalizedBox.bottom).toDouble()
            ),
            keypoints = keypoints.map { point ->
                PresenceKeypoint(point.x.toDouble(), point.y.toDouble(), point.conf.toDouble())
            }
        )
    }

    private fun PoseResult.toPresenceStrength(): PresenceStrength {
        if (isConfirmed) return PresenceStrength.CONFIRMED
        return if (hasTrustedShoulders()) PresenceStrength.STRONG else PresenceStrength.WEAK
    }

    private fun PoseResult.estimateGroundConfidence(): Double {
        if (keypoints.size < 17) return 0.25
        val leftAnkle = keypoints[15].conf
        val rightAnkle = keypoints[16].conf
        return when {
            leftAnkle >= 0.50f && rightAnkle >= 0.50f -> 1.00
            leftAnkle >= 0.50f || rightAnkle >= 0.50f -> 0.85
            leftAnkle >= 0.20f || rightAnkle >= 0.20f -> 0.65
            isConfirmed && hasTrustedShoulders() -> 0.50
            hasTrustedShoulders() -> 0.40
            else -> 0.25
        }
    }

    private fun PoseResult.hasTrustedShoulders(): Boolean {
        if (keypoints.size <= 6) return false
        return keypoints[5].conf >= 0.7f && keypoints[6].conf >= 0.7f
    }
}
