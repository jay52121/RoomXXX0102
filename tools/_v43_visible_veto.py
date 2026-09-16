from pathlib import Path

core = Path('app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/PortalV4Core.kt')
s = core.read_text(encoding='utf-8')
old = '''        if(e.from==livingId&&currentBody!=null&&bodyScore!=null&&e.peakAbsorption>=policy.absorptionArmRatio){
            val peakAlong=e.peakAlong
            if(bodyScore<=policy.absorptionReleaseRatio&&peakAlong!=null&&abs(currentBody.centerAlong-peakAlong)>=policy.absorptionPassByAlong){
                p.episode=null;p.possible=emptySet();p.lastEvidence="BODY_PASS_BY";p.status="PASSED_PORTAL"
                notes+="PASS_BY:${p.number}:${e.gate.id}"
                return
            }
        }

        if (depthCommits(e)) {'''
new = '''        if(e.from==livingId&&currentBody!=null&&bodyScore!=null&&e.peakAbsorption>=policy.absorptionArmRatio){
            val peakAlong=e.peakAlong
            if(bodyScore<=policy.absorptionReleaseRatio&&peakAlong!=null&&abs(currentBody.centerAlong-peakAlong)>=policy.absorptionPassByAlong){
                p.episode=null;p.possible=emptySet();p.lastEvidence="BODY_PASS_BY";p.status="PASSED_PORTAL"
                notes+="PASS_BY:${p.number}:${e.gate.id}"
                return
            }
        }
        // If the person was strongly absorbed by the aperture but is now clearly visible again with
        // the whole detection box outside that aperture, the earlier visual evidence was a pass-by.
        // This is intentionally a veto only: bbox geometry is never allowed to prove a transfer.
        if(e.from==livingId&&e.peakAbsorption>=policy.absorptionArmRatio&&p.track in observedTracks&&
            currentBody==null&&!boxOverlapsAperture(e.gate,p.box)){
            p.episode=null;p.possible=emptySet();p.lastEvidence="BODY_PASS_BY_VISIBLE";p.status="PASSED_PORTAL"
            notes+="PASS_BY_VISIBLE:${p.number}:${e.gate.id}"
            return
        }

        if (depthCommits(e)) {'''
if old not in s:
    raise SystemExit('core pass-by insertion point not found')
s = s.replace(old, new, 1)
old = '''        if(e.from==livingId&&e.visualReadyAt>=0&&t-e.visualReadyAt>=policy.visualWitnessMs){
            val peakAlong=e.peakAlong
            val tangentialStable=currentBody==null||peakAlong==null||abs(currentBody.centerAlong-peakAlong)<policy.absorptionPassByAlong
            if(tangentialStable){
                commit(p,e,t,inferred=true,evidence="PORTAL_DEPTH_MIGRATION_WITNESSED")
                return
            }
        }'''
new = '''        if(e.from==livingId&&e.visualReadyAt>=0&&t-e.visualReadyAt>=policy.visualWitnessMs){
            val peakAlong=e.peakAlong
            val tangentialStable=currentBody==null||peakAlong==null||abs(currentBody.centerAlong-peakAlong)<policy.absorptionPassByAlong
            val visiblyAbsorbed=bodyScore!=null&&bodyScore>=policy.absorptionArmRatio
            val disappeared=p.detectorMissingSince>=0&&t-p.detectorMissingSince>=policy.visualWitnessMs
            if(tangentialStable&&(visiblyAbsorbed||disappeared)){
                commit(p,e,t,inferred=true,evidence="PORTAL_DEPTH_MIGRATION_WITNESSED")
                return
            }
        }'''
if old not in s:
    raise SystemExit('depth witness block not found')
s = s.replace(old, new, 1)
old = '''    private fun lowerBodyNearThreshold(g: FlowGate, box: FlowBox, p: Person): Boolean {
        val foot = box.foot
        return g.along(foot) in -0.25..1.25 && g.distance(foot) <= clearBand(p) * 1.15
    }

    private fun contactBand'''
new = '''    private fun lowerBodyNearThreshold(g: FlowGate, box: FlowBox, p: Person): Boolean {
        val foot = box.foot
        return g.along(foot) in -0.25..1.25 && g.distance(foot) <= clearBand(p) * 1.15
    }

    private fun boxOverlapsAperture(g: FlowGate, box: FlowBox): Boolean {
        if(g.aperture.isEmpty()) return false
        val l=g.aperture.minOf{it.x};val r=g.aperture.maxOf{it.x}
        val t=g.aperture.minOf{it.y};val b=g.aperture.maxOf{it.y}
        return box.right>l&&box.left<r&&box.bottom>t&&box.top<b
    }

    private fun contactBand'''
if old not in s:
    raise SystemExit('helper insertion point not found')
s = s.replace(old, new, 1)
core.write_text(s, encoding='utf-8')

test = Path('app/src/test/java/com/example/roomxxx0102/logic/roomalgorithm/gate/PortalV4CoreTest.kt')
t = test.read_text(encoding='utf-8')
anchor = '''    @Test fun bodyThatTraversesAlongDoorIsPassByNotEntry() {
        val c=core();admitLiving(c)
        c.step(300,listOf(person(.525)),bodies=mapOf(1 to listOf(body(300,.28,.18))))
        c.step(500,listOf(person(.525)),bodies=mapOf(1 to listOf(body(500,.86,.48))))
        val r=c.step(850,listOf(person(.525)),bodies=mapOf(1 to listOf(body(850,.30,.84))))
        assertTrue(r.events.isEmpty())
        assertEquals(1,r.counts["L"])
        assertEquals(0,r.counts["A"])
        assertTrue(r.notes.any{it.startsWith("PASS_BY:")})
    }
}'''
replacement = '''    @Test fun bodyThatTraversesAlongDoorIsPassByNotEntry() {
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
}'''
if anchor not in t:
    raise SystemExit('test anchor not found')
test.write_text(t.replace(anchor,replacement,1),encoding='utf-8')

print('visible pass-by veto applied')
