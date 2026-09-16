package com.example.roomxxx0102.logic.roomalgorithm.gate

import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateActivitySchedulerTest {
    private fun gate(id:String="g")=FlowGate(
        id=id, room="room-$id", a=FlowPoint(.4,.6), b=FlowPoint(.6,.6),
        aperture=listOf(FlowPoint(.4,.2),FlowPoint(.6,.2),FlowPoint(.6,.6),FlowPoint(.4,.6)),
        insideSign=1.0, aspect=16.0/9.0)

    private fun person(id:Int=1,bottom:Double=.61,score:Double=.7)=FlowDetection(
        id=id, box=FlowBox(.45,bottom-.18,.55,bottom), joints=emptyList(), score=score,
        locked=false, shielded=false)

    @Test fun farPersonKeepsGateOff() {
        val s=GateActivityScheduler(GateConfig(GateMethod.DIFFERENCE))
        val d=s.update(0,listOf(person(bottom=.80)),listOf(gate())).single()
        assertEquals(GateSensorPhase.OFF,d.phase)
        assertFalse(d.shouldProcessPixels)
    }

    @Test fun rawCandidateCanArmBeforeFormalLock() {
        val s=GateActivityScheduler(GateConfig(GateMethod.DIFFERENCE,armScore=.25,armDistanceScale=.30))
        val d=s.update(0,listOf(person(bottom=.64,score=.30)),listOf(gate())).single()
        assertEquals(GateSensorPhase.ARMED,d.phase)
        assertFalse(d.shouldProcessPixels)
        assertEquals(1,d.ownerTrack)
    }

    @Test fun thresholdContactStartsPixelWorkAndLossEntersHold() {
        val cfg=GateConfig(GateMethod.DIFFERENCE,holdMs=900)
        val s=GateActivityScheduler(cfg)
        val active=s.update(0,listOf(person(bottom=.61)),listOf(gate())).single()
        assertEquals(GateSensorPhase.ACTIVE,active.phase)
        assertTrue(active.shouldProcessPixels)
        val hold=s.update(400,emptyList(),listOf(gate())).single()
        assertEquals(GateSensorPhase.HOLD,hold.phase)
        assertTrue(hold.shouldProcessPixels)
        val off=s.update(1000,emptyList(),listOf(gate())).single()
        assertEquals(GateSensorPhase.OFF,off.phase)
        assertFalse(off.shouldProcessPixels)
    }

    @Test fun noMoreThanConfiguredCandidateGatesAreActivated() {
        val cfg=GateConfig(GateMethod.DIFFERENCE,maxActiveGates=2)
        val s=GateActivityScheduler(cfg)
        val gates=listOf(gate("a"),gate("b"),gate("c"))
        val decisions=s.update(0,listOf(person()),gates)
        assertEquals(2,decisions.count { it.phase==GateSensorPhase.ACTIVE })
        assertEquals(2,decisions.count { it.shouldProcessPixels })
    }
}
