from pathlib import Path
import subprocess

history = Path("codexHistory.md")
text = history.read_text(encoding="utf-8")
title = "优化人工事件房门绑定交互与真值可靠性"
if title not in text:
    entry = """## [412] 2026-10-09 - 优化人工事件房门绑定交互与真值可靠性

**用户指令**：
> 按照你的意见进行修改

**实现方案**：

* **明确选择对象**：新增常驻紧凑状态行，显示选中事件序号/总数、视频时间、进入/离开、当前绑定房间和未绑定数量；状态栏可点回对应时间。
* **连续标注导航**：支持上一个、下一个、下一个未绑定（末尾自动回绕），并支持取消当前选择；新增事件后直接进入该事件的绑定状态。
* **可撤销的房门真值**：支持清除已绑定房门，仍允许点击其他房门覆盖；仅在人工事件附近允许点击绑定，视频继续播放或离开回顾模式时解除选择。
* **更安全的点击**：房门绑定从按下即提交改为同一房门内抬手确认，移动手指取消；白色房间名称优先于较大多边形，盲区名称也可选。
* **统一调试信息面板**：面板 X 与侧边调试按钮共享 MainActivity 的显示状态，选择事件自动收起信息面板，不退出调试；再次按侧边按钮恢复信息面板，关闭/重新进入调试则默认展开，实时/看手模式仍可独立关闭。
* **隔离不同视频标注**：事件文件名加入完整视频键的 SHA-256 摘要，旧记录只在 videoKey 完全一致时安全迁移，避免同名视频互串标注。
* **验证**：新增独立单元测试覆盖改绑、清除、删除、切换视频与同名文件隔离；另执行一次针对性 Kotlin 编译和单测检查，不构建 APK，临时验证配置不进入正式提交。

---

"""
    marker = "# Codex History\n\n"
    if marker not in text:
        raise RuntimeError("codexHistory header missing")
    history.write_text(text.replace(marker, marker + entry, 1), encoding="utf-8")

user = "按照你的意见进行修改"
assistant = "我会直接改现有版本，不重做整套界面。重点是把“当前选中了哪个事件”变成明确可见、可取消的状态，并让选事件、收起信息面板、点击房门、撤销绑定形成一条顺畅的操作流程。先核对当前分支和源码，再在独立分支修改、验证、合并；仍以你本地编译为主。"
Path("tools/_dialogue_user_current.txt").write_text(user, encoding="utf-8")
Path("tools/_dialogue_assistant_current.txt").write_text(assistant, encoding="utf-8")
subprocess.run([
    "python3", "tools/dialogue_archive.py", "append-turn",
    "--user-file", "tools/_dialogue_user_current.txt",
    "--assistant-file", "tools/_dialogue_assistant_current.txt",
    "--title", title,
    "--history-file", "dialogueHistory.md",
], check=True)
