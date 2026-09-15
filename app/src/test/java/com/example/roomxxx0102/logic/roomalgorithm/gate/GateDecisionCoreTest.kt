package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.junit.Assert.*
import org.junit.Test

class GateDecisionCoreTest {
    private val living=listOf(GP(0.0,0.5),GP(1.0,0.5),GP(1.0,1.0),GP(0.0,1.0))
    private val aperture=listOf(GP(0.4,0.5),GP(0.6,0.5),GP(0.6,0.10),GP(0.4,0.10))
    private val gate=Gate.create("door","bed",GP(0.4,0.5),GP(0.6,0.5),aperture,living,1.0)!!
    private fun core(initial:Map<String,Int> = emptyMap(),flow:Boolean=false)=GateDecisionCore("living",living,listOf(gate),listOf("living","bed"),1.0,GateParams(),initial,flow)
    private fun person(y:Double,id:Int=7,x:Double=0.5,locked:Boolean=true,score:Double=0.9):GDetection {
        val ys=listOf(.28,.285,.285,.28,.28,.23,.23,.19,.19,.15,.15,.13,.13,.07,.07,0.0,0.0)
        val joints=ys.mapIndexed { i,dy->GJoint(GP(x+if(i%2==0).018 else -.018,y-dy),.9) }
        return GDetection(id,GB(x-.05,y-.31,x+.05,y+.01),joints,score,locked,false)
    }
    private fun admit(c:GateDecisionCore,start:Long=0,id:Int=7):GateDecision {
        c.step(start,listOf(person(.68,id)));c.step(start+100,listOf(person(.66,id)))
        return c.step(start+200,listOf(person(.64,id)))
    }
    private fun contact(t:Long,restore:Boolean=false,flow:Boolean=false,gateId:String="door")=GateVisual(gateId,7,t,
        contact=!restore,backgroundReady=true,valid=true,ownedCells=6,visibleFraction=if(restore)0.0 else 1.0,
        restoredFraction=if(restore).96 else 0.0,backgroundRestored=restore,
        motion=if(flow)GP(0.0,-.01) else null,motionCells=if(flow)6 else 0,motionVerified=flow)
    private fun atDoor(c:GateDecisionCore,flow:Boolean=false) {
        admit(c)
        listOf(.60,.56,.52,.507).forEachIndexed { i,y -> val t=300L+i*100;c.step(t,listOf(person(y)),GateVisualBatch(t,listOf(contact(t,flow=flow)))) }
    }
    private fun disappear(c:GateDecisionCore,flow:Boolean=false):GateDecision {
        var d=c.snapshot()
        for(t in 700L..1100L step 100)d=c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true,flow))))
        return d
    }
    @Test fun confirmedHumanIsAdmittedWithoutAnyOpticalFlow() { val d=admit(core());assertEquals(1,d.counts["living"]) }
    @Test fun evenLockedStationaryCandidateDoesNotPassOnTimeAlone() { val c=core();for(t in 0L..2000 step 100)c.step(t,listOf(person(.7)));assertEquals(0,c.snapshot().counts["living"]) }
    @Test fun oneDetectionDoesNotCreatePerson() { assertEquals(0,core().step(0,listOf(person(.7))).counts["living"]) }
    @Test fun unlockedSofaDoesNotCreatePerson() { val c=core();for(t in 0L..1500 step 100)c.step(t,listOf(person(.7,locked=false)));assertEquals(0,c.snapshot().counts["living"]) }
    @Test fun lowConfidenceBodyDoesNotCreatePerson() { val c=core();for(t in 0L..1500 step 100)c.step(t,listOf(person(.7,score=.2)));assertEquals(0,c.snapshot().counts["living"]) }
    @Test fun invalidSkeletonDoesNotCreatePerson() { val c=core();for(t in 0L..1500 step 100)c.step(t,listOf(person(.7).copy(joints=emptyList())));assertEquals(0,c.snapshot().counts["living"]) }
    @Test fun duplicateFramesDoNotSatisfyAdmission() { val c=core();repeat(20){c.step(1,listOf(person(.7)))};assertEquals(0,c.snapshot().counts["living"]) }
    @Test fun frameGapDoesNotBridgeAnUnseenCrossing() { val c=core();admit(c);val d=c.step(1500,listOf(person(.45)));assertEquals(1,d.counts["living"]);assertTrue(d.events.isEmpty()) }
    @Test fun measuredEntryDoesNotRequireDisappearance() {
        val c=core();atDoor(c)
        c.step(700,listOf(person(.49)));c.step(800,listOf(person(.48)))
        val d=c.step(900,listOf(person(.47)));assertEquals(1,d.counts["bed"]);assertEquals(0,d.counts["living"]);assertEquals(1,d.events.size)
    }
    @Test fun stillVisibleInsideDoesNotCountAgain() {
        val c=core();atDoor(c);for(t in 700L..1700 step 100)c.step(t,listOf(person(.47)))
        assertEquals(1,c.snapshot().counts["bed"]);assertEquals(1,c.snapshot().counts.values.sum())
    }
    @Test fun measuredExitMovesOneSlot() {
        val c=core();atDoor(c);for(t in 700L..1100 step 100)c.step(t,listOf(person(.47)))
        c.step(1200,listOf(person(.48)));c.step(1300,listOf(person(.52)));c.step(1400,listOf(person(.54)))
        val d=c.step(1500,listOf(person(.56)));assertEquals(1,d.counts["living"]);assertEquals(0,d.counts["bed"])
    }
    @Test fun quickPeekAndReturnDoesNotCommit() {
        val c=core();atDoor(c);c.step(700,listOf(person(.49)));val d=c.step(760,listOf(person(.52)))
        assertEquals(1,d.counts["living"]);assertTrue(d.events.isEmpty())
    }
    @Test fun sofaOcclusionKeepsLivingCount() { val c=core();admit(c);for(t in 300L..3000 step 100)c.step(t,emptyList());assertEquals(1,c.snapshot().counts["living"]);assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun fakeTerminalEvidenceFarFromDoorCannotTransfer() {
        val c=core();admit(c);for(t in 300L..1200 step 100)c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true))))
        assertEquals(1,c.snapshot().counts["living"])
    }
    @Test fun disappearNearGateWithoutOwnedContactIsNotEntry() { val c=core();admit(c);c.step(300,listOf(person(.58)));c.step(400,listOf(person(.507)));disappear(c);assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun diffCanInferOnlyAfterContactAndBackgroundReturn() { val c=core();atDoor(c);val d=disappear(c);assertEquals(1,d.counts["bed"]) }
    @Test fun foregroundRemainingPreventsDisappearanceCommit() {
        val c=core();atDoor(c);for(t in 700L..1400 step 100)c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true).copy(visibleFraction=.4,backgroundRestored=false))))
        assertEquals(0,c.snapshot().counts["bed"])
    }
    @Test fun opticalModeRequiresVerifiedDirectionalFlow() { val c=core(flow=true);atDoor(c);disappear(c);assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun opticalModeAcceptsDirectionalFlowAndRestoration() { val c=core(flow=true);atDoor(c,true);disappear(c,true);assertEquals(1,c.snapshot().counts["bed"]) }
    @Test fun aStoppedPersonIsNotAbsent() { val c=core();atDoor(c);for(t in 700L..1500 step 100)c.step(t,listOf(person(.507)),GateVisualBatch(t,listOf(contact(t,true))));assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun failedInferenceIsNotEmptyDetection() { val c=core();atDoor(c);for(t in 700L..1200 step 100)c.step(t,null,GateVisualBatch(t,listOf(contact(t,true))));assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun roiOutsidePersonCannotProduceAbsence() { val c=core();atDoor(c);for(t in 700L..1200 step 100)c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true))),GB(.0,.0,.2,.3));assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun brokenImageInvalidatesVisualContacts() { val c=core();atDoor(c);c.step(700,emptyList(),GateVisualBatch(700,healthy=false));disappear(c);assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun incompleteBackgroundCannotProveDisappearance() { val c=core();atDoor(c);for(t in 700L..1200 step 100)c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true).copy(backgroundReady=false))));assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun oldRestorationCannotBeFinalizedByMissingEvidence() { val c=core();atDoor(c);for(t in 700L..900 step 100)c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true))));val d=c.step(1000,emptyList());assertEquals(0,d.counts["bed"]) }
    @Test fun conflictingVisualOwnerCannotTransfer() { val c=core();atDoor(c);for(t in 700L..1200 step 100)c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true).copy(ambiguous=true))));assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun restoredPixelCountWithoutGroundApproachCannotTransfer() {
        val c=core();admit(c);c.step(300,listOf(person(.60)));c.step(400,listOf(person(.52)))
        for(t in 500L..700 step 100)c.step(t,listOf(person(.55)),GateVisualBatch(t,listOf(contact(t))))
        for(t in 800L..1400 step 100)c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true))))
        assertEquals(0,c.snapshot().counts["bed"])
    }
    @Test fun reappearingNearSamePlaceKeepsSinglePerson() {
        val c=core();admit(c);c.step(300,emptyList());c.step(400,emptyList())
        c.step(500,listOf(person(.64,id=8)));assertEquals(1,c.snapshot().counts["living"])
    }
    @Test fun unknownNewIdFarAwayDoesNotDuplicateHiddenPerson() {
        val c=core();admit(c);c.step(300,emptyList());for(t in 600L..1200 step 100)c.step(t,listOf(person(.82-(t-600)*.00005,id=8,x=.8)))
        assertEquals(1,c.snapshot().counts.values.sum())
    }
    @Test fun acceptedPersonNotDeletedWhenConfidenceDrops() { val c=core();admit(c);for(t in 300L..1400 step 100)c.step(t,listOf(person(.64,locked=false,score=.2)));assertEquals(1,c.snapshot().counts["living"]) }
    @Test fun anonymousLivingInitialSlotIsReused() { val c=core(mapOf("living" to 1));admit(c);assertEquals(1,c.snapshot().counts["living"]) }
    @Test fun hiddenInitialRoomsStayCounted() { val c=core(mapOf("bed" to 2));admit(c);assertEquals(2,c.snapshot().counts["bed"]);assertEquals(3,c.snapshot().counts.values.sum()) }
    @Test fun identityBackendSwitchDoesNotClearCounts() { val c=core(mapOf("bed" to 2));admit(c);val d=c.detachIdentities();assertEquals(3,d.counts.values.sum());assertEquals(2,d.counts["bed"]) }
    @Test fun resetBackgroundDoesNotClearCounts() { val c=core();atDoor(c);c.invalidateVisual();assertEquals(1,c.snapshot().counts["living"]);disappear(c);assertEquals(0,c.snapshot().counts["bed"]) }
    @Test fun repeatedConfirmedInsideFramesDoNotRepeatEvent() { val c=core();atDoor(c);var n=0;for(t in 700L..2500 step 100)n+=c.step(t,listOf(person(.47))).events.size;assertEquals(1,n) }
    @Test fun imageMassDoesNotInventAHuman() { val c=core();for(t in 0L..1500 step 100)c.step(t,emptyList(),GateVisualBatch(t,listOf(contact(t,true))));assertEquals(0,c.snapshot().counts.values.sum()) }
    @Test fun newPersonWithUniqueExitOnsetReusesBedroomSlot() {
        val c=core(mapOf("bed" to 1))
        var d=c.snapshot()
        for(i in 0..5) { val t=i*100L;val v=GateVisual("door",7,t,valid=true,origin=true);d=c.step(t,listOf(person(.512+i*.02)),GateVisualBatch(t,listOf(v))) }
        assertEquals(1,d.counts.values.sum());assertEquals(1,d.counts["living"]);assertEquals(0,d.counts["bed"])
    }
    @Test fun finiteDoorRejectsCrossingItsExtension() { assertNull(gate.intersection(GP(.8,.55),GP(.8,.45))) }
    @Test fun finiteDoorAcceptsActualCrossing() { assertNotNull(gate.intersection(GP(.5,.55),GP(.5,.45))) }
    @Test fun gateNormalPointsTowardLiving() { assertTrue(gate.side(GP(.5,.7))>0);assertTrue(gate.side(GP(.5,.3))<0) }
    @Test fun reverseVertexOrderDoesNotReverseLivingSide() { val g=Gate.create("d","b",gate.b,gate.a,aperture,living,1.0)!!;assertTrue(g.side(GP(.5,.7))>0) }
    @Test fun zeroLengthGateIsInvalid() { assertNull(Gate.create("d","b",GP(.5,.5),GP(.5,.5),aperture,living,1.0)) }
    @Test fun badCalibrationIsNotGuessed() { assertNull(Gate.create("d","b",GP(.2,.1),GP(.8,.1),aperture,living,1.0)) }
    @Test fun sharedCornerRequiresMultipleGateHypotheses() {
        val ga=Gate.create("a","A",GP(.3,.5),GP(.5,.5),aperture,living,1.0)!!
        val gb=Gate.create("b","B",GP(.5,.5),GP(.7,.5),aperture,living,1.0)!!
        val c=GateDecisionCore("living",living,listOf(ga,gb),listOf("living","A","B"),1.0,GateParams())
        atDoor(c);for(t in 700L..1100 step 100)c.step(t,listOf(person(.47)))
        assertEquals(1,c.snapshot().counts["living"]);assertTrue(c.snapshot().people.any { it.possible.size>=3 })
    }
    @Test fun oneFootIsNotStrongCrossingEvidence() { val d=person(.6).copy(joints=person(.6).joints.mapIndexed { i,j->if(i==16)j.copy(confidence=0.0) else j });assertFalse(GroundEstimator(.6).measure(d,null)!!.measured) }
    @Test fun truncatedBoxIsNotNewGroundPoint() {
        val estimator=GroundEstimator(.6);repeat(4){estimator.measure(person(.7),null)}
        val d=person(.7).copy(box=GB(.45,.39,.55,.56),joints=person(.7).joints.mapIndexed { i,j->if(i>=13)j.copy(confidence=0.0) else j })
        assertNull(estimator.measure(d,null))
    }
    @Test fun roiClippedBottomIsNotStrongGround() { val d=person(.7);assertNull(GroundEstimator(.6).measure(d,GB(.0,.0,1.0,.71))) }
    @Test fun nanParametersAreSanitized() { val p=GateParams(restoredFraction=Double.NaN,backgroundRate=Double.POSITIVE_INFINITY).validated();assertEquals(.88,p.restoredFraction,.00001);assertEquals(.012,p.backgroundRate,.00001) }
    @Test fun parameterRangesBoundCost() { val p=GateParams(sampleHz=200,imageEdge=4000,maxPoints=9000,lkWindow=200).validated();assertEquals(30,p.sampleHz);assertEquals(960,p.imageEdge);assertEquals(640,p.maxPoints);assertEquals(31,p.lkWindow) }
    @Test fun methodsHaveDifferentStableIds() { assertEquals(3,GateMethod.entries.map { it.id }.distinct().size);assertNull(GateMethod.fromId("legacy")) }
    @Test fun roomCountsNeverBecomeNegative() { val c=core();atDoor(c);disappear(c);assertTrue(c.snapshot().counts.values.all { it>=0 }) }
    @Test fun observationsWithInvalidCoordinatesAreRejected() { val c=core();val d=person(.7).copy(box=GB(Double.NaN,.1,.5,.7));c.step(0,listOf(d));assertEquals(0,c.snapshot().counts.values.sum()) }
}
