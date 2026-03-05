package com.example.roomxxx0102.logic.presence

import java.security.MessageDigest

/**
 * Presence 算法版本注册表。
 *
 * 约定：
 * - 配置里保存的是“用户选择项”，可能是 AUTO（自动跟随最新）
 * - 运行时必须先 resolve 成具体版本，再创建算法实例
 */
object PresenceAlgorithmRegistry {
    const val AUTO_LATEST = "AUTO_LATEST"
    const val VERSION_V1_0_0_B03011413 = "V1.0.0(B03011413)"
    const val VERSION_V1_1_0_B03021639 = "V1.1.0(B03021639)"
    const val VERSION_V1_1_1_B03021717 = "V1.1.1(B03021717)"
    const val VERSION_V1_1_2_B03021736 = "V1.1.2(B03021736)"
    const val VERSION_V1_1_3_B03021801 = "V1.1.3(B03021801)"
    const val VERSION_V1_1_4_B03021831 = "V1.1.4(B03021831)"
    const val VERSION_V1_1_5_B03021852 = "V1.1.5(B03021852)"
    const val VERSION_V1_1_6_B03021957 = "V1.1.6(B03021957)"
    const val VERSION_V1_1_7_B03022014 = "V1.1.7(B03022014)"
    const val VERSION_V1_1_8_B03022153 = "V1.1.8(B03022153)"
    const val VERSION_V1_1_9_B03022207 = "V1.1.9(B03022207)"
    const val VERSION_V1_2_0_B03022300 = "V1.2.0(B03022300)"
    const val VERSION_V1_2_1_B03022335 = "V1.2.1(B03022335)"
    const val VERSION_V1_2_2_B03022359 = "V1.2.2(B03022359)"
    const val VERSION_V1_2_3_B03030020 = "V1.2.3(B03030020)"
    const val VERSION_V1_3_0_B03030116 = "V1.3.0(B03030116)"
    const val VERSION_V1_3_1_B03030340 = "V1.3.1(B03030340)"
    const val VERSION_V1_3_2_B03030450 = "V1.3.2(B03030450)"
    const val VERSION_V1_3_4_B03041455 = "V1.3.4(B03041455)"
    const val VERSION_V1_5_0_B03041530 = "V1.5.0(B03041530)"
    const val VERSION_V1_5_1_B03052210 = "V1.5.1(B03052210)"
    const val VERSION_V1_5_2_B03052250 = "V1.5.2(B03052250)"
    const val VERSION_V1_5_3_B03052330 = "V1.5.3(B03052330)"
    const val ACTIVE_BASELINE_ID = "MAINLINE_20260303"

    private val allVersionIds = listOf(
        VERSION_V1_5_0_B03041530,
        VERSION_V1_5_1_B03052210,
        VERSION_V1_5_3_B03052330
    )

    private val archivedVersionIds = listOf(
        VERSION_V1_0_0_B03011413,
        VERSION_V1_1_0_B03021639,
        VERSION_V1_1_1_B03021717,
        VERSION_V1_1_2_B03021736,
        VERSION_V1_1_3_B03021801,
        VERSION_V1_1_4_B03021831,
        VERSION_V1_1_5_B03021852,
        VERSION_V1_1_6_B03021957,
        VERSION_V1_1_7_B03022014,
        VERSION_V1_1_8_B03022153,
        VERSION_V1_1_9_B03022207,
        VERSION_V1_2_0_B03022300,
        VERSION_V1_2_1_B03022335,
        VERSION_V1_2_2_B03022359,
        VERSION_V1_2_3_B03030020,
        VERSION_V1_3_0_B03030116,
        VERSION_V1_3_1_B03030340,
        VERSION_V1_3_2_B03030450,
        VERSION_V1_3_4_B03041455,
        VERSION_V1_5_2_B03052250
    )

    data class AlgorithmOption(
        val id: String,
        val label: String
    )

    fun latestVersionId(): String = allVersionIds.last()

    fun allVersionIds(): List<String> = allVersionIds.toList()

    fun archivedVersionIds(): List<String> = archivedVersionIds.toList()

    /**
     * 将设置页中的选择项解析为可执行的具体版本号。
     */
    fun resolveVersionId(selectedId: String?): String {
        if (selectedId.isNullOrBlank() || selectedId == AUTO_LATEST) {
            return latestVersionId()
        }
        return if (allVersionIds.contains(selectedId)) selectedId else latestVersionId()
    }

    /**
     * 返回设置页用的选项列表：自动 + 所有具体版本。
     */
    fun buildOptions(): List<AlgorithmOption> {
        val latest = latestVersionId()
        val options = mutableListOf<AlgorithmOption>()
        options.add(AlgorithmOption(AUTO_LATEST, "自动(最新: $latest)"))
        allVersionIds.forEach { version ->
            options.add(AlgorithmOption(version, version))
        }
        return options
    }

    private fun buildRuntimeTag(
        versionId: String,
        baselineId: String,
        params: PresenceEstimatorParams
    ): String {
        val hash = paramsHash(params)
        return "$versionId|b=$baselineId|h=$hash"
    }

    private fun paramsHash(params: PresenceEstimatorParams): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(params.toString().toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * 按选择项创建算法实例。
     */
    fun create(
        selectedId: String?,
        params: PresenceEstimatorParams = PresenceEstimatorParams()
    ): PresenceAlgorithmEngine {
        val resolved = resolveVersionId(selectedId)
        if (
            resolved == VERSION_V1_5_3_B03052330 ||
            resolved == VERSION_V1_5_1_B03052210 ||
            resolved == VERSION_V1_5_0_B03041530 ||
            resolved == VERSION_V1_3_4_B03041455 ||
            resolved == VERSION_V1_3_2_B03030450 ||
            resolved == VERSION_V1_3_1_B03030340
        ) {
            val activeParams = buildActiveMainlineParams(params)
            val runtimeVersionId = when (resolved) {
                VERSION_V1_5_3_B03052330 -> VERSION_V1_5_3_B03052330
                VERSION_V1_5_1_B03052210 -> VERSION_V1_5_1_B03052210
                else -> VERSION_V1_5_0_B03041530
            }
            val runtimeTag = buildRuntimeTag(
                versionId = runtimeVersionId,
                baselineId = ACTIVE_BASELINE_ID,
                params = activeParams
            )
            return PresenceAlgorithmV1_1_1_B03021717(
                params = activeParams,
                versionId = runtimeVersionId,
                runtimeTag = runtimeTag
            )
        }
        return when (resolved) {
            VERSION_V1_1_2_B03021736 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterThreshold = 0.52
                ),
                versionId = VERSION_V1_1_2_B03021736
            )
            VERSION_V1_1_3_B03021801 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterThreshold = 0.52,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_1_3_B03021801
            )
            VERSION_V1_1_4_B03021831 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterThreshold = 0.52,
                    exitSourceRoomScoreThreshold = 0.40,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_1_4_B03021831
            )
            VERSION_V1_1_5_B03021852 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterThreshold = 0.52,
                    exitSourceRoomScoreThreshold = 0.40,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_1_5_B03021852
            )
            VERSION_V1_1_6_B03021957 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.10,
                    enterThreshold = 0.52,
                    exitSourceRoomScoreThreshold = 0.40,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_1_6_B03021957
            )
            VERSION_V1_1_7_B03022014 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.10,
                    enterThreshold = 0.52,
                    exitSourceRoomScoreThreshold = 0.40,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_1_7_B03022014
            )
            VERSION_V1_1_8_B03022153 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.10,
                    enterThreshold = 0.52,
                    exitSourceRoomScoreThreshold = 0.40,
                    exitOutsidePoseScoreThreshold = 0.12,
                    exitPosePointMinConfidence = 0.05,
                    exitPoseMinConfidence = 0.20,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_1_8_B03022153
            )
            VERSION_V1_1_9_B03022207 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.10,
                    enterThreshold = 0.52,
                    exitSourceRoomScoreThreshold = 0.40,
                    exitOutsidePoseScoreThreshold = 0.12,
                    exitPosePointMinConfidence = 0.05,
                    exitPoseMinConfidence = 0.20,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_1_9_B03022207
            )
            VERSION_V1_2_0_B03022300 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.10,
                    enterThreshold = 0.52,
                    enterMotionWindowFrames = 3,
                    enterNormalAdvanceMin = 0.008,
                    enterLateralRatioMin = 0.35,
                    enterGroundFootBlendStartConfidence = 0.60,
                    exitSourceRoomScoreThreshold = 0.40,
                    exitOutsidePoseScoreThreshold = 0.12,
                    exitPosePointMinConfidence = 0.05,
                    exitPoseMinConfidence = 0.20,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_2_0_B03022300
            )
            VERSION_V1_2_1_B03022335 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.08,
                    enterThreshold = 0.52,
                    enterMotionWindowFrames = 3,
                    enterNormalAdvanceMin = 0.008,
                    enterLateralRatioMin = 0.35,
                    enterGroundFootBlendStartConfidence = 0.60,
                    exitSourceRoomScoreThreshold = 0.40,
                    exitOutsidePoseScoreThreshold = 0.10,
                    exitPosePointMinConfidence = 0.05,
                    exitPoseMinConfidence = 0.20,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_2_1_B03022335
            )
            VERSION_V1_2_2_B03022359 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.08,
                    enterThreshold = 0.58,
                    enterMotionWindowFrames = 3,
                    enterNormalAdvanceMin = 0.008,
                    enterLateralRatioMin = 0.35,
                    enterGroundFootBlendStartConfidence = 0.60,
                    enterAMin = 0.35,
                    exitSourceRoomScoreThreshold = 0.40,
                    exitOutsidePoseScoreThreshold = 0.08,
                    exitPosePointMinConfidence = 0.05,
                    exitPoseMinConfidence = 0.20,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_2_2_B03022359
            )
            VERSION_V1_2_3_B03030020 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.08,
                    enterThreshold = 0.58,
                    enterMotionWindowFrames = 3,
                    enterNormalAdvanceMin = 0.008,
                    enterLateralRatioMin = 0.35,
                    enterGroundFootBlendStartConfidence = 0.60,
                    enterAMin = 0.35,
                    exitSourceRoomScoreThreshold = 0.40,
                    exitOutsidePoseScoreThreshold = 0.08,
                    exitPosePointMinConfidence = 0.05,
                    exitPoseMinConfidence = 0.20,
                    grayPoseMinConfidenceForSwitch = 0.45,
                    unifiedPoseScoreWeight = 0.80,
                    unifiedPoseScoreWeightNoPolygon = 0.30,
                    // V1.2.3 之前尚未引入这些“额外门禁”，保持历史行为。
                    enterVisibleRequireNearDoor = false,
                    enterVisibleNearDoorMultiplier = 1.4,
                    enterVisibleNearDoorMin = 0.025,
                    enterVisibleNearDoorLatchFrames = 0,
                    enterVisibleRequireContainment = false,
                    enterVisibleTargetContainmentMin = 0.75,
                    enterVisibleSourceContainmentMax = 0.20,
                    enterVisibleRequireDoorEvidence = false,
                    enterVisibleDoorAssistMin = 0.10,
                    exitVisibleRequireNearDoor = false,
                    exitVisibleNearDoorMultiplier = 1.4,
                    exitVisibleNearDoorMin = 0.025,
                    visibleExitPoseTransitionModel = VisibleExitPoseTransitionModel.OUTSIDE_ONLY,
                    allowVisibleExitPolygonRecovery = true,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_2_3_B03030020
            )
            VERSION_V1_3_0_B03030116 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.08,
                    enterThreshold = 0.30,
                    enterMotionWindowFrames = 3,
                    enterNormalAdvanceMin = 0.008,
                    enterLateralRatioMin = 0.35,
                    enterGroundFootBlendStartConfidence = 0.60,
                    enterAMin = 0.35,
                    exitSourceRoomScoreThreshold = 0.40,
                    exitOutsidePoseScoreThreshold = 0.08,
                    exitPosePointMinConfidence = 0.05,
                    exitPoseMinConfidence = 0.20,
                    grayPoseMinConfidenceForSwitch = 0.45,
                    unifiedPoseScoreWeight = 0.80,
                    unifiedPoseScoreWeightNoPolygon = 0.30,
                    enterVisibleRequireNearDoor = true,
                    enterVisibleNearDoorMultiplier = 1.4,
                    enterVisibleNearDoorMin = 0.025,
                    enterVisibleNearDoorLatchFrames = 1,
                    enterVisibleRequireContainment = true,
                    enterVisibleTargetContainmentMin = 0.75,
                    enterVisibleSourceContainmentMax = 0.20,
                    enterVisibleRequireDoorEvidence = true,
                    enterVisibleDoorAssistMin = 0.10,
                    exitVisibleRequireNearDoor = true,
                    exitVisibleNearDoorMultiplier = 1.4,
                    exitVisibleNearDoorMin = 0.025,
                    visibleExitPoseTransitionModel = VisibleExitPoseTransitionModel.OUTSIDE_PLUS_TARGET,
                    allowVisibleExitPolygonRecovery = false,
                    recoveryTargetContainmentMin = 0.60,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_3_0_B03030116
            )
            VERSION_V1_3_1_B03030340 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params.copy(
                    enterDoorD0 = 0.015,
                    enterDoorD1 = 0.08,
                    enterDoorDynamicRatio = 0.08,
                    enterThreshold = 0.30,
                    enterMotionWindowFrames = 3,
                    enterNormalAdvanceMin = 0.008,
                    enterLateralRatioMin = 0.35,
                    enterGroundFootBlendStartConfidence = 0.60,
                    enterAMin = 0.35,
                    exitSourceRoomScoreThreshold = 0.40,
                    exitOutsidePoseScoreThreshold = 0.08,
                    exitPosePointMinConfidence = 0.05,
                    exitPoseMinConfidence = 0.20,
                    grayPoseMinConfidenceForSwitch = 0.45,
                    unifiedPoseScoreWeight = 0.80,
                    unifiedPoseScoreWeightNoPolygon = 0.30,
                    // V1.3.1：关闭原硬门槛，改为单分数 + 连续积分。
                    enterVisibleRequireNearDoor = false,
                    enterVisibleNearDoorMultiplier = 1.4,
                    enterVisibleNearDoorMin = 0.025,
                    enterVisibleNearDoorLatchFrames = 0,
                    enterVisibleRequireContainment = false,
                    enterVisibleTargetContainmentMin = 0.75,
                    enterVisibleSourceContainmentMax = 0.20,
                    enterVisibleRequireDoorEvidence = false,
                    enterVisibleDoorAssistMin = 0.10,
                    useSwitchScoreIntegrator = true,
                    poseHardRejectMinConfidence = 0.20,
                    softDoorClearTau = 0.002,
                    poseNearGateDps0 = 0.12,
                    switchEvidenceBeta = 0.72,
                    switchEvidenceThreshold = 0.56,
                    switchEvidenceMax = 2.0,
                    useSoftDoorClearGate = true,
                    exitVisibleRequireNearDoor = false,
                    exitVisibleNearDoorMultiplier = 1.4,
                    exitVisibleNearDoorMin = 0.025,
                    exitVisibleDpsHoldFrames = 3,
                    exitVisibleDpsHoldDecay = 0.85,
                    exitVisibleDpsHoldMin = 0.02,
                    exitVisibleLongMotionWindowFrames = 6,
                    visibleExitPoseTransitionModel = VisibleExitPoseTransitionModel.OUTSIDE_PLUS_TARGET,
                    allowVisibleExitPolygonRecovery = false,
                    recoveryTargetContainmentMin = 0.60,
                    // 积分已承担连续帧证据，避免再叠加 2/2。
                    enterConfirmFrames = 1,
                    visiblePolygonSyncFrames = 0
                ),
                versionId = VERSION_V1_3_1_B03030340
            )
            VERSION_V1_1_1_B03021717 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params,
                versionId = VERSION_V1_1_1_B03021717
            )
            VERSION_V1_1_0_B03021639 -> PresenceAlgorithmV1_1_1_B03021717(
                params = params,
                versionId = VERSION_V1_1_0_B03021639
            )
            VERSION_V1_0_0_B03011413 -> PresenceAlgorithmV1_0_0_B03011413(params)
            else -> PresenceAlgorithmV1_0_0_B03011413(params)
        }
    }
}

private fun buildActiveMainlineParams(params: PresenceEstimatorParams): PresenceEstimatorParams {
    return PresenceEstimatorParams(
        nearDoorDist = params.nearDoorDist,
        disappearDoorDist = params.disappearDoorDist,
        nearDoorAlongMargin = params.nearDoorAlongMargin,
        deepEnterMargin = params.deepEnterMargin,
        confirmNFramesBlind = params.confirmNFramesBlind,
        confirmNFramesOutside = params.confirmNFramesOutside,
        doorSeparationMargin = params.doorSeparationMargin,
        stableDoorFrames = params.stableDoorFrames,
        staleTrackFrames = params.staleTrackFrames,
        enterDoorD0 = 0.015,
        enterDoorD1 = 0.08,
        enterDoorDynamicRatio = 0.08,
        enterWeightA = params.enterWeightA,
        enterWeightC = params.enterWeightC,
        enterThreshold = 0.30,
        enterMotionWindowFrames = 3,
        enterNormalAdvanceMin = 0.008,
        enterLateralRatioMin = 0.35,
        enterGroundFootBlendStartConfidence = 0.60,
        exitSourceRoomScoreThreshold = 0.40,
        exitOutsidePoseScoreThreshold = 0.08,
        exitPosePointMinConfidence = 0.05,
        exitPoseMinConfidence = 0.20,
        grayPoseMinConfidenceForSwitch = 0.45,
        unifiedPoseScoreWeight = 0.80,
        unifiedPoseScoreWeightNoPolygon = 0.30,
        enterVisibleRequireNearDoor = false,
        enterVisibleNearDoorMultiplier = 1.4,
        enterVisibleNearDoorMin = 0.025,
        enterVisibleNearDoorLatchFrames = 0,
        enterVisibleRequireContainment = false,
        enterVisibleTargetContainmentMin = 0.75,
        enterVisibleSourceContainmentMax = 0.20,
        enterVisibleRequireDoorEvidence = false,
        enterVisibleDoorAssistMin = 0.10,
        useSwitchScoreIntegrator = true,
        poseHardRejectMinConfidence = 0.20,
        softDoorClearTau = 0.002,
        poseNearGateDps0 = 0.12,
        doorProximitySoftTailRatio = params.doorProximitySoftTailRatio,
        enableVisibleCandidateStickiness = params.enableVisibleCandidateStickiness,
        candidateStickAllZeroEps = params.candidateStickAllZeroEps,
        candidateStickFreezeRatio = params.candidateStickFreezeRatio,
        candidateStickSwitchMargin = params.candidateStickSwitchMargin,
        enableFallbackCandidateFreeze = params.enableFallbackCandidateFreeze,
        switchEvidenceBeta = 0.72,
        switchEvidenceThreshold = 0.56,
        switchEvidenceMax = 2.0,
        useSoftDoorClearGate = true,
        exitVisibleRequireNearDoor = false,
        exitVisibleNearDoorMultiplier = 1.4,
        exitVisibleNearDoorMin = 0.025,
        exitVisibleDpsHoldFrames = 3,
        exitVisibleDpsHoldDecay = 0.85,
        exitVisibleDpsHoldMin = 0.02,
        exitVisibleLongMotionWindowFrames = 6,
        visibleExitPoseTransitionModel = VisibleExitPoseTransitionModel.OUTSIDE_PLUS_TARGET,
        allowVisibleExitPolygonRecovery = false,
        recoveryTargetContainmentMin = 0.60,
        enterConfirmFrames = 1,
        enterAMin = 0.35,
        enterLowConfThreshold = params.enterLowConfThreshold,
        enterCStrict = params.enterCStrict,
        enterAMinStrict = params.enterAMinStrict,
        cContainmentGrid = params.cContainmentGrid,
        enterConfirmedGraceFrames = params.enterConfirmedGraceFrames,
        visiblePolygonSyncFrames = 0,
        visiblePolygonSyncMinContainment = params.visiblePolygonSyncMinContainment
    )
}

/**
 * Presence 算法 V1.0.0(B03011413)。
 *
 * 说明：
 * - 当前实现直接复用 RoomTransitionEstimator 现有行为，保证线上逻辑不变
 * - 版本化后的后续迭代只需新增实现并在注册表登记
 */
class PresenceAlgorithmV1_0_0_B03011413(
    params: PresenceEstimatorParams = PresenceEstimatorParams()
) : PresenceAlgorithmEngine {

    override val versionId: String = PresenceAlgorithmRegistry.VERSION_V1_0_0_B03011413
    private val estimator = RoomTransitionEstimator(params)

    override fun reset() {
        estimator.reset()
    }

    override fun processFrame(
        rooms: List<PresenceRoomSnapshot>,
        doors: List<PresenceDoorSnapshot>,
        observations: List<PresenceTrackObservation>,
        outsideMode: PresenceOutsideMode
    ): PresenceFrameResult {
        return estimator.processFrame(
            rooms = rooms,
            doors = doors,
            observations = observations,
            outsideMode = outsideMode
        )
    }
}
