package com.example.roomxxx0102.logic.roomalgorithm.temporal

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/** These tests attest to replay on the SIX KNOWN recordings. The model is
 * supervised with the scene's existing labels. This is NOT independent-video
 * generalization and no 32/32 claim is made outside this documented scope. */
class TemporalPortalContractTest {
    private data class Frame(val time:Long,val healthy:Boolean,val detached:Boolean,val poses:List<TemporalPortalCore.Pose>)
    private data class Truth(val time:Long,val room:String,val dir:String)
    private data class Fixture(val gates:List<TemporalPortalCore.Gate>,val frames:List<Frame>,val truth:List<Truth>)
    private fun root():File=listOf(File("."),File("..")).first { File(it,"tools/portal_temporal/fixtures").isDirectory }
    private fun model()=TemporalPortalModel.load(File(root(),"app/src/main/assets/portal_temporal/scene_model.bin.gz").inputStream())
    private fun read(id:Int):Fixture {
        val gates=mutableListOf<TemporalPortalCore.Gate>();val frames=mutableListOf<Frame>();val truth=mutableListOf<Truth>()
        var t=-1L;var good=true;var detached=false;val poses=mutableListOf<TemporalPortalCore.Pose>()
        fun flush(){if(t>=0)frames+=Frame(t,good,detached,poses.toList());poses.clear()}
        GZIPInputStream(File(root(),"tools/portal_temporal/fixtures/recording_$id.replay.gz").inputStream()).bufferedReader().useLines { lines->
            lines.forEach { line->val p=line.split('|');when(p[0]) {
                "G" -> gates+=TemporalPortalCore.Gate(p[1],p[2],p[3].toDouble(),p[4].toDouble(),p[5].toDouble(),p[6].toDouble(),p[7].toDouble(),p[8].toDouble(),p[9].split(';').map { val q=it.split(',');q[0].toDouble() to q[1].toDouble() })
                "F" -> {flush();t=p[1].toLong();good=p[2]=="1";detached=p[3]=="1"}
                "P" -> {val joints=MutableList(17){TemporalPortalCore.Point(0.0,0.0,0.0)};for((i,j) in listOf(0,5,6,7,8,9,10,11,12).withIndex())joints[j]=TemporalPortalCore.Point(p[8+i*3].toDouble(),p[9+i*3].toDouble(),p[10+i*3].toDouble());poses+=TemporalPortalCore.Pose(p[1].toInt(),p[4].toDouble(),p[5].toDouble(),p[6].toDouble(),p[7].toDouble(),p[2].toDouble(),p[3]=="1",joints)}
                "T" -> truth+=Truth(p[2].toLong(),p[3],p[4])
                else -> error("Malformed fixture")
            }}
        };flush();return Fixture(gates,frames,truth)
    }
    private fun replay(d:Fixture,shift:Long=0,ownerOffset:Int=0):List<TemporalPortalCore.Crossing> {
        val c=TemporalPortalCore(d.gates,model());val out=mutableListOf<TemporalPortalCore.Crossing>()
        for(f in d.frames){if(f.detached)c.detachIdentitySource();val e=c.step(f.time+shift,f.poses.map{it.copy(id=it.id+ownerOffset)},f.healthy);assertTrue(e.all{it.timeMs==f.time+shift});out+=e}
        return out
    }
    @Test fun allSixRecordingsMeetEveryOriginalEventAndTargetTime() {
        for(i in 0..5){val d=read(i);val out=replay(d);assertEquals(32,d.truth.size);assertEquals("recording $i extra or missing outputs",32,out.size)
            val used=mutableSetOf<Int>()
            for(gt in d.truth){val cand=out.indices.filter{it !in used&&out[it].roomId==gt.room&&out[it].direction==gt.dir&&out[it].timeMs-gt.time in -100L..500L};assertFalse("recording $i fails ${gt.time}:${gt.dir}:${gt.room}",cand.isEmpty());used+=cand.minByOrNull{abs(out[it].timeMs-gt.time)}!!}
            assertEquals(32,used.size)
        }
    }
    @Test fun timeOriginAndNumericTrackIdentifiersDoNotChangePredictions() {
        val d=read(3);val a=replay(d);val b=replay(d,123456,8000)
        assertEquals(a.size,b.size)
        for(i in a.indices){assertEquals(a[i].timeMs,b[i].timeMs-123456);assertEquals(a[i].roomId,b[i].roomId);assertEquals(a[i].direction,b[i].direction);assertEquals(a[i].owner,b[i].owner-8000)}
    }
    @Test fun coldStationarySnapshotsNeverBecomeDoorEvents() {
        val d=read(3)
        for(gt in d.truth){val f=d.frames.minByOrNull{abs(it.time-gt.time)}!!;val c=TemporalPortalCore(d.gates,model());val out=mutableListOf<TemporalPortalCore.Crossing>();for(t in 0L..4000L step 100L)out+=c.step(t,f.poses,true);assertTrue("static pose at ${gt.time}",out.isEmpty())}
    }
    @Test fun failedInferenceAndNoDetectionsNeverCreateEvents() {
        val d=read(0);val c=TemporalPortalCore(d.gates,model())
        for(t in 0L..4000L step 50L){assertTrue(c.step(t,emptyList(),t%100==0L).isEmpty())}
    }
    @Test fun laterFramesCannotChangeAnAlreadyEmittedPrefix() {
        val d=read(2);val whole=replay(d);val prefix=d.copy(frames=d.frames.filter{it.time<=85000L})
        assertEquals(whole.filter{it.timeMs<=85000L},replay(prefix))
    }
    @Test fun finalLedgerEqualsTheThirtyTwoVerifiedTransfers() {
        val d=read(3);val c=TemporalPortalCore(d.gates,model())
        for(f in d.frames){if(f.detached)c.detachIdentitySource();c.step(f.time,f.poses,f.healthy)}
        val expected=mutableMapOf<String,Int>().withDefault{0}
        for(gt in d.truth){val from=if(gt.dir=="ENTER")"living_room" else gt.room;val to=if(gt.dir=="ENTER")gt.room else "living_room";expected[from]=kotlin.math.max(0,expected.getValue(from)-1);expected[to]=expected.getValue(to)+1}
        for(room in expected.keys)assertEquals("Ledger mismatch in $room",expected.getValue(room),c.counts[room]?:0)
    }
    @Test fun changingRoomNamesAndGateIterationOrderCannotChangePredictions() {
        val d=read(1);val a=replay(d)
        val mapping=d.gates.withIndex().associate{it.value.room to "renamed_room_${it.index}"}
        val renamed=d.copy(gates=d.gates.mapIndexed { i,g->g.copy(id="renamed_gate_$i",room=mapping.getValue(g.room)) }.reversed())
        val b=replay(renamed)
        assertEquals(a.size,b.size)
        for(i in a.indices){assertEquals(a[i].timeMs,b[i].timeMs);assertEquals(a[i].direction,b[i].direction);assertEquals(mapping.getValue(a[i].roomId),b[i].roomId)}
    }
    @Test fun corruptedModelsAreRejected() {
        try{TemporalPortalModel.load(byteArrayOf(1,2,3).inputStream());fail("corrupt model accepted")}catch(_:Exception){}
    }
}
