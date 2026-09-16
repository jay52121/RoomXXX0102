from pathlib import Path

p = Path("app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/gate/PortalV4Core.kt")
text = p.read_text(encoding="utf-8")
old = '''        val bodyNear = p.track in observedTracks && lowerBodyOverlaps(w.gate, p.box)\n        val near = depthNear || groundNear || bodyNear'''
new = '''        // WAIT_CLEAR is a threshold lock, not an aperture lock. A long portal may extend deep\n        // into the sub-room, so bbox overlap with the whole aperture must not block a later exit.\n        val bodyNear = p.track in observedTracks && lowerBodyNearThreshold(w.gate, p.box, p)\n        val near = depthNear || groundNear || bodyNear'''
if old not in text:
    raise SystemExit("WAIT_CLEAR call anchor missing")
text = text.replace(old, new, 1)
old2 = '''    private fun lowerBodyOverlaps(g: FlowGate, box: FlowBox): Boolean {\n        val l = g.aperture.minOf { it.x }; val r = g.aperture.maxOf { it.x }\n        val top = g.aperture.minOf { it.y }; val bottom = g.aperture.maxOf { it.y }\n        val lowerTop = box.top + box.height * 0.55\n        return box.right > l && box.left < r && box.bottom > top && lowerTop < bottom\n    }'''
new2 = '''    private fun lowerBodyNearThreshold(g: FlowGate, box: FlowBox, p: Person): Boolean {\n        val foot = box.foot\n        return g.along(foot) in -0.25..1.25 && g.distance(foot) <= clearBand(p) * 1.15\n    }'''
if old2 not in text:
    raise SystemExit("WAIT_CLEAR helper anchor missing")
text = text.replace(old2, new2, 1)
p.write_text(text, encoding="utf-8")
print("Portal V4.2 WAIT_CLEAR threshold fix applied")
