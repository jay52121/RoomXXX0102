from pathlib import Path
import subprocess

history_path = Path("codexHistory.md")
text = history_path.read_text(encoding="utf-8")
title = "SISP 纯结构化网页调试标注台与无匹配跳回"
if title not in text:
    entry = """## [413] 2026-10-10 - SISP 纯结构化网页调试标注台与无匹配跳回

**用户指令**：
> 暂停键什么的都要按上，而且空格=暂停，键盘左右键等于前进 5S,后退 5S。给开关：无匹配则弹跳回事件记录位置。然后应该方便的，可以去设置进出门之类的。完成设计后开始。

**实现方案**：

* **纯结构化电脑网页**：Android 新增开关式局域网 HTTP 调试服务与一次性六位配对码；浏览器读取同步的房间多边形、门线、原始 Pose 17 点、人体框、Track ID、算法状态与当前播放进度，不传 Bitmap、截图或视频。
* **播放与键盘**：网页提供从头播放、播放/暂停、固定前进/后退 5 秒、逐帧、时间轴拖动与事件导航；空格暂停/播放，左右方向键固定 ±5 秒，逗号/句号逐帧。
* **人机标注**：浏览器显示双轨人工/算法事件、匹配状态及偏差；可选人工事件、创建 ENTER/EXIT、修改事件方向、点击房门绑定/改绑/解绑、删除和撤销，实际写入现有 Android 人工事件文件。
* **无匹配跳回**：独立开关；人工事件匹配窗口超时，且事件后已有真正分析帧、无时间/方向/门正确匹配时自动跳回并暂停；每轮每条人工事件只触发一次，避免无限循环。与旧智能匹配暂停/切房间暂停互斥。
* **一致性与安全性**：每帧传真实 `timestampMs/frameSeq` 与原始坐标系宽高；视频或算法切换重置结构化缓存；只有局域网配对者可读写；网络回调统一调度主线程，现有房间判定算法不改。
* **质量验证**：新增匹配一对一/方向/房门/未观测/超窗单测及事件精确编辑/撤销测试；临时运行网页脚本语法检查、Android Kotlin 编译与单测，正式不保留云端 workflow，不生成 APK。

---

"""
    marker = "# Codex History\n\n"
    if marker not in text:
        raise RuntimeError("codexHistory.md header missing")
    history_path.write_text(text.replace(marker, marker + entry, 1), encoding="utf-8")

user = "暂停键什么的都要按上，而且空格=暂停，键盘左右键等于前进 5S,后退 5S。给开关：无匹配则弹跳回事件记录位置。然后应该方便的，可以去设置进出门之类的。完成设计后开始。"
assistant = "我会按“电脑网页是主操作台、手机只负责计算和执行”的方案直接开始实现。网页不传视频或截图，只同步地图、人体 Pose、时间轴、算法事件和匹配状态；播放、暂停、前后 5 秒、逐帧及房门标注都能在电脑操作。\n\n“无匹配则跳回”我先设计为：播放越过人工事件的匹配窗口仍无有效匹配时，自动跳回该事件并暂停，避免无限循环。接下来先核对仓库现有数据接口，再做可运行的第一版。"
Path("tools/_dialogue_user_current.txt").write_text(user, encoding="utf-8")
Path("tools/_dialogue_assistant_current.txt").write_text(assistant, encoding="utf-8")
subprocess.run([
    "python3", "tools/dialogue_archive.py", "append-turn",
    "--user-file", "tools/_dialogue_user_current.txt",
    "--assistant-file", "tools/_dialogue_assistant_current.txt",
    "--title", title,
    "--history-file", "dialogueHistory.md",
], check=True)
