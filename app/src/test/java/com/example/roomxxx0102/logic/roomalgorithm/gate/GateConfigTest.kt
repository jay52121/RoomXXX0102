package com.example.roomxxx0102.logic.roomalgorithm.gate
import org.junit.Assert.*
import org.junit.Test

class GateConfigTest {
    @Test fun methodsHaveDistinctStableIds() { assertEquals(3,GateMethod.entries.map { it.id }.toSet().size) }
    @Test fun unknownMethodIsNotSilentlyMog2() { assertEquals(null,GateMethod.from("removed")) }
    @Test fun invalidConfigCannotRemoveComputeBounds() {
        val c=GateConfig(GateMethod.OPTICAL_FLOW,points=999999,visionEdge=9999,sampleMs=0,fbError=Double.NaN,
            clearRatio=Double.POSITIVE_INFINITY,maxActiveGates=99,historyMs=99,holdMs=99999,armScore=Double.NaN).checked()
        assertEquals(256,c.points);assertEquals(960,c.visionEdge);assertEquals(33,c.sampleMs)
        assertEquals(1.5,c.fbError,0.0);assertEquals(0.08,c.clearRatio,0.0)
        assertEquals(3,c.maxActiveGates);assertEquals(600,c.historyMs);assertEquals(1600,c.holdMs);assertEquals(.25,c.armScore,0.0)
    }
    @Test fun eventRoiDefaultsKeepOneSecondHistoryAndTwoGateCap() {
        val c=GateConfig(GateMethod.DIFFERENCE)
        assertEquals(1000,c.historyMs);assertEquals(900,c.holdMs);assertEquals(2,c.maxActiveGates)
        assertEquals(1920,c.captureEdge);assertEquals(128,GateConfig(GateMethod.OPTICAL_FLOW).points)
    }
    @Test fun onlyOneInferencePermitCanExist() {
        val p=GateSamplingPermit();assertTrue(p.acquire(100,50))
        repeat(100) { assertFalse(p.acquire(101+it.toLong(),50)) }
        p.release();assertTrue(p.acquire(250,50));p.release()
    }
    @Test fun skippedRequestsNeverBecomeWorkItems() {
        val p=GateSamplingPermit();assertTrue(p.acquire(100,50))
        repeat(50) { p.acquire(110+it.toLong(),50) }
        assertEquals(50L,p.skipped);p.release();assertFalse(p.isBusy())
        assertTrue(p.acquire(200,50))
    }
    @Test fun rateLimitAppliesAfterWorkerBecomesFree() {
        val p=GateSamplingPermit();assertTrue(p.acquire(0,50));p.release()
        assertFalse(p.acquire(49,50));assertTrue(p.acquire(50,50))
    }
    @Test fun stationaryForegroundIsNotDisappearance() {
        val e=GateClearEvidence()
        repeat(20) { assertEquals(0L,e.observe(it*50L,100,100,true,300,.08)) }
    }
    @Test fun vacatedPixelsWithForegroundElsewhereAreNotDisappearance() {
        val e=GateClearEvidence();e.observe(0,100,100,true,300,.08)
        repeat(10) { assertEquals(0L,e.observe(50+it*50L,100,0,true,300,.08)) }
    }
    @Test fun genuineClearNeedsAStableHold() {
        val e=GateClearEvidence();e.observe(0,100,100,true,300,.08)
        assertEquals(0L,e.observe(50,0,0,true,300,.08))
        assertEquals(100L,e.observe(150,0,0,true,300,.08))
        assertEquals(200L,e.observe(250,0,0,true,300,.08))
    }
    @Test fun gapRestartsClearHold() {
        val e=GateClearEvidence();e.observe(0,100,100,true,300,.08);e.observe(50,0,0,true,300,.08)
        assertEquals(0L,e.observe(500,0,0,true,300,.08))
    }
    @Test fun unreliableFrameCannotClearTheDoor() {
        val e=GateClearEvidence();e.observe(0,100,100,true,300,.08);e.observe(50,0,0,true,300,.08)
        assertEquals(0L,e.observe(250,0,0,false,300,.08));assertEquals(0L,e.observe(300,0,0,true,300,.08))
    }
    @Test fun emptyDoorWithoutOwnedReferenceDoesNotCreateAnEvent() {
        val e=GateClearEvidence();repeat(30) { assertEquals(0L,e.observe(it*50L,0,0,true,300,.08)) }
    }
    @Test fun duplicateAndReversedTimestampsCannotAccumulateClearTime() {
        val e=GateClearEvidence();e.observe(0,100,100,true,300,.08);e.observe(50,0,0,true,300,.08)
        repeat(10) { assertEquals(0L,e.observe(50,0,0,true,300,.08)) }
        assertEquals(0L,e.observe(40,0,0,true,300,.08))
    }
}
