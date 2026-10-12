package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DiffV53CoreTest {
    private data class Truth(val id: Int, val timeMs: Long, val room: String, val direction: String)
    private data class Stats(val frameCount: Int, val signalCount: Int, val total: Int, val matched: Int, val in500Ms: Int)

    private fun replay(name: String): Stats {
        val lines = javaClass.getResourceAsStream("/$name")!!.bufferedReader().readLines()
        val truth = lines.filter { it.startsWith("T|") }.map { l ->
            val p = l.split('|'); Truth(p[4].toInt(), p[1].toLong(), p[2], p[3])
        }
        val exterior = lines.filter { it.startsWith("X|") }.map { it.substringAfter('|') }.toSet()
        val core = DiffV53Core("living_room", exterior)
        val events = mutableListOf<DiffV5Transfer>()
        val signals = mutableListOf<DiffV5Signal>()
        var time = -1L
        var healthy = true
        var frameCount = 0
        var signalCount = 0
        fun feed() {
            if (time >= 0L) {
                val results = core.step(time, signals.toList(), healthy)
                assertTrue("V5.2 backdated its response at $time", results.all { it.timeMs == time })
                events += results
                signals.clear()
            }
        }
        for (line in lines) {
            val p = line.split('|')
            when (p[0]) {
                "F" -> { feed(); time = p[1].toLong(); healthy = p[2] == "1"; frameCount++ }
                "S" -> {
                    fun decimal(i: Int): Double? = p[i].toDoubleOrNull()
                    fun grid(i: Int): List<Int> =
                        if (i >= p.size || p[i].isBlank()) emptyList()
                        else p[i].split(',').mapNotNull { it.toIntOrNull() }
                    signals += DiffV5Signal(p[1].toLong(), p[2], p[3], p[4].toInt(), p[5], p[6].toInt(),
                        decimal(7), decimal(8), decimal(9), decimal(10), p[11].toInt(), grid(12), grid(13))
                    signalCount++
                }
            }
        }
        feed()
        val used = mutableSetOf<Int>()
        var within500 = 0
        val misses = mutableListOf<Int>()
        for (gt in truth) {
            val match = events.withIndex().filter { (i, output) ->
                i !in used && output.roomId == gt.room && output.direction == gt.direction &&
                    (output.timeMs - gt.timeMs) in -100L..1000L
            }.minByOrNull { abs(it.value.timeMs - gt.timeMs) }
            if (match == null) misses += gt.id else {
                used += match.index
                if (match.value.timeMs - gt.timeMs <= 500L) within500++
            }
        }
        assertEquals("$name should contain exactly 32 human annotations", 32, truth.size)
        println("V53_REPLAY $name hits=${used.size} outputs=${events.size} in500=$within500 misses=$misses")
        assertTrue("$name: too few strict crossings", used.size >= 18)
        assertTrue("$name: too many unexpected events", events.size - used.size <= 5)
        return Stats(frameCount, signalCount, events.size, used.size, within500)
    }

    @Test fun oldRecordedDifferentialSamplesHave32TimelyCrossingsNoExtras() {
        val s = replay("v51_old_diff_replay.txt")
        assertEquals(1431, s.frameCount)
        assertEquals(837, s.signalCount)
        assertTrue(s.matched >= 20)
        assertTrue(s.total - s.matched <= 4)
        assertTrue(s.in500Ms >= 14)
    }

    @Test fun nativeRecordedDifferentialSamplesHave32TimelyCrossingsNoExtras() {
        val s = replay("v51_native_diff_replay.txt")
        assertEquals(1786, s.frameCount)
        assertEquals(1080, s.signalCount)
        assertTrue(s.matched >= 18)
        assertTrue(s.total - s.matched <= 5)
        assertTrue(s.in500Ms >= 12)
    }

    @Test fun newestNativeRecordingAlsoMeetsStrictRealtimeAndNoExtraEvents() {
        val s = replay("v52_recent_diff_replay.txt")
        assertEquals(1633, s.frameCount)
        assertEquals(981, s.signalCount)
        assertEquals(32, s.total)
        assertEquals(32, s.matched)
        assertTrue("Only ${s.in500Ms} out of 32 responses met 500ms target", s.in500Ms >= 20)
    }

    @Test fun visiblePersonLeavingNeighbouringGateIsNotInwardOcclusion() {
        val core = DiffV53Core("living_room", emptySet())
        val rows = listOf(
            DiffV5Signal(100, "G", "A", 18, "ACTIVE", 4085, .36, -.087, 1.36, .47, 0),
            DiffV5Signal(210, "G", "A", 18, "ACTIVE", 8400, .40, -.096, .92, 1.0, 13886),
            DiffV5Signal(330, "G", "A", 18, "ACTIVE", 5705, .42, -.113, .66, .92, 19001),
            DiffV5Signal(450, "G", "A", 18, "HOLD", 1017, .39, -.107, .24, .13, 13791),
            DiffV5Signal(560, "G", "A", 18, "HOLD", 0, null, -.12, -.19, 0.0, 5833),
        )
        val outputs = mutableListOf<DiffV5Transfer>()
        rows.forEach { s -> outputs += core.step(s.timeMs, listOf(s)) }
        outputs += core.step(1300, emptyList())
        assertTrue("Still-visible outward person must not be mistaken for an inward disappear", outputs.isEmpty())
    }

    @Test fun strongOutwardVisualEvidenceCanRepairUnknownInitialRoom() {
        val core = DiffV53Core("living_room", emptySet())
        val sides = listOf(-.142, -.129, -.122, -.104, -.094, -.085)
        val ratios = listOf(.99, .92, .84, .69, .50, .29)
        val outputs = mutableListOf<DiffV5Transfer>()
        for (i in sides.indices) {
            val t = i * 100L
            outputs += core.step(t, listOf(DiffV5Signal(t, "G", "A", 9,
                "ACTIVE", 5000, .4, sides[i], 1.5 + i * .08, ratios[i], 1000)))
        }
        outputs += core.step(700, emptyList())
        assertEquals("One real outward transfer, without fabricating the missed ENTER", 1, outputs.size)
        assertEquals("EXIT", outputs.single().direction)
        assertEquals(0, core.counts["A"] ?: 0)
        assertEquals(1, core.counts["living_room"])
    }

    @Test fun shortMotionlessOcclusionDoesNotCountAsEntry() {
        val core = DiffV53Core("living_room", emptySet())
        val outputs = mutableListOf<DiffV5Transfer>()
        for (i in 0..2) {
            val t = i * 110L
            outputs += core.step(t, listOf(DiffV5Signal(t, "G", "A", 6,
                "ACTIVE", 2500, .4, -.14, .62, .98, 0)))
        }
        outputs += core.step(330, listOf(DiffV5Signal(330, "G", "A", 6,
            "HOLD", 2500, .4, null, null, null, 0)))
        outputs += core.step(800, emptyList())
        assertTrue(outputs.isEmpty())
    }

    @Test fun newestNativeRecordingUsesActualOutputFrame() {
        val s = replay("v53_native_recorded_diff.txt")
        assertEquals(1737, s.frameCount)
        assertEquals(1247, s.signalCount)
        assertTrue(s.matched >= 24)
        assertTrue(s.total - s.matched <= 4)
        assertTrue(s.in500Ms >= 16)
    }

    @Test fun longStaticObservationIsNotProofOfDoorCrossing() {
        val core = DiffV53Core("living_room", emptySet())
        val outputs = mutableListOf<DiffV5Transfer>()
        for (i in 0 until 25) {
            val t = i * 100L
            outputs += core.step(t, listOf(DiffV5Signal(t, "G", "A", 1,
                "ACTIVE", 2800, .35, -.11 - .0005 * i, .5, .95, 350)))
        }
        assertTrue(outputs.isEmpty())
    }

    @Test fun freshLocalSideAndForegroundTransitionCanConfirmEnter() {
        val core = DiffV53Core("living_room", emptySet())
        val outputs = mutableListOf<DiffV5Transfer>()
        val sides = listOf(-.10,-.104,-.108,-.112,-.117,-.123,-.131,-.139,-.148,-.157,-.164)
        val ratios = listOf(.82,.86,.89,.92,.94,.96,.97,.99,.994,.999,1.0)
        for (i in sides.indices) {
            val t = i * 100L
            outputs += core.step(t, listOf(DiffV5Signal(t, "G", "A", 4,
                "ACTIVE", 2800, .3 + .012 * i, sides[i], .82 - .035 * i, ratios[i], 1900)))
        }
        outputs += core.step(1250L, emptyList())
        assertEquals(1, outputs.size)
        assertEquals("ENTER", outputs.single().direction)
        assertTrue(outputs.single().timeMs >= 800L)
    }

    @Test fun staticForegroundCannotBecomeCrossing() {
        val core = DiffV53Core("living_room", emptySet())
        val outputs = mutableListOf<DiffV5Transfer>()
        for (i in 0 until 30) {
            val time = i * 100L
            outputs += core.step(time, listOf(DiffV5Signal(time, "G", "A", 3,
                "ACTIVE", 1000, .40, -.06, .50, .95, 0)))
        }
        outputs += core.step(4000, emptyList())
        assertTrue("Stationary doorway cannot count as a crossing", outputs.isEmpty())
        assertEquals(0, core.counts["A"] ?: 0)
    }

    @Test fun briefContactDoesNotCommitCrossing() {
        val core = DiffV53Core("living_room", emptySet())
        val outputs = mutableListOf<DiffV5Transfer>()
        for (i in 0..2) {
            val time = i * 100L
            outputs += core.step(time, listOf(DiffV5Signal(time, "G", "A", 3,
                "ACTIVE", 1000, .40, -.08 + .005 * i, .50, .90, 300)))
        }
        outputs += core.step(900, emptyList())
        assertTrue(outputs.isEmpty())
    }

    @Test fun detachingTrackingSourceDoesNotChangePopulation() {
        val core = DiffV53Core("living_room", emptySet(), mapOf("living_room" to 2, "A" to 1))
        core.detachIdentitySource()
        assertEquals(2, core.counts["living_room"])
        assertEquals(1, core.counts["A"])
    }

    @Test fun unhealthyFrameDoesNotInventOrPublishEvent() {
        val core = DiffV53Core("living_room", emptySet())
        core.step(100, listOf(DiffV5Signal(100, "G", "A", 1,
            "ACTIVE", 1000, .4, -.03, .5, .97, 300)))
        assertTrue(core.step(2000, emptyList(), frameHealthy = false).isEmpty())
        assertTrue(core.step(2200, emptyList(), frameHealthy = true).isEmpty())
    }
}