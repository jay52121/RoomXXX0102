from pathlib import Path

path = Path('codexHistory.md')
text = path.read_text(encoding='utf-8')
entry = '''## [410] 2026-09-17 - 人工事件自动推断真实房门并可视化核对

**用户指令**：
> 你把这个跑一次，然后再写入到上面的提示信息里面去，我做一次验证就可以了。最后我去跑一次，然后上面不是有一个什么进出门的信息区吗？然后我就去看一下是不是对的信息区之前也有信息，你做一些区隔，用颜色写清楚是推断进出

**实现方案**：

* **独立门位推断**：人工 ENTER/EXIT 不再要求额外手工选择房门；诊断回放在人工事件时间点前后各 350ms 内，直接使用原始 YOLO Pose 人体框与所有静态 Portal 多边形计算精确交叠面积，取最高交叠门作为 `inferredPortal`。该过程不读取 V4 候选门、锁门、FSM、Ledger 或算法最终房间结果，避免循环验证。
* **几何交叠**：新增 `MarkedPortalTruthInference`，用 Sutherland-Hodgman 将 Portal 多边形裁剪到人体矩形，分别计算人体覆盖率、Portal 覆盖率及组合重叠分数；Portal 互不重叠时可直接用于定位人工事件对应门。
* **验证提示**：保留原有算法进出提示不变，新增独立青色/深青底 `【推断进出】` 二级提示条，显示事件序号、推断路线、Pose ID 和几何重叠分数，便于用户逐事件肉眼核对。
* **诊断 JSON 扩展**：schema 升级为 2；每个人工事件写入 `inferredPortal`（roomId、门名、poseId、score、personCoverage、portalCoverage、相对事件时间），汇总新增推断成功/缺失数量。验证阶段仍保持 `portalTruthAvailable=false`，不提前把自动推断结果作为硬 GT 参与 MATCH/WRONG_PORTAL 分类。
* **构建策略**：遵照用户要求，本轮没有运行 Gradle、Android 云编译或 APK 构建；仅执行精确源码补丁和静态标记核对，真机由用户本地编译后用同一视频验证。

---
'''
if '## [410]' not in text:
    anchor = '# Codex History\n\n'
    if anchor not in text:
        raise RuntimeError('codex history header missing')
    text = text.replace(anchor, anchor + entry, 1)
    path.write_text(text, encoding='utf-8')
print('codex history prepared')
