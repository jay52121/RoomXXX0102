from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt"
text = path.read_text(encoding="utf-8")
marker = "loopPlayback = !isDiagnosticReplayActive"
if marker not in text:
    old = """        videoFeeder = VideoFeeder(this, textureView).apply {\n            this.yoloAnalyzer = this@MainActivity.yoloAnalyzer\n"""
    new = """        videoFeeder = VideoFeeder(this, textureView).apply {\n            // startVideoMode 会重建 VideoFeeder；诊断回放参数必须挂在新实例上。\n            loopPlayback = !isDiagnosticReplayActive\n            onPlaybackCompleted = if (isDiagnosticReplayActive) {\n                { runOnUiThread { finishDiagnosticReplay() } }\n            } else null\n            this.yoloAnalyzer = this@MainActivity.yoloAnalyzer\n"""
    if old not in text:
        raise RuntimeError("VideoFeeder construction anchor not found")
    text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")
print("diagnostic runtime replay patch applied")
