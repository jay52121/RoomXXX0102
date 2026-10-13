package com.example.roomxxx0102.logic.roomalgorithm.temporal

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/** Replays measured poses. T rows are read by this TEST HARNESS only, after
 * constructing runtime evidence. Never pass annotations to TemporalPortalCore. */
fun main(args:Array<String>) {
    require(args.size>=3) { "model.bin.gz fixture.replay.gz output.json [drop seed jitterPx shiftMs]" }
    val model=TemporalPortalModel.load(File(args[0]).inputStream())
    val gates=mutableListOf<TemporalPortalCore.Gate>()
    data class Truth(val id:Int,val time:Long,val room:String,val dir:String)
    val truths=mutableListOf<Truth>()
    val poses=mutableListOf<TemporalPortalCore.Pose>()
    var core:TemporalPortalCore?=null
    var now=-1L;var healthy=true;var detach=false;var frameCount=0
    val drop=args.getOrNull(3)?.toDouble()?:0.0
    val rng=java.util.Random(args.getOrNull(4)?.toLong()?:1L)
    val jitter=args.getOrNull(5)?.toDouble()?:0.0
    val shift=args.getOrNull(6)?.toLong()?:0L
    val dump=args.getOrNull(7)?.let { java.io.DataOutputStream(File(it).outputStream()) }
    dump?.writeInt(69)
    val events=mutableListOf<TemporalPortalCore.Crossing>()
    val frames=StringBuilder();var first=true
    var featureCount=0L;var sourceFrames=0
    fun escape(s:String)="\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""
    fun flush() {
        if(now<0){poses.clear();return}
        sourceFrames++
        val keep=drop==0.0 || rng.nextDouble()>=drop
        if(!keep){poses.clear();return}
        if(core==null)core=TemporalPortalCore(gates,model)
        if(detach)core!!.detachIdentitySource()
        val out=core!!.step(now,poses.toList(),healthy)
        check(out.all{it.timeMs==now})
        events+=out;featureCount+=core!!.observations.size
        if(dump!=null)for(obs in core!!.observations) {
            dump.writeLong(now);dump.writeUTF(obs.gateId);dump.writeInt(obs.owner)
            for(value in obs.features)dump.writeFloat(value)
        }
        if(!first)frames.append(',');first=false
        frames.append("{\"timeMs\":$now,\"input\":{},\"outputs\":[")
        frames.append(out.joinToString(",") { e ->
            val from=if(e.direction=="ENTER")"living_room" else e.roomId
            val to=if(e.direction=="ENTER")e.roomId else "living_room"
            "{\"timeMs\":$now,\"gateId\":"+escape(e.gateId)+",\"direction\":"+escape(e.direction)+
                ",\"from\":"+escape(from)+",\"to\":"+escape(to)+",\"owner\":${e.owner},\"confidence\":${e.confidence}}"
        })
        frames.append("]}");frameCount++;poses.clear()
    }
    GZIPInputStream(File(args[1]).inputStream()).bufferedReader().useLines { lines ->
        lines.forEach { line ->
            val p=line.split('|')
            when(p[0]) {
                "G" -> {
                    val polygon=p[9].split(';').map { a->val v=a.split(',');v[0].toDouble() to v[1].toDouble() }
                    gates+=TemporalPortalCore.Gate(p[1],p[2],p[3].toDouble(),p[4].toDouble(),p[5].toDouble(),p[6].toDouble(),p[7].toDouble(),p[8].toDouble(),polygon)
                }
                "F" -> { flush();now=p[1].toLong()+shift;healthy=p[2]=="1";detach=p[3]=="1" }
                "P" -> {
                    val joints=MutableList(17) { TemporalPortalCore.Point(0.0,0.0,0.0) }
                    for((i,j) in listOf(0,5,6,7,8,9,10,11,12).withIndex()) {
                        joints[j]=TemporalPortalCore.Point(p[8+i*3].toDouble()+rng.nextGaussian()*jitter/2232.0,
                            p[9+i*3].toDouble()+rng.nextGaussian()*jitter/1256.0,p[10+i*3].toDouble())
                    }
                    poses+=TemporalPortalCore.Pose(p[1].toInt(),p[4].toDouble(),p[5].toDouble(),p[6].toDouble(),p[7].toDouble(),p[2].toDouble(),p[3]=="1",joints)
                }
                "T" -> truths+=Truth(p[1].toInt(),p[2].toLong()+shift,p[3],p[4])
                else -> error("Unexpected fixture row")
            }
        }
    }
    flush()
    dump?.close()
    val used=mutableSetOf<Int>();var hits=0;var quick=0;var maxLag=Long.MIN_VALUE;var minLag=Long.MAX_VALUE
    for(gt in truths) {
        val candidates=events.indices.filter{it !in used && events[it].roomId==gt.room && events[it].direction==gt.dir && events[it].timeMs-gt.time in -100L..1000L}
        if(candidates.isEmpty())continue
        val i=candidates.minByOrNull{abs(events[it].timeMs-gt.time)}!!
        used+=i;hits++;val lag=events[i].timeMs-gt.time;if(lag<=500)quick++;maxLag=maxOf(maxLag,lag);minLag=minOf(minLag,lag)
    }
    val truthJson=truths.joinToString(",") { t -> "{\"id\":${t.id},\"timeMs\":${t.time},\"portalRoomId\":"+escape(t.room)+",\"direction\":"+escape(t.dir)+"}" }
    File(args[2]).writeText("{\"algorithm\":\"SceneTemporalV6-native\",\"groundTruth\":[$truthJson],\"frames\":[$frames]}")
    println("NATIVE frames=$frameCount inputs=$featureCount hits=$hits/${truths.size} extra=${events.size-hits} in500=$quick range=$minLag..$maxLag")
}
