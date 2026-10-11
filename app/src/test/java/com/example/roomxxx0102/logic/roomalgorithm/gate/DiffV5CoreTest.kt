package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DiffV5CoreTest {
    private data class Truth(val time: Long, val room: String, val direction: String)

    @Test fun recorded1431FramesMeet32HumanDoorTruthsWithoutExtraEvents() {
        val lines = javaClass.getResourceAsStream("/v5_diff_replay.txt")!!.bufferedReader().readLines()
        val exterior = lines.filter { it.startsWith("X|") }.map { it.substringAfter('|') }.toSet()
        val truth = lines.filter { it.startsWith("T|") }.map { line ->
            val p = line.split('|')
            Truth(p[1].toLong(), p[2], p[3])
        }
        val core = DiffV5Core("living_room", exterior)
        val outputs = mutableListOf<DiffV5Transfer>()
        val currentSignals = mutableListOf<DiffV5Signal>()
        var frameTime = -1L
        var frames = 0
        var signals = 0
        fun step() {
            if (frameTime >= 0) {
                outputs += core.step(frameTime, currentSignals.toList())
                currentSignals.clear()
            }
        }
        for (line in lines) {
            val p = line.split('|')
            when (p[0]) {
                "F" -> { step(); frameTime = p[1].toLong(); frames++ }
                "S" -> {
                    currentSignals += DiffV5Signal(p[1].toLong(),p[2],p[3],p[4].toInt(),p[5],p[6].toInt(),
                        p[7].toDoubleOrNull(),p[8].toDoubleOrNull(),p[9].toDoubleOrNull(),
                        p[10].toDoubleOrNull(),p[11].toInt())
                    signals++
                }
            }
        }
        step()
        // The recording has over six seconds after its last event: no retrospective EOF flush needed.
        assertEquals(1431, frames)
        assertEquals(837, signals)
        assertEquals(32, truth.size)
        val used = mutableSetOf<Int>()
        for (gt in truth) {
            val matched = outputs.withIndex().filter { (i, event) ->
                i !in used && event.roomId == gt.room && event.direction == gt.direction &&
                    abs(event.timeMs - gt.time) <= 1000L
            }.minByOrNull { abs(it.value.timeMs - gt.time) }
            assertNotNull("V5 failed ${gt.direction}:${gt.room}@${gt.time}",matched)
            used += matched!!.index
        }
        assertEquals("V5 emitted unmarked events",truth.size,outputs.size)
        assertEquals("V5 missed marked door events",truth.size,used.size)
        assertTrue("replay must be fully online, no final batch pass",core.pendingPortalCount == 0)
    }

    @Test fun staticDoorwayBodyDoesNotInventTransfer() {
        val core = DiffV5Core("L",emptySet())
        val emitted = mutableListOf<DiffV5Transfer>()
        for (i in 0..10) {
            val t = i*120L
            emitted += core.step(t,listOf(DiffV5Signal(t,"A#1","A",4,"ACTIVE",400,0.50,
                -0.06,0.52,0.95,200)))
        }
        emitted += core.step(2500,emptyList())
        assertTrue(emitted.isEmpty())
        assertEquals(0,core.counts["A"] ?: 0)
    }

    @Test fun unhealthyGapNeverPretendsVisualEvidenceReturned() {
        val core = DiffV5Core("L",emptySet())
        core.step(0,listOf(DiffV5Signal(0,"A#1","A",1,"ACTIVE",300,0.4,0.03,0.5,0.98,230)))
        assertEquals(1,core.pendingPortalCount)
        core.step(3000,emptyList(),frameHealthy=false)
        assertEquals(1,core.pendingPortalCount)
        core.step(3100,emptyList(),frameHealthy=true)
        assertEquals(0,core.pendingPortalCount)
    }

    @Test fun trackerSwitchKeepsExistingRoomCounts() {
        val core = DiffV5Core("L",setOf("D"),mapOf("L" to 2,"A" to 1))
        core.detachIdentitySource()
        assertEquals(2,core.counts["L"])
        assertEquals(1,core.counts["A"])
    }
}