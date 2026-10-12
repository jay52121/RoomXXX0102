package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DiffV54CoreTest {
    private fun runMotion(
        dir: Int, period: Int, missingFrames: Set<Int> = emptySet(),
        foregroundGain: Double = 1.0, poseJitter: Double = 0.0,
        optical: Boolean = true,
    ): List<DiffV54Core.Crossing> {
        val core = DiffV54Core("living", confirmMs = 120)
        val emitted = mutableListOf<DiffV54Core.Crossing>()
        var previousSide: Double? = null
        for (t in 0L..2200L step period.toLong()) {
            val frame = (t / period).toInt()
            if (frame in missingFrames) continue
            val scaled = t / 90.0
            val cleanSide = if (dir < 0) -.11 - .0037 * scaled else -.19 + .0037 * scaled
            val side = cleanSide + (if (frame % 2 == 0) poseJitter else -poseJitter)
            val absorption = if (dir < 0) (.60 + .023 * scaled).coerceAtMost(1.0)
                else (1.0 - .026 * scaled).coerceAtLeast(.05)
            val signedMotion = previousSide?.let { side - it }
            previousSide = side
            val measure = DiffV54Core.Measurement(t, "gate", "room", 42, true, side,
                absorption, .55, (2000 * foregroundGain).toInt(),
                if (optical) signedMotion else null, if (optical) 24 else 0)
            emitted += core.step(t, listOf(measure))
        }
        emitted += core.step(2500L, emptyList())
        return emitted
    }

    @Test fun physicalMotionIsStableAgainstFrameDropsAndDifferenceAmplitude() {
        for (dir in listOf(-1, 1)) {
            val expected = if (dir < 0) "ENTER" else "EXIT"
            for (period in listOf(75, 90, 100, 120, 150)) {
                val reference = runMotion(dir, period)
                assertEquals("A complete motion pulse should yield exactly one event", 1, reference.size)
                assertEquals(expected, reference.single().direction)
                val crossingT = if (dir < 0) 1550L else 650L
                assertTrue(reference.single().timeMs - crossingT in -100L..500L)
                for (gain in listOf(.15, 1.0, 20.0)) {
                    val missing = setOf(3, 6, 9)
                    val current = runMotion(dir, period, missing, gain, .001)
                    assertEquals("Dropouts/noise/gain must not change number of events", 1, current.size)
                    assertEquals(expected, current.single().direction)
                    assertTrue("Event time drift > 300ms", abs(current.single().timeMs - reference.single().timeMs) <= 300)
                }
            }
        }
    }

    @Test fun noOpticalMotionDoesNotCreateAnInventedCrossing() {
        assertTrue(runMotion(-1, 100, optical = false).isEmpty())
        assertTrue(runMotion(+1, 100, optical = false).isEmpty())
    }

    @Test fun stationaryPersonIsNotAnEnterOrExit() {
        val core = DiffV54Core("living")
        val outputs = mutableListOf<DiffV54Core.Crossing>()
        for (i in 0..40) {
            val t = i * 90L
            outputs += core.step(t, listOf(DiffV54Core.Measurement(t,"g","r",1,true,
                -.13,.99,.5,14000,0.0,24)))
        }
        assertTrue(outputs.isEmpty())
        assertEquals(DiffV54Core.Phase.WATCH, core.diagnostic("g")?.phase)
    }

    @Test fun approachingThenRetreatingCannotBeCountedAsExit() {
        val core=DiffV54Core("living")
        var prev:Double?=null
        val events=mutableListOf<DiffV54Core.Crossing>()
        for (i in 0..24) {
            val t=i*90L
            val p=if (i<=9) i else 18-i
            val side=-.12-.0025*p
            val ratio=.70+.025*p
            val flow=prev?.let{side-it}
            prev=side
            events+=core.step(t,listOf(DiffV54Core.Measurement(t,"door","room",4,
                true,side,ratio,.6,2500,flow,24)))
        }
        assertTrue("Reversal of unfinished approach must not create false EXIT", events.isEmpty())
        assertEquals(DiffV54Core.Phase.RETREAT,core.diagnostic("door")?.phase)
    }

    @Test fun roomLedgerIsNotAVisualGatekeeperAndIdentityRestartPreservesCounts() {
        val core=DiffV54Core("living",mapOf("room" to 0,"living" to 0))
        val measurements=mutableListOf<DiffV54Core.Crossing>()
        val src=runMotion(+1, 90)
        assertEquals("EXIT",src.single().direction)
        var lastSide:Double?=null
        for (t in 0L..2200L step 90L){
            val p=t/90.0;val side=-.19+.0037*p
            val flow=lastSide?.let{side-it};lastSide=side
            measurements+=core.step(t,listOf(DiffV54Core.Measurement(t,"gate","room",7,true,
                side,(1.0-.026*p).coerceAtLeast(.05),.55,1000,flow,24)))
        }
        assertEquals(1,measurements.size)
        assertEquals(0,core.counts["room"] ?: 0)
        assertEquals(1,core.counts["living"] ?: 0)
        core.detachIdentitySource()
        assertEquals(1,core.counts["living"] ?: 0)
        assertTrue(core.ownedRooms.isEmpty())
    }

    @Test fun unhealthyFrameCannotReleasePendingEvent() {
        val core=DiffV54Core("living")
        var prev:Double?=null
        val outs=mutableListOf<DiffV54Core.Crossing>()
        for(t in 0L..1800L step 100L){
            val p=t/90.0;val side=-.11-.0037*p;val ratio=(.60+.023*p).coerceAtMost(1.0)
            val flow=prev?.let{side-it};prev=side
            outs+=core.step(t,listOf(DiffV54Core.Measurement(t,"door","room",7,true,
                side,ratio,.5,1000,flow,24)),frameHealthy=t!=1600L)
        }
        assertTrue(outs.all{it.timeMs!=1600L})
    }

    @Test fun historicalDifferentialFilesCannotPretendToHaveOpticalEvidence() {
        // These four real recordings have no optical-normal displacement samples.
        // They are valid checks for non-crash and no ungrounded transfers, NOT
        // evidence that this newly added visual flow sensor reaches 32/32.
        for (resource in listOf("v51_old_diff_replay.txt", "v51_native_diff_replay.txt",
            "v52_recent_diff_replay.txt", "v53_native_recorded_diff.txt")) {
            val stream = javaClass.classLoader!!.getResourceAsStream(resource)
                ?: error("Missing existing real recording fixture $resource")
            val core=DiffV54Core("living_room")
            val out=mutableListOf<DiffV54Core.Crossing>()
            var t=-1L;var healthy=true
            val signals=mutableListOf<DiffV54Core.Measurement>()
            fun commit() { if(t>=0){out+=core.step(t,signals.toList(),healthy);signals.clear()} }
            stream.bufferedReader().useLines{lines -> lines.forEach{l ->
                val p=l.split('|')
                when(p[0]){
                    "F"->{ commit();t=p[1].toLong();healthy=p[2]=="1" }
                    "S"->{fun num(i:Int)=p.getOrNull(i)?.toDoubleOrNull()
                        signals+=DiffV54Core.Measurement(p[1].toLong(),p[2],p[3],
                            p[4].toInt(),p[5]=="ACTIVE",num(8),num(10),num(9),
                            p[11].toInt(),null,0)}
                }
            }}
            commit()
            assertTrue("Optical-less legacy recording should not hallucinate transfers: $resource",out.isEmpty())
        }
    }
}