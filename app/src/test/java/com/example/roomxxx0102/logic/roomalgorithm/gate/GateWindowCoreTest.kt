package com.example.roomxxx0102.logic.roomalgorithm.gate
import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import org.junit.Assert.*
import org.junit.Test

class GateWindowCoreTest {
    private val living=listOf(FlowPoint(0.0,.5),FlowPoint(1.0,.5),FlowPoint(1.0,1.0),FlowPoint(0.0,1.0))
    private fun gate(id:String="A",l:Double=.3,r:Double=.6)=FlowGate.create(id,id,FlowPoint(l,.5),FlowPoint(r,.5),
        listOf(FlowPoint(l,.5),FlowPoint(r,.5),FlowPoint(r,.05),FlowPoint(l,.05)),living,1.0,false,false)!!
    private fun engine(g:List<FlowGate> = listOf(gate()),initial:Map<String,Int> = emptyMap())=PortalV3Core("L",living,g,listOf("L","A","B"),1.0,initial,
        FlowCorePolicy(windowMode=true,gapMs=300,contactScale=.1,confirmMs=120))
    private fun person(y:Double,id:Int=1,x:Double=.45)=FlowDetection(id,FlowBox(x-.07,y-.34,x+.07,y+.006),
        listOf(.30,.31,.31,.29,.29,.25,.25,.19,.19,.14,.14,.14,.14,.07,.07,0.0,0.0).mapIndexed { i,k->
            FlowJoint(FlowPoint(x+if(i%2==0) .025 else -.025,y-k),.95)
        },.9,true,false)
    private fun seed(e:PortalV3Core,x:Double=.45) { e.step(0,listOf(person(.83,x=x)));e.step(100,listOf(person(.81,x=x)));e.step(200,listOf(person(.79,x=x))) }
    private fun contact(e:PortalV3Core,x:Double=.45) { seed(e,x);e.step(300,listOf(person(.67,x=x)));e.step(400,listOf(person(.56,x=x)));e.step(500,listOf(person(.501,x=x)));e.step(600,listOf(person(.501,x=x))) }
    private fun w(t:Long,gate:String="A",remaining:Int=0,known:Boolean=true,exclusive:Boolean=true,acquired:Boolean=true,hold:Long=300,cells:Int=6)=
        FlowWindowEvidence(gate,cells,100,remaining,hold,known,exclusive,acquired,t)
    private fun ev(vararg windows:FlowWindowEvidence)=mapOf(1 to FlowEvidence(1,windowEvidence=windows.toList()))
    private fun missing(e:PortalV3Core,window:(Long)->FlowWindowEvidence):FlowDecision {
        e.step(700,emptyList(),ev(window(700)));e.step(800,emptyList(),ev(window(800)));e.step(900,emptyList(),ev(window(900)))
        return e.step(1000,emptyList(),ev(window(1000)))
    }
    @Test fun directCrossingWorksForAllMeasurementMethods() {
        for(m in GateMethod.entries) {
            val e=engine();seed(e);e.step(300,listOf(person(.68)));e.step(400,listOf(person(.56)));e.step(500,listOf(person(.48)))
            assertTrue(e.step(600,listOf(person(.46))).events.isEmpty())
            val r=e.step(650,listOf(person(.45)))
            assertEquals(1,r.counts["A"]);assertFalse(r.events.single().inferred)
        }
    }
    @Test fun visibleRoomPersonIsNotAutomaticallyBackInLiving() {
        val e=engine();seed(e);e.step(300,listOf(person(.68)));e.step(400,listOf(person(.56)));e.step(500,listOf(person(.48)));e.step(650,listOf(person(.45)))
        repeat(10) { assertTrue(e.step(700+it*50L,listOf(person(.45))).events.isEmpty()) }
        e.step(1250,listOf(person(.53)));e.step(1350,listOf(person(.55)));val r=e.step(1400,listOf(person(.56)))
        assertEquals(1,r.counts["L"]);assertEquals(0,r.counts["A"])
    }
    @Test fun knownWindowClearAtRealGroundContactCanEnter() {
        val e=engine();contact(e);val r=missing(e){w(it)}
        assertEquals(1,r.counts["A"]);assertTrue(r.events.single().inferred)
    }
    @Test fun unknownBackgroundCannotConfirmDisappearance() {
        val e=engine();contact(e);val r=missing(e){w(it,known=false)}
        assertEquals(1,r.counts["L"]);assertTrue(r.events.isEmpty())
    }
    @Test fun personStoppingInWindowDoesNotEnterFromDifferenceZero() {
        val e=engine();contact(e);val r=missing(e){w(it,remaining=80)}
        assertEquals(1,r.counts["L"]);assertTrue(r.events.isEmpty())
    }
    @Test fun momentaryEmptyWindowCannotConfirm() {
        val e=engine();contact(e);val r=missing(e){w(it,hold=50)}
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun unownedDoorMovementDoesNotCount() {
        val e=engine();contact(e);assertTrue(missing(e){w(it,acquired=false)}.events.isEmpty())
    }
    @Test fun multiplePeoplePreventWindowCommit() {
        val e=engine();contact(e);assertTrue(missing(e){w(it,exclusive=false)}.events.isEmpty())
    }
    @Test fun insufficientSpatialCoverageCannotConfirm() {
        val e=engine();contact(e);assertTrue(missing(e){w(it,cells=1)}.events.isEmpty())
    }
    @Test fun furnitureLossWithoutGroundContactCannotBecomeEntry() {
        val e=engine();seed(e);e.step(300,emptyList(),ev(w(300)));e.step(400,emptyList(),ev(w(400)))
        val r=e.step(600,emptyList(),ev(w(600)))
        assertEquals(1,r.counts["L"]);assertTrue(r.events.isEmpty())
    }
    @Test fun mereImageOverlapDoesNotCountAsDoorContact() {
        val e=engine();seed(e);e.step(300,listOf(person(.70)));e.step(400,listOf(person(.63)));e.step(500,listOf(person(.61)))
        assertTrue(missing(e){w(it)}.events.isEmpty())
    }
    @Test fun staleWindowEvidenceIsRejected() {
        val e=engine();contact(e);assertTrue(missing(e){w(100)}.events.isEmpty())
    }
    @Test fun skippedLongGapDoesNotTransferRoom() {
        val e=engine();contact(e)
        val r=e.step(1300,emptyList(),ev(w(1300)))
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun rawLkPointDeathCannotBypassWindowSafety() {
        val e=engine();contact(e)
        val f=FlowEvidence(1,samples=(0..20).map { FlowSample(FlowPoint(.45,.3),null,it,10,true) },backgroundReturn=true)
        e.step(700,emptyList(),mapOf(1 to f));e.step(800,emptyList(),mapOf(1 to f));e.step(900,emptyList(),mapOf(1 to f))
        assertTrue(e.step(1000,emptyList(),mapOf(1 to f)).events.isEmpty())
    }
    @Test fun twoDistinctAperturesDoNotGuaranteeUniquePersonDoor() {
        val e=engine(listOf(gate("A",.3,.5),gate("B",.5,.7)));contact(e,x=.5)
        e.step(700,emptyList(),ev(w(700),w(700,"B")));e.step(800,emptyList(),ev(w(800),w(800,"B")));e.step(900,emptyList(),ev(w(900),w(900,"B")))
        val r=e.step(1000,emptyList(),ev(w(1000),w(1000,"B")))
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun staticSofaWithDriftingBoxFailsPixelAdmission() {
        val e=engine();var r=e.step(0,listOf(person(.83)),mapOf(1 to FlowEvidence(1,pixelChange=0.0)))
        repeat(12) { r=e.step(100+it*50L,listOf(person(.82-it*.004)),mapOf(1 to FlowEvidence(1,pixelChange=0.0))) }
        assertEquals(0,r.counts.values.sum())
    }
    @Test fun changingPixelsAndReliableMovingPoseCanBeAdmitted() {
        val e=engine();e.step(0,listOf(person(.83)),mapOf(1 to FlowEvidence(1,pixelChange=.2)))
        e.step(100,listOf(person(.81)),mapOf(1 to FlowEvidence(1,pixelChange=.2)))
        assertEquals(1,e.step(200,listOf(person(.79)),mapOf(1 to FlowEvidence(1,pixelChange=.2))).counts["L"])
    }
    @Test fun directionReversalCancelsPendingCrossing() {
        val e=engine();contact(e);e.step(700,listOf(person(.48)));val r=e.step(750,listOf(person(.55)))
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun failedFrameDoesNotClearCounts() {
        val e=engine();contact(e);val r=e.step(700,emptyList(),ev(w(700)),frameHealthy=false)
        assertTrue(r.events.isEmpty());assertEquals(1,r.counts["L"])
    }
    @Test fun initialRoomSlotIsReusedOnVisibleExit() {
        val e=engine(initial=mapOf("A" to 2))
        e.step(0,listOf(person(.38)));e.step(100,listOf(person(.40)));e.step(200,listOf(person(.42)))
        e.step(300,listOf(person(.48)));e.step(400,listOf(person(.53)));val r=e.step(550,listOf(person(.56)))
        assertEquals(1,r.counts["A"]);assertEquals(1,r.counts["L"]);assertEquals(2,r.counts.values.sum())
    }
    @Test fun sourceBackendChangePreservesOccupancy() {
        val e=engine();contact(e);assertEquals(1,e.detachIdentitySource().counts["L"])
    }
    @Test fun unknownRoomDoesNotBecomeOutsideThroughTimeout() {
        val e=engine();seed(e)
        repeat(100) { e.step(250+it*50L,emptyList()) }
        assertEquals(1,e.step(5500,emptyList()).counts["L"])
    }
}
