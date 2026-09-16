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
    val gapMs: Long = 300,
    val contactScale: Double = 0.10,
    val admissionTravel: Double = 0.012,
    val depthMinPixels: Int = 16,
    val depthEnterCommit: Double = 0.44,
    val depthExitCommit: Double = 0.20,
    val depthTravel: Double = 0.20,
    val depthMinSamples: Int = 3,
    val depthMinSpanMs: Long = 80,
    val episodeTimeoutMs: Long = 1800,
    val waitClearMs: Long = 320,
    val disappearanceMs: Long = 220,
    val clearMs: Long = 200,
    val clearRatio: Double = 0.08,
) {
    companion object {
        fun from(config: GateConfig) = PortalV4Policy(
            gapMs = config.maxGapMs.toLong(),
            contactScale = config.contactScale,
            admissionTravel = config.admissionTravel,
            episodeTimeoutMs = config.episodeMs.toLong(),
            clearMs = config.clearMs.toLong(),
            clearRatio = config.clearRatio,
        )
    }
}
