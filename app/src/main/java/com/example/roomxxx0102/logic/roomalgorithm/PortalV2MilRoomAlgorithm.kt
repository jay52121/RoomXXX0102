package com.example.roomxxx0102.logic.roomalgorithm

import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceEstimatorParams
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTrackerRegistry

/**
 * Portal V2 + MIL 的历史实验基线。
 *
 * 仅用于与 ViTTrack 做同场景对照；房间人数/事件仍由 PortalV2RoomAlgorithm 内部的 Legacy 旁路提供。
 */
class PortalV2MilRoomAlgorithm(
    selectedPresenceVersionId: String?,
    presenceParams: PresenceEstimatorParams = PresenceEstimatorParams()
) : RoomAlgorithmEngine {
    private val presenceVersionId = PresenceAlgorithmRegistry.resolveVersionId(selectedPresenceVersionId)
    private val delegate = PortalV2RoomAlgorithm(
        selectedPresenceVersionId = selectedPresenceVersionId,
        presenceParams = presenceParams,
        visualTrackerId = PortalVisualTrackerRegistry.OPENCV_MIL_ID
    )

    override val algorithmId: String = RoomAlgorithmRegistry.PORTAL_V2_MIL_ID
    override val runtimeTag: String
        get() = "PORTAL_V2_MIL_BASELINE|${delegate.runtimeTag}"
    override val configurationKey: String =
        "$algorithmId|${PortalVisualTrackerRegistry.OPENCV_MIL_ID}|${RoomAlgorithmRegistry.LEGACY_PRESENCE_ID}|$presenceVersionId"

    override fun processFrame(input: RoomAlgorithmFrameInput): RoomAlgorithmFrameResult =
        delegate.processFrame(input)

    override fun reset() = delegate.reset()
}
