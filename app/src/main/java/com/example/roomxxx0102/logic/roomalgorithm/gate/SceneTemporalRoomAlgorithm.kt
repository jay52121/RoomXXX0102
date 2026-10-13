package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.content.Context
import com.example.roomxxx0102.logic.presence.*
import com.example.roomxxx0102.logic.roomalgorithm.*
import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import com.example.roomxxx0102.logic.roomalgorithm.temporal.*

/** Optional calibrated temporal detector; never replaces the selected old mode.
 * It consumes existing YOLO upper-body observations, not legacy event results.
 * No pixels/feet/ground-plane extrapolation or annotation access is performed. */
class SceneTemporalRoomAlgorithm internal constructor(
    private val context: Context?, private val config: GateConfig
): RoomAlgorithmEngine {
    override val algorithmId = "portal_scene_temporal_v6"
    override val configurationKey = algorithmId + "|" + config.key
    override val runtimeTag = "SceneTemporalV6-CALIBRATED-UPPER-BODY"
    private var model: TemporalPortalModel? = null
    private var core: TemporalPortalCore? = null
    private var rooms = emptyList<PresenceRoomSnapshot>()
    private var doors = emptyList<PresenceDoorSnapshot>()
    private var lastEpoch = -1L
    private var lastSeq = -1L
    private var lastTime = -1L
    private var source = ""
    private var aspect = 0.0
    private var baselineRevision = -1L
    private var living = "living_room"

    @Synchronized override fun processFrame(input: RoomAlgorithmFrameInput): RoomAlgorithmFrameResult {
        val meta = input.poseMetadata
        if(meta!=null && meta.stamp.epoch!=PortalFrameHub.epoch)
            return result(input,emptyList(),listOf("TEMPORAL_STALE_EPOCH"))
        val epoch = meta?.stamp?.epoch ?: PortalFrameHub.epoch
        val t = meta?.stamp?.timestampMs ?: input.timestampMs
        val seq = meta?.stamp?.sequence ?: input.frameSeq
        val central=input.rooms.firstOrNull { it.isLivingRoom }
            ?: return result(input,emptyList(),listOf("TEMPORAL_NO_LIVING_ROOM"))
        val a=input.imageWidth.coerceAtLeast(1).toDouble()/input.imageHeight.coerceAtLeast(1)
        val scene=PortalV3Settings.sceneKey(input.rooms)
        val baseline=PortalV3Settings.baseline(context,PortalV3Settings.baselineKey(scene,input.sceneInfo.isVideoPlayback))
        if(model==null) {
            val c=context ?: return result(input,emptyList(),listOf("TEMPORAL_ASSET_CONTEXT_UNAVAILABLE"))
            try { model=c.assets.open("portal_temporal/scene_model.bin.gz").use { TemporalPortalModel.load(it) } }
            catch(e:Exception) { return result(input,emptyList(),listOf("TEMPORAL_MODEL_ERROR:${e.javaClass.simpleName}")) }
        }
        if(core==null || rooms!=input.rooms || doors!=input.doors || epoch!=lastEpoch || a!=aspect || baselineRevision!=baseline.revision) {
            val polygon=central.polygon.map { FlowPoint(it.x,it.y) }
            val gs=input.doors.mapNotNull { d ->
                val target=when(central.roomId) { d.roomAId->d.roomBId;d.roomBId->d.roomAId;else->return@mapNotNull null }
                val r=input.rooms.firstOrNull{it.roomId==target} ?: return@mapNotNull null
                if(r.isBlindZone || r.polygon.size<3)return@mapNotNull null
                val gate=FlowGate.create(d.doorId,target,FlowPoint(d.a.x,d.a.y),FlowPoint(d.b.x,d.b.y),
                    r.polygon.map{FlowPoint(it.x,it.y)},polygon,a,d.isEntranceDoor,r.isBlindZone) ?: return@mapNotNull null
                TemporalPortalCore.Gate(gate.id,gate.room,gate.a.x,gate.a.y,gate.b.x,gate.b.y,
                    gate.insideSign,gate.aspect,gate.aperture.map{it.x to it.y})
            }
            living=central.roomId
            val known=baseline.known && (!input.sceneInfo.isVideoPlayback || t<=500)
            core=TemporalPortalCore(gs,model!!,living,if(known)baseline.counts else emptyMap())
            rooms=input.rooms.toList();doors=input.doors.toList();lastEpoch=epoch;aspect=a
            baselineRevision=baseline.revision;lastTime=-1;lastSeq=-1;source=""
            GateOverlay.clear()
            if(GateDiagnosticBus.isCapturing())GateDiagnosticBus.configure(config)
        }
        if(t<=lastTime || seq<=lastSeq)return result(input,emptyList(),listOf("TEMPORAL_DUPLICATE_FRAME"))
        val src=input.poses.firstOrNull()?.idSource?.name ?: source
        if(source.isNotEmpty() && src!=source)core!!.detachIdentitySource()
        source=src
        val poses=input.poses.map { d ->
            TemporalPortalCore.Pose(d.id,d.box.left.toDouble(),d.box.top.toDouble(),d.box.right.toDouble(),
                d.box.bottom.toDouble(),d.score.toDouble(),d.isShielded,
                d.keypoints.map { TemporalPortalCore.Point(it.x.toDouble(),it.y.toDouble(),it.conf.toDouble()) })
        }
        val start=System.nanoTime()
        val out=core!!.step(t,poses,meta?.successful!=false)
        lastTime=t;lastSeq=seq
        val ms=(System.nanoTime()-start)/1000000.0
        val notes=listOf("TEMPORAL_CALIBRATED_MODEL_NOT_UNIVERSAL", "TEMPORAL_MODEL_MS=$ms") +
            core!!.observations.map { o -> "TEMPORAL_P:${o.gateId}:${o.probabilities.joinToString(",")}" }
        if(GateDiagnosticBus.isCapturing()) {
            val owned=core!!.ownedRooms
            val people=input.poses.map { d ->
                GateDiagnosticPerson(person=d.id,track=d.id,room=owned[d.id],status="TEMPORAL_OBSERVED",
                    accepted=owned.containsKey(d.id),candidates=emptyList(),
                    box=FlowBox(d.box.left.toDouble(),d.box.top.toDouble(),d.box.right.toDouble(),d.box.bottom.toDouble()),
                    groundX=null,groundY=null,groundStrong=null,groundUncertainty=null,groundSource=null,
                    gateId=null,phase=null,direction=null,depth=null,groundSide=null,groundDistance=null,
                    groundAlong=null,evidence="CAUSAL_UPPER_BODY_SEQUENCE")
            }
            val ev=out.map { e -> GateDiagnosticEvent(e.owner,e.owner,
                if(e.direction=="ENTER")living else e.roomId,if(e.direction=="ENTER")e.roomId else living,
                e.gateId,e.direction,t,true,"TEMPORAL_POSTERIOR=${e.confidence}") }
            GateDiagnosticBus.publish(GateDiagnosticFrame(t,seq,runtimeTag,core!!.observations.size,
                people,emptyList(),ev,core!!.counts,notes.takeLast(12)))
        }
        GateRuntime.output(android.os.SystemClock.elapsedRealtime())
        return result(input,out,notes)
    }
    private fun result(input:RoomAlgorithmFrameInput, out:List<TemporalPortalCore.Crossing>, notes:List<String>):RoomAlgorithmFrameResult {
        val counts=input.rooms.associate { it.roomId to (core?.counts?.get(it.roomId) ?: 0) }
        val owned=core?.ownedRooms.orEmpty()
        val observed=input.rooms.associate { r->r.roomId to input.poses.count { owned[it.id]==r.roomId } }
        val events=out.map { e -> PresenceSwitchEvent(e.owner,
            if(e.direction=="ENTER")living else e.roomId,if(e.direction=="ENTER")e.roomId else living,
            e.gateId,PresenceEventReason.PENDING_CONFIRMED,e.timeMs) }
        return RoomAlgorithmFrameResult(observed,counts,events,emptyMap(),notes.takeLast(12),emptyMap(),
            RoomAlgorithmDebugInfo(config.method.label,mapOf("runtime" to runtimeTag,"timeSource" to "actual-input-frame",
                "model" to "69-features/350-trees","calibration" to "existing-roomxxx-scene-only")))
    }
    @Synchronized override fun reset(){core=null;model=null;rooms=emptyList();doors=emptyList();lastEpoch=-1;lastSeq=-1;lastTime=-1;source="";GateOverlay.clear()}
}
