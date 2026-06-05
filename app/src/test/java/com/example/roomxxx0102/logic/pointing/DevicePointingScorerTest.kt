package com.example.roomxxx0102.logic.pointing

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePointingScorerTest {
    private val scorer = DevicePointingScorer(DevicePointingScoringConfig())

    private fun point(x: Float, y: Float): PointF = PointF().apply {
        this.x = x
        this.y = y
    }

    private fun buildTarget(): DevicePreparedTarget {
        return scorer.prepareTargets(
            targets = listOf(
                DevicePointingTarget(
                    id = "tv",
                    hotspot = point(0.5f, 0.5f),
                    polygon = listOf(
                        point(0.25f, 0.25f),
                        point(0.75f, 0.25f),
                        point(0.75f, 0.75f),
                        point(0.25f, 0.75f)
                    )
                )
            ),
            imageWidth = 400,
            imageHeight = 400
        ).first()
    }

    @Test
    fun `prepareTargets 预计算静态尺度`() {
        val target = buildTarget()
        assertEquals(200f, target.shortEdgePx, 0.001f)
        assertEquals(36f, target.hotspotRadiusPx, 0.001f)
        assertEquals(70f, target.polygonAssistRadiusPx, 0.001f)
        assertEquals(18f, target.nearFieldExemptDistancePx, 0.001f)
        assertEquals(36f, target.backwardTolerancePx, 0.001f)
        assertEquals(100f, target.penetrationNormLengthPx, 0.001f)
        assertEquals(120f, target.baseNearFieldCropDistancePx, 0.001f)
        assertEquals(16f, target.absoluteNearExemptDistancePx, 0.001f)
        assertEquals(100f, target.nearFieldDeviceDistanceThresholdPx, 0.001f)
    }

    @Test
    fun `极近目标允许背向近场豁免并得到正分`() {
        val target = buildTarget()
        val evaluation = scorer.buildFrameEvaluation(
            timestampMs = 100L,
            rayOrigin = point(185f, 200f),
            rayDirection = point(-1f, 0f),
            rayConfidence = 0.9f,
            targets = listOf(target)
        )
        val score = evaluation.frameScores.first()
        assertTrue(score.debugMetrics.hotspotDirectionCos >= 0.99f)
        assertTrue(score.hotspotScore > 0.5f)
        assertTrue(score.totalScore > 0.3f)
    }

    @Test
    fun `远距离背向目标总分应接近零`() {
        val target = buildTarget()
        val evaluation = scorer.buildFrameEvaluation(
            timestampMs = 100L,
            rayOrigin = point(350f, 200f),
            rayDirection = point(1f, 0f),
            rayConfidence = 0.9f,
            targets = listOf(target)
        )
        val score = evaluation.frameScores.first()
        assertEquals(0f, score.hotspotScore, 0.0001f)
        assertTrue(score.totalScore < 0.05f)
    }
}
