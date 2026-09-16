from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]

history_path = root / "codexHistory.md"
history = history_path.read_text(encoding="utf-8")
entry = '''## [408] 2026-09-17 01:50:00 - V4-A 事件中心诊断回放录制器

**用户指令**：
> 干脆你直接来写录制器如何？你觉得可以的话就开始往 github 里面写，不可以的话告诉我原因

**实现方案**：

* **新增诊断回放入口**：调试面板新增“诊断回放”；点击后强制使用当前保存参数的 V4-A，从视频开头重置房间算法/追踪状态并单次完整播放，播放期间关闭自动房间切换暂停与智能匹配暂停，结束后自动恢复原设置。
* **事件中心而非全视频日志**：人工 ENTER/EXIT 事件只保留前 1.5 秒、后 2.5 秒的 V4 实际分析帧；算法孤立误报额外保留前后各 1.5 秒；其他无事件时间不落盘。
* **单 JSON 输出**：新增 `EventDiagnosticRecorder`，诊断结束自动生成一个 `V4A_事件诊断_*.json`；不保存原视频、Logcat、视频 SHA/尺寸、完整 17 点 Pose 或逐像素 Mask。
* **紧凑时空证据**：每帧保存人/Track、bbox、Ground 来源/强度/不确定度、门侧/门距/门宽投影、Portal scheduler、FSM、房间计数、算法输出；Motion 与 HumanOwned 仅保存像素数、p20/p50/p80 和 Portal 坐标系 8×4 占用网格（0..255）。
* **客观事件匹配**：新增一对一 `EventDiagnosticMatcher`，只输出 MATCH / MISS / WRONG_DIRECTION / DUPLICATE / FALSE_POSITIVE，不让待诊断算法自行判断“失败属于哪一层”。
* **人工真值边界**：现有 `EventMarkerManager` 只记录 ENTER/EXIT + 时间/帧，没有目标 Portal，因此 schema 明确写入 `portalTruthAvailable=false`，不把算法候选门冒充人工真值；文件仍保留实际参与竞争的 gate 和结构化门证据，后续可无缝扩展人工 Portal 标签。
* **低额外开销**：新增 `GateDiagnosticBus`；诊断关闭时不额外扫描 Mask，诊断开启时才从已有 V4 Motion/Owned Mat 生成紧凑统计。
* **自动结束修复**：`VideoFeeder` 支持诊断模式关闭循环并在真实播放末尾回调；二次运行时审计发现 `startVideoMode()` 会重建 `VideoFeeder`，因此又补充在新实例构造时继承诊断非循环与完成回调，避免真机循环不导出。
* **验证**：新增 `EventDiagnosticMatcherTest` 覆盖一对一匹配、重复输出、错方向和孤立误报；完整 `testDebugUnitTest assembleDebug` 与 Debug APK 构建通过。CI 额外用临时空 `kws-sdk/consumer-rules.pro` 绕过仓库现有库打包缺文件问题，该临时文件未进入正式提交。

---
'''
if "## [408] 2026-09-17 01:50:00 - V4-A 事件中心诊断回放录制器" not in history:
    if not history.startswith("# Codex History\n"):
        raise RuntimeError("Unexpected codexHistory header")
    history = "# Codex History\n\n" + entry + history[len("# Codex History\n\n"):]
    history_path.write_text(history, encoding="utf-8")

user_file = root / "tools/_dialogue_user_current.txt"
assistant_file = root / "tools/_dialogue_assistant_current.txt"
user_file.write_text("干脆你直接来写录制器如何？你觉得可以的话就开始往 github 里面写，不可以的话告诉我原因", encoding="utf-8")
assistant_file.write_text("可以，我直接写。这个功能和现有 V4 调试链路高度耦合，交给我做反而更合适：我会先找到你已经做好的“人工进出门标记”“调试面板”“回放重置/跳转”和 V4 每帧诊断对象，尽量直接复用，不另造一套旁路。第一版只做**事件中心 JSON 录制器**，不改进出门算法本身。", encoding="utf-8")

dialogue = (root / "dialogueHistory.md").read_text(encoding="utf-8")
if "事件中心诊断回放录制器开发启动" not in dialogue:
    subprocess.run([
        "python3", str(root / "tools/dialogue_archive.py"), "append-turn",
        "--user-file", str(user_file),
        "--assistant-file", str(assistant_file),
        "--title", "事件中心诊断回放录制器开发启动",
        "--time", "2026-09-17 01:50:00",
        "--history-file", str(root / "dialogueHistory.md"),
    ], check=True)
print("history finalized")
