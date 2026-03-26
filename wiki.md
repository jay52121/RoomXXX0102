# RoomFlow Wiki

## 1. 目录
1.1 锁定条件 (Lock)
1.2 解锁条件 (Unlock)
1.3 ROI 更新触发规则
1.4 Presence 人数算法版本与可视房间进入判定
1.5 切换房间自动暂停开关
1.6 Presence 调试日志压缩
1.7 播放往复回跳（播放器卡死）排查

## 1.1 锁定条件 (Lock)
来源：SimpleTrackerEngine / RemoteByteTrackEngine 的 shouldLock 逻辑。

满足以下全部条件才会 Lock：
- 历史最高分 maxScore >= 0.6
- 当前分数 currentScore >= 0.5
- 运动通过 movementOk（最近 3 帧中心点两两距离均 >= mutualExclusionThreshold）
- 形体完整 integrityOk（上半身点数 >= 5 且脚踝低于上半身）
- 肩膀可信 shouldersTrusted（左右肩置信度 >= POSE_HIGH_CONFIDENCE_THRESHOLD）

## 1.2 解锁条件 (Unlock)
目前有 2 种解锁来源：
- 肩膀可信度连续不足 10 帧（lowShoulderFrames >= 10）
- Pose 静止：最近 4 帧中任意两帧关键点坐标在 1px 误差内完全相同

## 1.3 ROI 更新触发规则
ROI 是否更新不看中心差，而是看目标框顶边两点的位移：
- 记录目标框顶边左右两个点的历史位置（像素坐标）
- 计算顶边两点的位移，取较大者 topMove
- 当 topMove > deadZoneThreshold 时才更新 ROI
- deadZoneThreshold = 0.1 * modelInputWidth

## 1.4 Presence 人数算法版本与可视房间进入判定

### 1.4.1 版本管理
- 入口：`PresenceAlgorithmRegistry`
- 当前版本：
  - `V1.0.0(B03011413)`：原始规则
  - `V1.1.0(B03021639)`：可视房间进入改为 AC 评分
  - `V1.1.1(B03021717)`：每帧 AC + 掉锁容忍 + 可视区连续帧兜底同步
  - `V1.1.2(B03021736)`：主判定参数放宽（优先修门口事件）
  - `V1.1.3(B03021801)`：关闭可视区兜底同步（仅保留门口 AC 主判定）
  - `V1.1.4(B03021831)`：出子房间改为“源房间保留分低于阈值”才允许回客厅
  - `V1.1.5(B03021852)`：新增通用 polygon 恢复候选，解决状态卡在旧房间导致漏迁移
  - `V1.1.6(B03021957)`：门口距离阈值改为按人框高度动态计算
  - `V1.1.7(B03022014)`：门口接近度改为“0~0.1*身高线性衰减，超出即0”
  - `V1.1.8(B03022153)`：子房间离开改为“可视区外关键点综合得分”触发（并区分房间内消失）
  - `V1.1.9(B03022207)`：仅“可视子房间->客厅”使用新退出规则；非可视房间保持旧退出逻辑
  - `V1.2.0(B03022300)`：可视房间进入改为“门洞法向穿越”主判定（双肩+脚+全身框估计地面点）
  - `V1.2.1(B03022335)`：可视进入在 `emp=true` 基础上新增 `dps/scs` 门槛；参数微调为“进更稳、出更早”
  - `V1.2.2(B03022359)`：灰色人体直接跳过；并联调“进更晚、出更早”
  - `V1.2.3(B03030020)`：进出统一改为关键点置信度加权分，脚踝高权重
  - `V1.3.0(B03030116)`：切换改为唯一综合分（主体分+法向辅助分），进出同一标准
- 设置项：`AppSettings.presenceAlgorithmVersion`
- `AUTO_LATEST`：自动使用最新版本（当前为 `V1.3.0(B03030116)`）

### 1.4.2 V1.1 可视房间进入（Living -> 可视子房间）
定义：
- `doorProximityScore`：地面落点到候选门线的接近评分（由距离分段映射到 0~1）
- `groundPointConfidence`：地面落点可信度（由脚踝/双肩/锁定状态估计）
- `doorEvidenceScore = doorProximityScore * groundPointConfidence`
- `targetRoomContainmentRatio`：人框被目标可视房间 polygon 包含比例（网格采样近似）
- `switchConfidenceScore = wA * doorEvidenceScore + wC * targetRoomContainmentRatio`

触发规则：
- 仅 `CONFIRMED` 目标可触发
- 每帧都会对“当前房间可达的可视房间门线”计算切换置信分，不再依赖先命中目标房间 polygon
- 候选中取最高 `switchConfidenceScore`，若前两名分差过小则判定门线歧义（不触发）
- 正常模式：`switchConfidenceScore >= EnterThreshold` 且 `doorProximityScore >= A_min`
- 低置信模式（`groundPointConfidence < A_conf_low`）：`targetRoomContainmentRatio >= C_strict` 且 `doorProximityScore >= A_min_strict`
- 连续 `K` 帧满足后，确认进入事件
- 若目标短时掉出 `CONFIRMED`，允许在 `enterConfirmedGraceFrames` 窗口内继续累计进入判定
- 调试面板 `presence decision` 持续输出当前拒绝/等待/触发原因

### 1.4.7 Presence 指标定义表（调试面板字段）
- `doorDist`：地面落点到候选门线的最短距离（0~1归一化坐标）。
- `doorProximityScore`：`doorDist` 映射后的门口接近度（越接近门越大）。
- `groundPointConfidence`：地面落点可信度（脚踝可信/双肩可信/锁定状态综合）。
- `doorEvidenceScore`：门口证据分，`doorProximityScore * groundPointConfidence`。
- `targetRoomContainmentRatio`：目标房间归属得分（关键点置信度加权后，位于目标房间可视区内的得分占比）。
- `switchConfidenceScore`：最终切换置信分，`wA*doorEvidenceScore + wC*targetRoomContainmentRatio`。
- `switchThreshold`：当前版本用于判定切换的阈值（对应 `enterThreshold`）。
- `sourceRoomContainmentRatio`：源房间归属得分（关键点置信度加权后，位于源房间可视区内的得分占比）。
- `sourceRoomStayScore`：当前仍在源房间的保留分，`wA*doorEvidenceScore + wC*sourceRoomContainmentRatio`。
- `exitSourceRoomScoreThreshold`：出子房间到客厅时的离开阈值；仅当 `sourceRoomStayScore` 低于该值才允许切换。
- `sourceRoomOutsidePoseScore`：源房间外部得分，定义为 `1 - sourceRoomContainmentRatio`。
- `exitOutsidePoseScoreThreshold`：`sourceRoomOutsidePoseScore` 的触发阈值（超过才允许从子房间离开）。
- `poseAverageConfidence`：当前目标平均关键点置信度。
- `exitPoseMinConfidence`：最低平均关键点置信度阈值（低于该值视为“可能房间内消失”，不触发离开）。
- `dynamicNearDist`：按当前人框高度动态计算的“门口近距离阈值”（用于 `doorProximityScore` 映射）。

### 1.4.3 可视区兜底同步（VISIBLE_POLYGON_SYNC）
- 场景：门口评分未触发但实时位置已稳定进入目标可视房间
- 规则：
  - 若 `polygonRoomId` 连续命中同一可视房间 `N` 帧（默认3）
  - 且包含比例 `C >= visiblePolygonSyncMinContainment`（默认0.55）
  - 则触发 `VISIBLE_POLYGON_SYNC` 事件，更新 persistent 人数
- 目的：降低“门口遮挡/掉锁”导致的可视房间漏更新

### 1.4.4 V1.1 默认参数（PresenceEstimatorParams）
- `enterDoorD0 = 0.01`
- `enterDoorD1 = 0.05`
- `enterWeightA = 0.70`
- `enterWeightC = 0.30`
- `enterThreshold = 0.62`
- `enterConfirmFrames = 2`
- `enterAMin = 0.25`
- `enterLowConfThreshold = 0.30`
- `enterCStrict = 0.85`
- `enterAMinStrict = 0.15`
- `cContainmentGrid = 8`
- `enterConfirmedGraceFrames = 24`
- `visiblePolygonSyncFrames = 3`
- `visiblePolygonSyncMinContainment = 0.55`

### 1.4.5 V1.1.2 参数调整（主判定放宽）
- 目的：优先修复“人眼已进房间但门口事件未触发”
- 改动：
  - `enterDoorD0: 0.010 -> 0.015`
  - `enterDoorD1: 0.050 -> 0.080`
  - `enterThreshold: 0.620 -> 0.520`
- 说明：仅参数调整，不改判定结构；用于验证门口主路径是否能跟上人眼判断。

### 1.4.6 V1.1.3 调整（关闭可视区兜底）
- 目的：先纯看门口 AC 主判定，排除 `VISIBLE_POLYGON_SYNC` 对结果的干扰。
- 改动：
  - `visiblePolygonSyncFrames = 0`（关闭兜底同步分支）
  - 其余参数沿用 `V1.1.2`：
    - `enterDoorD0 = 0.015`
    - `enterDoorD1 = 0.080`
    - `enterThreshold = 0.520`
- 说明：这是对照版本，用于定位“厨房/客厅反复横跳”是否由兜底分支触发。

### 1.4.8 V1.1.4 调整（出房间用源房间保留分）
- 目的：修复“门口反复横跳”中 `子房间->客厅` 被过早触发的问题。
- 改动：
  - 保留 `客厅->子房间` 的原有 `switchConfidenceScore >= switchThreshold` 逻辑。
  - `子房间->客厅` 改为：`sourceRoomStayScore <= exitSourceRoomScoreThreshold` 且 `doorProximityScore >= enterAMin` 才允许切换。
  - 继续保持 `visiblePolygonSyncFrames = 0`（兜底关闭）。
- 默认参数：
  - `exitSourceRoomScoreThreshold = 0.40`

### 1.4.9 V1.1.5 调整（通用 polygon 恢复候选）
- 目的：当状态卡在旧房间，但当前目标已稳定落在另一个可视房间时，允许按通用规则恢复正确房间。
- 改动：
  - 在门候选之外，新增 `POLYGON_RECOVERY` 候选（不依赖门邻接，适用于任意可视房间）。
  - `POLYGON_RECOVERY` 触发条件：
    - `targetRoomContainmentRatio >= recoveryTargetContainmentMin`
    - `sourceRoomStayScore <= exitSourceRoomScoreThreshold`
    - 连续帧计数仍沿用 `enterConfirmFrames`
  - 调试字段 `mode` 会显示 `POLYGON_RECOVERY:*` 便于核对触发来源。
- 默认参数：
  - `recoveryTargetContainmentMin = 0.60`

### 1.4.10 V1.1.6 调整（门距阈值按身高动态）
- 目的：避免固定门距阈值在不同透视尺度下过宽/过窄导致进入过于激进。
- 改动：
  - `dynamicNearDist = personBoxHeight * enterDoorDynamicRatio`
  - `doorProximityScore` 映射改为动态阈值：
    - `doorDist = 0` 时满分 1
    - `doorDist >= dynamicNearDist` 时为 0
    - 区间内线性衰减：`1 - doorDist / dynamicNearDist`
  - 其余分数结构保持不变。
- 默认参数：
  - `enterDoorDynamicRatio = 0.10`

### 1.4.11 V1.1.7 调整（线性门距规则固化）
- 目的：与调试口径对齐，门口证据分严格符合“0.1*身高内有效，超过即0”。
- 规则：
  - `doorProximityScore = max(0, 1 - doorDist / (0.1 * personBoxHeight))`
  - `doorEvidenceScore = doorProximityScore * groundPointConfidence`
- 说明：不再使用 `d0/d1` 双阈值平台映射。

### 1.4.12 V1.1.8 调整（子房间离开改为可视区外关键点评分）
- 目的：修复“从可视子房间出来后人数不减”，并区分“走出房间”与“房间内消失”。
- 改动：
  - `子房间 -> 客厅` 不再使用 `sourceRoomStayScore` 作为主触发条件。
  - 改为要求：
    - `sourceRoomOutsidePoseScore >= exitOutsidePoseScoreThreshold`
    - `poseAverageConfidence >= exitPoseMinConfidence`
    - `doorProximityScore >= enterAMin`（保留门口约束）
  - 其中 `sourceRoomOutsidePoseScore` 按关键点置信度加权，不区分身体部位权重。
- 默认参数：
  - `exitOutsidePoseScoreThreshold = 0.12`
  - `exitPosePointMinConfidence = 0.05`
  - `exitPoseMinConfidence = 0.20`

### 1.4.13 V1.1.9 调整（可视/非可视退出逻辑分流）
- 目的：严格满足“只有可视房间使用新规则，非可视房间维持旧逻辑”。
- 改动：
  - `可视子房间 -> 客厅`：
    - 只用 `sourceRoomOutsidePoseScore` 与 `poseAverageConfidence` 判定离开
    - 不再被 `AMBIGUOUS_DOOR` 分支拦截
    - 不再要求 `doorProximityScore` 过线
  - `非可视房间（盲区等） -> 客厅`：
    - 保持旧判定：`sourceRoomStayScore <= exitSourceRoomScoreThreshold` 且 `doorProximityScore >= enterAMin`
  - 调试字段新增：`exitRule=VISIBLE_OUTSIDE_POSE|LEGACY`

### 1.4.14 V1.2.0 调整（可视房间进入改为门洞法向穿越）
- 目的：修复“经过门口但未进入门洞”仍被误判进房间。
- 改动：
  - 可视房间进入主判定不再依赖可视区重叠作为触发条件。
  - 每帧估计地面接触点 `G(t)`：
    - 稳态点 `G0`：由双肩中点 + 人框底中心推算
    - 脚踝高置信时，用脚点与 `G0` 融合（`enterGroundFootBlendStartConfidence`）
  - 在门局部坐标计算：
    - 法向推进 `doorAdvanceDelta = Δs`
    - 横向滑动 `doorLateralDelta = Δq`
    - 比值 `doorAdvanceLateralRatio = |Δs|/(|Δq|+eps)`
  - 进入通过条件（可视房间）：
    - `enterMotionPass=true`
    - 其中 `enterMotionPass` 要求：
      - nearDoor 已满足（由 `doorProximityScore>0`）
      - `doorAdvanceDelta >= enterNormalAdvanceMin`
      - `doorAdvanceLateralRatio >= enterLateralRatioMin`
  - 退出逻辑保持 `V1.1.9` 分流不变。
- 默认参数：
  - `enterMotionWindowFrames = 3`
  - `enterNormalAdvanceMin = 0.008`
  - `enterLateralRatioMin = 0.35`
  - `enterGroundFootBlendStartConfidence = 0.60`

### 1.4.15 V1.2.1 调整（仅用现有参数收敛）
- 目的：不新增参数，降低“进入太早、出来太晚”。
- 进入判定（可视房间）在 `emp=true` 基础上增加：
  - `dps >= enterAMin`
  - `scs >= enterThreshold`
- 参数微调（沿用现有参数）：
  - `enterDoorDynamicRatio: 0.10 -> 0.08`（进更稳）
  - `exitOutsidePoseScoreThreshold: 0.12 -> 0.10`（出更早）

### 1.4.16 V1.2.2 调整（灰色过滤 + 阈值联调）
- 目的：灰色/低质量姿态不参与切换，同时缓解“进入太早、退出太晚”。
- 规则改动：
  - 在可视房间切换评估入口增加硬门禁：
    - `poseAverageConfidence < 0.55` 时直接拒绝，输出 `SKIP_GRAY_POSE`
    - 不进入进入/退出评分与计数流程
- 参数联调（不新增参数）：
  - `enterThreshold: 0.52 -> 0.58`（进入更稳，减少提前进入）
  - `enterAMin: 0.25 -> 0.35`（进入必须更靠近门）
  - `exitOutsidePoseScoreThreshold: 0.10 -> 0.08`（可视房间退出更早）

### 1.4.17 V1.2.3 调整（进出统一关键点加权分）
- 目的：让进入/离开都依赖同一套“关键点置信度加权分”，减少“人框先进去但关键点尚未过门”造成的误判。
- 主改动：
  - 可视房间相关主判定（含 `POLYGON_RECOVERY`）由“人框网格包含比例”切换为“关键点加权归属得分”。
  - `sourceRoomOutsidePoseScore` 改为与源房间归属分互补：`1 - sourceRoomContainmentRatio`。
  - 可视区兜底同步（若开启）也改为关键点加权归属得分，不再看人框面积采样。
- 关键点权重（与置信度相乘）：
  - 脚踝（15/16）= 3.0（最高）
  - 膝盖（13/14）= 1.8
  - 髋部（11/12）= 1.4
  - 肩部（5/6）= 1.2
  - 其他点 = 1.0
- 说明：
  - `computeContainmentScore(人框网格)` 保留为兼容函数，但 V1.2.3 主路径不再使用该函数作为进出主证据。

### 1.4.18 V1.3.0 调整（唯一综合分标准）
- 目的：进房间/出房间统一到同一个综合分，避免规则分叉导致“候选模式切换、计数被打断”。
- 唯一切换标准：
  - 综合分 `switchConfidenceScore = 0.35 * poseTransitionScore + 0.65 * doorAssistScore`（DOOR 模式）
  - 其中：
    - `poseTransitionScore = ((1 - sourceRoomContainmentRatio) + targetRoomContainmentRatio) / 2`
    - `doorAssistScore = doorProximityScore`（仅当门法向运动通过时，否则为0）
- 判定条件（DOOR 模式）：
  - `enterMotionPass = true`（法向推进通过，横向路过门不会通过）
  - `doorProximityScore >= enterAMin`
  - `poseAverageConfidence >= exitPoseMinConfidence`
  - `switchConfidenceScore >= enterThreshold`
- POLYGON_RECOVERY 规则：
  - 对“可视子房间 -> 客厅”不再加入 `POLYGON_RECOVERY` 候选，避免与 DOOR 候选来回切换。
  - 其他方向保留恢复候选，按主体分阈值判定。
- 版本参数：
  - `enterThreshold = 0.45`
  - 其他参数沿用 V1.2.3（`enterAMin=0.35`，`exitOutsidePoseScoreThreshold=0.08` 等）

## 1.5 切换房间自动暂停开关
- 设置项：`切换房间后暂停播放`
- 存储键：`AppSettings.isPauseOnRoomSwitchEnabled`
- 行为：开关打开后，检测到 `Presence` 房间切换事件时，若当前为 `PLAYING`，自动执行一次暂停按钮逻辑（进入 `STILL`）。

## 1.6 Presence 调试日志压缩
- `presenceRecent` 历史按“连续相同事件/判定/计数”自动合并为区间行：
  - 例：`[f=120-128 x9 ...]`
- 同一帧多次更新只保留最后一条，避免重复帧刷屏。
- `presence decision` 文本改为短键输出；剪贴板报告中不再重复输出 `legend` 行，缩写定义统一见本节。

### 1.6.1 Presence 短键对照（统一口径）
- `dd=doorDist`
- `dps=doorProximityScore`
- `gpc=groundPointConfidence`
- `des=doorEvidenceScore`
- `trc=targetRoomContainmentRatio`
- `src=sourceRoomContainmentRatio`
- `sops=sourceRoomOutsidePoseScore`
- `sopsTh=exitOutsidePoseScoreThreshold`
- `pac=poseAverageConfidence`
- `pacMin=exitPoseMinConfidence`
- `srss=sourceRoomStayScore`
- `scs=switchConfidenceScore`
- `scsTh=switchThreshold`
- `srssTh=exitSourceRoomScoreThreshold`
- `dnd=dynamicNearDist`
- `rcm=recoveryTargetContainmentMin`
- `dad=doorAdvanceDelta`
- `dld=doorLateralDelta`
- `dalr=doorAdvanceLateralRatio`
- `enaMin=enterNormalAdvanceMin`
- `elrMin=enterLateralRatioMin`
- `emw=enterMotionWindowFrames`
- `emp=enterMotionPass`
- `upw=unifiedPoseWeight`
- `ptm=poseTransitionModel`
- `tpc=targetTopPoseContributors`
- `evnp=enterVisibleNearDoorPass`
- `evrp=enterVisibleNearDoorRawPass`
- `evlp=enterVisibleNearDoorLatchPass`
- `evnl=enterVisibleNearDoorLimit`
- `xvnp=exitVisibleNearDoorPass`
- `xvnl=exitVisibleNearDoorLimit`
- `evcp=enterVisibleContainmentPass`
- `evtm=enterVisibleTargetContainmentMin`
- `evsm=enterVisibleSourceContainmentMax`
- `psd=pastSignedDistance`
- `csd=currentSignedDistance`
- `pld=pastLateralDistance`
- `cld=currentLateralDistance`
- `dnx=doorNormalX`
- `dny=doorNormalY`
- `dmx=doorMidX`
- `dmy=doorMidY`
- `tcx=targetCentroidX`
- `tcy=targetCentroidY`
- `pgx=pastGroundX`
- `pgy=pastGroundY`
- `cgx=currentGroundX`
- `cgy=currentGroundY`
- `mhs=motionHistorySize`
- `mnp=motionNearDoorPassed`

### 1.6.2 Presence 完整参数速查（h/m/x）
说明：`presence decision` 的压缩串按 `h[...]|m[...]|x[...]` 输出，以下为完整字段口径（含当前主线扩展字段）。

#### h 段（头信息）
- `f`：来源房间（from）。
- `t`：目标房间（to）。
- `fr`：连续命中帧计数（frames），例如 `1/1`、`2/2`。
- `md`：判定模式（mode），例如 `DOOR:ENTER_VISIBLE`。
- `er`：退出规则（exitRule），例如 `VISIBLE_OUTSIDE_POSE`。
- `lc`：低置信标记（lowConf）。
- `sg`：候选分差（scoreGap）。

#### m 段（数值指标）
- `dd`：门距离（doorDist）。
- `dps`：门接近分（doorProximityScore）。
- `gpc`：地面点置信度（groundPointConfidence）。
- `des`：门证据分（doorEvidenceScore）。
- `dpe`：门接近有效分（doorProximityScoreEff）。
- `trc`：目标房间包含率（targetRoomContainmentRatio）。
- `src`：来源房间包含率（sourceRoomContainmentRatio）。
- `sops`：来源房间外部姿态分（sourceRoomOutsidePoseScore）。
- `pac`：姿态平均置信度（poseAverageConfidence）。
- `srss`：来源房间停留分（sourceRoomStayScore）。
- `pts`：姿态过渡分（poseTransitionScore）。
- `das`：门辅助分（doorAssistScore）。
- `scs`：切换置信分（switchConfidenceScore）。
- `ss`：单帧切换分（switchScore）。
- `e`：积分证据分（evidenceScore）。
- `eth`：积分触发阈值（evidenceThreshold）。
- `scsTh`：切换阈值（switchThreshold）。
- `srssTh`：来源停留阈值（exitSourceRoomScoreThreshold）。
- `sopsTh`：来源外部姿态阈值（exitOutsidePoseScoreThreshold）。
- `dnd`：动态近门距离（dynamicNearDist）。
- `nd`：当前近门基准距离（nearDist）。
- `dpsR`：原始门接近分（dpsRaw）。
- `dpsF`：最终门接近分（dpsFinal）。
- `dpsE`：进入链路门接近分（dpsEnter，可能做了 gamma 压缩）。
- `dpsTr`：soft-tail 尾部比例（dpsTailRatio）。
- `pacE`：有效姿态平均置信（poseEffectiveConfidence，仅统计有效关键点）。
- `ins`：门内进度分（insideScore）。
- `itr`：向门内推进分（inwardTrendScore）。
- `pbs`：擦门抑制分（passBySuppress）。
- `crs`：门洞穿越分（crossScore）。
- `eph`：进入阶段系数（enterPhase，`min(ins, itr)`）。
- `epf`：进入近门因子（enterProxFactor，门外偏向 `dpsE`、门内偏向 `max(dps, proxFloor)`）。
- `epfl`：进入近门下限（enterProxFloor）。
- `ecp`：进入门洞项（enterCrossPart，`epf * crs`）。
- `ess`：进入单帧分（enterSwitchScore）。
- `sRef`：门内进度参考尺度（enterInsideScoreRef）。
- `vRef`：向内推进参考尺度（enterInwardTrendRef）。
- `rRef`：法横比参考尺度（enterPassByRef）。
- `dad`：法向推进量（doorAdvanceDelta）。
- `dld`：横向位移量（doorLateralDelta）。
- `dalr`：法向/横向比（doorAdvanceLateralRatio）。
- `dadS`：短窗法向推进（doorAdvanceDeltaShort）。
- `dadL`：长窗法向推进（doorAdvanceDeltaLong）。
- `bsc`：最佳候选分（bestScore）。
- `ssc`：次佳候选分（secondScore）。
- `psc`：上一候选分（prevScore）。
- `bmp`：最佳与上一候选分差（bestMinusPrev）。
- `pg`：姿态门控（poseGate）。
- `cg`：门歧义清晰度门控（clearGate）。
- `ngp`：近门姿态门控（nearGateForPose）。
- `dsg`：门分离度（doorScoreGap）。
- `bdt`：反向抑制时间差毫秒（bounceDtMs）。
- `bfac`：反向抑制系数（bounceFactor）。

#### x 段（开关/配置/状态）
- `pacMin`：姿态最小置信阈值（exitPoseMinConfidence）。
- `phrMin`：姿态硬拒绝阈值（poseHardRejectMinConfidence）。
- `gpm`：灰姿态阈值（grayPoseMinConfidence）。
- `edg`：进入链路 `dps` gamma（enterDpsGamma）。
- `mwu`：运动窗口类型（motionWindowUsed），常见 `SHORT/LONG/FALLBACK`。
- `evnR`：进子房间近门硬约束开关（enterVisibleRequireNearDoor）。
- `evcR`：进子房间包含硬约束开关（enterVisibleRequireContainment）。
- `xvnR`：出子房间近门硬约束开关（exitVisibleRequireNearDoor）。
- `evdeR`：进子房间门证据硬约束开关（enterVisibleRequireDoorEvidence）。
- `evdeP`：门证据是否通过（enterVisibleDoorEvidencePass）。
- `evdaM`：门辅助最小阈值（enterVisibleDoorAssistMin）。
- `xvdh`：出子房间 `dps` 保持是否生效（exitDpsHoldApplied）。
- `xvpm`：可视退出姿态模型（visibleExitPoseTransitionModel）。
- `xvpr`：可视退出是否允许 polygon recovery（allowVisibleExitPolygonRecovery）。
- `cand`：当前候选（candidate，通常是 `房间@门#编号`）。
- `pc`：上一候选（prevCandidate）。
- `stk`：是否触发候选粘性（stickApplied）。
- `stkr`：粘性原因（stickReason，例如 `ALL_ZERO/FREEZE`）。
- `fbf`：是否触发 FALLBACK 冻结（fallbackFrozen）。
- `bap`：是否触发反向抑制（bounceApplied）。
- `lsw`：上一次切换记录（lastSwitch）。
- `ssba`：反向抑制前后分值（ssBeforeAfter）。

## 1.7 播放往复回跳（播放器卡死）排查

### 1.7.1 现象简述
- 表现：画面在一个小时间段内来回跳（`posMs/nowMs` 在窄区间往返），声音可能继续走，`playState` 仍显示 `PLAYING`。
- 已知非根因：`EventValidationTick` 的 `overdueTriggered=false` 时，不是智能校验暂停导致。
- 高概率根因方向：播放链路出现反复 seek 回拉，或 UI 播放状态与 `MediaPlayer.isPlaying` 短时不同步。

### 1.7.2 必开开关
- 设置中打开：`切换时记录暂停判定日志`（`pauseSwitchLog=true`）。
- 该开关会启用 `RoomPlayerDiag` 的播放器诊断日志。

### 1.7.3 推荐抓取命令（仅播放器相关）
1. 清空日志：
```powershell
adb logcat -c
```
2. 复现后导出（事后也可）：
```powershell
adb logcat -d -v threadtime | findstr /I "RoomPlayerDiag VideoFeeder MediaPlayer EventValidationTick RF_ROI playState seek seekTo pause resume"
```
3. 同时复制一份当时的调试面板快照（长按调试面板按钮）。

### 1.7.4 重点日志字段
- `RoomPlayerDiag togglePause ... beforePos/afterPos beforePlaying/afterPlaying`
- `RoomPlayerDiag seekByMs request ... before target mode captureAsStep`
- `RoomPlayerDiag seekComplete ... pending=...`
- `RoomPlayerDiag playLoopAnomaly ... delta stallCount`
- `EventValidationTick ... overdueTriggered=...`
- `RF_ROI ... posMs=... digest=...`

### 1.7.5 快速判读口径
- `overdueTriggered=false` 且 `RoomPlayerDiag` 有频繁 `seekByMs request`：优先排查谁在触发 seek。
- `playState=PLAYING` 但 `beforePlaying/afterPlaying=false`：优先排查状态机与播放器同步点。
- `playLoopAnomaly stallCount` 连续升高且 `delta<=0`：说明播放位置未前进或倒跳，需沿调用链回溯最近一次 seek/pause 操作来源。

## 1.8 TFLite GPU 并发崩溃（destroyed mutex）

### 1.8.1 现象简述
- 启动后几秒偶发直接闪退，重启后可能暂时正常。
- 日志关键字：
  - `FORTIFY: pthread_mutex_lock called on a destroyed mutex`
  - `Fatal signal 6 (SIGABRT)`
  - 调用栈位于 `libtensorflowlite_gpu_jni.so` / `NativeInterpreterWrapper_run`

### 1.8.2 已确认根因
- `VideoFeeder` 以固定周期提交推理，若单帧推理耗时超过调度间隔，容易产生并发 `Interpreter.run()`。
- TFLite `Interpreter + GPU Delegate` 非线程安全；并发运行会触发 JNI/GPU 侧 mutex 生命周期冲突。

### 1.8.3 修复策略（V1.6后）
- 推理执行改为单线程串行（single-thread executor）。
- 增加 `inFlight` 防重入：上一帧未完成时新帧直接丢弃，不排队。
- 日志改为异常触发：仅在连续阻塞（回压）时输出 `inferenceBackpressure`。

### 1.8.4 抓取建议（仅遇到问题时）
```powershell
adb logcat -c
adb logcat -v threadtime | findstr /I "libtensorflowlite_gpu_jni NativeInterpreterWrapper_run FORTIFY SIGABRT RoomPlayerDiag VideoFeeder"
```

### 1.8.5 判读口径
- 若出现 `destroyed mutex + libtensorflowlite_gpu_jni + NativeInterpreterWrapper_run`，优先按“推理并发”处理，不归因到房间计数算法。
- 若同时看到 `inferenceBackpressure`，说明推理耗时已接近/超过调度间隔，应继续关注设备负载与帧率配置。
