package com.example.roomxxx0102.logic.roomalgorithm.gate

internal data class PortalDepthEvidence(
    val gateId: String,
    val track: Int,
    val timeMs: Long,
    val p20: Double,
    val median: Double,
    val p80: Double,
    val ownedPixels: Int,
    val exclusive: Boolean,
)

/** Visible-body convergence into a portal. It deliberately does not depend on feet. */
internal data class PortalBodyEvidence(
    val gateId: String,
    val track: Int,
    val timeMs: Long,
    val poseInsideRatio: Double,
    val pixelInsideRatio: Double,
    val visiblePosePoints: Int,
    val bodyPixels: Int,
    val insidePixels: Int,
    val centerAlong: Double,
    val centerSide: Double,
    val exclusive: Boolean,
) {
    // Pose carries most of the weight because the local crop can truncate a body outside the door.
    // With too few pose points, pixel overlap remains a weak fallback rather than becoming decisive.
    val absorption: Double get() = if (visiblePosePoints >= 4) {
        (poseInsideRatio * 0.75 + pixelInsideRatio * 0.25).coerceIn(0.0, 1.0)
    } else (pixelInsideRatio * 0.85).coerceIn(0.0, 1.0)
}

internal enum class PortalEpisodePhase { CONTACT, TRANSITING, WAIT_CLEAR }

internal data class PortalV4PersonDebug(
    val track: Int,
    val room: String?,
    val gateId: String?,
    val phase: PortalEpisodePhase?,
    val direction: String?,
    val depth: Double?,
    val groundSide: Double?,
    val evidence: String?,
)

internal data class PortalV4Policy(
    // A cadence hint only. A sparse analysis interval is not a video discontinuity.
    val gapMs: Long = 300,
    val contactScale: Double = 0.10,
    val admissionTravel: Double = 0.012,
    val depthMinPixels: Int = 16,
    val depthEnterCommit: Double = 0.44,
    val depthExitCommit: Double = 0.20,
    val depthTravel: Double = 0.20,
    val depthMinSamples: Int = 3,
    val depthMinSpanMs: Long = 80,
    val depthHistoryMs: Long = 3200,
    // Episodes die from lack of evidence, with a separate hard stale cap.
    val episodeIdleMs: Long = 1400,
    val episodeTimeoutMs: Long = 5000,
    val waitClearMs: Long = 320,
    val disappearanceMs: Long = 220,
    val clearMs: Long = 200,
    val clearRatio: Double = 0.08,
    // Whole-body absorption is a pending visual witness, never an instant room transfer.
    val absorptionArmRatio: Double = 0.58,
    val absorptionCommitRatio: Double = 0.78,
    val absorptionReleaseRatio: Double = 0.42,
    val absorptionPassByAlong: Double = 0.22,
    val visualWitnessMs: Long = 600,
    val absorptionPeakFreshMs: Long = 1700,
) {
    companion object {
        fun from(config: GateConfig) = PortalV4Policy(
            gapMs = config.maxGapMs.toLong(),
            contactScale = config.contactScale,
            admissionTravel = config.admissionTravel,
            episodeIdleMs = maxOf(1200L, config.holdMs.toLong() + 300L),
            episodeTimeoutMs = maxOf(4500L, config.episodeMs.toLong() * 3L),
            clearMs = config.clearMs.toLong(),
            clearRatio = config.clearRatio,
        )
    }
}
