# Gemini History

## [026] 2026-01-10 22:30:00 - 重构房间设置页为下拉菜单模式
​
**用户指令 (概述)**：
将“主房间/次房间”切换按钮替换为 Spinner 下拉菜单，并新增“设备设置”入口。整体页面布局保持不变，设备设置作为第三种模式，目前仅做占位。
​
**AINoRead (原文)**：
> “设备设置”和另外两个是一样的逻辑,也是几个button默认... 整体页面布局不改,目前是点击这个页面菜单最左侧的切换两个功能,我们只是把点击互相切换改成下拉切换.
​
**实现方案 (Implementation)**：
​
*   **`res/layout/activity_main.xml`**
    *   **控件替换**：将 `btnModeSwitcher` 替换为 `Spinner` (`spnEditMode`)。
    *   **新增容器**：添加 `llDeviceSettings` 作为设备管理界面的占位容器（默认隐藏）。
    *   **新增按钮**：添加 `btnClose` 图标按钮用于退出编辑模式。
​
*   **`ui/activities/MainActivity.kt`**
    *   **逻辑重构**：移除旧的切换按钮监听，初始化 `Spinner` 并设置 `ArrayAdapter`。
    *   **模式切换**：在 `onItemSelected` 中处理三种模式：
        *   `主房间设置` -> `applyModeSelection(LIVING_ROOM_HULL)`
        *   `次房间设置` -> `applyModeSelection(SUB_ROOM_ANCHOR)`
        *   `设备设置` -> 隐藏 `editorView`，显示 `llDeviceSettings`。
    *   **按钮适配**：调整右侧操作栏按钮在不同模式下的显隐逻辑。
​
 ---
## [025] 2026-01-08 01:52:41 - 升级 ROI 追踪器抗震荡平滑算法
​
**用户指令 (概述)**：
实现基于物理帧率自适应的 EMA 滤波、1% 像素死区过滤以及快车道/跳变响应机制，彻底消除追踪过程中的高频震荡和反馈环路。
​
**AINoRead (原文)**：
> 那就用上这两个稳定手段:通过当前模型的延时来确定帧率对应的跟随时间... 死区：偏移 dx,dy = targetCenter - roiCenter；若 |dx| < 0.01*640 且 |dy| < 0.01*640，本帧 ROI 不更新... EMA 平滑（动态帧率自适应）... 跟随系数 = 1 - exp( -dt / 跟随时间 )... 快车道 / 直接跳...
​
**实现方案 (Implementation)**：
​
*   **`logic/analyzer/RoiTracker.kt`**
    *   **平滑状态持久化**：引入 `roiCenterX`, `roiCenterY` 存储平滑后的中心，以及 `lastUpdateTs` 用于计算实时 `dt`。
    *   **死区控制**：实现 1% 阈值判断，忽略微小像素抖动。
    *   **自适应 EMA**：根据实时检测间隔（帧率）计算跟随系数，平衡响应速度与稳定性。
    *   **分级响应机制**：
        *   位移 < 1%：静止（死区）。
        *   位移 < 12%：标准平滑（tau=0.16s）。
        *   位移 < 25%：快速跟随（tau=0.05s）。
        *   位移 > 25%：瞬移对齐（跳变）。
​
*   **`ui/activities/MainActivity.kt`**
    *   **状态重置**：在彻底丢失目标进入 `SearchingFull` 模式时，强制调用 `roiTracker.resetSmoothing()`，确保下次捕捉目标时能瞬间锁定而非缓慢滑动。
​
 ---
## [024] 2026-01-07 22:39:37 - 补全联网权限与 HTTP 访问支持

**用户指令 (概述)**：
检查并补全联网权限，确保能够正常访问本地网络中的 HTTP 推理服务器。

**AINoRead (原文)**：
> 检查一下我们现在是否有联网权限，没有的话就加上。

**实现方案 (Implementation)**：

*   **`app/src/main/AndroidManifest.xml`**
    *   **权限补全**：新增 `android.permission.ACCESS_NETWORK_STATE` 权限，用于获取网络状态信息。
    *   **安全配置**：在 `<application>` 标签中添加 `android:usesCleartextTraffic="true"`，允许应用通过非加密的 HTTP 协议访问本地推理后端。

---

## [023] 2026-01-07 22:39:37 - 实现三段式播放控制 (播放/静止/暂停)
​
**用户指令 (概述)**：
将播放控制从“播放/暂停”升级为“播放/静止/暂停”三段式循环切换，其中“静止”状态视频画面暂停，但模型识别继续运行。
​
**AINoRead (原文)**：
> 我们要把当前的播放和暂停扩充一个逻辑。 加上一个。'静止"的中间态，这个中间态播放暂停了，但是识别仍然在继续。 考虑一下怎么？ 做，尽量不要影响之前的逻辑。这三个按钮轮流生效
​
**实现方案 (Implementation)**：
​
*   **`ui/activities/MainActivity.kt`**
    *   **状态机**：引入 `PlayState` 枚举 (`PLAYING`, `STILL`, `PAUSED`) 代替旧的布尔值。
    *   **循环切换**：重写 `togglePause` 方法，实现 `PLAYING` → `STILL` → `PAUSED` 的循环，并更新按钮文本为 `[ 播放中 ]`, `[ 静止中 ]`, `[ 暂停中 ]`。
    *   **逻辑分发**：根据新状态，分别调用 `videoFeeder` 的 `resume()`, `pause()`, `setStillMode()`。
​
*   **`logic/video/VideoFeeder.kt`**
    *   **新增状态**：增加 `private var isStillMode = false` 属性。
    *   **逻辑变更**：修改 `analyzeRunnable` 的核心判断条件为 `if (mediaPlayer!!.isPlaying || isStillMode)`，确保在视频暂停但 `isStillMode` 为 `true` 时，分析循环继续。
​
 ---
## [022] 2026-01-06 04:30:00 - 恢复持久化人数显示 (黄色数字)
​
**用户指令 (概述)**：
修复在之前的 UI 重构中意外丢失的“持久化人数”显示功能，确保棋子上方重新出现黄色的持久化计数。
​
**AINoRead (原文)**：
> 我现在每个房间上面只有一个数字了，看一下是什么时候另外一个数字不见的。 另外一个数字应该是持久化的数字。
​
**实现方案 (Implementation)**：
​
*   **`ui/views/DetectionOverlayView.kt`**
    *   **方法修复**：恢复 `drawPawn` 方法的 `persistentCount: Int` 参数。
    *   **绘制增强**：在棋子名称的最上方（`y - r * 6.5f`）使用黄色字体恢复绘制 `persistentCount`。
    *   **调用同步**：在 `onDraw` 中调用 `drawPawn` 时，传入 `room.persistentPersonCount`。
​
 ---
## [021] 2026-01-06 04:15:00 - 实现弱目标框虚线绘制
​
**用户指令 (概述)**：
针对未锁定且双肩置信度不足的目标，将其检测框改为虚线绘制，以增强视觉区分。
​
**AINoRead (原文)**：
> 我们要做一个新的。 修改，如果一个ID既没有被lock，他的肩膀也没有大于高度可信,那他的人物框就用虚线绘制.
​
**实现方案 (Implementation)**：
​
*   **`ui/drawers/PoseDrawer.kt`**
    *   **线型逻辑**：引入 `dashedEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)`。
    *   **判定标准**：判断当前 `PoseResult` 是否为“弱目标”（`!isConfirmed && !shouldersTrusted`）。
    *   **绘制变更**：根据判定结果，动态切换 `boxPaint.pathEffect`。
        ​
 ---
## [020] 2026-01-06 04:00:00 - 调整人物锁定校验点

**用户指令 (概述)**：
放宽姿态完整性校验逻辑，仅要求 **左肩(5)** 和 **右肩(6)** 的置信度 >= 0.7f 即可触发锁定。

**AINoRead (原文)**：
> 现在修改一下关于。 信任点对锁定逻辑的。 判断。 我们只要肩膀的两个点大于高度可信，那我们就。 允许他lock

**实现方案 (Implementation)**：

*   **`logic/analyzer/YoloPoseAnalyzer.kt`**
    *   **校验点变更**：将 `REQUIRED_LOCK_KEYPOINTS` 从 `listOf(0, 1, 2, 3, 4, 5, 6)` 修改为 `listOf(5, 6)`。
    *   **逻辑说明**：这意味着系统现在更关注躯干的识别（双肩），而不再强制要求面部（眼鼻耳）清晰可见才锁定。

---

## [019] 2026-01-06 03:35:00 - 关键点阈值全局同步

**用户指令 (概述)**：
将关键点“高度可信”阈值提升至 0.7，并确保所有逻辑（绘制颜色、锁定判定）统一使用该阈值。

**AINoRead (原文)**：
> 这两个当然要同步我再说一次这个0.7不要写死了。 用我们刚才的。 那个变量。 我们那个。 常量就是来专门储存所有的对。 关键点的可信。 的储存以后，所有的对可信点的判断都是他。

**实现方案 (Implementation)**：

*   **`data/model/PoseData.kt`**
    *   **全局常量**：定义 `const val POSE_HIGH_CONFIDENCE_THRESHOLD = 0.7f`，作为唯一的真理来源 (Single Source of Truth)。

*   **`logic/analyzer/YoloPoseAnalyzer.kt`**
    *   **重构**：移除私有的 `HIGH_CONFIDENCE_THRESHOLD`，改用 `PoseData.kt` 中的全局常量。
    *   **逻辑同步**：`checkPoseIntegrity` 现在使用 0.7f 作为判定标准，意味着只有非常清晰的目标才能触发锁定。

*   **`ui/drawers/PoseDrawer.kt`**
    *   **重构**：移除私有的 `kptConfThreshold`。
    *   **颜色分级**：`getKeypointColor` 逻辑更新：
        *   < 0.01: GRAY
        *   < 0.05: RED
        *   < 0.1: YELLOW
        *   < 0.7 (`POSE_HIGH_CONFIDENCE_THRESHOLD`): CYAN
        *   >= 0.7: GREEN
    *   **绘制同步**：所有关键点和骨架的绘制判断 (`if (conf > ...)`) 统一使用全局常量或对齐的分级逻辑。

---

## [018] 2026-01-06 03:15:00 - 实现关键点分级着色

**用户指令 (概述)**：
实现全身 17 个关键点（Keypoints）的置信度分级着色。颜色规则：灰色(<0.01)、红色(<0.05)、黄色(<0.1)、青色(<0.3)、绿色(>=0.3)。

**AINoRead (原文)**：
> 我们现在。 全身的17个点，颜色都是一样的，我们要做一些变更。 看一下我们现在有没有这个实现。 没有的话去加上变更，然后我去改各个颜色的阈值。 score < 0.01f -> Color.GRAY ...

**实现方案 (Implementation)**：

*   **`ui/drawers/PoseDrawer.kt`**
    *   **分级逻辑**：新增私有方法 `getKeypointColor(score: Float): Int`，根据用户指定的 5 个阈值返回对应的颜色（GRAY, RED, YELLOW, CYAN, GREEN）。
    *   **绘制变更**：在 `draw` 方法的关键点绘制循环中，将原本统一的 `kptPaint.color` 改为对每个点调用 `getKeypointColor(p.conf)` 动态设置。

---

## [017] 2026-01-06 03:00:00 - 增强人物锁定逻辑与互斥判定

**用户指令 (概述)**：
强化人物锁定逻辑，要求连续 5 帧位置均不重合才锁定，且锁定前需通过姿态完整性校验（指定关键点 >= 0.3f）。

**AINoRead (原文)**：
> 我们计算一下人物框,连续5帧位置均不一样,才lock... 任意两帧差别都必须大于2个像素... 我们做一个通过是不是人判断类,如果以下这些点没有全部全部为绿色,则这一帧返回flase.

**实现方案 (Implementation)**：

*   **`logic/analyzer/YoloPoseAnalyzer.kt`**
    *   **5 帧互斥判定**：
        *   `TrackedSubjectHistory` 新增 `recentPositions: ArrayDeque`。
        *   在 `updateTrackingState` 中，对未锁定目标收集最近 5 帧中心点。
        *   当满 5 帧时，执行双重循环比对，要求任意两帧距离 > 0.003f (约 2px)。
    *   **姿态完整性校验**：
        *   新增 `checkPoseIntegrity(result)` 方法。
        *   检查鼻、眼、耳、肩共 7 个关键点置信度是否均 >= 0.3f (`HIGH_CONFIDENCE_THRESHOLD`)。
        *   在 `TrackedSubjectHistory` 中新增 `hasPassedIntegrityCheck` 标记。
    *   **锁定条件**：
        *   修改 `isLocked` 判定：`score > 0.6` && `hasEverMoved` (5帧互斥通过) && `hasPassedIntegrityCheck`。
    *   **阈值调整**：
        *   `MIN_CANDIDATE_SCORE_THRESHOLD` 下调至 0.15f，以捕获更模糊的目标进行锁定判定。

---

## [016] 2026-01-06 02:40:00 - 实现自适应 ROI 尺寸策略

**用户指令 (概述)**：
实现 ROI 框的自增自减策略：当人体检测框接近或超过当前裁剪框大小时，框体增加 50 像素；当人体框变小时，框体缩小 50 像素。确保框体始终为正方形，且在 640 到视频短边最大值之间。

**AINoRead (原文)**：
> 我们的640直接裁剪的逻辑有一些问题。 我们要设计一个自增自减的策略，如果。 人物框。 也已经达到了和裁剪框一样大甚至更大。 那我们的裁剪框体就要增大50像素(永远为正方形)。 永远不要让绿框贴近框体。 直到到达视频短边的最大值。当人体检测框变小时(长边小于裁剪框体-50), 裁剪框体就缩小50,直到640的下限.

**实现方案 (Implementation)**：

*   **`logic/analyzer/RoiTracker.kt`**
    *   **动态尺寸逻辑**：引入 `currentRoiSize` 状态（初始 640f）。
    *   **自动扩容**：在 `calculate` 中，若 `max(personW, personH) >= currentRoiSize`，则 `currentRoiSize += 50f`。
    *   **自动缩容**：若 `max(personW, personH) < (currentRoiSize - 50f)`，则 `currentRoiSize -= 50f`。
    *   **边界保护**：通过 `coerceIn(640f, minImageSide)` 确保尺寸合法性。
    *   **几何更新**：使用动态调整后的 `currentRoiSize` 重新计算归一化 `roiNormW` 和 `roiNormH`。

---
## [014] 2026-01-06 01:55:00 - 实现 ROI 真实裁剪与状态机

**用户指令**：
> 1.在设置里面加一个开关,启用ROI真实跟踪.只有当打开时,才进行ROI裁剪...
> 2.初始就按照你说的那样,找到人后仍然为黄色
> 3.将阈值设置为10帧...
> 1.状态名更明确一下... 2.开关是关闭状态下,仍然和之前一样追踪,但是虚线更稀疏.3.彻底丢失目标后,不绘制.

**实现方案 (Implementation)**：

从“UI 画框验证”全面升级为“底层真实裁剪推理”，并实现了完整的追踪状态机。

*   **`data/repository/AppSettings.kt`** & **`ui/settings/SettingsHomeFragment.kt`**
    *   新增 `isRoiRealCropEnabled` 开关，允许用户控制是否真的把图片切小了喂给 YOLO。

*   **`logic/analyzer/YoloPoseAnalyzer.kt`**
    *   **核心升级**：`analyzeBitmapAndTrackPoses` 新增 `roi` 参数。
    *   **裁剪**：如果 `roi != null`，使用 `Bitmap.createBitmap` 物理裁剪出 ROI 区域。
    *   **坐标映射**：在后处理阶段 (`extractRawPoses`)，如果进行了裁剪，将模型输出的局部坐标（相对于 ROI）逆变换回全图坐标（Global Coordinates）。

*   **`ui/activities/MainActivity.kt`**
    *   **状态机**：引入 `roiMissingFrameCount`。
    *   **SearchingFull**：超过 10 帧未检测到，ROI 为 null（全屏搜索）。
    *   **Tracking/Lost**：检测到目标或丢失缓冲期，计算 ROI。
    *   **控制逻辑**：将计算出的 ROI 传给 `VideoFeeder`，用于下一帧推理。

*   **`logic/video/VideoFeeder.kt`**
    *   新增 `nextFrameRoi` 字段，作为 `MainActivity` 和 `YoloPoseAnalyzer` 之间的桥梁。

*   **`ui/views/DetectionOverlayView.kt`**
    *   **视觉反馈**：
        *   跟踪中：黄色。
        *   丢失缓冲 (<10帧)：红色。
        *   彻底丢失：不绘制。
    *   **样式区分**：如果是“模拟追踪”（开关关闭），使用**极度稀疏**的虚线，以区别于“真实裁剪追踪”。

---

## [013] 2026-01-06 01:25:00 - 集成动态 ROI 追踪器

**用户指令**：
> 实现固定尺寸 ROI 的视觉验证系统... 新建 logic/analyzer/RoiTracker.kt... 修改 ui/views/DetectionOverlayView.kt... 联调 MainActivity.kt... 绘制一个 黄色虚线框... 增加跟踪状态指示（红/黄切换）。

**实现方案 (Implementation)**：

为了解决高分辨率下关键点模糊的问题，实现了一个“动态 ROI 追踪”原型。

*   **`logic/analyzer/RoiTracker.kt` (新增)**
    *   核心算法：实现了 Clamp & Shift 逻辑，确保 640x640 的框始终在屏幕内且平滑跟随目标。
    *   状态管理：新增 `isTracking` 属性，用于指示当前是否锁定目标。

*   **`ui/views/DetectionOverlayView.kt`**
    *   绘制逻辑：新增 `roiBox` 和 `isRoiTracking` 属性。
    *   视觉样式：使用 `DashPathEffect` 绘制虚线框。根据 `isRoiTracking` 状态切换颜色（黄色=追踪中，红色=丢失）。

*   **`ui/activities/MainActivity.kt`**
    *   逻辑串联：在 `poseAnalyzer` 回调中实例化并调用 `roiTracker.calculate`。
    *   数据传递：将计算出的 `roiRect` 和 `isTracking` 状态实时传递给 `overlayView`。

---

## [012] 2026-01-06 00:55:00 - 集成战术雷达地图 (Tactical Map)

**用户指令**：
> 我们这一次的核心需求是构建一个全平面的“雷达地图” (Tactical Map)... 独立的上帝视角... 智能坐标映射... 关键信息可视化...
> 在主屏幕的菜单列表增加一个"雷达"... view展示后,在画面居中最上方放一个X图标来关闭它.

**实现方案 (Implementation)**：

实现了一个全新的、脱离摄像头透视干扰的“战术雷达地图”。

*   **`ui/views/TacticalMapView.kt` (新增)**
    *   核心算法：实现 `calculateWorldBounds` (计算世界包围盒) 和 `Matrix.setRectToRect` (自动缩放居中)。
    *   绘制逻辑：使用 `Canvas` 绘制房间多边形和人物点位，不依赖任何 Bitmap。

*   **`res/layout/activity_main.xml`**
    *   布局结构：添加全屏覆盖的 `FrameLayout` (`flRadarContainer`)，包含 `TacticalMapView` 和关闭按钮。默认隐藏。

*   **`ui/activities/MainActivity.kt`**
    *   交互逻辑：处理 `btnRadar` (显示) 和 `btnCloseRadar` (隐藏) 的点击事件。
    *   数据驱动：在 `poseAnalyzer` 回调中，收集所有 `landingPoint` 并实时更新雷达数据。

---

## [011] 2026-01-06 00:45:00 - 优化人数显示样式

**用户指令**：
> 看不太清，当人数不等于零的时候，把它变成一个大大的完全不透明的。 红色的数字。

**实现方案 (Implementation)**：

*   **`ui/views/DetectionOverlayView.kt`**
    *   `drawPawn`：优化绘制逻辑. 当 `count > 0` 时，使用 **红色 (Color.RED)** 和 **放大字体 (45f)**；否则保持白色默认样式。

---

## [010] 2026-01-06 00:35:00 - 调整人数显示位置

**用户指令**：
> 数字应该显示在房间名称的上方，而不是下方。

**实现方案 (Implementation)**：

*   **`ui/views/DetectionOverlayView.kt`**
    *   `drawPawn`：调整 `drawText` 的 Y 坐标，将人数显示移至房间名称上方 (`y - r * 4.0f`)。

---

## [009] 2026-01-06 00:15:00 - 增加房间人数统计功能

**实现方案 (Implementation)**：

*   **`data/model/RoomConfig.kt`**
    *   数据结构：新增 `personCount` (瞬时值) 和 `realPersonCount` (逻辑值) 字段。

*   **`ui/activities/MainActivity.kt`**
    *   统计逻辑：重写 `poseAnalyzer` 回调，引入双重循环判定：遍历所有房间 (`allRooms`)，使用 `GeometryUtils.isPointInPolygon` 统计每个房间的人数。

---

## [008] 2026-01-05 23:55:00 - 修复重影问题(状态同步)

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   Bug修复：在 `toggleEditModeUI()` 方法末尾，补上了漏调用的 `overlayView.setEditMode(isEditing)`，确保视图状态同步。

---

## [007] 2026-01-05 23:42:15 - 修复编辑模式下的重影与图标大小不一致问题

**实现方案 (Implementation)**：

*   **`ui/views/DetectionOverlayView.kt`**
    *   状态控制：新增 `isEditMode` 属性。在 `onDraw` 中，当处于编辑模式时，停止绘制房间区域和图标，只绘制人体，从而消除重影。

*   **`ui/activities/MainActivity.kt`**
    *   逻辑串联：在 `toggleEditModeUI` 中调用 `overlayView.setEditMode` 同步状态。

---

## [006] 2026-01-05 23:22:45 - 开启编辑模式下的实时检测显示

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   可见性控制：修改 `toggleEditModeUI`，强制 `overlayView` 在编辑模式下保持 `VISIBLE`，以便显示人体骨架。

---

## [005] 2026-01-05 23:05:00 - 修复地图拉伸与暂停问题

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   `enterEditMode`：恢复 `captureCurrentFrame()` 以提供坐标参考，但移除 `videoFeeder.pause()` 以保持播放。设置 `editorView.drawBackground = false`。

*   **`ui/views/LivingRoomEditorView.kt`**
    *   渲染逻辑：新增 `drawBackground` 属性。在 `onDraw` 中，仅当此属性为 true 时才绘制背景 Bitmap，但无论如何都利用 Bitmap 尺寸计算 `dstRect` 以保证坐标对齐。

---

## [004] 2026-01-05 22:50:30 - 实现不暂停视频的实时编辑模式

**实现方案 (Implementation)**：

*   **`ui/views/LivingRoomEditorView.kt`**
    *   新增属性 `var drawBackground: Boolean = true`。
    *   在 `onDraw` 方法中，仅当 `drawBackground` 为 `true` 时才执行 `canvas.drawBitmap`，但无论如何都会保留 `dstRect` 的计算逻辑。

*   **`ui/activities/MainActivity.kt`**
    *   在 `enterEditMode` 中移除了 `videoFeeder?.pause()` 和 `isPaused = true`。
    *   设置 `editorView.drawBackground = false`，从而隐藏静态截图但保留其坐标元数据。

---

## [003] 2026-01-05 22:45:00 - 回滚移除编辑模式暂停逻辑

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   回滚操作：恢复了 `enterEditMode()` 中暂停视频 (`videoFeeder.pause()`) 和设置截图背景 (`captureCurrentFrame()`) 的旧逻辑。

---

## [002] 2026-01-05 22:42:30 - 修改未绑定边缘颜色

**实现方案 (Implementation)**：

*   **`ui/views/DetectionOverlayView.kt`**
    *   样式调整：将 `regionStrokePaint` 的颜色从橙色改为白色。

---

## [001] 2026-01-05 22:38:15 - 移除编辑模式暂停逻辑

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   `enterEditMode`：注释掉 `videoFeeder.pause()` 和 `captureCurrentFrame()`，尝试让背景透明并继续播放视频（此版本导致了坐标拉伸问题，后续已修复）。
