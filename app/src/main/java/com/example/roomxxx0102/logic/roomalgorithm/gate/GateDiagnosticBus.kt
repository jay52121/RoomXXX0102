package com.example.roomxxx0102.logic.roomalgorithm.gate

import com.example.roomxxx0102.logic.roomalgorithm.flow.FlowBox

/**
 * 诊断回放专用的小型数据总线。
 *
 * 默认关闭；关闭时视觉链路不会额外扫描 Mask。开启后，每个 V4 分析帧只保留一份
 * 不含 Bitmap/Mat 的结构化快照，由 UI 线程的 EventDiagnosticRecorder 立即取走。
 */
internal data class GateMaskDigest(
    val pixels: Int,
    val p20: Double?,
    val p50: Double?,
    val p80: Double?,
    val grid: List<Int>,
)

internal data class GatePortalDiagnostic(
    val gateId: String,
    val ownerTrack: Int?,
    val phase: String,
    val schedulerDistance: Double?,
    val contact: Boolean,
    val referenceKnown: Boolean,
    val exclusive: Boolean?,
    val foregroundPixels: Int,
    val peakPixels: Int,
    val clearForMs: Long,
    val historyFrames: Int,
    val bodyMotionRatio: Double?,
    val motion: GateMaskDigest?,
    val owned: GateMaskDigest?,
    val bodyInsideRatio: Double? = null,
    val poseInsideRatio: Double? = null,
    val visiblePosePoints: Int? = null,
    val bodyAlong: Double? = null,
    val bodySide: Double? = null,
)

internal data class GateDiagnosticPerson(
    val person: Int,
    val track: Int,
    val room: String?,
    val status: String,
    val accepted: Boolean,
    val candidates: List<String>,
    val box: FlowBox,
    val groundX: Double?,
    val groundY: Double?,
    val groundStrong: Boolean?,
    val groundUncertainty: Double?,
    val groundSource: String?,
    val gateId: String?,
    val phase: String?,
    val direction: String?,
    val depth: Double?,
    val groundSide: Double?,
    val groundDistance: Double?,
    val groundAlong: Double?,
    val evidence: String?,
)

internal data class GateDiagnosticEvent(
    val person: Int,
    val track: Int,
    val from: String,
    val to: String,
    val gateId: String,
    val direction: String,
    val timeMs: Long,
    val inferred: Boolean,
    val reason: String?,
)

internal data class GateDiagnosticConfig(
    val methodId: String,
    val sampleMs: Int,
    val maxGapMs: Int,
    val pixelThreshold: Int,
    val clearMs: Int,
    val clearRatio: Double,
    val contactScale: Double,
    val admissionTravel: Double,
    val episodeMs: Int,
    val historyMs: Int,
    val holdMs: Int,
    val armScore: Double,
    val armDistanceScale: Double,
    val maxActiveGates: Int,
    val depthMinPixels: Int,
    val depthEnterCommit: Double,
    val depthExitCommit: Double,
    val depthTravel: Double,
    val depthMinSamples: Int,
    val depthMinSpanMs: Long,
    val waitClearMs: Long,
    val disappearanceMs: Long,
    val episodeIdleMs: Long = 0,
    val episodeHardMs: Long = 0,
    val absorptionArmRatio: Double = 0.0,
    val absorptionCommitRatio: Double = 0.0,
    val absorptionReleaseRatio: Double = 0.0,
    val absorptionPassByAlong: Double = 0.0,
    val visualWitnessMs: Long = 0,
) {
    companion object {
        fun from(config: GateConfig): GateDiagnosticConfig {
            val policy = PortalV4Policy.from(config)
            return GateDiagnosticConfig(
                methodId = config.method.id,
                sampleMs = config.sampleMs,
                maxGapMs = config.maxGapMs,
                pixelThreshold = config.pixelThreshold,
                clearMs = config.clearMs,
                clearRatio = config.clearRatio,
                contactScale = config.contactScale,
                admissionTravel = config.admissionTravel,
                episodeMs = config.episodeMs,
                historyMs = config.historyMs,
                holdMs = config.holdMs,
                armScore = config.armScore,
                armDistanceScale = config.armDistanceScale,
                maxActiveGates = config.maxActiveGates,
                depthMinPixels = policy.depthMinPixels,
                depthEnterCommit = policy.depthEnterCommit,
                depthExitCommit = policy.depthExitCommit,
                depthTravel = policy.depthTravel,
                depthMinSamples = policy.depthMinSamples,
                depthMinSpanMs = policy.depthMinSpanMs,
                waitClearMs = policy.waitClearMs,
                disappearanceMs = policy.disappearanceMs,
                episodeIdleMs = policy.episodeIdleMs,
                episodeHardMs = policy.episodeTimeoutMs,
                absorptionArmRatio = policy.absorptionArmRatio,
                absorptionCommitRatio = policy.absorptionCommitRatio,
                absorptionReleaseRatio = policy.absorptionReleaseRatio,
                absorptionPassByAlong = policy.absorptionPassByAlong,
                visualWitnessMs = policy.visualWitnessMs,
            )
        }
    }
}

internal data class GateDiagnosticFrame(
    val timeMs: Long,
    val frameSeq: Long,
    val runtimeTag: String,
    val activeGates: Int,
    val people: List<GateDiagnosticPerson>,
    val portals: List<GatePortalDiagnostic>,
    val events: List<GateDiagnosticEvent>,
    val counts: Map<String, Int>,
    val notes: List<String>,
)

internal object GateDiagnosticBus {
    @Volatile private var capturing = false
    @Volatile private var latestFrame: GateDiagnosticFrame? = null
    @Volatile private var currentConfig: GateDiagnosticConfig? = null

    fun isCapturing(): Boolean = capturing

    @Synchronized
    fun beginCapture() {
        capturing = true
        latestFrame = null
        currentConfig = null
    }

    @Synchronized
    fun endCapture() {
        capturing = false
        latestFrame = null
        currentConfig = null
    }

    @Synchronized
    fun configure(config: GateConfig) {
        if (!capturing) return
        currentConfig = GateDiagnosticConfig.from(config)
    }

    @Synchronized
    fun publish(frame: GateDiagnosticFrame) {
        if (!capturing) return
        latestFrame = frame
    }

    @Synchronized
    fun frameFor(frameSeq: Long): GateDiagnosticFrame? {
        if (!capturing) return null
        return latestFrame?.takeIf { it.frameSeq == frameSeq }
    }

    @Synchronized
    fun configSnapshot(): GateDiagnosticConfig? = currentConfig
}
