package com.example.roomxxx0102.logic.pointing

import android.graphics.PointF
import android.graphics.RectF

data class DevicePointingTarget(
    val id: String,
    val hotspot: PointF,
    val polygon: List<PointF>
)

data class DevicePreparedTarget(
    val id: String,
    val hotspotPx: PointF,
    val polygonPx: List<PointF>,
    val boundsPx: RectF,
    val shortEdgePx: Float,
    val hotspotRadiusPx: Float,
    val polygonAssistRadiusPx: Float,
    val nearFieldExemptDistancePx: Float,
    val backwardTolerancePx: Float,
    val penetrationNormLengthPx: Float
)

data class DeviceFrameScore(
    val deviceId: String,
    val hotspotScore: Float,
    val polygonScore: Float,
    val geometricScore: Float,
    val totalScore: Float
)

data class DeviceFrameEvaluation(
    val timestampMs: Long,
    val rayConfidence: Float,
    val frameScores: List<DeviceFrameScore>
)

data class DeviceWindowDeviceStats(
    val deviceId: String,
    var weightedAverageScore: Float = 0f,
    var peakFrameScore: Float = 0f,
    var weightedHitRatio: Float = 0f,
    var weightedLeadRatio: Float = 0f,
    var finalScore: Float = 0f
)

data class DeviceWindowResult(
    val confidenceStatus: PointingConfidenceStatus,
    val winnerId: String?,
    val secondId: String?,
    val winnerStats: DeviceWindowDeviceStats?,
    val secondStats: DeviceWindowDeviceStats?,
    val finalLeadRatio: Float,
    val dynamicFinalThreshold: Float,
    val weightedAverageRayConfidence: Float,
    val sortedStats: List<DeviceWindowDeviceStats>
)
