package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.content.Context
import android.util.Log
import com.example.roomxxx0102.logic.presence.*
import com.example.roomxxx0102.logic.roomalgorithm.*
import com.example.roomxxx0102.logic.roomalgorithm.flow.*

/**
 * V5 experimental full-app adapter. Shares only the V4 local *measurement* backend,
 * never its state machine, ground estimator, or WAIT_CLEAR transitions.
 */
class DiffV52RoomAlgorithm internal constructor(
    private val context: Context?,
    private val config: GateConfig,
) : RoomAlgorithmEngine {
    override val algorithmId = GateMethod.V5_2_DIFFERENCE.id
    override val configurationKey = algorithmId + "|" + config.key
    override val runtimeTag = "GateV5.2-DIFF-EARLY-ROI"
    private var core: DiffV52Core? = null
    private var vision: GateEventVision? = null
    private var gates = emptyList<FlowGate>()
    private var roomIds = emptyList<String>()
    private var livingId = ""
    private var lastDecision: FlowDecision? = null
    private var lastResult: GateEventVisionResult? = null
    private var roomsCache = emptyList<PresenceRoomSnapshot>()
    private var doorsCache = emptyList<PresenceDoorSnapshot>()
    private var epoch = -1L
    private var lastTime = -1L
    private var lastSequence = -1L
    private var baselineRevision = -1L
    private var aspect = 0.0
    private var identitySource = ""
    private var initialKnown = false
    private var logAt = -1L

    @Synchronized override fun processFrame(input: RoomAlgorithmFrameInput): RoomAlgorithmFrameResult {
        val meta = input.poseMetadata
        if (meta != null && meta.stamp.epoch != PortalFrameHub.epoch) {
            return result(input, emptyList(), listOf("V52_STALE_EPOCH"))
        }
        val sourceEpoch = meta?.stamp?.epoch ?: PortalFrameHub.epoch
        val time = meta?.stamp?.timestampMs ?: input.timestampMs
        val seq = meta?.stamp?.sequence ?: input.frameSeq
        val living = input.rooms.firstOrNull { it.isLivingRoom }
            ?: return result(input, emptyList(), listOf("V52_NO_LIVING_GEOMETRY"))
        val frameAspect = input.imageWidth.coerceAtLeast(1).toDouble() / input.imageHeight.coerceAtLeast(1)
        val scene = PortalV3Settings.sceneKey(input.rooms)
        val baseline = PortalV3Settings.baseline(context,
            PortalV3Settings.baselineKey(scene, input.sceneInfo.isVideoPlayback))
        if (core == null || epoch != sourceEpoch || roomsCache != input.rooms ||
            doorsCache != input.doors || baselineRevision != baseline.revision || aspect != frameAspect) {
            vision?.close()
            val polygon = living.polygon.map { FlowPoint(it.x, it.y) }
            gates = input.doors.mapNotNull { door ->
                val target = when (living.roomId) {
                    door.roomAId -> door.roomBId
                    door.roomBId -> door.roomAId
                    else -> return@mapNotNull null
                }
                val room = input.rooms.firstOrNull { it.roomId == target } ?: return@mapNotNull null
                FlowGate.create(door.doorId, target,
                    FlowPoint(door.a.x, door.a.y), FlowPoint(door.b.x, door.b.y),
                    room.polygon.map { FlowPoint(it.x, it.y) }, polygon, frameAspect,
                    door.isEntranceDoor, room.isBlindZone)
            }
            livingId = living.roomId
            roomIds = input.rooms.map { it.roomId }
            initialKnown = baseline.known && (!input.sceneInfo.isVideoPlayback || time <= 500)
            core = DiffV52Core(livingId, gates.filter { it.isExterior }.map { it.room }.toSet(),
                if (initialKnown) baseline.counts else emptyMap(), config.confirmMs.toLong())
            if (GateDiagnosticBus.isCapturing()) GateDiagnosticBus.configure(config)
            vision = GateEventVision.create(gates, config)
            roomsCache = input.rooms.toList()
            doorsCache = input.doors.toList()
            baselineRevision = baseline.revision
            epoch = sourceEpoch
            lastTime = -1L
            lastSequence = -1L
            lastDecision = null
            lastResult = null
            identitySource = ""
            aspect = frameAspect
            Log.i("PortalV52", "runtime=$runtimeTag gates=${gates.size}/${input.doors.size} initialKnown=$initialKnown")
        }
        if (seq <= lastSequence || time <= lastTime) {
            return result(input, emptyList(), listOf("V52_DUPLICATE_OR_REVERSED_FRAME"))
        }
        val engine = core!!
        val identity = input.poses.firstOrNull()?.idSource?.name ?: identitySource
        if (identitySource.isNotEmpty() && identity != identitySource) {
            engine.detachIdentitySource()
            vision?.close()
            vision = GateEventVision.create(gates, config)
        }
        identitySource = identity
        val detections = input.poses.map { d ->
            FlowDetection(d.id, FlowBox(d.box.left.toDouble(), d.box.top.toDouble(),
                d.box.right.toDouble(), d.box.bottom.toDouble()),
                d.keypoints.map { FlowJoint(FlowPoint(it.x.toDouble(), it.y.toDouble()), it.conf.toDouble()) },
                d.score.toDouble(), d.isConfirmed, d.isShielded)
        }
        val coverage = meta?.roi?.let {
            FlowBox(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble())
        }
        val healthyPose = meta?.successful != false
        val started = System.nanoTime()
        val visual = if (input.bitmap != null && healthyPose) {
            vision?.update(input.bitmap, time, detections, lastDecision?.people.orEmpty(), coverage)
        } else null
        lastResult = visual
        val measurements = visual?.diagnostics.orEmpty().mapNotNull { d ->
            val roomId = gates.firstOrNull { it.id == d.gateId }?.room ?: return@mapNotNull null
            DiffV5Signal(time, d.gateId, roomId, d.ownerTrack ?: return@mapNotNull null,
                d.phase, d.owned?.pixels ?: 0, d.owned?.p50, d.bodySide, d.bodyAlong,
                d.bodyInsideRatio, d.motion?.pixels ?: 0)
        }
        val verified = engine.step(time, measurements,
            frameHealthy = healthyPose && visual?.healthy != false && visual != null)
        val events = verified.map { e ->
            if (e.direction == "ENTER") FlowEvent(e.ownerTrack, e.ownerTrack, livingId, e.roomId,
                e.gateId, e.timeMs, true)
            else FlowEvent(e.ownerTrack, e.ownerTrack, e.roomId, livingId, e.gateId, e.timeMs, true)
        }
        val ownerRooms = engine.ownedRooms
        val people = detections.map { d ->
            val room = ownerRooms[d.id]
            FlowPersonView(d.id, d.id, room, null, d.box,
                if (room == null) "V52_UNASSIGNED" else "V52_DIFF_CONFIRMED",
                emptySet(), room != null)
        }
        val counts = roomIds.associateWith { engine.counts[it] ?: 0 }
        val decision = FlowDecision(counts, counts, counts, people.count { it.room == null },
            events, people, verified.map { "V52_DIFF:${it.direction}:${it.roomId}:${it.evidence}" })
        lastDecision = decision
        lastSequence = seq
        lastTime = time
        val notes = decision.notes + visual?.notes.orEmpty() +
            (if (vision == null) listOf("V52_OPENCV_NOT_AVAILABLE_NO_FALLBACK") else emptyList())
        if (GateDiagnosticBus.isCapturing()) publishDiagnostics(time, seq, decision, visual, notes)
        GateRuntime.output(android.os.SystemClock.elapsedRealtime())
        GateOverlay.publish(config.method.label, decision, gates, visual, initialKnown,
            input.rooms.associate { it.roomId to it.roomName }, config)
        if (time - logAt >= 1000 || events.isNotEmpty()) {
            logAt = time
            Log.i("PortalV52", "t=$time frame=$seq measured=${measurements.size} pending=${engine.pendingPortalCount} " +
                "visionMs=${visual?.costMs ?: 0} totalMs=${(System.nanoTime()-started)/1_000_000} events=$events")
        }
        return result(input, events, notes)
    }

    private fun publishDiagnostics(time: Long, seq: Long, decision: FlowDecision,
                                   visual: GateEventVisionResult?, notes: List<String>) {
        val detailed = visual?.diagnostics.orEmpty().associateBy { it.gateId }
        val portals = visual?.tiles.orEmpty().map { tile ->
            detailed[tile.gate] ?: GatePortalDiagnostic(tile.gate, tile.owner, tile.phase.name,
                tile.schedulerDistance, tile.contact, tile.referenceKnown, null,
                tile.foreground, 0, 0L, tile.historyFrames, null, null, null)
        }
        val people = decision.people.map { p ->
            GateDiagnosticPerson(person = p.person, track = p.track, room = p.room,
                status = p.status, accepted = p.accepted, candidates = emptyList(), box = p.box,
                groundX = null, groundY = null, groundStrong = null, groundUncertainty = null,
                groundSource = null, gateId = null, phase = null, direction = null,
                depth = null, groundSide = null, groundDistance = null, groundAlong = null,
                evidence = if (p.accepted) "V52_DIFF_CONFIRMED" else "V52_UNASSIGNED")
        }
        val events = decision.events.map { e ->
            GateDiagnosticEvent(e.person, e.track, e.from, e.to, e.gate,
                if (e.to == livingId) "EXIT" else "ENTER", e.timeMs, true, "V52_DIFF_REALTIME")
        }
        GateDiagnosticBus.publish(GateDiagnosticFrame(time, seq, runtimeTag,
            visual?.activeGates ?: 0, people, portals, events, decision.counts, notes.takeLast(12)))
    }

    private fun result(input: RoomAlgorithmFrameInput, events: List<FlowEvent>,
                       notes: List<String>): RoomAlgorithmFrameResult {
        val d = lastDecision
        val visible = input.poses.map { it.id }.toSet()
        val counts = d?.counts ?: input.rooms.associate { it.roomId to 0 }
        val observed = input.rooms.associate { room ->
            room.roomId to (d?.people?.count { it.accepted && it.track in visible && it.room == room.roomId } ?: 0)
        }
        return RoomAlgorithmFrameResult(observed, counts, events.map { e ->
            PresenceSwitchEvent(e.track, e.from, e.to, e.gate,
                PresenceEventReason.PENDING_CONFIRMED, e.timeMs)
        }, emptyMap(), notes.takeLast(12), emptyMap(),
            RoomAlgorithmDebugInfo(config.method.label, mapOf(
                "runtime" to runtimeTag,
                "initialKnown" to initialKnown.toString(),
                "pendingGateEpisodes" to (core?.pendingPortalCount ?: 0).toString(),
                "visionMs" to (lastResult?.costMs ?: 0).toString(),
                "activeGates" to (lastResult?.activeGates ?: 0).toString(),
                "historyKB" to ((lastResult?.historyBytes ?: 0)/1024).toString(),
                "outputLag" to "deferred-until-portal-idle",
                "config" to config.key
            )))
    }

    @Synchronized override fun reset() {
        vision?.close()
        vision = null
        core = null
        lastDecision = null
        lastResult = null
        lastTime = -1L
        lastSequence = -1L
        epoch = -1L
        GateOverlay.clear()
    }
}