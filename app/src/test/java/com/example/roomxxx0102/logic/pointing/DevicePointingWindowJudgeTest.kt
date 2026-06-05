package com.example.roomxxx0102.logic.pointing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePointingWindowJudgeTest {
    private val judge = DevicePointingWindowJudge(DevicePointingScoringConfig())

    private fun point(x: Float, y: Float) = android.graphics.PointF().apply {
        this.x = x
        this.y = y
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        android.graphics.RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    private fun target(id: String) = DevicePreparedTarget(
        id = id,
        hotspotPx = point(0f, 0f),
        polygonPx = emptyList(),
        boundsPx = rect(0f, 0f, 10f, 10f),
        shortEdgePx = 100f,
        hotspotRadiusPx = 30f,
        polygonAssistRadiusPx = 50f,
        nearFieldExemptDistancePx = 15f,
        backwardTolerancePx = 30f,
        penetrationNormLengthPx = 50f,
        baseNearFieldCropDistancePx = 60f,
        absoluteNearExemptDistancePx = 15f,
        nearFieldDeviceDistanceThresholdPx = 100f
    )

    private fun frameScore(deviceId: String, geometric: Float, total: Float): DeviceFrameScore {
        val metrics = DeviceFrameDebugMetrics(
            deviceId = deviceId,
            forwardProjectionPx = 1f,
            hotspotEuclideanDistancePx = 1f,
            hotspotDirectionCos = 1f,
            polygonDirectionAlignment = 1f,
            nearFieldDirectionAlignment = 1f,
            nearFieldSupportFactor = 0f,
            effectiveNearFieldCropDistancePx = 0f,
            hotspotScore = geometric,
            polygonScore = geometric,
            geometricScore = geometric,
            totalScore = total
        )
        return DeviceFrameScore(
            deviceId = deviceId,
            hotspotScore = geometric,
            polygonScore = geometric,
            geometricScore = geometric,
            totalScore = total,
            debugMetrics = metrics
        )
    }

    @Test
    fun `高置信窗口应输出第一名设备`() {
        val frames = listOf(
            DeviceFrameEvaluation(
                timestampMs = 10L,
                rayConfidence = 0.9f,
                usableConfidenceFactor = 0.8666666f,
                frameScores = listOf(
                    frameScore("tv", 0.95f, 0.90f),
                    frameScore("lamp", 0.40f, 0.36f)
                )
            ),
            DeviceFrameEvaluation(
                timestampMs = 20L,
                rayConfidence = 0.9f,
                usableConfidenceFactor = 0.8666666f,
                frameScores = listOf(
                    frameScore("tv", 0.94f, 0.89f),
                    frameScore("lamp", 0.42f, 0.37f)
                )
            )
        )
        val result = judge.judge(listOf(target("tv"), target("lamp")), frames)
        assertEquals(PointingConfidenceStatus.HIGH_CONFIDENCE, result.confidenceStatus)
        assertEquals("tv", result.winnerId)
        assertTrue(result.finalLeadRatio > 1.2f)
        assertTrue((result.winnerStats?.weightedHitRatio ?: 0f) >= 0.20f)
    }

    @Test
    fun `分差不足时应输出未定`() {
        val frames = listOf(
            DeviceFrameEvaluation(
                timestampMs = 10L,
                rayConfidence = 0.7f,
                usableConfidenceFactor = 0.6f,
                frameScores = listOf(
                    frameScore("tv", 0.60f, 0.42f),
                    frameScore("lamp", 0.58f, 0.41f)
                )
            ),
            DeviceFrameEvaluation(
                timestampMs = 20L,
                rayConfidence = 0.7f,
                usableConfidenceFactor = 0.6f,
                frameScores = listOf(
                    frameScore("tv", 0.62f, 0.43f),
                    frameScore("lamp", 0.60f, 0.42f)
                )
            )
        )
        val result = judge.judge(listOf(target("tv"), target("lamp")), frames)
        assertEquals(PointingConfidenceStatus.UNDETERMINED, result.confidenceStatus)
        assertEquals("tv", result.winnerId)
        assertTrue(result.finalLeadRatio < 1.20f || (result.winnerStats?.weightedHitRatio ?: 0f) < 0.20f)
    }
}
