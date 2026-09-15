package com.example.roomxxx0102.logic.roomalgorithm.flow

import org.junit.Assert.*
import org.junit.Test

class PortalV3CoreTest {
    private val living = listOf(FlowPoint(0.0, 0.5), FlowPoint(1.0, 0.5), FlowPoint(1.0, 1.0), FlowPoint(0.0, 1.0))
    private fun gate(id: String = "A", x0: Double = 0.3, x1: Double = 0.6) = FlowGate.create(id, id, FlowPoint(x0, 0.5), FlowPoint(x1, 0.5), listOf(FlowPoint(x0, 0.5), FlowPoint(x1, 0.5), FlowPoint(x1, 0.05), FlowPoint(x0, 0.05)), living, 1.0, false, false)!!
    private fun engine(gs: List<FlowGate> = listOf(gate())) = PortalV3Core("L", living, gs, listOf("L", "A", "B"), 1.0)
    private fun d(y: Double, id: Int = 1, x: Double = 0.45, locked: Boolean = true, confidence: Double = 0.95): FlowDetection {
        val box = FlowBox(x - 0.07, y - 0.34, x + 0.07, y + 0.006)
        val locations = listOf(0.30,0.31,0.31,0.29,0.29,0.25,0.25,0.19,0.19,0.14,0.14,0.14,0.14,0.07,0.07,0.0,0.0)
        val joints = locations.mapIndexed { i, offset -> FlowJoint(FlowPoint(x + if (i % 2 == 0) 0.025 else -0.025, y - offset), confidence) }.toMutableList()
        joints[15] = FlowJoint(FlowPoint(x - 0.025, y), confidence); joints[16] = FlowJoint(FlowPoint(x + 0.025, y), confidence)
        return FlowDetection(id, box, joints, 0.9, locked, false)
    }
    private fun seed(e: PortalV3Core) { e.step(0, listOf(d(0.83))); e.step(100, listOf(d(0.81))); e.step(200, listOf(d(0.79))) }
    private fun enter(e: PortalV3Core): FlowDecision {
        seed(e)
        e.step(300, listOf(d(0.69))); e.step(400, listOf(d(0.58))); e.step(500, listOf(d(0.53)))
        e.step(600, listOf(d(0.48)))
        return e.step(700, listOf(d(0.46)))
    }
    @Test fun visibleEntryDoesNotRequireDisappearance() {
        val e = engine(); val r = enter(e)
        assertEquals(0, r.counts["L"]); assertEquals(1, r.counts["A"]); assertEquals(1, r.events.size)
        assertFalse(r.events.single().inferred)
        assertEquals(1, e.step(800, listOf(d(0.44))).counts["A"])
    }
    @Test fun visibleExitIsNotDetectorReappearance() {
        val e = engine(); enter(e)
        assertTrue(e.step(800, listOf(d(0.44))).events.isEmpty())
        e.step(900, listOf(d(0.48))); e.step(1000, listOf(d(0.53)))
        val r = e.step(1100, listOf(d(0.56)))
        assertEquals(1, r.counts["L"]); assertEquals(0, r.counts["A"])
        assertEquals("A", r.events.single().from)
    }
    @Test fun furnitureOcclusionDoesNotDecrementLiving() {
        val e = engine(); seed(e)
        repeat(30) { e.step(300 + it * 100L, emptyList()) }
        assertEquals(1, e.step(3400, emptyList()).counts["L"])
    }
    @Test fun lossNearDoorAloneIsNotEntry() {
        val e = engine(); seed(e); e.step(300,listOf(d(0.67))); e.step(400,listOf(d(0.56))); e.step(500,listOf(d(0.501)))
        val r = e.step(900,emptyList())
        assertEquals(0,r.counts["A"]); assertEquals(1,r.counts["L"])
    }
    @Test fun noInferenceIsNotMissingDetection() {
        val e = engine(); seed(e)
        assertTrue(e.step(300, null).events.isEmpty())
        assertEquals(1, e.step(400, null).counts["L"])
    }
    @Test fun croppedRoiCannotDeclareAnUnexaminedPersonMissing() {
        val e = engine(); seed(e)
        val roi = FlowBox(0.0,0.0,0.15,1.0)
        repeat(10) { assertTrue(e.step(300 + it * 100L, emptyList(), coverage=roi).events.isEmpty()) }
    }
    @Test fun unconfirmedFalsePositiveNeverAddsAPerson() {
        val e = engine(); var r = e.step(0, listOf(d(0.8, locked=false)))
        repeat(20) { r=e.step(100+it*100L,listOf(d(0.8-it*0.005,locked=false))) }
        assertEquals(0,r.counts.values.sum())
    }
    @Test fun nonzeroLowConfidenceJointsAreNotAnIntegrityPass() {
        val e=engine(); var r=e.step(0,listOf(d(0.8,confidence=0.1)))
        repeat(10) { r=e.step(100+it*100L,listOf(d(0.8-it*0.01,confidence=0.1))) }
        assertEquals(0,r.counts.values.sum())
    }
    @Test fun staticImageWithJitteringDetectorDoesNotAddPerson() {
        val e=engine(); e.step(0,listOf(d(0.8)))
        val f=FlowEvidence(1,samples=(0..6).map { FlowSample(FlowPoint(.45,.3),FlowPoint(.45,.3),it,5) },reliable=true)
        var r=e.step(100,listOf(d(.78)),mapOf(1 to f))
        repeat(12) { r=e.step(200+it*100L,listOf(d(.78-it*.002)),mapOf(1 to f)) }
        assertEquals(0,r.counts.values.sum())
    }
    @Test fun halfBodyBoxCannotManufactureGroundCrossing() {
        val e=engine(); seed(e)
        val partial=d(.49).copy(joints=d(.49).joints.mapIndexed { i,k -> if(i>=13) k.copy(score=.01) else k },box=FlowBox(.38,.43,.52,.49))
        repeat(5) { assertTrue(e.step(300+it*100L,listOf(partial)).events.isEmpty()) }
        assertEquals(1,e.step(900,emptyList()).counts["L"])
    }
    @Test fun duplicateFrameCannotFinishPendingCrossing() {
        val e=engine(); seed(e);e.step(300,listOf(d(.66)));e.step(400,listOf(d(.55)));e.step(500,listOf(d(.48)))
        repeat(10) { assertTrue(e.step(500,listOf(d(.48))).events.isEmpty()) }
        assertEquals(1,e.step(600,listOf(d(.46))).counts["A"])
    }
    @Test fun probeAndReturnDoesNotCommit() {
        val e=engine();seed(e);e.step(300,listOf(d(.65)));e.step(400,listOf(d(.54)))
        e.step(500,listOf(d(.49)))
        val r=e.step(600,listOf(d(.55)))
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun sharedDoorEndpointRemainsAmbiguous() {
        val e=engine(listOf(gate("A",.3,.5),gate("B",.5,.7)))
        val ys=listOf(.83,.81,.79,.69,.58,.53,.48,.46)
        var r=e.step(0,listOf(d(ys[0],x=.5)))
        ys.drop(1).forEachIndexed { i,y -> r=e.step((i+1)*100L,listOf(d(y,x=.5))) }
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"]);assertEquals(1,r.upper["A"]);assertEquals(1,r.upper["B"])
    }
    @Test fun adjacentDoorInteriorChoosesOnlyTheCrossedSegment() {
        val e=engine(listOf(gate("A",.3,.5),gate("B",.5,.7)))
        val ys=listOf(.83,.81,.79,.69,.58,.53,.48,.46)
        var r=e.step(0,listOf(d(ys[0],x=.56)))
        ys.drop(1).forEachIndexed { i,y -> r=e.step((i+1)*100L,listOf(d(y,x=.56))) }
        assertEquals(1,r.counts["B"]);assertEquals(0,r.counts["A"])
    }
    @Test fun reappearingWithNewIdReusesHiddenLivingPerson() {
        val e=engine();seed(e);e.step(300,emptyList());e.step(400,emptyList())
        val r=e.step(500,listOf(d(.79,id=99)))
        assertEquals(1,r.counts["L"]);assertEquals(1,r.people.count { it.accepted })
    }
    @Test fun exitWithNewIdConsumesOneAnonymousRoomSlot() {
        val e=engine();enter(e);e.step(800,emptyList());e.step(900,emptyList())
        e.step(1000,listOf(d(.43,id=99)));e.step(1100,listOf(d(.45,id=99)));e.step(1200,listOf(d(.47,id=99)))
        e.step(1300,listOf(d(.53,id=99)));val r=e.step(1400,listOf(d(.56,id=99)))
        assertEquals(1,r.counts.values.sum());assertEquals(1,r.counts["L"]);assertEquals(0,r.counts["A"])
    }
    @Test fun invalidImageCannotProduceTransition() {
        val e=engine();seed(e)
        val r=e.step(300,listOf(d(.48)),frameHealthy=false)
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun frameGapCannotFabricateCrossing() {
        val e=engine();seed(e)
        val r=e.step(2500,listOf(d(.45)))
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun onePatchRepeatedManyTimesIsNotManyIndependentTerminalWitnesses() {
        val e=engine();seed(e);e.step(300,listOf(d(.67)));e.step(400,listOf(d(.56)));e.step(500,listOf(d(.501)))
        val f=FlowEvidence(1,samples=(0..300).map { FlowSample(FlowPoint(.45,.3),null,1,5,true) },backgroundReturn=true)
        e.step(600,emptyList(),mapOf(1 to f));val r=e.step(900,emptyList(),mapOf(1 to f))
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun orderedTerminalEvidenceAtActualThresholdCanCommit() {
        val e=engine();seed(e);e.step(300,listOf(d(.67)));e.step(400,listOf(d(.56)));e.step(500,listOf(d(.501)))
        val live=FlowEvidence(1,samples=(0..7).map { FlowSample(FlowPoint(.45,.3),FlowPoint(.45,.3),it,5) },reliable=true)
        e.step(600,listOf(d(.501)),mapOf(1 to live))
        val dead=FlowEvidence(1,samples=(0..7).map { FlowSample(FlowPoint(.45,.3),null,it,6,true) },backgroundReturn=true)
        e.step(700,emptyList(),mapOf(1 to dead));e.step(800,emptyList())
        val r=e.step(1000,emptyList())
        assertEquals(1,r.counts["A"]);assertTrue(r.events.single().inferred)
    }
    @Test fun concaveCornerWalkInsideLivingDoesNotBecomeRoomEntry() {
        val poly=listOf(FlowPoint(0.0,.5),FlowPoint(.5,.5),FlowPoint(.5,0.0),FlowPoint(1.0,0.0),FlowPoint(1.0,1.0),FlowPoint(0.0,1.0))
        val a=FlowGate.create("A","A",FlowPoint(.3,.5),FlowPoint(.5,.5),emptyList(),poly,1.0,false,false)!!
        val b=FlowGate.create("B","B",FlowPoint(.5,.5),FlowPoint(.5,.3),emptyList(),poly,1.0,false,false)!!
        val e=PortalV3Core("L",poly,listOf(a,b),listOf("L","A","B"),1.0)
        e.step(0,listOf(d(.65,x=.42)));e.step(100,listOf(d(.63,x=.43)));e.step(200,listOf(d(.61,x=.44)))
        e.step(300,listOf(d(.55,x=.45)));e.step(400,listOf(d(.49,x=.51)));val r=e.step(500,listOf(d(.45,x=.55)))
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun startingLivingCountIsNotAddedAgainOnDetection() {
        val e=PortalV3Core("L",living,listOf(gate()),listOf("L","A"),1.0,mapOf("L" to 1))
        seed(e)
        assertEquals(1,e.step(300,listOf(d(.77))).counts["L"])
    }
    @Test fun startingHiddenCountTransfersOneSlotOnExit() {
        val e=PortalV3Core("L",living,listOf(gate()),listOf("L","A"),1.0,mapOf("A" to 2))
        e.step(0,listOf(d(.38)));e.step(100,listOf(d(.40)));e.step(200,listOf(d(.42)))
        assertEquals(2,e.step(300,listOf(d(.45))).counts["A"])
        e.step(400,listOf(d(.53)));val r=e.step(500,listOf(d(.56)))
        assertEquals(1,r.counts["A"]);assertEquals(1,r.counts["L"]);assertEquals(2,r.counts.values.sum())
    }
    @Test fun reverseOriginEvidenceCanRecoverLateVisibleExit() {
        val e=PortalV3Core("L",living,listOf(gate()),listOf("L","A"),1.0,mapOf("A" to 1))
        e.step(0,listOf(d(.515).copy(originGate="A")))
        e.step(100,listOf(d(.53).copy(originGate="A")))
        e.step(200,listOf(d(.55).copy(originGate="A")))
        val r=e.step(300,listOf(d(.57)))
        assertEquals(0,r.counts["A"]);assertEquals(1,r.counts["L"])
        assertEquals("A",r.events.single().from)
    }
    @Test fun unrelatedApertureHintFarFromThresholdCannotDeductRoomCount() {
        val e=PortalV3Core("L",living,listOf(gate()),listOf("L","A"),1.0,mapOf("A" to 1))
        e.step(0,listOf(d(.83).copy(originGate="A")));e.step(100,listOf(d(.81)));val r=e.step(200,listOf(d(.79)))
        assertEquals(1,r.counts["A"]);assertTrue(r.events.isEmpty())
    }
    @Test fun initialCountsNeverNeedFakeTransitionEvents() {
        val e=PortalV3Core("L",living,listOf(gate()),listOf("L","A"),1.0,mapOf("L" to 1,"A" to 2))
        val r=e.step(0,emptyList());assertEquals(3,r.counts.values.sum());assertTrue(r.events.isEmpty())
    }
    @Test fun repeatedBeyondDoorObservationsDoNotDuplicateTransfers() {
        val e=engine();enter(e)
        repeat(20) { assertTrue(e.step(800+it*100L,listOf(d(.43))).events.isEmpty()) }
        assertEquals(1,e.step(2900,emptyList()).counts.values.sum())
    }
    @Test fun sameIdLargeTeleportDoesNotMoveItsLedgerSlot() {
        val e=engine();seed(e)
        val r=e.step(300,listOf(d(.43,x=.9)))
        assertEquals(1,r.counts["L"]);assertTrue(r.events.isEmpty())
    }
    @Test fun imageAvailableButFeatureBudgetExhaustedCannotAdmitFromBoxJitter() {
        val e=engine()
        e.step(0,listOf(d(.83)),mapOf(1 to FlowEvidence(1)))
        e.step(100,listOf(d(.81)),mapOf(1 to FlowEvidence(1)))
        assertEquals(0,e.step(200,listOf(d(.79)),mapOf(1 to FlowEvidence(1))).counts.values.sum())
    }


    @Test fun repeatedSubpixelJitterCannotAccumulateIntoHumanAdmission() {
        val e=engine()
        fun jitter(delta:Double)=FlowEvidence(1,delta=FlowPoint(delta,0.0),samples=(0..6).map { FlowSample(FlowPoint(.45,.3),FlowPoint(.45+delta,.3),it,5) },reliable=true)
        var r=e.step(0,listOf(d(.8)),mapOf(1 to jitter(0.0)))
        repeat(80) { i -> r=e.step(100+i*100L,listOf(d(.8)),mapOf(1 to jitter(if(i%2==0) .0008 else -.0008))) }
        assertEquals(0,r.counts.values.sum())
    }
    @Test fun currentlyVisibleRoomPersonCannotBeConsumedAsAnAnonymousSlot() {
        val e=engine()
        e.step(0,listOf(d(.40,id=1,x=.40)))
        e.step(100,listOf(d(.42,id=1,x=.40)))
        e.step(200,listOf(d(.44,id=1,x=.40)))
        e.step(600,listOf(d(.39,id=2,x=.55),d(.44,id=1,x=.40)))
        e.step(1000,listOf(d(.41,id=2,x=.55),d(.44,id=1,x=.40)))
        val r=e.step(1400,listOf(d(.43,id=2,x=.55),d(.44,id=1,x=.40)))
        assertEquals(2,r.counts["A"])
        assertEquals(2,r.people.count { it.accepted })
    }
}
