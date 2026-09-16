from pathlib import Path
p=Path('app/src/test/java/com/example/roomxxx0102/logic/roomalgorithm/gate/PortalV4CoreTest.kt')
s=p.read_text(encoding='utf-8')
old='''        val r=c.step(1400,listOf(person(.70,x=.84)))'''
new='''        // Move just fully beyond the aperture (left edge .61 > gate right .60) without
        // violating the tracker continuity guard for the same biological person.
        val r=c.step(1400,listOf(person(.70,x=.68)))'''
if old not in s: raise SystemExit('test line not found')
p.write_text(s.replace(old,new,1),encoding='utf-8')
print('test trajectory fixed')
