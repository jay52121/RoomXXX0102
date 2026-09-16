package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.content.Context
import android.util.Log
import com.example.roomxxx0102.logic.roomalgorithm.*
import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import com.example.roomxxx0102.logic.presence.*

/** Three measurement backends share one ledger and one event-driven portal scheduler. */
class GateRoomAlgorithm internal constructor(private val context:Context?,private val config:GateConfig):RoomAlgorithmEngine {
    override val algorithmId=config.method.id
    override val configurationKey=algorithmId+"|"+config.key
    override val runtimeTag="GateV4.2-${config.method.name}-EPISODE_CORE"
    private var core:PortalV4Core?=null
    private var vision:GateEventVision?=null
    private var lastDecision:FlowDecision?=null
    private var roomCache=emptyList<PresenceRoomSnapshot>()
    private var doorCache=emptyList<PresenceDoorSnapshot>()
    private var epoch=-1L;private var lastTime=-1L;private var lastSequence=-1L
    private var initialKnown=false;private var baselineRevision=-1L
    private var identitySource="";private var geometryAspect=0.0
    private var logAt=-1L
    private var lastResult:GateEventVisionResult?=null

    @Synchronized override fun processFrame(input:RoomAlgorithmFrameInput):RoomAlgorithmFrameResult {
        val meta=input.poseMetadata
        if(meta!=null && meta.stamp.epoch!=PortalFrameHub.epoch) return result(input,emptyList(),listOf("STALE_EPOCH"))
        val sourceEpoch=meta?.stamp?.epoch?:PortalFrameHub.epoch
        val time=meta?.stamp?.timestampMs?:input.timestampMs
        val seq=meta?.stamp?.sequence?:input.frameSeq
        val living=input.rooms.firstOrNull { it.isLivingRoom }?:return result(input,emptyList(),listOf("NO_LIVING_GEOMETRY"))
        val aspect=input.imageWidth.coerceAtLeast(1).toDouble()/input.imageHeight.coerceAtLeast(1)
        val scene=PortalV3Settings.sceneKey(input.rooms)
        val baseline=PortalV3Settings.baseline(context,PortalV3Settings.baselineKey(scene,input.sceneInfo.isVideoPlayback))
        if(core==null || sourceEpoch!=epoch || roomCache!=input.rooms || doorCache!=input.doors || baselineRevision!=baseline.revision || aspect!=geometryAspect) {
            vision?.close()
            val polygon=living.polygon.map { FlowPoint(it.x,it.y) }
            val gates=input.doors.mapNotNull { d ->
                val target=when(living.roomId) { d.roomAId->d.roomBId;d.roomBId->d.roomAId;else->return@mapNotNull null }
                val r=input.rooms.firstOrNull { it.roomId==target }?:return@mapNotNull null
                FlowGate.create(d.doorId,target,FlowPoint(d.a.x,d.a.y),FlowPoint(d.b.x,d.b.y),r.polygon.map { FlowPoint(it.x,it.y) },polygon,aspect,d.isEntranceDoor,r.isBlindZone)
            }
            initialKnown=baseline.known && (!input.sceneInfo.isVideoPlayback || time<=500)
            core=PortalV4Core(living.roomId,polygon,gates,input.rooms.map { it.roomId },aspect,
                if(initialKnown) baseline.counts else emptyMap(),PortalV4Policy.from(config))
            if(GateDiagnosticBus.isCapturing()) GateDiagnosticBus.configure(config)
            vision=GateEventVision.create(gates,config)
            roomCache=input.rooms.toList();doorCache=input.doors.toList();baselineRevision=baseline.revision
            epoch=sourceEpoch;lastTime=-1;lastSequence=-1;lastDecision=null;identitySource="";lastResult=null;geometryAspect=aspect
            Log.i("PortalV4","runtime=$runtimeTag method=$algorithmId gates=${gates.size}/${input.doors.size} initialKnown=$initialKnown config=$config")
        }
        if(seq<=lastSequence || time<=lastTime) return result(input,emptyList(),listOf("DUPLICATE_OR_REVERSED_FRAME"))
        val engine=core!!
        val source=input.poses.firstOrNull()?.idSource?.name?:identitySource
        if(identitySource.isNotEmpty() && source!=identitySource) {
            lastDecision=engine.detachIdentitySource();vision?.close();vision=GateEventVision.create(engine.gates,config)
        }
        identitySource=source
        val detections=input.poses.map { d -> FlowDetection(d.id,FlowBox(d.box.left.toDouble(),d.box.top.toDouble(),d.box.right.toDouble(),d.box.bottom.toDouble()),
            d.keypoints.map { FlowJoint(FlowPoint(it.x.toDouble(),it.y.toDouble()),it.conf.toDouble()) },d.score.toDouble(),d.isConfirmed,d.isShielded) }
        val roi=meta?.roi?.let { FlowBox(it.left.toDouble(),it.top.toDouble(),it.right.toDouble(),it.bottom.toDouble()) }
        val successful=meta?.successful!=false
        val start=System.nanoTime()
        val bitmap=input.bitmap
        // Failed inference is neither an empty detection nor permission to create a disappearance event.
        val visual=if(bitmap!=null && successful) vision?.update(bitmap,time,detections,lastDecision?.people.orEmpty(),roi) else null
        lastResult=visual
        val anchored=if(visual?.origin!=null) detections.map { d -> if(d.id==visual.origin.first) d.copy(originGate=visual.origin.second) else d } else detections
        val decision=engine.step(time,detections=if(successful) anchored else null,depths=visual?.depths.orEmpty(),flows=visual?.flows.orEmpty(),coverage=roi,frameHealthy=successful && visual?.healthy!=false)
        lastDecision=decision;lastSequence=seq;lastTime=time
        val notes=decision.notes+visual?.notes.orEmpty()+if(vision==null) listOf("NATIVE_UNAVAILABLE_GROUND_ONLY") else emptyList()
        val debug=engine.debugSnapshot()
        if(GateDiagnosticBus.isCapturing()) {
            val detailed=visual?.diagnostics.orEmpty().associateBy{it.gateId}
            val portals=visual?.tiles.orEmpty().map{tile->
                detailed[tile.gate]?:GatePortalDiagnostic(
                    gateId=tile.gate,ownerTrack=tile.owner,phase=tile.phase.name,
                    schedulerDistance=tile.schedulerDistance,contact=tile.contact,referenceKnown=tile.referenceKnown,
                    exclusive=null,foregroundPixels=tile.foreground,peakPixels=0,clearForMs=0L,
                    historyFrames=tile.historyFrames,bodyMotionRatio=null,motion=null,owned=null
                )
            }
            val people=decision.people.map{person->
                val d=debug[person.track]
                val gate=d?.gateId?.let{id->engine.gates.firstOrNull{it.id==id}}
                val ground=person.ground
                GateDiagnosticPerson(
                    person=person.person,track=person.track,room=person.room,status=person.status,
                    accepted=person.accepted,candidates=person.candidates.sorted(),box=person.box,
                    groundX=ground?.point?.x,groundY=ground?.point?.y,groundStrong=ground?.strong,
                    groundUncertainty=ground?.uncertainty,groundSource=ground?.source,gateId=d?.gateId,
                    phase=d?.phase?.name,direction=d?.direction,depth=d?.depth,groundSide=d?.groundSide,
                    groundDistance=if(gate!=null&&ground!=null)gate.distance(ground.point)else null,
                    groundAlong=if(gate!=null&&ground!=null)gate.along(ground.point)else null,
                    evidence=d?.evidence
                )
            }
            val diagEvents=decision.events.map{event->
                val direction=when{event.from==living.roomId->"ENTER";event.to==living.roomId->"EXIT";else->"INTERNAL"}
                GateDiagnosticEvent(
                    person=event.person,track=event.track,from=event.from,to=event.to,gateId=event.gate,
                    direction=direction,timeMs=event.timeMs,inferred=event.inferred,reason=debug[event.track]?.evidence
                )
            }
            GateDiagnosticBus.publish(GateDiagnosticFrame(
                timeMs=time,frameSeq=seq,runtimeTag=runtimeTag,activeGates=visual?.activeGates?:0,
                people=people,portals=portals,events=diagEvents,counts=decision.counts,notes=notes.takeLast(12)
            ))
        }
        GateRuntime.output(android.os.SystemClock.elapsedRealtime())
        GateOverlay.publish(config.method.label,decision,engine.gates,visual,initialKnown,input.rooms.associate { it.roomId to it.roomName },config,debug)
        if(time-logAt>=1000 || decision.events.isNotEmpty()) {
            logAt=time
            Log.i("PortalV4","runtime=$runtimeTag t=$time seq=$seq poseMs=${GateRuntime.poseCostMs} visionMs=${visual?.costMs?:0} " +
                "roomMs=${(System.nanoTime()-start)/1000000} activeGates=${visual?.activeGates?:0} historyKB=${(visual?.historyBytes?:0)/1024} " +
                "points=${visual?.points?:0} queue=0 skipped=${GateRuntime.skipped} events=${decision.events} notes=$notes")
        }
        return result(input,decision.events,notes)
    }
    private fun result(input:RoomAlgorithmFrameInput,events:List<FlowEvent>,notes:List<String>):RoomAlgorithmFrameResult {
        val d=lastDecision;val visible=input.poses.map { it.id }.toSet()
        val counts=d?.counts?:input.rooms.associate { it.roomId to 0 }
        val observed=input.rooms.associate { room -> room.roomId to (d?.people?.count { it.accepted && it.track in visible && it.room==room.roomId }?:0) }
        return RoomAlgorithmFrameResult(observed,counts,
            events.map { PresenceSwitchEvent(it.track,it.from,it.to,it.gate,if(it.inferred) PresenceEventReason.PENDING_CONFIRMED else PresenceEventReason.VISIBLE_SWITCH,it.timeMs) },
            emptyMap(),notes.takeLast(12),emptyMap(),RoomAlgorithmDebugInfo(config.method.label,
                mapOf("runtime" to runtimeTag,"initialKnown" to initialKnown.toString(),"lower" to d?.lower.toString(),"upperKnownPeople" to d?.upper.toString(),
                    "visionMs" to (lastResult?.costMs?:0).toString(),"activeGates" to (lastResult?.activeGates?:0).toString(),
                    "historyKB" to ((lastResult?.historyBytes?:0)/1024).toString(),"queueDepth" to "0","config" to config.key)))
    }
    @Synchronized override fun reset() {
        vision?.close();vision=null;core=null;lastDecision=null;lastResult=null
        lastTime=-1;lastSequence=-1;epoch=-1;GateOverlay.clear()
    }
}
