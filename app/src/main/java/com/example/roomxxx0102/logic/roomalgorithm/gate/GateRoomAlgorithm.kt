package com.example.roomxxx0102.logic.roomalgorithm.gate

import android.content.Context
import android.util.Log
import com.example.roomxxx0102.logic.roomalgorithm.*
import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalFrameHub
import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalV3Settings
import com.example.roomxxx0102.logic.presence.*

object GateAlgorithms {
    const val DIFFERENCE_ID = "gate_diff"
    const val FLOW_ID = "gate_flow"
    const val MOG2_ID = "gate_mog2"
    fun handles(id:String?)=GateMethod.fromId(id)!=null
    fun configurationKey(id:String,context:Context?)=GateSettings.key(context,requireNotNull(GateMethod.fromId(id)))
    fun create(id:String,context:Context?):RoomAlgorithmEngine=GateRoomAlgorithm(requireNotNull(GateMethod.fromId(id)),context?.applicationContext)
}

/** Three real sensing backends, one ground-first transition/ledger contract. No Legacy counts. */
internal class GateRoomAlgorithm(private val method:GateMethod,private val context:Context?):RoomAlgorithmEngine {
    override val algorithmId=method.id
    private val params=GateSettings.read(context,method)
    override val configurationKey=GateSettings.key(context,method)
    override val runtimeTag="Gate-1.0-${method.name}-${params.hashCode()}"
    private var core:GateDecisionCore?=null
    private var sensor:GateVisualSensor?=null
    private var fingerprint=""
    private var lastTime=-1L
    private var lastSeq=-1L
    private var identitySource=""
    private var initialKnown=false
    private var lastDecision:GateDecision?=null
    private var resetVersion=-1L

    @Synchronized override fun processFrame(input:RoomAlgorithmFrameInput):RoomAlgorithmFrameResult {
        val metadata=input.poseMetadata
        if(metadata!=null && metadata.stamp.epoch!=PortalFrameHub.epoch) return result(input,emptyList(),listOf("STALE_SOURCE_FRAME"))
        val living=input.rooms.firstOrNull { it.isLivingRoom } ?: return result(input,emptyList(),listOf("NO_LIVING_CALIBRATION"))
        val timestamp=metadata?.stamp?.timestampMs ?: input.timestampMs
        val seq=metadata?.stamp?.sequence ?: input.frameSeq
        val scene=PortalV3Settings.sceneKey(input.rooms)
        val baseline=PortalV3Settings.baseline(context,PortalV3Settings.baselineKey(scene,input.sceneInfo.isVideoPlayback))
        val aspect=input.imageWidth.coerceAtLeast(1).toDouble()/input.imageHeight.coerceAtLeast(1)
        val key="$scene|${input.doors}|$aspect|${metadata?.stamp?.epoch}|${input.sceneInfo.isVideoPlayback}|${baseline.revision}"
        if(core==null || key!=fingerprint) {
            sensor?.close()
            val polygon=living.polygon.map { GP(it.x,it.y) }
            val gates=input.doors.mapNotNull { d ->
                val target=if(d.roomAId==living.roomId)d.roomBId else if(d.roomBId==living.roomId)d.roomAId else return@mapNotNull null
                val r=input.rooms.firstOrNull { it.roomId==target } ?: return@mapNotNull null
                Gate.create(d.doorId,target,GP(d.a.x,d.a.y),GP(d.b.x,d.b.y),r.polygon.map { GP(it.x,it.y) },polygon,aspect,d.isEntranceDoor,r.isBlindZone)
            }
            initialKnown=baseline.known && (!input.sceneInfo.isVideoPlayback || timestamp<=500)
            core=GateDecisionCore(living.roomId,polygon,gates,input.rooms.map { it.roomId },aspect,params,
                if(initialKnown)baseline.counts else emptyMap(),method==GateMethod.OPTICAL_FLOW)
            sensor=createSensor(gates)
            fingerprint=key;lastTime=-1;lastSeq=-1;lastDecision=null;identitySource="";resetVersion=GateRuntime.backgroundRevision
            Log.i("GateAlgorithm","init id=$algorithmId tag=$runtimeTag gates=${gates.size}/${input.doors.size} initialKnown=$initialKnown params=$params")
        }
        if(timestamp<=lastTime || seq<=lastSeq) return result(input,emptyList(),listOf("STALE_OR_DUPLICATE_FRAME"))
        val engine=core!!
        val source=input.poses.firstOrNull()?.idSource?.name ?: identitySource
        if(source.isNotEmpty() && identitySource.isNotEmpty() && source!=identitySource) {
            lastDecision=engine.detachIdentities();sensor?.close();sensor=createSensor(engine.gates)
        }
        identitySource=source
        if(resetVersion!=GateRuntime.backgroundRevision) {
            sensor?.close();sensor=createSensor(engine.gates);engine.invalidateVisual();resetVersion=GateRuntime.backgroundRevision
        }
        val detections=input.poses.map { d -> GDetection(d.id,GB(d.box.left.toDouble(),d.box.top.toDouble(),d.box.right.toDouble(),d.box.bottom.toDouble()),
            d.keypoints.map { GJoint(GP(it.x.toDouble(),it.y.toDouble()),it.conf.toDouble()) },d.score.toDouble(),d.isConfirmed,d.isShielded) }
        val successful=metadata?.successful!=false
        val visual=if(successful) sensor!!.measure(input.bitmap,timestamp,detections,lastDecision?.people ?: emptyList())
                   else GateVisualBatch(timestamp,healthy=false,message="DETECTOR_ERROR_NOT_ABSENCE")
        val coverage=metadata?.roi?.let { GB(it.left.toDouble(),it.top.toDouble(),it.right.toDouble(),it.bottom.toDouble()) }
        val started=System.nanoTime()
        val decision=engine.step(timestamp,if(successful)detections else null,visual,coverage)
        val coreMs=(System.nanoTime()-started)/1e6
        lastDecision=decision;lastTime=timestamp;lastSeq=seq
        GateRuntime.recordOutput(algorithmId,timestamp,visual,coreMs,decision)
        GateOverlay.publish(method.title,decision,visual,engine.gates,input.rooms.associate { it.roomId to it.roomName },initialKnown,coreMs)
        decision.events.forEach { Log.i("GateEvent","algorithm=$algorithmId from=${it.from} to=${it.to} gate=${it.gate} track=${it.track} time=${it.time} inferred=${it.inferred}") }
        return result(input,decision.events,decision.notes+listOf(visual.message))
    }
    private fun createSensor(gates:List<Gate>):GateVisualSensor=when(method) {
        GateMethod.DIFFERENCE -> DifferenceGateSensor(gates,params)
        GateMethod.OPTICAL_FLOW -> OpticalFlowGateSensor(gates,params)
        GateMethod.MOG2 -> Mog2GateSensor(gates,params)
    }
    private fun result(input:RoomAlgorithmFrameInput,events:List<GateEvent>,notes:List<String>):RoomAlgorithmFrameResult {
        val decision=lastDecision
        val ids=input.poses.map { it.id }.toSet()
        return RoomAlgorithmFrameResult(
            input.rooms.associate { r -> r.roomId to (decision?.people?.count { it.accepted && it.track in ids && it.room==r.roomId && !r.isEntranceDoor } ?: 0) },
            decision?.counts ?: input.rooms.associate { it.roomId to 0 },
            events.map { PresenceSwitchEvent(it.track,it.from,it.to,it.gate,if(it.inferred)PresenceEventReason.PENDING_CONFIRMED else PresenceEventReason.VISIBLE_SWITCH,it.time) },
            emptyMap(),notes.takeLast(12),emptyMap(),RoomAlgorithmDebugInfo(
                method.title+if(initialKnown)" | \u521d\u59cb\u4eba\u6570\u5df2\u8bbe\u7f6e" else " | \u9690\u85cf\u521d\u59cb\u4eba\u6570\u672a\u77e5",
                mapOf("algorithm" to algorithmId,"params" to params.toString(),"initialKnown" to initialKnown.toString(),
                    "lower" to decision?.lower.toString(),"upperKnownPeople" to decision?.upper.toString(),
                    "unknownLocation" to (decision?.unknown ?: 0).toString(),"backlog" to "0")))
    }
    @Synchronized override fun reset() {
        sensor?.close();sensor=null;core=null;lastDecision=null;fingerprint="";lastTime=-1;lastSeq=-1;identitySource=""
        GateOverlay.clear()
    }
}
