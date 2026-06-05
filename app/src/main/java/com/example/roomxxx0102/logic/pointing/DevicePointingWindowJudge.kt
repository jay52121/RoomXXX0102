package com.example.roomxxx0102.logic.pointing

import android.util.Log

class DevicePointingWindowJudge(
    private val config: DevicePointingScoringConfig = DevicePointingScoringConfig()
) {
    fun judge(
        targets: List<DevicePreparedTarget>,
        frames: List<DeviceFrameEvaluation>
    ): DeviceWindowResult {
        val emptyMetrics = DeviceWindowDebugMetrics(
            weightedAverageRayConfidence = 0f,
            dynamicFinalThreshold = config.absoluteMinimumFinalThreshold,
            winnerDeviceId = null,
            secondDeviceId = null,
            finalLeadRatio = 0f,
            confidenceStatus = PointingConfidenceStatus.UNDETERMINED,
            deviceStats = emptyList()
        )
        if (targets.isEmpty()) {
            return DeviceWindowResult(
                confidenceStatus = PointingConfidenceStatus.UNDETERMINED,
                winnerId = null,
                secondId = null,
                winnerStats = null,
                secondStats = null,
                finalLeadRatio = 0f,
                dynamicFinalThreshold = config.absoluteMinimumFinalThreshold,
                weightedAverageRayConfidence = 0f,
                sortedStats = emptyList(),
                debugMetrics = emptyMetrics
            )
        }
        val statsById = linkedMapOf<String, DeviceWindowDeviceStats>()
        targets.forEach { target -> statsById[target.id] = DeviceWindowDeviceStats(deviceId = target.id) }
        if (frames.isEmpty()) {
            val emptyStats = statsById.values.toList()
            val metrics = emptyMetrics.copy(deviceStats = emptyStats)
            return DeviceWindowResult(
                confidenceStatus = PointingConfidenceStatus.UNDETERMINED,
                winnerId = null,
                secondId = null,
                winnerStats = null,
                secondStats = null,
                finalLeadRatio = 0f,
                dynamicFinalThreshold = config.absoluteMinimumFinalThreshold,
                weightedAverageRayConfidence = 0f,
                sortedStats = emptyStats,
                debugMetrics = metrics
            )
        }

        var weightedAverageRayConfidence = 0f
        val totalFrames = frames.size
        frames.forEachIndexed { index, frame ->
            val t = index + 1
            val timeWeight = 2f * t / (totalFrames.toFloat() * (totalFrames + 1f))
            val peakTimeFactor = 0.5f + 0.5f * t / totalFrames.toFloat()
            weightedAverageRayConfidence += timeWeight * frame.rayConfidence

            frame.frameScores.forEach { score ->
                val stats = statsById[score.deviceId] ?: return@forEach
                stats.weightedAverageFrameScore += timeWeight * score.totalScore
                stats.temporalPeakFrameScore = maxOf(
                    stats.temporalPeakFrameScore,
                    peakTimeFactor * score.totalScore
                )
                if (score.geometricScore >= config.frameHitGeometricThreshold) {
                    stats.weightedHitRatio += timeWeight * frame.usableConfidenceFactor
                }
            }

            val sortedByTotal = frame.frameScores.sortedByDescending { it.totalScore }
            val first = sortedByTotal.firstOrNull()
            val second = sortedByTotal.getOrNull(1)
            if (first != null) {
                val leadRatio = first.geometricScore / ((second?.geometricScore ?: 0f) + 1e-6f)
                if (leadRatio >= config.frameLeadRatioThreshold) {
                    statsById[first.deviceId]?.let { stats ->
                        stats.weightedLeadRatio += timeWeight * frame.usableConfidenceFactor
                    }
                }
            }
        }

        statsById.values.forEach { stats ->
            stats.finalScore =
                config.finalAverageWeight * stats.weightedAverageFrameScore +
                    config.finalPeakWeight * stats.temporalPeakFrameScore +
                    config.finalLeadWeight * stats.weightedLeadRatio
        }

        val sortedStats = statsById.values.sortedByDescending { it.finalScore }
        val winner = sortedStats.firstOrNull()
        val second = sortedStats.getOrNull(1)
        val finalLeadRatio = (winner?.finalScore ?: 0f) / ((second?.finalScore ?: 0f) + 1e-6f)
        val dynamicFinalThreshold = maxOf(
            config.absoluteMinimumFinalThreshold,
            config.baselineFinalThreshold * (0.4f + 0.6f * weightedAverageRayConfidence)
        )

        val highConfidence = winner != null &&
            winner.finalScore >= dynamicFinalThreshold &&
            finalLeadRatio >= config.minimumFinalLeadRatio &&
            winner.weightedHitRatio >= config.minimumWeightedHitRatio
        val confidenceStatus = when {
            highConfidence -> PointingConfidenceStatus.HIGH_CONFIDENCE
            winner != null && config.forceOutputOnLowConfidence -> PointingConfidenceStatus.LOW_CONFIDENCE
            else -> PointingConfidenceStatus.UNDETERMINED
        }

        val metrics = DeviceWindowDebugMetrics(
            weightedAverageRayConfidence = weightedAverageRayConfidence,
            dynamicFinalThreshold = dynamicFinalThreshold,
            winnerDeviceId = winner?.deviceId,
            secondDeviceId = second?.deviceId,
            finalLeadRatio = finalLeadRatio,
            confidenceStatus = confidenceStatus,
            deviceStats = sortedStats
        )
        logWindowResult(metrics)
        return DeviceWindowResult(
            confidenceStatus = confidenceStatus,
            winnerId = winner?.deviceId,
            secondId = second?.deviceId,
            winnerStats = winner,
            secondStats = second,
            finalLeadRatio = finalLeadRatio,
            dynamicFinalThreshold = dynamicFinalThreshold,
            weightedAverageRayConfidence = weightedAverageRayConfidence,
            sortedStats = sortedStats,
            debugMetrics = metrics
        )
    }

    private fun logWindowResult(metrics: DeviceWindowDebugMetrics) {
        for (stats in metrics.deviceStats) {
            logInfo(
                "DevicePointingJudge",
                "DEVICE_POINTING|WINDOW|device=${stats.deviceId}" +
                    "|avg=${format(stats.weightedAverageFrameScore)}" +
                    "|peak=${format(stats.temporalPeakFrameScore)}" +
                    "|hit=${format(stats.weightedHitRatio)}" +
                    "|lead=${format(stats.weightedLeadRatio)}" +
                    "|final=${format(stats.finalScore)}"
            )
        }
        logInfo(
            "DevicePointingJudge",
            "DEVICE_POINTING|WINDOW_SUMMARY|winner=${metrics.winnerDeviceId ?: "-"}" +
                "|second=${metrics.secondDeviceId ?: "-"}" +
                "|leadRatio=${format(metrics.finalLeadRatio)}" +
                "|threshold=${format(metrics.dynamicFinalThreshold)}" +
                "|avgConf=${format(metrics.weightedAverageRayConfidence)}" +
                "|confidence=${metrics.confidenceStatus}"
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
