package com.example.roomxxx0102.logic.pointing

import android.util.Log

class DevicePointingWindowJudge(
    private val forceOutputOnLowConfidence: Boolean = false
) {
    fun judge(
        targets: List<DevicePreparedTarget>,
        frames: List<DeviceFrameEvaluation>
    ): DeviceWindowResult {
        if (targets.isEmpty()) {
            return DeviceWindowResult(
                confidenceStatus = PointingConfidenceStatus.UNDETERMINED,
                winnerId = null,
                secondId = null,
                winnerStats = null,
                secondStats = null,
                finalLeadRatio = 0f,
                dynamicFinalThreshold = 0.20f,
                weightedAverageRayConfidence = 0f,
                sortedStats = emptyList()
            )
        }
        if (frames.isEmpty()) {
            return DeviceWindowResult(
                confidenceStatus = PointingConfidenceStatus.UNDETERMINED,
                winnerId = null,
                secondId = null,
                winnerStats = null,
                secondStats = null,
                finalLeadRatio = 0f,
                dynamicFinalThreshold = 0.20f,
                weightedAverageRayConfidence = 0f,
                sortedStats = targets.map { DeviceWindowDeviceStats(deviceId = it.id) }
            )
        }

        val stats = linkedMapOf<String, DeviceWindowDeviceStats>()
        targets.forEach { target -> stats[target.id] = DeviceWindowDeviceStats(target.id) }
        val totalFrames = frames.size
        var weightedAverageRayConfidence = 0f

        frames.forEachIndexed { index, frame ->
            val t = index + 1
            val timeWeight = 2f * t / (totalFrames * (totalFrames + 1f))
            val peakTimeFactor = 0.5f + 0.5f * t / totalFrames.toFloat()
            val usableConfidenceFactor = DevicePointingGeometry.clamp(
                (frame.rayConfidence - 0.25f) / (1f - 0.25f),
                0f,
                1f
            )
            weightedAverageRayConfidence += timeWeight * frame.rayConfidence

            frame.frameScores.forEach { score ->
                val stat = stats[score.deviceId] ?: return@forEach
                stat.weightedAverageScore += timeWeight * score.totalScore
                stat.peakFrameScore = maxOf(stat.peakFrameScore, peakTimeFactor * score.totalScore)
                if (score.geometricScore >= 0.65f) {
                    stat.weightedHitRatio += timeWeight * usableConfidenceFactor
                }
            }

            val sortedByTotal = frame.frameScores.sortedByDescending { it.totalScore }
            val first = sortedByTotal.firstOrNull()
            val second = sortedByTotal.getOrNull(1)
            if (first != null) {
                val leadRatio = first.geometricScore / ((second?.geometricScore ?: 0f) + 1e-6f)
                if (leadRatio >= 1.15f) {
                    stats[first.deviceId]?.let { winnerStat ->
                        winnerStat.weightedLeadRatio += timeWeight * usableConfidenceFactor
                    }
                }
            }
        }

        stats.values.forEach { stat ->
            stat.finalScore =
                0.70f * stat.weightedAverageScore +
                    0.15f * stat.peakFrameScore +
                    0.15f * stat.weightedLeadRatio
        }
        val sortedStats = stats.values.sortedByDescending { it.finalScore }
        val winner = sortedStats.firstOrNull()
        val second = sortedStats.getOrNull(1)
        val finalLeadRatio = (winner?.finalScore ?: 0f) / ((second?.finalScore ?: 0f) + 1e-6f)
        val dynamicFinalThreshold = maxOf(
            0.20f,
            0.45f * (0.4f + 0.6f * weightedAverageRayConfidence)
        )

        val highConfidence =
            winner != null &&
                winner.finalScore >= dynamicFinalThreshold &&
                finalLeadRatio >= 1.20f &&
                winner.weightedHitRatio >= 0.20f

        val confidenceStatus = when {
            highConfidence -> PointingConfidenceStatus.HIGH_CONFIDENCE
            winner != null && forceOutputOnLowConfidence -> PointingConfidenceStatus.LOW_CONFIDENCE
            else -> PointingConfidenceStatus.UNDETERMINED
        }

        logWindowResult(
            sortedStats = sortedStats,
            winner = winner,
            second = second,
            finalLeadRatio = finalLeadRatio,
            dynamicFinalThreshold = dynamicFinalThreshold,
            weightedAverageRayConfidence = weightedAverageRayConfidence,
            confidenceStatus = confidenceStatus
        )

        return DeviceWindowResult(
            confidenceStatus = confidenceStatus,
            winnerId = winner?.deviceId,
            secondId = second?.deviceId,
            winnerStats = winner,
            secondStats = second,
            finalLeadRatio = finalLeadRatio,
            dynamicFinalThreshold = dynamicFinalThreshold,
            weightedAverageRayConfidence = weightedAverageRayConfidence,
            sortedStats = sortedStats
        )
    }

    private fun logWindowResult(
        sortedStats: List<DeviceWindowDeviceStats>,
        winner: DeviceWindowDeviceStats?,
        second: DeviceWindowDeviceStats?,
        finalLeadRatio: Float,
        dynamicFinalThreshold: Float,
        weightedAverageRayConfidence: Float,
        confidenceStatus: PointingConfidenceStatus
    ) {
        for (stat in sortedStats) {
            Log.i(
                "DevicePointingJudge",
                "DEVICE_POINTING|WINDOW|device=${stat.deviceId}|avg=${format(stat.weightedAverageScore)}|peak=${format(stat.peakFrameScore)}|hit=${format(stat.weightedHitRatio)}|lead=${format(stat.weightedLeadRatio)}|final=${format(stat.finalScore)}"
            )
        }
        Log.i(
            "DevicePointingJudge",
            "DEVICE_POINTING|WINDOW_SUMMARY|winner=${winner?.deviceId ?: "-"}|second=${second?.deviceId ?: "-"}|leadRatio=${format(finalLeadRatio)}|threshold=${format(dynamicFinalThreshold)}|avgConf=${format(weightedAverageRayConfidence)}|confidence=$confidenceStatus"
        )
    }

    private fun format(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)
}
