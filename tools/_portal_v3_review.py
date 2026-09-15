from pathlib import Path
import inspect


def refine():
    base = ROOT / 'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/flow'
    def edit(path, old, new):
        text = path.read_text(encoding='utf-8')
        assert text.count(old) == 1, (path, old)
        path.write_text(text.replace(old, new), encoding='utf-8', newline='\n')
    core = base / 'PortalV3Core.kt'
    edit(core, '        var motion = 0.0', '        var motion = 0.0\n        val motionTrace = ArrayDeque<Pair<Long, FlowPoint>>()')
    edit(core, '    private var events = mutableListOf<FlowEvent>()', '    private var events = mutableListOf<FlowEvent>()\n    private var observedTracks = emptySet<Int>()')
    edit(core, '        events = mutableListOf(); notes = mutableListOf()', '        events = mutableListOf(); notes = mutableListOf()\n        observedTracks = detections?.map { it.id }?.toSet() ?: emptySet()')
    edit(core, '                p.motion += flow.delta.distance(FlowPoint(0.0, 0.0), aspect)', '''                p.motion += flow.delta.distance(FlowPoint(0.0, 0.0), aspect)
                p.motionTrace.add(timeMs to flow.delta)
                while (p.motionTrace.isNotEmpty() && timeMs - p.motionTrace.first().first > 1500) p.motionTrace.removeFirst()''')
    edit(core, '        val motionOk = if (p.imageTested) p.motion >= 0.005 else groundMotion >= 0.012', '''        val recentMotion = p.motionTrace.filter { t - it.first <= 1500 }.map { it.second }
        val netMotion = FlowPoint(recentMotion.sumOf { it.x }, recentMotion.sumOf { it.y }).distance(FlowPoint(0.0,0.0),aspect)
        val travel = recentMotion.sumOf { it.distance(FlowPoint(0.0,0.0),aspect) }
        val motionOk = if (p.imageTested) netMotion >= 0.005 && netMotion >= travel * 0.45 else groundMotion >= 0.012''')
    edit(core, 'it.track != p.track && t - it.lastDetection > 250 && it.room == firstRoom', 'it.track != p.track && it.track !in observedTracks && t - it.lastDetection > 250 && it.room == firstRoom')
    edit(core, 'it.room == livingId && t - it.lastDetection > 450 }', 'it.room == livingId && it.track !in observedTracks && t - it.lastDetection > 450 }')
    settings = base / 'PortalV3Settings.kt'
    edit(settings, '        val fields=rooms.associate { r -> r.id to EditText(context).apply', '''        val fields=rooms.associate { r ->
            layout.addView(TextView(context).apply { text=r.name })
            r.id to EditText(context).apply''')
    edit(settings, 'r.boundaryPoints.map { PresencePoint(it.x.toDouble(),it.y.toDouble()) }', 'if (!r.isLivingBlindZone && r.boundaryPoints.size >= 3) r.boundaryPoints.map { PresencePoint(it.x.toDouble(),it.y.toDouble()) } else emptyList()')
    edit(base/'PortalV3Vision.kt', '            val cloud = clouds.getOrPut(d.id)', '            if (d.id !in clouds && clouds.size >= 4) continue\n            val cloud = clouds.getOrPut(d.id)')
    test = ROOT / 'app/src/test/java/com/example/roomxxx0102/logic/roomalgorithm/flow/PortalV3CoreTest.kt'
    text = test.read_text(encoding='utf-8')
    position = text.rfind('}')
    assert position > 0
    extra = '''
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
'''
    test.write_text(text[:position]+extra+text[position:],encoding='utf-8',newline='\n')


p=Path(__file__).with_name('_portal_v3_apply.py')
s=p.read_text(encoding='utf-8')
def replace(old,new):
    global s
    assert s.count(old)==1,old
    s=s.replace(old,new)
replace('write(ROOT/name, content)', 'write(ROOT/name, content.rstrip() + "\\n")')
replace("run(['git','diff','--check'])", inspect.getsource(refine)+"\nrefine()\nrun(['git','diff','--check'])")
replace("parts+[Path(__file__),", "parts+[ROOT/'tools/_portal_v3_review.py',Path(__file__),")
replace("allowed=set(payload['changed'])|", "allowed={'tools/_portal_v3_review.py'}|set(payload['changed'])|")
p.write_text(s,encoding='utf-8',newline='\n')
