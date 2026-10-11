from pathlib import Path
import subprocess

title = "结构化网页调试台第一版"
history = Path("codexHistory.md")
text = history.read_text(encoding="utf-8")
if title not in text:
    entry = """## [419] 2026-10-11 - 结构化网页调试台第一版

**用户指令**：
> 仔细思考详细设计一个个工具，映射最急基本的信息到 web（有必要的话客户端亦可以）中，包括人的当前 pose 点和整个地图，以及进度条和匹配关系等所有东西播放时关键信息就可以在电脑上看到并操作，相当于标记工具。
> 不要远程视频，只要结构化数据。空格=暂停，键盘左右键=前进/后退 5 秒，增加无匹配时跳回人工事件位置的开关。完成设计后开始，并收尾。

**实现方案**：

* **零图像链路**：新增 Android 本地按需启动的局域网 HTTP/WebSocket 服务与内置网页，不上传原视频、截图、Bitmap、音频或逐像素 Mask；一键复制带配对码的网页网址。
* **空间结构化可视化**：网页根据房间/门几何和 PoseResult 归一化坐标绘制完整地图、门线、人体框、17 点骨架、Track ID、得分与来源。V4 帧输出同步候选门、拒绝原因、调试细节、各 Track 方向/目标房间评分。
* **播放器与快捷键**：网页操控当前 Android 视频的播放/暂停、±5 秒跳转、逐帧、时间轴点击与跳到人工事件；空格与左右箭头快捷键固定按用户要求。
* **人工事件与匹配**：双轨时间线、人工/算法事件表；可新建 ENTER/EXIT、选中、跳转、点击房门直接绑定/覆盖/解绑、翻转方向、精确删除、导出结构化 JSON；自动按 ±1000ms、方向及人工门真值一对一匹配。
* **自动回跳保护**：可开关、默认关闭。只有确实完整观察人工事件前后窗口，并在窗口结束后为 MISS/WRONG_PORTAL/WRONG_DIRECTION 才一键暂停跳回；未观察全窗口与未到时间绝不触发。网页回跳开关开启时暂时屏蔽手机旧自动暂停以避免争用。
* **安全与性能**：无需新增三方服务端库；限私有局域网、一次性配对码、限两个会话、传输队列有界；只在网页连接时序列化 Pose，浏览器缓存收到的历史结构化帧。
* **验证与文档**：新增事件匹配测试与网页脚本语法检查，执行 Android Kotlin 编译和单元测试；不生成 APK，正式分支不保留临时 CI。使用方法和安全限制详见 docs/SISP_WEB_DEBUG_V1.md。

---

"""
    marker = "# Codex History\n\n"
    if marker not in text:
        raise RuntimeError("codexHistory header missing")
    history.write_text(text.replace(marker, marker + entry, 1), encoding="utf-8")

user = "那你把它收尾"
assistant = "我按上一轮确定的方向把它收尾成可用的第一版：**手机只输出结构化数据，电脑网页负责显示和标注**，绝不传视频或截图。网页会有播放/暂停、空格暂停、左右键 ±5 秒、Pose/房门地图、人工与算法双轨事件，以及“未匹配就跳回人工事件”的开关。我先检查仓库当前状态和现有接口，再补服务端、网页和必要的测试，最后合并到 `9月新房间判定算法`。"
Path("tools/_dialogue_user_current.txt").write_text(user, encoding="utf-8")
Path("tools/_dialogue_assistant_current.txt").write_text(assistant, encoding="utf-8")
dialogue = Path("dialogueHistory.md")
if title not in dialogue.read_text(encoding="utf-8"):
    subprocess.run([
        "python3", "tools/dialogue_archive.py", "append-turn",
        "--user-file", "tools/_dialogue_user_current.txt",
        "--assistant-file", "tools/_dialogue_assistant_current.txt",
        "--title", title,
        "--history-file", str(dialogue),
    ], check=True)
