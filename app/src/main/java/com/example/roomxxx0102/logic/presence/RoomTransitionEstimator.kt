package com.example.roomxxx0102.logic.presence

import kotlin.math.max

/**
 * 目标强度分层。
 * - WEAK：弱目标（仅 debug/observed，不触发 Presence 事件）
 * - STRONG：强目标（仅 debug/observed，不触发 Presence 事件）
 * - CONFIRMED：超强目标（唯一允许触发 Presence 事件）
 */
enum class PresenceStrength {
    WEAK,
    STRONG,
    CONFIRMED
}

/**
 * 外部模式（预留）。
 */
enum class PresenceOutsideMode {
    VISIBLE,
    INVISIBLE
}

/**
 * 房间切换事件原因。
 */
enum class PresenceEventReason {
    VISIBLE_SWITCH,
    PENDING_CONFIRMED,
    VISIBLE_POLYGON_SYNC,
    ORIGIN_SWITCH
}

/**
 * 供 UI 展示的切换分数类型（以客厅为参照）。
 */
enum class PresenceSwitchDisplayType {
    ENTER_SUB_ROOM,
    EXIT_SUB_ROOM,
    UNKNOWN
}

/**
 * 每个 track 在当前帧的切换分数提示（用于 UI 文本显示）。
 */
data class PresenceTrackSwitchScore(
    val score: Double,
    val type: PresenceSwitchDisplayType,
    val fromRoomId: String? = null,
    val toRoomId: String? = null
)

/**
 * 每帧输入的观测目标。
 */
data class PresenceTrackObservation(
    val trackId: Int,
    val landingPoint: PresencePoint,
    val strength: PresenceStrength,
    // 当前观测对应的视频时间（毫秒）；不可用时为 -1。
    val timestampMs: Long = -1L,
    // 地面落点可信度（0~1），用于门口接近评分的置信度加权。
    val groundConfidence: Double = 1.0,
    // 人框（归一化坐标），用于“可视区包含程度”评分；为空时 C 评分默认为 0。
    val personBox: PresenceRect? = null,
    // 全身关键点（归一化坐标），用于“可视区外综合得分”。
    val keypoints: List<PresenceKeypoint> = emptyList()
)

/**
 * 归一化坐标系下的姿态关键点。
 */
data class PresenceKeypoint(
    val x: Double,
    val y: Double,
    val confidence: Double
)

/**
 * 归一化坐标系下的人框。
 */
data class PresenceRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    val width: Double get() = (right - left).coerceAtLeast(0.0)
    val height: Double get() = (bottom - top).coerceAtLeast(0.0)
    val area: Double get() = width * height
}

/**
 * 房间快照（估计器输入）。
 */
data class PresenceRoomSnapshot(
    val roomId: String,
    val roomName: String,
    val polygon: List<PresencePoint>,
    val isLivingRoom: Boolean,
    val isBlindZone: Boolean,
    val isEntranceRoom: Boolean
)

/**
 * 门线快照（估计器输入）。
 */
data class PresenceDoorSnapshot(
    val doorId: String,
    val a: PresencePoint,
    val b: PresencePoint,
    val roomAId: String,
    val roomBId: String,
    val isEntranceDoor: Boolean
)

/**
 * 可视子房间 -> 客厅 时，主体过渡分模型。
 */
enum class VisibleExitPoseTransitionModel {
    OUTSIDE_ONLY,
    OUTSIDE_PLUS_TARGET
}

/**
 * 可调参数集合。
 * 注意：全部以归一化坐标（0~1）为单位。
 */
data class PresenceEstimatorParams(
    val nearDoorDist: Double = 0.02,
    val disappearDoorDist: Double = 0.015,
    val nearDoorAlongMargin: Double = 0.10,
    val deepEnterMargin: Double = 0.01,
    val confirmNFramesBlind: Int = 3,
    val confirmNFramesOutside: Int = 3,
    val doorSeparationMargin: Double = 0.003,
    val stableDoorFrames: Int = 2,
    val staleTrackFrames: Int = 120,
    // AC 评分相关参数（V1.1+ 使用；V1.0 可忽略）
    val enterDoorD0: Double = 0.01,
    val enterDoorD1: Double = 0.05,
    // 动态门距阈值系数：nearDist = personBoxHeight * enterDoorDynamicRatio。
    // 当 doorDist >= nearDist 时，doorProximityScore 直接为 0。
    val enterDoorDynamicRatio: Double = 0.10,
    val enterWeightA: Double = 0.70,
    val enterWeightC: Double = 0.30,
    val enterThreshold: Double = 0.62,
    // 可视房间进入（门洞穿越）运动窗口帧数。
    val enterMotionWindowFrames: Int = 3,
    // 可视房间进入时，门法向最小净推进（Δs）阈值。
    val enterNormalAdvanceMin: Double = 0.008,
    // 可视房间进入时，法向推进/横向滑动的最小比值阈值。
    val enterLateralRatioMin: Double = 0.35,
    // 地面接触点融合里，脚踝开始起作用的置信度阈值 c0。
    val enterGroundFootBlendStartConfidence: Double = 0.60,
    // 源房间保留分（用于“出子房间->客厅”）：低于该阈值才允许离开源房间。
    val exitSourceRoomScoreThreshold: Double = 0.40,
    // 源房间“可视区外综合得分”阈值（用于“出子房间->客厅”）。
    // 分数定义：按关键点置信度加权后，位于源房间可视区外的比例。
    val exitOutsidePoseScoreThreshold: Double = 0.12,
    // 参与“可视区外综合得分”计算的最小关键点置信度。
    val exitPosePointMinConfidence: Double = 0.05,
    // 判定“走出房间”时要求的最低平均关键点置信度（用于区分“走出”与“房间内消失”）。
    val exitPoseMinConfidence: Double = 0.20,
    // 灰色人体过滤阈值：低于该平均置信度不参与房间切换判定。
    val grayPoseMinConfidenceForSwitch: Double = 0.55,
    // 统一切换分：主体（Pose-可视区关系）权重。
    val unifiedPoseScoreWeight: Double = 0.80,
    // 当缺少可视区域时，主体分降权。
    val unifiedPoseScoreWeightNoPolygon: Double = 0.30,
    // 客厅 -> 可视子房间：是否要求近门前置。
    val enterVisibleRequireNearDoor: Boolean = true,
    // 客厅 -> 可视子房间：近门判定门限（dnd 的倍率与最小值）。
    val enterVisibleNearDoorMultiplier: Double = 1.4,
    val enterVisibleNearDoorMin: Double = 0.025,
    // 客厅 -> 可视子房间：近门锁存帧数（0 表示禁用锁存）。
    val enterVisibleNearDoorLatchFrames: Int = 1,
    // 客厅 -> 可视子房间：是否启用 target/source 双门槛。
    val enterVisibleRequireContainment: Boolean = true,
    val enterVisibleTargetContainmentMin: Double = 0.75,
    val enterVisibleSourceContainmentMax: Double = 0.20,
    // 客厅 -> 可视子房间：是否要求“门证据”(门辅助分或法向运动通过)。
    val enterVisibleRequireDoorEvidence: Boolean = false,
    // 客厅 -> 可视子房间：门辅助分最小阈值。
    val enterVisibleDoorAssistMin: Double = 0.10,
    // V1.3.1：是否启用“单分数 + 连续积分”切换判定。
    val useSwitchScoreIntegrator: Boolean = false,
    // V1.3.1：极低姿态质量硬拒绝阈值（仅用于防噪声积分）。
    val poseHardRejectMinConfidence: Double = 0.20,
    // V1.3.1：门歧义软门控的 sigmoid 温度参数。
    val softDoorClearTau: Double = 0.002,
    // V1.3.1：近门软门控起始阈值（只作用于 PoseTerm）。
    val poseNearGateDps0: Double = 0.20,
    // V1.3.1：dps 软尾比例（仅用于可视门分数链路，避免 nearDist 边界断崖）。
    // dd0 = nearDist, dd1 = nearDist * (1 + ratio)，dd>=dd1 才严格归零。
    val doorProximitySoftTailRatio: Double = 1.0,
    // V1.3.1：候选粘性开关（仅可视门分数链路）。
    val enableVisibleCandidateStickiness: Boolean = true,
    // V1.3.1：all-zero 粘性阈值（best/second 同时低于该值才触发）。
    val candidateStickAllZeroEps: Double = 0.05,
    // V1.3.1：临界冻结触发比例（prevE >= ratio * eth）。
    val candidateStickFreezeRatio: Double = 0.85,
    // V1.3.1：临界冻结退出门限（best - prev >= margin 则允许切换）。
    val candidateStickSwitchMargin: Double = 0.08,
    // V1.3.1：FALLBACK 帧候选冻结开关（只冻结当前帧，不延续到下一帧）。
    val enableFallbackCandidateFreeze: Boolean = true,
    // V1.3.1：证据积分参数。
    val switchEvidenceBeta: Double = 0.72,
    val switchEvidenceThreshold: Double = 0.62,
    val switchEvidenceMax: Double = 2.0,
    // V1.3.1：是否将门歧义改为软门控（不再硬拒绝）。
    val useSoftDoorClearGate: Boolean = false,
    // 可视子房间 -> 客厅：是否要求近门前置。
    val exitVisibleRequireNearDoor: Boolean = true,
    // 可视子房间 -> 客厅：近门判定门限（dnd 的倍率与最小值）。
    val exitVisibleNearDoorMultiplier: Double = 1.4,
    val exitVisibleNearDoorMin: Double = 0.025,
    // 可视子房间 -> 客厅：doorAssist 专用 dps 极短保持参数（仅用于 EXIT_TO_LIVING 的 doorAssist 输入）。
    val exitVisibleDpsHoldFrames: Int = 2,
    val exitVisibleDpsHoldDecay: Double = 0.80,
    val exitVisibleDpsHoldMin: Double = 0.02,
    // 可视子房间 -> 客厅：门口推进使用长窗帧数（不足时回退短窗）。
    val exitVisibleLongMotionWindowFrames: Int = 6,
    // 可视子房间 -> 客厅：主体分模型。
    val visibleExitPoseTransitionModel: VisibleExitPoseTransitionModel = VisibleExitPoseTransitionModel.OUTSIDE_PLUS_TARGET,
    // 可视子房间 -> 客厅：是否允许 polygon recovery 候选。
    val allowVisibleExitPolygonRecovery: Boolean = false,
    // 通用恢复候选（基于 polygon 命中）最小包含比例。
    val recoveryTargetContainmentMin: Double = 0.60,
    val enterConfirmFrames: Int = 2,
    val enterAMin: Double = 0.25,
    val enterLowConfThreshold: Double = 0.30,
    val enterCStrict: Double = 0.85,
    val enterAMinStrict: Double = 0.15,
    val cContainmentGrid: Int = 8,
    // 最近曾为 CONFIRMED 的容忍窗口（用于门口遮挡掉锁后继续判定进入）
    val enterConfirmedGraceFrames: Int = 24,
    // 可视区兜底同步：连续命中帧数阈值
    val visiblePolygonSyncFrames: Int = 3,
    // 可视区兜底同步：最低包含比例阈值
    val visiblePolygonSyncMinContainment: Double = 0.55
)

/**
 * 每帧输出事件。
 */
data class PresenceSwitchEvent(
    val trackId: Int?,
    val fromRoomId: String,
    val toRoomId: String,
    val doorId: String,
    val reason: PresenceEventReason
)

/**
 * 每帧估计结果。
 */
data class PresenceFrameResult(
    val observedCounts: Map<String, Int>,
    val presenceCounts: Map<String, Int>,
    val events: List<PresenceSwitchEvent>,
    val pendingDoorCounters: Map<String, Int>,
    val rejectedReasons: List<String>,
    val trackSwitchScores: Map<Int, PresenceTrackSwitchScore> = emptyMap()
)

/**
 * 位置判定 / 房间切换事件估计器（独立工具类）。
 *
 * 设计目标：
 * 1) 仅 CONFIRMED 目标触发切换事件
 * 2) 支持盲区 pending 确认（可视 -> 盲区 / 外部不可视）
 * 3) 输出 PresenceCounts 与事件清单，便于主流程接入
 */
class RoomTransitionEstimator(
    private val params: PresenceEstimatorParams = PresenceEstimatorParams()
) {

    private data class TrackRuntimeState(
        var currentPresenceRoomId: String? = null,
        var lastSeenFrame: Long = 0L,
        var stableDoorId: String? = null,
        var stableDoorFrames: Int = 0
    )

    private data class PendingTransition(
        val trackId: Int,
        val fromRoomId: String,
        val toRoomId: String,
        val doorId: String,
        var counterFrames: Int = 0
    )

    private data class DoorPickResult(
        val bestDoorId: String?,
        val isAmbiguous: Boolean,
        val candidatesDebug: List<String>
    )

    private val trackStates = mutableMapOf<Int, TrackRuntimeState>()
    private val pendingTransitions = mutableMapOf<Int, PendingTransition>()
    private val internalPresenceCounts = linkedMapOf<String, Int>()
    private var frameSeq: Long = 0L

    fun reset() {
        trackStates.clear()
        pendingTransitions.clear()
        internalPresenceCounts.clear()
        frameSeq = 0L
    }

    /**
     * 处理单帧观测并返回 Presence 结果。
     */
    fun processFrame(
        rooms: List<PresenceRoomSnapshot>,
        doors: List<PresenceDoorSnapshot>,
        observations: List<PresenceTrackObservation>,
        outsideMode: PresenceOutsideMode = PresenceOutsideMode.INVISIBLE
    ): PresenceFrameResult {
        frameSeq += 1
        syncPresenceRoomKeys(rooms)

        val roomById = rooms.associateBy { it.roomId }
        val observedCounts = linkedMapOf<String, Int>().apply {
            rooms.forEach { put(it.roomId, 0) }
            put("UNKNOWN", 0)
        }
        val events = mutableListOf<PresenceSwitchEvent>()
        val rejectedReasons = mutableListOf<String>()
        val confirmedTrackIds = hashSetOf<Int>()

        for (obs in observations) {
            val polygonRoomId = pickRoomByPolygon(obs.landingPoint, rooms)
            if (polygonRoomId == null) {
                observedCounts["UNKNOWN"] = (observedCounts["UNKNOWN"] ?: 0) + 1
            } else {
                observedCounts[polygonRoomId] = (observedCounts[polygonRoomId] ?: 0) + 1
            }

            if (obs.strength != PresenceStrength.CONFIRMED) {
                continue
            }

            confirmedTrackIds.add(obs.trackId)
            val state = trackStates.getOrPut(obs.trackId) { TrackRuntimeState() }
            state.lastSeenFrame = frameSeq

            val nearAnyDoor = pickBestDoor(obs.landingPoint, doors)
            if (!nearAnyDoor.isAmbiguous && nearAnyDoor.bestDoorId != null) {
                if (state.stableDoorId == nearAnyDoor.bestDoorId) {
                    state.stableDoorFrames += 1
                } else {
                    state.stableDoorId = nearAnyDoor.bestDoorId
                    state.stableDoorFrames = 1
                }
            } else {
                state.stableDoorId = null
                state.stableDoorFrames = 0
            }

            val pending = pendingTransitions[obs.trackId]
            if (pending != null && polygonRoomId == pending.fromRoomId) {
                // 回到原房间，说明之前 pending 误触发，取消。
                pendingTransitions.remove(obs.trackId)
            }

            if (state.currentPresenceRoomId == null) {
                if (polygonRoomId != null) {
                    state.currentPresenceRoomId = polygonRoomId
                    addPresence(polygonRoomId, 1)
                }
                continue
            }

            val fromRoomId = state.currentPresenceRoomId!!
            if (polygonRoomId != null && polygonRoomId != fromRoomId) {
                val betweenDoors = doors.filter { connectsRooms(it, fromRoomId, polygonRoomId) }
                if (betweenDoors.isEmpty()) {
                    rejectedReasons.add("track=${obs.trackId} NO_DOOR from=$fromRoomId to=$polygonRoomId")
                    continue
                }

                val doorPick = pickBestDoor(obs.landingPoint, betweenDoors)
                if (doorPick.bestDoorId == null || doorPick.isAmbiguous) {
                    val reason = if (doorPick.isAmbiguous) "AMBIGUOUS_DOOR" else "NOT_NEAR"
                    rejectedReasons.add("track=${obs.trackId} $reason from=$fromRoomId to=$polygonRoomId")
                    continue
                }

                val toRoom = roomById[polygonRoomId]
                val deepEnough = if (toRoom != null && toRoom.polygon.size >= 3 && !toRoom.isBlindZone) {
                    val d = PresenceGeometry.distancePointToPolygonBoundary(obs.landingPoint, toRoom.polygon)
                    d >= params.deepEnterMargin
                } else {
                    true
                }

                if (!deepEnough) {
                    rejectedReasons.add("track=${obs.trackId} NOT_DEEP_ENOUGH to=$polygonRoomId")
                    continue
                }

                applyTransition(
                    trackId = obs.trackId,
                    fromRoomId = fromRoomId,
                    toRoomId = polygonRoomId,
                    doorId = doorPick.bestDoorId,
                    reason = PresenceEventReason.VISIBLE_SWITCH,
                    events = events
                )
                state.currentPresenceRoomId = polygonRoomId
                pendingTransitions.remove(obs.trackId)
                continue
            }

            if (polygonRoomId == fromRoomId) {
                // 人仍在原房间，清掉旧 pending。
                pendingTransitions.remove(obs.trackId)
                continue
            }

            // polygonRoomId == null：可能在门口并即将进入盲区/外部不可视
            val stableDoorId = state.stableDoorId
            if (stableDoorId != null && state.stableDoorFrames >= params.stableDoorFrames) {
                val door = doors.firstOrNull { it.doorId == stableDoorId }
                if (door != null) {
                    val toRoomId = otherRoom(door, fromRoomId)
                    if (toRoomId != null && isInvisibleTargetRoom(toRoomId, roomById, outsideMode)) {
                        val old = pendingTransitions[obs.trackId]
                        if (old == null || old.toRoomId != toRoomId || old.doorId != door.doorId) {
                            pendingTransitions[obs.trackId] = PendingTransition(
                                trackId = obs.trackId,
                                fromRoomId = fromRoomId,
                                toRoomId = toRoomId,
                                doorId = door.doorId,
                                counterFrames = 0
                            )
                        }
                    }
                }
            }
        }

        // 处理“消失后的 pending 确认”
        val pendingSnapshot = pendingTransitions.values.toList()
        for (pending in pendingSnapshot) {
            if (confirmedTrackIds.contains(pending.trackId)) continue
            val state = trackStates[pending.trackId] ?: continue
            pending.counterFrames += 1

            val requiredFrames = if (pending.toRoomId == OUTSIDE_ROOM_ID) {
                params.confirmNFramesOutside
            } else {
                params.confirmNFramesBlind
            }

            if (pending.counterFrames >= requiredFrames) {
                applyTransition(
                    trackId = pending.trackId,
                    fromRoomId = pending.fromRoomId,
                    toRoomId = pending.toRoomId,
                    doorId = pending.doorId,
                    reason = PresenceEventReason.PENDING_CONFIRMED,
                    events = events
                )
                state.currentPresenceRoomId = pending.toRoomId
                pendingTransitions.remove(pending.trackId)
            }
        }

        // 清理超时状态（不改 Presence，仅避免状态表无限增长）
        val staleIds = trackStates
            .filterValues { frameSeq - it.lastSeenFrame > params.staleTrackFrames }
            .keys
            .toList()
        for (id in staleIds) {
            trackStates.remove(id)
            pendingTransitions.remove(id)
        }

        val pendingMap = linkedMapOf<String, Int>()
        for (p in pendingTransitions.values) {
            val key = "${p.trackId}:${p.fromRoomId}->${p.toRoomId}@${p.doorId}"
            pendingMap[key] = p.counterFrames
        }

        return PresenceFrameResult(
            observedCounts = observedCounts.toMap(),
            presenceCounts = internalPresenceCounts.toMap(),
            events = events.toList(),
            pendingDoorCounters = pendingMap.toMap(),
            rejectedReasons = rejectedReasons.toList()
        )
    }

    private fun syncPresenceRoomKeys(rooms: List<PresenceRoomSnapshot>) {
        val roomIds = rooms.map { it.roomId }.toSet()
        // 添加新房间 key
        rooms.forEach { room ->
            if (!internalPresenceCounts.containsKey(room.roomId)) {
                internalPresenceCounts[room.roomId] = 0
            }
        }
        // 删除已不存在房间 key
        val removed = internalPresenceCounts.keys.filter { !roomIds.contains(it) }
        removed.forEach { internalPresenceCounts.remove(it) }
        // 保底非负
        internalPresenceCounts.replaceAll { _, v -> max(0, v) }
    }

    private fun pickRoomByPolygon(point: PresencePoint, rooms: List<PresenceRoomSnapshot>): String? {
        val hits = mutableListOf<Pair<String, Double>>()
        for (room in rooms) {
            // 盲区房间按不可视处理：不参与 polygon 命中
            if (room.isBlindZone) continue
            if (room.polygon.size < 3) continue
            if (PresenceGeometry.isPointInPolygon(point, room.polygon)) {
                val depth = PresenceGeometry.distancePointToPolygonBoundary(point, room.polygon)
                hits.add(room.roomId to depth)
            }
        }
        if (hits.isEmpty()) return null
        return hits.maxByOrNull { it.second }?.first
    }

    private fun pickBestDoor(point: PresencePoint, doors: List<PresenceDoorSnapshot>): DoorPickResult {
        data class Candidate(val doorId: String, val score: Double, val dist: Double, val t: Double)

        val candidates = mutableListOf<Candidate>()
        for (door in doors) {
                val dist = PresenceGeometry.distancePointToSegment(point, door.a, door.b)
                val t = PresenceGeometry.projectTParamOnSegment(point, door.a, door.b)
                val inT = t >= -params.nearDoorAlongMargin && t <= 1.0 + params.nearDoorAlongMargin
                if (dist <= params.nearDoorDist && inT) {
                val segmentLen = kotlin.math.hypot(door.b.x - door.a.x, door.b.y - door.a.y)
                    val extensionPenalty = max(0.0, max(-t, t - 1.0)) * segmentLen
                    val score = dist + extensionPenalty
                    candidates.add(Candidate(door.doorId, score, dist, t))
                }
            }

        if (candidates.isEmpty()) {
            return DoorPickResult(bestDoorId = null, isAmbiguous = false, candidatesDebug = emptyList())
        }
        val sorted = candidates.sortedBy { it.score }
        val best = sorted.first()
        val debug = sorted.map { "${it.doorId}(d=${"%.4f".format(it.dist)},t=${"%.4f".format(it.t)},s=${"%.4f".format(it.score)})" }
        if (sorted.size >= 2) {
            val second = sorted[1]
            if ((second.score - best.score) < params.doorSeparationMargin) {
                return DoorPickResult(bestDoorId = null, isAmbiguous = true, candidatesDebug = debug)
            }
        }
        return DoorPickResult(bestDoorId = best.doorId, isAmbiguous = false, candidatesDebug = debug)
    }

    private fun connectRoomVisible(roomById: Map<String, PresenceRoomSnapshot>, roomId: String): Boolean {
        val room = roomById[roomId] ?: return false
        return !room.isBlindZone && room.polygon.size >= 3
    }

    private fun isInvisibleTargetRoom(
        roomId: String,
        roomById: Map<String, PresenceRoomSnapshot>,
        outsideMode: PresenceOutsideMode
    ): Boolean {
        if (roomId == OUTSIDE_ROOM_ID) return outsideMode == PresenceOutsideMode.INVISIBLE
        val room = roomById[roomId] ?: return false
        return room.isBlindZone || room.polygon.size < 3
    }

    private fun connectsRooms(door: PresenceDoorSnapshot, roomAId: String, roomBId: String): Boolean {
        return (door.roomAId == roomAId && door.roomBId == roomBId) ||
            (door.roomAId == roomBId && door.roomBId == roomAId)
    }

    private fun otherRoom(door: PresenceDoorSnapshot, currentRoomId: String): String? {
        return when (currentRoomId) {
            door.roomAId -> door.roomBId
            door.roomBId -> door.roomAId
            else -> null
        }
    }

    private fun addPresence(roomId: String, delta: Int) {
        val old = internalPresenceCounts[roomId] ?: 0
        internalPresenceCounts[roomId] = max(0, old + delta)
    }

    private fun applyTransition(
        trackId: Int?,
        fromRoomId: String,
        toRoomId: String,
        doorId: String,
        reason: PresenceEventReason,
        events: MutableList<PresenceSwitchEvent>
    ) {
        if (fromRoomId == toRoomId) return
        addPresence(fromRoomId, -1)
        addPresence(toRoomId, 1)
        events.add(
            PresenceSwitchEvent(
                trackId = trackId,
                fromRoomId = fromRoomId,
                toRoomId = toRoomId,
                doorId = doorId,
                reason = reason
            )
        )
    }

    companion object {
        const val OUTSIDE_ROOM_ID = "__outside__"
    }
}
