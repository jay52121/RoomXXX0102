package com.example.roomxxx0102.logic.roomalgorithm.flow

import android.content.Context
import android.util.Log
import com.example.roomxxx0102.logic.roomalgorithm.*
import com.example.roomxxx0102.logic.presence.*

/** A real alternative ledger/transition algorithm, not a Legacy visualization wrapper. */
class PortalV3RoomAlgorithm(private val context: Context? = null) : RoomAlgorithmEngine {
    override val algorithmId = "portal_v3_flow"
    override val runtimeTag = "PortalV3-FlowGate-1.0"
    override val configurationKey = algorithmId
    private var fingerprint = ""
    private var core: PortalV3Core? = null
    private var vision: PortalV3Vision? = null
    private var epoch = -1L
    private var sequence = -1L
    private var lastTime = -1L
    private var lastDecision: FlowDecision? = null
    private var initialKnown = false
    private var scene = ""
    private val originTried = linkedMapOf<Int, Long>()
    private var identitySource = ""

    @Synchronized override fun processFrame(input: RoomAlgorithmFrameInput): RoomAlgorithmFrameResult {
        val meta = input.poseMetadata
        if (meta != null && meta.stamp.epoch != PortalFrameHub.epoch) return result(input, emptyList(), listOf("STALE_SOURCE_FRAME"))
        val living = input.rooms.firstOrNull { it.isLivingRoom }
            ?: return result(input, emptyList(), listOf("NO_LIVING_CALIBRATION"))
        val sourceEpoch = meta?.stamp?.epoch ?: 0L
        val aspect = input.imageWidth.coerceAtLeast(1).toDouble() / input.imageHeight.coerceAtLeast(1)
        val nextScene = PortalV3Settings.sceneKey(input.rooms)
        val baseline = PortalV3Settings.baseline(context, PortalV3Settings.baselineKey(nextScene, input.sceneInfo.isVideoPlayback))
        val idSource = input.poses.firstOrNull()?.idSource?.name ?: identitySource
        val applyStartCounts = baseline.known && (!input.sceneInfo.isVideoPlayback || (meta?.stamp?.timestampMs ?: input.timestampMs) <= 500L)
        val key = nextScene + input.doors.toString() + "|$aspect|${input.sceneInfo.isVideoPlayback}|${baseline.known}:${baseline.revision}"
        if (core == null || fingerprint != key || epoch != sourceEpoch) {
            vision?.close()
            val polygon = living.polygon.map { FlowPoint(it.x, it.y) }
            val gates = input.doors.mapNotNull { d ->
                val target = if (d.roomAId == living.roomId) d.roomBId else if (d.roomBId == living.roomId) d.roomAId else return@mapNotNull null
                val r = input.rooms.firstOrNull { it.roomId == target } ?: return@mapNotNull null
                FlowGate.create(d.doorId,target,FlowPoint(d.a.x,d.a.y),FlowPoint(d.b.x,d.b.y),r.polygon.map { FlowPoint(it.x,it.y) },polygon,aspect,d.isEntranceDoor,r.isBlindZone)
            }
            core = PortalV3Core(living.roomId,polygon,gates,input.rooms.map { it.roomId },aspect,if(applyStartCounts) baseline.counts else emptyMap())
            core!!.restoreProfiles(PortalV3Settings.readProfiles(context,nextScene))
            vision = PortalV3Vision(gates)
            fingerprint=key; scene=nextScene; epoch=sourceEpoch; sequence=-1; lastTime=-1
            lastDecision=null; initialKnown=applyStartCounts; originTried.clear()
            Log.i("PortalV3", "reset source=$sourceEpoch gates=${gates.size}/${input.doors.size} initialKnown=$initialKnown")
        }
        if (identitySource.isNotEmpty() && idSource.isNotEmpty() && identitySource != idSource) {
            lastDecision = core!!.detachIdentitySource()
            vision?.close(); vision = PortalV3Vision(core!!.gates); originTried.clear()
        }
        identitySource=idSource
        val engine=core!!; val visual=vision!!
        val stamp=meta?.stamp
        if (stamp != null && stamp.sequence <= sequence) return result(input,emptyList(),listOf("DUPLICATE_FRAME"))
        val eventBatch=mutableListOf<FlowEvent>(); val allNotes=mutableListOf<String>()
        var displayFlows=emptyMap<Int,FlowEvidence>()
        val frames=if(stamp!=null) PortalFrameHub.through(sequence,stamp) else emptyList()
        val roi=meta?.roi?.let { FlowBox(it.left.toDouble(),it.top.toDouble(),it.right.toDouble(),it.bottom.toDouble()) }
        var detections=input.poses.map { d -> FlowDetection(d.id,FlowBox(d.box.left.toDouble(),d.box.top.toDouble(),d.box.right.toDouble(),d.box.bottom.toDouble()),d.keypoints.map { FlowJoint(FlowPoint(it.x.toDouble(),it.y.toDouble()),it.conf.toDouble()) },d.score.toDouble(),d.isConfirmed,d.isShielded) }
        for (frame in frames) {
            val measuredFlows=visual.advance(frame)
            val flows=measuredFlows.toMutableMap()
            detections.forEach { d -> flows.putIfAbsent(d.id,FlowEvidence(d.id,imageAvailable=!visual.unavailable,frameHealthy=visual.healthy,reason="NO_OWNED_FEATURE_SUPPORT")) }
            displayFlows=flows
            val target=frame.stamp.sequence == stamp!!.sequence
            val observation=if (target && meta?.successful == true) {
                val accepted=lastDecision?.people?.filter { it.accepted }?.map { it.track }?.toSet() ?: emptySet()
                visual.observe(detections,frame.stamp.timestampMs,accepted)
                detections=detections.map { d ->
                    if (d.id !in accepted && frame.stamp.timestampMs - (originTried[d.id] ?: -10000) >= 400 && engine.gates.any { it.distance(d.box.foot) < 0.15 }) {
                        originTried[d.id]=frame.stamp.timestampMs
                        d.copy(originGate=visual.traceOrigin(d,PortalFrameHub.before(frame.stamp)))
                    } else d
                }
                detections
            } else null
            val decision=engine.step(frame.stamp.timestampMs,observation,flows,if(target) roi else null,visual.healthy && (!target || meta?.successful != false))
            lastDecision=decision; lastTime=frame.stamp.timestampMs; sequence=frame.stamp.sequence
            eventBatch+=decision.events; allNotes+=decision.notes
        }
        // Missing images, failed inference, and a successful empty result are distinct states.
        if (frames.isEmpty() || (stamp != null && sequence < stamp.sequence)) {
            val time=stamp?.timestampMs ?: input.timestampMs
            if (time > lastTime) {
                val decision=engine.step(time,if(meta?.successful != false) detections else null,coverage=roi,
                    frameHealthy=meta?.successful != false)
                lastDecision=decision; lastTime=time; eventBatch+=decision.events; allNotes+=decision.notes
            }
            if(stamp!=null) sequence=stamp.sequence
            allNotes+="NO_ALIGNED_IMAGE_GROUND_ONLY"
        }
        if (originTried.size > 128) originTried.clear()
        if (eventBatch.any { !it.inferred }) PortalV3Settings.saveProfiles(context,scene,engine.profileSamples())
        if(visual.cameraMoved) allNotes+="CAMERA_MOVED_RECALIBRATE"
        if(visual.unavailable) allNotes+="OPENCV_UNAVAILABLE_GROUND_ONLY"
        val decision=lastDecision
        if(decision!=null) {
            PortalV3Overlay.publish(decision,engine.gates,displayFlows.values.flatMap { it.samples },initialKnown,
                input.rooms.associate { it.roomId to it.roomName },visual.lastCostMs,allNotes.takeLast(6))
        }
        eventBatch.forEach { Log.i("PortalV3", "person=${it.person} track=${it.track} from=${it.from} to=${it.to} gate=${it.gate} time=${it.timeMs} inferred=${it.inferred}") }
        return result(input,eventBatch,allNotes.takeLast(10))
    }

    private fun result(input: RoomAlgorithmFrameInput, events: List<FlowEvent>, notes: List<String>): RoomAlgorithmFrameResult {
        val d=lastDecision
        val ids=input.poses.map { it.id }.toSet()
        val observed=input.rooms.associate { r -> r.roomId to (d?.people?.count { it.accepted && it.track in ids && it.room == r.roomId } ?: 0) }
        val counts=d?.counts ?: input.rooms.associate { it.roomId to 0 }
        return RoomAlgorithmFrameResult(observed,counts,
            events.map { PresenceSwitchEvent(it.track,it.from,it.to,it.gate,if(it.inferred) PresenceEventReason.PENDING_CONFIRMED else PresenceEventReason.VISIBLE_SWITCH,it.timeMs) },
            emptyMap(),notes,emptyMap(),RoomAlgorithmDebugInfo(
                if(initialKnown) "V3 \u5df2\u8bbe\u7f6e\u521d\u59cb\u4eba\u6570" else "V3 \u4ec5\u7edf\u8ba1\u5df2\u77e5\u4eba\uff1b\u9690\u85cf\u521d\u59cb\u4eba\u6570\u672a\u77e5",
                mapOf("initialKnown" to initialKnown.toString(),"lower" to d?.lower.toString(),"upperKnownPeople" to d?.upper.toString(),"unknownLocation" to (d?.unknown?:0).toString(),"pending" to d?.people?.filter { it.candidates.isNotEmpty() }?.joinToString { "${it.person}:${it.candidates}" }.orEmpty())
            ))
    }
    @Synchronized override fun reset() {
        vision?.close(); vision=null; core=null; fingerprint=""; lastDecision=null; sequence=-1; lastTime=-1; epoch=-1
        originTried.clear(); identitySource=""; PortalV3Overlay.clear()
    }
}
