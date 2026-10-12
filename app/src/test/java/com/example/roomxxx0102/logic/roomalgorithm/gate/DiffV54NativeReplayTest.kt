package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Exact FRAME-by-FRAME Kotlin replay of the real V5.4 Android recording.
 *
 * F rows are processed input frames, M rows are measured optical/pose evidence,
 * and T rows are HUMAN LABELS used only *after* prediction for scoring.
 * No result from the Android run is injected into the state machine.
 *
 * CAUTION: passing this test is NOT a claim of accurate crossing detection.
 * The current real-device baseline remains very poor: 6/32 strict matches,
 * 3 extra outputs and only 3 matches <=500ms after the limited peak-occupancy
 * correction. These explicit bounds catch regressions while development
 * remains gated by independent new-video validation.
 */
class DiffV54NativeReplayTest {
    private data class Truth(val id: Int, val timeMs: Long, val roomId: String, val dir: String)
    private data class Result(val events: List<DiffV54Core.Crossing>, val frames: Int,
                      val measures: Int, val truths: List<Truth>)
    private fun replay(dropChance: Double = 0.0, seed: Long = 1L): Result {
        val rng = java.util.Random(seed)
        val input = javaClass.classLoader!!.getResourceAsStream("v54_real_device_flow_replay.txt")
            ?: error("Missing pinned V5.4 native recording")
        val core = DiffV54Core("living_room", confirmMs=120L)
        val events = mutableListOf<DiffV54Core.Crossing>()
        val ground = mutableListOf<Truth>()
        var now = -1L
        var healthy = true
        var detached = false
        var frames = 0
        var count = 0
        val signals = mutableListOf<DiffV54Core.Measurement>()
        fun parse(p: String) = p.toDoubleOrNull()
        fun flush() {
            if (now < 0) return
            if (detached) core.detachIdentitySource()
            val out = core.step(now, signals.toList(), healthy)
            assertTrue("Causal output must use current input VIDEO time, not an inferred earlier frame",
                out.all { it.timeMs == now })
            events += out
            signals.clear()
        }
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val p = line.split('|')
                when(p[0]) {
                    "F" -> {
                        flush()
                        // Drop a complete analysis frame including every gate
                        // measurement; human labels remain outside the decoder.
                        val keep = dropChance <= 0.0 || rng.nextDouble() >= dropChance
                        now = if (keep) p[1].toLong() else -1L
                        healthy=p[2]=="1"
                        detached=p[3]=="1"
                        if (keep) frames++
                    }
                    "M" -> {
                        if (now < 0) return@forEach
                        signals += DiffV54Core.Measurement(
                            p[1].toLong(), p[2], p[3], p[4].toInt(), p[5]=="true",
                            parse(p[6]), parse(p[7]), parse(p[8]), p[9].toInt(),
                            parse(p[10]), p[11].toInt())
                        count++
                    }
                    "T" -> ground += Truth(p[1].toInt(),p[2].toLong(),p[3],p[4])
                    else -> error("Malformed replay line: $line")
                }
            }
        }
        flush()
        return Result(events,frames,count,ground)
    }

    @Test fun nativeReplayExposesStrictRealResponseFloorWithoutHidingFalsePositives() {
        val r=replay()
        assertEquals(1681,r.frames)
        assertEquals(1161,r.measures)
        assertEquals(32,r.truths.size)
        val used=mutableSetOf<Int>()
        var quick=0
        for(gt in r.truths) {
            val candidates=r.events.indices.filter { i ->
                i !in used && r.events[i].roomId==gt.roomId &&
                    r.events[i].direction==gt.dir &&
                    r.events[i].timeMs-gt.timeMs in -100L..1000L
            }
            if(candidates.isEmpty()) continue
            val index=candidates.minBy { abs(r.events[it].timeMs-gt.timeMs) }
            used += index
            if(r.events[index].timeMs-gt.timeMs<=500L) quick++
        }
        val extras=r.events.size-used.size
        println("V54_REAL_NATIVE frames=${r.frames} points=${r.measures} " +
            "matches=${used.size}/32 outputs=${r.events.size} extra=$extras within500=$quick")
        // Assert the measured, unmodified real LK recording. This is still
        // far from the desired 32/32 and is not an independent blind test.
        assertTrue("Native LK regression below verified candidate floor",used.size>=11)
        assertEquals("Causal stage transitions reintroduced early/late outputs",0,extras)
        assertTrue("Prompt (<=500ms) response regression",quick>=7)
        assertTrue("Missing exterior EXIT near 3.823s",r.events.any {
            it.direction=="EXIT" && it.roomId==r.truths[0].roomId &&
                it.timeMs in 3723L..4823L
        })
        assertTrue("Missing kitchen EXIT near 68.567s",r.events.any {
            it.direction=="EXIT" && it.roomId==r.truths[12].roomId &&
                it.timeMs in 68467L..69567L
        })
    }

    @Test fun genuineLaterBathroomInAndOutRecoverAfterAnEarlierRetreat() {
        val r=replay()
        val targetIn=r.truths.first { it.id == 23 }
        val targetOut=r.truths.first { it.id == 24 }
        assertTrue("RETREAT swallowed the next genuine ENTER",
            r.events.any { it.roomId==targetIn.roomId && it.direction=="ENTER" &&
                it.timeMs-targetIn.timeMs in -100L..1000L })
        assertTrue("Same person's subsequent EXIT was lost or posted late",
            r.events.any { it.roomId==targetOut.roomId && it.direction=="EXIT" &&
                it.timeMs-targetOut.timeMs in -100L..1000L })
        // The two previous early child-room entries must not recur.
        for (id in listOf(10,20)) {
            val gt=r.truths.first { it.id==id }
            assertTrue("Saturation caused early child-room ENTER #$id",
                r.events.none { it.roomId==gt.roomId && it.direction=="ENTER" &&
                    it.timeMs < gt.timeMs-100L &&
                    kotlin.math.abs(it.timeMs-gt.timeMs)<2000L })
        }
    }

    @Test fun recordedSamplingDropoutsCannotRestoreTheRetreatLock() {
        for (drop in listOf(.03,.10)) {
            val scored=mutableListOf<Pair<Int,Int>>()
            for (seed in 0L until 10L) {
                val r=replay(drop,seed)
                val used=mutableSetOf<Int>()
                for (gt in r.truths) {
                    val matches=r.events.indices.filter { i ->
                        i !in used && r.events[i].roomId==gt.roomId &&
                        r.events[i].direction==gt.dir &&
                        r.events[i].timeMs-gt.timeMs in -100L..1000L
                    }
                    if (matches.isNotEmpty())
                        used+=matches.minBy { abs(r.events[it].timeMs-gt.timeMs) }
                }
                val extras=r.events.size-used.size
                scored+=used.size to extras
                assertTrue("Drop=${drop*100}% seed=$seed matched only ${used.size}",
                    used.size>=8)
                assertTrue("Drop=${drop*100}% seed=$seed emitted $extras extras",
                    extras<=3)
            }
            println("V54_DROPOUT $drop minHits=${scored.minOf { it.first }} " +
                "worstExtra=${scored.maxOf { it.second }}")
        }
    }

    @Test fun changingTrackIdCannotInheritEarlierPersonsMotion() {
        val core=DiffV54Core("living_room")
        core.step(100L,listOf(DiffV54Core.Measurement(100L,"g","r",1,true,-.15,.8,.5,1300,null,0)))
        core.step(190L,listOf(DiffV54Core.Measurement(190L,"g","r",1,true,-.14,.85,.5,1300,null,0)))
        assertTrue((core.diagnostic("g")?.progress ?: 0.0)>0.0)
        core.step(270L,listOf(DiffV54Core.Measurement(270L,"g","r",2,true,-.08,.7,.3,1300,null,0)))
        assertEquals("A new owner must begin with no inherited kinematics",
            0.0,core.diagnostic("g")!!.progress,1e-8)
        assertEquals(0,core.pendingPortalCount)
    }
}
