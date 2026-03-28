package com.example.roomxxx0102.logic.pointing

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

class DevicePointingScorer {
    fun prepareTargets(
        targets: List<DevicePointingTarget>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DevicePreparedTarget> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        return targets.mapNotNull { target ->
            if (target.polygon.size != 4) return@mapNotNull null
            val hotspotPx = PointF(target.hotspot.x * imageWidth, target.hotspot.y * imageHeight)
            val polygonPx = target.polygon.map { PointF(it.x * imageWidth, it.y * imageHeight) }
            val edge1 = DevicePointingGeometry.distance(polygonPx[0], polygonPx[1])
            val edge2 = DevicePointingGeometry.distance(polygonPx[1], polygonPx[2])
            val shortEdge = min(edge1, edge2).coerceAtLeast(1f)
            val hotspotRadius = DevicePointingGeometry.clamp(0.18f * shortEdge, 20f, 60f)
            val polygonAssistRadius = DevicePointingGeometry.clamp(0.35f * shortEdge, 30f, 100f)
            val bounds = RectF(
                polygonPx.minOf { it.x },
                polygonPx.minOf { it.y },
                polygonPx.maxOf { it.x },
                polygonPx.maxOf { it.y }
            )
            DevicePreparedTarget(
                id = target.id,
                hotspotPx = hotspotPx,
                polygonPx = polygonPx,
                boundsPx = bounds,
                shortEdgePx = shortEdge,
                hotspotRadiusPx = hotspotRadius,
                polygonAssistRadiusPx = polygonAssistRadius,
                nearFieldExemptDistancePx = 0.5f * hotspotRadius,
                backwardTolerancePx = 1.0f * hotspotRadius,
                penetrationNormLengthPx = 0.5f * shortEdge
            )
        }
    }

    fun scoreFrame(
        rayOrigin: PointF,
        rayDirection: PointF,
        rayConfidence: Float,
        targets: List<DevicePreparedTarget>
    ): List<DeviceFrameScore> {
        val dir = DevicePointingGeometry.normalize(rayDirection)
        return targets.map { target ->
            val relative = DevicePointingGeometry.subtract(target.hotspotPx, rayOrigin)
            val forwardProjection = DevicePointingGeometry.dot(relative, dir)
            val hotspotDistance = DevicePointingGeometry.pointToRayDistance(target.hotspotPx, rayOrigin, dir)
            val hotspotEuclidean = DevicePointingGeometry.distance(target.hotspotPx, rayOrigin)
            val forwardFactor = when {
                forwardProjection >= 0f -> 1f
                hotspotEuclidean <= target.nearFieldExemptDistancePx -> 1f
                forwardProjection > -target.backwardTolerancePx -> {
                    max(0f, 1f + forwardProjection / target.backwardTolerancePx)
                }
                else -> 0f
            }
            val hotspotDistanceScore = max(0f, 1f - hotspotDistance / target.hotspotRadiusPx)
            val hotspotScore = forwardFactor * hotspotDistanceScore

            val polygonDistance = DevicePointingGeometry.rayToPolygonDistance(rayOrigin, dir, target.polygonPx)
            val polygonIntersect = DevicePointingGeometry.rayIntersectsPolygon(rayOrigin, dir, target.polygonPx)
            val polygonApproachScore = max(0f, 1f - polygonDistance / target.polygonAssistRadiusPx)
            val penetrationLength = if (polygonIntersect) {
                DevicePointingGeometry.rayPenetrationLength(rayOrigin, dir, target.polygonPx)
            } else {
                0f
            }
            val polygonPenetrationScore = DevicePointingGeometry.clamp(
                penetrationLength / target.penetrationNormLengthPx,
                0f,
                1f
            )
            val polygonScore = 0.5f * polygonApproachScore + 0.5f * polygonPenetrationScore

            val geometricScore = 0.70f * hotspotScore + 0.30f * polygonScore
            val totalScore = rayConfidence * geometricScore
            DeviceFrameScore(
                deviceId = target.id,
                hotspotScore = hotspotScore.coerceIn(0f, 1f),
                polygonScore = polygonScore.coerceIn(0f, 1f),
                geometricScore = geometricScore.coerceIn(0f, 1f),
                totalScore = totalScore.coerceIn(0f, 1f)
            )
        }
    }

    fun logFrameScores(frameIndex: Int, rayConfidence: Float, scores: List<DeviceFrameScore>) {
        for (score in scores.sortedByDescending { it.totalScore }) {
            Log.i(
                "DevicePointingJudge",
                "DEVICE_POINTING|FRAME|index=$frameIndex|device=${score.deviceId}|conf=${format(rayConfidence)}|hot=${format(score.hotspotScore)}|poly=${format(score.polygonScore)}|geom=${format(score.geometricScore)}|total=${format(score.totalScore)}"
            )
        }
    }

    private fun format(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)
}
