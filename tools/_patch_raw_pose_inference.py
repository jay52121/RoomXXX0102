from pathlib import Path

# EventDiagnosticRecorder: inference uses raw Pose boxes, not V4 person output.
p = Path('app/src/main/java/com/example/roomxxx0102/logic/validation/EventDiagnosticRecorder.kt')
t = p.read_text(encoding='utf-8')
repls = [
    ('import android.content.Context\n', 'import android.content.Context\nimport com.example.roomxxx0102.data.model.PoseResult\n'),
    ('fun recordFrame(frame: GateDiagnosticFrame) {', 'fun recordFrame(frame: GateDiagnosticFrame, rawPoses: List<PoseResult>) {'),
    ('updatePortalInference(frame)\n', 'updatePortalInference(frame, rawPoses)\n'),
    ('private fun updatePortalInference(frame: GateDiagnosticFrame) {', 'private fun updatePortalInference(frame: GateDiagnosticFrame, rawPoses: List<PoseResult>) {'),
    ('MarkedPortalTruthInference.infer(frame.people, inferenceRegions, frame.timeMs)', 'MarkedPortalTruthInference.infer(rawPoses, inferenceRegions, frame.timeMs)'),
    (' ｜ 人#${inferred.person} ｜ 几何重叠=$scoreText', ' ｜ Pose#${inferred.poseId} ｜ 几何重叠=$scoreText'),
    ('.put("person", inferred.person)\n                    .put("track", inferred.track)', '.put("poseId", inferred.poseId)'),
    ('人工事件门位由人工时间点±${PORTAL_INFERENCE_WINDOW_MS}ms内的人体框×静态Portal几何独立推断；未使用V4候选门/锁门/FSM/输出。', '人工事件门位由人工时间点±${PORTAL_INFERENCE_WINDOW_MS}ms内的原始Pose人体框×静态Portal几何独立推断；未使用V4候选门/锁门/FSM/Ledger/输出。'),
]
for old, new in repls:
    if old not in t:
        if new in t:
            continue
        raise RuntimeError(f'EventDiagnostic anchor missing: {old[:100]!r}')
    t = t.replace(old, new, 1)
p.write_text(t, encoding='utf-8')

# MainActivity: pass the raw Pose list from the same analysis frame into the recorder.
p = Path('app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt')
t = p.read_text(encoding='utf-8')
old = 'GateDiagnosticBus.frameFor(frameSeq)?.let { diagnosticRecorder?.recordFrame(it) }'
new = 'GateDiagnosticBus.frameFor(frameSeq)?.let { diagnosticRecorder?.recordFrame(it, results) }'
if old in t:
    t = t.replace(old, new, 1)
elif new not in t:
    raise RuntimeError('MainActivity recordFrame anchor missing')
p.write_text(t, encoding='utf-8')

print('patched raw Pose inference source')
