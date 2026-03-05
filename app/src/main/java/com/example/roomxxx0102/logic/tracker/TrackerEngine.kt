package com.example.roomxxx0102.logic.tracker

import android.graphics.RectF
import com.example.roomxxx0102.data.model.Keypoint

data class TrackDetection(
    val box: RectF,
    val keypoints: List<Keypoint>,
    val score: Float
)

data class TrackResult(
    val trackId: Int,
    val box: RectF,
    val keypoints: List<Keypoint>,
    val score: Float,
    val isMoving: Boolean,
    val isConfirmed: Boolean,
    val isRemote: Boolean = false,
    val isShielded: Boolean = false
)

interface TrackerEngine {
    fun reset()
    fun track(
        detections: List<TrackDetection>,
        frameWidth: Int,
        frameHeight: Int,
        temporalAdvanced: Boolean = true,
        suppressStagnantUnlock: Boolean = false
    ): List<TrackResult>
    fun getShieldZones(): List<RectF>
    fun consumeUnlockMessage(): String?
}
