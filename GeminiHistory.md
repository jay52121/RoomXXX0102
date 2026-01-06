# Gemini History
# Gemini History

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
> 1.状态名需要更明确一下... 2.开关是关闭状态下,仍然和之前一样追踪,但是虚线更稀疏.3.彻底丢失目标后,不绘制.

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
    *   `drawPawn`：优化绘制逻辑。当 `count > 0` 时，使用 **红色 (Color.RED)** 和 **放大字体 (45f)**；否则保持白色默认样式。

---

## [010] 2026-01-06 00:35:00 - 调整人数显示位置

**用户指令**：
> 数字应该显示在房间名称的上方，而不是下方。

**实现方案 (Implementation)**：

*   **`ui/views/DetectionOverlayView.kt`**
    *   `drawPawn`：调整 `drawText` 的 Y 坐标，将人数显示移至房间名称上方 (`y - r * 4.0f`)。

---

## [009] 2026-01-06 00:15:00 - 增加房间人数统计功能

**用户指令**：
> 回到我们之前说的客厅人数显示问题。 修改这个房间人数的数据模型吧.增加一个真实人数和当前识别人数的持久化... 第二步，把当前识别人数的逻辑改成和客厅一样... 第三步，把当前识别人数显示在房间名称的上面。

**实现方案 (Implementation)**：

*   **`data/model/RoomConfig.kt`**
    *   数据结构：新增 `personCount` (瞬时值) 和 `realPersonCount` (逻辑值) 字段。

*   **`ui/activities/MainActivity.kt`**
    *   统计逻辑：重写 `poseAnalyzer` 回调，引入双重循环判定：遍历所有房间 (`allRooms`)，使用 `GeometryUtils.isPointInPolygon` 统计每个房间的人数。

---

## [008] 2026-01-05 23:55:00 - 修复重影问题(状态同步)

**用户指令**：
> 没有发生变化还是有重影

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   Bug修复：在 `toggleEditModeUI()` 方法末尾，补上了漏调用的 `overlayView.setEditMode(isEditing)`，确保视图状态同步。

---

## [007] 2026-01-05 23:42:15 - 修复编辑模式下的重影与图标大小不一致问题

**用户指令**：
> 这次画面看起来没有任何问题，但是。 不知道为什么所有的文字。 也就是我们之前的房间的文字，还有。 房间的图标都变大了。 而且和之前的重叠了叠在了一起,变成了两个。 分析这个原因是为什么？

**实现方案 (Implementation)**：

*   **`ui/views/DetectionOverlayView.kt`**
    *   状态控制：新增 `isEditMode` 属性。在 `onDraw` 中，当处于编辑模式时，停止绘制房间区域和图标，只绘制人体，从而消除重影。

*   **`ui/activities/MainActivity.kt`**
    *   逻辑串联：在 `toggleEditModeUI` 中调用 `overlayView.setEditMode` 同步状态。

---

## [006] 2026-01-05 23:22:45 - 开启编辑模式下的实时检测显示

**用户指令**：
> 好的，这次没有问题了，但是在设置界面，我还是希望。 人体识别能够继续进行。现在人体识别。 停止了。

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   可见性控制：修改 `toggleEditModeUI`，强制 `overlayView` 在编辑模式下保持 `VISIBLE`，以便显示人体骨架。

---

## [005] 2026-01-05 23:05:00 - 修复地图拉伸与暂停问题

**用户指令**：
> 果然是你那次提交的问题... 在设置界面它的确是能够继续播放了，但是整个地图被拉的非常的大... 听起来修改量还是比较大的。这已经是你觉得最小的修改方式了吗？... 好的，我们尝试一下吧。

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   `enterEditMode`：恢复 `captureCurrentFrame()` 以提供坐标参考，但移除 `videoFeeder.pause()` 以保持播放。设置 `editorView.drawBackground = false`。

*   **`ui/views/LivingRoomEditorView.kt`**
    *   渲染逻辑：新增 `drawBackground` 属性。在 `onDraw` 中，仅当此属性为 true 时才绘制背景 Bitmap，但无论如何都利用 Bitmap 尺寸计算 `dstRect` 以保证坐标对齐。

---

## [004] 2026-01-05 22:50:30 - 实现不暂停视频的实时编辑模式

**用户指令**：
> 果然是你那次提交的问题... 在设置界面它的确是能够继续播放了，但是整个地图被拉的非常的大... 听起来修改量还是比较大的。这已经是你觉得最小的修改方式了吗？... 好的，我们尝试一下吧。

**实现方案 (Implementation)**：

*   **`ui/views/LivingRoomEditorView.kt`**
    *   新增属性 `var drawBackground: Boolean = true`。
    *   在 `onDraw` 方法中，仅当 `drawBackground` 为 `true` 时才执行 `canvas.drawBitmap`，但无论如何都会保留 `dstRect` 的计算逻辑。

*   **`ui/activities/MainActivity.kt`**
    *   在 `enterEditMode` 中移除了 `videoFeeder?.pause()` 和 `isPaused = true`。
    *   设置 `editorView.drawBackground = false`，从而隐藏静态截图但保留其坐标元数据。

---

## [003] 2026-01-05 22:45:00 - 回滚移除编辑模式暂停逻辑

**用户指令**：
> ## 2026-01-06 - 移除编辑模式暂停逻辑  这次修改有问题,先回滚试试

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   回滚操作：恢复了 `enterEditMode()` 中暂停视频 (`videoFeeder.pause()`) 和设置截图背景 (`captureCurrentFrame()`) 的旧逻辑。

---

## [002] 2026-01-05 22:42:30 - 修改未绑定边缘颜色

**用户指令**：
> 在主界面里面。 没有被绑定。 房门的客厅区域边儿目前显示为橙色我需要你把它改成白色。设置界面的是白色没有问题。

**实现方案 (Implementation)**：

*   **`ui/views/DetectionOverlayView.kt`**
    *   样式调整：将 `regionStrokePaint` 的颜色从橙色改为白色。

---

## [001] 2026-01-05 22:38:15 - 移除编辑模式暂停逻辑

**用户指令**：
> 目前的逻辑是，当我点击。 设置房间的时候。 会站暂停整个功能的识别和播放。 我希望。 把这个逻辑去掉。

**实现方案 (Implementation)**：

*   **`ui/activities/MainActivity.kt`**
    *   `enterEditMode`：注释掉 `videoFeeder.pause()` 和 `captureCurrentFrame()`，尝试让背景透明并继续播放视频（此版本导致了坐标拉伸问题，后续已修复）。
