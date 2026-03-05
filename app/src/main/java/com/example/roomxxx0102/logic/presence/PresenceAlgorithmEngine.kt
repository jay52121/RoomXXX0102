package com.example.roomxxx0102.logic.presence

/**
 * Presence 算法引擎统一接口。
 *
 * 作用：
 * 1) 让主流程只依赖稳定接口，便于后续频繁迭代算法
 * 2) 支持在设置页按版本切换
 */
interface PresenceAlgorithmEngine {
    /**
     * 当前算法版本标识，例如：V1.0.0(B03011413)
     */
    val versionId: String

    /**
     * 运行态标识：用于日志追踪“当前实际生效参数”。
     * 默认与 versionId 一致；新实现可附带 baselineId / paramsHash。
     */
    val runtimeTag: String
        get() = versionId

    /**
     * 清空算法内部运行态。
     */
    fun reset()

    /**
     * 处理单帧观测，输出 Presence 估计结果。
     */
    fun processFrame(
        rooms: List<PresenceRoomSnapshot>,
        doors: List<PresenceDoorSnapshot>,
        observations: List<PresenceTrackObservation>,
        outsideMode: PresenceOutsideMode = PresenceOutsideMode.INVISIBLE
    ): PresenceFrameResult
}
