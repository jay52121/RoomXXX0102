package com.example.roomxxx0102.logic.presence

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Presence 算法 V1.1.1(B03021717)
 *
 * 核心改动：
 * - 可视房间进入判定由“单门口+走深”升级为 AC 评分模型
 * - A：地面点到门口接近度（A_pos）× 落点可信度（A_conf）
 * - C：人框被目标可视房间 polygon 包含比例
 * - Score = wA*A + wC*C，连续 K 帧触发进入
 *
 * 说明：
 * - 仅替换“可视房间进入”这段逻辑
 * - 盲区 pending / 外部不可视流程沿用旧版策略
 */
class PresenceAlgorithmV1_1_1_B03021717(
    private val params: PresenceEstimatorParams = PresenceEstimatorParams(),
    override val versionId: String = PresenceAlgorithmRegistry.VERSION_V1_1_1_B03021717,
    override val runtimeTag: String = versionId
) : PresenceAlgorithmEngine {
    private enum class BlindPendingPolicy {
        LEGACY,
        DISABLE_BLIND,
        ENTER_BLIND_ONLY
    }

    private data class ExitDoorAssistHoldState(
        var value: Double,
        var remainingFrames: Int
    )

    private data class DoorOriginSample(
        val frameSeq: Long,
        val timestampMs: Long,
        val point: PresencePoint
    )

    private data class DoorOriginCandidateScore(
        val fromRoomId: String,
        val doorId: String,
        val score: Double,
        val proximityScore: Double,
        val crossingScore: Double,
        val crossDetected: Boolean,
        val trendScore: Double,
        val distanceMin: Double,
        val anchorFrameSeq: Long,
        val anchorTimestampMs: Long
    )

    private data class DoorOriginDecision(
        val candidate: DoorOriginCandidateScore,
        val secondScore: Double,
        val blockedByLedger: Boolean,
        val blockedByLowScore: Boolean,
        val blockedByMargin: Boolean
    )

    private data class TrackRuntimeState(
        var currentPresenceRoomId: String? = null,
        var lastSeenFrame: Long = 0L,
        var lastConfirmedFrame: Long = 0L,
        var stableDoorId: String? = null,
        var stableDoorFrames: Int = 0,
        var enterCandidateRoomId: String? = null,
        var enterCandidateDoorId: String? = null,
        var enterCandidateFrames: Int = 0,
        var visibleSyncRoomId: String? = null,
        var visibleSyncFrames: Int = 0,
        var lastEstimatedGroundPoint: PresencePoint? = null,
        var lastScoredFromRoomId: String? = null,
        var lastScoredToRoomId: String? = null,
        var lastScoredDoorId: String? = null,
        var lastSwitchFromRoomId: String? = null,
        var lastSwitchToRoomId: String? = null,
        var lastSwitchDoorId: String? = null,
        var lastSwitchTimestampMs: Long = -1L,
        var lastSwitchFrameSeq: Long = -1L,
        val groundPointHistory: ArrayDeque<PresencePoint> = ArrayDeque(),
        val switchEvidenceByCandidate: MutableMap<String, Double> = linkedMapOf(),
        val exitDoorAssistHoldByCandidate: MutableMap<String, ExitDoorAssistHoldState> = linkedMapOf(),
        val doorOriginSamples: ArrayDeque<DoorOriginSample> = ArrayDeque(),
        var doorOriginInitWaitFrames: Int = 0
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
        val bestDist: Double,
        val isAmbiguous: Boolean,
        val candidatesDebug: List<String>
    )

    private data class EnterEvalResult(
        val confirmed: Boolean,
        val toRoomId: String?,
        val doorId: String?,
        val debugText: String,
        val displayScore: Double? = null,
        val displayType: PresenceSwitchDisplayType? = null,
        val displayFromRoomId: String? = null,
        val displayToRoomId: String? = null,
        val candidateKey: String? = null,
        val evidenceScore: Double? = null,
        val switchScore: Double? = null
    )

    private data class DoorMotionStats(
        val advanceDelta: Double,
        val lateralDelta: Double,
        val advanceLateralRatio: Double,
        val advanceDeltaShort: Double,
        val advanceDeltaLong: Double,
        val windowUsed: String,
        val pass: Boolean,
        val pastSignedDistance: Double,
        val currentSignedDistance: Double,
        val pastLateralDistance: Double,
        val currentLateralDistance: Double,
        val normalX: Double,
        val normalY: Double,
        val doorMidX: Double,
        val doorMidY: Double,
        val targetCentroidX: Double,
        val targetCentroidY: Double,
        val pastGroundX: Double,
        val pastGroundY: Double,
        val currentGroundX: Double,
        val currentGroundY: Double,
        val historySize: Int,
        val nearDoorPassed: Boolean
    )

    private data class DoorProximityScore(
        val raw: Double,
        val score: Double
    )

    private val trackStates = mutableMapOf<Int, TrackRuntimeState>()
    private val pendingTransitions = mutableMapOf<Int, PendingTransition>()
    private val internalPresenceCounts = linkedMapOf<String, Int>()
    private var frameSeq: Long = 0L
    private val blindPendingPolicy: BlindPendingPolicy = when (versionId) {
        PresenceAlgorithmRegistry.VERSION_V1_5_3_B03052330 -> BlindPendingPolicy.ENTER_BLIND_ONLY
        PresenceAlgorithmRegistry.VERSION_V1_5_1_B03052210 -> BlindPendingPolicy.ENTER_BLIND_ONLY
        PresenceAlgorithmRegistry.VERSION_V1_5_0_B03041530 -> BlindPendingPolicy.DISABLE_BLIND
        else -> BlindPendingPolicy.LEGACY
    }
    private val enableDoorOriginExit = versionId == PresenceAlgorithmRegistry.VERSION_V1_5_3_B03052330

    override fun reset() {
        trackStates.clear()
        pendingTransitions.clear()
        internalPresenceCounts.clear()
        frameSeq = 0L
    }

    override fun processFrame(
        rooms: List<PresenceRoomSnapshot>,
        doors: List<PresenceDoorSnapshot>,
        observations: List<PresenceTrackObservation>,
        outsideMode: PresenceOutsideMode
    ): PresenceFrameResult {
        frameSeq += 1
        syncPresenceRoomKeys(rooms)

        val roomById = rooms.associateBy { it.roomId }
        val livingRoomId = rooms.firstOrNull { it.isLivingRoom }?.roomId
        val observedCounts = linkedMapOf<String, Int>().apply {
            rooms.forEach { put(it.roomId, 0) }
            put("UNKNOWN", 0)
        }
        val events = mutableListOf<PresenceSwitchEvent>()
        val rejectedReasons = mutableListOf<String>()
        val observedTrackIds = hashSetOf<Int>()
        val trackSwitchScores = linkedMapOf<Int, PresenceTrackSwitchScore>()

        for (obs in observations) {
            observedTrackIds.add(obs.trackId)
            val polygonRoomId = pickRoomByPolygon(obs.landingPoint, rooms)
            if (polygonRoomId == null) {
                observedCounts["UNKNOWN"] = (observedCounts["UNKNOWN"] ?: 0) + 1
            } else {
                observedCounts[polygonRoomId] = (observedCounts[polygonRoomId] ?: 0) + 1
            }
            val state = trackStates.getOrPut(obs.trackId) { TrackRuntimeState() }
            val estimatedGroundPoint = estimateGroundPoint(obs)
            val roomNowBeforeIdentityReset = state.currentPresenceRoomId
            val gapFrames = if (state.lastSeenFrame > 0L) {
                (frameSeq - state.lastSeenFrame).toInt().coerceAtLeast(0)
            } else {
                0
            }
            val jumpDist = state.lastEstimatedGroundPoint?.let { last ->
                hypot(
                    estimatedGroundPoint.x - last.x,
                    estimatedGroundPoint.y - last.y
                )
            } ?: 0.0
            val identityResetReason = buildIdentityResetReason(
                gapFrames = gapFrames,
                jumpDist = jumpDist
            )
            val identityResetApplied = roomNowBeforeIdentityReset != null &&
                identityResetReason != "NONE"
            if (identityResetApplied) {
                resetTrackIdentityState(state)
                pendingTransitions.remove(obs.trackId)
                rejectedReasons.add(
                    "track=${obs.trackId} identityResetApplied=true " +
                        "resetReason=$identityResetReason " +
                        "gapFrames=$gapFrames " +
                        "jumpDist=${fmt(jumpDist)} " +
                        "roomNowBefore=${roomNowBeforeIdentityReset ?: "-"} " +
                        "roomNowAfter=${polygonRoomId ?: "UNKNOWN"}"
                )
            }
            state.lastSeenFrame = frameSeq
            val isConfirmedNow = obs.strength == PresenceStrength.CONFIRMED
            if (isConfirmedNow) {
                state.lastConfirmedFrame = frameSeq
            }
            state.lastEstimatedGroundPoint = estimatedGroundPoint
            appendDoorOriginSample(state, obs, estimatedGroundPoint)

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
                pendingTransitions.remove(obs.trackId)
            }

            if (state.currentPresenceRoomId == null) {
                if (isConfirmedNow && polygonRoomId != null) {
                    if (enableDoorOriginExit &&
                        livingRoomId != null &&
                        polygonRoomId == livingRoomId
                    ) {
                        val originDecision = resolveDoorOriginToLiving(
                            state = state,
                            roomById = roomById,
                            doors = doors,
                            livingRoomId = livingRoomId,
                            preferredFromRoomId = null
                        )
                        if (originDecision != null) {
                            if (!originDecision.blockedByLowScore &&
                                !originDecision.blockedByMargin &&
                                !originDecision.blockedByLedger
                            ) {
                                val switched = applyTransition(
                                    trackId = obs.trackId,
                                    fromRoomId = originDecision.candidate.fromRoomId,
                                    toRoomId = livingRoomId,
                                    doorId = originDecision.candidate.doorId,
                                    reason = PresenceEventReason.ORIGIN_SWITCH,
                                    events = events,
                                    rejectedReasons = rejectedReasons,
                                    blockContext = "candidate=${originDecision.candidate.fromRoomId}@${originDecision.candidate.doorId} " +
                                        "originScore=${fmt(originDecision.candidate.score)} " +
                                        "originP=${fmt(originDecision.candidate.proximityScore)} " +
                                        "originC=${fmt(originDecision.candidate.crossingScore)}"
                                )
                                if (switched) {
                                    recordLastSwitch(
                                        state = state,
                                        fromRoomId = originDecision.candidate.fromRoomId,
                                        toRoomId = livingRoomId,
                                        doorId = originDecision.candidate.doorId,
                                        timestampMs = originDecision.candidate.anchorTimestampMs
                                    )
                                    state.currentPresenceRoomId = livingRoomId
                                    state.doorOriginInitWaitFrames = 0
                                    resetStateAfterCommittedEvent(state)
                                    pendingTransitions.remove(obs.trackId)
                                    rejectedReasons.add(
                                        "track=${obs.trackId} ORIGIN_INIT_OK " +
                                            "from=${originDecision.candidate.fromRoomId} " +
                                            "door=${originDecision.candidate.doorId} " +
                                            "score=${fmt(originDecision.candidate.score)} " +
                                            "cross=${originDecision.candidate.crossDetected} " +
                                            "anchorF=${originDecision.candidate.anchorFrameSeq}"
                                    )
                                    appendGroundPointHistory(state, estimatedGroundPoint)
                                    continue
                                }
                            } else {
                                rejectedReasons.add(
                                    "track=${obs.trackId} ORIGIN_INIT_SKIP " +
                                        "from=${originDecision.candidate.fromRoomId} door=${originDecision.candidate.doorId} " +
                                        "score=${fmt(originDecision.candidate.score)} second=${fmt(originDecision.secondScore)} " +
                                        "low=${originDecision.blockedByLowScore} margin=${originDecision.blockedByMargin} " +
                                        "ledger=${originDecision.blockedByLedger}"
                                )
                            }
                        }
                        if (state.doorOriginInitWaitFrames < DOOR_ORIGIN_INIT_WAIT_FRAMES) {
                            state.doorOriginInitWaitFrames += 1
                            rejectedReasons.add(
                                "track=${obs.trackId} INIT_WAIT_ORIGIN " +
                                    "frames=${state.doorOriginInitWaitFrames}/$DOOR_ORIGIN_INIT_WAIT_FRAMES room=$polygonRoomId"
                            )
                            appendGroundPointHistory(state, estimatedGroundPoint)
                            continue
                        }
                    }
                    state.currentPresenceRoomId = polygonRoomId
                    state.doorOriginInitWaitFrames = 0
                    clearSwitchEvidence(state)
                    addPresence(polygonRoomId, 1)
                    rejectedReasons.add("track=${obs.trackId} INIT room=$polygonRoomId")
                } else {
                    rejectedReasons.add("track=${obs.trackId} INIT_WAIT confirmed=$isConfirmedNow room=${polygonRoomId ?: "UNKNOWN"}")
                }
                appendGroundPointHistory(state, estimatedGroundPoint)
                continue
            }

            val fromRoomId = state.currentPresenceRoomId!!
            if (enableDoorOriginExit &&
                isConfirmedNow &&
                livingRoomId != null &&
                fromRoomId != livingRoomId &&
                polygonRoomId == livingRoomId
            ) {
                val originDecision = resolveDoorOriginToLiving(
                    state = state,
                    roomById = roomById,
                    doors = doors,
                    livingRoomId = livingRoomId,
                    preferredFromRoomId = fromRoomId
                )
                if (originDecision != null) {
                    if (!originDecision.blockedByLowScore &&
                        !originDecision.blockedByMargin &&
                        !originDecision.blockedByLedger
                    ) {
                        val switched = applyTransition(
                            trackId = obs.trackId,
                            fromRoomId = fromRoomId,
                            toRoomId = livingRoomId,
                            doorId = originDecision.candidate.doorId,
                            reason = PresenceEventReason.ORIGIN_SWITCH,
                            events = events,
                            rejectedReasons = rejectedReasons,
                            blockContext = "candidate=$fromRoomId@${originDecision.candidate.doorId} " +
                                "originScore=${fmt(originDecision.candidate.score)} " +
                                "originP=${fmt(originDecision.candidate.proximityScore)} " +
                                "originC=${fmt(originDecision.candidate.crossingScore)}"
                        )
                        if (switched) {
                            recordLastSwitch(
                                state = state,
                                fromRoomId = fromRoomId,
                                toRoomId = livingRoomId,
                                doorId = originDecision.candidate.doorId,
                                timestampMs = originDecision.candidate.anchorTimestampMs
                            )
                            state.currentPresenceRoomId = livingRoomId
                            resetStateAfterCommittedEvent(state)
                            pendingTransitions.remove(obs.trackId)
                            rejectedReasons.add("track=${obs.trackId} eventResetApplied=true lastSwitchKept=true")
                            rejectedReasons.add(
                                "track=${obs.trackId} ORIGIN_EXIT_OK from=$fromRoomId door=${originDecision.candidate.doorId} " +
                                    "score=${fmt(originDecision.candidate.score)} " +
                                    "cross=${originDecision.candidate.crossDetected} " +
                                    "anchorF=${originDecision.candidate.anchorFrameSeq}"
                            )
                            appendGroundPointHistory(state, estimatedGroundPoint)
                            continue
                        }
                    } else {
                        rejectedReasons.add(
                            "track=${obs.trackId} ORIGIN_EXIT_SKIP from=$fromRoomId door=${originDecision.candidate.doorId} " +
                                "score=${fmt(originDecision.candidate.score)} second=${fmt(originDecision.secondScore)} " +
                                "low=${originDecision.blockedByLowScore} margin=${originDecision.blockedByMargin} " +
                                "ledger=${originDecision.blockedByLedger}"
                        )
                    }
                } else {
                    rejectedReasons.add("track=${obs.trackId} ORIGIN_EXIT_WAIT from=$fromRoomId")
                }
            }
            val inGrace = state.lastConfirmedFrame > 0 &&
                (frameSeq - state.lastConfirmedFrame) <= params.enterConfirmedGraceFrames
            val canEvaluateVisibleEnter = isConfirmedNow || inGrace
            var switchedByVisible = false

            if (canEvaluateVisibleEnter) {
                val eval = evaluateVisibleEnterByScore(
                    obs = obs,
                    state = state,
                    fromRoomId = fromRoomId,
                    polygonRoomId = polygonRoomId,
                    roomById = roomById,
                    doors = doors,
                    estimatedGroundPoint = estimatedGroundPoint
                )
                if (isConfirmedNow &&
                    eval.displayScore != null &&
                    eval.displayType != null
                ) {
                    trackSwitchScores[obs.trackId] = PresenceTrackSwitchScore(
                        score = eval.displayScore,
                        type = eval.displayType,
                        fromRoomId = eval.displayFromRoomId,
                        toRoomId = eval.displayToRoomId
                    )
                }
                if (eval.confirmed && eval.doorId != null && eval.toRoomId != null) {
                    val switched = applyTransition(
                        trackId = obs.trackId,
                        fromRoomId = fromRoomId,
                        toRoomId = eval.toRoomId,
                        doorId = eval.doorId,
                        reason = PresenceEventReason.VISIBLE_SWITCH,
                        events = events,
                        rejectedReasons = rejectedReasons,
                        blockContext = "candidate=${eval.candidateKey ?: "-"} " +
                            "ss=${eval.switchScore?.let { fmt(it) } ?: "-"} " +
                            "e=${eval.evidenceScore?.let { fmt(it) } ?: "-"}"
                    )
                    if (switched) {
                        recordLastSwitch(
                            state = state,
                            fromRoomId = fromRoomId,
                            toRoomId = eval.toRoomId,
                            doorId = eval.doorId,
                            timestampMs = obs.timestampMs
                        )
                        state.currentPresenceRoomId = eval.toRoomId
                        resetStateAfterCommittedEvent(state)
                        pendingTransitions.remove(obs.trackId)
                        rejectedReasons.add("track=${obs.trackId} eventResetApplied=true lastSwitchKept=true")
                        rejectedReasons.add("track=${obs.trackId} ${eval.debugText}")
                        switchedByVisible = true
                    }
                } else {
                    rejectedReasons.add("track=${obs.trackId} ${eval.debugText}")
                }
            } else {
                resetEnterCandidate(state)
                val graceAge = if (state.lastConfirmedFrame <= 0L) "INF" else "${frameSeq - state.lastConfirmedFrame}"
                rejectedReasons.add(
                    "track=${obs.trackId} NOT_CONFIRMED grace=$graceAge" +
                        "/${params.enterConfirmedGraceFrames}"
                )
            }

            // 可视区兜底同步：连续 N 帧稳定落在另一个可视房间时，同步 Presence。
            // 可通过 visiblePolygonSyncFrames <= 0 彻底关闭（用于对照主判定）。
            val visibleSyncEnabled = params.visiblePolygonSyncFrames > 0
            if (visibleSyncEnabled && !switchedByVisible && polygonRoomId != null && polygonRoomId != fromRoomId) {
                val targetRoom = roomById[polygonRoomId]
                val containment = computeRoomPoseScore(obs.keypoints, targetRoom?.polygon ?: emptyList())
                val eligibleByStrength = obs.strength != PresenceStrength.WEAK || inGrace
                val passedContainment = containment >= params.visiblePolygonSyncMinContainment
                if (targetRoom != null && !targetRoom.isBlindZone && targetRoom.polygon.size >= 3 &&
                    eligibleByStrength && passedContainment
                ) {
                    if (state.visibleSyncRoomId == polygonRoomId) {
                        state.visibleSyncFrames += 1
                    } else {
                        state.visibleSyncRoomId = polygonRoomId
                        state.visibleSyncFrames = 1
                    }
                    val need = params.visiblePolygonSyncFrames.coerceAtLeast(1)
                    if (state.visibleSyncFrames >= need) {
                        val switched = applyTransition(
                            trackId = obs.trackId,
                            fromRoomId = fromRoomId,
                            toRoomId = polygonRoomId,
                            doorId = "visible_sync",
                            reason = PresenceEventReason.VISIBLE_POLYGON_SYNC,
                            events = events,
                            rejectedReasons = rejectedReasons,
                            blockContext = "candidate=${polygonRoomId}@visible_sync ss=- e=-"
                        )
                        if (switched) {
                            recordLastSwitch(
                                state = state,
                                fromRoomId = fromRoomId,
                                toRoomId = polygonRoomId,
                                doorId = "visible_sync",
                                timestampMs = obs.timestampMs
                            )
                            state.currentPresenceRoomId = polygonRoomId
                            resetStateAfterCommittedEvent(state)
                            pendingTransitions.remove(obs.trackId)
                            rejectedReasons.add("track=${obs.trackId} eventResetApplied=true lastSwitchKept=true")
                            rejectedReasons.add(
                                "track=${obs.trackId} VISIBLE_SYNC_OK from=$fromRoomId to=$polygonRoomId " +
                                    "frames=$need/$need C=${fmt(containment)}"
                            )
                        } else {
                            state.visibleSyncRoomId = null
                            state.visibleSyncFrames = 0
                        }
                    } else {
                        rejectedReasons.add(
                            "track=${obs.trackId} VISIBLE_SYNC_WAIT from=$fromRoomId to=$polygonRoomId " +
                                "frames=${state.visibleSyncFrames}/${params.visiblePolygonSyncFrames} C=${fmt(containment)}"
                        )
                    }
                } else {
                    state.visibleSyncRoomId = null
                    state.visibleSyncFrames = 0
                    rejectedReasons.add(
                        "track=${obs.trackId} VISIBLE_SYNC_REJECT from=$fromRoomId to=$polygonRoomId " +
                            "C=${fmt(containment)} need=${fmt(params.visiblePolygonSyncMinContainment)}"
                    )
                }
            } else {
                state.visibleSyncRoomId = null
                state.visibleSyncFrames = 0
            }

            if (polygonRoomId == fromRoomId || switchedByVisible) {
                pendingTransitions.remove(obs.trackId)
            }
            if (isConfirmedNow) {
                val stableDoorId = state.stableDoorId
                if (stableDoorId != null && state.stableDoorFrames >= params.stableDoorFrames) {
                    val door = doors.firstOrNull { it.doorId == stableDoorId }
                    if (door != null) {
                        val toRoomId = otherRoom(door, fromRoomId)
                        if (toRoomId != null && isInvisibleTargetRoom(toRoomId, roomById, outsideMode)) {
                            if (shouldDisableBlindPending(fromRoomId, toRoomId, roomById)) {
                                pendingTransitions.remove(obs.trackId)
                                rejectedReasons.add(
                                    "track=${obs.trackId} pendingDisabled=true mode=${blindPendingPolicy.name} " +
                                        "from=$fromRoomId to=$toRoomId door=${door.doorId}"
                                )
                            } else {
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
            }
            appendGroundPointHistory(state, estimatedGroundPoint)
        }

        val pendingSnapshot = pendingTransitions.values.toList()
        for (pending in pendingSnapshot) {
            if (shouldDisableBlindPending(pending.fromRoomId, pending.toRoomId, roomById)) {
                pendingTransitions.remove(pending.trackId)
                rejectedReasons.add(
                    "track=${pending.trackId} pendingDropped=true mode=${blindPendingPolicy.name} " +
                        "from=${pending.fromRoomId} to=${pending.toRoomId} door=${pending.doorId}"
                )
                continue
            }
            if (observedTrackIds.contains(pending.trackId)) continue
            val state = trackStates[pending.trackId] ?: continue
            pending.counterFrames += 1

            val requiredFrames = if (pending.toRoomId == OUTSIDE_ROOM_ID) {
                params.confirmNFramesOutside
            } else {
                params.confirmNFramesBlind
            }

            if (pending.counterFrames >= requiredFrames) {
                val switched = applyTransition(
                    trackId = pending.trackId,
                    fromRoomId = pending.fromRoomId,
                    toRoomId = pending.toRoomId,
                    doorId = pending.doorId,
                    reason = PresenceEventReason.PENDING_CONFIRMED,
                    events = events,
                    rejectedReasons = rejectedReasons,
                    blockContext = "candidate=${pending.toRoomId}@${pending.doorId} ss=- e=-"
                )
                if (switched) {
                    recordLastSwitch(
                        state = state,
                        fromRoomId = pending.fromRoomId,
                        toRoomId = pending.toRoomId,
                        doorId = pending.doorId,
                        timestampMs = -1L
                    )
                    state.currentPresenceRoomId = pending.toRoomId
                    resetStateAfterCommittedEvent(state)
                    rejectedReasons.add("track=${pending.trackId} eventResetApplied=true lastSwitchKept=true")
                }
                pendingTransitions.remove(pending.trackId)
            }
        }

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

        if (observations.isEmpty()) {
            rejectedReasons.add("NO_OBSERVATION")
        } else if (rejectedReasons.isEmpty()) {
            rejectedReasons.add("NO_DECISION")
        }

        return PresenceFrameResult(
            observedCounts = observedCounts.toMap(),
            presenceCounts = internalPresenceCounts.toMap(),
            events = events.toList(),
            pendingDoorCounters = pendingMap.toMap(),
            rejectedReasons = rejectedReasons.toList(),
            trackSwitchScores = trackSwitchScores.toMap()
        )
    }

    private fun evaluateVisibleEnterByScore(
        obs: PresenceTrackObservation,
        state: TrackRuntimeState,
        fromRoomId: String,
        polygonRoomId: String?,
        roomById: Map<String, PresenceRoomSnapshot>,
        doors: List<PresenceDoorSnapshot>,
        estimatedGroundPoint: PresencePoint
    ): EnterEvalResult {
        val fromRoom = roomById[fromRoomId]
        val sourceRoomPolygon = fromRoom?.polygon ?: emptyList()
        val sourceRoomContainmentRatio = computeRoomPoseScore(obs.keypoints, sourceRoomPolygon)
        val sourceRoomOutsidePoseScore = (1.0 - sourceRoomContainmentRatio).coerceIn(0.0, 1.0)
        val poseAverageConfidence = computeAveragePoseConfidence(obs.keypoints)
        val poseEffectiveConfidence = computeEffectivePoseConfidence(
            keypoints = obs.keypoints,
            minConfidence = params.exitPosePointMinConfidence
        )
        if (params.useSwitchScoreIntegrator) {
            decaySwitchEvidence(state)
        }
        if (params.useSwitchScoreIntegrator &&
            poseAverageConfidence < params.poseHardRejectMinConfidence
        ) {
            resetEnterCandidate(state)
            return EnterEvalResult(
                confirmed = false,
                toRoomId = null,
                doorId = null,
                debugText = "SKIP_HARD_POSE from=$fromRoomId " +
                    "poseAverageConfidence=${fmt(poseAverageConfidence)} " +
                    "poseHardRejectMinConfidence=${fmt(params.poseHardRejectMinConfidence)}"
            )
        }
        if (!params.useSwitchScoreIntegrator &&
            poseAverageConfidence < params.grayPoseMinConfidenceForSwitch
        ) {
            resetEnterCandidate(state)
            return EnterEvalResult(
                confirmed = false,
                toRoomId = null,
                doorId = null,
                debugText = "SKIP_GRAY_POSE from=$fromRoomId " +
                    "poseAverageConfidence=${fmt(poseAverageConfidence)} " +
                    "grayPoseMinConfidence=${fmt(params.grayPoseMinConfidenceForSwitch)}"
            )
        }

        data class Candidate(
            val toRoomId: String,
            val doorId: String,
            val doorDist: Double,
            val nearDist: Double,
            val doorProximityScoreRaw: Double,
            val doorProximityScore: Double,
            val doorProximityScoreEnter: Double,
            val groundPointConfidence: Double,
            val doorEvidenceScore: Double,
            val doorProximityScoreEff: Double,
            val targetRoomContainmentRatio: Double,
            val sourceRoomContainmentRatio: Double,
            val sourceRoomOutsidePoseScore: Double,
            val poseAverageConfidence: Double,
            val poseEffectiveConfidence: Double,
            val sourceRoomStayScore: Double,
            val unifiedPoseWeight: Double,
            val poseTransitionModel: String,
            val targetTopPoseContributors: String,
            val poseTransitionScore: Double,
            val doorAssistScore: Double,
            val switchConfidenceScore: Double,
            val poseGate: Double,
            val nearGateForPose: Double,
            val insideScore: Double,
            val inwardTrendScore: Double,
            val passBySuppress: Double,
            val crossScore: Double,
            val enterPhase: Double,
            val enterProxFactor: Double,
            val enterProxFloor: Double,
            val enterCrossPart: Double,
            val enterSwitchScore: Double,
            val enterInsideScoreRef: Double,
            val enterInwardTrendRef: Double,
            val enterPassByRef: Double,
            val doorAdvanceDelta: Double,
            val doorLateralDelta: Double,
            val doorAdvanceLateralRatio: Double,
            val doorAdvanceDeltaShort: Double,
            val doorAdvanceDeltaLong: Double,
            val motionWindowUsed: String,
            val exitDpsHoldApplied: Boolean,
            val enterMotionPass: Boolean,
            val pastSignedDistance: Double,
            val currentSignedDistance: Double,
            val pastLateralDistance: Double,
            val currentLateralDistance: Double,
            val normalX: Double,
            val normalY: Double,
            val doorMidX: Double,
            val doorMidY: Double,
            val targetCentroidX: Double,
            val targetCentroidY: Double,
            val pastGroundX: Double,
            val pastGroundY: Double,
            val currentGroundX: Double,
            val currentGroundY: Double,
            val motionHistorySize: Int,
            val motionNearDoorPassed: Boolean,
            val lowConfMode: Boolean,
            val mode: String
        )

        val candidates = mutableListOf<Candidate>()
        for (door in doors) {
            val toRoomId = otherRoom(door, fromRoomId) ?: continue
            val targetRoom = roomById[toRoomId] ?: continue
            if (targetRoom.isBlindZone || targetRoom.polygon.size < 3) continue

            val dist = PresenceGeometry.distancePointToSegment(obs.landingPoint, door.a, door.b)
            val dynamicNearDist = computeDynamicNearDoorDistance(obs.personBox)
            val doorProximity = distanceScoreByDoor(dist, dynamicNearDist)
            val doorProximityScoreRaw = doorProximity.raw
            val doorProximityScore = doorProximity.score
            val isVisibleEnterByDoor = (fromRoom?.isLivingRoom == true) &&
                !targetRoom.isLivingRoom &&
                !targetRoom.isBlindZone &&
                targetRoom.polygon.size >= 3
            val doorProximityScoreEnter = if (isVisibleEnterByDoor) {
                doorProximityScore.pow(ENTER_VISIBLE_DPS_GAMMA).coerceIn(0.0, 1.0)
            } else {
                doorProximityScore
            }
            val groundPointConfidence = obs.groundConfidence.coerceIn(0.0, 1.0)
            val doorEvidenceScore = doorProximityScore * groundPointConfidence
            val targetRoomContainmentRatio = computeRoomPoseScore(obs.keypoints, targetRoom.polygon)
            val targetTopPoseContributors = buildTopPoseContributors(obs.keypoints, targetRoom.polygon)
            val sourceRoomStayScore = sourceRoomContainmentRatio
            val isVisibleSourceRoom = fromRoom != null &&
                !fromRoom.isLivingRoom &&
                !fromRoom.isBlindZone &&
                fromRoom.polygon.size >= 3
            val isVisibleExitToLiving = isVisibleSourceRoom && targetRoom.isLivingRoom
            val poseTransitionModel = if (isVisibleExitToLiving) {
                "EXIT_VISIBLE_TO_LIVING:${params.visibleExitPoseTransitionModel.name}"
            } else {
                "TARGET_MINUS_SOURCE"
            }
            val poseTransitionScore = if (isVisibleExitToLiving) {
                when (params.visibleExitPoseTransitionModel) {
                    VisibleExitPoseTransitionModel.OUTSIDE_ONLY -> sourceRoomOutsidePoseScore
                    VisibleExitPoseTransitionModel.OUTSIDE_PLUS_TARGET ->
                        (sourceRoomOutsidePoseScore + targetRoomContainmentRatio).coerceIn(0.0, 1.0)
                }
            } else {
                (targetRoomContainmentRatio - sourceRoomContainmentRatio).coerceAtLeast(0.0)
            }
            val lowConfMode = groundPointConfidence < params.enterLowConfThreshold
            val switchCandidateKey = buildSwitchEvidenceKey(toRoomId, door.doorId)
            val (doorProximityScoreEff, exitDpsHoldApplied) =
                if (params.useSwitchScoreIntegrator && isVisibleExitToLiving) {
                    computeExitDoorAssistProximityScore(
                        state = state,
                        candidateKey = switchCandidateKey,
                        rawDoorProximityScore = doorProximityScore
                    )
                } else {
                    doorProximityScore to false
                }
            val motionStats = computeDoorMotionStats(
                state = state,
                currentGroundPoint = estimatedGroundPoint,
                door = door,
                targetRoom = targetRoom,
                nearDoorPassed = doorProximityScore > 0.0,
                motionEnabled = doorProximityScoreEff > 0.0,
                useLongWindow = params.useSwitchScoreIntegrator && isVisibleExitToLiving,
                longWindowFrames = params.exitVisibleLongMotionWindowFrames
            )
            val normalTerm = (motionStats.advanceDelta / params.enterNormalAdvanceMin).coerceIn(0.0, 1.0)
            val ratioTerm = (motionStats.advanceLateralRatio / params.enterLateralRatioMin).coerceIn(0.0, 1.0)
            val doorAssistProximity = if (isVisibleEnterByDoor) {
                doorProximityScoreEnter
            } else {
                doorProximityScoreEff
            }
            val doorAssistScore = (doorAssistProximity * normalTerm * ratioTerm).coerceIn(0.0, 1.0)
            val enterInsideScoreRef =
                (dynamicNearDist * ENTER_VISIBLE_INSIDE_SCORE_NEAR_DIST_RATIO).coerceAtLeast(1e-6)
            val enterInwardTrendRef =
                (params.enterNormalAdvanceMin * ENTER_VISIBLE_INWARD_TREND_REF_MULTIPLIER)
                    .coerceAtLeast(1e-6)
            val enterPassByRef = params.enterLateralRatioMin.coerceAtLeast(1e-6)
            val insideScore = if (isVisibleEnterByDoor) {
                (motionStats.currentSignedDistance / enterInsideScoreRef).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val inwardTrendScore = if (isVisibleEnterByDoor) {
                (motionStats.advanceDelta / enterInwardTrendRef).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val passBySuppress = if (isVisibleEnterByDoor) {
                (motionStats.advanceLateralRatio / enterPassByRef).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val crossScore = (insideScore * inwardTrendScore * passBySuppress).coerceIn(0.0, 1.0)
            val enterPhase = if (isVisibleEnterByDoor) {
                min(insideScore, inwardTrendScore).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val enterProxFloor = ENTER_VISIBLE_PROX_FLOOR
            val enterProxFactor = if (isVisibleEnterByDoor) {
                (
                    (1.0 - enterPhase) * doorProximityScoreEnter +
                        enterPhase * max(doorProximityScore, enterProxFloor)
                    ).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val enterCrossPart = if (isVisibleEnterByDoor) {
                (enterProxFactor * crossScore).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val poseWeight = if (sourceRoomPolygon.size >= 3 && targetRoom.polygon.size >= 3) {
                params.unifiedPoseScoreWeight
            } else {
                params.unifiedPoseScoreWeightNoPolygon
            }
            val poseGate = if (params.useSwitchScoreIntegrator) {
                ((poseEffectiveConfidence - params.grayPoseMinConfidenceForSwitch) /
                    (1.0 - params.grayPoseMinConfidenceForSwitch))
                    .coerceIn(0.0, 1.0)
            } else {
                1.0
            }
            val nearGateForPose = if (params.useSwitchScoreIntegrator) {
                // 入户/可视子房间 -> 客厅：避免 nearGate 在门线附近抖动时把主体分压穿，导致“该出不出”。
                if (isVisibleExitToLiving) {
                    1.0
                } else {
                    val poseDpsInput = if (isVisibleEnterByDoor) {
                        doorProximityScoreEnter
                    } else {
                        doorProximityScore
                    }
                    ((poseDpsInput - params.poseNearGateDps0) /
                        (1.0 - params.poseNearGateDps0)).coerceIn(0.0, 1.0)
                }
            } else {
                1.0
            }
            val enterSwitchScore = if (isVisibleEnterByDoor && params.useSwitchScoreIntegrator) {
                val poseEvidence = (poseGate * poseTransitionScore).coerceIn(0.0, 1.0)
                (
                    ENTER_VISIBLE_CROSS_WEIGHT * enterCrossPart +
                        ENTER_VISIBLE_POSE_WEIGHT * poseEvidence
                    ).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val switchConfidenceScore = if (params.useSwitchScoreIntegrator) {
                if (isVisibleEnterByDoor) {
                    enterSwitchScore
                } else {
                    (
                        poseGate *
                            (
                                poseWeight * nearGateForPose * poseTransitionScore +
                                    (1.0 - poseWeight) * doorAssistScore
                                )
                        ).coerceIn(0.0, 1.0)
                }
            } else {
                (poseWeight * poseTransitionScore +
                    (1.0 - poseWeight) * doorAssistScore).coerceIn(0.0, 1.0)
            }

            candidates.add(
                Candidate(
                    toRoomId = toRoomId,
                    doorId = door.doorId,
                    doorDist = dist,
                    nearDist = dynamicNearDist,
                    doorProximityScoreRaw = doorProximityScoreRaw,
                    doorProximityScore = doorProximityScore,
                    doorProximityScoreEnter = doorProximityScoreEnter,
                    groundPointConfidence = groundPointConfidence,
                    doorEvidenceScore = doorEvidenceScore,
                    doorProximityScoreEff = doorProximityScoreEff,
                    targetRoomContainmentRatio = targetRoomContainmentRatio,
                    sourceRoomContainmentRatio = sourceRoomContainmentRatio,
                    sourceRoomOutsidePoseScore = sourceRoomOutsidePoseScore,
                    poseAverageConfidence = poseAverageConfidence,
                    poseEffectiveConfidence = poseEffectiveConfidence,
                    sourceRoomStayScore = sourceRoomStayScore,
                    unifiedPoseWeight = poseWeight,
                    poseTransitionModel = poseTransitionModel,
                    targetTopPoseContributors = targetTopPoseContributors,
                    poseTransitionScore = poseTransitionScore,
                    doorAssistScore = doorAssistScore,
                    switchConfidenceScore = switchConfidenceScore,
                    poseGate = poseGate,
                    nearGateForPose = nearGateForPose,
                    insideScore = insideScore,
                    inwardTrendScore = inwardTrendScore,
                    passBySuppress = passBySuppress,
                    crossScore = crossScore,
                    enterPhase = enterPhase,
                    enterProxFactor = enterProxFactor,
                    enterProxFloor = enterProxFloor,
                    enterCrossPart = enterCrossPart,
                    enterSwitchScore = enterSwitchScore,
                    enterInsideScoreRef = enterInsideScoreRef,
                    enterInwardTrendRef = enterInwardTrendRef,
                    enterPassByRef = enterPassByRef,
                    doorAdvanceDelta = motionStats.advanceDelta,
                    doorLateralDelta = motionStats.lateralDelta,
                    doorAdvanceLateralRatio = motionStats.advanceLateralRatio,
                    doorAdvanceDeltaShort = motionStats.advanceDeltaShort,
                    doorAdvanceDeltaLong = motionStats.advanceDeltaLong,
                    motionWindowUsed = motionStats.windowUsed,
                    exitDpsHoldApplied = exitDpsHoldApplied,
                    enterMotionPass = motionStats.pass,
                    pastSignedDistance = motionStats.pastSignedDistance,
                    currentSignedDistance = motionStats.currentSignedDistance,
                    pastLateralDistance = motionStats.pastLateralDistance,
                    currentLateralDistance = motionStats.currentLateralDistance,
                    normalX = motionStats.normalX,
                    normalY = motionStats.normalY,
                    doorMidX = motionStats.doorMidX,
                    doorMidY = motionStats.doorMidY,
                    targetCentroidX = motionStats.targetCentroidX,
                    targetCentroidY = motionStats.targetCentroidY,
                    pastGroundX = motionStats.pastGroundX,
                    pastGroundY = motionStats.pastGroundY,
                    currentGroundX = motionStats.currentGroundX,
                    currentGroundY = motionStats.currentGroundY,
                    motionHistorySize = motionStats.historySize,
                    motionNearDoorPassed = motionStats.nearDoorPassed,
                    lowConfMode = lowConfMode,
                    mode = "DOOR"
                )
            )
        }

        // 通用恢复候选：当当前状态与 polygon 命中明显不一致时，允许按可视区重定位（不依赖门邻接）。
        val recoveryRoomId = polygonRoomId
        if (!recoveryRoomId.isNullOrBlank() && recoveryRoomId != fromRoomId) {
            val recoveryRoom = roomById[recoveryRoomId]
            val isRecoveryVisible = recoveryRoom != null && !recoveryRoom.isBlindZone && recoveryRoom.polygon.size >= 3
            val isVisibleSubRoom = fromRoom != null &&
                !fromRoom.isLivingRoom &&
                !fromRoom.isBlindZone &&
                fromRoom.polygon.size >= 3
            val isRecoveryExitToLiving = isVisibleSubRoom && (recoveryRoom?.isLivingRoom == true)
            val skipRecoveryExitToLiving = isRecoveryExitToLiving && !params.allowVisibleExitPolygonRecovery
            if (isRecoveryVisible && !skipRecoveryExitToLiving) {
                val recoveryContainment = computeRoomPoseScore(obs.keypoints, recoveryRoom.polygon)
                val recoveryPoseTransitionScore =
                    (recoveryContainment - sourceRoomContainmentRatio).coerceAtLeast(0.0)
                val recoveryPoseWeight = if (sourceRoomPolygon.size >= 3 && recoveryRoom.polygon.size >= 3) {
                    params.unifiedPoseScoreWeight
                } else {
                    params.unifiedPoseScoreWeightNoPolygon
                }
                val recoverySwitchScore = (recoveryPoseWeight * recoveryPoseTransitionScore).coerceIn(0.0, 1.0)
                candidates.add(
                    Candidate(
                        toRoomId = recoveryRoomId,
                        doorId = "polygon_recovery",
                        doorDist = Double.NaN,
                        nearDist = 0.0,
                        doorProximityScoreRaw = 0.0,
                        doorProximityScore = 0.0,
                        doorProximityScoreEnter = 0.0,
                        groundPointConfidence = obs.groundConfidence.coerceIn(0.0, 1.0),
                        doorEvidenceScore = 0.0,
                        doorProximityScoreEff = 0.0,
                        targetRoomContainmentRatio = recoveryContainment,
                        sourceRoomContainmentRatio = sourceRoomContainmentRatio,
                        sourceRoomOutsidePoseScore = sourceRoomOutsidePoseScore,
                        poseAverageConfidence = poseAverageConfidence,
                        poseEffectiveConfidence = poseEffectiveConfidence,
                        sourceRoomStayScore = sourceRoomContainmentRatio,
                        unifiedPoseWeight = recoveryPoseWeight,
                        poseTransitionModel = "POLYGON_RECOVERY",
                        targetTopPoseContributors = "-",
                        poseTransitionScore = recoveryPoseTransitionScore,
                        doorAssistScore = 0.0,
                        switchConfidenceScore = recoverySwitchScore,
                        poseGate = 1.0,
                        nearGateForPose = 1.0,
                        insideScore = 0.0,
                        inwardTrendScore = 0.0,
                        passBySuppress = 0.0,
                        crossScore = 0.0,
                        enterPhase = 0.0,
                        enterProxFactor = 0.0,
                        enterProxFloor = ENTER_VISIBLE_PROX_FLOOR,
                        enterCrossPart = 0.0,
                        enterSwitchScore = 0.0,
                        enterInsideScoreRef = 0.0,
                        enterInwardTrendRef = 0.0,
                        enterPassByRef = 0.0,
                        doorAdvanceDelta = 0.0,
                        doorLateralDelta = 0.0,
                        doorAdvanceLateralRatio = 0.0,
                        doorAdvanceDeltaShort = 0.0,
                        doorAdvanceDeltaLong = 0.0,
                        motionWindowUsed = "FALLBACK",
                        exitDpsHoldApplied = false,
                        enterMotionPass = false,
                        pastSignedDistance = 0.0,
                        currentSignedDistance = 0.0,
                        pastLateralDistance = 0.0,
                        currentLateralDistance = 0.0,
                        normalX = 0.0,
                        normalY = 0.0,
                        doorMidX = 0.0,
                        doorMidY = 0.0,
                        targetCentroidX = 0.0,
                        targetCentroidY = 0.0,
                        pastGroundX = 0.0,
                        pastGroundY = 0.0,
                        currentGroundX = 0.0,
                        currentGroundY = 0.0,
                        motionHistorySize = state.groundPointHistory.size,
                        motionNearDoorPassed = false,
                        lowConfMode = false,
                        mode = "POLYGON_RECOVERY"
                    )
                )
            }
        }

        if (candidates.isEmpty()) {
            resetEnterCandidate(state)
            return EnterEvalResult(
                confirmed = false,
                toRoomId = null,
                doorId = null,
                debugText = "NO_VISIBLE_DOOR from=$fromRoomId"
            )
        }

        val fromRoomIsVisibleSubRoom = fromRoom != null &&
            !fromRoom.isLivingRoom &&
            !fromRoom.isBlindZone &&
            fromRoom.polygon.size >= 3
        val fromRoomIsLiving = fromRoom?.isLivingRoom == true

        val candidatePool = if (fromRoomIsVisibleSubRoom) {
            val livingCandidates = candidates.filter { candidate ->
                roomById[candidate.toRoomId]?.isLivingRoom == true
            }
            if (livingCandidates.isEmpty()) {
                resetEnterCandidate(state)
                return EnterEvalResult(
                    confirmed = false,
                    toRoomId = null,
                    doorId = null,
                    debugText = "NO_LIVING_CANDIDATE from=$fromRoomId"
                )
            }
            livingCandidates
        } else if (fromRoomIsLiving) {
            val visibleDoorCandidates = candidates.filter { candidate ->
                val room = roomById[candidate.toRoomId]
                candidate.mode == "DOOR" &&
                    room != null &&
                    !room.isLivingRoom &&
                    !room.isBlindZone &&
                    room.polygon.size >= 3
            }
            if (visibleDoorCandidates.isNotEmpty()) visibleDoorCandidates else candidates
        } else {
            candidates
        }

        val sorted = candidatePool.sortedByDescending { it.switchConfidenceScore }
        var best = sorted.first()
        val second = sorted.getOrNull(1)
        val bestScoreRaw = best.switchConfidenceScore
        val secondScoreRaw = second?.switchConfidenceScore ?: 0.0
        var scoreGap = if (second != null) {
            bestScoreRaw - secondScoreRaw
        } else {
            1.0
        }
        if (!params.useSoftDoorClearGate &&
            sorted.size >= 2 &&
            scoreGap < params.doorSeparationMargin
        ) {
            resetEnterCandidate(state)
            return EnterEvalResult(
                confirmed = false,
                toRoomId = null,
                doorId = null,
                debugText = "AMBIGUOUS_DOOR from=$fromRoomId scoreGap=${fmt(scoreGap)}"
            )
        }

        val isVisibleScoreChain = fromRoomIsVisibleSubRoom || fromRoomIsLiving
        val prevCandidateKey = if (
            state.lastScoredFromRoomId == fromRoomId &&
            !state.lastScoredToRoomId.isNullOrBlank() &&
            !state.lastScoredDoorId.isNullOrBlank()
        ) {
            "${state.lastScoredToRoomId}@${state.lastScoredDoorId}"
        } else {
            null
        }
        var prevScore = 0.0
        var bestMinusPrev = 0.0
        var stickApplied = false
        var stickReason = "NONE"
        var fallbackFrozen = false
        if (params.useSwitchScoreIntegrator &&
            params.enableVisibleCandidateStickiness &&
            isVisibleScoreChain &&
            !prevCandidateKey.isNullOrBlank()
        ) {
            val prevCandidate = sorted.firstOrNull { candidate ->
                buildSwitchEvidenceKey(candidate.toRoomId, candidate.doorId) == prevCandidateKey
            }
            if (prevCandidate != null) {
                val prevKey = buildSwitchEvidenceKey(prevCandidate.toRoomId, prevCandidate.doorId)
                val bestKey = buildSwitchEvidenceKey(best.toRoomId, best.doorId)
                prevScore = prevCandidate.switchConfidenceScore
                bestMinusPrev = best.switchConfidenceScore - prevScore
                if (bestKey != prevKey) {
                    val allZero = bestScoreRaw < params.candidateStickAllZeroEps &&
                        secondScoreRaw < params.candidateStickAllZeroEps
                    val prevEvidence = state.switchEvidenceByCandidate[prevKey] ?: 0.0
                    val freeze = prevEvidence >=
                        params.candidateStickFreezeRatio * params.switchEvidenceThreshold &&
                        bestMinusPrev < params.candidateStickSwitchMargin
                    val fallback = params.enableFallbackCandidateFreeze &&
                        best.motionWindowUsed == "FALLBACK"
                    when {
                        fallback -> {
                            best = prevCandidate
                            stickApplied = true
                            stickReason = "FALLBACK"
                            fallbackFrozen = true
                        }
                        allZero -> {
                            best = prevCandidate
                            stickApplied = true
                            stickReason = "ALL_ZERO"
                        }
                        freeze -> {
                            best = prevCandidate
                            stickApplied = true
                            stickReason = "FREEZE"
                        }
                    }
                }
            }
        }

        // clearGate 基于当前生效候选重新计算分差；粘性生效时仍保持可解释。
        scoreGap = run {
            val selectedKey = buildSwitchEvidenceKey(best.toRoomId, best.doorId)
            val alt = sorted.firstOrNull {
                buildSwitchEvidenceKey(it.toRoomId, it.doorId) != selectedKey
            }
            if (alt == null) 1.0 else (best.switchConfidenceScore - alt.switchConfidenceScore)
        }
        val selectedCandidateKey = buildSwitchEvidenceKey(best.toRoomId, best.doorId)
        state.lastScoredFromRoomId = fromRoomId
        state.lastScoredToRoomId = best.toRoomId
        state.lastScoredDoorId = best.doorId

        val clearGate = if (params.useSoftDoorClearGate) {
            val tau = params.softDoorClearTau.coerceAtLeast(1e-6)
            sigmoid((scoreGap - params.doorSeparationMargin) / tau)
        } else {
            1.0
        }
        val rawSwitchScore = (best.switchConfidenceScore * clearGate).coerceIn(0.0, 1.0)
        val lastSwitchLabel = if (!state.lastSwitchFromRoomId.isNullOrBlank() &&
            !state.lastSwitchToRoomId.isNullOrBlank()
        ) {
            "${state.lastSwitchFromRoomId}->${state.lastSwitchToRoomId}@${state.lastSwitchDoorId ?: "-"}"
        } else {
            "-"
        }
        val isReverseBounceCandidate = params.useSwitchScoreIntegrator &&
            isVisibleScoreChain &&
            !state.lastSwitchFromRoomId.isNullOrBlank() &&
            !state.lastSwitchToRoomId.isNullOrBlank() &&
            state.lastSwitchFromRoomId == best.toRoomId &&
            state.lastSwitchToRoomId == fromRoomId
        val bounceDtMs = if (obs.timestampMs >= 0L && state.lastSwitchTimestampMs >= 0L) {
            (obs.timestampMs - state.lastSwitchTimestampMs).coerceAtLeast(0L)
        } else if (state.lastSwitchFrameSeq > 0L) {
            ((frameSeq - state.lastSwitchFrameSeq).coerceAtLeast(0L) * DEFAULT_FRAME_INTERVAL_MS)
        } else {
            Long.MAX_VALUE
        }
        val bounceApplied = isReverseBounceCandidate && bounceDtMs <= REVERSE_BOUNCE_WINDOW_MS
        val bounceFactor = if (bounceApplied) {
            val ratio = (bounceDtMs.toDouble() / REVERSE_BOUNCE_WINDOW_MS.toDouble()).coerceIn(0.0, 1.0)
            (REVERSE_BOUNCE_MIN_FACTOR + (1.0 - REVERSE_BOUNCE_MIN_FACTOR) * ratio)
                .coerceIn(REVERSE_BOUNCE_MIN_FACTOR, 1.0)
        } else {
            1.0
        }
        val switchScore = (rawSwitchScore * bounceFactor).coerceIn(0.0, 1.0)
        val evidenceThreshold = if (params.useSwitchScoreIntegrator) {
            params.switchEvidenceThreshold
        } else {
            params.enterThreshold
        }
        val evidenceScore = if (params.useSwitchScoreIntegrator) {
            accumulateSwitchEvidence(state, selectedCandidateKey, switchScore)
        } else {
            switchScore
        }
        val stickyDebugSuffix =
            "candidate=$selectedCandidateKey " +
                "prevCandidate=${prevCandidateKey ?: "-"} " +
                "bestScore=${fmt(bestScoreRaw)} " +
                "secondScore=${fmt(secondScoreRaw)} " +
                "prevScore=${fmt(prevScore)} " +
                "bestMinusPrev=${fmt(bestMinusPrev)} " +
                "stickApplied=$stickApplied " +
                "stickReason=$stickReason " +
                "fallbackFrozen=$fallbackFrozen " +
                "bounceApplied=$bounceApplied " +
                "bounceDtMs=${if (bounceDtMs == Long.MAX_VALUE) "-1" else bounceDtMs} " +
                "bounceFactor=${fmt(bounceFactor)} " +
                "lastSwitch=$lastSwitchLabel " +
                "ssBeforeAfter=${fmt(rawSwitchScore)}->${fmt(switchScore)} " +
                "nearDist=${fmt(best.nearDist)} " +
                "dpsRaw=${fmt(best.doorProximityScoreRaw)} " +
                "dpsFinal=${fmt(best.doorProximityScore)} " +
                "dpsEnter=${fmt(best.doorProximityScoreEnter)} " +
                "poseEffectiveConfidence=${fmt(best.poseEffectiveConfidence)} " +
                "insideScore=${fmt(best.insideScore)} " +
                "inwardTrendScore=${fmt(best.inwardTrendScore)} " +
                "passBySuppress=${fmt(best.passBySuppress)} " +
                "crossScore=${fmt(best.crossScore)} " +
                "enterPhase=${fmt(best.enterPhase)} " +
                "enterProxFactor=${fmt(best.enterProxFactor)} " +
                "enterProxFloor=${fmt(best.enterProxFloor)} " +
                "enterCrossPart=${fmt(best.enterCrossPart)} " +
                "enterSwitchScore=${fmt(best.enterSwitchScore)} " +
                "enterInsideScoreRef=${fmt(best.enterInsideScoreRef)} " +
                "enterInwardTrendRef=${fmt(best.enterInwardTrendRef)} " +
                "enterPassByRef=${fmt(best.enterPassByRef)} " +
                "enterDpsGamma=${fmt(ENTER_VISIBLE_DPS_GAMMA)} " +
                "dpsTailRatio=${fmt(params.doorProximitySoftTailRatio)}"

        val toRoom = roomById[best.toRoomId]
        val isExitToLiving = (fromRoom?.isLivingRoom == false) && (toRoom?.isLivingRoom == true)
        val displayType = when {
            fromRoom?.isLivingRoom == true && toRoom?.isLivingRoom == false ->
                PresenceSwitchDisplayType.ENTER_SUB_ROOM
            fromRoom?.isLivingRoom == false && toRoom?.isLivingRoom == true ->
                PresenceSwitchDisplayType.EXIT_SUB_ROOM
            else -> PresenceSwitchDisplayType.UNKNOWN
        }
        val useVisibleExitOutsideRule = fromRoomIsVisibleSubRoom && isExitToLiving
        val useVisibleEnterMotionRule = fromRoomIsLiving &&
            toRoom != null &&
            !toRoom.isLivingRoom &&
            !toRoom.isBlindZone &&
            toRoom.polygon.size >= 3 &&
            best.mode == "DOOR"
        val enterConfirmFramesRequired = if (params.useSwitchScoreIntegrator && useVisibleEnterMotionRule) {
            ENTER_VISIBLE_CONFIRM_FRAMES
        } else {
            params.enterConfirmFrames
        }
        val decisionRuleLabel = when {
            useVisibleExitOutsideRule -> "VISIBLE_OUTSIDE_POSE"
            useVisibleEnterMotionRule -> "VISIBLE_DOOR_MOTION"
            else -> "LEGACY"
        }
        val enterVisibleNearDoorLimit =
            (computeDynamicNearDoorDistance(obs.personBox) * params.enterVisibleNearDoorMultiplier)
                .coerceAtLeast(params.enterVisibleNearDoorMin)
        val exitVisibleNearDoorLimit =
            (computeDynamicNearDoorDistance(obs.personBox) * params.exitVisibleNearDoorMultiplier)
                .coerceAtLeast(params.exitVisibleNearDoorMin)
        val needEnterNearDoor = useVisibleEnterMotionRule && params.enterVisibleRequireNearDoor
        val needExitNearDoor = useVisibleExitOutsideRule && params.exitVisibleRequireNearDoor
        val enterVisibleNearDoorRawPass = !needEnterNearDoor ||
            (best.doorDist.isFinite() && best.doorDist <= enterVisibleNearDoorLimit) ||
            best.motionNearDoorPassed
        val enterNearDoorLatchFrames = params.enterVisibleNearDoorLatchFrames.coerceAtLeast(0)
        val enterVisibleNearDoorLatchPass =
            needEnterNearDoor &&
                enterNearDoorLatchFrames > 0 &&
                !enterVisibleNearDoorRawPass &&
                state.enterCandidateRoomId == best.toRoomId &&
                state.enterCandidateDoorId == best.doorId &&
                state.enterCandidateFrames >= enterNearDoorLatchFrames
        val enterVisibleNearDoorPass = !needEnterNearDoor ||
            enterVisibleNearDoorRawPass ||
            enterVisibleNearDoorLatchPass
        val exitVisibleNearDoorPass = !needExitNearDoor ||
            (best.doorDist.isFinite() && best.doorDist <= exitVisibleNearDoorLimit) ||
            best.motionNearDoorPassed
        val enterVisibleContainmentPass = !useVisibleEnterMotionRule ||
            !params.enterVisibleRequireContainment ||
            (best.targetRoomContainmentRatio >= params.enterVisibleTargetContainmentMin &&
                best.sourceRoomContainmentRatio <= params.enterVisibleSourceContainmentMax)
        val enterVisibleDoorEvidencePass = !useVisibleEnterMotionRule ||
            !params.enterVisibleRequireDoorEvidence ||
            best.enterMotionPass ||
            best.doorAssistScore >= params.enterVisibleDoorAssistMin
        val passed = if (params.useSwitchScoreIntegrator) {
            evidenceScore >= evidenceThreshold
        } else if (best.mode == "POLYGON_RECOVERY" && !useVisibleExitOutsideRule) {
            // 统一标准：恢复候选也只看综合分；法向辅助无法计算时仅用主体分。
            best.switchConfidenceScore >= max(params.enterThreshold, params.recoveryTargetContainmentMin)
        } else if (best.mode == "DOOR") {
            // 统一标准：主体分 + 法向辅助分；法向仅作辅助，不再作为硬门槛。
            best.poseAverageConfidence >= params.exitPoseMinConfidence &&
                enterVisibleNearDoorPass &&
                exitVisibleNearDoorPass &&
                enterVisibleContainmentPass &&
                enterVisibleDoorEvidencePass &&
                best.switchConfidenceScore >= params.enterThreshold
        } else {
            best.switchConfidenceScore >= params.enterThreshold
        }

        if (!passed) {
            resetEnterCandidate(state)
            return EnterEvalResult(
                confirmed = false,
                toRoomId = best.toRoomId,
                doorId = best.doorId,
                displayScore = switchScore,
                displayType = displayType,
                displayFromRoomId = fromRoomId,
                displayToRoomId = best.toRoomId,
                candidateKey = selectedCandidateKey,
                evidenceScore = evidenceScore,
                switchScore = switchScore,
                debugText = "SCORE_REJECT from=$fromRoomId to=${best.toRoomId} door=${best.doorId} " +
                    "doorDist=${fmt(best.doorDist)} doorProximityScore=${fmt(best.doorProximityScore)} " +
                    "doorProximityScoreEff=${fmt(best.doorProximityScoreEff)} " +
                    "groundPointConfidence=${fmt(best.groundPointConfidence)} " +
                    "doorEvidenceScore=${fmt(best.doorEvidenceScore)} " +
                    "targetRoomContainmentRatio=${fmt(best.targetRoomContainmentRatio)} " +
                    "sourceRoomContainmentRatio=${fmt(best.sourceRoomContainmentRatio)} " +
                    "sourceRoomOutsidePoseScore=${fmt(best.sourceRoomOutsidePoseScore)} " +
                    "exitOutsidePoseScoreThreshold=${fmt(params.exitOutsidePoseScoreThreshold)} " +
                    "poseAverageConfidence=${fmt(best.poseAverageConfidence)} " +
                    "exitPoseMinConfidence=${fmt(params.exitPoseMinConfidence)} " +
                    "sourceRoomStayScore=${fmt(best.sourceRoomStayScore)} " +
                    "unifiedPoseWeight=${fmt(best.unifiedPoseWeight)} " +
                    "poseTransitionModel=${best.poseTransitionModel} " +
                    "targetTopPoseContributors=${best.targetTopPoseContributors} " +
                    "poseTransitionScore=${fmt(best.poseTransitionScore)} " +
                    "doorAssistScore=${fmt(best.doorAssistScore)} " +
                    "switchConfidenceScore=${fmt(best.switchConfidenceScore)} " +
                    "switchScore=${fmt(switchScore)} " +
                    "evidenceScore=${fmt(evidenceScore)} " +
                    "evidenceThreshold=${fmt(evidenceThreshold)} " +
                    "poseGate=${fmt(best.poseGate)} " +
                    "clearGate=${fmt(clearGate)} " +
                    "nearGateForPose=${fmt(best.nearGateForPose)} " +
                    "doorScoreGap=${fmt(scoreGap)} " +
                    "switchThreshold=${fmt(params.enterThreshold)} " +
                    "exitSourceRoomScoreThreshold=${fmt(params.exitSourceRoomScoreThreshold)} " +
                    "dynamicNearDist=${fmt(computeDynamicNearDoorDistance(obs.personBox))} " +
                    "doorAdvanceDelta=${fmt(best.doorAdvanceDelta)} " +
                    "doorLateralDelta=${fmt(best.doorLateralDelta)} " +
                    "doorAdvanceLateralRatio=${fmt(best.doorAdvanceLateralRatio)} " +
                    "doorAdvanceDeltaShort=${fmt(best.doorAdvanceDeltaShort)} " +
                    "doorAdvanceDeltaLong=${fmt(best.doorAdvanceDeltaLong)} " +
                    "motionWindowUsed=${best.motionWindowUsed} " +
                    "exitDpsHoldApplied=${best.exitDpsHoldApplied} " +
                    "enterNormalAdvanceMin=${fmt(params.enterNormalAdvanceMin)} " +
                    "enterLateralRatioMin=${fmt(params.enterLateralRatioMin)} " +
                    "enterMotionWindowFrames=${params.enterMotionWindowFrames} " +
                    "enterMotionPass=${best.enterMotionPass} " +
                    "enterVisibleRequireNearDoor=${params.enterVisibleRequireNearDoor} " +
                    "enterVisibleNearDoorPass=$enterVisibleNearDoorPass " +
                    "enterVisibleNearDoorRawPass=$enterVisibleNearDoorRawPass " +
                    "enterVisibleNearDoorLatchPass=$enterVisibleNearDoorLatchPass " +
                    "enterVisibleNearDoorLimit=${fmt(enterVisibleNearDoorLimit)} " +
                    "exitVisibleRequireNearDoor=${params.exitVisibleRequireNearDoor} " +
                    "exitVisibleNearDoorPass=$exitVisibleNearDoorPass " +
                    "exitVisibleNearDoorLimit=${fmt(exitVisibleNearDoorLimit)} " +
                    "visibleExitPoseTransitionModel=${params.visibleExitPoseTransitionModel.name} " +
                    "allowVisibleExitPolygonRecovery=${params.allowVisibleExitPolygonRecovery} " +
                    "enterVisibleRequireContainment=${params.enterVisibleRequireContainment} " +
                    "enterVisibleContainmentPass=$enterVisibleContainmentPass " +
                    "enterVisibleTargetContainmentMin=${fmt(params.enterVisibleTargetContainmentMin)} " +
                    "enterVisibleSourceContainmentMax=${fmt(params.enterVisibleSourceContainmentMax)} " +
                    "enterVisibleRequireDoorEvidence=${params.enterVisibleRequireDoorEvidence} " +
                    "enterVisibleDoorEvidencePass=$enterVisibleDoorEvidencePass " +
                    "enterVisibleDoorAssistMin=${fmt(params.enterVisibleDoorAssistMin)} " +
                    "pastSignedDistance=${fmt(best.pastSignedDistance)} " +
                    "currentSignedDistance=${fmt(best.currentSignedDistance)} " +
                    "pastLateralDistance=${fmt(best.pastLateralDistance)} " +
                    "currentLateralDistance=${fmt(best.currentLateralDistance)} " +
                    "doorNormalX=${fmt(best.normalX)} " +
                    "doorNormalY=${fmt(best.normalY)} " +
                    "doorMidX=${fmt(best.doorMidX)} " +
                    "doorMidY=${fmt(best.doorMidY)} " +
                    "targetCentroidX=${fmt(best.targetCentroidX)} " +
                    "targetCentroidY=${fmt(best.targetCentroidY)} " +
                    "pastGroundX=${fmt(best.pastGroundX)} " +
                    "pastGroundY=${fmt(best.pastGroundY)} " +
                    "currentGroundX=${fmt(best.currentGroundX)} " +
                    "currentGroundY=${fmt(best.currentGroundY)} " +
                    "motionHistorySize=${best.motionHistorySize} " +
                    "motionNearDoorPassed=${best.motionNearDoorPassed} " +
                    "recoveryTargetContainmentMin=${fmt(params.recoveryTargetContainmentMin)} " +
                    "mode=${best.mode}:${if (isExitToLiving) "EXIT_TO_LIVING" else "ENTER_VISIBLE"} " +
                    "exitRule=$decisionRuleLabel " +
                    "lowConf=${best.lowConfMode} " +
                    stickyDebugSuffix
            )
        }

        if (state.enterCandidateRoomId == best.toRoomId && state.enterCandidateDoorId == best.doorId) {
            state.enterCandidateFrames += 1
        } else {
            state.enterCandidateRoomId = best.toRoomId
            state.enterCandidateDoorId = best.doorId
            state.enterCandidateFrames = 1
        }

        val confirmed = state.enterCandidateFrames >= enterConfirmFramesRequired
        return EnterEvalResult(
            confirmed = confirmed,
            toRoomId = best.toRoomId,
            doorId = best.doorId,
            displayScore = switchScore,
            displayType = displayType,
            displayFromRoomId = fromRoomId,
            displayToRoomId = best.toRoomId,
            candidateKey = selectedCandidateKey,
            evidenceScore = evidenceScore,
            switchScore = switchScore,
            debugText = if (confirmed) {
                "ENTER_OK from=$fromRoomId to=${best.toRoomId} door=${best.doorId} " +
                "frames=${state.enterCandidateFrames}/${enterConfirmFramesRequired} " +
                    "doorDist=${fmt(best.doorDist)} doorProximityScore=${fmt(best.doorProximityScore)} " +
                    "doorProximityScoreEff=${fmt(best.doorProximityScoreEff)} " +
                    "groundPointConfidence=${fmt(best.groundPointConfidence)} " +
                    "doorEvidenceScore=${fmt(best.doorEvidenceScore)} " +
                    "targetRoomContainmentRatio=${fmt(best.targetRoomContainmentRatio)} " +
                    "sourceRoomContainmentRatio=${fmt(best.sourceRoomContainmentRatio)} " +
                    "sourceRoomOutsidePoseScore=${fmt(best.sourceRoomOutsidePoseScore)} " +
                    "exitOutsidePoseScoreThreshold=${fmt(params.exitOutsidePoseScoreThreshold)} " +
                    "poseAverageConfidence=${fmt(best.poseAverageConfidence)} " +
                    "exitPoseMinConfidence=${fmt(params.exitPoseMinConfidence)} " +
                    "sourceRoomStayScore=${fmt(best.sourceRoomStayScore)} " +
                    "unifiedPoseWeight=${fmt(best.unifiedPoseWeight)} " +
                    "poseTransitionModel=${best.poseTransitionModel} " +
                    "targetTopPoseContributors=${best.targetTopPoseContributors} " +
                    "poseTransitionScore=${fmt(best.poseTransitionScore)} " +
                    "doorAssistScore=${fmt(best.doorAssistScore)} " +
                    "switchConfidenceScore=${fmt(best.switchConfidenceScore)} " +
                    "switchScore=${fmt(switchScore)} " +
                    "evidenceScore=${fmt(evidenceScore)} " +
                    "evidenceThreshold=${fmt(evidenceThreshold)} " +
                    "poseGate=${fmt(best.poseGate)} " +
                    "clearGate=${fmt(clearGate)} " +
                    "nearGateForPose=${fmt(best.nearGateForPose)} " +
                    "doorScoreGap=${fmt(scoreGap)} " +
                    "switchThreshold=${fmt(params.enterThreshold)} " +
                    "exitSourceRoomScoreThreshold=${fmt(params.exitSourceRoomScoreThreshold)} " +
                    "dynamicNearDist=${fmt(computeDynamicNearDoorDistance(obs.personBox))} " +
                    "doorAdvanceDelta=${fmt(best.doorAdvanceDelta)} " +
                    "doorLateralDelta=${fmt(best.doorLateralDelta)} " +
                    "doorAdvanceLateralRatio=${fmt(best.doorAdvanceLateralRatio)} " +
                    "doorAdvanceDeltaShort=${fmt(best.doorAdvanceDeltaShort)} " +
                    "doorAdvanceDeltaLong=${fmt(best.doorAdvanceDeltaLong)} " +
                    "motionWindowUsed=${best.motionWindowUsed} " +
                    "exitDpsHoldApplied=${best.exitDpsHoldApplied} " +
                    "enterNormalAdvanceMin=${fmt(params.enterNormalAdvanceMin)} " +
                    "enterLateralRatioMin=${fmt(params.enterLateralRatioMin)} " +
                    "enterMotionWindowFrames=${params.enterMotionWindowFrames} " +
                    "enterMotionPass=${best.enterMotionPass} " +
                    "enterVisibleRequireNearDoor=${params.enterVisibleRequireNearDoor} " +
                    "enterVisibleNearDoorPass=$enterVisibleNearDoorPass " +
                    "enterVisibleNearDoorRawPass=$enterVisibleNearDoorRawPass " +
                    "enterVisibleNearDoorLatchPass=$enterVisibleNearDoorLatchPass " +
                    "enterVisibleNearDoorLimit=${fmt(enterVisibleNearDoorLimit)} " +
                    "exitVisibleRequireNearDoor=${params.exitVisibleRequireNearDoor} " +
                    "exitVisibleNearDoorPass=$exitVisibleNearDoorPass " +
                    "exitVisibleNearDoorLimit=${fmt(exitVisibleNearDoorLimit)} " +
                    "visibleExitPoseTransitionModel=${params.visibleExitPoseTransitionModel.name} " +
                    "allowVisibleExitPolygonRecovery=${params.allowVisibleExitPolygonRecovery} " +
                    "enterVisibleRequireContainment=${params.enterVisibleRequireContainment} " +
                    "enterVisibleContainmentPass=$enterVisibleContainmentPass " +
                    "enterVisibleTargetContainmentMin=${fmt(params.enterVisibleTargetContainmentMin)} " +
                    "enterVisibleSourceContainmentMax=${fmt(params.enterVisibleSourceContainmentMax)} " +
                    "enterVisibleRequireDoorEvidence=${params.enterVisibleRequireDoorEvidence} " +
                    "enterVisibleDoorEvidencePass=$enterVisibleDoorEvidencePass " +
                    "enterVisibleDoorAssistMin=${fmt(params.enterVisibleDoorAssistMin)} " +
                    "pastSignedDistance=${fmt(best.pastSignedDistance)} " +
                    "currentSignedDistance=${fmt(best.currentSignedDistance)} " +
                    "pastLateralDistance=${fmt(best.pastLateralDistance)} " +
                    "currentLateralDistance=${fmt(best.currentLateralDistance)} " +
                    "doorNormalX=${fmt(best.normalX)} " +
                    "doorNormalY=${fmt(best.normalY)} " +
                    "doorMidX=${fmt(best.doorMidX)} " +
                    "doorMidY=${fmt(best.doorMidY)} " +
                    "targetCentroidX=${fmt(best.targetCentroidX)} " +
                    "targetCentroidY=${fmt(best.targetCentroidY)} " +
                    "pastGroundX=${fmt(best.pastGroundX)} " +
                    "pastGroundY=${fmt(best.pastGroundY)} " +
                    "currentGroundX=${fmt(best.currentGroundX)} " +
                    "currentGroundY=${fmt(best.currentGroundY)} " +
                    "motionHistorySize=${best.motionHistorySize} " +
                    "motionNearDoorPassed=${best.motionNearDoorPassed} " +
                    "recoveryTargetContainmentMin=${fmt(params.recoveryTargetContainmentMin)} " +
                    "mode=${best.mode}:${if (isExitToLiving) "EXIT_TO_LIVING" else "ENTER_VISIBLE"} " +
                    "exitRule=$decisionRuleLabel " +
                    stickyDebugSuffix
            } else {
                "ENTER_WAIT from=$fromRoomId to=${best.toRoomId} door=${best.doorId} " +
                "frames=${state.enterCandidateFrames}/${enterConfirmFramesRequired} " +
                    "doorDist=${fmt(best.doorDist)} doorProximityScore=${fmt(best.doorProximityScore)} " +
                    "doorProximityScoreEff=${fmt(best.doorProximityScoreEff)} " +
                    "groundPointConfidence=${fmt(best.groundPointConfidence)} " +
                    "doorEvidenceScore=${fmt(best.doorEvidenceScore)} " +
                    "targetRoomContainmentRatio=${fmt(best.targetRoomContainmentRatio)} " +
                    "sourceRoomContainmentRatio=${fmt(best.sourceRoomContainmentRatio)} " +
                    "sourceRoomOutsidePoseScore=${fmt(best.sourceRoomOutsidePoseScore)} " +
                    "exitOutsidePoseScoreThreshold=${fmt(params.exitOutsidePoseScoreThreshold)} " +
                    "poseAverageConfidence=${fmt(best.poseAverageConfidence)} " +
                    "exitPoseMinConfidence=${fmt(params.exitPoseMinConfidence)} " +
                    "sourceRoomStayScore=${fmt(best.sourceRoomStayScore)} " +
                    "unifiedPoseWeight=${fmt(best.unifiedPoseWeight)} " +
                    "poseTransitionModel=${best.poseTransitionModel} " +
                    "targetTopPoseContributors=${best.targetTopPoseContributors} " +
                    "poseTransitionScore=${fmt(best.poseTransitionScore)} " +
                    "doorAssistScore=${fmt(best.doorAssistScore)} " +
                    "switchConfidenceScore=${fmt(best.switchConfidenceScore)} " +
                    "switchScore=${fmt(switchScore)} " +
                    "evidenceScore=${fmt(evidenceScore)} " +
                    "evidenceThreshold=${fmt(evidenceThreshold)} " +
                    "poseGate=${fmt(best.poseGate)} " +
                    "clearGate=${fmt(clearGate)} " +
                    "nearGateForPose=${fmt(best.nearGateForPose)} " +
                    "doorScoreGap=${fmt(scoreGap)} " +
                    "switchThreshold=${fmt(params.enterThreshold)} " +
                    "exitSourceRoomScoreThreshold=${fmt(params.exitSourceRoomScoreThreshold)} " +
                    "dynamicNearDist=${fmt(computeDynamicNearDoorDistance(obs.personBox))} " +
                    "doorAdvanceDelta=${fmt(best.doorAdvanceDelta)} " +
                    "doorLateralDelta=${fmt(best.doorLateralDelta)} " +
                    "doorAdvanceLateralRatio=${fmt(best.doorAdvanceLateralRatio)} " +
                    "doorAdvanceDeltaShort=${fmt(best.doorAdvanceDeltaShort)} " +
                    "doorAdvanceDeltaLong=${fmt(best.doorAdvanceDeltaLong)} " +
                    "motionWindowUsed=${best.motionWindowUsed} " +
                    "exitDpsHoldApplied=${best.exitDpsHoldApplied} " +
                    "enterNormalAdvanceMin=${fmt(params.enterNormalAdvanceMin)} " +
                    "enterLateralRatioMin=${fmt(params.enterLateralRatioMin)} " +
                    "enterMotionWindowFrames=${params.enterMotionWindowFrames} " +
                    "enterMotionPass=${best.enterMotionPass} " +
                    "enterVisibleRequireNearDoor=${params.enterVisibleRequireNearDoor} " +
                    "enterVisibleNearDoorPass=$enterVisibleNearDoorPass " +
                    "enterVisibleNearDoorRawPass=$enterVisibleNearDoorRawPass " +
                    "enterVisibleNearDoorLatchPass=$enterVisibleNearDoorLatchPass " +
                    "enterVisibleNearDoorLimit=${fmt(enterVisibleNearDoorLimit)} " +
                    "exitVisibleRequireNearDoor=${params.exitVisibleRequireNearDoor} " +
                    "exitVisibleNearDoorPass=$exitVisibleNearDoorPass " +
                    "exitVisibleNearDoorLimit=${fmt(exitVisibleNearDoorLimit)} " +
                    "visibleExitPoseTransitionModel=${params.visibleExitPoseTransitionModel.name} " +
                    "allowVisibleExitPolygonRecovery=${params.allowVisibleExitPolygonRecovery} " +
                    "enterVisibleRequireContainment=${params.enterVisibleRequireContainment} " +
                    "enterVisibleContainmentPass=$enterVisibleContainmentPass " +
                    "enterVisibleTargetContainmentMin=${fmt(params.enterVisibleTargetContainmentMin)} " +
                    "enterVisibleSourceContainmentMax=${fmt(params.enterVisibleSourceContainmentMax)} " +
                    "enterVisibleRequireDoorEvidence=${params.enterVisibleRequireDoorEvidence} " +
                    "enterVisibleDoorEvidencePass=$enterVisibleDoorEvidencePass " +
                    "enterVisibleDoorAssistMin=${fmt(params.enterVisibleDoorAssistMin)} " +
                    "pastSignedDistance=${fmt(best.pastSignedDistance)} " +
                    "currentSignedDistance=${fmt(best.currentSignedDistance)} " +
                    "pastLateralDistance=${fmt(best.pastLateralDistance)} " +
                    "currentLateralDistance=${fmt(best.currentLateralDistance)} " +
                    "doorNormalX=${fmt(best.normalX)} " +
                    "doorNormalY=${fmt(best.normalY)} " +
                    "doorMidX=${fmt(best.doorMidX)} " +
                    "doorMidY=${fmt(best.doorMidY)} " +
                    "targetCentroidX=${fmt(best.targetCentroidX)} " +
                    "targetCentroidY=${fmt(best.targetCentroidY)} " +
                    "pastGroundX=${fmt(best.pastGroundX)} " +
                    "pastGroundY=${fmt(best.pastGroundY)} " +
                    "currentGroundX=${fmt(best.currentGroundX)} " +
                    "currentGroundY=${fmt(best.currentGroundY)} " +
                    "motionHistorySize=${best.motionHistorySize} " +
                    "motionNearDoorPassed=${best.motionNearDoorPassed} " +
                    "recoveryTargetContainmentMin=${fmt(params.recoveryTargetContainmentMin)} " +
                "mode=${best.mode}:${if (isExitToLiving) "EXIT_TO_LIVING" else "ENTER_VISIBLE"} " +
                    "exitRule=$decisionRuleLabel " +
                    stickyDebugSuffix
            }
        )
    }

    private fun resetEnterCandidate(state: TrackRuntimeState) {
        state.enterCandidateRoomId = null
        state.enterCandidateDoorId = null
        state.enterCandidateFrames = 0
    }

    private fun resetStateAfterCommittedEvent(state: TrackRuntimeState) {
        // 事件级重置：保留 currentPresenceRoomId / lastSwitch，清理证据与跨帧缓存，避免立刻反向触发。
        resetEnterCandidate(state)
        state.visibleSyncRoomId = null
        state.visibleSyncFrames = 0
        state.doorOriginInitWaitFrames = 0
        state.stableDoorId = null
        state.stableDoorFrames = 0
        state.lastScoredFromRoomId = null
        state.lastScoredToRoomId = null
        state.lastScoredDoorId = null
        state.groundPointHistory.clear()
        state.doorOriginSamples.clear()
        clearSwitchEvidence(state)
    }

    private fun resetTrackIdentityState(state: TrackRuntimeState) {
        // 身份断裂重置：彻底清空 track 关联状态，避免 trackId 复用串人。
        state.currentPresenceRoomId = null
        state.lastSeenFrame = 0L
        state.lastConfirmedFrame = 0L
        state.stableDoorId = null
        state.stableDoorFrames = 0
        state.enterCandidateRoomId = null
        state.enterCandidateDoorId = null
        state.enterCandidateFrames = 0
        state.visibleSyncRoomId = null
        state.visibleSyncFrames = 0
        state.doorOriginInitWaitFrames = 0
        state.lastEstimatedGroundPoint = null
        state.lastScoredFromRoomId = null
        state.lastScoredToRoomId = null
        state.lastScoredDoorId = null
        state.lastSwitchFromRoomId = null
        state.lastSwitchToRoomId = null
        state.lastSwitchDoorId = null
        state.lastSwitchTimestampMs = -1L
        state.lastSwitchFrameSeq = -1L
        state.groundPointHistory.clear()
        state.doorOriginSamples.clear()
        state.switchEvidenceByCandidate.clear()
        state.exitDoorAssistHoldByCandidate.clear()
    }

    private fun buildIdentityResetReason(gapFrames: Int, jumpDist: Double): String {
        val gapTriggered = gapFrames >= IDENTITY_RESET_GAP_FRAMES
        val jumpTriggered = jumpDist >= IDENTITY_RESET_JUMP_DIST
        return when {
            gapTriggered && jumpTriggered -> "GAP+JUMP"
            gapTriggered -> "GAP"
            jumpTriggered -> "JUMP"
            else -> "NONE"
        }
    }

    private fun recordLastSwitch(
        state: TrackRuntimeState,
        fromRoomId: String,
        toRoomId: String,
        doorId: String,
        timestampMs: Long
    ) {
        state.lastSwitchFromRoomId = fromRoomId
        state.lastSwitchToRoomId = toRoomId
        state.lastSwitchDoorId = doorId
        state.lastSwitchTimestampMs = timestampMs
        state.lastSwitchFrameSeq = frameSeq
    }

    private fun clearSwitchEvidence(state: TrackRuntimeState) {
        state.switchEvidenceByCandidate.clear()
        state.exitDoorAssistHoldByCandidate.clear()
    }

    private fun decaySwitchEvidence(state: TrackRuntimeState) {
        val beta = params.switchEvidenceBeta.coerceIn(0.0, 1.0)
        if (state.switchEvidenceByCandidate.isEmpty()) return
        val keys = state.switchEvidenceByCandidate.keys.toList()
        for (key in keys) {
            val old = state.switchEvidenceByCandidate[key] ?: continue
            val decayed = (old * beta).coerceIn(0.0, params.switchEvidenceMax)
            if (decayed <= 1e-6) {
                state.switchEvidenceByCandidate.remove(key)
            } else {
                state.switchEvidenceByCandidate[key] = decayed
            }
        }
    }

    private fun buildSwitchEvidenceKey(toRoomId: String, doorId: String): String {
        return "$toRoomId@$doorId"
    }

    private fun accumulateSwitchEvidence(
        state: TrackRuntimeState,
        candidateKey: String,
        switchScore: Double
    ): Double {
        val previous = state.switchEvidenceByCandidate[candidateKey] ?: 0.0
        val updated = (previous + switchScore).coerceIn(0.0, params.switchEvidenceMax)
        state.switchEvidenceByCandidate[candidateKey] = updated
        return updated
    }

    /**
     * EXIT_TO_LIVING 专用：对 doorAssist 的 dps 输入做极短保持。
     * 注意：只影响 doorAssist，不影响 near 判定、候选排序、门歧义 clearGate。
     */
    private fun computeExitDoorAssistProximityScore(
        state: TrackRuntimeState,
        candidateKey: String,
        rawDoorProximityScore: Double
    ): Pair<Double, Boolean> {
        val holdFrames = params.exitVisibleDpsHoldFrames.coerceAtLeast(0)
        if (holdFrames <= 0) return rawDoorProximityScore to false
        val raw = rawDoorProximityScore.coerceIn(0.0, 1.0)
        if (raw > 0.0) {
            state.exitDoorAssistHoldByCandidate[candidateKey] =
                ExitDoorAssistHoldState(value = raw, remainingFrames = holdFrames)
            return raw to false
        }
        val holdState = state.exitDoorAssistHoldByCandidate[candidateKey] ?: return 0.0 to false
        if (holdState.remainingFrames <= 0 || holdState.value <= 1e-6) {
            state.exitDoorAssistHoldByCandidate.remove(candidateKey)
            return 0.0 to false
        }
        val decayed = (holdState.value * params.exitVisibleDpsHoldDecay).coerceIn(0.0, 1.0)
        holdState.value = decayed
        holdState.remainingFrames -= 1
        if (holdState.remainingFrames <= 0 || decayed < params.exitVisibleDpsHoldMin) {
            state.exitDoorAssistHoldByCandidate.remove(candidateKey)
        }
        return decayed to true
    }

    private fun appendGroundPointHistory(state: TrackRuntimeState, groundPoint: PresencePoint) {
        state.groundPointHistory.addLast(groundPoint)
        while (state.groundPointHistory.size > 16) {
            state.groundPointHistory.removeFirst()
        }
    }

    /**
     * 估计地面接触点 G(t)。
     * - 先用“肩中点 + 框底中心”得到稳态地面估计 G0
     * - 脚踝高置信时与脚点融合；低置信时自动回退到 G0
     */
    private fun estimateGroundPoint(obs: PresenceTrackObservation): PresencePoint {
        val box = obs.personBox ?: PresenceRect(
            left = obs.landingPoint.x,
            top = obs.landingPoint.y,
            right = obs.landingPoint.x,
            bottom = obs.landingPoint.y
        )
        val shoulder = estimateShoulderMid(obs.keypoints, box)
        val boxBottomCenter = PresencePoint(
            x = ((box.left + box.right) * 0.5).coerceIn(0.0, 1.0),
            y = box.bottom.coerceIn(0.0, 1.0)
        )
        val g0 = projectGroundByShoulderAndBoxBottom(shoulder, boxBottomCenter)

        val (footPoint, footConfidence) = estimateFootPoint(obs.keypoints)
        if (footPoint == null) return g0

        val c0 = params.enterGroundFootBlendStartConfidence.coerceIn(0.0, 0.99)
        val blend = ((footConfidence - c0) / (1.0 - c0)).coerceIn(0.0, 1.0)
        return PresencePoint(
            x = ((1.0 - blend) * g0.x + blend * footPoint.x).coerceIn(0.0, 1.0),
            y = ((1.0 - blend) * g0.y + blend * footPoint.y).coerceIn(0.0, 1.0)
        )
    }

    private fun estimateShoulderMid(keypoints: List<PresenceKeypoint>, box: PresenceRect): PresencePoint {
        val left = keypoints.getOrNull(5)
        val right = keypoints.getOrNull(6)
        return when {
            left != null && right != null -> PresencePoint(
                x = ((left.x + right.x) * 0.5).coerceIn(0.0, 1.0),
                y = ((left.y + right.y) * 0.5).coerceIn(0.0, 1.0)
            )
            left != null -> PresencePoint(left.x.coerceIn(0.0, 1.0), left.y.coerceIn(0.0, 1.0))
            right != null -> PresencePoint(right.x.coerceIn(0.0, 1.0), right.y.coerceIn(0.0, 1.0))
            else -> PresencePoint(
                x = ((box.left + box.right) * 0.5).coerceIn(0.0, 1.0),
                y = box.top.coerceIn(0.0, 1.0)
            )
        }
    }

    private fun projectGroundByShoulderAndBoxBottom(
        shoulder: PresencePoint,
        boxBottomCenter: PresencePoint
    ): PresencePoint {
        val dx = boxBottomCenter.x - shoulder.x
        val dy = boxBottomCenter.y - shoulder.y
        if (abs(dy) < 1e-6) {
            return PresencePoint(shoulder.x.coerceIn(0.0, 1.0), boxBottomCenter.y.coerceIn(0.0, 1.0))
        }
        val alpha = (boxBottomCenter.y - shoulder.y) / dy
        return PresencePoint(
            x = (shoulder.x + alpha * dx).coerceIn(0.0, 1.0),
            y = (shoulder.y + alpha * dy).coerceIn(0.0, 1.0)
        )
    }

    private fun estimateFootPoint(keypoints: List<PresenceKeypoint>): Pair<PresencePoint?, Double> {
        val left = keypoints.getOrNull(15)
        val right = keypoints.getOrNull(16)
        return when {
            left != null && right != null -> {
                PresencePoint(
                    x = ((left.x + right.x) * 0.5).coerceIn(0.0, 1.0),
                    y = ((left.y + right.y) * 0.5).coerceIn(0.0, 1.0)
                ) to ((left.confidence + right.confidence) * 0.5).coerceIn(0.0, 1.0)
            }
            left != null -> PresencePoint(left.x.coerceIn(0.0, 1.0), left.y.coerceIn(0.0, 1.0)) to left.confidence.coerceIn(0.0, 1.0)
            right != null -> PresencePoint(right.x.coerceIn(0.0, 1.0), right.y.coerceIn(0.0, 1.0)) to right.confidence.coerceIn(0.0, 1.0)
            else -> null to 0.0
        }
    }

    private fun appendDoorOriginSample(
        state: TrackRuntimeState,
        obs: PresenceTrackObservation,
        groundPoint: PresencePoint
    ) {
        state.doorOriginSamples.addLast(
            DoorOriginSample(
                frameSeq = frameSeq,
                timestampMs = obs.timestampMs,
                point = groundPoint
            )
        )
        while (state.doorOriginSamples.size > DOOR_ORIGIN_WINDOW_MAX_FRAMES) {
            state.doorOriginSamples.removeFirst()
        }
    }

    private fun resolveDoorOriginToLiving(
        state: TrackRuntimeState,
        roomById: Map<String, PresenceRoomSnapshot>,
        doors: List<PresenceDoorSnapshot>,
        livingRoomId: String,
        preferredFromRoomId: String?
    ): DoorOriginDecision? {
        val samples = state.doorOriginSamples.toList()
        if (samples.size <= DOOR_ORIGIN_DELTA_WINDOW_FRAMES) return null
        val dMax = (params.nearDoorDist * DOOR_ORIGIN_DMAX_RATIO).coerceAtLeast(1e-6)
        val s0 = (params.nearDoorDist * DOOR_ORIGIN_S0_RATIO).coerceAtLeast(1e-6)
        val v0 = (s0 * DOOR_ORIGIN_V0_RATIO).coerceAtLeast(1e-6)
        val candidates = mutableListOf<DoorOriginCandidateScore>()

        for (door in doors) {
            val fromRoomId = when {
                door.roomAId == livingRoomId -> door.roomBId
                door.roomBId == livingRoomId -> door.roomAId
                else -> null
            } ?: continue
            if (!preferredFromRoomId.isNullOrBlank() && preferredFromRoomId != fromRoomId) continue

            val normal = computeDoorOriginNormalTowardRoom(
                door = door,
                targetRoomId = fromRoomId,
                roomById = roomById,
                livingRoomId = livingRoomId
            )
            val midX = (door.a.x + door.b.x) * 0.5
            val midY = (door.a.y + door.b.y) * 0.5
            var minDist = Double.MAX_VALUE
            var proximitySum = 0.0
            val signed = ArrayList<Double>(samples.size)

            for (sample in samples) {
                val dist = PresenceGeometry.distancePointToSegment(sample.point, door.a, door.b)
                minDist = min(minDist, dist)
                val proximity = (1.0 - dist / dMax).coerceIn(0.0, 1.0)
                proximitySum += proximity
                val relX = sample.point.x - midX
                val relY = sample.point.y - midY
                signed += relX * normal.first + relY * normal.second
            }
            if (!minDist.isFinite() || minDist > dMax) continue

            var crossDetected = false
            var outTrendMax = 0.0
            var anchorIndex = -1
            for (index in DOOR_ORIGIN_DELTA_WINDOW_FRAMES until signed.size) {
                val prev = signed[index - DOOR_ORIGIN_DELTA_WINDOW_FRAMES]
                val curr = signed[index]
                if (!crossDetected && prev >= s0 && curr <= -s0) {
                    crossDetected = true
                    anchorIndex = index
                }
                val ds = curr - prev
                val outTrend = ((-ds) / v0).coerceIn(0.0, 1.0)
                if (outTrend > outTrendMax) {
                    outTrendMax = outTrend
                    if (anchorIndex < 0) {
                        anchorIndex = index
                    }
                }
            }
            val crossingScore = max(if (crossDetected) 1.0 else 0.0, outTrendMax)
            val proximityScore = (proximitySum / samples.size.toDouble()).coerceIn(0.0, 1.0)
            val score = (
                DOOR_ORIGIN_CROSS_WEIGHT * crossingScore +
                    (1.0 - DOOR_ORIGIN_CROSS_WEIGHT) * proximityScore
                ).coerceIn(0.0, 1.0)

            val anchorSample = samples.getOrNull(anchorIndex.coerceAtLeast(0))
            candidates += DoorOriginCandidateScore(
                fromRoomId = fromRoomId,
                doorId = door.doorId,
                score = score,
                proximityScore = proximityScore,
                crossingScore = crossingScore,
                crossDetected = crossDetected,
                trendScore = outTrendMax,
                distanceMin = minDist,
                anchorFrameSeq = anchorSample?.frameSeq ?: -1L,
                anchorTimestampMs = anchorSample?.timestampMs ?: -1L
            )
        }
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedByDescending { it.score }
        val best = sorted.first()
        val secondScore = sorted.getOrNull(1)?.score ?: 0.0
        val blockedByLowScore = best.score < DOOR_ORIGIN_MIN_SCORE
        val blockedByMargin = (best.score - secondScore) < DOOR_ORIGIN_MIN_MARGIN
        val blockedByLedger = best.fromRoomId != OUTSIDE_ROOM_ID &&
            (internalPresenceCounts[best.fromRoomId] ?: 0) <= 0
        return DoorOriginDecision(
            candidate = best,
            secondScore = secondScore,
            blockedByLedger = blockedByLedger,
            blockedByLowScore = blockedByLowScore,
            blockedByMargin = blockedByMargin
        )
    }

    private fun computeDoorOriginNormalTowardRoom(
        door: PresenceDoorSnapshot,
        targetRoomId: String,
        roomById: Map<String, PresenceRoomSnapshot>,
        livingRoomId: String
    ): Pair<Double, Double> {
        val midX = (door.a.x + door.b.x) * 0.5
        val midY = (door.a.y + door.b.y) * 0.5
        val uxRaw = door.b.x - door.a.x
        val uyRaw = door.b.y - door.a.y
        val uLen = hypot(uxRaw, uyRaw).coerceAtLeast(1e-6)
        val ux = uxRaw / uLen
        val uy = uyRaw / uLen
        val n1x = -uy
        val n1y = ux
        val n2x = uy
        val n2y = -ux

        fun chooseByPoint(px: Double, py: Double): Pair<Double, Double> {
            val relX = px - midX
            val relY = py - midY
            val dot1 = relX * n1x + relY * n1y
            val dot2 = relX * n2x + relY * n2y
            return if (dot1 >= dot2) {
                n1x to n1y
            } else {
                n2x to n2y
            }
        }

        if (targetRoomId == OUTSIDE_ROOM_ID) {
            val living = roomById[livingRoomId]
            if (living != null && living.polygon.size >= 3) {
                val centroid = computePolygonCentroid(living.polygon)
                val towardLiving = chooseByPoint(centroid.x, centroid.y)
                return (-towardLiving.first) to (-towardLiving.second)
            }
        } else {
            val target = roomById[targetRoomId]
            if (target != null && target.polygon.size >= 3) {
                val centroid = computePolygonCentroid(target.polygon)
                return chooseByPoint(centroid.x, centroid.y)
            }
        }
        return n1x to n1y
    }

    private fun computeDoorMotionStats(
        state: TrackRuntimeState,
        currentGroundPoint: PresencePoint,
        door: PresenceDoorSnapshot,
        targetRoom: PresenceRoomSnapshot,
        nearDoorPassed: Boolean,
        motionEnabled: Boolean,
        useLongWindow: Boolean,
        longWindowFrames: Int
    ): DoorMotionStats {
        val shortWindow = params.enterMotionWindowFrames.coerceAtLeast(1)
        val longWindow = longWindowFrames.coerceAtLeast(shortWindow)
        if (!motionEnabled || state.groundPointHistory.size < shortWindow) {
            return DoorMotionStats(
                advanceDelta = 0.0,
                lateralDelta = 0.0,
                advanceLateralRatio = 0.0,
                advanceDeltaShort = 0.0,
                advanceDeltaLong = 0.0,
                windowUsed = "FALLBACK",
                pass = false,
                pastSignedDistance = 0.0,
                currentSignedDistance = 0.0,
                pastLateralDistance = 0.0,
                currentLateralDistance = 0.0,
                normalX = 0.0,
                normalY = 0.0,
                doorMidX = 0.0,
                doorMidY = 0.0,
                targetCentroidX = 0.0,
                targetCentroidY = 0.0,
                pastGroundX = 0.0,
                pastGroundY = 0.0,
                currentGroundX = currentGroundPoint.x,
                currentGroundY = currentGroundPoint.y,
                historySize = state.groundPointHistory.size,
                nearDoorPassed = nearDoorPassed
            )
        }
        val mid = PresencePoint(
            x = (door.a.x + door.b.x) * 0.5,
            y = (door.a.y + door.b.y) * 0.5
        )

        val uxRaw = door.b.x - door.a.x
        val uyRaw = door.b.y - door.a.y
        val uLen = hypot(uxRaw, uyRaw).coerceAtLeast(1e-6)
        val ux = uxRaw / uLen
        val uy = uyRaw / uLen

        val n1x = -uy
        val n1y = ux
        val n2x = uy
        val n2y = -ux
        val centroid = computePolygonCentroid(targetRoom.polygon)
        val toCentroidX = centroid.x - mid.x
        val toCentroidY = centroid.y - mid.y
        val dot1 = toCentroidX * n1x + toCentroidY * n1y
        val dot2 = toCentroidX * n2x + toCentroidY * n2y
        val nx = if (dot1 >= dot2) n1x else n2x
        val ny = if (dot1 >= dot2) n1y else n2y

        fun projectSignedAndLateral(point: PresencePoint): Pair<Double, Double> {
            val relX = point.x - mid.x
            val relY = point.y - mid.y
            val s = relX * nx + relY * ny
            val q = relX * ux + relY * uy
            return s to q
        }

        val pastGroundPointShort = state.groundPointHistory.elementAt(state.groundPointHistory.size - shortWindow)
        val (pastSShort, pastQShort) = projectSignedAndLateral(pastGroundPointShort)
        val (currS, currQ) = projectSignedAndLateral(currentGroundPoint)
        val deltaSShort = currS - pastSShort
        val deltaQShort = currQ - pastQShort
        val ratioShort = abs(deltaSShort) / (abs(deltaQShort) + 1e-6)

        val hasLongWindow = useLongWindow && state.groundPointHistory.size >= longWindow
        val (deltaSLong, deltaQLong, ratioLong) = if (hasLongWindow) {
            val pastGroundPointLong = state.groundPointHistory.elementAt(state.groundPointHistory.size - longWindow)
            val (pastSLong, pastQLong) = projectSignedAndLateral(pastGroundPointLong)
            val ds = currS - pastSLong
            val dq = currQ - pastQLong
            val r = abs(ds) / (abs(dq) + 1e-6)
            Triple(ds, dq, r)
        } else {
            Triple(deltaSShort, deltaQShort, ratioShort)
        }

        val windowUsed = when {
            hasLongWindow -> "LONG"
            useLongWindow -> "FALLBACK"
            else -> "SHORT"
        }
        val selectedDeltaS = if (hasLongWindow) deltaSLong else deltaSShort
        val selectedDeltaQ = if (hasLongWindow) deltaQLong else deltaQShort
        val selectedRatio = if (hasLongWindow) ratioLong else ratioShort
        val pass = selectedDeltaS >= params.enterNormalAdvanceMin &&
            selectedRatio >= params.enterLateralRatioMin

        return DoorMotionStats(
            advanceDelta = selectedDeltaS,
            lateralDelta = selectedDeltaQ,
            advanceLateralRatio = selectedRatio,
            advanceDeltaShort = deltaSShort,
            advanceDeltaLong = deltaSLong,
            windowUsed = windowUsed,
            pass = pass,
            pastSignedDistance = pastSShort,
            currentSignedDistance = currS,
            pastLateralDistance = pastQShort,
            currentLateralDistance = currQ,
            normalX = nx,
            normalY = ny,
            doorMidX = mid.x,
            doorMidY = mid.y,
            targetCentroidX = centroid.x,
            targetCentroidY = centroid.y,
            pastGroundX = pastGroundPointShort.x,
            pastGroundY = pastGroundPointShort.y,
            currentGroundX = currentGroundPoint.x,
            currentGroundY = currentGroundPoint.y,
            historySize = state.groundPointHistory.size,
            nearDoorPassed = nearDoorPassed
        )
    }

    private fun computePolygonCentroid(polygon: List<PresencePoint>): PresencePoint {
        if (polygon.isEmpty()) return PresencePoint(0.5, 0.5)
        val sx = polygon.sumOf { it.x }
        val sy = polygon.sumOf { it.y }
        return PresencePoint(
            x = (sx / polygon.size.toDouble()).coerceIn(0.0, 1.0),
            y = (sy / polygon.size.toDouble()).coerceIn(0.0, 1.0)
        )
    }

    private fun distanceScoreByDoor(distance: Double, nearDistInput: Double): DoorProximityScore {
        val nearDist = nearDistInput.coerceAtLeast(1e-6)
        val d = distance.coerceAtLeast(0.0)
        val raw = (1.0 - d / nearDist).coerceIn(0.0, 1.0)
        val tailRatio = params.doorProximitySoftTailRatio.coerceAtLeast(0.0)
        val score = if (tailRatio <= 1e-6) {
            raw
        } else {
            val dd0 = nearDist
            val dd1 = nearDist * (1.0 + tailRatio)
            when {
                d <= dd0 -> raw
                d >= dd1 -> 0.0
                else -> ((dd1 - d) / (dd1 - dd0)).coerceIn(0.0, 1.0)
            }
        }
        return DoorProximityScore(raw = raw, score = score)
    }

    private fun computeDynamicNearDoorDistance(box: PresenceRect?): Double {
        val h = box?.height ?: 0.0
        return (h * params.enterDoorDynamicRatio).coerceAtLeast(1e-6)
    }

    private fun computeContainmentScore(box: PresenceRect?, polygon: List<PresencePoint>): Double {
        if (box == null || polygon.size < 3 || box.area <= 1e-9) return 0.0
        val grid = params.cContainmentGrid.coerceIn(2, 40)
        var inside = 0
        val total = grid * grid
        for (iy in 0 until grid) {
            for (ix in 0 until grid) {
                val px = box.left + (ix + 0.5) * box.width / grid
                val py = box.top + (iy + 0.5) * box.height / grid
                if (PresenceGeometry.isPointInPolygon(PresencePoint(px, py), polygon)) {
                    inside += 1
                }
            }
        }
        return (inside.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * 房间归属得分（进出统一）：
     * - 每个关键点贡献 = 部位权重 * 关键点置信度
     * - 脚踝权重最高，但仍受置信度调制
     * - 得分 = 房间内贡献 / 全部有效贡献，范围 0~1
     */
    private fun computeRoomPoseScore(
        keypoints: List<PresenceKeypoint>,
        polygon: List<PresencePoint>
    ): Double {
        if (keypoints.isEmpty() || polygon.size < 3) return 0.0
        var totalWeightedScore = 0.0
        var insideWeightedScore = 0.0
        for (index in keypoints.indices) {
            val point = keypoints[index]
            val confidence = point.confidence.coerceIn(0.0, 1.0)
            if (confidence < params.exitPosePointMinConfidence) continue
            val weightedConfidence = confidence * keypointBodyWeight(index)
            totalWeightedScore += weightedConfidence
            val isInside = PresenceGeometry.isPointInPolygon(
                PresencePoint(point.x, point.y),
                polygon
            )
            if (isInside) {
                insideWeightedScore += weightedConfidence
            }
        }
        if (totalWeightedScore <= 1e-9) return 0.0
        return (insideWeightedScore / totalWeightedScore).coerceIn(0.0, 1.0)
    }

    /**
     * 目标房间内贡献最高的关键点（TopN），用于定位“为什么会被判进该房间”。
     * 格式：k{index}:{weightedConfidence}@{rawConfidence}
     */
    private fun buildTopPoseContributors(
        keypoints: List<PresenceKeypoint>,
        polygon: List<PresencePoint>,
        topN: Int = 5
    ): String {
        if (keypoints.isEmpty() || polygon.size < 3) return "-"
        data class Contributor(val index: Int, val weighted: Double, val raw: Double)
        val contributors = mutableListOf<Contributor>()
        for (index in keypoints.indices) {
            val point = keypoints[index]
            val confidence = point.confidence.coerceIn(0.0, 1.0)
            if (confidence < params.exitPosePointMinConfidence) continue
            val inside = PresenceGeometry.isPointInPolygon(PresencePoint(point.x, point.y), polygon)
            if (!inside) continue
            val weighted = confidence * keypointBodyWeight(index)
            contributors.add(Contributor(index, weighted, confidence))
        }
        if (contributors.isEmpty()) return "-"
        return contributors
            .sortedByDescending { it.weighted }
            .take(topN.coerceAtLeast(1))
            .joinToString(",") { c -> "k${c.index}:${fmt(c.weighted)}@${fmt(c.raw)}" }
    }

    /**
     * 当前目标的平均关键点置信度。
     * 用于区分“走出房间”（通常仍有稳定关键点）与“房间内消失”（关键点整体掉置信）。
     */
    private fun computeAveragePoseConfidence(keypoints: List<PresenceKeypoint>): Double {
        if (keypoints.isEmpty()) return 0.0
        val confidences = keypoints.map { it.confidence.coerceIn(0.0, 1.0) }
        return confidences.average().coerceIn(0.0, 1.0)
    }

    private fun computeEffectivePoseConfidence(
        keypoints: List<PresenceKeypoint>,
        minConfidence: Double
    ): Double {
        if (keypoints.isEmpty()) return 0.0
        val threshold = minConfidence.coerceIn(0.0, 1.0)
        val valid = keypoints
            .map { it.confidence.coerceIn(0.0, 1.0) }
            .filter { it >= threshold }
        if (valid.isEmpty()) return 0.0
        return valid.average().coerceIn(0.0, 1.0)
    }

    private fun syncPresenceRoomKeys(rooms: List<PresenceRoomSnapshot>) {
        val roomIds = rooms.map { it.roomId }.toSet()
        rooms.forEach { room ->
            if (!internalPresenceCounts.containsKey(room.roomId)) {
                internalPresenceCounts[room.roomId] = 0
            }
        }
        val removed = internalPresenceCounts.keys.filter { !roomIds.contains(it) }
        removed.forEach { internalPresenceCounts.remove(it) }
        internalPresenceCounts.replaceAll { _, v -> max(0, v) }
    }

    private fun pickRoomByPolygon(point: PresencePoint, rooms: List<PresenceRoomSnapshot>): String? {
        val hits = mutableListOf<Pair<String, Double>>()
        for (room in rooms) {
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
                val segmentLen = hypot(door.b.x - door.a.x, door.b.y - door.a.y)
                val extensionPenalty = max(0.0, max(-t, t - 1.0)) * segmentLen
                val score = dist + extensionPenalty
                candidates.add(Candidate(door.doorId, score, dist, t))
            }
        }
        if (candidates.isEmpty()) {
            return DoorPickResult(
                bestDoorId = null,
                bestDist = Double.MAX_VALUE,
                isAmbiguous = false,
                candidatesDebug = emptyList()
            )
        }
        val sorted = candidates.sortedBy { it.score }
        val best = sorted.first()
        val debug = sorted.map {
            "${it.doorId}(d=${fmt(it.dist)},t=${fmt(it.t)},s=${fmt(it.score)})"
        }
        if (sorted.size >= 2) {
            val second = sorted[1]
            if ((second.score - best.score) < params.doorSeparationMargin) {
                return DoorPickResult(
                    bestDoorId = null,
                    bestDist = best.dist,
                    isAmbiguous = true,
                    candidatesDebug = debug
                )
            }
        }
        return DoorPickResult(
            bestDoorId = best.doorId,
            bestDist = best.dist,
            isAmbiguous = false,
            candidatesDebug = debug
        )
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

    private fun shouldDisableBlindPending(
        fromRoomId: String,
        toRoomId: String,
        roomById: Map<String, PresenceRoomSnapshot>
    ): Boolean {
        return when (blindPendingPolicy) {
            BlindPendingPolicy.LEGACY -> false
            BlindPendingPolicy.DISABLE_BLIND -> toRoomId != OUTSIDE_ROOM_ID
            BlindPendingPolicy.ENTER_BLIND_ONLY -> {
                if (toRoomId == OUTSIDE_ROOM_ID) return false
                val toBlind = isBlindRoom(toRoomId, roomById)
                val fromBlind = isBlindRoom(fromRoomId, roomById)
                !(toBlind && !fromBlind)
            }
        }
    }

    private fun isBlindRoom(
        roomId: String,
        roomById: Map<String, PresenceRoomSnapshot>
    ): Boolean {
        if (roomId == OUTSIDE_ROOM_ID) return false
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
        events: MutableList<PresenceSwitchEvent>,
        rejectedReasons: MutableList<String>,
        blockContext: String = "-"
    ): Boolean {
        if (fromRoomId == toRoomId) return true
        val isOutsideTransition = fromRoomId == OUTSIDE_ROOM_ID || toRoomId == OUTSIDE_ROOM_ID
        if (!isOutsideTransition) {
            val fromCount = internalPresenceCounts[fromRoomId] ?: 0
            if (fromCount <= 0) {
                rejectedReasons.add(
                    "track=${trackId ?: -1} ledgerBlockApplied=true " +
                        "blockReason=FROM_COUNT_ZERO " +
                        "fromCount=$fromCount " +
                        "from=$fromRoomId to=$toRoomId door=$doorId " +
                        blockContext
                )
                return false
            }
        }
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
        return true
    }

    private fun sigmoid(x: Double): Double {
        val clamped = x.coerceIn(-50.0, 50.0)
        return 1.0 / (1.0 + exp(-clamped))
    }

    private fun fmt(v: Double): String = String.format("%.3f", v)

    companion object {
        const val OUTSIDE_ROOM_ID = "__outside__"
        private const val IDENTITY_RESET_GAP_FRAMES = 18
        private const val IDENTITY_RESET_JUMP_DIST = 0.25
        private const val ENTER_VISIBLE_DPS_GAMMA = 3.0
        private const val ENTER_VISIBLE_CROSS_WEIGHT = 0.8
        private const val ENTER_VISIBLE_POSE_WEIGHT = 0.2
        private const val ENTER_VISIBLE_INSIDE_SCORE_NEAR_DIST_RATIO = 0.8
        private const val ENTER_VISIBLE_INWARD_TREND_REF_MULTIPLIER = 1.3
        private const val ENTER_VISIBLE_PROX_FLOOR = 0.35
        private const val ENTER_VISIBLE_CONFIRM_FRAMES = 2
        private const val REVERSE_BOUNCE_WINDOW_MS = 900L
        private const val REVERSE_BOUNCE_MIN_FACTOR = 0.20
        private const val DEFAULT_FRAME_INTERVAL_MS = 33L
        private const val DOOR_ORIGIN_WINDOW_MAX_FRAMES = 18
        private const val DOOR_ORIGIN_DELTA_WINDOW_FRAMES = 2
        private const val DOOR_ORIGIN_DMAX_RATIO = 2.0
        private const val DOOR_ORIGIN_S0_RATIO = 0.3
        private const val DOOR_ORIGIN_V0_RATIO = 0.5
        private const val DOOR_ORIGIN_CROSS_WEIGHT = 0.7
        private const val DOOR_ORIGIN_MIN_SCORE = 0.50
        private const val DOOR_ORIGIN_MIN_MARGIN = 0.15
        private const val DOOR_ORIGIN_INIT_WAIT_FRAMES = 8

        private fun keypointBodyWeight(index: Int): Double {
            return when (index) {
                15, 16 -> 3.0 // 脚踝：出入房间核心证据，权重最高
                13, 14 -> 1.8 // 膝盖
                11, 12 -> 1.4 // 髋部
                5, 6 -> 1.2   // 肩部
                else -> 1.0
            }
        }
    }
}
