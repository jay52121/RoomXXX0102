from pathlib import Path
p=Path('app/src/test/java/com/example/roomxxx0102/logic/roomalgorithm/gate/PortalV4CoreTest.kt')
s=p.read_text(encoding='utf-8')
old='''    private fun admitLiving(c:PortalV4Core) {'''
new='''    private fun upperOnlyPerson(y:Double,id:Int=1,x:Double=.45):FlowDetection {
        val d=person(y,id,x)
        return d.copy(joints=d.joints.mapIndexed { i,j -> if(i>=13) j.copy(score=.05) else j })
    }
    private fun admitLiving(c:PortalV4Core) {'''
if old not in s: raise SystemExit('helper anchor not found')
s=s.replace(old,new,1)
old='''        c.step(300,listOf(person(.525)),bodies=mapOf(1 to listOf(body(300,.25,.25))),depths=mapOf(1 to listOf(depth(300,.08))))
        c.step(500,listOf(person(.525)),bodies=mapOf(1 to listOf(body(500,.88,.50))),depths=mapOf(1 to listOf(depth(500,.32))))
        c.step(700,listOf(person(.525)),bodies=mapOf(1 to listOf(body(700,.86,.52))),depths=mapOf(1 to listOf(depth(700,.55))))
        // Still the same accepted person, now fully visible to the side of the aperture. No current
        // portal body evidence is present because the portal sensor may already have gone idle.
        // Move just fully beyond the aperture (left edge .61 > gate right .60) without
        // violating the tracker continuity guard for the same biological person.
        val r=c.step(1400,listOf(person(.70,x=.68)))'''
new='''        c.step(300,listOf(upperOnlyPerson(.525)),bodies=mapOf(1 to listOf(body(300,.25,.25))),depths=mapOf(1 to listOf(depth(300,.08))))
        c.step(500,listOf(upperOnlyPerson(.525)),bodies=mapOf(1 to listOf(body(500,.88,.50))),depths=mapOf(1 to listOf(depth(500,.32))))
        c.step(700,listOf(upperOnlyPerson(.525)),bodies=mapOf(1 to listOf(body(700,.86,.52))),depths=mapOf(1 to listOf(depth(700,.55))))
        // Keep portal evidence for one more sample so the old admitted ground estimate ages out;
        // knees/feet are intentionally unavailable, matching the dining-table occlusion case.
        c.step(800,listOf(upperOnlyPerson(.525)),bodies=mapOf(1 to listOf(body(800,.84,.53))),depths=mapOf(1 to listOf(depth(800,.55))))
        // Still the same accepted person, now fully visible to the side of the aperture. No current
        // portal body evidence is present because the portal sensor may already have gone idle.
        // Move just fully beyond the aperture (left edge .61 > gate right .60) without
        // violating the tracker continuity guard for the same biological person.
        val r=c.step(1400,listOf(upperOnlyPerson(.70,x=.68)))'''
if old not in s: raise SystemExit('scenario anchor not found')
s=s.replace(old,new,1)
p.write_text(s,encoding='utf-8')
print('no-ground visible veto test applied')
