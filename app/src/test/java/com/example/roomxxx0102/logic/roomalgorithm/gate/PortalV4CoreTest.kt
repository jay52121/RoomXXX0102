package com.example.roomxxx0102.logic.roomalgorithm.gate

import com.example.roomxxx0102.logic.roomalgorithm.flow.*
import org.junit.Assert.*
import org.junit.Test

class PortalV4CoreTest {
    private val living = listOf(FlowPoint(0.0,.5),FlowPoint(1.0,.5),FlowPoint(1.0,1.0),FlowPoint(0.0,1.0))
    private fun gate(id:String="A",l:Double=.30,r:Double=.60)=FlowGate.create(
        id,id,FlowPoint(l,.5),FlowPoint(r,.5),
        listOf(FlowPoint(l,.5),FlowPoint(r,.5),FlowPoint(r,.05),FlowPoint(l,.05)),
        living,1.0,false,false
    )!!
    private fun core(gates:List<FlowGate> = listOf(gate()), initial:Map<String,Int> = emptyMap()) = PortalV4Core(
        "L",living,gates,listOf("L","A","B"),1.0,initial,
        PortalV4Policy(gapMs=300,contactScale=.10,admissionTravel=.012,waitClearMs=250)
    )
    private fun person(y:Double,id:Int=1,x:Double=.45,origin:String?=null)=FlowDetection(
        id,FlowBox(x-.07,y-.34,x+.07,y+.006),
        listOf(.30,.31,.31,.29,.29,.25,.25,.19,.19,.14,.14,.14,.14,.07,.07,0.0,0.0).mapIndexed { i,k ->
            FlowJoint(FlowPoint(x+if(i%2==0) .025 else -.025,y-k),.95)
        },.9,true,false,origin
    )
    private fun admitLiving(c:PortalV4Core) {
        c.step(0,listOf(person(.83)))
        c.step(100,listOf(person(.81)))
        val r=c.step(200,listOf(person(.79)))
        assertEquals(1,r.counts["L"])
    }
    private fun depth(t:Long,v:Double,gate:String="A",id:Int=1,pixels:Int=240)=PortalDepthEvidence(
        gate,id,t,(v-.10).coerceAtLeast(0.0),v,(v+.16).coerceAtMost(1.0),pixels,true
    )
    private fun body(t:Long,ratio:Double,along:Double=.5,gate:String="A",id:Int=1)=PortalBodyEvidence(
        gate,id,t,ratio,ratio,8,240,(240*ratio).toInt(),along,0.02,true
    )

    @Test fun strongFiniteGroundCrossingCommitsImmediately() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.62)))
        c.step(400,listOf(person(.53)))
        val r=c.step(500,listOf(person(.47)))
        assertEquals(1,r.events.size)
        assertEquals("L",r.events.single().from)
        assertEquals("A",r.events.single().to)
        assertFalse(r.events.single().inferred)
        assertEquals(1,r.counts["A"])
    }

    @Test fun waitClearBlocksDoorLineJitterFromDoubleCounting() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.53)))
        assertEquals(1,c.step(400,listOf(person(.47))).counts["A"])
        val ys=listOf(.505,.492,.507,.493,.508,.492,.51)
        ys.forEachIndexed { i,y ->
            val r=c.step(450+i*50L,listOf(person(y)))
            assertTrue(r.events.isEmpty())
            assertEquals(1,r.counts["A"])
            assertEquals(0,r.counts["L"])
        }
    }

    @Test fun depthMigrationCanEnterWhenFeetNeverCross() {
        val c=core();admitLiving(c)
        val values=listOf(.08,.20,.34,.50)
        var result:FlowDecision?=null
        values.forEachIndexed { i,v ->
            val t=300+i*60L
            result=c.step(t,listOf(person(.525)),depths=mapOf(1 to listOf(depth(t,v))))
        }
        val r=result!!
        assertEquals(1,r.events.size)
        assertTrue(r.events.single().inferred)
        assertEquals("A",r.events.single().to)
        assertEquals(1,r.counts["A"])
    }

    @Test fun stationaryPortalDepthDoesNotTransfer() {
        val c=core();admitLiving(c)
        repeat(8) { i ->
            val t=300+i*60L
            val r=c.step(t,listOf(person(.525)),depths=mapOf(1 to listOf(depth(t,.22))))
            assertTrue(r.events.isEmpty())
            assertEquals(1,r.counts["L"])
        }
    }

    @Test fun reverseTransferAllowedOnlyAfterPortalActuallyClears() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.53)))
        c.step(400,listOf(person(.47)))
        // Move deep enough that the portal is no longer occupied; WAIT_CLEAR must expire first.
        c.step(500,listOf(person(.32)))
        c.step(650,listOf(person(.30)))
        c.step(800,listOf(person(.30)))
        // New contact from the room side, then a real crossing back to living.
        c.step(900,listOf(person(.47)))
        val r=c.step(1000,listOf(person(.54)))
        assertEquals(1,r.events.size)
        assertEquals("A",r.events.single().from)
        assertEquals("L",r.events.single().to)
        assertEquals(1,r.counts["L"])
    }

    @Test fun originRoomSlotIsReusedAndDepthCanExit() {
        val c=core(initial=mapOf("A" to 1))
        c.step(0,listOf(person(.40,origin="A")),depths=mapOf(1 to listOf(depth(0,.72))))
        c.step(100,listOf(person(.43,origin="A")),depths=mapOf(1 to listOf(depth(100,.62))))
        c.step(200,listOf(person(.46,origin="A")),depths=mapOf(1 to listOf(depth(200,.50))))
        c.step(260,listOf(person(.48)),depths=mapOf(1 to listOf(depth(260,.34))))
        val r=c.step(340,listOf(person(.49)),depths=mapOf(1 to listOf(depth(340,.16))))
        assertEquals(1,r.events.size)
        assertEquals("A",r.events.single().from)
        assertEquals("L",r.events.single().to)
        assertEquals(1,r.counts.values.sum())
        assertEquals(1,r.counts["L"])
    }

    @Test fun sparseAnalysisGapKeepsGroundCrossingContinuity() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.53)))
        val r=c.step(800,listOf(person(.47)))
        assertEquals(1,r.events.size)
        assertEquals("A",r.events.single().to)
    }

    @Test fun failedInferenceHoldsEpisodeInsteadOfErasingHistory() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.53)))
        val held=c.step(650,null,frameHealthy=false)
        assertTrue(held.events.isEmpty())
        val r=c.step(900,listOf(person(.47)))
        assertEquals(1,r.events.size)
        assertEquals("A",r.events.single().to)
    }

    @Test fun wholeBodyAbsorptionCommitsOnlyAfterDisappearanceWitness() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.525)),bodies=mapOf(1 to listOf(body(300,.30,.42))))
        val peak=c.step(500,listOf(person(.525)),bodies=mapOf(1 to listOf(body(500,.88,.48))))
        assertTrue(peak.events.isEmpty())
        c.step(800,emptyList())
        val r=c.step(1450,emptyList())
        assertEquals(1,r.events.size)
        assertEquals("A",r.events.single().to)
        assertEquals(1,r.counts["A"])
        assertTrue(r.events.single().inferred)
    }

    @Test fun bodyThatTraversesAlongDoorIsPassByNotEntry() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.525)),bodies=mapOf(1 to listOf(body(300,.28,.18))))
        c.step(500,listOf(person(.525)),bodies=mapOf(1 to listOf(body(500,.86,.48))))
        val r=c.step(850,listOf(person(.525)),bodies=mapOf(1 to listOf(body(850,.30,.84))))
        assertTrue(r.events.isEmpty())
        assertEquals(1,r.counts["L"])
        assertEquals(0,r.counts["A"])
        assertTrue(r.notes.any{it.startsWith("PASS_BY:")})
    }

    @Test fun personVisibleBeyondApertureCancelsDeferredVisualEntry() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.525)),bodies=mapOf(1 to listOf(body(300,.25,.25))),depths=mapOf(1 to listOf(depth(300,.08))))
        c.step(500,listOf(person(.525)),bodies=mapOf(1 to listOf(body(500,.88,.50))),depths=mapOf(1 to listOf(depth(500,.32))))
        c.step(700,listOf(person(.525)),bodies=mapOf(1 to listOf(body(700,.86,.52))),depths=mapOf(1 to listOf(depth(700,.55))))
        // Still the same accepted person, now fully visible to the side of the aperture. No current
        // portal body evidence is present because the portal sensor may already have gone idle.
        val r=c.step(1400,listOf(person(.70,x=.84)))
        assertTrue(r.events.isEmpty())
        assertEquals(1,r.counts["L"])
        assertEquals(0,r.counts["A"])
        assertTrue(r.notes.any{it.startsWith("PASS_BY_VISIBLE:")})
    }
}
