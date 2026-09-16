from pathlib import Path

path = Path("codexHistory.md")
text = path.read_text(encoding="utf-8")
entry = '''## [407] 2026-09-16 23:44:00 - V4.2 Portal Episode Core 重写进出门状态机

**用户指令**：
> 开始开发吧

**实现方案**：

* **V4 脱离 V3 转换核心**：新增 `PortalV4Core`，`GateRoomAlgorithm` 不再把 V4 视觉证据交给 `PortalV3Core`；A/B/C 三套视觉继续共用现有 Event ROI 前端。
* **单门单事件状态机**：每个人一次只允许锁定一扇 Portal，状态为 CONTACT / TRANSITING / WAIT_CLEAR；一次 episode 最多提交一个 ENTER/EXIT，提交后必须清离门槛区域才允许反向新事件，从结构上阻断门线附近抖动导致的进出反复跳变。
* **脚点穿门直接提交**：若存在可靠强 Ground，历史源侧点到当前目标侧点真实穿过有限门底边，则立即提交 `GROUND_CROSSING`，不再沿用 V3 跨线后额外等待多帧 target-side 的 120ms 确认。
* **HumanOwned 门深度证据**：新增 `PortalDepthEvidence`，在高分辨率 HumanOwnedMask 上按门底到门内深处归一化为 0..1，统计 p20 / median / p80；脚点在门内消失时，可由连续深度迁移确认 `PORTAL_DEPTH_MIGRATION`，静止在门口不提交。
* **遮挡消失兜底**：客厅进入子房间时，只有先存在一定向内深度、再出现可信背景恢复，才允许 `PORTAL_DISAPPEARANCE`；普通视觉掉点不能单独计入房间。
* **真实多人独占**：修复生产路径 `FlowWindowEvidence.exclusive=true` 写死问题；现在根据同时与该门下半身区域重叠的候选人体数量计算真实 exclusivity。
* **清场防反跳**：首次 CI 暴露 WAIT_CLEAR 用“整条长 Portal bbox 重叠”会锁死反向出门；修正为只锁门槛附近的脚点/深度证据，人在房内走深后可正常解锁。
* **调试可见性**：调试面板/人体标签新增 Portal phase、IN/OUT、gateId、Depth、GroundSide 与提交证据（`GROUND_CROSSING` / `PORTAL_DEPTH_MIGRATION` / `PORTAL_DISAPPEARANCE` / `PORTAL_CLEARED`），便于真机逐帧核对状态机。
* **测试**：新增 `PortalV4CoreTest`，覆盖强脚点单门立即进入、门线抖动不重复计数、无脚点深度迁移进入、静止门区不误进、清场后才允许反向、子房间来源 slot 复用并出门、长帧间隔取消 episode。首轮 96 项中仅清场边界 1 项失败并据此修复；最终同一套 96 项单测全部通过，`:app:assembleDebug` 通过。
* **边界**：本轮没有实现开关门和开关灯的独立环境状态处理；保留 V4.1 已验证的逐像素视觉层，真实视频准确率仍需用最简单单人单门场景继续验证。

---
'''
marker = "# Codex History\n\n"
if marker not in text:
    raise SystemExit("codexHistory heading not found")
if "## [407]" in text:
    raise SystemExit("codexHistory [407] already exists")
path.write_text(text.replace(marker, marker + entry, 1), encoding="utf-8")
print("codexHistory [407] prepended")
