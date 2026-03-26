# Codex History

## [247] 2026-03-27 00:08:00 - 将当前ROI裁剪链路接入MediaPipe Hand Landmarker

**用户指令**：
> 我们先把目前的ROI喂给MediaPipe。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让 Hand Landmarker 在 `roiCrop=true` 且 ROI 存在时使用当前 ROI 裁剪图输入，同时保持手点 overlay 和 pointing 继续使用原图坐标系。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/HandLandmarkerPointingAdapter.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：HandSmokeTester.detect/onLiveStreamResult/cropBitmapIfNeeded/mapToFullFrame、VideoFeeder.analyzeRunnable、HandLandmarkerPointingAdapter.toObservation
    *   关键改动：
      * `VideoFeeder` 调用手部检测时，改为把当前 `nextFrameRoi` 一并传给 `HandSmokeTester`。
      * `HandSmokeTester` 在 ROI 存在时先裁剪子图送入 MediaPipe，再把返回的 landmark 坐标逆映射回原图归一化坐标。
      * 手点 overlay 与 pointing observation 均改为消费逆映射后的原图坐标，因此现有显示和目标矩形无需额外改坐标系。
      * `roiCrop=false` 或 ROI 为空时，继续保持整帧输入逻辑不变。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [246] 2026-03-26 06:18:00 - 重构测试视频来源入口为折叠式文件加载与历史视频列表

**用户指令**：
> 把选择测试视频也重构一下，分为现在的从文件中加载，和显示历史加载过的视频；和刚才读取一样，也是展开和关闭；历史也可以删除（需确认）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将设置页“选择测试视频”重构为折叠式入口，支持从文件加载与历史视频列表，并对历史视频提供查看、读取和确认删除。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/res/layout/fragment_settings_home.xml、dialogueHistory.md、codexHistory.md
    *   涉及方法：AppSettings.getTestVideoHistory/pushTestVideoHistory/removeTestVideoHistory、SettingsHomeFragment.loadSelectedVideo/setVideoListExpanded/refreshVideoListContent/showVideoHistoryDetails/confirmLoadVideoHistory/confirmDeleteVideoHistory
    *   关键改动：
      * `AppSettings` 新增测试视频历史记录的持久化读写，按最近使用顺序去重保存。
      * 设置页“选择测试视频”改为折叠式入口；展开后先显示“从文件中加载”按钮，再显示“历史加载过的视频”列表。
      * 历史视频项支持“查看 / 读取 / 删除”，读取与删除都增加确认步骤。
      * 通过文件选择器加载新视频后，会自动写入历史并继续复用原有“加载默认房间配置”流程。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [245] 2026-03-26 06:05:00 - 修复按视频配置保存误判无变更并改为折叠式读取菜单

**用户指令**：
> 我这个视频的房间配置似乎保存过，也似乎没保存过；现在提示没有变更保存不了，先看看这个问题。读取的菜单应该是折叠的，展开后出现列表，然后可以查看和选择进行确认后读取。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复当前视频配置保存时对旧配置/跨作用域配置误判“没有变更”的问题，并把读取入口改成折叠展开的配置列表交互。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/VideoRoomConfigManager.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/res/layout/fragment_settings_home.xml、dialogueHistory.md、codexHistory.md
    *   涉及方法：VideoRoomConfigManager.isCurrentVideoConfigFile、SettingsHomeFragment.loadDefaultConfigForSelectedVideo/saveCurrentConfigAs/setConfigListExpanded/refreshConfigListContent/showConfigFileDetails/confirmLoadConfigFile
    *   关键改动：
      * 新增 `VideoRoomConfigManager.isCurrentVideoConfigFile()`，用于判断当前激活配置文件是否属于当前视频目录。
      * 保存按钮的“无变更”拦截仅对“当前视频作用域内的当前配置文件”生效，兼容旧版本配置首次迁移为当前视频新配置的场景。
      * “读取当前视频配置”改为折叠式列表；展开后直接显示当前视频下的配置项。
      * 每个配置项支持“查看”和“读取”两个动作；读取前会二次确认，避免误切换。
      * 切换视频和保存成功后会自动刷新折叠列表内容。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [244] 2026-03-26 05:41:00 - 修复 codexHistory 倒叙规则并整理头尾顺序

**用户指令**：
> Codex History 全乱了，你写到最后去了；我的顺序是倒叙，规则文件没写清楚的话去改下规则文件；头尾现在一堆错误，去改吧。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：明确 codexHistory.md 的维护规则，并把最近被写乱的头尾条目重新整理成倒叙顺序。
    *   修改文件：AGENTS.md、codexHistory.md
    *   涉及方法：codexHistory 条目重排、AGENTS 规则补充
    *   关键改动：
      * 在 `AGENTS.md` 中补充规则：`codexHistory.md` 必须按倒叙维护，新增条目插入到最前面的最新位置，禁止追加到文件末尾。
      * 以条目块为单位重排 `codexHistory.md`，保持每条历史内容不变，仅按标题时间整理为最新在前。
      * 将本次修复记录为最新条目，便于后续继续按倒叙维护。

---

## [242] 2026-03-26 01:18:00 - 新增Hand Landmarker关键点置信度5秒采样结论输出

**用户指令**：
> 请帮我做一个“最小改动但能直接产出结论”的 MediaPipe Hand Landmarker 置信度验证。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不重构现有业务链路的前提下，复用现有手部按钮触发一次 5 秒 confidence probe，对核心点 5/6/8/9/10/12 的 `presence/visibility` 做实际采样、统计，并在 Logcat 直接输出可执行结论。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：HandSmokeTester.startConfidenceProbeSession/onLiveStreamResult/sampleConfidenceProbe/emitConfidenceSummary、MainActivity.setupButtons
    *   关键改动：
      * 在 `HandSmokeTester` 内新增 5 秒 probe session 与统计器，不改现有 `detect()` 主链路。
      * 采样对象固定为第一只手，记录 `timestampMs()`、`handCount`、`handednesses()`、以及核心点 `5/6/8/9/10/12` 的 `presence()/visibility()` 是否存在与数值。
      * 统计输出：`totalResultFrames`、`noHandFrames`、`validHandFrames`，以及每个核心点的覆盖率、均值、最小值、最大值，再输出 `corePointsPresenceCoverage/corePointsVisibilityCoverage`。
      * 按规则自动生成结论 A/B/C，并统一用 `HandPointConfidenceSummary` 作为日志 tag 输出。
      * 复用现有 `按住看手` 按钮：按下时启动一次 5 秒 probe，同时保留原有手点显示模式。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [241] 2026-03-26 01:05:00 - 新增按住显示手点的Hand Overlay调试模式

**用户指令**：
> 这样,放一个按钮,按住时,不再显示现有的pose那套东西,只显示手的这套新点.同样用颜色代表置信度(如果有)
> ok
> 行,开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不改动 YOLOPose 主逻辑的前提下，新增一个“按住只显示手点”的调试模式，用于直接验证 MediaPipe Hand Landmarker 的可视化效果。
    *   修改文件：app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：HandSmokeTester.onLiveStreamResult、DetectionOverlayView.updateHandData/setHandOnlyState/onDraw、MainActivity.setupButtons/refreshOverlayDisplay/applySettings
    *   关键改动：
      * 在底部常规控制栏新增按钮 `btnHandOverlay`，文案为“按住看手”。
      * `HandSmokeTester` 新增 `HandPoint` 数据结构与 `onHandsResult` 回调；在手部结果回调里把全部 21 点坐标回传给 UI。
      * `DetectionOverlayView` 新增 hand-only 模式与手点绘制逻辑：按住按钮时不再走现有 pose 绘制，仅绘制手部关键点。
      * 手点颜色按置信度着色；当前 MediaPipe Hand Landmarker 未直接提供单点置信度时，回退为统一青色显示，满足“如果有则按置信度”的约束。
      * `MainActivity` 用 `OnTouchListener` 实现“按住显示、松开恢复”，并在 `refreshOverlayDisplay/applySettings` 中统一同步 overlay 模式状态。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [240] 2026-03-26 00:00:00 - 修复HandSmokeTester被VideoFeeder.stop提前关闭

**用户指令**：
> 从日志看只有 `HSMOKE|CALL_SITE|...` 没有 `HSMOKE|CALL|...`，要求重新判断根因并直接修复。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 Hand 冒烟验证链路中 `HandSmokeTester` 被 `VideoFeeder.start()->stop()` 预清理链路提前关闭，导致 `detect()` 一进入就返回的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：VideoFeeder.stop、MainActivity.onDestroy
    *   关键改动：
      * 从 `VideoFeeder.stop()` 中移除 `handSmokeTester?.close()`，避免 `start()` 前置 `stop()` 时把手部检测器提前销毁。
      * 将 `handSmokeTester?.close()` 保留到 `MainActivity.onDestroy()`，使其生命周期与页面真正销毁对齐。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [239] 2026-03-26 00:00:00 - 补充HSMOKE分层日志用于Hand冒烟验证

**用户指令**：
> 认为当前只看到初始化日志，不清楚 `detect(bitmap)` 和回调是否真正跑通；要求用更合理的验证方式排查，并开始补日志。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：为 MediaPipe Hand 冒烟链路补齐“调用层/回调层”可观测日志，快速判断断点发生在何处。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：HandSmokeTester.detect/setupHandLandmarker/onLiveStreamResult/onLiveStreamError、VideoFeeder.analyzeRunnable
    *   关键改动：
      * 统一所有手部冒烟日志前缀为 `HSMOKE|...`，便于 logcat 单关键字检索。
      * `VideoFeeder` 在取到 `textureView.bitmap` 后、调用 `handSmokeTester.detect(bitmap)` 前打印：
        * `HSMOKE|CALL_SITE|bitmap=...`
      * `HandSmokeTester.detect()` 入口打印：
        * `HSMOKE|CALL|bitmap=...|ts=...`
      * 初始化日志调整为：
        * `HSMOKE|INIT|...`
      * 结果日志调整为：
        * `HSMOKE|RESULT|hands=...`
        * `HSMOKE|RESULT|firstHandLandmarks=...`
        * `HSMOKE|RESULT|indexVectorNorm=(...)`
      * 异常日志调整为：
        * `HSMOKE|ERROR|...`
        * `HSMOKE|WARN|...`
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [240] 2026-03-26 00:00:00 - 接入触发式pointing解析器并直接显示门命中结果

**用户指令**：
> 基于现有 MediaPipe Hand Landmarker，实现一个触发式 pointing 解析器：按下现有“按住看手”按钮后启动 session，用门矩形列表代替设备列表，在 200ms~1000ms 内根据双指 pointing 轴和多帧证据判断指向目标，并把命中结果直接显示出来。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不改 YOLOPose 主链路的前提下，新增纯业务层 pointing 解析器，并在按钮触发后直接给出门目标命中/未识别结果。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/pointing/TriggeredPointingResolver.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/HandLandmarkerPointingAdapter.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：TriggeredPointingResolver.startSession/updateTargets/submitFrame/cancelSession、HandLandmarkerPointingAdapter.toObservation、HandSmokeTester.detect/onLiveStreamResult、MainActivity.buildPointingTargetRects/startTriggeredPointingSession/handleTriggeredPointingObservation/handleTriggeredPointingDecision
    *   关键改动：
      * 新增纯 Kotlin 核心类 `TriggeredPointingResolver`，实现双指 pointing 轴构造、frameQuality、每目标 frameScore、多帧证据累积、FAST/NORMAL/TIMEOUT 决策，以及未识别原因输出。
      * 新增薄适配层 `HandLandmarkerPointingAdapter`，仅把 `HandLandmarkerResult` 第一只手转换成与目标矩形同坐标系的 `HandObservation`。
      * `HandSmokeTester` 新增结果观测回调与 `timestamp -> bitmap尺寸` 对齐，确保 hand landmarks 能按像素坐标喂给 resolver；保留原有 `HSMOKE` 冒烟链路。
      * `MainActivity` 复用现有“按住看手”按钮：按下时构建门矩形目标列表、启动 pointing session；每个 hand result 到来时提交给 resolver；识别成功后用现有横幅直接显示“命中：房间名(score)”，失败则显示“未识别(原因)”。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [241] 2026-03-26 00:00:00 - 新增pointing调试overlay并接入设置开关

**用户指令**：
> 为现有 `TriggeredPointingResolver` 增加一个只用于调试的 pointing overlay，帮助可视化真正参与判定的数据；开关放到设置界面，要求显示 raw/smoothed 指向线、关键中点、winner/top2 矩形与 expandedRect、分数与接受路径，并在 session 结束后保留最后一帧约 1 秒。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不重写 pointing 算法的前提下，把 resolver 当前真实判定数据可视化，并通过设置页开关控制显示。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/pointing/TriggeredPointingResolver.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：TriggeredPointingResolver.startSession/submitFrame/finalizeRecognized/finalizeUnrecognized/latestDebugSnapshot、DetectionOverlayView.setPointingDebugOverlayEnabled/updatePointingDebugSnapshot/onDraw/drawPointingDebugOverlay、AppSettings.init/setPointingDebugOverlayEnabled、SettingsHomeFragment.onViewCreated、MainActivity.handleTriggeredPointingObservation/handleTriggeredPointingDecision/applySettings
    *   关键改动：
      * 在 `TriggeredPointingResolver` 新增 `PointingDebugSnapshot`、`PointingTargetDebugInfo`，直接复用 resolver 内部真实参与判定的 raw/smoothed 方向、关键中点、top target 分数、expandedRect、acceptPath、valid/noHand 计数等数据，不在 overlay 内重复算一套。
      * `DetectionOverlayView` 增加 pointing debug 绘制层：支持画 raw/smoothed 延长线、tip/dip/pip/mcp/smoothedOrigin 点、所有 target rect、winner/top2 的 expandedRect，以及左上角调试文本；session 结束后按 `holdMs` 保留最后一帧约 1 秒。
      * `AppSettings` 新增 `pointing_debug_overlay_enabled` 持久化开关；`SettingsHomeFragment` 在设置页接入 `显示 pointing 调试 overlay` 开关。
      * `MainActivity` 在每次 `submitFrame` 后把 `pointingResolver.latestDebugSnapshot()` 推给 overlay；在 Recognized/Unrecognized 后以 `holdMs=1000` 保留最后一帧，`applySettings()` 同步设置页开关状态。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [242] 2026-03-26 00:00:00 - 调整pointing overlay按住显示与未识别保留

**用户指令**：
> 只要按住就显示线,未识别后仍然显示

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让 pointing debug overlay 的显示时机与按钮按压态直接绑定，并在未识别后仍保留最后一帧线条直到松手。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：MainActivity.btnHandOverlay.onTouch、MainActivity.handleTriggeredPointingDecision、DetectionOverlayView.updatePointingDebugSnapshot/drawPointingDebugOverlay
    *   关键改动：
      * 按钮松手时立即 `updatePointingDebugSnapshot(null)`，统一由松手清空 overlay。
      * Recognized/Unrecognized 后，如果按钮仍按住，则不再使用 `1000ms` 自动超时；只有未按住时才保留 1 秒。
      * overlay 绘制条件从“仅 session active 才画线”调整为“只要当前快照里仍有 smoothed 指向数据就继续画”，因此未识别后的最后一帧也会保留显示。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [243] 2026-03-26 00:00:00 - 重构按视频分组的房间配置保存与读取

**用户指令**：
> 房间视频和数据备份要重构：每个视频的第一个配置应与视频同名；只有切换视频时才切换房间配置，应用重启不按视频切；点击保存时要识别空房间/没改过，再弹文件名确认保存新配置文件；只有主动读取时，才列出当前视频下的多个配置。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把原来的全局单文件房间配置，改成“按视频分目录、多配置文件”的管理方式，并重做设置页的保存/读取流程。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/data/repository/VideoRoomConfigManager.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/res/layout/fragment_settings_home.xml、dialogueHistory.md、codexHistory.md
    *   涉及方法：AppSettings.init/setActiveRoomConfigPath、RoomRepository.init/switchToConfigFile/saveAsConfigFile/hasMeaningfulConfig/hasUnsavedChanges、VideoRoomConfigManager.currentVideoContext/defaultConfigFileForCurrentVideo/listConfigFilesForCurrentVideo/suggestNextConfigNameForCurrentVideo、SettingsHomeFragment.selectVideoLauncher/loadDefaultConfigForSelectedVideo/saveCurrentConfigAs/confirmOverwriteAndSave、MainActivity.onCreate/onResume
    *   关键改动：
      * 新增 `VideoRoomConfigManager`：按视频名创建 `filesDir/room_configs/<视频名>/` 目录，默认配置为 `<视频名>.Room`，额外配置按 `<视频名>_2.Room`、`<视频名>_3.Room` 递增。
      * `RoomRepository` 从固定 `room_config.json` 改为支持切换当前配置文件；增加当前配置文件、是否为空房间、是否有未保存改动等判断；取消编辑过程中的自动持久化，改为显式保存。
      * `AppSettings` 新增 `activeRoomConfigPath`，用于启动时恢复上一次真实加载的配置文件；应用重启不再根据当前视频自动切换配置。
      * 设置页“选择测试视频”后会自动尝试加载该视频的同名默认配置；若不存在则加载临时空配置并提示“无房间配置文件”。
      * 设置页“保存现有房间配置”先校验“空房间/无改动”，再弹文件名确认，保存到当前视频目录下的新配置文件；若重名则再次确认是否覆盖。
      * 设置页“读取当前视频配置”不再走外部覆盖式恢复，而是列出当前视频下的多个配置文件（带序号）供用户选择加载。
      * `MainActivity` 启动顺序改为先初始化 `AppSettings` 再初始化 `RoomRepository`，并在 `onResume()` 根据最新配置与视频源刷新显示。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [236] 2026-03-25 00:00:00 - 切换为Codex App并同步注意事项状态

**用户指令**：
> 我们写个新日志,更换为codex app,同时告诉我你有没有读取之前的注意事项文件,以及有没有发出通知  
> 后面不要再发通知了,开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：补记一次上下文切换日志，明确当前已切换为 Codex App，并确认注意事项读取与通知策略。
    *   修改文件：dialogueHistory.md、codexHistory.md
    *   涉及方法：tools/dialogue_archive.py append-turn
    *   关键说明：
      * 已读取并对齐注意事项文件：`AGENTS.md`、`dialogueHistory.md` 最新条目、`GeminiHistory.md`。
      * 已记录此前确实发过 commentary 进度通知。
      * 从本条之后，按用户要求，不再额外发送进度通知。

---

## [237] 2026-03-25 00:00:00 - 下载官方Hand Landmarker模型文件

**用户指令**：
> 新任务：仅将 Google 官方 MediaPipe Hand Landmarker 模型文件下载到 `app/src/main/assets/hand_landmarker.task`，不要改业务代码、Gradle、YOLOPose、相机或推理逻辑；若网络异常则只用官方地址排查。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将官方 `hand_landmarker.task` 模型文件落到项目 assets 目录，为后续接入做准备。
    *   修改文件：app/src/main/assets/hand_landmarker.task、dialogueHistory.md、codexHistory.md
    *   涉及方法：tools/dialogue_archive.py append-turn
    *   关键处理：
      * 先诊断当前 Codex 会话外网访问问题，确认 `curl.exe` / `Invoke-WebRequest` 因 Windows TLS/Schannel 凭据链路异常而失败。
      * 进一步验证 Python `urllib` 可正常访问官方模型地址并返回 `200`。
      * 使用 Python 直接从官方固定地址下载模型，自动创建 `app/src/main/assets/` 目录并完成落盘。
      * 校验结果：`app/src/main/assets/hand_landmarker.task` 成功存在，文件大小 `7819105` 字节。

---

## [238] 2026-03-25 00:00:00 - 最小改动接入MediaPipe Hand冒烟验证

**用户指令**：
> 在现有 Android 项目里做一个“最小改动的 MediaPipe Hand Landmarker 冒烟验证”：新增 `tasks-vision` 依赖、新建独立 `HandSmokeTester.kt`、仅在已有 `Bitmap` 预览帧位置加一行 `handSmokeTester.detect(bitmap)`，不要改 YOLOPose 主逻辑，并告知 logcat 如何查看结果。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不改 YOLOPose 主流程的前提下，验证官方 `hand_landmarker.task` 能否在现有视频帧链路中正常跑通。
    *   修改文件：app/build.gradle.kts、app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：HandSmokeTester.detect/setupHandLandmarker/onLiveStreamResult、VideoFeeder.analyzeRunnable/stop、MainActivity.onCreate/onDestroy
    *   关键改动：
      * `app/build.gradle.kts` 增加 `implementation("com.google.mediapipe:tasks-vision:latest.release")`。
      * 新建 `HandSmokeTester.kt`：使用 `HandLandmarker.createFromOptions(...)`、`LIVE_STREAM`、`numHands=1`、三个 confidence 都为 `0.5`；提供 `detect(bitmap: Bitmap)`；在结果回调打印：
        * 手数量 `hands=...`
        * 第一只手 landmark 数量 `firstHandLandmarks=...`
        * 食指方向归一化向量 `indexVectorNorm=(x=..., y=..., z=...)`
      * 在 `VideoFeeder` 的 `textureView.bitmap` 成功取得后，新增一行 `handSmokeTester?.detect(bitmap)`。
      * 在 `MainActivity` 初始化 `HandSmokeTester` 并挂载给 `VideoFeeder`，销毁时随 `VideoFeeder.stop()` 一起释放。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [235] 2026-03-06 03:20:00 - 1.6.2扩展ORIGIN_EXIT死锁解锁路径

**用户指令**：
> 按 1.6.2：把 ANOMALOUS_ORIGIN 从 INIT-only 扩展到 ORIGIN_EXIT 主路径；新增 `ORIGIN_EXIT_LEDGER_BLOCK` 和 `ORIGIN_EXIT_ANOMALOUS_COMMIT` 日志；其余 1.6.1 规则不变。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“fromCount==0 时 ORIGIN_EXIT 长期被 ledgerBlock 卡死”的问题，确保在出门主路径也可显式解锁账本。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmRegistry.allVersionIds/create、PresenceAlgorithmV1_1_0_B03021639.processFrame(ORIGIN_EXIT分支)
    *   关键改动：
      * 新增版本 `V1.6.2(B03060320)` 并纳入主线可选版本与分发。
      * 将 `1.6.2` 绑定到 `enableDoorOriginExit/enableUnifiedLedgerV16/enableAnomalousLedger`。
      * 在 ORIGIN_EXIT 被账本阻断时新增专用日志：
        * `ORIGIN_EXIT_LEDGER_BLOCK`（打印 from/to/door、fromCount、originScore/second/margin、nearDoorAmbiguous、originBlockedReason）。
      * 在 ORIGIN_EXIT 分支新增异常提交：
        * 条件：`fromCount==0` + `score>=0.70` + `margin>=0.15` + 非歧义 + 去重未命中；
        * 行为：提交 `ANOMALOUS_ORIGIN`，落账 `living+=1`、`from不扣`；
        * 提交日志：`ORIGIN_EXIT_ANOMALOUS_COMMIT`（含 `reason=ORIGIN_EXIT_LEDGER_BOOTSTRAP` 与 `beforeCounts->afterCounts`）。
      * 保持 1.6.1 既有规则不变：`events=[]` 回滚、INIT/identityReset 不落账、ANOMALOUS_SPAWN 严格触发与去重。

---

## [234] 2026-03-06 02:25:00 - 1.6.1异常事件解死锁与防刷账本

**用户指令**：
> 按最终方案实现 1.6.1：保持 `events=[]` 不改账本，补齐 `originWindow` 上限、`originBlockedReason`、异常事件严格触发与去重，直接编码。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在 1.6 主框架下解开“账本全零导致 ledgerBlock 卡死”，并避免异常路径刷账本。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：PresenceEventReason(enum)、PresenceAlgorithmRegistry.allVersionIds/create、PresenceAlgorithmV1_1_0_B03021639.processFrame、resolveDoorOriginToLiving、appendDoorOriginSample、resetStateAfterCommittedEvent、resetTrackIdentityState、isOriginInitWindowExpired、buildOriginBlockedReason
    *   关键改动：
      * 新增版本 `V1.6.1(B03060220)` 并注册为最新可选。
      * 新增事件原因：`ANOMALOUS_ORIGIN`、`ANOMALOUS_SPAWN`（并预留 `LEDGER_BOOTSTRAP` 枚举）。
      * `originWindow` 增加硬上限：`500ms / 12帧`，避免无限等待。
      * `originBlockedReason` 明确化：`blockedByLowScore / blockedByMargin / nearDoorAmbiguous / blockedByLedger / NO_ORIGIN_DECISION`。
      * `ANOMALOUS_ORIGIN` 严格触发：`fromCount==0` 且 `score>=0.70` 且 `margin>=0.15` 且非门歧义；落账策略 `living +1, from不扣`。
      * `ANOMALOUS_SPAWN` 严格触发：`stable>=5帧` 且 `distToNearestDoor>=3*nearDoorDist` 且 origin 不可提交；落账 `room +1`。
      * 去重改为空间+时间键：
        * spawn：`(roomId, 500ms桶)` + `roomCooldown=1000ms`
        * origin：`(fromRoom,doorId,500ms桶)`
      * 保持硬约束：`events.isEmpty()` 且 counts 变化时回滚并打印 `ledgerGuardRevert=true reason=NO_EVENT_COUNT_DELTA`。

---

## [233] 2026-03-06 02:00:00 - 修复TFLite GPU并发崩溃并补充Wiki

**用户指令**：
> 刚刚启动几秒钟挂了一次但是重新启动后就没有挂了...  
> 我需要你把这一次的错误写到wiki里面去...只有遇到这种问题的时候再打...然后修改，你就进行吧。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 `pthread_mutex_lock called on a destroyed mutex`（TFLite GPU JNI 崩溃），并将故障特征与抓取方法写入 Wiki。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、wiki.md、codexHistory.md
    *   涉及方法：VideoFeeder.analyzeRunnable.run、VideoFeeder.submitInferenceTask（新增）、VideoFeeder.stop
    *   关键改动：
      * 将“每100ms新建线程推理”改为“单线程串行推理执行器”。
      * 增加 `inferenceInFlight` 防重入，上一帧未完成时直接跳过新任务，避免并发 `Interpreter.run()`。
      * 日志收敛为异常触发：仅当连续回压达到阈值时输出 `inferenceBackpressure`，避免常态刷屏。
      * Wiki 新增 `1.8 TFLite GPU 并发崩溃（destroyed mutex）`，包含现象、根因、修复策略与 logcat 命令。

---

## [232] 2026-03-06 01:20:00 - 1.6.0账本收口首版（INIT不落账+无事件变更回滚）

**用户指令**：
> 1.6 AI建议:目标...（统一提交与落账）  
> 不需要搞什么回滚。如果有问题，我会用git来回滚,只要你没增加文件。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：按 1.6 思路先收口“存在账本只能由事件提交改动”，消除 `INIT/identity reset` 引发的隐形 `+1/-1`。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmRegistry.create、PresenceAlgorithmV1_1_0_B03021639.processFrame、formatPresenceDelta
    *   关键改动：
      * 新增版本 `V1.6.0(B03060120)`，加入主线可选版本与创建分发。
      * `V1.6.0` 继承 `ENTER_BLIND_ONLY` 与 Door-Origin 出门能力（`enableDoorOriginExit=true`）。
      * 新增开关 `enableUnifiedLedgerV16`，在 `V1.6.0` 生效：
        * `INIT` 阶段改为 `INIT_BIND_ONLY`，仅绑定 track->room，不再直接 `addPresence(+1)`。
        * `ORIGIN_INIT` 命中时改为 `ORIGIN_INIT_BIND_ONLY`，仅做来源解释与状态绑定，不直接落账。
      * 增加“无事件人数变化保护”：
        * 当 `events.isEmpty()` 且账本发生变化时，自动回滚到帧前账本，并输出  
          `ledgerGuardRevert=true reason=NO_EVENT_COUNT_DELTA delta={...}`。
      * 结果：保证 `events=[]` 不再导致账本变化，账本变更统一由显式提交事件驱动。 

---

## [231] 2026-03-06 00:55:00 - 播放往复回跳诊断日志增强与Wiki排查流程

**用户指令**：
> 好的，你着手处理吧，然后。只有等下一次，遇到了才能知道。怎么录log了,把它写到wiki里面去,带上这个bug本身简述.wiki项目:"播放往复回跳"

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：为“播放往复回跳（画面卡住来回跳、声音可能继续）”增加播放器链路诊断日志，并将抓取流程与判读口径写入 Wiki。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、wiki.md、codexHistory.md
    *   涉及方法：MainActivity.onSeekBackwardRequested、onSeekForwardRequested、togglePause、logPlayerDiag；VideoFeeder.setupMediaPlayer(setOnSeekCompleteListener/setOnPreparedListener)、setStillMode、pause、resume、seekByMs、computeTemporalAdvanced、stop、logPlayerDiag
    *   关键改动：
      * `MainActivity` 增加 `RoomPlayerDiag` 上层日志：
        * `togglePause` 记录前后 `playState`、`position`、`isPlaying`；
        * `±1帧/±5s` seek 记录动作类型、seek前后位置、播放态、逐帧seek调试值。
      * `VideoFeeder` 增加底层日志：
        * seek请求参数（`delta/target/mode/captureAsStep`）；
        * seek完成回调状态（`pos/isPlaying/pending`）；
        * pause/resume/setStillMode 状态切换；
        * `playLoopAnomaly`：播放中位置不前进或倒跳时输出 `delta/stallCount`。
      * 新增 `wiki.md` 章节 `1.7 播放往复回跳（播放器卡死）排查`：
        * bug现象简述；
        * 必开开关；
        * 仅播放器相关的 logcat 抓取命令；
        * 关键字段与快速判读口径。

---

## [230] 2026-03-06 00:20:00 - 1.5.3 Door-Origin出门归因重构并下线1.5.2

**用户指令**：
> 1.这个是不是只管出门?管哪些出门? 2.1.5.2不需要保留.  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：按 1.5.3 方案接入“Door-Origin / Door-Crossing”出门归因主链路，覆盖“出到客厅”场景；同时将 1.5.2 从可选版本下线（仅保留归档）。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceBaselineArchive.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceEventReason 枚举、PresenceAlgorithmRegistry 版本分发、PresenceAlgorithmV1_1_0_B03021639.processFrame、resetStateAfterCommittedEvent、resetTrackIdentityState、appendDoorOriginSample、resolveDoorOriginToLiving、computeDoorOriginNormalTowardRoom
    *   关键改动：
      * 新增版本 `V1.5.3(B03052330)`，并从 `allVersionIds` 中移除 `1.5.2`，`1.5.2` 仅保留在归档列表。
      * 新增事件原因 `ORIGIN_SWITCH`，用于区分门源归因触发的“出到客厅”。
      * 在主流程中新增 Door-Origin 窗口：
        * 每帧追加地面点样本 `doorOriginSamples`；
        * 对连接客厅的候选门计算：门口接近积分 `P` + 跨门槛方向性 `C`；
        * 评分 `Score = 0.7*C + 0.3*P`，并执行低分/小优势/账本阻断三重门控。
      * 接入两条提交路径：
        * `INIT` 阶段：CONFIRMED 且落在客厅时，优先回溯门源（支持次卧/入户等来源）；
        * 常规阶段：当前在非客厅且当前帧进入客厅时，优先走 `ORIGIN_SWITCH` 提交。
      * 新增 `INIT_WAIT_ORIGIN`（最多 8 帧）以避免 lock 帧“探头未出门”就误提交。
      * 事件级重置与身份重置均清理 Door-Origin 缓存，避免跨人串证据。

---

## [229] 2026-03-05 23:45:00 - 移除播放记录功能并回归logcat抓取

**用户指令**：
> 看来不需要了,这次根本没出发出门事件.删掉那个记录播放功能吧,我们还是用logcat来做抓你要的东西.告诉我怎么抓就行  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：移除“开始记录播放/停止记录播放”功能，避免与进出门排查混用，统一回归 `logcat` 抓取诊断。
    *   修改文件：app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：setupButtons、onCreate（pose 回调 runOnUiThread）、pauseForSmartMatchAnomaly、onSeekBackwardRequested、onSeekForwardRequested、refreshDebugPanelButton、togglePause、hardRestartPlayback
    *   关键改动：
      * 删除布局按钮 `btnPlayTrace`。
      * 删除 `MainActivity` 中播放记录相关字段与方法：
        * `isPlayTraceRecording/playTrace*`
        * `appendPlayTrace/buildPlayTraceReport/togglePlayTraceRecording/refreshPlayTraceButton/maybeAppendPlayTraceHeartbeat`
      * 移除所有播放记录写入调用（心跳、切换事件、扣减兜底、seek、暂停、硬重启、智能匹配暂停）。
      * 收口此前为记录功能新增的 `source` 参数，恢复 `togglePause/onSeekForwardRequested/onSeekBackwardRequested` 的简洁签名与调用。

---

## [228] 2026-03-05 23:10:00 - 新增播放链路录制按钮并停止即复制

**用户指令**：
> 我觉得这个不是因为它引起的，你先去掉这个东西.在调试面板里面加个按钮(开始记录播放,点击后变成停止记录播放).然后你想一下需要哪些信息放到这里面来，我把它记录好之后如果下次遇到了再发给你。  
> 停止后就直接复制,不需要长按.开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：移除播放按键防抖，并在调试面板场景新增“播放链路录制”按钮，支持一键开始/停止，停止即自动复制完整诊断文本。
    *   修改文件：app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：setupButtons、togglePause、appendPlayTrace、buildPlayTraceReport、togglePlayTraceRecording、refreshPlayTraceButton、maybeAppendPlayTraceHeartbeat、onSeekBackwardRequested、onSeekForwardRequested、hardRestartPlayback、onCreate(视频帧回调内runOnUiThread)
    *   关键改动：
      * 新增按钮 `btnPlayTrace`（开始记录播放/停止记录播放），仅在调试面板开启时显示。
      * `togglePlayTraceRecording` 在“停止记录”时直接复制报告到剪贴板，不再依赖长按。
      * 移除 `togglePause` 中的连点防抖逻辑，保留状态切换并追加操作来源日志。
      * 录制内容增强：
        * 手动/自动暂停来源（`manual_toggle`、`auto_pause_switch_event`、`auto_pause_count_delta`、`smart_match_pause`）。
        * seek 来源（点击/长按）。
        * 房间切换与扣减兜底判定摘要（`switch_event`、`count_delta_fallback`）。
        * 低频播放心跳（`heartbeat`：`mpPlaying/posDelta/frameDelta`），用于定位“声音在走但画面不动”。
      * 报告结构包含 `trace + presenceRecent + recentFrames`，便于一次复制后直接复盘。

---

## [227] 2026-03-05 22:35:00 - 1.5.2：移除identity扣减并补充异常最近帧logcat

**用户指令**：
> 那我们要做两件事，第一件事就是把扣减先关掉。然后就是确定一下为什么出门1.5的逻辑没有生效。  
> 不需要这么搞。你先清除掉不该有的逻辑然后把版本号记为一点5.2。然后。在log cat里面去记录最近帧的情况，我会手动复制给你。看一下为什么没有触发？

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：去除 1.5.1 中不应存在的 identity reset 直接扣减；升级为 1.5.2；在 logcat 增加可复制的最近帧诊断输出，便于排查“为何未触发出门逻辑”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceBaselineArchive.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.processFrame（identityReset分支）、PresenceAlgorithmRegistry.create/resolve/buildOptions、MainActivity.onCreate（pose回调）、MainActivity.logPresenceAnomalyDiagnostics、MainActivity.hardRestartPlayback
    *   关键改动：
      * **移除 identity reset 直接扣减**：删除 `identityResetApplied` 分支中的 `addPresence(beforeRoomId, -1)`，仅保留状态重置与诊断文本。
      * **版本升级到 1.5.2**：
        * 新增 `VERSION_V1_5_2_B03052250`
        * 加入 `allVersionIds`（成为最新）
        * `create` 主线映射支持 `1.5.2`
        * `PresenceBaselineArchive.ACTIVE_VERSION_ID` 指向 `1.5.2`
      * **1.5.2 策略对齐**：`blindPendingPolicy` 中 `1.5.2` 与 `1.5.1` 一致，采用 `ENTER_BLIND_ONLY`。
      * **增加 logcat 最近帧诊断**（仅 `pauseSwitchLog=true` 时）：
        * 当出现 `identityResetApplied/pendingDisabled/pendingDropped/ledgerBlockApplied` 异常原因，输出 `presenceAnomaly` 汇总行；
        * 同步输出 `presenceRecent` 与 `recentFrame` 列表，方便人工复制。
      * **重播重置清理**：`hardRestartPlayback` 增加异常日志去重状态清空，避免新回合诊断被抑制。

---

## [226] 2026-03-05 22:05:00 - 增加“无事件扣减”暂停兜底与原因归因日志

**用户指令**：
> 有扣减,但是没有暂停  
> 可以，没问题。嗯。但是这样的日志够吗？你知道这是因为什么原因扣减，这样打的话。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“presenceCounts 发生扣减但无 events，导致未触发切换暂停”的遗漏，并补齐可归因日志。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity.onCreate（poseAnalyzer 回调内 runOnUiThread）、extractNegativeCountDelta、formatNegativeCountDelta、resolveCountDeltaLikelyCause、hardRestartPlayback
    *   关键改动：
      * 新增“计数扣减兜底暂停”：
        * 条件：`presenceResult.events` 为空，且相对上一帧存在负向人数差分；
        * 触发：在 `pauseOnSwitch=true` 且 `playStateBefore!=PAUSED` 下执行自动暂停。
      * 新增归因解析：
        * 从 `rejectedReasons` 中优先提取 `identityResetApplied` 的 `resetReason/track/gap/jump`；
        * 次级识别 `pendingDropped/pendingDisabled` 与 `ledgerBlockApplied`。
      * 统一写入 `RoomPauseSwitch` 日志：
        * `switch=COUNT_DELTA_FALLBACK`、`deltaMap`、`likelyCause`、`playStateBefore/After`、`shouldAutoPause/didAutoPause`。
      * 在 `hardRestartPlayback` 中清空 `lastPresenceCountsForPause`，避免重启后误判差分。

---

## [224] 2026-03-05 21:45:00 - 切换事件统一纳入暂停触发（含扣减类切换）

**用户指令**：
> 这样你先把这个做成一个暂停事件也就是说切换的时候它会暂停。因为他出门扣减肯定也算是一次暂停对吧？所以说怎么你想想把它做进我们之前的暂停切换房间逻辑里面去。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将扣减类切换事件（如盲区 `PENDING_CONFIRMED`）纳入统一“切换自动暂停”流程，避免仅在 `PLAYING` 时才触发导致的漏停。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity 中 poseAnalyzer 回调的切换暂停判定段
    *   关键改动：
      * `shouldAutoPause` 从 `playStateBefore == PlayState.PLAYING` 调整为 `playStateBefore != PlayState.PAUSED`。
      * 保持原有 `RoomPauseSwitch` 日志链路不变，仍输出 `shouldAutoPause/didAutoPause/playStateBefore/playStateAfter/reason` 等字段，便于验证扣减切换是否进入暂停流程。

---

## [226] 2026-03-05 21:24:00 - 新增V1.5.1组合策略（恢复进盲区pending）

**用户指令**：
> ok,开始1.5.1吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提供 1.5.1 对照版本，恢复“进不可视房间”能力，同时保留 1.5 的其它主线逻辑，便于与后续 1.5.2 比较。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceBaselineArchive.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmRegistry.create、PresenceAlgorithmV1_1_0_B03021639.processFrame、PresenceAlgorithmV1_1_0_B03021639.shouldDisableBlindPending
    *   关键改动：
      * 注册新版本 `V1.5.1(B03052210)`，并加入可选版本列表（最新）。
      * 主线创建逻辑按选择输出 runtimeVersion：
        * 选择 `1.5.1` 时输出 `V1.5.1`；
        * 其余主线兼容入口维持 `V1.5.0`。
      * 在算法内新增盲区 pending 策略枚举：
        * `LEGACY`：历史行为；
        * `DISABLE_BLIND`：1.5.0（禁用盲区子房间 pending）；
        * `ENTER_BLIND_ONLY`：1.5.1（仅允许“可视房间 -> 盲区房间”的 pending）。
      * 对 `1.5.1`：
        * `to=OUTSIDE` pending 仍允许；
        * `to=盲区` 且 `from=可视` 允许（恢复进次卧）；
        * 其它盲区 pending 继续禁用并输出日志：
          * `pendingDisabled=true mode=ENTER_BLIND_ONLY`
          * `pendingDropped=true mode=ENTER_BLIND_ONLY`
      * `PresenceBaselineArchive.ACTIVE_VERSION_ID` 同步至 `V1.5.1`。

---

## [225] 2026-03-05 21:05:00 - 1.5禁用盲区子房间PENDING_CONFIRMED旧路径

**用户指令**：
> 那就改啊,1.3应该只是被归档了

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免 `V1.5` 继续沿用 `V1.3` 的盲区子房间“消失确认(PENDING_CONFIRMED)”扣减/加人路径。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.processFrame
    *   关键改动：
      * 新增 `disableBlindPendingForV15` 开关（按 `versionId == V1.5.0` 判断）。
      * 在 pending 建立阶段：
        * 若 `V1.5` 且目标为盲区子房间（`toRoomId != OUTSIDE_ROOM_ID`），直接禁用并清理该 track 的 pending；
        * 输出日志：`pendingDisabled=true mode=V1.5 ...`。
      * 在 pending 处理阶段：
        * 若 `V1.5` 且 pending 目标非 `OUTSIDE`，直接丢弃，不再进入 `PENDING_CONFIRMED` 提交；
        * 输出日志：`pendingDropped=true mode=V1.5 ...`。
      * 对 `to=OUTSIDE` 的 pending 流程保持不变。

---

## [224] 2026-03-05 10:12:00 - 清理废弃1.4残留并统一主线版本为1.5

**用户指令**：
> 只是看看有没有什么残留,然后后面改门判断的1.4改为1.5

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：彻底清理废弃 1.4 残留代码，避免继续影响编译与运行；并将主线门判断版本命名与运行标签统一到 1.5。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceBaselineArchive.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.processFrame、PresenceAlgorithmRegistry.create、PresenceAlgorithmRegistry.resolveVersionId
    *   关键改动：
      * 删除 `PresenceEventReason.ORIGIN_INFERRED`。
      * 删除 `PresenceAlgorithmV1_1_0_B03021639.kt` 中 1.4 Door-Origin 初始化归因分支与配套函数链：
        * `inferOriginDoorToLiving`
        * `buildOriginWindowPoints`
        * `resolveDoorNormalTowardRoom`
        * 以及对应常量和数据结构。
      * 主线版本统一为 `V1.5.0(B03041530)`：
        * `allVersionIds` 仅保留 `V1.5.0(B03041530)` 作为可选运行版本；
        * `V1.3.4(B03041455)` 下沉至归档列表；
        * `create` 主线运行标签统一输出 `V1.5.0(B03041530)`。
      * `PresenceBaselineArchive.ACTIVE_VERSION_ID` 同步切换为 `V1.5.0(B03041530)`。

---

## [225] 2026-03-04 22:45:00 - 新增 INIT 阶段 Door-Origin 归因（仅脚中心）

**用户指令**：
> ai: 只用这两个信号（门口接近积分 + 穿门槛方向性）...  
> 我是说新的应该是1.4,刚才那个改成1.3.5,然后开始编码

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不推翻现有 `ss -> e -> eth` 主框架下，为“新目标首次锁定到客厅”补充门来源归因，避免无事件初始化与错误来源。
    *   修改文件：`app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt`、`app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt`、`codexHistory.md`
    *   涉及方法：`PresenceEventReason`、`processFrame`（INIT 分支）、新增 `inferOriginDoorToLiving`、`buildOriginWindowPoints`、`resolveDoorNormalTowardRoom`
    *   关键改动：
      * 新增事件原因 `ORIGIN_INFERRED`，用于标识“初始化归因触发”的房间切换事件。
      * 在 `state.currentPresenceRoomId == null` 且 `isConfirmedNow && polygonRoomId=Living` 时：
        * 先执行 Door-Origin 归因（仅用脚中心历史窗口）：
          * 候选筛选：`dd_min <= Dmax`
          * 接近积分：`P_d = avg(clip(1-dd/Dmax,0,1))`
          * 方向性：`C_d = max(Cross, OutTrend)`，其中 `Cross` 使用 `s(t-W)>=S0 && s(t)<=-S0`，`OutTrend = max(clip(-Δs/V0,0,1))`
          * 合成：`Score = α*C + (1-α)*P`
          * 置信条件：`Score >= minScore` 且 `Top1-Top2 >= margin`
          * 账本守恒：`fromRoom != outside` 时要求 `presenceCounts[fromRoom] > 0`
        * 归因成功则直接提交 `fromRoom -> Living`（`ORIGIN_INFERRED`），失败才回退到原 `INIT room=living`。
      * 增加归因日志：`INIT_ORIGIN_OK / INIT_ORIGIN_BLOCK / INIT_ORIGIN_UNKNOWN`，含 `P/C/score/ddMin` 便于复盘。
      * 参数常量（当前为代码常量）：`W=2`、`S0=0.3*nearDoorDist`、`V0=0.5*S0`、`Dmax=2*nearDoorDist`、`α=0.7`、`margin=0.15`、`minScore=0.5`。

---

## [223] 2026-03-04 15:35:00 - 修复播放连点导致画面来回震动

**用户指令**：
> 有时多点了几次播放,视频就来回震动,没法继续播放.但是声音还在走

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复播放键快速连点时出现“画面反复跳动但音频继续”的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md
    *   涉及方法：MainActivity.togglePause、VideoFeeder.clearStepSeekTransientState
    *   关键改动：
      * `MainActivity.togglePause` 新增 280ms 防抖（`SystemClock.elapsedRealtime()`），防止一次连点触发多次状态连跳。
      * 状态切换前统一 `stopSeekHold()`，避免长按逐帧 seek 任务在状态切换后继续干扰。
      * 切回 `PLAYING` 时调用 `videoFeeder.clearStepSeekTransientState()`，清空逐帧步进与 +10ms 补偿残留。
      * `VideoFeeder` 新增 `clearStepSeekTransientState()`：重置 `pendingForwardNudge/pendingSeekState/lastStepSeekDebug`，避免历史 seek 残留继续拉扯画面。

---

## [222] 2026-03-04 15:20:00 - 增加身份断裂重置与账本守恒拦截

**用户指令**：
> gpt的回复,注意看下注意事项,然后开始吧...  
> 1) Identity reset（处理 trackId 复用串人）  
> 2) Event-level reset（处理“刚切完又反向”残留）  
> 3) 账本守恒硬保护（先上硬拦 + 日志）

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 trackId 复用导致“串人反向切换”与提交后缓存残留导致“刚切完又反向”的问题，并增加账本守恒硬保护。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：processFrame、evaluateVisibleEnterByScore、applyTransition、resetStateAfterCommittedEvent、resetTrackIdentityState、buildIdentityResetReason
    *   关键改动：
      * 新增 **Identity reset**：
        * 触发条件：`gapFrames >= 18` 或 `jumpDist >= 0.25`（支持 `GAP/JUMP/GAP+JUMP` 诊断）。
        * 触发后：清空该 track 的 room/evidence/candidate/stableDoor/groundHistory/lastScored/lastSwitch/hold 等状态，并移除 pending。
        * 同时对旧 `currentRoom` 做一次 `-1`，再按首帧重定位流程重新初始化，避免串人继承旧账本。
      * 新增 **Event-level reset**：
        * 每次 `applyTransition` 成功后执行，清空证据与跨帧缓存（候选、evidence、hold、groundHistory、stableDoor、lastScored），保留 `lastSwitch` 供 anti-bounce 使用。
      * 新增 **账本守恒硬保护**：
        * 对非 `outside` 相关转移，若 `presenceCounts[fromRoom] == 0`，直接阻断该次转移。
        * 输出日志：`ledgerBlockApplied=true blockReason=FROM_COUNT_ZERO fromCount=...`，并附带候选/ss/e 上下文。
      * 提交链路接入：
        * `VISIBLE_SWITCH` / `VISIBLE_POLYGON_SYNC` / `PENDING_CONFIRMED` 三条转移路径都改为先检查 `applyTransition` 返回值，再决定是否写入 `lastSwitch` 和更新 `currentRoom`。
      * 调试字段补齐：
        * `identityResetApplied/resetReason/gapFrames/jumpDist/roomNowBefore/roomNowAfter`
        * `eventResetApplied=true lastSwitchKept=true`
        * `ledgerBlockApplied/blockReason/fromCount`

---

## [202] 2026-03-04 15:08:00 - 修复 V1.3.4 编译错误（min 导入缺失）

**用户指令**：
> e: ...PresenceAlgorithmV1_1_0_B03021639.kt:625:17 Unresolved reference 'min'.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 `ENTER_VISIBLE V2.1` 新代码引入的编译错误。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：无（import 修复）
    *   关键改动：
      * 在文件顶部补充 `import kotlin.math.min`，解决 `min(...)` 解析失败。

---

## [201] 2026-03-04 15:02:00 - V1.3.4：ENTER_VISIBLE V2.1 解耦 dpsE 绑死

**用户指令**：
> 按 GPT 评审方案继续：在不改 ss->e->eth 主框架下，修复“偏早/漏检并存”；先备份 1.3.3，再推进 1.3.4。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：仅优化 `DOOR:ENTER_VISIBLE` 链路，减少“首帧冲高偏早”与“过门后 dpsE 塌陷导致不过线”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceBaselineArchive.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、MainActivity.compactPresenceDecisionTextForPanel、MainActivity.buildPresenceShortKeyLegend、RoiLogAggregator.snapshotForPanel、PresenceAlgorithmRegistry.create
    *   关键改动：
      * `V2.1` 近门因子改造（仅 ENTER_VISIBLE）：
        * `phi = min(insideScore, inwardTrendScore)`
        * `proxFactor = (1-phi)*dpsE + phi*max(dps, proxFloor)`
        * `proxFloor = 0.35`
        * `enterCrossPart = proxFactor * crossScore`
      * ENTER 单帧分更新：
        * `enterSwitchScore = 0.8*enterCrossPart + 0.2*(poseGate*poseTransitionScore)`
      * 参考尺度调优（抑制首帧冲高）：
        * `S_ref`: `0.5*nearDist -> 0.8*nearDist`
        * `V_ref`: `1.0*enaMin -> 1.3*enaMin`
        * `R_ref` 保持不变。
      * 新增调试字段并接入压缩日志与 schema：
        * `eph/epf/epfl/ecp`（`enterPhase/enterProxFactor/enterProxFloor/enterCrossPart`）
      * 版本升级：
        * 新增并切换主线版本 `V1.3.4(B03041455)`；
        * `PresenceBaselineArchive.ACTIVE_VERSION_ID` 同步为 `V1.3.4(B03041455)`。

---

## [200] 2026-03-04 14:32:00 - ENTER_VISIBLE改为门洞穿越主证据并修复遮挡低分

**用户指令**：
> 开始吧（按评审方案落地：不推翻 ss->e->eth 框架，仅改 ENTER_VISIBLE，加入门洞穿越证据与有效姿态置信）

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不改 EXIT/盲区分支的前提下，降低 ENTER_VISIBLE 的“提前进门/遮挡进不去/末段断续不过线”问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、computeEffectivePoseConfidence、MainActivity.compactPresenceDecisionTextForPanel、MainActivity.buildPresenceShortKeyLegend、RoiLogAggregator.snapshotForPanel
    *   关键改动：
      * 仅在 `DOOR:ENTER_VISIBLE` 链路引入门洞穿越分：
        * `insideScore`（门内进度）
        * `inwardTrendScore`（向内推进）
        * `passBySuppress`（贴门横走抑制）
        * `crossScore = inside * inward * passBy`
      * 新进入单帧分（仅 ENTER_VISIBLE）：
        * `enterSwitchScore = 0.8*(dpsEnter*crossScore) + 0.2*(poseGate*poseTransitionScore)`
      * `poseGate` 改用 `poseEffectiveConfidence`（仅统计 `>= exitPosePointMinConfidence` 的有效点均值），不再被下半身低置信直接拖穿。
      * ENTER_VISIBLE 确认帧改为独立常量 `ENTER_VISIBLE_CONFIRM_FRAMES=2`（不改 `eth/beta`）。
      * EXIT_TO_LIVING 与盲区门 stable/pending 分支保持不变。
      * 调试日志新增并压缩输出：`pacE/ins/itr/pbs/crs/ess/sRef/vRef/rRef`，并同步 schema。

---

## [221] 2026-03-04 05:30:00 - 房间切换暂停提示追加事件偏差（±ms）

**用户指令**：
> 切换房间的怎么没有显示+-ms  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：为“检测到房间切换 … 已自动暂停/已停止+1帧长按”提示补充与标注事件的时间偏差（±ms）。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * MainActivity.runOnUiThread（检测结果处理分支）
      * MainActivity.nearestRuntimeDeltaMs（复用）
      * MainActivity.appendRuntimeEventsForValidation（调用时机前移）
    *   关键改动：
      * 在切换提示文案中追加 `偏差=+/-xxxms`（无可比事件时显示 `偏差=无可比事件`）。
      * 复用现有事件类型映射与最近偏差计算，不改变智能匹配暂停逻辑。
      * `RoomPauseSwitch` 日志补充 `switchType` 与 `offset` 字段，便于复盘。

---

## [220] 2026-03-04 05:20:00 - 智能暂停关闭时仍执行事件匹配与斜线状态更新

**用户指令**：
> 没有启用智能暂停的时候，进度条也应该按事件匹配并进行斜线。  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：解除“事件匹配状态更新”对智能暂停开关的依赖，保证关闭智能暂停后，进度条已匹配刻度仍可正常变为斜线。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * MainActivity.maybeRunSmartMatchValidation
      * MainActivity.handleRuntimeEventMatching
    *   关键改动：
      * `maybeRunSmartMatchValidation` 不再因 `isSmartMatchPauseEnabled=false` 直接返回，改为始终执行匹配扫描。
      * 新增 `pauseEnabled` 分支：
        * 关闭时：仅更新匹配状态并刷新 UI；
        * 开启时：保留原异常暂停/漏匹配扫描逻辑。
      * `handleRuntimeEventMatching` 新增 `pauseEnabled` 参数；
        * 关闭时对“无匹配/重复匹配”不触发暂停与诊断复制，仅继续匹配流程；
        * 匹配成功路径保持不变（可更新已匹配事件状态）。

---

## [219] 2026-03-04 05:12:00 - 仅ENTER_VISIBLE启用dps非线性压缩（gamma=3）

**用户指令**：
> GPT建议：3个早触发case里dd/nearDist≈1.10~1.20导致dps仍高，建议仅对DOOR:ENTER_VISIBLE做dps压缩：dps_enter=pow(dps_linear,gamma)，默认gamma=3；EXIT不动；日志补dpsEnter和gamma。  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：降低“进子房间”在 nearDist 外侧软尾区的提前触发，保持 `eth/beta` 与统一积分框架不变。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore
      * MainActivity.toReadablePresenceDecision
      * MainActivity.buildPresenceShortKeyLegend
    *   关键改动：
      * 新增常量 `ENTER_VISIBLE_DPS_GAMMA=3.0`。
      * 仅在 `DOOR:ENTER_VISIBLE` 候选中，计算 `dpsEnter = dps^gamma`。
      * `dpsEnter` 只替换进入链路的 `nearGateForPose` 和 `doorAssist` 输入；`EXIT_TO_LIVING` 与盲区分支不变。
      * 日志新增：`dpsEnter`、`enterDpsGamma`（短键 `dpsE`、`edg`）。

---

## [218] 2026-03-04 05:05:00 - 可视门链路新增反向短窗抑制，缓解“刚出又进”回弹

**用户指令**：
> 确认 P2 按“反向短窗抑制”做：仅在 lastSwitch 的反向、且 dt<=bounceWindowMs 内，对该反向候选的 ss（或 e 增量）乘衰减系数；过窗口不衰减。  
> 不动 eth/beta、不动盲区分支、不改主框架。  
> 加日志：bounceApplied/dt/bounceFactor/lastSwitch/ssBeforeAfter。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：降低 `V1.3.2` 中“可视门切换后短时间反向回弹”的触发概率，避免 `入户->客厅` 后马上 `客厅->入户`。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt
      * codexHistory.md
    *   涉及方法：
      * PresenceTrackObservation（新增 `timestampMs`）
      * MainActivity 中 PresenceTrackObservation 构建
      * PresenceAlgorithmV1_1_0_B03021639.processFrame
      * PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore
      * PresenceAlgorithmV1_1_0_B03021639.recordLastSwitch（新增）
      * MainActivity.toReadablePresenceDecision / buildPresenceShortKeyLegend
    *   关键改动：
      * 在可视门积分链路中引入“反向短窗抑制”：
        * 条件：当前候选与 `lastSwitch` 反向，且 `dtMs <= 900ms`；
        * 行为：`switchScore = rawSwitchScore * bounceFactor`，`bounceFactor` 随 `dt` 线性增长（最小 0.20，窗口末端恢复 1.0）。
      * 不改 `eth/beta`，不改盲区 `pending/stableDoor` 分支。
      * 记录每次切换 `lastSwitch`（from/to/door/timestamp/frameSeq），并在评估阶段读取。
      * 新增并接入调试字段：`bounceApplied/bounceDtMs/bounceFactor/lastSwitch/ssBeforeAfter`（含省流短键与 schema）。

---

## [217] 2026-03-04 04:55:00 - 切换主线显示版本到V1.3.2并消除Registry签名崩溃路径

**用户指令**：
> 现在算法应该是1.3.2了拜托

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：确保主线算法与归档显示统一为 `V1.3.2`，并避免 `PresenceAlgorithmRegistry` 继续触发 Kotlin 默认参数/`copy$default` 签名崩溃路径。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt
      * app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceBaselineArchive.kt
      * codexHistory.md
    *   涉及方法：
      * PresenceAlgorithmRegistry.create
      * PresenceAlgorithmRegistry.buildActiveMainlineParams（新增）
    *   关键改动：
      * 新增并启用版本常量 `V1.3.2(B03030450)` 作为唯一主线可选版本。
      * `create` 中对 `V1.3.2/V1.3.1` 统一走主线分支，运行时 `versionId/runtimeTag` 输出均为 `V1.3.2`。
      * 将主线参数构造从 `params.copy(...)` 改为显式 `PresenceEstimatorParams(...)` 构造，绕开 `copy$default` 运行时签名不一致风险。
      * `PresenceBaselineArchive.ACTIVE_VERSION_ID` 同步为 `V1.3.2(B03030450)`。

---

## [201] 2026-03-04 04:45:00 - 修复PresenceAlgorithmRegistry默认参数签名崩溃

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 `NoSuchMethodError: PresenceAlgorithmRegistry.create$default / PresenceEstimatorParams.<init>` 启动崩溃。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity.ensurePresenceAlgorithmVersion
    *   关键改动：
      * 将 `PresenceAlgorithmRegistry.create(selectedId)` 改为显式调用
        `PresenceAlgorithmRegistry.create(selectedId, PresenceEstimatorParams())`，
        避免运行时走 Kotlin 合成默认参数方法 `create$default`，从而规避参数签名漂移导致的崩溃。

---

## [216] 2026-03-04 04:38:00 - 增加EventValidation链路诊断日志并支持长按匹配信息区复制

**用户指令**：
> 解释为什么500~600ms期间没触发漏匹配；看不出来就加定位日志。  
> 另外再次强调：编译通过后先发出声音。  
> 并新增：timeMs后追加北京时间、长按匹配信息区复制日志。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位“运行时分支提前返回导致漏匹配扫描未执行”的时序问题；并完善日志可读性与匹配信息区交互复制。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt
      * codexHistory.md
    *   涉及方法：
      * MainActivity.maybeRunSmartMatchValidation
      * MainActivity.logValidationTick（新增）
      * MainActivity.formatBeijingTime（新增）
      * MainActivity.onValidationBannerLongPressed（新增）
      * MainActivity.buildSmartMatchDiagnosticReport
      * MainActivity.buildUnlockClipboardReport
      * MainActivity.buildDebugPanelClipboardReport
      * DetectionOverlayView.setOnUnlockBannerLongPressListener（新增）
      * DetectionOverlayView.onTouchEvent（新增）
      * DetectionOverlayView.drawUnlockBannerBelowMarker
    *   关键改动：
      * 新增 `EventValidationTick` 日志，输出：`nowMs/windowMs/runtimeEventsCount/markedEventsCount/didReturnByRuntimeBranch/didScanOverdueBranch/overdueTriggered/playState`。
      * 智能匹配、unlock快照、调试面板快照三类复制日志统一 `timeMs=... (北京时间=...)`。
      * 匹配信息条支持长按识别（命中条幅区域），长按后静默复制“智能匹配诊断快照”。
      * 本次编译通过后已执行提示音命令（`[console]::beep(...)`）。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [200] 2026-03-04 04:35:00 - 可视门分数链路防断链（soft tail + 候选粘性 + FALLBACK冻结）

**用户指令**：
> 继续刚才的任务，按边界要求实现：  
> 1) dps soft tail；  
> 2) 候选粘性（ALL_ZERO/FREEZE，可退出）；  
> 3) FALLBACK 时仅冻结不换轨；  
> 并补充日志字段（candidate/prevCandidate、dpsRaw/dpsFinal、best/second/prev、stickReason 等）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“e 接近 eth 时候选跳远门导致 dps=0→das/ss=0→换轨清零”的临界帧断链问题，同时保持盲区门 `stableDoor/pending` 分支不受影响。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：PresenceEstimatorParams、PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.distanceScoreByDoor、MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend
    *   关键改动：
      * 新增 `dps` 软尾参数：`doorProximitySoftTailRatio`（默认 `1.0`），并将 `distanceScoreByDoor` 改为返回 `raw/final` 双值：  
        `dd<=nearDist` 保持近门曲线，`nearDist<dd<nearDist*(1+ratio)` 软衰减，`dd>=dd1` 严格归零。
      * 新增可视链路候选粘性参数与逻辑：  
        `ALL_ZERO`（best/second 同时低于阈值）与 `FREEZE`（`prevE >= freezeRatio*eth` 且 `best-prev < switchMargin`）触发“保持 prevCandidate，不换轨”。
      * 新增 `FALLBACK` 冻结：当本帧 best 为 `mwu=FALLBACK` 且存在 `prevCandidate` 时仅冻结当前帧候选，不改变主公式，不延续到下一帧。
      * 为调试面板压缩日志新增字段映射与 schema：  
        `nearDist,dpsRaw,dpsFinal,dpsTailRatio,candidate,prevCandidate,bestScore,secondScore,prevScore,bestMinusPrev,stickApplied,stickReason,fallbackFrozen`。

---

## [215] 2026-03-04 04:15:00 - 匹配异常文案分流：漏匹配显示实际等待，无合理匹配显示最近合理偏差

**用户指令**：
> 漏匹配还是有的，这次这个改名。  
> 不用窗口值，要显示实际等了多久（应>=窗口）。  
> 运行时未命中改成“无合理匹配 … 最近合理匹配XXms”。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修正文案语义，避免把两类异常混在一起；同时让漏匹配时长可反映真实等待。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * MainActivity.buildMissMatchMessage
      * MainActivity.buildNoReasonableMatchMessage（新增）
      * MainActivity.maybeRunSmartMatchValidation
      * MainActivity.handleRuntimeEventMatching
    *   关键改动：
      * 标注超窗未命中：保留“漏匹配”，文案改为 `实际等待=+XXXms`（`nowMs - markedMs`）。
      * 运行时无匹配：改为 `无合理匹配:类型 路径, 最近合理匹配=±XXXms`。
      * 最近合理匹配无可比事件时显示 `无可比事件`。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [214] 2026-03-04 04:02:00 - 快照增加北京时间并支持长按匹配信息区复制诊断

**用户指令**：
> 1. 日志里在 `timeMs=...` 后面增加北京时间。  
> 2. 长按匹配信息区时也执行一次日志复制。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提升日志可读性（直接看到北京时间）并增强复盘效率（长按匹配信息区即复制诊断）。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * DetectionOverlayView.setOnUnlockBannerLongPressListener（新增）
      * DetectionOverlayView.drawUnlockBannerBelowMarker
      * DetectionOverlayView.onTouchEvent（新增）
      * MainActivity.setupButtons
      * MainActivity.onValidationBannerLongPressed（新增）
      * MainActivity.formatBeijingTime（新增）
      * MainActivity.buildSmartMatchDiagnosticReport
      * MainActivity.buildUnlockClipboardReport
      * MainActivity.buildDebugPanelClipboardReport
    *   关键改动：
      * 三类复制日志的 `timeMs` 行改为：`timeMs=... (北京时间=yyyy-MM-dd HH:mm:ss.SSS)`。
      * 匹配信息条（unlock banner）支持长按识别，仅在命中信息条区域时触发。
      * 长按匹配信息条时自动复制“智能匹配诊断快照”（静默复制，无新增toast）。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [213] 2026-03-04 03:42:00 - 无匹配提示文案统一为“漏匹配:…,+xxxms”

**用户指令**：
> 无匹配时信息窗应写成“漏匹配:XXXX,+500ms”。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让无匹配提示直接可读、统一格式，避免“无匹配事件”语义不清。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * MainActivity.buildMissMatchMessage（新增）
      * MainActivity.nearestRuntimeDeltaMs（新增）
      * MainActivity.buildNearestOffsetForRuntime
      * MainActivity.maybeRunSmartMatchValidation
      * MainActivity.handleRuntimeEventMatching
    *   关键改动：
      * 新增统一漏匹配文案构造：`漏匹配:事件类型 [可选路径],偏差`。
      * 标注超窗未匹配改为：`漏匹配:进/出子房间,+windowMs`（例如 `+500ms`）。
      * 运行时无匹配改为：`漏匹配:进/出子房间 from->to,+deltaMs`（优先最近同类型事件偏差，缺失时回退 `+windowMs`）。
      * 自动复制的诊断日志内容保持不变，仅更新信息窗提示文本。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [212] 2026-03-04 03:28:00 - 智能诊断已匹配状态补充房间路径

**用户指令**：
> 已匹配日志里要看出匹配的事件到底是什么，比如从哪个房间到哪个房间。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让自动复制的智能匹配诊断日志可直接定位“匹配到的房间切换路径”。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * MainActivity.handleRuntimeEventMatching
      * MainActivity.buildSmartMatchDiagnosticReport
    *   关键改动：
      * 将 `matchedRuntimeByMarkedKey` 的缓存对象从 `RuntimeRoomEvent` 提升为 `ValidationRuntimeEvent`，保留 `fromName/toName`。
      * 诊断日志 `markedEvents(all)` 中 `state=已匹配(...)` 新增：
        * `路径=fromName->toName`
      * 其他匹配逻辑不变，偏差定义保持 `runtimeMs - markedMs`。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [211] 2026-03-04 03:16:00 - 已匹配/重复匹配提示增加具体事件引用

**用户指令**：
> 已经匹配过的日志里要写清楚匹配的是具体哪个事件，方便盘查。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让“已匹配/异常重复匹配”提示可直接定位到标注列表中的具体事件。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * MainActivity.markedEventRef（新增）
      * MainActivity.handleRuntimeEventMatching
    *   关键改动：
      * 新增 `markedEventRef`，按当前标注列表生成事件引用文本：
        * `事件[#序号,type=...,f=...,ms=...]`
      * “已经匹配”提示改为包含完整事件引用与偏差。
      * “异常重复匹配”提示改为包含完整事件引用与最近偏差。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [210] 2026-03-04 03:05:00 - 已匹配刻度改为斜杠并关闭异常复制提示Toast

**用户指令**：
> 不需要弹出toast。上方信息区已能看到原因。  
> 已匹配刻度还是竖线，要求改成“/”样式。  

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让“已匹配事件点”视觉样式与未匹配强区分，同时避免异常自动复制时二次打断。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * DetectionOverlayView.drawEventMarkerBar
      * MainActivity.copySmartMatchDiagnostic
    *   关键改动：
      * 未匹配刻度保持原有竖线色块。
      * 已匹配刻度改为同色“/”单斜线（45°）绘制，不再使用窄区域条纹。
      * 异常自动复制智能诊断日志改为静默执行，不再弹“已复制智能匹配诊断日志”Toast。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [209] 2026-03-04 02:40:00 - 已匹配刻度改45度斜线并在异常时自动复制智能匹配诊断日志

**用户指令**：
> 颜色区别不够明显：颜色恢复之前方案，已匹配改成45度斜线。  
> 出现无匹配事件或异常重复匹配时，自动复制日志（包含所有已记录事件点）。  
> 偏差符号统一：标记1000ms、实际1027ms应显示+27ms。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提升事件刻度可辨识度，并在智能匹配异常时自动产出可复盘诊断信息；同时统一偏差正负方向。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
    *   涉及方法：
      * DetectionOverlayView.drawEventMarkerBar
      * DetectionOverlayView.drawMatchedTickHatch（新增）
      * MainActivity.maybeRunSmartMatchValidation
      * MainActivity.handleRuntimeEventMatching
      * MainActivity.copySmartMatchDiagnostic（新增）
      * MainActivity.buildSmartMatchDiagnosticReport（新增）
      * MainActivity.formatSignedOffsetMs
    *   关键改动：
      * 事件刻度改为：未匹配=原色实心，已匹配=同色实心+45度斜线覆盖（不再依赖“更亮色”区分）。
      * 在“无匹配事件（运行时/标注超窗）”与“异常重复匹配”分支，自动复制“智能匹配诊断快照”到剪贴板并提示。
      * 诊断快照包含：当前运行事件、窗口参数、最近偏差、全部标注事件点及其匹配状态、近期运行时事件列表。
      * 偏差统一为 `实际触发时间 - 标记时间`，保证示例 `1000ms -> 1027ms` 显示 `+27ms`。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [208] 2026-03-04 02:12:00 - 智能匹配提示追加±毫秒偏差并取消已匹配刻度减淡

**用户指令**：
> 第三阶段补充：事件提示末尾显示离匹配事件的正负毫秒数。  
> 未匹配或重复匹配，显示离最近可匹配事件的时间偏差。  
> 已匹配事件点不要减淡，保持标准颜色。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提高事件提示可复盘性（直接看到提前/滞后毫秒），并恢复事件刻度统一可见性。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt
      * codexHistory.md
      * dialogueHistory.md
    *   涉及方法：
      * MainActivity.formatSignedOffsetMs
      * MainActivity.buildNearestOffsetForRuntime
      * MainActivity.buildNearestOffsetForMarked
      * MainActivity.maybeRunSmartMatchValidation
      * MainActivity.handleRuntimeEventMatching
      * DetectionOverlayView.drawEventMarkerBar
    *   关键改动：
      * 新增带符号偏差格式：`+Nms / -Nms`。
      * 匹配成功提示追加：`偏差=±Nms`（实际触发时间 - 标注时间）。
      * 无匹配/异常重复提示追加：`最近偏差=±Nms`（相对最近同类型标注事件）。
      * 过期未匹配（标注点超窗）提示追加最近偏差信息。
      * 进度条事件刻度不再按匹配状态减淡，统一使用 ENTER/EXIT 标准颜色。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [207] 2026-03-04 01:59:00 - 修复PoseResult签名崩溃并改为侧路分数渲染

**用户指令**：
> 运行后一秒就崩溃（NoSuchMethodError，PoseResult 构造）。  
> 分数显示仍要保留，并且是一行长字符串拼接，不要重叠。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：消除 `PoseResult` 构造签名变更引发的运行时崩溃，同时保留“ID左侧切换分”显示。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/data/model/PoseData.kt
      * app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt
      * app/src/main/java/com/example/roomxxx0102/ui/drawers/PoseDrawer.kt
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * codexHistory.md
      * dialogueHistory.md
    *   涉及方法：
      * PoseResult 数据结构
      * DetectionOverlayView.updatePoseData / onDraw
      * PoseDrawer.draw
      * MainActivity poseAnalyzer 回调（Presence分数映射到UI）
    *   关键改动：
      * 回退 `PoseResult` 扩展字段，恢复原始构造签名，修复 `NoSuchMethodError`。
      * 改为侧路传参：`updatePoseData(..., switchHints)` 传 `trackId -> (score, EventType)`。
      * `PoseDrawer` 同一基线分段绘制：
        * 左段：切换分（按事件类型着色）
        * 右段：`ID:xx 置信度 Lock`
        * 视觉上是一行长字符串，不重叠。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [206] 2026-03-03 21:15:00 - 为每个lock目标在ID左侧显示切换分（按进/出着色）

**用户指令**：
> 对于每个lock的人，在ID左侧加“进房间分/出房间分”，并按进门事件和出门事件颜色区分。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把 Presence 每帧切换分可视化到人物标签，便于逐人实时观察“进子房间/出子房间”趋势。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt
      * app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt
      * app/src/main/java/com/example/roomxxx0102/data/model/PoseData.kt
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * app/src/main/java/com/example/roomxxx0102/ui/drawers/PoseDrawer.kt
      * codexHistory.md
      * dialogueHistory.md
    *   涉及方法：
      * PresenceFrameResult 数据模型扩展
      * PresenceAlgorithmV1_1_0_B03021639.processFrame / evaluateVisibleEnterByScore
      * MainActivity poseAnalyzer 回调中的 `overlayView.updatePoseData(...)` 数据注入
      * PoseDrawer.draw
    *   关键改动：
      * `PresenceFrameResult` 新增 `trackSwitchScores: Map<Int, PresenceTrackSwitchScore>`。
      * 新增 `PresenceSwitchDisplayType`（`ENTER_SUB_ROOM` / `EXIT_SUB_ROOM` / `UNKNOWN`）与 `PresenceTrackSwitchScore`。
      * 在 V1.3.1 评估中，把最佳候选的 `switchScore(ss)` 与方向类型写入 `EnterEvalResult`，并在 `processFrame` 汇总到 `trackSwitchScores`（仅 CONFIRMED 目标）。
      * `PoseResult` 新增 `switchDisplayScore`、`switchDisplayType`（默认空）。
      * `MainActivity` 把 `presenceResult.trackSwitchScores` 按 `trackId` 注入到对应 `PoseResult` 后再绘制。
      * `PoseDrawer` 在 `ID` 文本左侧新增分数前缀：
        * 进子房间：绿色（`#4CAF50`）
        * 出子房间：橙色（`#FF9800`）
        * 仅对 `isConfirmed=true` 且存在分数时显示。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [200] 2026-03-03 20:45:00 - 移除旧房间切换提示，仅保留智能匹配提示

**用户指令**：
> 现在的事件显示还是老的(不是智能判断那个),是每次事件发生时显示的提示信息。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“每次房间切换仍弹旧提示”问题，统一为智能匹配提示链路。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.onCreate 内 poseAnalyzer 回调的 runOnUiThread 分支
    *   关键改动：
      * 删除旧提示字符串 `presenceSwitchBanner` 构造逻辑（`位置切换: A->B(...)`）。
      * 删除旧分支 `if (presenceSwitchBanner != null && !AppSettings.isSmartMatchPauseEnabled) { ... }`。
      * 保留并继续使用智能匹配提示、异常提示、以及“切换后自动暂停”提示。
      * 编译校验通过：`:app:compileDebugKotlin` 成功。

---

## [205] 2026-03-03 20:25:00 - 事件类型命名中文化（进子房间/出子房间）与切换暂停提示联动

**用户指令**：
> 重新命名：之前的出门改“出子房间”，进门改“进子房间”；提示不要英文。  
> “切换房间后暂停播放”的提示也要带上新的类型提示。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：统一事件类型对外文案，去除 ENTER/EXIT 英文暴露，并在自动暂停提示中带上中文类型。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/res/layout/activity_main.xml、codexHistory.md
    *   涉及方法：MainActivity.eventTypeLabel、MainActivity.refreshEventMarkerControls、MainActivity.addMarkedEvent、MainActivity.confirmDeleteCurrentMarkedEvents、MainActivity.maybeRunSmartMatchValidation、MainActivity.handleRuntimeEventMatching、MainActivity pose 回调切换暂停提示分支
    *   关键改动：
      * 新增 `eventTypeLabel`：`ENTER -> 进子房间`，`EXIT -> 出子房间`。
      * 智能匹配提示文案全部替换为中文类型（无匹配/已匹配/异常重复匹配）。
      * 标注新增成功提示与删除按钮动态文案替换为中文类型。
      * 自动暂停提示从“检测到房间切换，已自动暂停”改为“检测到房间切换(进子房间/出子房间)，已自动暂停”。
      * 工具栏按钮文案改为“记录进子房间事件 / 记录出子房间事件”。

---

## [204] 2026-03-03 20:05:00 - 去除入户特判并统一客厅方向映射

**用户指令**：
> 影响可以接受，我自己改标注。把入户特判映射去掉，按刚才统一规则来。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：消除“入户->客厅被映射为ENTER”的特殊逻辑，统一所有子房间到客厅均映射为EXIT。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity.appendRuntimeEventsForValidation、MainActivity.mapPresenceEventType
    *   关键改动：
      * `mapPresenceEventType` 中删除 `fromName.contains("入户") -> ENTER` 的特判。
      * 统一规则：
        * `fromRoomId == livingRoomId` => `EventType.ENTER`
        * `toRoomId == livingRoomId` => `EventType.EXIT`
      * 同步精简函数签名，去掉不再使用的 `roomNameById` 形参传递。

---

## [203] 2026-03-03 19:45:00 - 第三阶段：智能检测匹配暂停（一一匹配+异常暂停+刻度命中变暗）

**用户指令**：
> 把漏检自动暂停改为智能检测匹配暂停，并加开关。  
> 事件点播放前高亮、命中后变暗；要求事件一一匹配。  
> 若匹配到已命中过的事件或无匹配事件要暂停并提示。  
> 重置并从头播放需清理匹配状态。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将“漏检自动暂停”升级为“运行时事件与标注事件的一一匹配校验暂停”，并可在设置中开关控制。
    *   修改文件：
      * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
      * app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt
      * app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt
      * app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt
      * app/src/main/res/layout/fragment_settings_home.xml
      * codexHistory.md
    *   涉及方法：
      * `MainActivity.appendRuntimeEventsForValidation`
      * `MainActivity.maybeRunSmartMatchValidation`
      * `MainActivity.handleRuntimeEventMatching`
      * `MainActivity.pauseForSmartMatchAnomaly`
      * `MainActivity.resetEventValidationTracking`
      * `MainActivity.hardRestartPlayback`
      * `DetectionOverlayView.setEventMarkerState`
      * `DetectionOverlayView.drawEventMarkerBar`
      * `AppSettings.init/setSmartMatchPauseEnabled`
      * `SettingsHomeFragment.onViewCreated/onResume`
    *   关键改动：
      * 新增开关配置 `isSmartMatchPauseEnabled`（默认开），并在设置页新增 `switch_smart_match_pause`。
      * 旧漏检扫描逻辑改为一一匹配：
        * 运行时事件在窗口内优先匹配未匹配的同类型标注点（最近优先）。
        * 若仅命中已匹配标注点 => `异常重复匹配`，立即暂停并提示。
        * 若无任何候选标注点 => `无匹配事件`，立即暂停并提示。
        * 若标注点超出窗口仍未匹配 => `无匹配事件`，立即暂停并提示。
      * 事件提示统一以 `事件类型:` 开头，并附带匹配结果说明文本。
      * 进度条刻度新增匹配状态：未匹配高亮，已匹配变暗。
      * “重置并从头播放”时显式清理全部匹配状态（包括已匹配/已告警集合）。
      * 智能匹配开关开启时，关闭旧 `presenceSwitchBanner` 覆盖，避免提示文案冲突。

---

## [202] 2026-03-03 19:20:00 - 新增事件后首次漏检校验忽略一次

**用户指令**：
> 我刚刚记录好一个事件继续播放就马上提醒我未命中事件。  
> 不如加一个临时状态，新设置后的第一次校验忽略。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免“刚新增标注事件后继续播放立即触发未命中暂停”的误报。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity.addMarkedEvent、MainActivity.resetEventValidationTracking、MainActivity.maybePauseForMissedMarkedEvents
    *   关键改动：
      * 新增一次性标记 `skipNextMissValidationOnce`。
      * 仅在 `addMarkedEvent` 成功新增事件后置 `true`。
      * `maybePauseForMissedMarkedEvents()` 首次命中该标记时直接跳过本次校验并清零。
      * `resetEventValidationTracking()` 时重置该标记，避免跨视频/重置后残留状态。

---

## [201] 2026-03-03 19:05:00 - 隐藏左上角人数卡片并将事件提示移至进度条下方居中

**用户指令**：
> 首先，现在画面左上角仍然有客厅的人数。其次进门出门的事件不要放在左上角了，放在进度条的下方，画面中间。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：移除左上角客厅人数显示，并把进/出事件提示从顶部左上改为进度条下方居中。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/res/layout/activity_main.xml、codexHistory.md
    *   涉及方法：DetectionOverlayView.onDraw、drawEventMarkerBar、drawUnlockBannerBelowMarker、MainActivity.setupButtons、MainActivity.toggleEditModeUI
    *   关键改动：
      * 事件提示条改为“进度条下方居中”绘制：新增 `drawUnlockBannerBelowMarker(...)`，并将进度条位置固定在顶部。
      * 原顶部左上整行提示绘制逻辑删除，不再占用左上区域。
      * `cardCounter` 在布局默认改为 `gone`，并在雷达切换/编辑态切换中均保持 `gone`，避免再次出现左上客厅人数卡片。

---

## [200] 2026-03-03 18:40:00 - 移除左上角Pose日志并在调试面板增加客厅存在/当前人数

**用户指令**：
> 1.删除左上角的pose日志显示 2.把客厅人数(存在/当前)放到调试面板里面去.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：清理主画面左上角冗余 Pose 文本，并增强调试面板可读性（直接显示客厅存在/当前人数）。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：DetectionOverlayView.onDraw、RoiLogAggregator.updateLivingRoomCounts、RoiLogAggregator.snapshotForPanel、MainActivity poseAnalyzer 回调
    *   关键改动：
      * 删除主画面左上角 `debugInfo` 绘制（不再显示 `Pose: xxms | Count:xx`）。
      * 在 `RoiLogAggregator` 增加客厅人数字段与 `updateLivingRoomCounts(persistentCount, currentCount)`。
      * 在调试面板快照中新增一行：`living counts(存在/当前)=x/y`。
      * 在 `MainActivity` 每帧统计后将 `livingRoom.persistentPersonCount` 与 `livingRoom.personCount` 注入聚合器。

---

## [199] 2026-03-03 18:05:00 - 事件标记按视频名持久化

**用户指令**：
> 对于。 每一个节点的设置你都要给我做持久化呀。而且这个持久化的文件应该和。 视频名称的文件一致。 也就是说每一个视频都可以对应一个持久化的。 事件标记系统。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将事件标记从内存态升级为“按视频独立持久化”，避免切换视频或重启后丢失。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/validation/EventMarkerManager.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：EventMarkerManager.init、bindVideo、addEvent、removeEventsNearFrame、clearBoundVideoEvents、loadEventsForVideo、saveEventsForVideo、resolveVideoBaseName、MainActivity.onCreate
    *   关键改动：
      * `EventMarkerManager` 新增 `init(context)`，在 `filesDir/event_markers/` 目录管理事件文件。
      * `bindVideo(videoKey)` 时按 `videoKey` 自动加载对应事件文件；未命中则创建空列表。
      * `addEvent/removeEventsNearFrame/clearBoundVideoEvents` 自动触发保存/删除文件。
      * 文件命名以视频名为基础：`<videoName>.events.json`；无法提取视频名时回退到稳定哈希名。
      * `MainActivity.onCreate` 增加 `eventMarkerManager.init(applicationContext)`，确保持久化能力生效。

---

## [198] 2026-03-03 09:20:00 - 智能校验阶段1：事件列表管理与顶部刻度UI

**用户指令**：
> 新增智能校验系统（阶段1：事件列表管理）：  
> - 独立事件模型与 EventMarkerManager（ENTER/EXIT、frameIndex/timestampMs、按视频绑定）  
> - 顶部半透明进度条（左右100px）+ ENTER/EXIT tick  
> - 仅在“暂停/静止 + 调试面板开启”时显示按钮：记录进门/记录出门/跳转下一个/删除当前  
> - 预留后续漏触发校验接口（window=±1000ms），本阶段不接入自动暂停  
> - 不修改现有人数/事件算法逻辑，仅播放器/调试UI层接入

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：实现事件标注与可视化管理闭环，支持人工标注回放定位，且不侵入现有 Presence 判定主线。
    *   修改文件：
        * app/src/main/java/com/example/roomxxx0102/logic/validation/EventMarkerManager.kt
        * app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt
        * app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt
        * app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt
        * app/src/main/res/layout/activity_main.xml
        * codexHistory.md
    *   涉及方法：
        * `EventMarkerManager.bindVideo/addEvent/getEvents/findEventsNearFrame/removeEventsNearFrame/findNextEventAfter/isMarkedEventMatched`
        * `VideoFeeder.seekToMs/getDurationMs`
        * `DetectionOverlayView.setEventMarkerState/drawEventMarkerBar`
        * `MainActivity.refreshEventMarkerUi/refreshEventMarkerOverlay/refreshEventMarkerControls/addMarkedEvent/jumpToNextMarkedEvent/confirmDeleteCurrentMarkedEvents`
    *   关键改动：
        * 新增 `EventType/MarkedEvent` 与 `EventMarkerManager`（按视频 key 内存隔离，自动排序，去重规则为“同类型同帧忽略”）。
        * 预留校验常量与接口：
          * `MATCH_WINDOW_MS = 1000`
          * `isMarkedEventMatched(markedEvent, runtimeEvents, windowMs)`
        * `DetectionOverlayView` 新增顶部进度条与事件刻度绘制：
          * 半透明轨道与进度
          * ENTER/EXIT 两色 tick
          * 左右固定 `100px` 边距
          * 顶部已有 unlock banner 时自动下移，避免重叠
        * 主界面新增事件工具栏（默认隐藏）：
          * `记录进门事件`
          * `记录出门事件`
          * `跳转到下一个事件`
          * `删除当前事件`（按 ±1帧容忍，动态文案与置灰）
        * 显示条件严格限制为：`isVideoMode && debugPanelEnabled && playState != PLAYING`。
        * 新增 `VideoFeeder.seekToMs` 供“跳转到下一个事件”按毫秒定位；新增 `getDurationMs` 供进度条映射。
        * UI 仅在播放器/调试层接入，不改动已有房间人数/Presence算法流程。

---

## [197] 2026-03-03 08:55:00 - 新增对话原文归档机制并写入项目规则

**用户指令**：
> 做一个单独文件把我们的对话记录下来；不要改动原文，尽量不消耗token。  
> 单独想办法实现，并把调用方法和规则写进规则文件，让其他AI知道去读。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提供“原文直存”的对话归档能力，并把执行规则固化到 `AGENTS.md`。
    *   修改文件：tools/dialogue_archive.py、dialogueHistory.md、AGENTS.md、codexHistory.md
    *   涉及方法：dialogue_archive.py 的 append_turn / format_entry / run_append_turn / main
    *   关键改动：
      * 新增 `tools/dialogue_archive.py`：
        * 命令：`append-turn`
        * 输入：`--user-file`、`--assistant-file`（UTF-8 原文文件）
        * 行为：自动编号、自动时间戳（可覆盖）、追加写入 `dialogueHistory.md`。
      * 新增 `dialogueHistory.md` 作为统一归档文件入口（只存原文，不改写）。
      * 在 `AGENTS.md` 增加对话归档规则与标准调用命令，并要求新任务前读取最新条目。

---

## [196] 2026-03-03 08:35:00 - 二次修复seek：后退方向兜底与暂停态seek后强制刷新

**用户指令**：
> 1.没有任何变化. 2.还是不能在暂停时跳转(之前一直是播放后才发现跳转是有效的)

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“首击 -1 帧仍可能前进”与“PAUSED 态 seek 后画面不刷新”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md
    *   涉及方法：VideoFeeder.setOnSeekCompleteListener、VideoFeeder.seekByMs、VideoFeeder.forcePausedFrameRefresh、VideoFeeder.stop
    *   关键改动：
      * 新增 `PendingSeekState`，记录每次 seek 的 `before/delta/captureAsStep`。
      * 对 `-1帧` 增加一次“方向兜底”：
        * 若 seek 完成位置 `>= before`，立即执行一次 `SEEK_PREVIOUS_SYNC` 校正，避免反向前进。
      * 在 `PAUSED`（`!isStillMode && !isPlaying`）下 seek 完成后，执行一次 `start()+pause()` 强制刷新当前帧显示。
      * `stop()` 时补充清空 `pendingSeekState`，避免跨会话残留。

---

## [195] 2026-03-03 08:20:00 - 修复静止首击-1反向与暂停态±5秒seek不生效

**用户指令**：
> 先修复:1.反向bug 2.暂停时不能+-5s生效的问题

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“静止后首次点击 -1 帧偶发前进”与“暂停状态下 ±5s 跳转不稳定/不生效”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md
    *   涉及方法：VideoFeeder.seekForward、VideoFeeder.seekBackward、VideoFeeder.seekBackwardFrame、VideoFeeder.seekByMs、VideoFeeder.clearForwardStepNudgeState
    *   关键改动：
      * 新增 `clearForwardStepNudgeState()`，在 `seekBackwardFrame` 与 `seekForward/seekBackward(秒级)` 前清空 `+1帧` 补偿残留状态，避免首次 `-1帧` 被旧补偿抵消成前进。
      * `seekByMs` 增加 `seekMode` 参数（默认仍为 `SEEK_CLOSEST`）。
      * `+5s` 改为 `SEEK_NEXT_SYNC`，`-5s` 改为 `SEEK_PREVIOUS_SYNC`，提升暂停态方向性 seek 的生效稳定性。

---

## [189] 2026-03-03 07:45:00 - 新增“切换时暂停判定日志”开关并输出每次切换判定结果

**用户指令**：
> 有时候人的房间切换了,但是没被暂停(偶发),分析下可能的原因。  
> 好的,就这么改,加一个开关,切换时记录暂停相关日志。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位“切换事件偶发未自动暂停”问题，提供每次切换的暂停判定可观测性。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：AppSettings.init / setPauseDecisionLogOnSwitchEnabled；SettingsHomeFragment.onViewCreated；MainActivity 分析回调 runOnUiThread 分支、buildUnlockClipboardReport、buildDebugPanelClipboardReport
    *   关键改动：
      * 新增设置开关：`切换时记录暂停判定日志`（`switch_pause_decision_log_on_switch`）。
      * 新增配置项：`isPauseDecisionLogOnSwitchEnabled`（持久化到 `SharedPreferences`）。
      * 每次存在 `presenceResult.events` 时，输出一条 `RoomPauseSwitch` 日志，包含：
        * `switch=from->to@door:reason`
        * `events`
        * `pauseOnSwitch`
        * `playStateBefore`
        * `shouldAutoPause`
        * `didAutoPause`
        * `playStateAfter`
      * 调试快照 settings 行新增 `pauseSwitchLog` 显示当前开关状态。

---

## [188] 2026-03-03 07:22:00 - EXIT_TO_LIVING 提速：dps短保持 + 长窗推进回退

**用户指令**：
> 确认按 4 条方案改：  
> 1) dpsEff 仅作用 EXIT_TO_LIVING 的 doorAssist 输入；  
> 2) 仅调整 dad/ratio 的窗口取值（短窗+长窗回退），不改主公式/阈值；  
> 3) 输出 motionWindowUsed 及 dadShort/dadLong；  
> 4) 保持进门行为不变。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复可视子房间退出客厅场景中 `doorAssist` 稀疏导致的慢触发，同时严格遵守“仅 EXIT_TO_LIVING 生效、主公式不变”的边界。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、computeDoorMotionStats、computeExitDoorAssistProximityScore；PresenceAlgorithmRegistry.create；MainActivity.compressPresenceDecision/buildPresenceShortKeyLegend；RoiLogAggregator.snapshotForPanel
    *   关键改动：
      * 新增 EXIT_TO_LIVING 专用 `dpsEff`（短保持+衰减）并仅接入 `doorAssist` 的 dps 输入；
      * `nearDoorPassed` 继续使用原始 `dps`，避免影响 near 判定与门歧义逻辑；
      * 新增“短窗+长窗”推进估计：`LONG` 可用时用长窗，否则在 EXIT 场景标记 `FALLBACK` 并回退短窗；
      * 新增日志字段：`doorProximityScoreEff`、`motionWindowUsed`、`doorAdvanceDeltaShort`、`doorAdvanceDeltaLong`、`exitDpsHoldApplied`；
      * 同步短键与 schema：`dpe/dadS/dadL/mwu/xvdh`。

---

## [187] 2026-03-03 06:28:00 - 仅对ENTER_VISIBLE改为“PoseGate只作用姿态项”

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“门口证据足够但被 PoseGate 全量压分导致不过线”的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore
    *   关键改动：
      * 在 `useSwitchScoreIntegrator=true` 且 `DOOR:ENTER_VISIBLE` 场景下，
        将公式从
        `pg * (a*ngp*pts + (1-a)*das)`
        改为
        `a*pg*ngp*pts + (1-a)*das`。
      * 其余场景（如 EXIT_TO_LIVING）保持原有积分公式不变。

---

## [186] 2026-03-03 06:18:00 - 切换为单主线运行并增加baseline参数指纹

**用户指令**：
> 老版本做一份存档，不要再做分支管理；并且要能看到当前到底跑的是什么。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免多版本分支继续引入参数错配；将运行策略收敛为“单主线 + 存档追溯”，并在日志中明确输出实际生效参数指纹。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmEngine.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceBaselineArchive.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmEngine.runtimeTag、PresenceAlgorithmRegistry.create、PresenceAlgorithmRegistry.resolveVersionId、PresenceAlgorithmRegistry.paramsHash、MainActivity 中 Presence 日志输出点
    *   关键改动：
      * 运行入口只保留 `V1.3.1(B03030340)` 作为可选版本（`AUTO` 也解析到该版本）。
      * 历史版本不再作为运行分支，转为归档清单（`PresenceBaselineArchive`）。
      * 在 `V1.3.1` 运行时固定使用主线 baseline 参数，并生成运行标签：
        * `versionId|b=baselineId|h=paramsHash`
      * 主界面和调试面板中的 `presenceAlgo`、切换日志都改为输出 `runtimeTag`，可直接核对“当前真实生效参数”。
      * 同时修正 `V1.3.1` 主线参数中的 `grayPoseMinConfidenceForSwitch=0.45`（避免再次落错分支）。

---

## [184] 2026-03-03 06:00:00 - 修复V1.3.1阈值改动误落分支

**用户指令**：
> 还是不行

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复前次参数调整未实际作用于 `V1.3.1` 的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmRegistry.create（`VERSION_V1_3_1_B03030340` 参数块）
    *   关键改动：
      * 将 `V1.3.1` 的 `grayPoseMinConfidenceForSwitch` 明确改为 `0.45`（此前误改到其他版本分支）。

---

## [183] 2026-03-03 05:50:00 - V1.3.1姿态门控阈值下调

**用户指令**：
> 好,就该这个

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：确认“进厨房不过”主因后，仅放宽姿态门控阈值，避免 `PoseGate` 过度压分。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmRegistry.create（V1.3.1 参数画像）
    *   关键改动：
      * `grayPoseMinConfidenceForSwitch: 0.55 -> 0.45`（仅 `V1.3.1(B03030340)`）
      * 其余积分公式、阈值和日志字段均保持不变。

---

## [182] 2026-03-03 05:42:00 - EXIT_TO_LIVING场景取消近门门控压分

**用户指令**：
> 第一个入户到客厅就没pass  
> 还是不行

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“可视房间/入户 -> 客厅”在门线附近抖动时被 `nearGateForPose` 压分导致积分过不了阈值的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore
    *   关键改动：
      * 在 `V1.3.1` 单分数模式下，`isVisibleExitToLiving=true` 时将 `nearGateForPose` 固定为 `1.0`；
      * 其余场景（尤其客厅->可视子房间）仍保留 `nearGateForPose` 约束，避免远处误入。

---

## [181] 2026-03-03 05:33:00 - V1.3.1首段入户触发阈值微调

**用户指令**：
> 第一个入户到客厅就没pass  
> ok（同意先调参数）

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不改公式结构的前提下，提升“入户->客厅”首段触发通过率。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmRegistry.create（V1.3.1 参数画像）
    *   关键改动：
      * `poseNearGateDps0: 0.20 -> 0.12`
      * `switchEvidenceThreshold: 0.62 -> 0.56`
      * 仅作用于 `V1.3.1(B03030340)`，其余版本不变。

---

## [194] 2026-03-03 05:32:00 - V1.3 入户回弹修复：进入可视房间增加门证据门槛

**用户指令**：
> 1.3 里离开入户门判断没问题，问题是第二次又进了入户；要么分数有问题，要么阈值有问题。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“入户->客厅刚成立后，下一帧又被客厅->入户回弹”的误触发，且不影响 `V1.2.3`。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：PresenceEstimatorParams、PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmRegistry.create、MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend
    *   关键改动：
      * 新增参数：
        * `enterVisibleRequireDoorEvidence`
        * `enterVisibleDoorAssistMin`
      * `DOOR:ENTER_VISIBLE` 新增通过条件：
        * `enterVisibleDoorEvidencePass = !requireDoorEvidence || enterMotionPass || doorAssistScore >= enterVisibleDoorAssistMin`
      * `V1.3.0(B03030116)` 显式启用：
        * `enterVisibleRequireDoorEvidence=true`
        * `enterVisibleDoorAssistMin=0.10`
      * `V1.2.3(B03030020)` 显式关闭该门槛，保持不受影响。
      * 省流日志 `x[]` 新增短键：
        * `evdeR`（enterVisibleRequireDoorEvidence）
        * `evdeP`（enterVisibleDoorEvidencePass）
        * `evdaM`（enterVisibleDoorAssistMin）

---

## [180] 2026-03-03 05:18:00 - 落地V1.3.1单分数积分判定并修复算法版本可见性

**用户指令**：
> 采用“单一分数 + 连续帧积分”方案（含 pac<0.20 硬拒绝、tau=0.002、完整日志新增字段）；并修复设置页当前算法看不到的问题。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 V1.3.1 切换为“单分数+积分证据”判定，消除硬门槛互相打架；同时修复设置页算法版本显示异常。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、app/src/main/res/layout/fragment_settings_home.xml、app/src/main/res/values/arrays.xml、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.processFrame、SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onResume、MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend、RoiLogAggregator.snapshotForPanel
    *   关键改动：
      * `V1.3.1` 启用单分数门控：
        * `SwitchScore = PoseGate * ClearGate * (alpha * NearGateForPose * PoseTerm + (1-alpha) * DoorTerm)`
        * `pac < 0.20` 硬拒绝；`clearGate` 使用 `sigmoid((diff-margin)/tau)`，`tau=0.002`
      * 新增候选积分证据：
        * `E(t)=clip(beta*E(t-1)+SwitchScore,0,Emax)`，参数 `beta=0.72, Eth=0.62, Emax=2.0`
        * 候选切换后清空积分，逐帧衰减避免旧证据污染。
      * `V1.3.1` 关闭硬歧义拒绝与退出近门硬门槛（改由分数抑制）。
      * 日志保持完整并新增字段：
        * `switchScore/evidenceScore/evidenceThreshold/poseGate/clearGate/nearGateForPose/doorScoreGap`
        * 同步更新 `schema` 与短键解析。
      * 设置页“人数算法版本”控件改为与“日志更新频率”同款 `Spinner`，并在 `onResume` 强制同步当前选中，确保当前算法可见。
      * 资源列表补充 `V1.3.1(B03030340)`。

---

## [193] 2026-03-03 05:05:00 - V1.2.3剩余串味修复：可视退出分模型与Recovery路径版本隔离

**用户指令**：
> 目前和当初1.2.3仍然有些区别，我需要你再仔细检查看到底哪里还可能有差异。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：继续消除 `V1.2.3` 与 `V1.3.0` 共用实现中的残余串味点，重点隔离“可视子房间->客厅”的主体分模型与 `POLYGON_RECOVERY` 行为。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：PresenceEstimatorParams、PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmRegistry.create、MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend
    *   关键改动：
      * 新增版本参数：
        * `visibleExitPoseTransitionModel`（`OUTSIDE_ONLY` / `OUTSIDE_PLUS_TARGET`）
        * `allowVisibleExitPolygonRecovery`
      * `evaluateVisibleEnterByScore` 改为按参数计算可视退出主体分，不再写死。
      * `POLYGON_RECOVERY` 对“可视子房间->客厅”是否允许，改为按参数控制。
      * `V1.2.3(B03030020)` 显式绑定：
        * `visibleExitPoseTransitionModel=OUTSIDE_ONLY`
        * `allowVisibleExitPolygonRecovery=true`
      * `V1.3.0(B03030116)` 显式绑定：
        * `visibleExitPoseTransitionModel=OUTSIDE_PLUS_TARGET`
        * `allowVisibleExitPolygonRecovery=false`
      * 省流日志 `x[]` 扩展：
        * 新增 `xvpm`（visibleExitPoseTransitionModel）
        * 新增 `xvpr`（allowVisibleExitPolygonRecovery）

---

## [192] 2026-03-03 04:18:00 - 省流日志追加版本门禁开关值（用于核验版本画像）

**用户指令**：
> 之前1.2.3很好用,现在像不是1.2.3；版本维护有严重问题

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在保持省流格式前提下，直接输出“关键门禁是否启用”，用于快速验证当前算法版本画像是否正确。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend
    *   关键改动：
      * `x[]` 扩展为：`[pacMin,gpm,evnR,evcR,xvnR]`。
      * 新增短键映射：
        * `evnR=enterVisibleRequireNearDoor`
        * `evcR=enterVisibleRequireContainment`
        * `xvnR=exitVisibleRequireNearDoor`
      * 现在同一条日志即可看出：该版本是否启用了“进入近门门禁 / 进入可视区双门槛 / 退出近门门禁”。

---

## [191] 2026-03-03 04:10:00 - 修复算法版本串味：将V1.2.3与V1.3.0判定门禁彻底参数化隔离

**用户指令**：
> 之前1.2.3的算法非常好用,现在一团糟... 证明某些判断或者变量肯定不是1.2.3的时候了,算法版本维护出现了严重问题

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“不同版本共用同一实现类导致后续门禁改动污染旧版本”的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/RoomTransitionEstimator.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、codexHistory.md
    *   涉及方法：PresenceEstimatorParams、PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmRegistry.create
    *   关键改动：
      * 将原先写死在算法类中的门禁常量参数化进 `PresenceEstimatorParams`：
        * 灰色人体阈值、统一主体分权重、进入/退出近门门禁、进入可视区双门槛、近门锁存帧数。
      * `V1.2.3(B03030020)` 显式使用历史画像（关闭后续新增门禁）：
        * `enterVisibleRequireNearDoor=false`
        * `enterVisibleRequireContainment=false`
        * `enterVisibleNearDoorLatchFrames=0`
        * `exitVisibleRequireNearDoor=false`
      * `V1.3.0(B03030116)` 显式使用当前画像（保留后续门禁）：
        * `enterVisibleRequireNearDoor=true`
        * `enterVisibleRequireContainment=true`
        * `enterVisibleNearDoorLatchFrames=1`
        * `exitVisibleRequireNearDoor=true`
      * 调试文案补充门禁开关状态（是否启用近门/可视区门槛），便于复核“当前版本到底在跑哪套规则”。

---

## [190] 2026-03-03 03:45:00 - 更换算法选择控件并压缩Presence日志为纯值序列

**用户指令**：
> 设置页不是新增一行,是现在的控件有问题,所以看不到,你就直接用日志更新频率那个控件应该就没问题  
> 日志冗余还是很多啊,理论上应该每条里面只有参数没有变量名了啊,然后用格式来组织.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“当前算法看不到”并将 Presence 日志进一步压缩到“值序列”级别。
    *   修改文件：app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md
    *   涉及方法：SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onResume、SettingsHomeFragment.syncPresenceSelector、MainActivity.toReadablePresenceDecision、RoiLogAggregator.appendPresenceSnapshotIfNeeded、RoiLogAggregator.parsePresenceHistoryEntry、RoiLogAggregator.formatCompressedEntry
    *   关键改动：
      * 将 `spn_presence_algorithm_version` 替换为 `btn_presence_algorithm_version`，采用“按钮 + 单选弹窗”方式选算法，避免 Spinner 在当前主题下不可见。
      * 每次进入设置页与切换后都刷新按钮文本，保证当前算法始终直观可见。
      * `presence decision` 输出改为纯值序列：`REASON|h[...值...]|m[...值...]|x[...值...]`。
      * `presenceRecent` 历史项改为 `[{frame},{ms},{hash}]|{event}|{decision}|{counts}`，去掉字段名冗余。

---

## [019] 2026-03-03 03:45:00 - 更换算法选择控件并压缩Presence日志为纯值序列

**用户指令**：
> 设置页不是新增一行,是现在的控件有问题,所以看不到,你就直接用日志更新频率那个控件应该就没问题  
> 日志冗余还是很多啊,理论上应该每条里面只有参数没有变量名了啊,然后用格式来组织.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“当前算法看不到”并将 Presence 日志进一步压缩到“值序列”级别。
    *   修改文件：app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md
    *   涉及方法：SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onResume、SettingsHomeFragment.syncPresenceSelector、MainActivity.toReadablePresenceDecision、RoiLogAggregator.appendPresenceSnapshotIfNeeded、RoiLogAggregator.parsePresenceHistoryEntry、RoiLogAggregator.formatCompressedEntry
    *   关键改动：
      * 将 `spn_presence_algorithm_version` 替换为 `btn_presence_algorithm_version`，采用“按钮 + 单选弹窗”方式选算法，避免 Spinner 在当前主题下不可见。
      * 每次进入设置页与切换后都刷新按钮文本，保证当前算法始终直观可见。
      * `presence decision` 输出改为纯值序列：
        * `REASON|h[...值...]|m[...值...]|x[...值...]`
        * 其中 `h/m/x` 的顺序定义仅保留在 `presence schema` 一行。
      * `presenceRecent` 历史项改为纯值分段格式：
        * `[{frame},{ms},{hash}]|{event}|{decision}|{counts}`
        * 去掉 `event=/decision=/counts=` 等字段名冗余。

---

## [189] 2026-03-03 03:20:00 - 设置页算法选择可见性修复与Presence日志序列化省流

**用户指令**：
> 设置页不是新增一行,是现在的控件有问题,所以看不到,你就直接用日志更新频率那个控件应该就没问题  
> 顺序说明不需要放在wiki,写在日志里面就行.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“人数算法版本”下拉当前值不可见，并将 Presence 决策日志改为固定顺序短值串，顺序说明直接写入日志。
    *   修改文件：app/src/main/res/values/arrays.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md
    *   涉及方法：SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onResume、SettingsHomeFragment.buildPresenceOptionsInStableOrder、SettingsHomeFragment.syncPresenceSpinnerSelection、MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend、RoiLogAggregator.snapshotForPanel
    *   关键改动：
      * 设置页新增 `presence_algorithm_labels` 资源数组，并让“人数算法版本”Spinner采用与“日志更新频率”同款绑定方式。
      * 统一版本选项顺序（`AUTO + allVersionIds`），并在 `onResume` 强制同步 Spinner 当前选中。
      * `presence decision` 改为固定头字段 + 固定指标序列，并在日志中新增 `presence schema=...`。

---

## [018] 2026-03-03 03:20:00 - 设置页算法选择可见性修复与Presence日志序列化省流

**用户指令**：
> 设置页不是新增一行,是现在的控件有问题,所以看不到,你就直接用日志更新频率那个控件应该就没问题  
> 顺序说明不需要放在wiki,写在日志里面就行.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“人数算法版本”下拉当前值不可见，并将 Presence 决策日志改为固定顺序短值串，顺序说明直接写入日志。
    *   修改文件：app/src/main/res/values/arrays.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md
    *   涉及方法：SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onResume、SettingsHomeFragment.buildPresenceOptionsInStableOrder、SettingsHomeFragment.syncPresenceSpinnerSelection、MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend、RoiLogAggregator.snapshotForPanel
    *   关键改动：
      * 设置页新增 `presence_algorithm_labels` 资源数组，并让“人数算法版本”Spinner采用与“日志更新频率”同款绑定方式（`ArrayAdapter.createFromResource`）。
      * 统一版本选项顺序（`AUTO + allVersionIds`），并在 `onResume` 强制同步 Spinner 当前选中，确保不展开下拉也能看到当前算法。
      * `presence decision` 改为固定头字段 + 固定指标序列：
        * 头字段：`h=[f,t,fr,md,er,lc,sg]`
        * 指标序列：`m=[dd,dps,gpc,des,trc,src,sops,pac,srss,pts,das,scs,scsTh,srssTh,sopsTh,dnd,dad,dld,dalr]`
        * 附加字段：`x=[pacMin,gpm]`
      * 在调试面板日志中新增 `presence schema=...`，用于直接解释序列顺序，不再依赖 wiki。

---

## [188] 2026-03-03 02:48:00 - 进入近门锁存修复（1帧）与短键补齐

**用户指令**：
> 这是一次新状态,但是没有成功触发客厅进入厨房.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“ENTER_WAIT 1/2 后因近门瞬时抖动被重置”的漏触发问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend
    *   关键改动：
      * 客厅->可视房间近门判定改为：`dd <= max(dnd * 1.4, 0.025) || mnp`。
      * 增加 1 帧近门锁存，避免 `ENTER_WAIT` 被瞬时抖动清零。
      * 新增 `evrp/evlp/evnl` 日志字段。

---

## [017] 2026-03-03 02:48:00 - 进入近门锁存修复（1帧）与短键补齐

**用户指令**：
> 这是一次新状态,但是没有成功触发客厅进入厨房.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“ENTER_WAIT 1/2 后因近门瞬时抖动被重置”的漏触发问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、MainActivity.toReadablePresenceDecision、MainActivity.buildPresenceShortKeyLegend
    *   关键改动：
      * 客厅->可视房间近门判定从 `dps>0 || mnp` 改为：
        * `dd <= max(dnd * 1.4, 0.025) || mnp`
      * 增加 1 帧近门锁存：
        * 当同一候选已进入 `ENTER_WAIT`（`frames>=1`）时，下一帧近门不满足可继续一次，不立即清零。
      * 新增日志字段：
        * `enterVisibleNearDoorRawPass`
        * `enterVisibleNearDoorLatchPass`
        * `enterVisibleNearDoorLimit`
      * 对应短键与 wiki 对照补齐：
        * `evrp` / `evlp` / `evnl`

---

## [187] 2026-03-03 02:35:00 - Presence日志短键扩展与退出近门阈值放宽

**用户指令**：
> 日志里面还是有currentGroundX...这些很长的占用... 用尽量短的变量名... 缩写写在wiki。  
> 然后这次从厨房出来很远才触发出门.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：压缩 Presence 日志长度并修正“可视房间->客厅”偏晚触发。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、MainActivity.toReadablePresenceDecision、MainActivity.buildUnlockClipboardReport、MainActivity.buildDebugPanelClipboardReport
    *   关键改动：
      * 退出近门前置放宽：`exitVisibleNearDoorPass = (doorDist <= max(dynamicNearDist * 1.4, 0.025)) || motionNearDoorPassed`。
      * 新增 `xvnl` 并扩展大量短键映射。
      * 剪贴板快照中移除重复 `presence legend` 行。

---

## [016] 2026-03-03 02:35:00 - Presence日志短键扩展与退出近门阈值放宽

**用户指令**：
> 日志里面还是有currentGroundX...这些很长的占用... 用尽量短的变量名... 缩写写在wiki。  
> 然后这次从厨房出来很远才触发出门.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：压缩 Presence 日志长度并修正“可视房间->客厅”偏晚触发。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、MainActivity.toReadablePresenceDecision、MainActivity.buildUnlockClipboardReport、MainActivity.buildDebugPanelClipboardReport
    *   关键改动：
      * 退出近门前置放宽：
        * `exitVisibleNearDoorPass = (doorDist <= max(dynamicNearDist * 1.4, 0.025)) || motionNearDoorPassed`
      * 日志新增阈值字段：`exitVisibleNearDoorLimit`（后续短键 `xvnl`）。
      * 短键映射扩展：新增 `upw/ptm/tpc/evnp/xvnp/xvnl/evcp/evtm/evsm/psd/csd/pld/cld/dnx/dny/dmx/dmy/tcx/tcy/pgx/pgy/cgx/cgy/mhs/mnp`。
      * 剪贴板快照中移除每次重复的 `presence legend` 行。
      * 在 `wiki.md` 固化完整短键对照，作为唯一参考口径。

---

## [186] 2026-03-03 02:22:00 - 抑制客厅进子房间提前判定并补充关键点贡献日志

**用户指令**：
> 这里还有一大堆点在外面呢,怎么就进厨房了?  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：降低“客厅->可视子房间”提前触发，补齐“哪些点把目标房间分拉高”的可观测性。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.buildTopPoseContributors
    *   关键改动：
      * 新增进入双门槛：`target>=0.75` 且 `source<=0.20`。
      * 新增 `targetTopPoseContributors` 与进入/退出关键判据日志。

---

## [015] 2026-03-03 02:22:00 - 抑制客厅进子房间提前判定并补充关键点贡献日志

**用户指令**：
> 这里还有一大堆点在外面呢,怎么就进厨房了?  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：降低“客厅->可视子房间”提前触发，补齐“哪些点把目标房间分拉高”的可观测性。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.buildTopPoseContributors
    *   关键改动：
      * 对 `VISIBLE_DOOR_MOTION`（客厅->可视房间）新增双门槛：
        * `targetRoomContainmentRatio >= 0.75`
        * `sourceRoomContainmentRatio <= 0.20`
      * 对 `VISIBLE_OUTSIDE_POSE`（可视房间->客厅）补充近门前置，避免离门较远时仅靠分值误触发。
      * 调试日志新增：
        * `enterVisibleContainmentPass`
        * `enterVisibleTargetContainmentMin`
        * `enterVisibleSourceContainmentMax`
        * `targetTopPoseContributors`（目标房间内贡献最高关键点 Top5，格式 `k{idx}:{加权值}@{置信度}`）

---

## [185] 2026-03-03 02:10:00 - 修复V1.3.0远离门口误触发进入（仅限客厅->可视房间）

**用户指令**：
> 这次效果也很差,来回跳,很远就算进去了... 检查下有没有显著错误...  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：阻止“客厅->可视子房间”在未靠近门口时，仅凭房间综合分触发 `ENTER_WAIT/ENTER_OK`。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore
    *   关键改动：
      * 新增近门前置 `enterVisibleNearDoorPass`。
      * 通过条件补充：`poseAverageConfidence` 达标 + `enterVisibleNearDoorPass` + `switchConfidenceScore` 达标。

---

## [014] 2026-03-03 02:10:00 - 修复V1.3.0远离门口误触发进入（仅限客厅->可视房间）

**用户指令**：
> 这次效果也很差,来回跳,很远就算进去了... 检查下有没有显著错误...  
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：阻止“客厅->可视子房间”在未靠近门口时，仅凭房间综合分触发 `ENTER_WAIT/ENTER_OK`。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore
    *   关键改动：
      * 新增近门前置 `enterVisibleNearDoorPass`，仅在 `VISIBLE_DOOR_MOTION` 规则下生效。
      * 通过条件补充为：`poseAverageConfidence` 达标 + `enterVisibleNearDoorPass` + `switchConfidenceScore` 达标。
      * `SCORE_REJECT / ENTER_WAIT / ENTER_OK` 日志新增 `enterVisibleNearDoorPass` 字段，便于确认是否因“未近门”被拒绝。
      * 退出方向（可视房间->客厅）与统一分公式保持不变，本次不触碰。

---

## [184] 2026-03-03 01:46:00 - 增强门口法向链路调试日志（仅定位，不改判定）

**用户指令**：
> 关键是分数为什么这么低？特别是门口分数为什么会是零？... 你不知道原因就好好重新改日志我们重新去抓。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：补齐“门口接近已命中但门辅助分仍为 0”的全链路定位信息。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.computeDoorMotionStats
    *   关键改动：
      * 增加法向/横向投影、几何上下文、地面点轨迹、历史窗状态等调试字段。

---

## [013] 2026-03-03 01:46:00 - 增强门口法向链路调试日志（仅定位，不改判定）

**用户指令**：
> 关键是分数为什么这么低？特别是门口分数为什么会是零？... 你不知道原因就好好重新改日志我们重新去抓。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：补齐“门口接近已命中但门辅助分仍为 0”的全链路定位信息，便于确认法向符号、历史窗取样和地面点是否异常。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.computeDoorMotionStats
    *   关键改动：
      * 扩展 `DoorMotionStats` 与候选 `Candidate` 调试字段，新增并透出：
        * 法向/横向投影：`pastSignedDistance`、`currentSignedDistance`、`pastLateralDistance`、`currentLateralDistance`
        * 几何上下文：`doorNormalX/Y`、`doorMidX/Y`、`targetCentroidX/Y`
        * 地面点轨迹：`pastGroundX/Y`、`currentGroundX/Y`
        * 历史窗状态：`motionHistorySize`、`motionNearDoorPassed`
      * 在 `SCORE_REJECT / ENTER_WAIT / ENTER_OK` 的 `presence decision` 文本中统一输出上述字段。
      * 保持判定逻辑与阈值不变，仅增强可观测性。

---

## [183] 2026-03-03 01:30:00 - 修复调试面板开关崩溃（RoiLogAggregator 方法兼容）

**用户指令**：
> 点击调试面板开关挂了

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 `NoSuchMethodError: snapshotForPanel()` 导致的主线程崩溃。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md
    *   涉及方法：RoiLogAggregator.snapshotForPanel
    *   关键改动：
      * 增加无参重载 `snapshotForPanel()`，保持旧调用兼容。

---

## [012] 2026-03-03 01:30:00 - 修复调试面板开关崩溃（RoiLogAggregator 方法兼容）

**用户指令**：
> 点击调试面板开关挂了

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 `NoSuchMethodError: snapshotForPanel()` 导致的主线程崩溃。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md
    *   涉及方法：RoiLogAggregator.snapshotForPanel
    *   关键改动：
      * 增加无参重载 `snapshotForPanel()`，内部转调带参版本 `snapshotForPanel(includePresenceHistory = true)`。
      * 保持原有带参接口不变，兼容旧调用路径与热更新/overlay 场景。

---

## [182] 2026-03-03 01:16:00 - 人数算法V1.3.0：唯一综合分标准（主体分+法向辅助分）

**用户指令**：
> 我再说一次,我们应该用一个唯一标准:人在房间内综合分...  
> 用1.3.0做版本号,进出都是这个分.没问题就开始

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将进/出房间统一到同一个综合分，避免候选切换导致抖动。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.processFrame、PresenceAlgorithmRegistry.create
    *   关键改动：
      * DOOR 模式统一分：`0.35*poseTransitionScore + 0.65*doorAssistScore`。
      * 发布 `V1.3.0(B03030116)` 并设为 `AUTO_LATEST`。

---

## [011] 2026-03-03 01:16:00 - 人数算法V1.3.0：唯一综合分标准（主体分+法向辅助分）

**用户指令**：
> 我再说一次,我们应该用一个唯一标准:人在房间内综合分.这个综合分应该主要由pose点和可是区域的关系来计算.辅助是法线向量,主要是为了规避路过门的误判.有这两个来综合计算出一个分值.  
> 用1.3.0做版本号,进出都是这个分.没问题就开始

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将进/出房间统一到同一个综合分，避免门候选与恢复候选来回切换导致的计数延迟与抖动。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.processFrame、PresenceAlgorithmRegistry.create
    *   关键改动：
      * DOOR 模式切换分统一为：`0.35*poseTransitionScore + 0.65*doorAssistScore`。
      * `poseTransitionScore` 由源/目标房间关键点加权归属分计算；`doorAssistScore` 由门法向通过后的门接近度提供。
      * DOOR 模式统一门槛：门法向通过 + 门接近度门槛 + 姿态置信门槛 + 综合分门槛。
      * 对“可视子房间 -> 客厅”禁用 `POLYGON_RECOVERY` 候选，防止与门候选交替造成 `ENTER_WAIT` 被反复重置。
      * 发布 `V1.3.0(B03030116)` 并设为 `AUTO_LATEST`。

---

## [181] 2026-03-03 00:20:00 - 人数算法迭代：进出统一为关键点置信度加权分（脚踝高权重）

**用户指令**：
> 置信度不应该只是个门槛,还应该参与加权计算.(进出都是),脚应该权重很大(前提算上置信度)  
> 我们还是用分,不是用比例.进出都靠分,而且理论上这个分能统一.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把可视房间进出判定统一到“关键点置信度加权分”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.processFrame、PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.computeRoomPoseScore、PresenceAlgorithmRegistry.create
    *   关键改动：
      * 引入关键点置信度×部位权重归属分，脚踝最高权重。
      * 发布 `V1.2.3(B03030020)` 并设为 `AUTO_LATEST`。

---

## [010] 2026-03-03 00:20:00 - 人数算法迭代：进出统一为关键点置信度加权分（脚踝高权重）

**用户指令**：
> 置信度不应该只是个门槛,还应该参与加权计算.(进出都是),脚应该权重很大(前提算上置信度)  
> 我们还是用分,不是用比例.进出都靠分,而且理论上这个分能统一.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把可视房间进出判定统一到“关键点置信度加权分”，降低人框面积先入门导致的提前误判。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.processFrame、PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmV1_1_0_B03021639.computeRoomPoseScore、PresenceAlgorithmRegistry.create
    *   关键改动：
      * 新增房间归属分 `computeRoomPoseScore`：按“关键点置信度 × 部位权重”计算房间内得分占比。
      * 脚踝权重提升为最高（3.0），膝/髋/肩次之，其他点为基础权重。
      * 进入与离开统一使用该分：`targetRoomContainmentRatio` 与 `sourceRoomContainmentRatio` 均改为关键点加权归属分；`sourceRoomOutsidePoseScore = 1 - sourceRoomContainmentRatio`。
      * `POLYGON_RECOVERY` 与可视区同步分支改为使用关键点加权分，不再以人框网格面积比例作为主证据。
      * 发布新版本 `V1.2.3(B03030020)` 并设为 `AUTO_LATEST`。

---

## [180] 2026-03-02 23:59:00 - 人数算法联调：灰色人体不参与切换，进入更稳、退出更早

**用户指令**：
> 我觉得的关键是,当人体还是灰色时不要参与判断,这样就不会因为乱飘的pose点影响判断了.  
> GeminiHistory暂时不用检查了.你现在只做了灰色,但是其他我说的阈值前后问题也要一起调节吖

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：联调 Presence 切换判定，抑制灰色低质姿态误判，同时优化“进入偏早/退出偏晚”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmRegistry.create
    *   关键改动：
      * 新增灰色门禁：`poseAverageConfidence < 0.55` 直接 `SKIP_GRAY_POSE`。
      * 新增版本 `V1.2.2(B03022359)` 并作为 `AUTO_LATEST`。
      * 联调参数：`enterThreshold=0.58`、`enterAMin=0.35`、`exitOutsidePoseScoreThreshold=0.08`。

---

## [009] 2026-03-02 23:59:00 - 人数算法联调：灰色人体不参与切换，进入更稳、退出更早

**用户指令**：
> 我觉得的关键是,当人体还是灰色时不要参与判断,这样就不会因为乱飘的pose点影响判断了.  
> GeminiHistory暂时不用检查了.你现在只做了灰色,但是其他我说的阈值前后问题也要一起调节吖

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：联调 Presence 切换判定，抑制灰色低质姿态误判，同时优化“进入偏早/退出偏晚”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmV1_1_0_B03021639.kt、app/src/main/java/com/example/roomxxx0102/logic/presence/PresenceAlgorithmRegistry.kt、wiki.md、codexHistory.md
    *   涉及方法：PresenceAlgorithmV1_1_0_B03021639.evaluateVisibleEnterByScore、PresenceAlgorithmRegistry.create
    *   关键改动：
      * 在可视切换评估入口新增灰色门禁：`poseAverageConfidence < 0.55` 直接 `SKIP_GRAY_POSE`，不参与切换判定。
      * 新增版本 `V1.2.2(B03022359)`，并作为 `AUTO_LATEST` 默认最新。
      * 参数联调（不新增新参数）：
        * `enterThreshold = 0.58`
        * `enterAMin = 0.35`
        * `exitOutsidePoseScoreThreshold = 0.08`

---

## [179] 2026-03-02 23:36:42 - V1.2.1现有参数收敛（进更稳/出更早）

**用户指令**：
> 不要再加变量，先用现有参数收敛；调整为减少“进入太早、出来太晚”。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：不新增参数，仅用现有参数与判定项收敛进入/离开时机
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - 可视进入分支 `VISIBLE_DOOR_MOTION` 由 `emp` 单条件改为：
          - `emp && dps>=enterAMin && scs>=enterThreshold`
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.2.1(B03022335)` 并设为最新
        - 参数微调：
          - `enterDoorDynamicRatio = 0.08`（原 0.10）
          - `exitOutsidePoseScoreThreshold = 0.10`（原 0.12）
    *   wiki.md：
        - 新增 `V1.2.1` 说明与参数变更记录

---

## [178] 2026-03-02 23:22:12 - Presence日志压缩与去重

**用户指令**：
> 日志字符数太长；presenceRecent 与 presence recent 有重复帧；变量名重复过多需压缩。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：减少调试日志体积，降低复制与分析成本
    *   RoiLogAggregator.kt：
        - `snapshotForPanel` 增加 `includePresenceHistory` 参数
        - `snapshotPresenceHistory` 增加连续相同内容合并输出（区间帧 + 次数）
        - 同帧重复更新仅保留最后一条
        - 合并行增加短 hash（用于区分“看似相同但内容不同”）
    *   MainActivity.kt：
        - 调试导出报告只保留一份 `presenceRecent`（移除重复区块）
        - `presence decision` 字段名压缩为短键（dd/dps/scs 等）
        - 报告新增 `presence legend` 一行用于短键对照
    *   wiki.md：
        - 新增 `1.6 Presence 调试日志压缩` 说明

---

## [177] 2026-03-02 23:12:07 - 新增“切换房间后暂停播放”开关

**用户指令**：
> 设置中加一个开关“切换房间后暂停播放”，打开后遇到房间切换自动点击一次暂停。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在房间切换事件发生时自动暂停，便于逐事件观察
    *   AppSettings.kt：
        - 新增配置项 `isPauseOnRoomSwitchEnabled`
        - 新增持久化键 `pause_on_room_switch`
        - 新增 `setPauseOnRoomSwitchEnabled(...)`
    *   fragment_settings_home.xml：
        - 新增设置开关 `switch_pause_on_room_switch`
    *   SettingsHomeFragment.kt：
        - 初始化并监听 `switchPauseOnRoomSwitch`
        - 与 `AppSettings.isPauseOnRoomSwitchEnabled` 双向绑定
    *   MainActivity.kt：
        - 在 Presence 切换事件处理处新增自动暂停：
          - 条件：有切换事件 + 开关开启 + 当前 `PLAYING`
          - 动作：调用一次 `togglePause(btnPause)`（等效点击一次暂停）
          - 横幅提示：`检测到房间切换，已自动暂停`
        - 调试快照设置行新增 `pauseOnSwitch` 字段
    *   wiki.md：
        - 新增 `1.5 切换房间自动暂停开关` 说明

---

## [176] 2026-03-02 23:02:12 - V1.2.0可视房间进入改为门洞法向穿越

**用户指令**：
> 采用“肩+脚+全身框”估计地面点，进入判定走门洞法向推进，区分贴门经过；并去掉日志重复帧。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：减少“经过门口却误判进入”的问题，保持退出逻辑分流不变
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - 新增地面点估计与历史：
          - `estimateGroundPoint(...)`
          - `appendGroundPointHistory(...)`
          - `computeDoorMotionStats(...)`
        - 可视房间进入改为门洞法向穿越主判定：
          - `doorAdvanceDelta(Δs)`
          - `doorLateralDelta(Δq)`
          - `doorAdvanceLateralRatio`
          - `enterMotionPass`
        - 可视进入使用 `enterMotionPass`；退出继续沿用 `V1.1.9` 分流
        - 调试文案新增穿越字段与阈值字段
    *   RoomTransitionEstimator.kt：
        - 新增进入运动参数：
          - `enterMotionWindowFrames`
          - `enterNormalAdvanceMin`
          - `enterLateralRatioMin`
          - `enterGroundFootBlendStartConfidence`
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.2.0(B03022300)` 并设为最新
    *   RoiLogAggregator.kt：
        - `presenceRecent` 同一帧仅保留最后一条，去除重复帧
    *   wiki.md：
        - 新增 `V1.2.0` 规则与参数说明

---

## [175] 2026-03-02 22:07:00 - V1.1.9可视/非可视退出逻辑分流

**用户指令**：
> 只有可视房间使用新增的退出规则，非可视房间仍然走之前逻辑。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：解决“可视子房间出来不减人”，同时保持盲区等非可视房间旧行为不变
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - 在 `evaluateVisibleEnterByScore(...)` 增加分流：
          - 可视子房间 -> 客厅：仅使用 `sourceRoomOutsidePoseScore` 与 `poseAverageConfidence` 判定
          - 非可视房间 -> 客厅：保持旧逻辑（`sourceRoomStayScore + doorProximityScore`）
        - 可视退出分支不再被 `AMBIGUOUS_DOOR` 提前拦截
        - 调试字段新增 `exitRule=VISIBLE_OUTSIDE_POSE|LEGACY`
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.1.9(B03022207)` 并设为最新
    *   wiki.md：
        - 新增 `V1.1.9` 规则说明（可视/非可视退出分流）

---

## [174] 2026-03-02 21:56:06 - V1.1.8子房间离开改为可视区外关键点评分

**用户指令**：
> 先解决“从可视房间出来人数没减少”；新增“处于可视区域外的综合得分”，暂不区分身体部位权重，用于判定离开房间并区分房间内消失。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“子房间->客厅”离开漏判，改用关键点可视区外评分驱动离开判定
    *   RoomTransitionEstimator.kt：
        - `PresenceTrackObservation` 新增 `keypoints`
        - 新增 `PresenceKeypoint` 数据结构
        - `PresenceEstimatorParams` 新增：
          - `exitOutsidePoseScoreThreshold`
          - `exitPosePointMinConfidence`
          - `exitPoseMinConfidence`
    *   MainActivity.kt：
        - 构建 `PresenceTrackObservation` 时传入 `pose.keypoints`（x/y/conf）
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - `evaluateVisibleEnterByScore(...)` 新增退出相关指标：
          - `sourceRoomOutsidePoseScore`
          - `poseAverageConfidence`
        - `子房间->客厅` 判定改为：
          - `sourceRoomOutsidePoseScore >= exitOutsidePoseScoreThreshold`
          - `poseAverageConfidence >= exitPoseMinConfidence`
          - `doorProximityScore >= enterAMin`
        - 新增方法：
          - `computeOutsidePoseScore(...)`
          - `computeAveragePoseConfidence(...)`
        - 调试文案增加退出评分与置信度字段，便于排查
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.1.8(B03022153)` 并设为最新
    *   wiki.md：
        - 新增 `V1.1.8` 规则说明与参数定义

---

## [173] 2026-03-02 20:15:52 - V1.1.7门口接近度改为0~0.1身高线性衰减

**用户指令**：
> 门口证据分按“距离=0且置信=1为满分；超过0.1×身高为0分”来实现。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将门口证据分规则与现场调试口径严格对齐，减少进入判定偏激进
    *   RoomTransitionEstimator.kt：
        - `PresenceEstimatorParams` 简化动态门距参数，仅保留 `enterDoorDynamicRatio`
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - `doorProximityScore` 计算改为单段线性：
          - `doorProximityScore = max(0, 1 - doorDist / dynamicNearDist)`
          - `dynamicNearDist = personBoxHeight * enterDoorDynamicRatio`
        - 去除旧的 `d0/d1` 平台映射
        - 保留 `doorEvidenceScore = doorProximityScore * groundPointConfidence`
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.1.7(B03022014)` 并设为最新
    *   wiki.md：
        - 新增 `V1.1.7` 规则说明，明确“0.1*身高内有效，超过即0”

---

## [172] 2026-03-02 19:58:25 - V1.1.6门距阈值改为按人框高度动态计算

**用户指令**：
> 先看数据合理性；建议门距阈值参考人的身高（约十分之一）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：降低固定门距阈值带来的尺度失配，缓解“进入过于激进”
    *   RoomTransitionEstimator.kt：
        - `PresenceEstimatorParams` 新增动态门距参数：
          - `enterDoorDynamicRatio`
          - `enterDoorDynamicMin`
          - `enterDoorDynamicMax`
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - 新增 `computeDynamicNearDoorDistance(personBox)`：
          - `dynamicNearDist = clamp(personBoxHeight * ratio, min, max)`
        - `doorProximityScore` 映射改为动态区间：
          - `d0 = 0.5 * dynamicNearDist`
          - `d1 = 1.5 * dynamicNearDist`
        - 调试字段增加 `dynamicNearDist`
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.1.6(B03021957)` 并设为最新
        - 默认参数：`ratio=0.10`、`min=0.01`、`max=0.06`
    *   wiki.md：
        - 新增 `V1.1.6` 规则说明与参数定义

---

## [171] 2026-03-02 19:47:31 - +1帧长按遇房间切换自动停止

**用户指令**：
> 新增逻辑：按住 `+1帧` 时如果发生房间转移，就终止继续走；需要重新按下才继续。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免长按 `+1帧` 在发生切换后继续推进，便于逐事件观察
    *   MainActivity.kt：
        - 在 Pose 分析回调的 `runOnUiThread` 中新增门控：
          - 条件：`presenceResult.events.isNotEmpty() && seekHoldActive && seekHoldDirection > 0 && currentPlayState == STILL`
          - 命中后执行 `stopSeekHold()`，等效自动抬手
          - 显示横幅：`检测到房间切换，已停止+1帧长按`
        - 范围限定：仅影响 `+1帧` 长按；单击 `+1帧`、`-1帧`、`±5s` 不受影响

---

## [170] 2026-03-02 18:53:35 - V1.1.5新增通用polygon恢复候选

**用户指令**：
> 入户到厨房的判定问题先单独解决；逻辑要通用，不要入户特判。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“状态卡在旧房间导致无法迁移到当前可视房间”的通用问题，不添加入户专属分支
    *   RoomTransitionEstimator.kt：
        - `PresenceEstimatorParams` 新增 `recoveryTargetContainmentMin`（默认 `0.60`）
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - `evaluateVisibleEnterByScore(...)` 入参新增 `polygonRoomId`
        - 在门候选之外新增 `POLYGON_RECOVERY` 通用候选（基于 polygon 命中，不依赖门邻接）
        - `POLYGON_RECOVERY` 通过条件：
          - `targetRoomContainmentRatio >= recoveryTargetContainmentMin`
          - `sourceRoomStayScore <= exitSourceRoomScoreThreshold`
          - 连续帧计数沿用 `enterConfirmFrames`
        - 调试文案新增 `mode=POLYGON_RECOVERY:*` 与 `recoveryTargetContainmentMin`
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.1.5(B03021852)` 并设为最新默认
        - 参数：沿用 V1.1.4 + `recoveryTargetContainmentMin=0.60`
    *   wiki.md：
        - 新增 `1.4.9 V1.1.5` 说明，强调“通用恢复候选”语义与触发门槛

---

## [169] 2026-03-02 18:39:38 - 修复长按播放时MediaPlayer并发读取崩溃

**用户指令**：
> 长按播放崩了（`MediaPlayer.getCurrentPosition` 抛 `IllegalStateException`）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：消除长按“重启播放”时 `MediaPlayer` 释放与并发读取位置导致的崩溃
    *   VideoFeeder.kt：
        - `getCurrentPositionMs()` 改为安全读取：捕获 `IllegalStateException` 并返回 `null`，同时记录轻量 `Log.w`
        - `stop()` 调整为先摘除共享引用（`val mp = mediaPlayer; mediaPlayer = null`），再对旧实例执行 `stop/release`，降低并发窗口

---

## [168] 2026-03-02 18:33:04 - V1.1.4出房间改为源房间保留分阈值判定

**用户指令**：
> 先试试（按“进入/离开双阈值和统一分数语义”方向推进）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：减少门口反复横跳，避免“子房间->客厅”仅靠目标分数过线就提前触发
    *   RoomTransitionEstimator.kt：
        - `PresenceEstimatorParams` 新增 `exitSourceRoomScoreThreshold`（默认 0.40）
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - `evaluateVisibleEnterByScore(...)` 新增源房间指标：
          - `sourceRoomContainmentRatio`
          - `sourceRoomStayScore = wA*doorEvidenceScore + wC*sourceRoomContainmentRatio`
        - 判定分支调整：
          - `客厅->子房间`：沿用 `switchConfidenceScore >= switchThreshold`
          - `子房间->客厅`：改为 `sourceRoomStayScore <= exitSourceRoomScoreThreshold` 且 `doorProximityScore >= enterAMin`
        - 调试字段新增并可读化：`sourceRoomContainmentRatio/sourceRoomStayScore/exitSourceRoomScoreThreshold/mode`
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.1.4(B03021831)` 并设为最新
        - 默认参数：沿用 V1.1.3 + `exitSourceRoomScoreThreshold=0.40`
    *   wiki.md：
        - 新增 `V1.1.4` 规则说明
        - 补充 `sourceRoomContainmentRatio/sourceRoomStayScore/exitSourceRoomScoreThreshold` 指标定义

---

## [167] 2026-03-02 18:21:07 - Presence指标命名与调试文案可读化

**用户指令**：
> C 又是啥玩意儿，看不懂；变量命名要一眼可懂，且 wiki 里能找到定义。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提升 Presence 调试可读性，仅重构命名与文案，不改变判定逻辑
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - 候选指标重命名：
          - `aPos -> doorProximityScore`
          - `aConf -> groundPointConfidence`
          - `aScore -> doorEvidenceScore`
          - `cScore -> targetRoomContainmentRatio`
          - `score -> switchConfidenceScore`
        - 调试文本字段改名：
          - `Apos/Aconf/A/C/Score/th`
          - 改为 `doorProximityScore/groundPointConfidence/doorEvidenceScore/targetRoomContainmentRatio/switchConfidenceScore/switchThreshold`
        - 额外补充 `doorDist`，便于直接查看门口距离
    *   wiki.md：
        - `1.4.2` 改为使用新命名描述判定流程
        - 新增 `1.4.7 Presence 指标定义表`，逐条定义调试面板字段含义与关系

---

## [166] 2026-03-02 18:14:35 - Presence调试面板精简并新增判定历史

**用户指令**：
> 多补充前几次产生判定时的情况，然后没用的全部清理掉。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提升 Presence 排查可读性，减少 ROI 噪声信息，支持回看“最近几次判定”上下文
    *   RoiLogAggregator.kt：
        - `updatePresenceDebug(...)` 增加 `posMs` 参数
        - 新增 Presence 判定历史缓存（最多 8 条），仅记录关键判定（`ENTER_ / SCORE_REJECT / AMBIGUOUS_DOOR / NO_VISIBLE_DOOR / VISIBLE_SYNC` 或有事件）
        - `snapshotForPanel()` 精简：移除本次排查无关的 `frameDigest/top/clamp/dx-dy` 等冗余行，保留 Presence 核心信息
        - 新增 `snapshotPresenceHistory()` 供调试面板与剪贴板复用
    *   MainActivity.kt：
        - 调用 `RoiLogAggregator.updatePresenceDebug(...)` 时传入 `videoPosMs`
        - 长按“调试面板”复制内容改为 `presenceRecent`（最近判定历史），不再附加 `recentFrames`
        - 去掉 `presence event/decision` 里的 `track=数字`，避免阅读干扰
        - 额外修复：将 3 处双肩阈值比较临时改为 `0.7f`，规避当前 `MainActivity` 对 `POSE_HIGH_CONFIDENCE_THRESHOLD` 解析异常导致的编译失败
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - 判定文本补充 `dist(门距)` 与 `th(阈值)`，便于直接看出为何触发/拒绝

---

## [165] 2026-03-02 18:04:26 - 新增V1.1.3关闭可视区兜底并设为默认最新

**用户指令**：
> 刚才不是说先关了兜底吗?

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：先关闭 `VISIBLE_POLYGON_SYNC`，只看门口 AC 主判定，定位“厨房/客厅反复横跳”
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - 增加 `visibleSyncEnabled = params.visiblePolygonSyncFrames > 0`
        - 仅在 `visibleSyncEnabled` 为 true 时执行可视区兜底同步分支
    *   PresenceAlgorithmRegistry.kt：
        - 新增版本 `V1.1.3(B03021801)`
        - 该版本参数沿用 V1.1.2，并设置 `visiblePolygonSyncFrames = 0`
        - 版本列表追加 V1.1.3，`AUTO_LATEST` 自动指向该版本
    *   wiki.md：
        - 新增 V1.1.3 条目与参数说明
        - 更新“当前最新版本”为 `V1.1.3(B03021801)`

---

## [164] 2026-03-02 17:50:01 - +1帧补偿扩展为两次并增加横幅提示

**用户指令**：
> lock 还是会掉；把之前 +10ms 再多执行一次，并且每次都要有明显提示。只改横幅提示，不要 toast。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：降低 +1帧落在重复帧导致的静态误判，且让每次补偿都可见
    *   VideoFeeder.kt：
        - `applyOneTimeForwardNudgeIfNeeded` 改为最多两次 `+10ms`（总计最多 +20ms）
        - 新增 `StepSeekDebug.nudgeCount` 与累计 `nudgeDeltaMs`
        - 新增 `onStepNudge` 回调，每次补偿触发时发送 `步进补偿 +10ms (第N次)`
    *   MainActivity.kt：接入 `videoFeeder.onStepNudge`，通过 `overlayView.showUnlockBanner(...)` 显示右上角横幅提示
    *   说明：按要求未新增 Toast，仅横幅提示

---

## [163] 2026-03-02 17:43:44 - 新增V1.1.2门口主判定参数放宽版本

**用户指令**：
> 确认日志时机正确后，按讨论先修门口主判定：放宽门口评分参数并继续迭代，不依赖兜底来掩盖问题。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：优先提升“门口主路径”触发率，减少人眼已进入但事件未触发的情况
    *   PresenceAlgorithmV1_1_0_B03021639.kt：算法实现支持外部注入 `versionId`（便于同实现多版本并行）
    *   PresenceAlgorithmRegistry.kt：新增 `V1.1.2(B03021736)` 并设为最新；在 `create()` 中为该版本注入放宽参数：
        - `enterDoorD0 = 0.015`
        - `enterDoorD1 = 0.08`
        - `enterThreshold = 0.52`
    *   wiki.md：同步 `V1.1.2` 版本与参数调整说明

---

## [162] 2026-03-02 17:31:04 - V1.1.1可读化面板与可视区连续帧兜底同步

**用户指令**：
> 日志里不要长ID；解释门口事件失败原因；并在可视区连续命中时做人数兜底同步。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：解决“面板可读性差 + 可视进入漏更新”
    *   PresenceAlgorithmV1_1_0_B03021639.kt（实现已升级为 `V1.1.1(B03021717)`）：
        - 每帧 AC 判定保留
        - 新增 `enterConfirmedGraceFrames` 掉锁容忍生效路径
        - 新增 `VISIBLE_POLYGON_SYNC` 兜底：连续 `visiblePolygonSyncFrames=3` 帧命中同一可视房间且 `C>=0.55` 时更新 persistent 人数
        - decision 持续输出 `VISIBLE_SYNC_WAIT/REJECT/OK` 等原因
    *   RoomTransitionEstimator.kt：新增参数 `visiblePolygonSyncFrames / visiblePolygonSyncMinContainment`，新增事件类型 `VISIBLE_POLYGON_SYNC`
    *   PresenceAlgorithmRegistry.kt：新增版本 `V1.1.1(B03021717)` 并作为最新版本
    *   MainActivity.kt：调试面板 presence 文本改为房间名（去除长 UUID/doorId）；`event/decision/counts` 均做可读化；位置切换横幅继续保留
    *   RoiLogAggregator.kt：`updatePresenceDebug` 改为接收格式化后的字符串（由 MainActivity 统一生成）
    *   wiki.md：同步记录 V1.1.1 与 `VISIBLE_POLYGON_SYNC` 规则

---

## [161] 2026-03-02 17:17:10 - V1.1改为每帧AC判定并增加掉锁容忍与位置切换提示

**用户指令**：
> 不应只在消失时判定；分数超过阈值就应判定进入。并要求：每帧都可计算、decision持续可见、掉锁遮挡场景容忍更长、人数命中时右上角显示“位置切换(原因)”。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：解决“门中消失未刷新”与“decision长期为空”问题，提高可视房间进入判定可解释性与可达性
    *   RoomTransitionEstimator.kt：`PresenceEstimatorParams` 新增 `enterConfirmedGraceFrames`（默认24）
    *   PresenceAlgorithmV1_1_0_B03021639.kt：
        - 去除“先命中目标房间 polygon 才计算”的前置，改为每帧对当前房间可达的可视门线计算 AC 分数
        - 新增近期CONFIRMED容忍窗口：掉锁后在 grace 帧内继续判定进入
        - `rejectedReasons` 持续输出（含 `NO_OBSERVATION / NOT_CONFIRMED / SCORE_REJECT / ENTER_WAIT / ENTER_OK`），避免面板 `presence decision` 为空
        - pending 计数改为“只要该 track 本帧被观测到就不推进”，避免非CONFIRMED时误累加
    *   MainActivity.kt：Presence 事件触发时在右上角提示 `位置切换: from->to (reason)`
    *   wiki.md：同步记录 V1.1 新规则和参数

---

## [160] 2026-03-02 17:01:16 - 调试面板按钮长按复制即时快照

**用户指令**：
> 长按测试面板按钮复制调试信息，并说明最佳复制时机。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：降低现场排查成本，支持一键导出“当前面板+最近帧”上下文
    *   MainActivity.kt：`btnDebugPanel` 新增 `setOnLongClickListener`
    *   MainActivity.kt：新增 `buildDebugPanelClipboardReport()`，导出内容包含 `playState/videoPosMs/settings/presenceAlgo + panel + recentFrames`
    *   MainActivity.kt：复用 `copyTextToClipboard()` 写入剪贴板，成功后 toast 提示

---

## [159] 2026-03-02 16:53:16 - 调试面板新增Presence判因四行信息

**用户指令**：
> 这次表现很糟糕，把需要的东西放到调试面板里，并说明如何判断问题；尽量少放必要信息。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 Presence 判定核心因子直接放进右侧调试面板，减少反复抓 log 定位成本
    *   RoiLogAggregator.kt：新增 `updatePresenceDebug()`；面板追加 4 行 `presence algo/event/decision/counts`
    *   MainActivity.kt：每帧 Presence 计算后调用 `RoiLogAggregator.updatePresenceDebug(...)` 注入算法版本、事件、判定原因、人数快照
    *   说明：`presence decision` 直接显示算法拒绝原因文本（在 V1.1 下包含 A/C/Score 等关键数值）

---

## [158] 2026-03-02 16:46:39 - V1.1可视房间进入改为AC评分判定

**用户指令**：
> 判定人是否进入带可视区域房间较差，经常出现人消失在门中但未刷新；参考 A(门口接近)+C(人框被可视区包含) 的加权思路，落地到项目里。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提升“门中消失但未切房”的判定稳定性，在不破坏盲区流程前提下增强可视房间进入识别
    *   RoomTransitionEstimator.kt：扩展 `PresenceTrackObservation`（新增 `groundConfidence/personBox`）、新增 `PresenceRect`、补充 AC 评分参数（`enterDoorD0/D1`、`enterWeightA/C`、`enterThreshold`、`enterConfirmFrames` 等）
    *   PresenceAlgorithmV1_1_0_B03021639.kt：新增 `V1.1.0(B03021639)` 算法实现；可视房间进入改为 `A=A_pos*A_conf` + `C=containment` + `Score` 连续帧确认；低置信度走 `C_strict + A_min_strict` 兜底；盲区 pending 逻辑保持兼容
    *   PresenceAlgorithmRegistry.kt：注册 `V1.1.0(B03021639)`，并作为 `AUTO_LATEST` 默认落点
    *   MainActivity.kt：构建 Presence 观测时传入 `personBox` 与 `estimateGroundConfidence()`
    *   wiki.md：新增 Presence 版本与 V1.1 AC 判定规则/默认参数文档

---

## [157] 2026-03-02 16:39:31 - 引入人数算法版本管理与设置切换

**用户指令**：
> 从现在开始要进行人数计算匹配算法迭代维护，支持版本管理并可在设置中切换；默认使用最新版本。先生成第一个版本：V1.0.0(B03011413)。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把 Presence 算法从“硬编码单实现”升级为“可版本切换”，为后续迭代做低风险对照
    *   PresenceAlgorithmEngine.kt：新增 `PresenceAlgorithmEngine` 接口，统一 `processFrame/reset/versionId` 能力
    *   PresenceAlgorithmRegistry.kt：新增算法注册表；首版注册 `V1.0.0(B03011413)`；提供 `AUTO_LATEST`、`resolveVersionId()`、`buildOptions()`、`create()`
    *   AppSettings.kt：新增 `presenceAlgorithmVersion` 持久化配置与 `setPresenceAlgorithmVersion()`；默认 `AUTO_LATEST`
    *   fragment_settings_home.xml：新增“人数算法版本”下拉控件 `spn_presence_algorithm_version`
    *   SettingsHomeFragment.kt：接入版本下拉初始化、回填与保存逻辑（基于注册表动态选项）
    *   MainActivity.kt：将 `RoomTransitionEstimator` 直接实例替换为版本引擎；新增 `ensurePresenceAlgorithmVersion()` 在 `onCreate/onResume` 同步设置；Presence 日志追加 `algo=版本号`

---

## [156] 2026-03-02 15:53:13 - 支持+-1帧长按连续步进

**用户指令**：
> 将 +-1 帧改为可长按，长按速度约为正常播放的 0.5x。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在静止模式下提供更顺滑的慢放排查方式，减少反复点击成本
    *   VideoFeeder.kt：新增 `getFrameStepMs()` 只读接口，供 UI 计算连续步进频率
    *   MainActivity.kt：为 `btnRewind/btnForward` 增加 `OnTouchListener` 长按处理
    *   MainActivity.kt：新增 `startSeekHold/stopSeekHold/seekHoldRunnable`，`ACTION_DOWN` 后 500ms 启动连续步进
    *   MainActivity.kt：连续步进间隔采用 `frameStepMs * 2`（约 0.5x 正常播放速度）；仅在 `STILL` 状态启用
    *   MainActivity.kt：`onPause()` 时主动 `stopSeekHold()`，防止切后台后残留触发

---

## [155] 2026-03-02 03:28:52 - +1帧重复帧自动补一次+10ms

**用户指令**：
> 不改 lock 规则；当 +1 帧出现“切到同一帧”时，最多自动再加 10ms，而且仅一次。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不调整 lock/unlock 规则前提下，降低 `+1帧` 落在重复解码帧导致的误触发
    *   VideoFeeder.kt：扩展 `StepSeekDebug`（增加 baseDigest / nudgeApplied / nudgeDelta / nudgeBefore / nudgeAfter）
    *   VideoFeeder.kt：`seekForwardFrame()` 记录 seek 前摘要并挂起一次性补偿任务g
    *   VideoFeeder.kt：分析循环中若检测到“当前摘要 == seek前摘要”，执行一次 `+10ms` 补偿并跳过当轮分析
    *   VideoFeeder.kt：`stop()` 补充清理补偿相关状态，防止跨会话串扰
    *   MainActivity.kt：unlock 剪贴板报告新增 `stepNudge` 行，显示补偿是否触发与前后位置

---

## [154] 2026-03-02 03:18:23 - 增强+1帧重复取帧诊断快照

**用户指令**：
> 在不改 lock 规则前提下，先定位“正常播放与 +1 帧行为差异”；把 seek 前后位置与帧级线索写入 unlock 剪贴板快照。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位 `+1帧` 是否出现重复取帧/近似取帧，解释与正常播放行为差异
    *   VideoFeeder.kt：新增 `StepSeekDebug`；`seekForwardFrame/seekBackwardFrame` 返回 step seek 诊断；记录 `seekComplete` 位置与时间；新增 `getCurrentPositionMs/getLastSeekCompletePositionMs/getLastSeekCompleteAtMs/peekLastStepSeekDebug`
    *   VideoFeeder.kt：在分析循环计算轻量帧摘要哈希（8x8 亮度采样 FNV-1a），并通过 `RoiLogAggregator.updateFrameDigest(...)` 汇总
    *   RoiLogAggregator.kt：新增 `updateFrameDigest`；将 `frameDigest + positionMs + temporalAdvanced` 写入 `snapshotForPanel`、`recentFrames` 与统一 `RF_ROI` 日志
    *   MainActivity.kt：静止态 `+1帧` 时缓存本次 `StepSeekDebug`；在 unlock 剪贴板快照中追加 `stepSeek` 与 `seekState` 行（before/target/after/current/seekComplete）

---

## [153] 2026-03-02 03:03:26 - 修复暂停瞬间误解锁与unlock日志ID插值

**用户指令**：
> 点击暂停一瞬间 lock 被取消；同时修复 unlock 日志里的 id 模板未展开问题。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免切换到静止状态的临界帧误触发 `PoseStagnant` 解锁，并修复 unlock 消息 ID 显示异常
    *   TrackerEngine.kt：`track(...)` 新增参数 `suppressStagnantUnlock`（默认 `false`）
    *   SimpleTrackerEngine.kt：修复 `unlock` 消息字符串插值（`id=$currentId`）；`PoseStagnant` 判定增加 `!suppressStagnantUnlock`
    *   RemoteByteTrackEngine.kt：修复 `unlock` 消息字符串插值（`id=$trackId`）；在 `track/PendingRequest/applyTrackingState/fallback` 全链路透传 `suppressStagnantUnlock` 并用于 `PoseStagnant` 判定
    *   YoloPoseAnalyzer.kt：`analyzeBitmapAndTrackPoses(...)` 增加 `suppressStagnantUnlock` 参数并透传至 tracker
    *   VideoFeeder.kt：切到 `静止` 时设置 500ms 短保护窗，仅抑制 `PoseStagnant` 解锁；同时在分析调用中透传该抑制标记

---

## [152] 2026-03-02 02:39:19 - +1帧触发 unlock 自动复制调试快照

**用户指令**：
> 把 unlock 诊断日志绑定到 +1帧；点击后如果发生 unlock，把所需日志写入剪贴板，并增加设置开关“调试信息写入剪贴板”（默认关）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在慢放排障时一键捕获 unlock 现场，减少手动筛 logcat 的成本
    *   AppSettings.kt：新增 `isClipboardDebugOnStepEnabled` 与持久化键 `clipboard_debug_on_step`
    *   fragment_settings_home.xml：新增开关 `switch_clipboard_debug`
    *   SettingsHomeFragment.kt：接入开关初始化与保存；开启时提示说明
    *   RoiLogAggregator.kt：新增最近帧摘要缓存与 `snapshotRecentFrames()` 接口
    *   MainActivity.kt：在静止态 `+1帧` 先武装抓取窗口；若随后发生 unlock，自动将“unlock原因+关键设置+面板快照+最近8帧摘要”写入系统剪贴板并提示

---

## [151] 2026-03-02 02:15:46 - 统一 seek 基准修复 ±5s 累计偏移

**用户指令**：
> 现在时间前后=-5S功能出了问题，点击后回到的不是当前的-5s，而是每次点击越来越往前。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 `-5s/+5s` 与 `±1帧` 的 seek 基准串扰，统一为“当前时间偏移”
    *   VideoFeeder.kt：删除 `lastSeekTargetMs` 游标逻辑
    *   VideoFeeder.kt：`seekByMs` 改为统一使用 `currentPosition + deltaMs` 计算目标时间
    *   VideoFeeder.kt：同步清理 `setupMediaPlayer/setStillMode/stop` 中对旧游标的重置代码

---

## [150] 2026-03-02 01:58:40 - 新增“静止时使用标准帧”设置与静止跳帧喂帧控制

**用户指令**：
> 增加设置项“静止时使用标准帧”；开启后静止状态下画面不变就不喂帧。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：减少静止同帧重复分析导致的锁定状态抖动，支持在“持续分析”和“标准帧喂入”之间切换
    *   AppSettings.kt：新增 `KEY_STILL_STANDARD_FRAME`、`isStillStandardFrameEnabled`、`setStillStandardFrameEnabled()` 并持久化加载
    *   fragment_settings_home.xml：新增开关 `switch_still_standard_frame`
    *   SettingsHomeFragment.kt：接入开关初始化与监听保存
    *   VideoFeeder.kt：在静止模式下，若开启该开关且 `currentPosition` 未变化，则跳过本轮喂帧

---

## [149] 2026-03-02 01:49:49 - 静止模式下仅按时间推进 lock/unlock 状态机

**用户指令**：
> 静止中会掉 lock，但又不能牺牲静止时 YOLO/ROI 调试能力；要求只在时间真正前进时推进状态机。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：保留静止调试能力，同时避免“同一帧重复分析”触发假解锁
    *   TrackerEngine.kt：`track(...)` 增加 `temporalAdvanced` 参数
    *   VideoFeeder.kt：新增 `lastAnalyzedPositionMs` 与 `computeTemporalAdvanced()`，将“时间是否前进”传给姿态分析
    *   YoloPoseAnalyzer.kt：`analyzeBitmapAndTrackPoses(...)` 增加 `temporalAdvanced` 并透传给 tracker
    *   SimpleTrackerEngine.kt / RemoteByteTrackEngine.kt：仅当 `temporalAdvanced=true` 时推进 `recentPositions/recentKeypoints`、`ShoulderLow`、`PoseStagnant`、`missingFrames` 等会改变 lock/unlock 的状态计数

---

## [148] 2026-03-02 01:34:25 - 长按播放键执行全量重置并从头播放

**用户指令**：
> 长按播放/暂停键，清除相关内容并重头开始播放（相当于关一次 APP）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提供“接近重启 App”的一键复位入口，避免历史运行态干扰
    *   MainActivity.kt：`btnPause` 新增长按监听；新增 `hardRestartPlayback()`，重置 `Yolo/Pose/Presence/ROI` 状态、清空房间人数、恢复播放态并调用 `startVideoMode()` 从头播放
    *   RoomPresenceChangeLogger.kt：新增 `reset()`，用于重置 Presence 变化日志内部快照

---

## [147] 2026-03-02 01:25:16 - 修复静止改帧串用旧游标

**用户指令**：
> 点击播放后再次静止改帧，时间似乎错乱回到上一次。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免“再次进入静止后首帧步进”串用上次静止会话的时间基准
    *   VideoFeeder.kt：在 `setStillMode(true)` 时重置 `lastSeekTargetMs = null`

---

## [146] 2026-03-02 01:16:30 - 新增右半透明调试信息面板

**用户指令**：
> 增加一个调试按钮，打开后在画面右半叠加半透明信息区，并随画面变化实时更新。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提供画面内可视化调试面板，减少来回看 logcat 的成本
    *   activity_main.xml：底部常规控制栏新增 `btnDebugPanel` 按钮
    *   MainActivity.kt：新增调试面板开关状态；按钮点击后切换开关并调用 `overlayView.setDebugPanelEnabled(...)`
    *   RoiLogAggregator.kt：新增 `snapshotForPanel()`，输出面板所需的实时状态快照
    *   DetectionOverlayView.kt：新增右半透明面板绘制逻辑，显示 `debugInfo + roiRatio + RoiLogAggregator` 快照内容

---

## [145] 2026-03-02 01:06:35 - 修复逐帧 seekTo 参数类型错误

**用户指令**：
> 编译报错：seekTo 参数类型不匹配（Int 传给了 Long）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 `VideoFeeder` 逐帧 seek 的 Kotlin 编译错误
    *   VideoFeeder.kt：`seekTo(target, SEEK_CLOSEST)` 改为 `seekTo(target.toLong(), SEEK_CLOSEST)`

---

## [144] 2026-03-02 01:02:54 - 修复逐帧按钮仅首击生效

**用户指令**：
> 点击帧+-只生效了一次。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复静止中逐帧步进连续点击失效问题
    *   VideoFeeder.kt：新增 `lastSeekTargetMs` 作为连续步进游标；`seekByMs` 改为基于游标累加并调用 `seekTo(target, SEEK_CLOSEST)`；在 `setupMediaPlayer/stop` 时重置游标

---

## [143] 2026-03-02 00:58:44 - 静止中切换为逐帧慢放控制

**用户指令**：
> 静止中状态下，将 -5S 和 +5S 改为 -1帧 和 +1帧，用于慢放；其他状态维持原逻辑。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不改动现有状态重置逻辑的前提下，提供静止中逐帧微调能力
    *   VideoFeeder.kt：新增 `seekBackwardFrame/seekForwardFrame`；新增 `seekByMs`；新增 `estimateFrameStepMs`（优先从视频 metadata 估算 fps，失败回退 33ms）
    *   MainActivity.kt：快进快退按钮改为状态感知逻辑（静止中=±1帧，其他=±5s）；新增 `refreshSeekButtons` 动态更新按钮文案

---

## [142] 2026-03-02 00:27:43 - 新增 Presence 位置判定与切换事件引擎

**用户指令**：
> 创建独立工具类实现“位置判定/房间切换事件”，据此更新各房间 PresenceCounts，尽量不影响现有框架。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将持久化人数逻辑从 MainActivity 抽离到独立 Presence 模块，并支持门线切换事件
    *   PresenceGeometry.kt：新增纯 Kotlin 几何工具（点在多边形、点到线段距离、投影参数、到边界距离）
    *   RoomTransitionEstimator.kt：新增核心估计器（WEAK/STRONG/CONFIRMED 分层、门线 near 判定、切换事件、盲区 pending 确认、presenceCounts 输出）
    *   RoomPresenceChangeLogger.kt：新增变化日志工具（仅 counts 变化时输出 `ROOM_PRESENCE_CHANGE`）
    *   MainActivity.kt：移除旧持久化人数更新路径，接入估计器；新增房间/门线快照构建与强度映射方法

---

## [141] 2026-03-01 20:38:37 - 盲区房间属性与连续多边房门

**用户指令**：
> 新建房间支持“是否为主房间盲区”（与入户门互斥）；盲区房间在房门选择时可选择多条连续边。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：为盲区房间提供独立属性与连续多边门绑定能力
    *   RoomConfig.kt：新增 isLivingBlindZone 字段
    *   RoomRepository.kt：新增字段持久化与 addNewRoom 参数
    *   MainActivity.kt：新增/属性弹窗加入“主房间盲区”并与“入户门”互斥
    *   LivingRoomEditorView.kt：门选择改为“普通单边 + 盲区连续多边（环形连续）”，并支持多边高亮与提交

---

## [140] 2026-01-11 23:12:52 - 日志频率新增“关闭”并修复可见性

**用户指令**：
> 日志频率设置文字不可见，新增“关闭”选项。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复日志频率控件可见性并支持关闭输出
    *   AppSettings.kt：新增 ROI_LOG_MODE_OFF
    *   RoiLogAggregator.kt：支持关闭模式
    *   fragment_settings_home.xml：Spinner 增加背景/主题与 entries
    *   arrays.xml：新增 roi_log_mode_labels
    *   SettingsHomeFragment.kt：移除手工 Adapter，使用 entries

---

## [139] 2026-01-11 23:10:12 - 顶边位移改为真实像素

**用户指令**：
> 顶边触发次数过少；需要按真实像素计算 64px 阈值。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让顶边位移阈值与实际像素一致
    *   RoiTracker.kt：topMove 从“归一化×640”改为“按 imageWidth/imageHeight 换算”

---

## [138] 2026-01-11 22:58:12 - 增加日志更新频率设置

**用户指令**：
> 设置中新增日志更新频率：1秒一次 / ROI每次移动一次。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：允许切换 ROI 日志输出频率
    *   AppSettings.kt：新增 roiLogMode 持久化配置与常量
    *   RoiLogAggregator.kt：按设置决定“1秒”或“ROI移动”输出
    *   fragment_settings_home.xml：新增日志频率 Spinner
    *   SettingsHomeFragment.kt：初始化 Spinner 并保存设置

---

## [137] 2026-01-11 22:50:28 - ROI 日志增加边界夹住标记

**用户指令**：
> 需要确认是否被边界夹住；日志中加入 clamp 标记。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：判断 ROI 是否被边界夹住导致停滞
    *   RoiLogAggregator.kt：新增 clampL/R/T/B 字段输出
    *   RoiTracker.kt：计算 clamp 标记并传入聚合日志

---

## [136] 2026-01-11 22:34:57 - 暂停中心偏移驱动

**用户指令**：
> 暂时放弃中心偏移，只保留顶边偏移；不要删除逻辑，用开关置 0。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：仅用顶边偏移驱动 ROI，排除中心偏移干扰
    *   RoiTracker.kt：增加 enableCenterOffset=false，中心偏移参与判定时置 0

---

## [135] 2026-01-11 22:29:43 - ROI 日志聚合为单条输出

**用户指令**：
> 日志太多；聚合到一秒一条，避免多条刷屏。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：统一 ROI 相关日志为每秒一条输出
    *   RoiLogAggregator.kt：新增日志聚合器，集中输出 ROI/追踪/姿态信息
    *   RoiTracker.kt：移除分散日志，改为写入聚合器
    *   YoloPoseAnalyzer.kt：移除 RF_ROI_COORD / Heartbeat 日志，改为聚合器
    *   DetectionOverlayView.kt：移除 RF_PoseUI / RF_ROI_DEBUG 日志，改为聚合器

---

## [134] 2026-01-11 22:17:35 - 顶边日志改为“ROI 变化且每秒一次”

**用户指令**：
> 顶边日志太多；仅当 ROI 位置变化且超过 1 秒时才打印。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：减少无效日志，保证每次打印可用
    *   RoiTracker.kt：新增 lastRoiTopLogTs/lastRoiTopLogRoi 节流；仅 ROI 变化且超 1 秒时输出

---

## [133] 2026-01-11 21:38:18 - ROI 死区加入中心偏移条件

**用户指令**：
> ROI 跟踪太慢，超过 64 也不动；用现有阈值让跟随变快。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免仅靠顶边位移触发导致跟踪断续
    *   RoiTracker.kt：死区判定增加 center 偏移；触发条件改为 topMove 或 center 偏移超阈值

---

## [132] 2026-01-11 21:33:28 - ROI 顶边锚点改为“移动后刷新”

**用户指令**：
> ROI 完全不移动；需改为慢速累计触发，锚点只在移动后更新。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免每帧更新锚点导致慢速目标永远进不去死区
    *   RoiTracker.kt：顶边位移改用锚点；仅在触发移动时刷新锚点；去掉死区返回时刷新锚点

---

## [131] 2026-01-11 21:28:15 - 修复 ROI 顶边基准不更新

**用户指令**：
> 修复 ROI 抖动，先看逻辑再改；避免日志瞎猜；并注意日志节流。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：确保 topMove 使用同一基准并在提前返回前刷新顶边基准，避免 ROI 死区误触发
    *   RoiTracker.kt：顶边位移只计算一次；提前返回前更新 lastTop*；移除重复的 shouldUpdateRoi 计算

---

## [130] 2026-01-11 21:20:14 - 顶边基准日志

**用户指令**：
> 确认 lastTop* 是否超出 1，要求打印原始值。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：输出顶边基准与当前顶边的原始值
    *   RoiTracker.kt：topMove 超阈值时输出 last/curr 顶边坐标

---

## [129] 2026-01-11 21:05:29 - 顶边基准持续更新

**用户指令**：
> topMove 仍异常偏大，需要基准持续更新。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：每次 ROI 更新都刷新顶边基准
    *   RoiTracker.kt：在返回 finalRoi 前更新 lastTopLeft/Right（归一化）

---

## [128] 2026-01-11 20:59:26 - 顶边位移改为归一化

**用户指令**：
> 顶边位移应不受 ROI 尺寸/分辨率影响。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：统一顶边位移尺度
    *   RoiTracker.kt：顶边基准改为归一化坐标，topMove 乘 modelInputWidth

---

## [127] 2026-01-11 20:52:27 - 顶边基准随 ROI 更新

**用户指令**：
> topMove 仍然异常大，需要修复基准更新。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：每次 ROI 更新后刷新顶边基准点
    *   RoiTracker.kt：在返回 finalRoi 前更新 lastTopLeft/Right

---

## [126] 2026-01-11 20:47:23 - ROI 坐标日志修正与节流

**用户指令**：
> RF_ROI_COORD 每帧输出且未插值，需要修正。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 RF_ROI_COORD 插值并降频
    *   YoloPoseAnalyzer.kt：RF_ROI_COORD 每秒输出一次并显示真实 roiPx/box

---

## [125] 2026-01-11 20:44:10 - ROI 坐标系诊断日志

**用户指令**：
> 怀疑 ROI 坐标系不一致导致 topMove 异常，需要诊断。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：输出 roiPx 与 box 坐标以确认坐标系
    *   YoloPoseAnalyzer.kt：新增 RF_ROI_COORD 日志

---

## [124] 2026-01-11 20:40:38 - 顶边基准初始化

**用户指令**：
> topMove 为无穷大，需初始化顶边基准。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：确保首次有 targetBox 时就建立顶边基准
    *   RoiTracker.kt：首次 targetBox 记录顶边点并将 topMove 置 0

---

## [123] 2026-01-11 20:36:06 - RF_RoiTick 增加 topMove

**用户指令**：
> 打出 topMove 的原始数据。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：输出顶边位移的原始数值
    *   RoiTracker.kt：RF_RoiTick 增加 topMove 字段

---

## [122] 2026-01-11 20:31:13 - 修复 ROI 汇总日志插值

**用户指令**：
> ROI 汇总日志输出为占位符，需修复。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 RF_ROI_DEBUG 汇总日志的字符串插值
    *   DetectionOverlayView.kt：输出真实 seq/cnt/key

---

## [121] 2026-01-11 20:28:51 - ROI 调试日志按秒汇总

**用户指令**：
> 一秒内如果有变化，输出这一秒内所有不同 ROI 值及其帧范围。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：按秒聚合 ROI_DEBUG 日志
    *   DetectionOverlayView.kt：按秒汇总不同 ROI 值并输出序号范围

---

## [120] 2026-01-11 20:23:12 - 顶边未动时直接冻结 ROI

**用户指令**：
> 顶边未超过阈值时应完全不更新 ROI。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：顶边未动时直接返回 lastRoi
    *   RoiTracker.kt：topMove <= deadZoneThreshold 时直接 return lastRoi

---

## [119] 2026-01-11 20:17:17 - ROI 顶边基准改为上次更新

**用户指令**：
> 顶边位移对比应基于上一次 ROI 更新时的顶边点。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：以“上次 ROI 更新”作为顶边基准
    *   RoiTracker.kt：只在 ROI 实际更新时刷新顶边基准点

---

## [118] 2026-01-11 20:13:49 - wiki 增加 ROI 触发规则

**用户指令**：
> ROI 顶边两点位移触发规则写入 wiki。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：补充 ROI 更新触发的规则说明
    *   wiki.md：新增 ROI 更新触发规则段落

---

## [117] 2026-01-11 20:11:46 - ROI 顶边两点变化触发

**用户指令**：
> 以 box 顶边两点位移判定是否更新 ROI，超过阈值才动。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：用顶边两点变化决定是否更新 ROI
    *   RoiTracker.kt：记录顶边左右点历史，topMove 超过 deadZoneThreshold 才更新

---

## [116] 2026-01-11 20:05:53 - 新增 wiki 记录锁定/解锁规则

**用户指令**：
> 新增 wiki.md，写明 lock 条件与 unlock 条件。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：集中记录锁定与解锁规则，便于后续查阅
    *   wiki.md：新增锁定条件与解锁条件说明

---

## [115] 2026-01-11 19:58:20 - 解锁新增静止判定并提示

**用户指令**：
> Lock 后若最近 4 帧任意两帧 pose 相同(1px)则解锁；解锁事件灰色提示 5 秒；问目前 unlock 种类。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：新增 pose 静止解锁与提示显示
    *   TrackerEngine.kt：新增 consumeUnlockMessage
    *   SimpleTrackerEngine.kt：增加 recentKeypoints 与 PoseStagnant 解锁提示
    *   RemoteByteTrackEngine.kt：增加 recentKeypoints 与 PoseStagnant 解锁提示
    *   YoloPoseAnalyzer.kt：转发解锁消息
    *   DetectionOverlayView.kt：顶部灰条显示解锁原因(5秒)
    *   MainActivity.kt：接收解锁消息并显示

---

## [114] 2026-01-11 19:43:42 - 贴边时跳过冻结

**用户指令**：
> ROI 不会贴边，需处理贴边场景。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：贴边时不应用 lastRoi 冻结
    *   RoiTracker.kt：新增 wasClamped 标记，只有未贴边时才复用 lastRoi

---

## [113] 2026-01-11 19:32:02 - ROI 变化阈值复用死区

**用户指令**：
> 追踪变化而非绝对距离，不引入新阈值。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：用现有 deadZoneThreshold 判断 ROI 是否需要更新
    *   RoiTracker.kt：finalRoi 与 lastRoi 差异 <= deadZoneThreshold 时直接复用 lastRoi

---

## [112] 2026-01-11 18:33:29 - ROI_DEBUG 日志节流

**用户指令**：
> View received ROI 仍每帧输出，需节流。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 ROI_DEBUG 降为每秒一次
    *   DetectionOverlayView.kt：RF_ROI_DEBUG 增加 1s 节流

---

## [111] 2026-01-11 18:30:01 - 日志统一前缀与节流

**用户指令**：
> 统一日志前缀，避免每帧大量输出，只需每秒一次。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：统一日志前缀并节流输出
    *   RoiTracker.kt：日志前缀改为 RF_，每秒输出一次
    *   YoloPoseAnalyzer.kt：RF_PoseHeartbeat 每秒输出
    *   DetectionOverlayView.kt：RF_ROI_DEBUG/RF_PoseUI 使用统一前缀并节流

---

## [110] 2026-01-11 18:23:22 - 修复 RoiTick 编译错误

**用户指令**：
> RoiTracker.kt 报错：targetCy 未定义。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 RoiTick 日志中的未定义变量
    *   RoiTracker.kt：移除 RoiTick 中的 targetCy 输出

---

## [109] 2026-01-11 18:21:29 - 重新开启 ROI 诊断日志

**用户指令**：
> ROI 抖动但没有日志，需要重新观察。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：恢复 ROI 抖动诊断输出
    *   RoiTracker.kt：重新开启 RoiTick/RoiJitter/RoiSizeChange 日志

---

## [108] 2026-01-11 03:25:20 - 设置页清空房间人数

**用户指令**：
> 设置中加按钮：清空所有房间人数。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提供一键清空人数入口
    *   fragment_settings_home.xml：新增 btn_clear_room_counts
    *   SettingsHomeFragment.kt：点击后清空 personCount/persistentPersonCount 并保存

---

## [107] 2026-01-11 03:21:14 - 修复设置页状态文本缺失

**用户指令**：
> 编译报错：tvTrackerStatus 未找到。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：补上设置页 ByteTrack 状态文本控件
    *   fragment_settings_home.xml：新增 tv_tracker_status

---

## [106] 2026-01-11 03:19:59 - 设置页显示 ByteTrack 服务状态

**用户指令**：
> 设置界面中显示服务端状态，仅在设置页刷新。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在设置页显示 ByteTrack 服务可用性
    *   fragment_settings_home.xml：新增 tv_tracker_status 文本
    *   SettingsHomeFragment.kt：轮询 /health 并在开关开启时更新状态

---

## [105] 2026-01-11 03:05:32 - ROI 回退到中心死区

**用户指令**：
> 回到最初中心死区逻辑，日志注释掉不要删除。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：恢复中心死区/中心 EMA，停用顶边逻辑
    *   RoiTracker.kt：恢复中心偏差判定；注释 RoiTick/RoiJitter/RoiSizeChange 日志

---

## [104] 2026-01-11 03:01:56 - ROI 死区与速度分离

**用户指令**：
> Y 轴跟踪太慢，需要与 X 轴速度一致。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：死区判定用顶边，速度判定用中心偏差
    *   RoiTracker.kt：dyTop 用于 deadzone，dyCenter 用于速度与 EMA

---

## [103] 2026-01-11 02:56:19 - ROI 顶边死区基准修正

**用户指令**：
> 顶边死区基准应与目标框高度对齐，避免 dy 恒大。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：顶边死区使用目标框高度作为基准
    *   RoiTracker.kt：dy 改为 targetTop 对齐 roiCenterY - boxHalfH

---

## [102] 2026-01-11 02:53:01 - ROI 诊断日志修正

**用户指令**：
> ROI 抖动时日志全是 0，需要修正。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让 RoiTick/RoiJitter 输出真实偏差
    *   RoiTracker.kt：修正日志变量遮蔽与 targetBox.top 输出

---

## [101] 2026-01-11 02:49:43 - ROI 周期诊断日志

**用户指令**：
> ROI 抖动时没有日志，要求能看到原因。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：每 30 帧输出 ROI 关键偏差信息
    *   RoiTracker.kt：新增 RoiTick 周期日志（dx/dy/阈值/roiTop/roiSize）

---

## [100] 2026-01-11 02:45:57 - ROI 抖动诊断日志

**用户指令**：
> 需要确认 ROI 抖动原因，怀疑是很小偏移也触发移动。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：输出死区内仍发生 ROI 变化的详细日志
    *   RoiTracker.kt：新增 RoiJitter/RoiSizeChange 诊断日志

---

ROI抖动日志:
- RoiJitter：死区内仍发生 ROI 移动时输出，带 dx/dy、dead 阈值、delta、roi。
- RoiSizeChange：ROI 尺寸切换（640/短边）时输出。

## [099] 2026-01-11 02:38:36 - ByteTrack 空帧短缓存

**用户指令**：
> ByteTrack 在空检测时短暂保留缓存，超过则清空。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免空帧持续输出旧轨迹，减少 ROI 卡死
    *   RemoteByteTrackEngine.kt：增加空帧计数，空检测连续>2帧清空缓存并输出空

---

## [098] 2026-01-11 02:28:38 - ROI 顶边死区但保持中心对齐

**用户指令**：
> 死区用 top，但 ROI 中心仍需对齐，避免两个框中心错位。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：顶边用于死区判定，ROI 仍以中心跟随
    *   RoiTracker.kt：dy 改为 targetTopY vs roiTopY；中心更新回到 centerY

---

## [097] 2026-01-11 02:26:08 - ROI 死区改为顶边中心

**用户指令**：
> 死区判断改用 box 顶边中心，减少贴边上下抖动。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：死区以 box 顶边为基准，降低贴边抖动
    *   RoiTracker.kt：使用 targetTopY 代替 centerY 参与死区与 EMA；中心同步改为 top

---

## [096] 2026-01-11 02:11:19 - 区域编辑删除与还原生效

**用户指令**：
> 点击删除房间区域没有变化，要求生效。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区域编辑下“还原/删除区域”真正执行
    *   MainActivity.kt：SUBROOM_AREA_EDIT 下 btnUndo 调用 restoreRegionEdit，btnClear 清空房间区域并退出编辑

---

## [095] 2026-01-11 02:08:12 - 主界面使用 labelPoint 绘制位置

**用户指令**：
> 主界面与设置界面房间图标/名称位置不一致，需统一。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：主界面绘制使用 labelPoint ?: anchorPoint，与设置界面一致
    *   DetectionOverlayView.kt：次房间绘制点改用 labelPoint ?: anchorPoint

---

## [094] 2026-01-11 02:02:32 - 动态按钮移到左侧

**用户指令**：
> 右侧只保留不保存/保存/X，其他按钮全部移到左侧排列。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：保证右侧只有操作区，其它动态按钮在左侧
    *   MainActivity.kt：动态按钮容器改为 llEditorLeft

---

## [093] 2026-01-11 02:01:29 - 主房间保存显示与撤销禁用

**用户指令**：
> 主房间设置保留清空，撤销功能先注释；进入主房间区域编辑后显示保存/不保存。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：主房间编辑显示保存/不保存并禁用撤销
    *   MainActivity.kt：注释 btnUndo 点击事件；LIVING_ROOM 状态隐藏撤销、显示保存/不保存

---

## [092] 2026-01-11 01:55:51 - 编辑菜单透明度改为 90% 透明

**用户指令**：
> 菜单透明度改为 90% 透明（不是 90% 不透明）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将编辑栏背景调整为更高透明度
    *   activity_main.xml：背景色由 #E6000000 改为 #1A000000

---

## [091] 2026-01-11 01:54:18 - 右侧固定与区域编辑按钮文案

**用户指令**：
> X 始终贴最右；菜单底色透明度 90%；区域编辑要显示还原/删除区域。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：固定右侧操作区并补回区域编辑按钮提示
    *   activity_main.xml：右侧操作容器增加 layout_gravity=end
    *   MainActivity.kt：区域编辑时按钮文案改为“还原/删除区域”，其余状态恢复默认文案

---

## [090] 2026-01-11 01:46:23 - 编辑右侧按钮独立框

**用户指令**：
> 三种房间设置里把不保存/保存/X 独立到右侧单独框，只有需要时显示保存与不保存。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把保存/不保存/X 固定到右侧独立区域，布局一致
    *   activity_main.xml：编辑工具栏拆分左右容器，右侧放不保存/保存/X 并单独背景

---

## [089] 2026-01-11 01:37:44 - 修复 YoloPoseAnalyzer 重复函数声明

**用户指令**：
> 修复 YoloPoseAnalyzer.kt 编译错误（重复函数声明）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：移除重复的 analyzeBitmapAndTrackPoses 声明，恢复类作用域
    *   YoloPoseAnalyzer.kt：删除重复函数声明

---

## [088] 2026-01-11 01:30:21 - 次房间菜单规则重排

**用户指令**：
> 菜单规则调整：属性/不保存/保存/X 分离，区域编辑显示还原+删除区域等。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：按新规则调整各状态按钮显示与文案，并保存/不保存后回退
    *   MainActivity.kt：btnCancel 处理区域/门选择回退，renderEditorMenu 调整显隐与文案

---

## [087] 2026-01-11 01:24:07 - 识别心跳日志

**用户指令**：
> 识别偶尔卡住，先加心跳与日志（30帧一次）。
> 搜索
- PoseHeartbeat（推理层是否还在出结果）
- PoseUI（UI 是否还在刷新）

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位推理是否停更或 UI 不刷新
    *   YoloPoseAnalyzer.kt：每 30 帧输出 PoseHeartbeat
    *   DetectionOverlayView.kt：每 30 次 UI 更新输出 PoseUI

---

## [086] 2026-01-11 00:47:25 - 修复区域编辑完成保存与退出

**用户指令**：
> 区域编辑点击完成后没有退出编辑。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在区域编辑完成时保存区域并退出编辑状态
    *   MainActivity.kt：btnFinish 增加 SUBROOM_AREA_EDIT 分支，保存 boundaryVertices 并退出；切换状态时清理区域编辑

---

## [085] 2026-01-11 00:39:14 - 区域编辑时禁用标签拖动

**用户指令**：
> 房间区域编辑时不允许移动房间位置。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区域编辑模式下禁止房间标签拖动
    *   LivingRoomEditorView.kt：区域编辑开启时跳过 labelPoint 拖动逻辑

---

## [084] 2026-01-11 00:35:14 - 恢复次房间区域编辑入口

**用户指令**：
> 房间区域编辑只提示 toast，无任何变化。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：进入区域编辑时真正启动 RegionEdit 绘制与状态
    *   MainActivity.kt：enterRoomAreaEditMode 调用 startSubRoomRegionEdit；退出时调用 endSubRoomRegionEdit

---

## [083] 2026-01-11 00:26:41 - 手势处理不再拦截 ACTION_UP 清理

**用户指令**：
> 点击仍会移动，怀疑没有触发拖动而是直接设置，要求修复。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免手势检测提前 return 导致 pending 状态不清理
    *   LivingRoomEditorView.kt：改为保留手势结果但不拦截 ACTION_UP/CANCEL 清理逻辑

---

## [082] 2026-01-11 00:19:52 - 修复 LivingRoomEditorView 括号错误

**用户指令**：
> 文件出现大量错误，怀疑括号缺失。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复点击拖动逻辑插入导致的多余括号
    *   LivingRoomEditorView.kt：删除 ACTION_DOWN 中多余的闭合括号

---

## [081] 2026-01-11 00:16:22 - 点击不再移动次房间标签

**用户指令**：
> 选中次房间后点击空白不应移动位置，只有拖动才移动。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：防止点击被误判为拖动，避免房间标签跳动
    *   LivingRoomEditorView.kt：加入拖动阈值与 pending 状态，仅超过阈值才更新 labelPoint

---

## [080] 2026-01-10 23:39:11 - 入户门属性与唯一性校验

**用户指令**：
> 增加入户门(唯一)属性；新增子房间与改名弹窗加入勾选并校验唯一性。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：支持入户门属性并在新增/改名时校验唯一性
    *   RoomConfig.kt：新增 isEntranceDoor 字段
    *   RoomRepository.kt：新增字段持久化与反序列化；addNewRoom 支持传入入户门标记
    *   MainActivity.kt：新增/改名弹窗加入“入户门”勾选并在重复时 toast 拒绝保存

---

## [079] 2026-01-08 06:10:43 - 重写 RemoteByteTrackEngine 以修复编译错误

**用户指令**：
> RemoteByteTrackEngine 编译错误较多，采用最佳方式重写修复。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：重构追踪状态逻辑与锁定条件，消除作用域与变量错误
    *   RemoteByteTrackEngine.kt：重写 buildTrackResults/applyTrackingState 并统一锁定判定与日志

---

## [078] 2026-01-08 06:05:30 - 首次锁定条件改为当前帧完整判定

**用户指令**：
> 首次锁定需要：历史最高分>=0.6、近3帧移动+距离阈值、当前帧完整性(上半身+脚踝)、肩膀可信、当前分数>=0.5、护盾区不拦截。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：锁定条件改为当前帧规则+近3帧移动判定
    *   SimpleTrackerEngine.kt：近3帧移动判定、shouldLock 新条件、候选数据携带当前完整性并更新 lockAdd 日志
    *   RemoteByteTrackEngine.kt：近3帧移动判定、shouldLock 新条件、候选数据携带当前完整性并更新 lockAdd 日志

---

## [077] 2026-01-08 05:30:55 - 修复 lockAdd 日志语法错误

**用户指令**：
> 编译错误：movedHistory 未定义且语法错误。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复日志插入导致的语法错误并补充 movedHistory 输出
    *   SimpleTrackerEngine.kt：修正 movedOk/movedHistory 语句位置
    *   RemoteByteTrackEngine.kt：修正 movedOk/movedHistory 语句位置

---

## [076] 2026-01-08 05:28:07 - 锁定条件使用当前移动修正

**用户指令**：
> 锁定应基于当前移动状态（不是历史移动）。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：纠正 wouldLock 仍在使用历史移动标记的问题
    *   SimpleTrackerEngine.kt：wouldLock 参数改为 isMovingNow
    *   RemoteByteTrackEngine.kt：wouldLock 参数改为 isMoving

---

## [075] 2026-01-08 05:21:44 - 锁定阈值与移动条件调整

**用户指令**：
> 历史最高分阈值调到 0.9；锁定改为当前移动判定。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提高锁定分数门槛并改为当前移动判定
    *   SimpleTrackerEngine.kt：MIN_SCORE_FOR_LOCKING 改为 0.9，wouldLock 使用 isMovingNow
    *   RemoteByteTrackEngine.kt：MIN_SCORE_FOR_LOCKING 改为 0.9，wouldLock 使用 isMoving

---

## [074] 2026-01-08 05:06:33 - 完整输出 lock 条件判断日志

**用户指令**：
> lockAdd 日志需要输出每个条件的判断过程和参数。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：补充 lock 成功时的完整条件日志，修复 history 引用错误
    *   SimpleTrackerEngine.kt：lockAdd 日志改为输出完整条件与参数
    *   RemoteByteTrackEngine.kt：lockAdd 日志改为输出完整条件与参数

---

## [073] 2026-01-08 05:03:43 - lock 成功时输出条件日志

**用户指令**：
> 每个 lock 成功都输出判断条件相关的值到 log:lockAdd。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：记录 lock 触发时的关键判断值以便排查
    *   SimpleTrackerEngine.kt：lock 成功时输出 local 条件日志
    *   RemoteByteTrackEngine.kt：lock 成功时输出 remote 条件日志

---

## [072] 2026-01-08 04:54:20 - 新增锁定姿态条件（上半身数量+脚踝位置）

**用户指令**：
> lock 时上半身(0-10)至少存在5个点；脚踝若存在必须低于0-6。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将锁定条件改为上半身点数量+脚踝相对位置判定
    *   SimpleTrackerEngine.kt：新增上半身/脚踝校验辅助函数并替换 checkPoseIntegrity
    *   RemoteByteTrackEngine.kt：新增上半身/脚踝校验辅助函数并替换 checkPoseIntegrity

---

## [071] 2026-01-08 04:45:26 - 抽象锁定条件为统一方法

**用户指令**：
> lock 的条件太多，抽成专门的验证方法。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：统一锁定条件判断，减少重复条件表达
    *   SimpleTrackerEngine.kt：新增 shouldLock 并替换 wouldLock 判断
    *   RemoteByteTrackEngine.kt：新增 shouldLock 并替换 wouldLock 判断

---

## [070] 2026-01-08 04:35:14 - 锁定目标低肩连续降级

**用户指令**：
> 如果一个框被 lock 后，连续 10 帧双肩点都低于可信阈值则 unlock。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：对已锁定目标加入低肩连续帧降级解锁
    *   SimpleTrackerEngine.kt：TrackedSubjectHistory 增加低肩计数并在追踪中执行解锁
    *   RemoteByteTrackEngine.kt：TrackState 增加低肩计数并在追踪中执行解锁

---

## [069] 2026-01-08 04:25:16 - 修复 DetectionOverlayView 括号错误

**用户指令**：
> DetectionOverlayView.kt:292 出现大量编译错误，疑似括号问题。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：移除误删护盾区代码后遗留的多余括号，恢复 onDraw 结构
    *   DetectionOverlayView.kt：删除 ROI 绘制块后的多余闭合括号

---

## [068] 2026-01-08 04:22:28 - 关闭护盾区可视化，仅保留拦截紫色框

**用户指令**：
> 护盾区不显示，只显示被拦截的升级框。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：仅保留被拦截升级的紫色框，不再绘制护盾区
    *   DetectionOverlayView.kt：移除护盾区字段与绘制入口
    *   MainActivity.kt：不再向 overlay 传递护盾区

---

## [067] 2026-01-08 04:16:15 - 强框护盾区与冷却区门禁

**用户指令**：
> 当强框经过弱框时，建立护盾区与冷却区，阻止弱框升级；护盾区需要可视化为稀疏紫色虚线，且被阻止升级的框也要显示紫色。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：强框护盾/冷却区阻断弱框升级，并绘制紫色护盾可视化
    *   PoseData.kt：PoseResult 增加 isShielded 字段用于绘制紫色框
    *   SimpleTrackerEngine.kt：补齐护盾区/冷却区状态与阻断升级逻辑，并输出护盾区
    *   RemoteByteTrackEngine.kt：补齐护盾区/冷却区状态与阻断升级逻辑，并输出护盾区
    *   DetectionOverlayView.kt：绘制护盾区紫色虚线框

---

## [066] 2026-01-08 01:18:13 - 仅远程结果显示 BID

**用户指令**：
> 只有真正用到服务器返回的ID才显示BID，否则显示ID。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：根据真实数据来源切换 ID/BID 显示
    *   PoseData.kt：新增 IdSource，并在 PoseResult 中记录 idSource
    *   TrackerEngine.kt：TrackResult 增加 isRemote 标记
    *   SimpleTrackerEngine.kt：TrackResult 设置 isRemote=false
    *   RemoteByteTrackEngine.kt：远程 TrackResult 设置 isRemote=true
    *   YoloPoseAnalyzer.kt：TrackResult 映射到 PoseResult 时写入 idSource
    *   PoseDrawer.kt：根据 idSource 显示 ID/BID

---

## [065] 2026-01-08 01:12:32 - 检测框 ID 显示为 BID

**用户指令**：
> 之前是ID,现在改成BID(文本)

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区分 ByteTrack 返回的 ID 显示
    *   PoseDrawer.kt：检测框文本由 "ID" 改为 "BID"

---

## [064] 2026-01-08 00:46:48 - 修复 RemoteByteTrackEngine 编译错误

**用户指令**：
> :app:compileDebugKotlin 7个错误... RemoteByteTrackEngine.kt...

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 RemoteByteTrackEngine 的 companion object 冲突与常量引用错误
    *   RemoteByteTrackEngine.kt：合并 companion object，补齐 TAG 与常量定义

---

## [063] 2026-01-08 00:26:45 - ByteTrack 调试日志

**用户指令**：
> 我们先来跑一些测试,你的log系统做好了吗?我怎么知道现在运行是否正常?

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：增加 ByteTrack 请求与回包日志，方便测试与排查
    *   TrackClient.kt：新增发送/失败/成功日志，输出 frame_id 与 tracks 数量
    *   RemoteByteTrackEngine.kt：新增请求状态、失败回退、远程禁用日志

---

## [062] 2026-01-08 00:15:58 - 接入 ByteTrack 客户端与单飞请求策略

**用户指令**：
> 使用 OkHttp 接 ByteTrack，单飞请求不阻塞 Analyzer；按 openapi schema 发送/解析，ID 直接用 track_id。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将远程追踪改为异步单飞请求，按 ByteTrack schema 传输并缓存结果
    *   TrackClient.kt：新增 OkHttp 客户端、/track 与 /health /reset 请求与 JSON 解析
    *   RemoteByteTrackEngine.kt：改为单飞异步调用 TrackClient；缓存 latestTracks；失败降级与冷却
    *   AndroidManifest.xml：新增 INTERNET 权限以允许 ByteTrack 网络访问
    *   gradle/libs.versions.toml：新增 okhttp 版本与库定义
    *   app/build.gradle.kts：引入 okhttp 依赖

---

## [061] 2026-01-07 23:58:16 - 引入可切换追踪引擎骨架（Simple/Remote ByteTrack）

**用户指令**：
> 保留 TrackedSubjectHistory/房间人数逻辑，仅替换 ID 生成+匹配+missingFrames 为 ByteTrack，并新增“使用新追踪预测模式”开关。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：引入可切换的追踪引擎架构，为 ByteTrack 接入做准备
    *   TrackerEngine.kt：新增 TrackDetection/TrackResult 与 TrackerEngine 接口
    *   SimpleTrackerEngine.kt：迁移原追踪/锁定逻辑为本地引擎实现
    *   RemoteByteTrackEngine.kt：新增远程 ByteTrack 框架与超时回退本地逻辑（/track + /reset）
    *   YoloPoseAnalyzer.kt：追踪逻辑改为调用 TrackerEngine；新增像素坐标映射与结果归一化
    *   AppSettings.kt：新增 isNewTrackerPredictionEnabled 及其持久化方法
    *   SettingsHomeFragment.kt：新增开关绑定与持久化
    *   fragment_settings_home.xml：新增“使用新追踪预测模式”开关
    *   MainActivity.kt：启动视频/相机时重置追踪器状态

---

## [060] 2026-01-07 20:47:35 - 落地点 Y 超出时压到底部

**用户指令**：
> 屏幕外下方的落地点直接压到屏幕最下方，只处理 Y。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让屏幕外落点仍能触发客厅判断
    *   PoseData.kt：landingPoint 计算后仅对 y > 1 做 clamp 到 1

---

## [059] 2026-01-07 20:31:28 - 关键点/框统一按归一化处理

**用户指令**：
> 选择A方案：把输出当作归一化坐标，允许轻微越界，不再除以 640。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：避免将 1.00x 误判为像素导致坐标被除以 640
    *   YoloPoseAnalyzer.kt：移除 box 与 keypoints 的“>1 就除以 640”逻辑，统一按归一化处理

---

## [058] 2026-01-07 20:21:19 - 脚踝关键点原始/映射日志

**用户指令**：
> 需要确认脚踝坐标接近 0 是模型输出还是转换问题，增加日志。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：对比脚踝关键点原始值与映射后值
    *   YoloPoseAnalyzer.kt：在提取关键点时记录脚踝 raw/归一化/映射值与 ROI、box

---

## [057] 2026-01-07 20:07:58 - 脚踝落点异常日志

**用户指令**：
> 脚踝坐标偶尔在左上角，要求增加日志查看落点与脚踝位置。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位落地点异常来源
    *   PoseDrawer.kt：当 landingPoint 接近 (0,0) 时记录 id、box、左右脚踝坐标与置信度
    *   AGENTS.md：更新 GeminiHistory 已读编号到 022

---

## [056] 2026-01-07 18:38:22 - 弱目标恢复显示但不参与逻辑

**用户指令**：
> 弱目标绘制也要保留。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：保留弱目标绘制，仅逻辑层忽略
    *   MainActivity.kt：updatePoseData 改回使用完整 results 列表

---

## [055] 2026-01-07 18:33:07 - 弱目标不参与人数/ROI/雷达

**用户指令**：
> 强目标(实线框)才参与逻辑，弱目标(虚线框)全部忽略。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：仅强目标参与人数统计、ROI 追踪与雷达显示
    *   MainActivity.kt：过滤 strongResults(isConfirmed)，仅用强目标做计数、ROI 目标与 overlay/雷达数据

---

## [054] 2026-01-07 06:39:17 - ROI 阈值基于长边/ROI 比例

**用户指令**：
> 以长边/ROI 边长比例判断：上切>0.85，下切<0.35。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：用长边/ROI 比例触发上切与下切
    *   RoiTracker.kt：按 maxPersonSide/currentRoiSize 计算 ratio，并据此切换

---

## [053] 2026-01-07 06:30:12 - ROI 尺寸双阈值状态切换

**用户指令**：
> 上切仅在当前 ROI=640 时生效(>0.85*640)，下切仅在 ROI=画面短边时生效(<0.35*640)。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：实现带状态的双阈值 ROI 切换，避免每帧同时判断
    *   RoiTracker.kt：仅在当前为 640 时上切、仅在当前为画面短边时下切

---

## [052] 2026-01-07 06:08:20 - ROI 框显示长边占比数值

**用户指令**：
> 在 ROI 框上边下方显示当前检测框长边占 ROI 的比例，只显示数字。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：显示当前检测框长边/ROI 的比例数值
    *   DetectionOverlayView.kt：新增 roiRatio 与 setRoiRatio，绘制 ROI 框下方数字
    *   MainActivity.kt：计算比例并传给 overlayView

---

## [051] 2026-01-07 04:27:28 - ROI 尺寸逻辑改为阈值切换

**用户指令**：
> 注释掉ROI自增自减逻辑，改为：默认 modelInputWidth，人物框短边 > modelInputWidth*0.75 切到画面短边，否则恢复默认。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：用阈值切换替代自增自减，稳定 ROI 尺寸
    *   RoiTracker.kt：移除自增自减，新增 modelInputWidth 基准与阈值判断，超阈值时 ROI 取画面短边
    *   AGENTS.md：更新 GeminiHistory 已读编号为 016

---

## [050] 2026-01-07 03:46:05 - 关键点按置信度分级着色

**用户指令**：
> 全身17个点颜色分级：score<0.01灰、<0.05红、<0.1黄、<0.3青、否则绿。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：关键点颜色随置信度变化，便于区分信号强弱
    *   PoseDrawer.kt：新增 getKeypointColor 并在绘制关键点时按置信度设置 kptPaint

---

## [049] 2026-01-07 03:30:18 - 选择新视频后自动切换播放源

**用户指令**：
> 更换视频后没有返回,发现还是之前的视频

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：从设置选择新视频后回到主界面能自动切换播放源
    *   MainActivity.kt：新增 lastVideoSourceKey 与 resolveVideoSourceKey，在 onResume 中检测变更并重启视频；startVideoMode 记录实际使用的视频源

---

## [048] 2026-01-07 03:15:09 - 细化 [045] 的文件改动描述

**用户指令**：
> 去把45改清楚。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把 [045] 的文件改动写清楚并补齐方法关联
    *   codexHistory.md：细化 [045] 的条目说明

---

## [047] 2026-01-07 03:14:03 - 调整变更摘要格式

**用户指令**：
> 不需要写修改文件几个字，写文件名并在条目里标明方法与改动。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 [045] 的变更摘要改为“文件名 + 改动 + 关联方法”的格式
    *   codexHistory.md：仅调整 [045] 的摘要格式

---

## [046] 2026-01-07 03:10:04 - 明确变更摘要的文件与目的描述

**用户指令**：
> 变更摘要要写清楚哪个文件里面动了哪些，目的是什么。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让日志记录更清楚每个文件改动内容
    *   修改文件：codexHistory.md（细化 [045] 的修改文件与目的描述）
    *   涉及方法：无

---

## [045] 2026-01-07 03:05:47 - 设置测试视频来源并持久化选择

**用户指令**：
> 在菜单里加入选择读取的测试视频，使用文件管理器选择并持久化；用户不变更就一直使用该视频。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：新增“选择测试视频”入口，持久化 Uri 并用于视频模式播放
    *   AppSettings.kt：新增 testVideoUri 读取/保存与 setTestVideoUri，持久化所选视频
    *   VideoFeeder.kt：新增 start(Uri) 与 startMediaPlayer(Uri)，支持 Uri 播放
    *   MainActivity.kt：startVideoMode 优先使用 AppSettings.testVideoUri 作为播放源
    *   SettingsHomeFragment.kt：onViewCreated 增加选择视频入口与 SAF 持久化权限
    *   fragment_settings_home.xml：新增“测试视频来源”卡片与按钮
    *   AGENTS.md：更新 GeminiHistory 已读编号到 014
    *   codexHistory.md：记录本条

---

## [044] 2026-01-07 00:33:59 - 同步 GeminiHistory 读取规则并迁移 codexHistory 格式

**用户指令**：
> 目录里面有一个文件叫GeminiHistory.md,是另一个负责小功能实现的ai的日志记录,你每次操作的时候都要先读一下看看有没有更新.其中AINoRead是指令的原文,很长,不需要读.你要学习一下他的每条记录都格式,然后你的日志文件也采用那种格式；下次读取的时候只读取没读取过的新内容:根据编号

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：新增 GeminiHistory 读取规则并将 codexHistory 迁移为相同格式
    *   修改文件：AGENTS.md、codexHistory.md
    *   涉及方法：无

---

## [043] 2026-01-06 21:31:26 - 补充区域编辑入口日志并恢复进入流程

**用户指令**：
> 补充区域编辑入口日志并恢复进入流程

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区域编辑无日志时补充入口诊断并恢复startSubRoomRegionEdit调用
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity.enterRoomAreaEditMode、MainActivity.setupButtons

---

## [042] 2026-01-06 21:16:13 - 区域编辑缺失时自动回填并保留日志

**用户指令**：
> 区域编辑缺失时自动回填并保留日志

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区域编辑时点/高亮不显示时自动回填三角形并保留诊断日志
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw、LivingRoomEditorView.ensureRegionEditFallback

---

## [041] 2026-01-06 04:48:52 - 新增次房间持久化人数与双数字显示

**用户指令**：
> 新增次房间持久化人数与双数字显示

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：实现次房间持久化人数逻辑与双数字显示（即时+持久化），支持消失/进入/回到客厅的加减规则
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/model/RoomConfig.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md
    *   涉及方法：MainActivity.onCreate、MainActivity.findRoomForPoint、MainActivity.incrementPersistentCount、MainActivity.decrementPersistentCount、DetectionOverlayView.onDraw、DetectionOverlayView.drawPawn

---

## [040] 2026-01-06 04:32:50 - 区域编辑时允许拖动房间图标

**用户指令**：
> 区域编辑时允许拖动房间图标

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区域编辑模式下也能随时拖动房间名称与图标
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onTouchEvent

---

## [039] 2026-01-06 04:28:47 - 修复区域编辑房间切换导致保存错房间

**用户指令**：
> 修复区域编辑房间切换导致保存错房间

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：禁止绘制阶段改写regionEditRoomId，避免保存时把A房间的编辑区域写到B房间
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [038] 2026-01-06 04:25:30 - 修复区域编辑编译错误的返回类型

**用户指令**：
> 修复区域编辑编译错误的返回类型

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复enterRoomAreaEditMode与finishRoomAreaEdit的返回类型不匹配导致的编译失败
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity.enterRoomAreaEditMode、MainActivity.finishRoomAreaEdit

---

## [037] 2026-01-06 04:23:59 - 区域编辑切换房间时弹窗确认保存

**用户指令**：
> 区域编辑切换房间时弹窗确认保存

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区域编辑中切换房间时弹窗确认保存/丢弃/取消，避免编辑状态错乱
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.getRegionEditRoomId、LivingRoomEditorView.setSelectedRoomId、MainActivity.applyModeSelection、MainActivity.finishRoomAreaEdit

---

## [036] 2026-01-06 04:19:01 - 区域编辑时不重复绘制已保存区域

**用户指令**：
> 区域编辑时不重复绘制已保存区域

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区域编辑模式下隐藏该房间已保存区域，避免与编辑中的区域重叠显示
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [035] 2026-01-06 04:14:46 - 拖动位置即时保存并统一位置来源

**用户指令**：
> 拖动位置即时保存并统一位置来源

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：取消临时位置体系，拖动后立即保存并统一主界面/编辑界面/区域编辑位置来源
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.getDisplayPoint、LivingRoomEditorView.onTouchEvent、LivingRoomEditorView.startSubRoomRegionEdit、DetectionOverlayView.onDraw、MainActivity.exitEditMode

---

## [034] 2026-01-06 04:05:51 - 拖动位置只在完成时保存，取消不保存

**用户指令**：
> 拖动位置只在完成时保存，取消不保存

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：房间名称/图标拖动仅在点击完成时持久化，取消不保存
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.getDisplayPoint、LivingRoomEditorView.onTouchEvent、LivingRoomEditorView.commitPendingLabelPositions、LivingRoomEditorView.discardPendingLabelPositions、MainActivity.exitEditMode

---

## [033] 2026-01-06 03:54:23 - 恢复区域编辑菜单与流程，修复仅提示不出现编辑的问题

**用户指令**：
> 恢复区域编辑菜单与流程，修复仅提示不出现编辑的问题

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：恢复区域编辑的菜单与保存流程
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：MainActivity.enterRoomAreaEditMode、MainActivity.renderEditorMenu、MainActivity.finishRoomAreaEdit、MainActivity.transitionTo、MainActivity.setupButtons

---

## [032] 2026-01-06 03:48:28 - 名称/图标位置持久化且禁止拖入客厅

**用户指令**：
> 名称/图标位置持久化且禁止拖入客厅

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：名称/图标拖动持久化并禁止进入客厅
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/model/RoomConfig.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.getDisplayPoint、LivingRoomEditorView.onTouchEvent

---

## [031] 2026-01-06 03:43:30 - 拖动名称/图标改为仅内存生效，不再持久化

**用户指令**：
> 拖动名称/图标改为仅内存生效，不再持久化

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：名称/图标拖动不持久化且不修改配置
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/model/RoomConfig.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.getDisplayPoint、LivingRoomEditorView.onTouchEvent

---

## [030] 2026-01-06 03:37:40 - 次房间名称/图标可拖动且不改变锚点

**用户指令**：
> 次房间名称/图标可拖动且不改变锚点

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：支持拖动房间名称与图标位置但不修改锚点
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/model/RoomConfig.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、AGENTS.md、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onTouchEvent、LivingRoomEditorView.getDisplayPoint

---

## [029] 2026-01-06 03:32:17 - 区域编辑优先匹配门边，若不匹配则回退初始三角形

**用户指令**：
> 区域编辑优先匹配门边，若不匹配则回退初始三角形

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复错误复用其他房间区域导致显示错乱
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.startSubRoomRegionEdit、LivingRoomEditorView.isRegionEdgeMatched

---

## [028] 2026-01-06 03:27:20 - 区域编辑高亮绑定当前房间，避免高亮跑到其它房间

**用户指令**：
> 区域编辑高亮绑定当前房间，避免高亮跑到其它房间

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复区域编辑高亮串房间的问题
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [027] 2026-01-06 03:24:46 - 区域编辑时增强可视化（加粗描边与更高透明度）

**用户指令**：
> 区域编辑时增强可视化（加粗描边与更高透明度）

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：排除区域绘制可见性问题
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [026] 2026-01-06 03:20:19 - 区域编辑绘制阶段增加日志，确认是否命中绘制路径

**用户指令**：
> 区域编辑绘制阶段增加日志，确认是否命中绘制路径

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位区域编辑偶发不绘制的问题
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [025] 2026-01-06 03:13:58 - 区域编辑添加日志用于定位不出三角形的问题

**用户指令**：
> 区域编辑添加日志用于定位不出三角形的问题

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位区域编辑不出图形的原因
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.startSubRoomRegionEdit、LivingRoomEditorView.onDraw

---

## [024] 2026-01-06 03:09:58 - 区域编辑初始化失败时给出详细原因提示

**用户指令**：
> 区域编辑初始化失败时给出详细原因提示

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位无法进入区域编辑的具体原因
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.startSubRoomRegionEdit

---

## [023] 2026-01-06 03:04:58 - 区域重叠校验允许共点/共边，仅拦截真实重叠

**用户指令**：
> 区域重叠校验允许共点/共边，仅拦截真实重叠

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：允许相邻房间共点但不重叠
    *   修改文件：app/src/main/java/com/example/roomxxx0102/utils/GeometryUtils.kt、codexHistory.md
    *   涉及方法：GeometryUtils.doPolygonsOverlap

---

## [022] 2026-01-06 02:58:39 - 客厅边界变化时实时同步次房间区域底边，非法则清空

**用户指令**：
> 客厅边界变化时实时同步次房间区域底边，非法则清空

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：客厅编辑实时联动次房间区域底边并在非法时清空
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.updateSubRoomRegionsForLivingRoomChange、LivingRoomEditorView.onTouchEvent、LivingRoomEditorView.handleDeleteVertex、LivingRoomEditorView.undo、LivingRoomEditorView.clear、MainActivity.applyModeSelection

---

## [021] 2026-01-06 02:49:13 - 区域编辑允许门边落在客厅边界，修复默认三角形无法保存

**用户指令**：
> 区域编辑允许门边落在客厅边界，修复默认三角形无法保存

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：允许区域点位于客厅边界并修复默认三角形保存/拖拽问题
    *   修改文件：app/src/main/java/com/example/roomxxx0102/utils/GeometryUtils.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：GeometryUtils.isPointOnPolygonBoundary、LivingRoomEditorView.isRegionPolygonValid、MainActivity.finishRoomAreaEdit

---

## [020] 2026-01-06 02:43:31 - 客厅编辑拖拽顶点时禁止自交

**用户指令**：
> 客厅编辑拖拽顶点时禁止自交

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：客厅多边形允许凹形但禁止自交
    *   修改文件：app/src/main/java/com/example/roomxxx0102/utils/GeometryUtils.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：GeometryUtils.checkSelfIntersection、LivingRoomEditorView.onTouchEvent

---

## [019] 2026-01-06 02:06:33 - 实现次房间感知区域编辑与校验，新增撤销/还原/删除/取消

**用户指令**：
> 实现次房间感知区域编辑与校验，新增撤销/还原/删除/取消

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：实现次房间感知区域编辑、约束与保存校验
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/utils/GeometryUtils.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.startSubRoomRegionEdit、LivingRoomEditorView.onTouchEvent、LivingRoomEditorView.onDraw、LivingRoomEditorView.commitDoorSelection、MainActivity.setupButtons、MainActivity.enterRoomAreaEditMode、MainActivity.finishRoomAreaEdit、MainActivity.unbindRoomsFromRemovedEdges、MainActivity.transitionTo、GeometryUtils.isPolygonSimple、GeometryUtils.doPolygonsOverlap、DetectionOverlayView.onDraw

---

## [018] 2026-01-06 01:32:21 - 增加门选择提交日志，便于定位偶发未保存问题

**用户指令**：
> 增加门选择提交日志，便于定位偶发未保存问题

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位偶发门选择未保存问题
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onTouchEvent、MainActivity.setupButtons

---

## [017] 2026-01-06 01:26:29 - 当前选中房间图标改为彩虹填充，文字临时改为主题色

**用户指令**：
> 当前选中房间图标改为彩虹填充，文字临时改为主题色

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提升选中房间的可视辨识度
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.drawPawn

---

## [016] 2026-01-06 01:20:19 - 门选择时隐藏当前房间旧边主题色，仅显示新选边彩虹高亮

**用户指令**：
> 门选择时隐藏当前房间旧边主题色，仅显示新选边彩虹高亮

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复门选择时旧边仍显示主题色的问题
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [015] 2026-01-06 01:12:53 - 房门选择模式下仅高亮当前待选边，旧边不再保持高亮

**用户指令**：
> 房门选择模式下仅高亮当前待选边，旧边不再保持高亮

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复点新边后旧边仍高亮的问题
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [014] 2026-01-06 01:06:07 - 选中房间时门边保持彩虹高亮，切换新边后旧边回到主题色

**用户指令**：
> 选中房间时门边保持彩虹高亮，切换新边后旧边回到主题色

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复选中房间时边为黑色并统一彩虹高亮规则
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [013] 2026-01-06 01:02:27 - 门边高亮改为彩虹渐变，解除绑定与换边时不保留旧色预览

**用户指令**：
> 门边高亮改为彩虹渐变，解除绑定与换边时不保留旧色预览

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提高选门高亮区分度并修复解除/换边预览残留
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [012] 2026-01-06 00:54:51 - 房间选中时门边统一用高亮色，取消选中后回到房间主题色

**用户指令**：
> 房间选中时门边统一用高亮色，取消选中后回到房间主题色

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复选门导致其它边变红并统一高亮色规则
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [011] 2026-01-06 00:47:25 - 房门选择预览高亮当前待选边，避免影响其它边显示

**用户指令**：
> 房门选择预览高亮当前待选边，避免影响其它边显示

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复房门选择预览颜色错误
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [010] 2026-01-06 00:28:02 - 修复规则与历史文件乱码，并统一结果与任务简报格式

**用户指令**：
> 修复规则与历史文件乱码，并统一结果与任务简报格式

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 AGENTS/codexHistory 乱码并统一结果与简报格式
    *   修改文件：AGENTS.md、codexHistory.md
    *   涉及方法：无

---

## [009] 2026-01-06 00:22:26 - 房门选择点击边后不应清空选中房间，保证完成时能保存

**用户指令**：
> 房门选择点击边后不应清空选中房间，保证完成时能保存

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复点边导致选中房间被清空从而无法保存
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.GestureDetector.onSingleTapUp

---

## [008] 2026-01-06 00:11:20 - 把规则文件也同步描述清楚，并让 codexHistory 结果与简报一致

**用户指令**：
> 把规则文件也同步描述清楚，并让 codexHistory 结果与简报一致

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：规则文件中文化并统一 codexHistory 结果表述
    *   修改文件：AGENTS.md、codexHistory.md
    *   涉及方法：无

---

## [007] 2026-01-06 00:07:28 - 重构编辑菜单状态机，统一入口与状态跳转，避免菜单错乱

**用户指令**：
> 重构编辑菜单状态机，统一入口与状态跳转，避免菜单错乱

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：重构菜单状态机以稳定层级
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：MainActivity.setupButtons、MainActivity.setSubRoomActionMode、MainActivity.setAddSubRoomMode、MainActivity.transitionTo、MainActivity.applyModeSelection、MainActivity.renderEditorMenu、LivingRoomEditorView.setDoorSelectArmed、LivingRoomEditorView.clearPendingDoorSelection、LivingRoomEditorView.discardPendingDoorSelection

---

## [006] 2026-01-05 23:59:53 - 房门选择进入简化菜单；仅完成时保存，未选边提示未保存；支持解除房门绑定

**用户指令**：
> 房门选择进入简化菜单；仅完成时保存，未选边提示未保存；支持解除房门绑定

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：房门选择延迟保存并支持解除绑定；未选边提示未保存
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.setDoorSelectArmed、LivingRoomEditorView.onTouchEvent、LivingRoomEditorView.onDraw、LivingRoomEditorView.clearPendingDoorSelection、LivingRoomEditorView.commitDoorSelection、MainActivity.setupButtons、MainActivity.renderEditorMenu

---

## [005] 2026-01-05 23:48:18 - 所有界面显示门/房间颜色并更粗更亮；客厅设置显示次房间标志和对应线段；新增改代码前确认规则

**用户指令**：
> 所有界面显示门/房间颜色并更粗更亮；客厅设置显示次房间标志和对应线段；新增改代码前确认规则

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：全界面显示门/房间颜色并加粗；客厅设置显示次房间标志与线段；新增改代码前确认规则
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/LivingRoomSetupActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/RoomListFragment.kt、app/src/main/res/layout/item_room_config.xml、AGENTS.md、codexHistory.md
    *   涉及方法：DetectionOverlayView.onDraw、DetectionOverlayView.setLivingRoomVertices、LivingRoomEditorView.onDraw、MainActivity.refreshOverlayDisplay、MainActivity.applyModeSelection、LivingRoomSetupActivity.loadInitialData、RoomListFragment.RoomViewHolder.bind

---

## [004] 2026-01-05 23:27:24 - 次房间图标与房门颜色：未绑定白色，绑定后使用持久化调色板；主界面同步显示

**用户指令**：
> 次房间图标与房门颜色：未绑定白色，绑定后使用持久化调色板；主界面同步显示

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：次房间颜色持久化并在图标与门边同步显示
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/model/RoomConfig.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/utils/RoomColorPalette.kt、codexHistory.md
    *   涉及方法：RoomRepository.updateRoom、RoomRepository.loadFromFile、RoomRepository.saveToFile、RoomRepository.ensureMissingThemeColors、LivingRoomEditorView.onDraw、DetectionOverlayView.onDraw

---

## [003] 2026-01-05 23:12:03 - 次房间编辑/选门时也显示客厅端点(仅显示不拖动)

**用户指令**：
> 次房间编辑/选门时也显示客厅端点(仅显示不拖动)

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：次房间模式显示客厅端点但不拖动
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md
    *   涉及方法：LivingRoomEditorView.onDraw

---

## [002] 2026-01-05 23:09:13 - 再加一条规则，所有的思考和输出我们都要上中文

**用户指令**：
> 再加一条规则，所有的思考和输出我们都要上中文

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：新增全中文输出规则
    *   修改文件：AGENTS.md、codexHistory.md
    *   涉及方法：无

---

## [001] 2026-01-05 23:07:23 - 设置规则：每次文件级编辑后给简报，并新增 codexHistory 记录变更指令与结果

**用户指令**：
> 设置规则：每次文件级编辑后给简报，并新增 codexHistory 记录变更指令与结果

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：新增项目规则与历史日志文件
    *   修改文件：AGENTS.md、codexHistory.md
    *   涉及方法：无

---
