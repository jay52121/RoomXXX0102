package com.example.roomxxx0102.logic.pointing

data class DeviceFrameDebugMetrics(
    val deviceId: String,
    val forwardProjectionPx: Float,
    val hotspotEuclideanDistancePx: Float,
    val hotspotDirectionCos: Float,
    val polygonDirectionAlignment: Float,
    val nearFieldDirectionAlignment: Float,
    val nearFieldSupportFactor: Float,
    val effectiveNearFieldCropDistancePx: Float,
    val hotspotScore: Float,
    val polygonScore: Float,
    val geometricScore: Float,
    val totalScore: Float
)

data class DeviceWindowDebugMetrics(
    val weightedAverageRayConfidence: Float,
    val dynamicFinalThreshold: Float,
    val winnerDeviceId: String?,
    val secondDeviceId: String?,
    val finalLeadRatio: Float,
    val confidenceStatus: PointingConfidenceStatus,
    val deviceStats: List<DeviceWindowDeviceStats>
)
