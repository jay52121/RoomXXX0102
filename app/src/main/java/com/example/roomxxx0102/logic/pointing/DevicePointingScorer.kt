package com.example.roomxxx0102.logic.pointing

import android.graphics.PointF
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class DevicePointingScorer(
    private val config: DevicePointingScoringConfig = DevicePointingScoringConfig()
) {
    fun prepareTargets(
        targets: List<DevicePointingTarget>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DevicePreparedTarget> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        return targets.mapNotNull { target ->
            if (target.polygon.size != 4) return@mapNotNull null
            val hotspotPx = DevicePointingGeometry.point(
                target.hotspot.x * imageWidth,
                target.hotspot.y * imageHeight
            )
            val polygonPx = target.polygon.map { point ->
                DevicePointingGeometry.point(point.x * imageWidth, point.y * imageHeight)
            }
            val edgeA = DevicePointingGeometry.distance(polygonPx[0], polygonPx[1])
            val edgeB = DevicePointingGeometry.distance(polygonPx[1], polygonPx[2])
            val shortEdgePx = min(edgeA, edgeB).coerceAtLeast(1f)
            val hotspotRadiusPx = DevicePointingGeometry.clamp(
                config.hotspotRadiusScale * shortEdgePx,
                config.hotspotRadiusMinPx,
                config.hotspotRadiusMaxPx
            )
            val polygonAssistRadiusPx = DevicePointingGeometry.clamp(
                config.polygonAssistRadiusScale * shortEdgePx,
                config.polygonAssistRadiusMinPx,
                config.polygonAssistRadiusMaxPx
            )
            val baseNearFieldCropDistancePx = DevicePointingGeometry.clamp(
                config.baseNearFieldCropScale * shortEdgePx,
                config.baseNearFieldCropMinPx,
                config.baseNearFieldCropMaxPx
            )
            val absoluteNearExemptDistancePx = DevicePointingGeometry.clamp(
                config.absoluteNearExemptScale * shortEdgePx,
                config.absoluteNearExemptMinPx,
                config.absoluteNearExemptMaxPx
            )
            DevicePreparedTarget(
                id = target.id,
                hotspotPx = hotspotPx,
                polygonPx = polygonPx,
                boundsPx = DevicePointingGeometry.polygonBounds(polygonPx),
                shortEdgePx = shortEdgePx,
                hotspotRadiusPx = hotspotRadiusPx,
                polygonAssistRadiusPx = polygonAssistRadiusPx,
                nearFieldExemptDistancePx = config.nearFieldExemptDistanceScale * hotspotRadiusPx,
                backwardTolerancePx = config.backwardToleranceScale * hotspotRadiusPx,
                penetrationNormLengthPx = config.penetrationNormLengthScale * shortEdgePx,
                baseNearFieldCropDistancePx = baseNearFieldCropDistancePx,
                absoluteNearExemptDistancePx = absoluteNearExemptDistancePx,
                nearFieldDeviceDistanceThresholdPx = max(
                    config.nearFieldDeviceDistanceMinPx,
                    config.nearFieldDeviceDistanceScale * hotspotRadiusPx
                )
            )
        }
    }

    fun buildFrameEvaluation(
        timestampMs: Long,
        rayOrigin: PointF,
        rayDirection: PointF,
        rayConfidence: Float,
        targets: List<DevicePreparedTarget>
    ): DeviceFrameEvaluation {
        val normalizedDirection = DevicePointingGeometry.normalize(rayDirection)
        val usableConfidenceFactor = usableConfidenceFactor(rayConfidence)
        val frameScores = targets.map { target ->
            scoreTarget(
                rayOrigin = rayOrigin,
                rayDirection = normalizedDirection,
                rayConfidence = rayConfidence,
                target = target
            )
        }
        return DeviceFrameEvaluation(
            timestampMs = timestampMs,
            rayConfidence = rayConfidence,
            usableConfidenceFactor = usableConfidenceFactor,
            frameScores = frameScores
        )
    }

    fun usableConfidenceFactor(rayConfidence: Float): Float {
        return DevicePointingGeometry.clamp(
            (rayConfidence - config.minimumUsableConfidence) / (1f - config.minimumUsableConfidence),
            0f,
            1f
        )
    }

    fun logFrameScores(frameIndex: Int, frameEvaluation: DeviceFrameEvaluation) {
        val confText = format(frameEvaluation.rayConfidence)
        for (score in frameEvaluation.frameScores.sortedByDescending { it.totalScore }) {
            val m = score.debugMetrics
            logInfo(
                "DevicePointingJudge",
                "DEVICE_POINTING|FRAME|index=$frameIndex|device=${score.deviceId}|conf=$confText" +
                    "|forward=${format(m.forwardProjectionPx)}|dist=${format(m.hotspotEuclideanDistancePx)}" +
                    "|cos=${format(m.hotspotDirectionCos)}|polyDir=${format(m.polygonDirectionAlignment)}" +
                    "|nearDir=${format(m.nearFieldDirectionAlignment)}|nearSupport=${format(m.nearFieldSupportFactor)}" +
                    "|crop=${format(m.effectiveNearFieldCropDistancePx)}|hot=${format(m.hotspotScore)}" +
                    "|poly=${format(m.polygonScore)}|geom=${format(m.geometricScore)}|total=${format(m.totalScore)}"
            )
        }
    }

    private fun scoreTarget(
        rayOrigin: PointF,
        rayDirection: PointF,
        rayConfidence: Float,
        target: DevicePreparedTarget
    ): DeviceFrameScore {
        val hotspotRelative = DevicePointingGeometry.subtract(target.hotspotPx, rayOrigin)
        val hotspotEuclideanDistancePx = DevicePointingGeometry.distance(target.hotspotPx, rayOrigin)
        val hotspotDirectionCos: Float
        val polygonDirectionAlignment: Float
        val nearFieldDirectionAlignment: Float
        if (hotspotEuclideanDistancePx <= target.absoluteNearExemptDistancePx) {
            hotspotDirectionCos = 1f
            polygonDirectionAlignment = 1f
            nearFieldDirectionAlignment = 1f
        } else {
            val hotspotDirectionUnit = DevicePointingGeometry.normalize(hotspotRelative)
            hotspotDirectionCos = DevicePointingGeometry.dot(hotspotDirectionUnit, rayDirection)
            polygonDirectionAlignment = DevicePointingGeometry.clamp(
                (hotspotDirectionCos - config.polygonDirectionCosFloor) /
                    (1f - config.polygonDirectionCosFloor),
                0f,
                1f
            )
            nearFieldDirectionAlignment = DevicePointingGeometry.clamp(
                (hotspotDirectionCos - config.nearFieldDirectionCosFloor) /
                    (1f - config.nearFieldDirectionCosFloor),
                0f,
                1f
            )
        }

        val hotspotForwardProjectionPx = DevicePointingGeometry.dot(hotspotRelative, rayDirection)
        val hotspotPerpendicularDistancePx = DevicePointingGeometry.pointToRayDistance(
            target.hotspotPx,
            rayOrigin,
            rayDirection
        )
        val forwardFactor = when {
            hotspotForwardProjectionPx >= 0f -> 1f
            hotspotEuclideanDistancePx <= target.nearFieldExemptDistancePx &&
                nearFieldDirectionAlignment > 0f -> nearFieldDirectionAlignment
            hotspotForwardProjectionPx > -target.backwardTolerancePx -> {
                max(0f, 1f + hotspotForwardProjectionPx / target.backwardTolerancePx)
            }
            else -> 0f
        }
        val hotspotDistanceScore = max(
            0f,
            1f - hotspotPerpendicularDistancePx / target.hotspotRadiusPx
        )
        val hotspotScore = forwardFactor * hotspotDistanceScore

        val nearFieldDistanceFactor = DevicePointingGeometry.clamp(
            (target.nearFieldDeviceDistanceThresholdPx - hotspotEuclideanDistancePx) /
                target.nearFieldDeviceDistanceThresholdPx,
            0f,
            1f
        )
        val nearFieldSupportFactor = nearFieldDistanceFactor * nearFieldDirectionAlignment
        val effectiveNearFieldCropDistancePx =
            target.baseNearFieldCropDistancePx * (1f - nearFieldSupportFactor)
        val croppedOrigin = DevicePointingGeometry.add(
            rayOrigin,
            DevicePointingGeometry.scale(rayDirection, effectiveNearFieldCropDistancePx)
        )

        val polygonDistancePx = DevicePointingGeometry.rayToPolygonDistance(
            croppedOrigin,
            rayDirection,
            target.polygonPx
        )
        val penetrationLengthPx = DevicePointingGeometry.rayPenetrationLength(
            croppedOrigin,
            rayDirection,
            target.polygonPx
        )
        val areaApproachScore = max(
            0f,
            1f - polygonDistancePx / target.polygonAssistRadiusPx
        )
        val areaPenetrationScore = DevicePointingGeometry.clamp(
            penetrationLengthPx / target.penetrationNormLengthPx,
            0f,
            1f
        )
        val rawPolygonScore = 0.5f * areaApproachScore + 0.5f * areaPenetrationScore
        val polygonScore = (
            rawPolygonScore * polygonDirectionAlignment.pow(config.polygonDirectionPower)
            ).coerceIn(0f, 1f)

        val hotspotWeight = config.hotspotWeightBase +
            config.hotspotWeightBoostByNearFieldSupport * nearFieldSupportFactor
        val polygonWeight = 1f - hotspotWeight
        val geometricScore = (
            hotspotWeight * hotspotScore +
                polygonWeight * polygonScore
            ).coerceIn(0f, 1f)
        val totalScore = (rayConfidence * geometricScore).coerceIn(0f, 1f)

        val debugMetrics = DeviceFrameDebugMetrics(
            deviceId = target.id,
            forwardProjectionPx = hotspotForwardProjectionPx,
            hotspotEuclideanDistancePx = hotspotEuclideanDistancePx,
            hotspotDirectionCos = hotspotDirectionCos,
            polygonDirectionAlignment = polygonDirectionAlignment,
            nearFieldDirectionAlignment = nearFieldDirectionAlignment,
            nearFieldSupportFactor = nearFieldSupportFactor,
            effectiveNearFieldCropDistancePx = effectiveNearFieldCropDistancePx,
            hotspotScore = hotspotScore.coerceIn(0f, 1f),
            polygonScore = polygonScore,
            geometricScore = geometricScore,
            totalScore = totalScore
        )
        return DeviceFrameScore(
            deviceId = target.id,
            hotspotScore = debugMetrics.hotspotScore,
            polygonScore = debugMetrics.polygonScore,
            geometricScore = debugMetrics.geometricScore,
            totalScore = debugMetrics.totalScore,
            debugMetrics = debugMetrics
        )
    }

    private fun format(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)

    private fun logInfo(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: RuntimeException) {
        }
    }
}
