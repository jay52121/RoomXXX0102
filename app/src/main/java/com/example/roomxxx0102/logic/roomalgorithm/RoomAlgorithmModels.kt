package com.example.roomxxx0102.logic.roomalgorithm

import android.graphics.Bitmap
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.logic.presence.PresenceDoorSnapshot
import com.example.roomxxx0102.logic.presence.PresenceOutsideMode
import com.example.roomxxx0102.logic.presence.PresenceRoomSnapshot
import com.example.roomxxx0102.logic.presence.PresenceSwitchEvent
import com.example.roomxxx0102.logic.presence.PresenceTrackSwitchScore

data class RoomAlgorithmSceneInfo(
    val isVideoPlayback: Boolean,
    val outsideMode: PresenceOutsideMode = PresenceOutsideMode.INVISIBLE
)

data class RoomAlgorithmFrameInput(
    val bitmap: Bitmap?,
    val timestampMs: Long,
    val frameSeq: Long,
    val poses: List<PoseResult>,
    val rooms: List<PresenceRoomSnapshot>,
    val doors: List<PresenceDoorSnapshot>,
    val imageWidth: Int,
    val imageHeight: Int,
    val sceneInfo: RoomAlgorithmSceneInfo
)

data class RoomAlgorithmDebugInfo(
    val summary: String,
    val details: Map<String, String> = emptyMap()
)

data class RoomAlgorithmFrameResult(
    val observedCounts: Map<String, Int>,
    val roomCounts: Map<String, Int>,
    val events: List<PresenceSwitchEvent>,
    val pendingDoorCounters: Map<String, Int>,
    val rejectedReasons: List<String>,
    val trackSwitchScores: Map<Int, PresenceTrackSwitchScore> = emptyMap(),
    val debugInfo: RoomAlgorithmDebugInfo
)
