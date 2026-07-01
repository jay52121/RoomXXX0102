# Codex History

## [386] 2026-07-01 10:45:00 - 恢复云端动态 ROI 快速手部链

**用户指令**：
> 对照云端 GitHub 中大幅摆手仍能稳定跟踪的版本，恢复对应快速链路；重大修改需要独立 Git 提交。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：恢复云端版本的动态手部 ROI 与 LIVE_STREAM 快速输入链，同时保留双手识别和关键点退化熔断。
    *   修改文件：`HandSmokeTester.kt`、`VideoFeeder.kt`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`HandSmokeTester.detect`、`VideoFeeder.analyzeRunnable` 手部检测帧分发。
    *   关键改动：
      *   将 `nextHandFrameRoi`（无值时回退 Pose ROI）重新作为 Hand Landmarker 的实际裁剪输入，不再仅用于可视化。
      *   保持 `RunningMode.LIVE_STREAM`、`detectAsync`、双手输出及退化结果拒绝/模型自愈逻辑。
      *   移除每帧强制创建不可变 ARGB_8888 副本，减少高频手部检测链上的 Bitmap 分配和复制。
      *   调试日志明确输出模型实际使用的 ROI。

---

## [385] 2026-07-01 10:29:35 - 收口快速流式手部检测与退化熔断

**用户指令**：
> IMAGE 独立帧虽然稳定，但 21 点刷新极慢；恢复此前接近实时的跟踪速度，同时避免关键点逐渐坍缩。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：兼顾 LIVE_STREAM 的高刷新率与独立帧方案的输出可靠性，不让退化结果污染 UI 和手势链。
    *   修改文件：`HandSmokeTester.kt`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`HandSmokeTester.detect/setupHandLandmarker/processResult/ensureHandLandmarkerConfig/isValidHandGeometry`。
    *   关键改动：
      *   恢复 `RunningMode.LIVE_STREAM` 与 `detectAsync`，拿回 MediaPipe 内部快速跟踪吞吐。
      *   在结果出口按坐标边界和关键点展开范围过滤伪手；正常有效手立即进入原有回调。
      *   若检测到手但全部点集坍缩，则拒绝该帧、保留 UI 上一正常结果，并设置重建标记。
      *   下一输入帧前自动关闭并重建 Hand Landmarker，强制重新执行掌检测，阻断错误状态跨帧延续。
      *   阈值集中为最小关键点展开范围 0.025、坐标边界容差 0.15；双手主手选择和二维控制接口保持不变。
      *   定向单测及 `:app:assembleDebug` 均通过，混合方案 APK 已成功覆盖安装到真机，未自动启动。

---

## [384] 2026-07-01 10:17:57 - 手部检测改为独立帧异步模式

**用户指令**：
> 完整帧刚打开时 21 点正常，随后逐渐收缩并脱离手部；按独立帧方案消除 MediaPipe LIVE_STREAM 内部追踪退化。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：禁止错误手部追踪状态跨帧累积，同时保持 UI 和上层手势链异步、低延迟运行。
    *   修改文件：`HandSmokeTester.kt`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`HandSmokeTester.detect/setupHandLandmarker/processResult/close/ensureHandLandmarkerConfig`。
    *   关键改动：
      *   Hand Landmarker 从 `RunningMode.LIVE_STREAM` 改为 `RunningMode.IMAGE`，每个采样帧独立执行掌检测和 21 点定位。
      *   新增专用单线程执行器与 `AtomicBoolean` 在途控制；模型忙时直接丢弃新帧，不阻塞 UI、不堆积任务。
      *   独立帧结果继续通过原 `onHandsResult/onPointingObservation` 接口输出，双手主手选择、二维控制和绘制无需改接口。
      *   使用提交帧时间戳关联 FrameContext，避免 IMAGE 结果时间戳与项目帧上下文失配。
      *   关闭检测器时同步停止执行器；配置变化仅在无在途推理时重建模型。
      *   定向单测及 `:app:assembleDebug` 均通过，独立帧 APK 已成功覆盖安装到真机，未自动启动。

---

## [383] 2026-07-01 10:11:28 - 取消手部模型动态外部 ROI 裁剪

**用户指令**：
> 视频约 10 秒且画面稳定，继续独立验证动态外部 ROI 与 MediaPipe LIVE_STREAM 内部追踪是否冲突。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：保持画面、模型和内部追踪不变，验证每帧变化的外部裁剪坐标系是否导致原始 21 点坍缩。
    *   修改文件：`VideoFeeder.kt`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`VideoFeeder.analyzeRunnable` 手部检测帧分发。
    *   关键改动：
      *   手部模型改为接收完整视频帧，不再传入每帧变化的 `nextHandFrameRoi/poseRoi`。
      *   Pose ROI、手部 ROI 计算及框显示继续保留，不影响其他检测与调试链路。
      *   CALL_SITE 日志同时标记 visualRoi 与 `detectorRoi=FULL_FRAME`，便于确认验证条件。
      *   定向单测及 `:app:assembleDebug` 均通过，完整帧验证 APK 已成功覆盖安装到真机，未自动启动。

---

## [382] 2026-07-01 00:19:15 - 强制 MediaPipe 输入为不可变 ARGB_8888

**用户指令**：
> 在运行时和官方模型均排除后，按官方输入要求验证 Bitmap 格式是否导致手部原始关键点坍缩。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：单变量验证输入 Bitmap 配置对 MediaPipe Hand Landmarker 原始 21 点输出的影响。
    *   修改文件：`HandSmokeTester.kt`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`HandSmokeTester.detect`。
    *   关键改动：
      *   ROI 裁剪后若 Bitmap 不是 `ARGB_8888` 或仍可变，则复制为不可变 `ARGB_8888` 后再创建 MPImage。
      *   HSMOKE 调用日志增加 source/cropped/input Config、是否复制和最终 mutable 状态，便于确认输入条件。
      *   模型、MediaPipe 0.10.35、运行模式、阈值和手势业务逻辑均保持不变。
      *   定向单测及 `:app:assembleDebug` 均通过，验证 APK 已成功覆盖安装到真机，未自动启动。

---

## [381] 2026-07-01 00:10:45 - 升级 MediaPipe 运行时验证关键点坍缩

**用户指令**：
> 将 MediaPipe tasks-vision 从 0.10.33 升级到官方当前稳定版，验证原始 21 点坐标坍缩是否由运行时版本导致。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不修改模型和业务链的前提下，单变量验证 MediaPipe 运行时版本问题。
    *   修改文件：`app/build.gradle.kts`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：Gradle `dependencies` 中 `com.google.mediapipe:tasks-vision` 版本解析。
    *   关键改动：
      *   将 `tasks-vision` 从 `0.10.33` 升级到官方当前稳定版 `0.10.35`。
      *   保留 2023 年手部模型、关键点映射、双手选择和绘制逻辑不变，确保验证变量单一。
      *   定向单测及 `:app:assembleDebug` 均通过，验证 APK 已成功覆盖安装到真机，未自动启动。

---

## [380] 2026-07-01 00:06:06 - 增加手部关键点坍缩诊断

**用户指令**：
> 看手视图同时检测到两只手时只显示两个点，而不是每只手完整的关键点，需要继续定位原因。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：区分 MediaPipe 原始关键点输出坍缩与项目 ROI 坐标映射坍缩，避免继续猜测绘制或选择逻辑。
    *   修改文件：`HandSmokeTester.kt`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`HandSmokeTester.onLiveStreamResult/logLandmarkSpread/formatPoint`。
    *   关键改动：
      *   每 500ms 输出一次 `HSMOKE|LANDMARK_SPREAD`，分别记录每只手的原始 X/Y 范围、映射后 X/Y 范围及腕点、食指 MCP、食指指尖坐标。
      *   确认模型文件自首次接入后未变化；MediaPipe 依赖曾由 `latest.release` 固定为 `0.10.33`，后续将依据诊断结果决定是否调整依赖。
      *   定向单测及 `:app:assembleDebug` 均通过，诊断 APK 已成功覆盖安装到真机，未自动启动。

---

## [379] 2026-06-30 23:59:45 - 改为手势选主手并增加构建版本信息

**用户指令**：
> 双手都进行识别，由目标手势决定哪只手成为主手；设置页最下方增加版本号和构建时间。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：移除“屏幕位置更高即主手”对二维控制的影响，改为由目标手势稳定激活主手，并让每个 APK 可通过设置页明确识别。
    *   修改文件：`HandTranslationController.kt`、`HandTranslationControllerTest.kt`、`DetectionOverlayView.kt`、`MainActivity.kt`、`SettingsHomeFragment.kt`、`fragment_settings_home.xml`、`app/build.gradle.kts`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`HandTranslationController.updateHands/selectTrackedHand/activate`、`DetectionOverlayView.drawHands/setHandTranslationControlState`、`MainActivity.updateHandTranslationControl`。
    *   关键改动：
      *   控制器每帧评估最多两只手，过滤掌尺度不足的坍缩结果，由最先匹配目标手势的有效手进入 HOLDING。
      *   HOLDING/ACTIVE 使用掌心最近邻连续性维持同一只手，避免 MediaPipe 双手列表顺序交换导致主手跳变。
      *   看手视图始终绘制全部检测手；仅 ACTIVE 主手的全部关键点变绿，其他手保持原色。
      *   增加双手顺序交换并混入 21 点坍缩伪手的回归测试，验证真实手能够从索引 1 切换到索引 0 并正常激活。
      *   Gradle 生成 `BuildConfig.BUILD_TIME`，设置页底部显示 `SISP 版本名 (版本码) · Build 年月日.时分`。
      *   定向单测及 `:app:assembleDebug` 均通过，最新 APK 已成功覆盖安装到真机，未自动启动。

---

## [378] 2026-06-30 23:34:47 - 统一调试面板显示与长按复制内容

**用户指令**：
> 长按调试面板复制的内容与调试面板内显示不一致，检查并修复。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让调试面板长按复制报告的面板主体严格复用当前视图实际使用的数据组装链。
    *   修改文件：`DetectionOverlayView.kt`、`MainActivity.kt`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`DetectionOverlayView.snapshotCurrentDebugPanelLines/drawDebugPanel`、`MainActivity.buildDebugPanelClipboardReport`。
    *   关键改动：
      *   定位到屏幕按看手/看人视图分别构建面板，而长按复制固定读取 `RoiLogAggregator`，导致看手数据被漏掉。
      *   新增当前调试面板即时快照方法，统一处理覆盖面板、看手面板和看人面板。
      *   屏幕绘制与剪贴板报告的 `panel` 区域改为调用同一方法；报告头和历史诊断区继续保留。
      *   整手二维控制定向单测及 `:app:assembleDebug` 均通过，APK 已成功覆盖安装到真机，未自动启动。

---

## [377] 2026-06-30 23:19:47 - 修复实时模式看手视图无检测结果

**用户指令**：
> 看手视图在实时模式下没有任何反应，排查并修复。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：补齐 CameraX 实时模式的手部检测帧链，使现有手部关键点和整手二维控制在看手视图正常运行。
    *   修改文件：`MainActivity.kt`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`MainActivity.startCameraMode`。
    *   关键改动：
      *   定位到实时 CameraX 的 `ImageAnalysis` 仅绑定 Pose/YOLO 分析器，从未调用 `HandSmokeTester.detect`；回顾模式则由 `VideoFeeder` 正常调用。
      *   将实时分析器收口为单次 `ImageProxy.toBitmap()`，继续执行原有 Pose/YOLO 分析，并仅在看手视图把同一 Bitmap 送入现有手部检测器。
      *   统一在外层 `finally` 关闭 `ImageProxy`，避免重复转换和重复关闭；回顾模式链路未修改。
      *   整手二维控制定向单测及 `:app:assembleDebug` 均通过，APK 已成功覆盖安装到真机，未自动启动。

---

## [376] 2026-06-30 23:02:22 - 增加仅看手视图生效的整手二维平移控制

**用户指令**：
> 在现有手部识别链增加整手二维平移控制：拇指和食指张开、其余三指卷曲并稳定保持 1 秒后激活，以激活时手掌中心和尺度为原点计算 X/Y；复用现有标记点和日志面板，满量程参数可在设置中即时调整；暂时只在看手视图生效。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不改模型和主手选择逻辑的前提下，为看手视图增加可调、可视、具备抖动容错的整手二维控制。
    *   修改文件：`HandTranslationController.kt`、`HandTranslationControllerTest.kt`、`AppSettings.kt`、`MainActivity.kt`、`DetectionOverlayView.kt`、`SettingsHomeFragment.kt`、`fragment_settings_home.xml`、codexHistory.md、dialogueHistory.md。
    *   涉及方法：`HandTranslationController.update/activate/updateActivePosition`、`MainActivity.updateHandTranslationControl/applyHandTranslationSnapshot/resetHandTranslationControl`、`DetectionOverlayView.setHandTranslationControlState/drawHands/buildHandPanelLines`、`AppSettings.setHandTranslationFullRange`。
    *   关键改动：
      *   新增 `IDLE/HOLDING/ACTIVE` 状态机，目标手势保持 1000ms 后记录四个掌指关节中心和固定手掌尺度并激活。
      *   HOLDING/ACTIVE 分别提供 150ms/300ms 短暂丢失容错，单帧位移超过激活尺度 0.45 倍时保持上一有效输出。
      *   X 向画面右侧为正，Y 向画面上方为正；输出经 0.3 EMA、2.5 中心死区和 ±100 截断。
      *   复用现有主手和手部关键点结果；ACTIVE 时现有手部标记点改为绿色，状态与 X/Y 覆盖刷新到看手调试面板，不追加刷屏日志。
      *   在可折叠的手部检测参数区增加“二维控制满量程（手掌尺度）”，范围 0.5～3.0、步长 0.1、默认 1.5，修改后立即生效。
      *   离开看手视图、切换观察模式或重启回放时清除状态，其他视图不运行该控制器。
      *   定向单测和 `:app:assembleDebug` 均通过；debug APK 已成功覆盖安装到连接真机，未自动启动。
      *   移除构建资源目录中误放的非 XML 进度表，并原样迁移到 `/Users/yzmac/Documents/2026年参赛大赛进度表（按时间排序）.md`。

---

## [375] 2026-06-29 12:45:00 - 生成包含 APK 与 0629 PPTX 的赛事完整交付包

**用户指令**：
> 将 200 多兆的 APK 包及“基于本地视觉AI的室内空间理解与智能调度平台Sisp0629.pptx”加入赛事提交材料，并搜索 PPTX 位置。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在核心源码披露包之外增加可运行 APK 和项目路演材料，形成完整赛事技术交付件。
    *   修改文件：`/Users/yzmac/Documents/SISP_Competition_Technical_Submission_2026Q2/`、对应外层 ZIP 与 SHA-256 文件，以及 codexHistory.md、dialogueHistory.md。
    *   涉及方法：本机 APK/PPTX 搜索、APK badging 与签名验证、文件分层组包、逐文件 SHA-256、ZIP 结构验证。
    *   关键改动：
      *   定位 PPTX：OneDrive/SISP/基于本地视觉AI的室内空间理解与智能调度平台Sisp0629.pptx，文件约 128MB。
      *   全盘未发现单文件超过 180MB 的 APK；采用当前项目最新 APK，磁盘文件约 114MB，安装解压后体积更大。
      *   APK 核验为应用名 SISP、arm64-v8a、最低 API 26、目标 API 36，APK Signature Scheme v2 验证通过。
      *   外层包按源码、演示 APK、PPTX、验证材料四区组织，并新增 README_FIRST.md 与 APK_INFO.txt。
      *   最终外层 ZIP 约 242MB，包含 7 个交付文件；逐文件哈希、APK/PPTX ZIP 结构、外层 ZIP 完整性和外部 SHA-256 均验证通过。
      *   未包含签名 keystore，当前 Android App 源码未修改。

---

## [374] 2026-06-29 12:37:00 - 完成 Core-first 与 Edge Continuity 赛事架构闭环

**用户指令**：
> 按已确认的正式架构改造赛事源码包：SISP Core 服务端作为主系统，APK 仅承担采集、展示、执行和 Core 不可用时的最小本地冗余；形成可用、降级、恢复的完整闭环并重新打包。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将赛事披露包从本地算法集合升级为 Core-first / Edge-continuity 的完整系统架构表达，并提供可运行状态机与验证测试。
    *   修改文件：Documents 下赛事交付包源码目录、README、架构/能力矩阵/闭环/接口/依赖/披露/验证文档、Gradle 验证入口、ZIP、SHA-256 文件，以及 codexHistory.md、dialogueHistory.md。
    *   涉及方法：SispRuntimeCoordinator.start/submit/attemptRecovery/stop、SispCorePort、FallbackPolicy、SyncJournal、EdgeContinuityRuntime、TrackerServicePort。
    *   关键改动：
      *   新增 `edge-runtime`，实现 `DISCOVERING → CONNECTING → CORE_ACTIVE → EDGE_DEGRADED → RECOVERING → CORE_ACTIVE` 状态闭环。
      *   定义 Core 能力、Edge 能力、执行模式、决策权威、版本化配置、观测、决策、日志和对账数据契约。
      *   Core 正常时执行权威决策；Core 失败时基于缓存配置执行受限本地冗余并写入顺序日志；恢复后补传日志、刷新配置、接受权威对账并清理已确认日志。
      *   将原源码目录规范为 `edge-kws`、`edge-fallback-pointing`、`edge-fallback-spatial`、`shared-contracts`，统一命名空间为 `com.sisp.edge.*` 与 `com.sisp.shared.*`。
      *   将远端跟踪编排更名为 `ResilientTrackEngine`，保留服务端口注入、在途控制、故障冷却和本地降级。
      *   README 重构为 SISP Core / Edge Continuity 系统拓扑，并新增运行闭环、能力矩阵、Core Ports、验证记录和构建说明。
      *   新增三条运行时测试，覆盖 Core 正常、断线降级和恢复对账；独立 Gradle 构建测试通过。
      *   最终包包含 44 个 Kotlin 文件、4574 行源码、61 个交付文件；已清除隐藏文件和构建产物，并通过敏感扫描、ZIP 完整性及全部 SHA-256 校验。
      *   当前 Android App 源码未修改，本次只改造独立赛事交付包。

---

## [373] 2026-06-29 12:09:00 - 将披露包服务连接收口为抽象端口

**用户指令**：
> 检查此前写死的服务器连接能力；在赛事披露包中可将相关方法抽象为接口并省略内部实现，既作为后续正式接口，也增强架构表达。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：从赛事源码披露包中移除固定服务地址和具体网络传输实现，以标准 Ports-and-Adapters 方式展示服务端集成边界。
    *   修改文件：交付包 `core-spatial` 跟踪服务接口与远端跟踪编排源码、README、ARCHITECTURE.md、DEPENDENCIES.md、DISCLOSURE_SCOPE.md、SERVICE_PORT.md、THIRD_PARTY_NOTICES.md、源码清单、ZIP 与校验文件，以及 codexHistory.md、dialogueHistory.md。
    *   涉及方法：RemoteByteTrackEngine 构造注入与服务调用、TrackerServicePort.submitFrame/reset/checkHealth、TrackerEndpointConfig 运行时配置、交付包敏感信息扫描与哈希重建。
    *   关键改动：
      *   从披露包删除具体 `TrackClient`、HTTP/JSON 序列化、固定 URL、端口及终端编号。
      *   新增 `TrackerServicePort` 出站端口、`TrackerEndpointConfig` 和服务响应数据契约。
      *   `RemoteByteTrackEngine` 改为依赖注入服务端口，继续保留异步帧调度、单请求在途控制、失败冷却、缓存结果和本地降级逻辑。
      *   新增 `SERVICE_PORT.md`，说明服务发现、认证、加密、协议协商和生产传输适配属于有限披露中省略的基础设施实现。
      *   重新生成 38 个 Kotlin 文件、3929 行源码的清单、ZIP 和 SHA-256；压缩完整性与全部哈希验证通过。
      *   当前 App 中原有写死 ByteTrack 地址未修改，本次仅更新独立赛事交付包。

---

## [372] 2026-06-29 11:52:00 - 生成赛事核心技术源码有限披露包

**用户指令**：
> 整理一份用于大赛提交的核心源码压缩包，编写规范且具有技术表达力的 README，并说明该资料属于早期 Demo；因现行版本软件著作权尚未完成且涉及公司核心资产，暂不披露完整源码。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：形成一份可供赛事技术审查的 SISP 核心源码有限披露包，在展示真实算法能力的同时隔离未公开资产和内部工程信息。
    *   修改文件：`/Users/yzmac/Documents/SISP_Core_Technology_Source_Disclosure_2026Q2/`、`/Users/yzmac/Documents/SISP_Core_Technology_Source_Disclosure_2026Q2.zip`、codexHistory.md、dialogueHistory.md
    *   涉及方法：核心源码筛选、命名空间规范化、设备指向公共类型解耦、README/架构/算法/依赖/披露范围文档编写、敏感信息扫描、SHA-256 完整性校验。
    *   关键改动：
      *   按 `core-pointing`、`core-kws`、`core-spatial`、`data-contracts` 四个模块整理 38 个 Kotlin 文件，共 4066 行源码。
      *   设备指向模块增加独立会话编排示例并将置信状态公共类型收口到交付副本模型中，不修改当前 App 源码。
      *   统一交付副本命名空间为 `com.sisp.core.*`，移除内部项目代号和固定局域网服务地址。
      *   新增 README、架构说明、算法说明、依赖边界、第三方说明、有限披露说明和评审用途许可证。
      *   明确本包基于早期 Demo（技术验证）版本；现行版本因软件著作权办理及公司核心资产保护暂不披露。
      *   排除模型权重、第三方 AAR、服务端实现、真实配置、密钥、历史记录、业务 UI 与个人文件。
      *   生成逐文件源码清单、内部 `CHECKSUMS.sha256` 和外部 ZIP 校验文件；压缩包完整性及全部哈希验证通过。

---

## [371] 2026-06-08 18:41:56 - 统一设置页选项控件蓝色右侧下拉样式

**用户指令**：
> 设置页中人数算法版本等选项控件不点击前看不到框，框颜色白色导致内容看不到；具备这种选项的控件都应在右侧显示当前选项并带向下箭头，选中的预览应为蓝色，例如日志更新频率“一秒一次”也应在外部蓝色显示。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：统一设置页 Spinner 类选项控件的布局和选中态视觉，使当前值在折叠状态下位于右侧并以蓝色可读显示。
    *   修改文件：app/src/main/res/layout/fragment_settings_home.xml、app/src/main/res/drawable/bg_settings_choice_spinner.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SettingsHomeFragment.createChoiceAdapter、SettingsHomeFragment.onViewCreated、SettingsHomeFragment.syncPoseRoiSizeVisibility、SettingsHomeFragment.syncPointingDisplayModeVisibility
    *   关键改动：
      *   新增 `bg_settings_choice_spinner.xml`，作为设置页选项胶囊背景：浅蓝底、蓝色描边、圆角。
      *   将 Pose ROI 比例、日志更新频率、人数算法版本、手势识别线显示时机四个 Spinner 从“标题下方整行”改为“标题左侧 + 当前值右侧”的横向布局。
      *   移除 Spinner 的白色系统背景预览，改为右侧蓝色当前值，并追加 `▾` 下拉提示。
      *   新增 `createChoiceAdapter()`，统一折叠预览和下拉列表样式；下拉列表中当前选中项使用蓝色文字与浅蓝底。
      *   保留所有原有选项内容、保存逻辑和开关控制流程。
      *   已执行 `./gradlew :app:assembleDebug` 和 `adb install -r app/build/outputs/apk/debug/app-debug.apk`，构建和安装成功；未主动启动 App。

---

## [370] 2026-06-08 16:48:57 - 回顾模式无视频配置提示

**用户指令**：
> 系统新安装默认为 回顾模式，进入回顾模式如果未配置视频，则弹出：未配置回顾视频。请在设置中配置视频或切换为实时模式

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在回顾模式没有可播放视频源时明确提示用户配置视频或切换实时模式。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.startVideoMode、MainActivity.showMissingReviewVideoToast
    *   关键改动：
      *   确认当前 `isVideoMode` 默认为 `true`，新安装默认进入回顾模式。
      *   `startVideoMode()` 在未配置测试视频 URI 且默认本地视频文件不存在时弹出 Toast：`未配置回顾视频。请在设置中配置视频或切换为实时模式`。
      *   增加 1500ms Toast 去重，避免启动/切换过程中重复刷提示。
      *   未自动切换实时模式，保留用户手动选择。
      *   已执行 `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`，构建和安装成功；未主动启动 App。

---

## [369] 2026-06-08 16:45:54 - 房间设置入口增加 SISP Core 提示

**用户指令**：
> 点击房间设置后 toast 弹出：SISP Core 未启动，切换至手动简易房间编辑模式

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在进入手动房间编辑前提示当前 SISP Core 未启动。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.setupButtons 中 btnSetupRoom 点击监听
    *   关键改动：
      *   `btnSetupRoom` 点击时先弹出 Toast：`SISP Core 未启动，切换至手动简易房间编辑模式`。
      *   Toast 后继续调用原有 `enterEditMode()`，不改变房间设置业务流程。
      *   已执行 `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`，构建和安装成功；未主动启动 App。

---

## [368] 2026-06-08 16:38:33 - 优化监控回放界面 SISP 控制栏视觉

**用户指令**：
> 第一阶段只做 UI 视觉风格优化，不改业务逻辑、不改点击事件、不引入新素材、不加图标、不动顶部进度条。重点修改 bg_side_action_panel/button/active/dim 和 activity_main.xml 必要文字颜色、按钮尺寸、padding、margin；按 SISP 深蓝黑半透明面板 + 科技蓝描边 + 克制高亮风格。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将监控/回放界面左右控制栏从临时调试感的青绿色渐变改为 SISP 品牌深蓝黑玻璃风格。
    *   修改文件：app/src/main/res/drawable/bg_side_action_panel.xml、app/src/main/res/drawable/bg_side_action_button.xml、app/src/main/res/drawable/bg_side_action_button_active.xml、app/src/main/res/drawable/bg_side_action_button_dim.xml、app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.refreshRuntimeModeButton、MainActivity.refreshDebugPanelButton
    *   关键改动：
      *   侧栏面板改为 `#E6050B14` 深色半透明背景、`#334B78FF` 蓝色弱描边、22dp 圆角。
      *   默认按钮改为 `#B30D1828` 半透明深蓝底、`#665A8DFF` 描边、15dp 圆角。
      *   高亮按钮改为 `#FF2563FF` 主蓝填充、`#FF66A3FF` 描边、白色文字。
      *   弱化/调试按钮改为 `#991E293B` 灰蓝底、`#55334155` 描边、`#9FB4D0` 文字。
      *   左右按钮宽度、padding、间距和文字色做轻量统一；保留所有 Button 结构和点击事件。
      *   动态的回顾/实时按钮、调试面板按钮同步 active/dim 文字色，避免动态背景与文字层级不一致。
      *   未改顶部进度条、未新增图标、未引入依赖、未改业务逻辑。
      *   已执行 `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`，构建和安装成功；未主动启动 App。

---

## [367] 2026-06-08 16:26:08 - 实现 Live 静止冻结覆盖与模式切换 loading

**用户指令**：
> 回顾模式 -> Live 模式 Live 模式 -> 回顾模式加上吧，Live -> 静止中 先不要。开始吧。冻结 ImageView 覆盖 ok，听你的

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让 Live 静止态用冻结画面覆盖当前预览，并为 Live/回顾模式切换提供轻量 loading 反馈。
    *   修改文件：app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.togglePause、MainActivity.toggleLiveStillState、MainActivity.freezeLivePreviewFrame、MainActivity.clearLiveFrozenFrame、MainActivity.switchToCameraRuntimeMode、MainActivity.switchToVideoRuntimeMode、MainActivity.showRuntimeSwitchLoading、MainActivity.hideRuntimeSwitchLoadingSoon
    *   关键改动：
      *   在 `PreviewView` 上方、`DetectionOverlayView` 下方新增 `liveFrozenFrameView`，用于 Live 静止态显示最后一帧截图。
      *   Live 模式点击状态按钮时走独立 `toggleLiveStillState()`，不再复用视频暂停/步进链路。
      *   进入 Live 静止态时使用 `previewView.bitmap` 填充冻结层；回到 Live 中或切换模式时清除冻结层。
      *   新增 `runtimeSwitchLoading` 中央胶囊提示，回顾 -> Live 显示“正在进入 Live…”，Live -> 回顾显示“正在进入回顾…”。
      *   不解绑 CameraX、不暂停 analyzer、不改模型链路，避免 demo 引入相机恢复延迟和复杂生命周期问题。
      *   已执行 `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`，构建和安装成功；未主动启动 App。

---

## [366] 2026-06-08 16:03:38 - 调整实时模式左侧控制按钮

**用户指令**：
> 实时模式下，左侧应该没有+-5s，播放中和静止中也该改成（静止中和Live 中）

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让实时模式左侧控制更符合 Live 场景，隐藏回顾专用快退/快进按钮并调整状态文案。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.switchToCameraRuntimeMode、MainActivity.switchToVideoRuntimeMode、MainActivity.refreshSeekButtons、MainActivity.refreshPlayStateButton
    *   关键改动：
      *   实时模式下将 `btnRewind` 与 `btnForward` 设为 `INVISIBLE`，不再显示 `-5s/+5s`。
      *   回顾模式下继续按原逻辑显示 `-5s/+5s` 或静止态 `-1帧/+1帧`。
      *   `refreshPlayStateButton()` 根据运行模式显示 `[ Live 中 ]` 或 `[ 播放中 ]`，静止态统一显示 `[ 静止中 ]`。
      *   切换实时/回顾模式时同步刷新状态按钮和 seek 按钮。
      *   已执行 `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`，构建和安装成功；未主动启动 App。

---

## [365] 2026-06-08 15:53:59 - 恢复启动后 Pose 分析链路同步

**用户指令**：
> 好的，这次没问题，但是进入之后明显有问题：首先，画面播放非常卡顿。其次，所有的框都没有绘制出来。你先分析一下。应该是整个模型都没有跑起来。ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：恢复启动后 `VideoFeeder` 的 Pose 分析模式与覆盖层显示模式同步，避免首次播放走旧 YOLO 链路导致人体框和 ROI 不绘制。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.initializeAfterSplashFirstFrame、MainActivity.applySettings、MainActivity.syncRuntimeAnalyzerMode
    *   关键改动：
      *   根据 logcat 判断手部检测和 YOLO 有运行，但 `RoomPoseUiDiag` 没有输出，说明首次启动未同步到 Pose 模式。
      *   新增轻量 `syncRuntimeAnalyzerMode()`，统一同步 `updateHandOverlayMode()` 与 `videoFeeder?.isPoseMode = AppSettings.isPoseModeEnabled`。
      *   在 `VideoFeeder` 创建后、启动权限/播放链路前主动调用该同步方法，确保首次回顾播放就走 Pose 链路。
      *   `applySettings()` 改为复用该同步方法，减少设置链路与启动链路分叉。
      *   已执行 `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`，构建和安装成功；未主动启动 App。

---

## [364] 2026-06-08 15:49:34 - 修复 MainActivity 启动阶段崩溃

**用户指令**：
> 不行了，撤销不了，我们直接在现在的基础上来修复 bug 吧。你先看一下 logcat，看一下为什么报错。现在 logcat 应该很多东西。你要注意筛选搜索是我们的这个应用，我刚刚关闭的。ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复启动重排后 `MainActivity` 启动阶段访问未完成初始化链路导致的崩溃。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.initializeAfterSplashFirstFrame、MainActivity.onResume
    *   关键改动：
      *   通过 crash buffer 定位到 `MainActivity.getOverlayView()` 在启动阶段触发 `NullPointerException`。
      *   为首帧后初始化增加 `isMainStartupInitializing` 与 `isMainStartupInitialized` 防重入标记。
      *   `onResume()` 在主初始化完成前只恢复沉浸式状态并提前返回，避免提前访问设置、仓库、覆盖层和视频链路。
      *   已执行 `./gradlew :app:assembleDebug`，构建通过。
      *   已执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`，安装成功；未主动启动 App。

---

## [363] 2026-06-08 12:13:33 - 最小改动优化冷启动遮罩显示

**用户指令**：
> 请按最小改动优化冷启动体验，目标是让 mainSplashOverlay 尽早显示，不要一口气大重构。系统/默认启动画面尽快退场；mainSplashOverlay 尽早显示并至少稳定显示约 1000ms；不要新建 SplashActivity；不要大改架构。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让 `mainSplashOverlay` 先绘制一帧，再执行原有模型、手势、视频初始化，并限制 APK 只打包真机 arm64-v8a。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/build.gradle.kts、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.onCreate、MainActivity.initializeAfterSplashFirstFrame、MainActivity.startMainSplashOverlay、MainActivity.hideMainSplashOverlayWhenReady、android.defaultConfig.ndk.abiFilters
    *   关键改动：
      *   `onCreate()` 只保留横屏、隐藏系统栏、`setContentView()`、立即显示 `mainSplashOverlay`。
      *   使用 `mainSplashOverlay.post { initializeAfterSplashFirstFrame() }`，确保启动遮罩至少先绘制一帧后再执行原有重初始化。
      *   原 `YoloAnalyzer`、`YoloPoseAnalyzer`、`HandSmokeTester`、`checkPermissionsAndStart()` 和视频启动链路保持原逻辑，只延后到首帧后。
      *   遮罩隐藏改为初始化完成后触发，并保证至少显示 1000ms。
      *   `app/build.gradle.kts` 增加 `abiFilters += listOf("arm64-v8a")`，当前 debug APK 仅包含真机 ABI。
      *   已执行 `./gradlew :app:installDebug`，安装到真机成功；未主动启动 App。当前 APK 大小约 112M，ABI 为 `arm64-v8a`。

---

## [362] 2026-06-07 22:33:13 - 优化启动页加载与回顾模式视频尺寸闪动

**用户指令**：
> 起始页的时间差不多，是这个时间后面真的在加载资源吗？我希望他能把后面的加载资源的时间给省下来。特别是最后几帧，它就卡住了。然后才进入实际界面。现在又会画面先撑满全屏，再缩回常的视频尺寸（目前我是回顾模式，16:9）

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让启动页显示期间同步加载 MainActivity 资源，并减少回顾模式 TextureView 首帧尺寸闪动。
    *   修改文件：app/src/main/AndroidManifest.xml、app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.onCreate、MainActivity.startMainSplashOverlay、MainActivity.startMainSplashLoading、MainActivity.onDestroy、MainActivity.startVideoMode、MainActivity.applyDefaultVideoTextureLayout、AndroidManifest LAUNCHER 入口
    *   关键改动：
      *   启动入口从单独等待 2 秒的 `SplashActivity` 改回 `MainActivity`，`SplashActivity` 暂时保留但不作为入口。
      *   在 `activity_main.xml` 顶层新增 `mainSplashOverlay`，显示横版启动图和右下角三点 loading。
      *   `MainActivity` 启动后立即显示启动遮罩，同时继续初始化模型、播放器和 UI；2 秒后遮罩淡出。
      *   `startVideoMode()` 启动播放器前先按父容器计算 16:9 默认 TextureView 尺寸并保持透明，真实视频尺寸回调后再显示，避免先全屏再缩回。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [361] 2026-06-07 22:25:21 - 替换为横版启动页图片

**用户指令**：
> 已经放进来了

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将启动页主视觉替换为用户放入本机的横版启动页图片。
    *   修改文件：app/src/main/res/drawable-nodpi/sisp_splash.png、app/src/main/res/layout/activity_splash.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：activity_splash全屏背景ImageView、splashLoading叠层
    *   关键改动：
      *   从 `/Users/yzmac/Documents/启动页.png` 复制横版图片到 `drawable-nodpi/sisp_splash.png`。
      *   启动页布局移除临时搭建的品牌文字/图标组合，改为全屏 `ImageView` 显示横版启动图。
      *   保留右下角“进入 SISP”胶囊和三点面包屑 loading，2 秒进入 App 逻辑不变。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [360] 2026-06-07 22:21:00 - 新增 SISP 启动页

**用户指令**：
> APP 的名字改成 SISP，启动页的图改成这张，右下角有个简单的面包屑 进度 loading，2 秒后进入 app

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将应用名称改为 SISP，并新增 2 秒启动页与右下角面包屑 loading。
    *   修改文件：app/src/main/AndroidManifest.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/SplashActivity.kt、app/src/main/res/layout/activity_splash.xml、app/src/main/res/drawable/bg_sisp_splash.xml、app/src/main/res/drawable/bg_sisp_loading_chip.xml、app/src/main/res/drawable/bg_sisp_loading_dot.xml、app/src/main/res/drawable/bg_sisp_splash_icon_shadow.xml、app/src/main/res/drawable-nodpi/sisp_splash_icon.png、app/src/main/res/values/strings.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：SplashActivity.onCreate、SplashActivity.onDestroy、SplashActivity.hideSystemUI、SplashActivity.startBreadcrumbLoading、AndroidManifest LAUNCHER 入口
    *   关键改动：
      *   `app_name` 从 `RoomXXX0102` 改为 `SISP`。
      *   新增 `SplashActivity` 作为 LAUNCHER Activity，强制横屏，全屏隐藏系统栏。
      *   启动页显示 SISP 品牌主视觉与“空间 · 感知 · 智能”文案。
      *   右下角新增“进入 SISP”胶囊与三个循环脉冲小圆点，作为简洁面包屑 loading。
      *   2 秒后自动进入 `MainActivity`，并使用淡入淡出过渡。
      *   当前本机未找到用户消息中的横版启动页原始文件，先使用现有 SISP 图标资源搭建启动页；后续落盘横版图后可直接替换启动页主视觉资源。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [359] 2026-06-07 21:57:18 - 替换应用启动图标

**用户指令**：
> 把这个作为 icon

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将用户提供的 `RoomXXXico.png` 替换为 APK 启动图标。
    *   修改文件：app/src/main/res/drawable-nodpi/roomxxx_icon_foreground.png、app/src/main/res/drawable/ic_launcher_background.xml、app/src/main/res/mipmap-anydpi/ic_launcher.xml、app/src/main/res/mipmap-anydpi/ic_launcher_round.xml、app/src/main/res/mipmap-*/ic_launcher.png、app/src/main/res/mipmap-*/ic_launcher_round.png、codexHistory.md、dialogueHistory.md
    *   涉及方法：Android launcher icon 资源、adaptive-icon foreground/monochrome/background
    *   关键改动：
      *   从 `/Users/yzmac/Documents/RoomXXXico.png` 生成 mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi 的 `ic_launcher.png` 与 `ic_launcher_round.png`。
      *   新增 `drawable-nodpi/roomxxx_icon_foreground.png` 作为 Android 8+ adaptive icon 前景。
      *   `mipmap-anydpi/ic_launcher.xml` 与 `ic_launcher_round.xml` 改为引用新前景图。
      *   `ic_launcher_background.xml` 主背景色改为白色，贴合新图标风格。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [358] 2026-06-07 21:51:41 - 雷达无房间提示与播放按钮初始状态修正

**用户指令**：
> 雷达还是要打开的。只是打开之后有这个提示‘’

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：雷达在无有效房间配置时仍正常打开，只额外提示当前仅显示人体识别；同时修正左侧播放按钮初始文案。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/res/layout/activity_main.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.setupButtons、MainActivity.togglePause、MainActivity.refreshPlayStateButton、activity_main.xml 中 `btnPause`
    *   关键改动：
      *   雷达按钮点击后始终显示雷达层；若 `RoomRepository.hasMeaningfulConfig()` 为 false，则 Toast 提示“无房间信息，请下发房间户型信息或手动配置房间户型，当前仅显示人体识别。”。
      *   新增 `refreshPlayStateButton()` 统一播放按钮文案，当前屏蔽暂停入口时 `PAUSED` 也显示为“[ 播放中 ]”。
      *   `setupButtons()` 初始化播放按钮文案，避免首屏仍显示旧的“暂停”。
      *   布局中 `btnPause` 初始文案改为“[ 播放中 ]”。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [357] 2026-06-07 21:42:55 - 清零左右浮层屏幕边缘外边距

**用户指令**：
> 不是相邻的间距啊。是和屏幕边缘的间距

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：纠正上一轮对“间距”的理解，将左右浮层与屏幕边缘的外边距设为 0。
    *   修改文件：app/src/main/res/layout/activity_main.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：activity_main.xml 中 `llNormalControls`、`llRightActionControls` 与按钮 margin 调整、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将左侧浮层 `layout_marginStart` 从 4dp 改为 0dp。
      *   将右侧浮层 `layout_marginEnd` 从 4dp 改为 0dp。
      *   恢复上一轮误删的相邻按钮小间距，包括左右侧按钮 6dp 分隔和右侧运行模式按钮 10dp 顶部间距。
      *   保留左侧上下分组中间 96dp 避让空档。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [356] 2026-06-07 21:40:02 - 清零左右侧按钮间距

**用户指令**：
> 左右按键间距都设置为 0

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：压缩左右两侧浮层内相邻按钮间距，使按钮排列更紧凑。
    *   修改文件：app/src/main/res/layout/activity_main.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：activity_main.xml 中左右侧浮层按钮 margin 调整、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   移除左侧 `btnRadar`、`btnRewind` 的相邻按钮底部间距。
      *   移除右侧 `btnSettings`、`btnSetupRoom`、`btnDebugPanel` 的相邻按钮底部间距。
      *   移除右侧 `btnRuntimeMode` 与上一按钮之间的顶部间距。
      *   保留左侧上下分组中间 96dp 避让空档，避免遮挡画面主体。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [355] 2026-06-07 21:37:45 - 重构左侧四个播放控制键

**用户指令**：
> 现在我们来帮下面的另外 4 个按钮，跟刚才的规则一样，摆到左边。但是记住，左边或者右边都有可能中间有遮挡，所以我们尽量把中间给留出来，也就是上面排两个，下面排两个。其他的如果是其他的数字也是一样的。要把中间给留出来

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将原底部剩余四个控制键迁移到左侧空白区，并按上下分组保留屏幕中部视频主体区域。
    *   修改文件：app/src/main/res/layout/activity_main.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：activity_main.xml 中 `llNormalControls`、`btnRadar`、`btnPause`、`btnRewind`、`btnForward`、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `llNormalControls` 从底部横向栏改为左侧竖向浮层，复用右侧功能键的圆角面板风格。
      *   四个按钮继续使用原 id，保留雷达、播放/暂停、后退、前进以及长按步进等现有逻辑。
      *   上方分组放置 `雷达`、播放/暂停按钮，下方分组放置 `-5s/-1帧`、`+5s/+1帧`，中间用 96dp 间隔留出画面主体空间。
      *   左侧按钮宽度统一为 82dp，贴近屏幕左边并减少遮挡。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [354] 2026-06-07 21:30:59 - 调整右侧按钮顺序并新增运行模式切换

**用户指令**：
> 系统设置应该在最上面，最下面加一个：实时模式、回顾模式（互斥），分别对应相机和视频播放。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：调整右侧功能键顺序，并新增实时/回顾运行模式互斥切换入口。
    *   修改文件：app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.setupButtons、MainActivity.applySettings、MainActivity.toggleRuntimeMode、MainActivity.switchToCameraRuntimeMode、MainActivity.switchToVideoRuntimeMode、MainActivity.refreshRuntimeModeButton、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将右侧竖排按钮顺序调整为：`系统设置`、`房间设置`、`调试面板`、视图按钮、运行模式按钮。
      *   新增 `btnRuntimeMode`，在视频播放时显示 `回顾模式`，在相机模式时显示 `实时模式`。
      *   点击运行模式按钮时在相机实时模式和视频回顾模式之间互斥切换。
      *   切到实时模式时暂停视频、隐藏 `TextureView` 并启动相机；切到回顾模式时解绑相机、隐藏 `PreviewView` 并启动视频。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [353] 2026-06-07 21:17:03 - 修复右侧功能键触摸与宽度

**用户指令**：
> 两个问题。第一个问题是右边新加的这些按钮的最右边，它的事件不太对。我点上去之后，它是执行的所有辅助界面消失的那个事件，只有这些按钮的左半部分可以。
> 其次就是这个按钮太宽了。我还是希望它尽量窄一些。尽量贴着右边边儿一些

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复右侧功能键右半区被黑边预览触摸逻辑抢占的问题，并缩窄按钮、贴近屏幕右侧。
    *   修改文件：app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.dispatchTouchEvent、MainActivity.isBlankAreaTouch、MainActivity.isTouchInsideView、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将右侧功能键宽度从 104dp 收窄为 82dp，高度从 44dp 调整为 40dp，字体降为 12sp。
      *   将右侧功能键容器右边距从 18dp 调整为 4dp，内边距从 8dp 调整为 5dp，使其更贴近屏幕右边。
      *   调整触摸分发顺序，先让正常控件消费事件，未消费时才进入黑边预览逻辑。
      *   黑边预览命中检测增加控制区排除：右侧功能键、底部控制栏、事件标注栏区域内不触发“隐藏辅助界面”事件。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [352] 2026-06-07 21:13:04 - 修复启动后只有声音无画面

**用户指令**：
> 现在直接没有画面，只有声音。进去之后

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复上一轮隐藏 `TextureView` 导致播放器有声音但无画面的回归。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.startVideoMode、VideoFeeder.setupMediaPlayer、VideoFeeder.adjustAspectRatio、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   不再使用 `View.INVISIBLE` 隐藏 `TextureView`，避免阻断或干扰播放器 Surface 创建。
      *   启动视频模式与初始化播放器时保持 `TextureView` 为 `VISIBLE`，但设置 `alpha=0` 暂时隐藏未稳定画面。
      *   视频尺寸布局应用完成后将 `TextureView.alpha` 恢复为 `1f`，保留减少闪动的效果同时恢复画面显示。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [351] 2026-06-07 21:09:16 - 修复启动时全屏与视频比例闪动

**用户指令**：
> 每次启动的时候，16:9 和 全面就会闪动几次

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：减少主界面启动时全屏相机层与视频 16:9/原比例层之间来回闪动。
    *   修改文件：app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.startVideoMode、VideoFeeder.setupMediaPlayer、VideoFeeder.adjustAspectRatio、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `PreviewView` 初始状态改为 `gone`，避免视频模式启动前先显示全屏相机预览层。
      *   `startVideoMode()` 先隐藏 `PreviewView`，并让 `TextureView` 保持 `VISIBLE` 以创建 Surface，同时用 `alpha=0` 隐藏未稳定画面。
      *   `VideoFeeder.setupMediaPlayer()` 初始化播放器时保持 `TextureView` 可见但透明，等待真实视频尺寸回调后再显示。
      *   `VideoFeeder.adjustAspectRatio()` 在父容器未测量完成时延后重试，应用最终宽高后再显示 `TextureView`，并对重复尺寸更新做去重。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [350] 2026-06-07 20:59:10 - 重构主界面右侧四个功能键

**用户指令**：
> AUDIO：声音视图就行了。 左边的暂时不动，我们下一步来改左边 4 个，先把右边 4 个进行修改吧。因为整个屏幕是 21:9 的，但是视频拍摄一般是 16:9 的，所以左右其实都留了一些空间。那我们就把右边的空间作为这个东西，放这 4 个键。暂时

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：利用 21:9 屏幕右侧空白区，将主界面右侧四个功能键从底部栏迁移为竖排浮动操作区，并优化文案与视觉样式。
    *   修改文件：app/src/main/res/layout/activity_main.xml、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/res/drawable/bg_side_action_panel.xml、app/src/main/res/drawable/bg_side_action_button.xml、app/src/main/res/drawable/bg_side_action_button_active.xml、app/src/main/res/drawable/bg_side_action_button_dim.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.updateHandOverlayMode、MainActivity.refreshDebugPanelButton、MainActivity.setupButtons、MainActivity.toggleEditModeUI、MainActivity.enterBlankPreviewMode、MainActivity.cancelBlankPreviewTracking、MainActivity.refreshEventMarkerUi、MainActivity.syncDeviceHitSelectionUi、MainActivity.applyUiLayerMode、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增右侧竖排容器 `llRightActionControls`，将 `btnSetupRoom`、`btnSettings`、`btnDebugPanel`、`btnHandOverlay` 移入该容器，底部控制栏暂只保留左侧播放相关按钮。
      *   四个按钮文案改为 `房间设置`、`系统设置`、`调试面板`、`看人视图/看手视图/声音视图`。
      *   调试面板按钮文字固定为 `调试面板`，通过亮色/暗色背景表示开关状态。
      *   新增右侧面板与按钮的圆角渐变背景资源，减少系统默认控件感。
      *   将右侧功能键容器纳入雷达、编辑、空白预览、设备选择等模式的显隐与层级同步，保持原底部按钮行为一致。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [349] 2026-06-06 20:02:44 - 将 ByteTrack 状态并入 SISP Core 卡片

**用户指令**：
> 把 ByteTrack 也进去，改成ByteTrack 服务状态

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 ByteTrack 服务状态从调试设置卡片移入顶部 SISP Core 服务区域，并统一状态文案。
    *   修改文件：app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SettingsHomeFragment.fetchTrackerStatus、SettingsHomeFragment.onViewCreated、SettingsHomeFragment.switchNewTracker 监听、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `tv_tracker_status` 从“调试显示设置”卡片搬到 SISP Core 卡片展开区域。
      *   ByteTrack 状态文案统一改为 `ByteTrack 服务状态：未启用/检测中/可用/不可用`。
      *   保留原有 ByteTrack 开关与轮询逻辑，仅调整展示位置和文字。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [348] 2026-06-06 19:56:51 - 增加 SISP Core 自动搜索圆环

**用户指令**：
> 这个未连接后面，我希望有一个圈一直在无限循环地转，表示我们在自动搜索中。这个圈要漂亮一点，科技一点，但是要尽量减少占用。如果有什么现成的控件也可以的

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在 SISP Core 未连接状态后增加轻量自动搜索动效，强化“正在发现 Core”的演示反馈。
    *   修改文件：app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SettingsHomeFragment.updateSispCoreUi、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 SISP Core 状态行中新增 18dp 小号 indeterminate `ProgressBar`，放在状态文字之后、展开按钮之前。
      *   将圆环 tint 设置为青蓝色 `#00BCD4`，保持轻量、科技感和较小占用。
      *   圆环仅在 `Disconnected` 自动搜索状态显示，进入连接中或已连接状态时隐藏。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [347] 2026-06-06 19:43:15 - 调整 SISP Core 状态行颜色

**用户指令**：
> 看起来没什么问题，我们稍微做一些颜色上的修改：
> 1. SPCO 要高亮一些，把它做成蓝色吧，因为它本身也是一个标题。
> 2. “未连接”要是一个红色。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：优化设置页 SISP Core 状态行的视觉层级，让标题更醒目、未连接状态更明确。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SettingsHomeFragment.updateSispCoreUi、SettingsHomeFragment.buildSispStatusText、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   使用 `SpannableString` 对 `SISP Core：未连接` 进行分段着色。
      *   `SISP Core：` 标题部分改为蓝色，强化模块标题感。
      *   `未连接` 状态部分改为红色，连接中和已连接状态暂不额外改色。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [346] 2026-06-05 22:04:05 - 增加 SISP Core 服务端连接入口

**用户指令**：
> 设置最上方增加：你现在要在现有 Android APK 项目中新增/完善一个“SISP Core 服务端连接”UI 模块。
> 目标：
> 这个模块用于比赛演示 APK。它暂时不需要真的连接服务端，但界面和交互要看起来真实、克制、产品化。它不是服务端配置中心，只是客户端连接 SISP Core 的入口。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在设置页顶部新增 SISP Core 客户端连接折叠卡片，用于演示 APK 展示本地 Core 发现、终端标识和手动连接入口。
    *   修改文件：app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SettingsHomeFragment.SispCoreConnectionState、SettingsHomeFragment.setSispCoreExpanded、SettingsHomeFragment.setSispManualFormExpanded、SettingsHomeFragment.buildSispTerminalIdentity、SettingsHomeFragment.updateSispCoreUi、SettingsHomeFragment.startSispManualConnection、SettingsHomeFragment.finishSispConnectionFailure、SettingsHomeFragment.clearSispConnectionRunnable、SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onDestroyView、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在设置页最上方新增 SISP Core 折叠卡片，默认只显示 `SISP Core：未连接`，展开后显示自动搜索状态、终端标识与手动连接入口。
      *   终端标识仅使用 `Build.VERSION.RELEASE`、`Build.MANUFACTURER`/`Build.BRAND` 和 `Build.MODEL`，不读取敏感唯一硬件标识，缺失时降级为 `Android 终端`。
      *   新增 `Disconnected`、`Connecting`、`Connected` 三态 UI，当前默认未连接，连接动作为本地 2.5 秒模拟 loading 后失败，不发起真实网络请求。
      *   手动连接表单仅包含服务端地址、端口和 `连接` 按钮，包含空地址、空端口、端口范围 1-65535 的轻量校验。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [344] 2026-06-05 20:54:00 - 修复测试视频选择 URI 授权链路

**用户指令**：
> 1. AS 没有能不能用其他的在线看输出？还是需要的吖 2.刚刚就是选视频的时候报错的，是不是该检查下权限申请相关链路？
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复无 Android Studio 环境下通过 adb 捕获到的测试视频选择权限崩溃，稳定 ACTION_OPEN_DOCUMENT 视频 URI 的持久授权链路。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/data/repository/VideoRoomConfigManager.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SettingsHomeFragment.selectVideoLauncher、SettingsHomeFragment.persistVideoUriPermission、SettingsHomeFragment.buildVideoPickerIntent、SettingsHomeFragment.launchVideoPicker、SettingsHomeFragment.refreshVideoListContent、SettingsHomeFragment.onViewCreated、VideoRoomConfigManager.resolveVideoDisplayName、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将测试视频选择入口从只返回 `Uri` 的 `OpenDocument` launcher 改为 `StartActivityForResult`，从返回 `Intent` 中读取真实授权 flags。
      *   新增 `persistVideoUriPermission()`，统一执行 `takePersistableUriPermission()`，优先使用系统返回的 READ/WRITE 授权位，兜底保留 READ 权限。
      *   新增 `buildVideoPickerIntent()` 与 `launchVideoPicker()`，让主按钮和“从文件中加载”入口共用同一套 ACTION_OPEN_DOCUMENT 权限配置。
      *   `VideoRoomConfigManager.resolveVideoDisplayName()` 对 `contentResolver.query()` 增加 `SecurityException` 兜底，旧 URI 授权失效时不再因读取显示名直接崩溃。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [345] 2026-06-05 00:00:00 - 固化项目 debug keystore 以支持覆盖安装

**用户指令**：
> 请在 Windows 项目 D:\Users\YZ\AndroidStudioProjects\RoomXXX0102 中查找当前用于 debug 构建的旧 debug keystore。
> 目标：
> 1. 找到旧 debug keystore
> 2. 将它复制到项目内：keystores/debug.keystore
> 3. 修改 app/build.gradle.kts，让 debug 构建显式使用这个 keystore
> 4. 确认 keystores/debug.keystore 被 git 跟踪。
> 5. 运行：.\gradlew.bat :app:assembleDebug
> 6. 提交并推送到当前分支“设备与语音匹配”。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把当前机器已有的旧 debug keystore 固化到项目内，并让 debug 构建显式使用该签名，保证后续调试安装可覆盖。
    *   修改文件：keystores/debug.keystore、app/build.gradle.kts、codexHistory.md、dialogueHistory.md
    *   涉及方法：android.signingConfigs.debug、android.buildTypes.debug、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在当前机器上查找到旧调试签名 `C:\Users\YZ\.android\debug.keystore`，并复制到项目内 `keystores/debug.keystore`。
      *   在 `app/build.gradle.kts` 中新增 `signingConfigs.debug`，显式指定 `../keystores/debug.keystore`、`androiddebugkey` 及默认调试口令。
      *   在 `buildTypes.debug` 中显式绑定该 debug 签名，不修改 release 配置。
      *   执行 `:app:assembleDebug` 验证通过，确认显式 debug 签名配置可正常产出 APK。

---

## [343] 2026-04-20 00:04:19 - 增加载入其他视频配置文件入口

**用户指令**：
> 新需求:在设置房间配置管理中,增加一个button- 载入其他视频的配置文件.
> 然后扫描我们目录中的所有配置文件,在弹出的窗口中选择,然后把那个配置文件另存一份应用到当前视频,需求清晰吗?
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在房间配置管理中新增“载入其他视频的配置文件”入口，实现跨视频配置扫描、选择、复制为当前视频副本并立即应用。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/VideoRoomConfigManager.kt、app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoRoomConfigManager.listAllConfigFiles、VideoRoomConfigManager.buildImportedConfigFileForCurrentVideo、SettingsHomeFragment.showImportOtherVideoConfigDialog、SettingsHomeFragment.confirmImportOtherVideoConfig、SettingsHomeFragment.importOtherVideoConfig、SettingsHomeFragment.onViewCreated、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `VideoRoomConfigManager` 新增全量扫描 `room_configs` 目录的能力，并新增“为当前视频生成唯一导入副本文件名”的辅助方法。
      *   在设置页“房间配置管理”卡片中新增按钮 `载入其他视频的配置文件`。
      *   在 `SettingsHomeFragment` 中新增跨视频配置导入链路：扫描所有非当前视频的配置文件、弹窗列表选择、确认后复制到当前视频目录、再通过 `RoomRepository.switchToConfigFile()` 立即应用。
      *   导入逻辑采用“复制后应用”，不直接引用源文件，避免不同视频之间共享同一份配置文件。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [342] 2026-03-31 23:59:00 - 新增手部检测参数调节折叠区

**用户指令**：
> 在说什么呀？重新阅读我最后的话。在
> 是的，这个选项区是可以折叠起来的。展开之后才可以调节，开始吧。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在设置页新增一个可折叠的“手部检测参数调节”区域，并把手部检测置信度、手部存在置信度、手部跟踪置信度三项做成中文拖拽调节。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：AppSettings.init、AppSettings.setHandDetectionConfidence、AppSettings.setHandPresenceConfidence、AppSettings.setHandTrackingConfidence、SettingsHomeFragment.setHandDetectionParamsExpanded、SettingsHomeFragment.syncHandDetectionConfidenceViews、SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onResume、HandSmokeTester.detect、HandSmokeTester.setupHandLandmarker、HandSmokeTester.ensureHandLandmarkerConfig、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `AppSettings` 新增三项浮点配置并持久化：`handDetectionConfidence`、`handPresenceConfidence`、`handTrackingConfidence`，默认均为 `0.5`。
      *   在设置页布局新增一个可折叠区域 `手部检测参数调节`，展开后显示三组 `SeekBar + 当前值`：`手部检测置信度`、`手部存在置信度`、`手部跟踪置信度`。
      *   在 `SettingsHomeFragment` 新增折叠状态控制、数值格式化、进度与置信度互转，以及三条 `SeekBar` 的持久化绑定；默认折叠，展开后可调。
      *   `HandSmokeTester` 改为在每次 `detect()` 前检查这三项设置是否变化，若变化则自动重建 `HandLandmarker`，使新阈值在下一次检测前即可生效。
      *   初始化日志补充输出当前使用的三项阈值，便于调试确认。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [341] 2026-03-31 04:05:00 - 收口设备窗口级指向判定新链并补齐回归测试

**用户指令**：
> 下面是一个AI的参考意见,结合实际额情况给方案:现在开始把“设备窗口级指向判定算法”的所有最终补丁，合并成一套唯一的、完整的实现。
> 不要再保留分散的局部 patch 风格逻辑，不要再继续做新的理论扩展。
> 目标是：把当前已经定稿的算法收口成一个清晰、可调试、可测试、可直接接入现有项目的最终版模块。
> （后续补充约束：第一阶段先不要大面积搬空旧 TriggeredPointingResolver.kt；先新增并收口 DevicePointingGeometry.kt、DevicePointingScoringConfig.kt、DevicePointingDebugModels.kt、DevicePointingModels.kt；DevicePreparedTarget 必须承载所有静态预计算量；调试输出先做结构化 debug model。）
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：按设备链独立收口的顺序，把设备窗口级指向判定的新几何打分链、窗口统计链和结构化调试模型落成唯一真相版本，并补齐最小可跑回归测试。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingGeometry.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingScoringConfig.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingDebugModels.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingModels.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingScorer.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingWindowJudge.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DeviceTriggeredPointingResolver.kt、app/src/test/java/com/example/roomxxx0102/logic/pointing/DevicePointingScorerTest.kt、app/src/test/java/com/example/roomxxx0102/logic/pointing/DevicePointingWindowJudgeTest.kt、app/build.gradle.kts、codexHistory.md、dialogueHistory.md
    *   涉及方法：DevicePointingGeometry.point、DevicePointingGeometry.rect、DevicePointingScorer.prepareTargets、DevicePointingScorer.buildFrameEvaluation、DevicePointingScorer.scoreTarget、DevicePointingScorer.logFrameScores、DevicePointingWindowJudge.judge、DevicePointingWindowJudge.logWindowResult、DeviceTriggeredPointingResolver.submitFrame、DeviceTriggeredPointingResolver.finalizeDecision、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `DevicePointingScoringConfig`，把热点半径、四边形辅助半径、近场豁免、后向容忍、近场裁剪、极近豁免、窗口阈值和输出策略等设备链参数集中到一处。
      *   新增 `DevicePointingDebugModels`，定义 `DeviceFrameDebugMetrics` 和 `DeviceWindowDebugMetrics`，让逐帧/整窗调试输出先结构化再映射成日志和调试快照。
      *   扩展 `DevicePreparedTarget`，把短边长度、热点命中半径、四边形辅助半径、近场豁免距离、后向容忍距离、穿透归一长度、基础近场裁剪距离、极近绝对豁免距离、近场设备距离阈值全部作为静态预计算量承载。
      *   重写 `DevicePointingScorer`：完整实现极近豁免、热点方向一致因子、近场方向一致因子、前向因子、热点得分、近场目标支持因子、裁剪后射线、四边形得分、纯几何得分和单帧总分，并输出帧级 debug metrics。
      *   重写 `DevicePointingWindowJudge`：完整实现时间权重、峰值时间因子、加权平均项、时序峰值项、加权命中比例、加权领先比例、动态最终阈值以及高置信/低置信/未定输出，并输出窗口级 debug metrics。
      *   收口 `DeviceTriggeredPointingResolver`：设备模式改为只接新 `DevicePointingScorer` / `DevicePointingWindowJudge`，旧 `TriggeredPointingResolver.kt` 先保留兼容壳和共享类型，不做激进抽空。
      *   新增 `DevicePointingScorerTest` 与 `DevicePointingWindowJudgeTest`，并补 `testImplementation(libs.junit)`；同时把新设备链里的 `PointF/RectF` 参数构造改为稳定 helper，解决 JVM 单测下 Android 图形对象构造退化问题。
      *   验证通过：`:app:testDebugUnitTest` 成功，设备链最小回归测试可跑通。

---

## [340] 2026-03-31 03:25:48 - 看手模式下自动续上常驻指向会话

**用户指令**：
> 手势质量分和下面几行还是只有在窗口里面才会刷新。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复看手模式下 pointing resolver 会话结束后不再继续处理后续手势观测，导致质量分和下面几行仍然只在识别窗口内刷新的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.handleTriggeredPointingObservation、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `handleTriggeredPointingObservation()` 中，收到新的 `HandObservation` 时如果当前 resolver 不 active 且当前处于看手模式，则自动重新启动 `startTriggeredPointingSession()`。
      *   启动后继续用当前这帧 observation 提交给 resolver，使看手常驻会话在结束后能够自动续上，不再只在语音触发窗口内刷新质量分、路径、Top3 等面板文字。
      *   非看手模式保持原逻辑，resolver 不 active 时直接返回。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [339] 2026-03-31 03:20:53 - 让看手调试面板文字持续实时刷新

**用户指令**：
> 手势的质量分能够让它一直刷新吗？
> 是的，包括下面还有其他的几个能一直刷新的都让它一直刷新。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把看手调试面板里的文字数据源从灰线显示时机控制里拆开，让手势质量、分数、Top3 等实时文字持续刷新。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.setPointingDebugOverlayEnabled、DetectionOverlayView.updatePointingPanelSnapshot、DetectionOverlayView.buildPointingPanelLines、MainActivity.handleTriggeredPointingObservation、MainActivity.applySettings、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `DetectionOverlayView` 新增 `pointingPanelSnapshot` 与 `updatePointingPanelSnapshot()`，专门作为看手调试面板的实时文字数据源。
      *   `buildPointingPanelLines()` 改为优先读取 `pointingPanelSnapshot`，不再依赖灰线显示链路里的 `livePointingSnapshot`。
      *   `MainActivity.handleTriggeredPointingObservation()` 改为每次观测都把 `latestDebugSnapshot()` 送进 `updatePointingPanelSnapshot()`，同时保留灰线仍由 `shouldShowLivePointingDebug()` 控制。
      *   `applySettings()` 在离开看手模式时清空 `pointingPanelSnapshot`，避免其它模式残留上一次手势数据。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [338] 2026-03-31 03:14:59 - 新增手势识别线显示时机设置

**用户指令**：
> 我观察到了现在不管是刷新还是灰线都只有当。在识别窗口里面才会显示出来。平时不会显示出来。 去设置项里面加一个手势识别线。显示时机。第一个是永远显示，第二个是识别窗口显示，第三个是。永不显示。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：给看手里的灰色手势识别线和实时 pointing 调试增加显示时机选项，支持“永远显示 / 识别窗口显示 / 永不显示”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/res/values/arrays.xml、app/src/main/res/layout/fragment_settings_home.xml、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：AppSettings.init、AppSettings.setPointingDebugDisplayMode、SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onResume、SettingsHomeFragment.syncPointingDisplayModeVisibility、MainActivity.handleTriggeredPointingObservation、MainActivity.applySettings、MainActivity.shouldEnablePointingDebugOverlay、MainActivity.shouldShowLivePointingDebug、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `AppSettings` 新增 `pointingDebugDisplayMode`，持久化三种模式：永远显示、识别窗口显示、永不显示。
      *   在设置页 `显示 pointing 调试 overlay` 下面新增 `手势识别线显示时机` 下拉框，并在开关关闭时自动隐藏。
      *   `MainActivity.applySettings()` 改为按新模式控制 overlay 总开关，并在不该显示实时灰线时主动清空 `liveSnapshot`。
      *   `handleTriggeredPointingObservation()` 改为仅在当前显示模式允许时，才把实时 pointing 快照送进 overlay；“识别窗口显示”沿用语音触发窗口期，“永远显示”则看手模式下持续更新。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [337] 2026-03-31 03:06:14 - 取消看手调试面板整块覆盖并合并标注信息

**用户指令**：
> 打开这个现在看手面板里面什么东西都没加进去。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复看手右侧调试面板被设备命中事件标注信息整块覆盖，导致 pointing 调试内容完全看不到的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.setHandDebugPanelExtraLines、DetectionOverlayView.buildHandPanelLines、DetectionOverlayView.drawDebugPanel、MainActivity.refreshDebugPanelMode、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `DetectionOverlayView` 新增 `handDebugPanelExtraLines` 与 `setHandDebugPanelExtraLines()`，专门用于给看手调试面板追加附加文本。
      *   `buildHandPanelLines()` 改为先显示设备命中事件标注附加内容，再显示 pointing 调试内容与 ROI 相关项。
      *   `drawDebugPanel()` 在看手模式下默认标题改为 `看手调试面板`，不再依赖整块 override 才能区分。
      *   `MainActivity.refreshDebugPanelMode()` 改为不再调用 `setDebugPanelOverride("看手调试面板", lines)` 覆盖整块面板，而是改用 `setHandDebugPanelExtraLines(lines)` 并清掉 override。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [336] 2026-03-31 02:58:50 - 按观察模式拆分右侧调试面板内容

**用户指令**：
> 你得把这些东西拆分开不然耦合太强了都不知道怎么办。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将右侧可开关调试面板按观察模式拆分，避免看手模式继续混入看人的房间/Presence/Pose 大量内容。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.buildPointingPanelLines、DetectionOverlayView.buildPersonPanelLines、DetectionOverlayView.buildHandPanelLines、DetectionOverlayView.drawDebugPanel、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `buildPersonPanelLines()`，保留看人的原始调试面板内容：`debugInfo`、当前 Pose ROI 占比、`RoiLogAggregator.snapshotForPanel()`。
      *   新增 `buildHandPanelLines()`，让看手模式的右侧调试面板只显示指向/手势相关信息，并补充手部 ROI 与少量 Pose ROI 关联项。
      *   `drawDebugPanel()` 改为按当前模式选择数据源：看手显示 `buildHandPanelLines()`，其它模式显示 `buildPersonPanelLines()`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [335] 2026-03-31 02:53:39 - 将左上角指向文字调试并入可开关面板

**用户指令**：
> 你现在有两个调试面板，一个是左上角的一个是可以开关的调试面板，把所有的东西都放到可以开关的调试面板里面去。而且现在很奇怪的是左上角那个很久才刷新一次。希望放进去之后就不会这样。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：移除左上角单独的 pointing 文字调试块，把指向状态、路径、分数、当前手势质量等信息统一并入右侧可开关调试面板，减少双面板割裂。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.drawPointingDebugOverlay、DetectionOverlayView.buildPointingPanelLines、DetectionOverlayView.drawDebugPanel、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   删除 `drawPointingDebugOverlay()` 末尾左上角 `drawDebugTextBlock()` 的 pointing 文字块。
      *   新增 `buildPointingPanelLines()`，把指向状态、接受路径、最佳目标、次高分、当前手势质量、有效帧/无手帧、Top3 等统一整理成调试面板文本。
      *   `drawDebugPanel()` 在原有 ROI/房间调试内容前追加这组 pointing 信息，使其随可开关调试面板一起显示。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [334] 2026-03-31 02:35:52 - 在看手调试面板显示当前手势质量

**用户指令**：
> 把frameQuality放到看手的调试面板.用中文
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在看手模式的指向调试面板中直接显示 `frameQuality`，方便观察灰色引导线显示条件，文案使用中文。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.drawPointingDebugOverlay、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在指向调试文本块里新增 `当前手势质量=xx` 一行，直接展示 `debugSnapshot.frameQuality`。
      *   不改指向判定算法、不改灰色引导线绘制逻辑，只补充调试可观测信息。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [333] 2026-03-31 02:06:19 - 为语音触发设备窗口补超时兜底结果

**用户指令**：
> 1.有open命中,没有结果.
> 这样命中设备和未命中都会更新进去吗
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复语音触发设备窗口只显示 `open命中...` 但没有后续结果的问题，为没有后续手势观测帧的情况补一个超时兜底结算，确保最终一定落到命中或未命中。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.startTriggeredPointingSession、MainActivity.handleTriggeredPointingDecision、MainActivity.triggerPointingSessionFromVoice、MainActivity.schedulePendingVoiceTimeout、MainActivity.cancelPendingVoiceTimeout、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `pendingVoiceTimeoutRunnable`，在语音触发设备窗口后按前向 `250ms` 安排一次超时兜底。
      *   若窗口后半段没有新的 `HandObservation` 进入、resolver 仍处于 `active`，则主动调用 `pointingResolver.submitFrame(null)` 触发现有 timeout 判定，从而落出 `设备未命中`。
      *   命中链路不变，仍由正常手势观测实时更新；成功/失败/启动失败时都会取消超时兜底，避免重复结算。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [332] 2026-03-31 01:53:36 - 恢复语音触发的 open/close 中间提示

**用户指令**：
> open命中，启动一次手势设备匹配 这套才是对的,弄哪里去了
> ok，而且要持续更新

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把语音触发设备识别时的中间设备提示从固定的“准备识别”改回 `open/close命中，启动一次手势设备匹配`，并确保每次新的语音命中都会覆盖更新中间窗。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.bindKwsCommandRelay、MainActivity.triggerPointingSessionFromVoice、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `bindKwsCommandRelay()` 向 `triggerPointingSessionFromVoice()` 传入当前命令类型，支持按 `OPEN/CLOSE` 区分中间提示文案。
      *   `triggerPointingSessionFromVoice()` 在每次语音命中开始时直接显示 `${command}命中，启动一次手势设备匹配`，替代固定 `准备识别`。
      *   语音触发文案使用 `lowercase(Locale.US)` 输出为 `open/close`，并继续由后续 `命中：... / 设备未命中 / 指向识别启动失败` 覆盖。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [331] 2026-03-31 01:43:41 - 统一设备中间提示只响应语音触发

**用户指令**：
> 还是不一样!
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让看手与听声音界面的中间设备提示彻底同源，只响应语音触发的设备窗口判定，不再被看手模式下持续手势会话的命中/未识别结果打扰。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.startTriggeredPointingSession、MainActivity.handleTriggeredPointingDecision、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `startTriggeredPointingSession()` 里仅当 `pendingVoicePointingFeedback` 为真时才显示 `指向识别启动失败` 中间提示。
      *   `handleTriggeredPointingDecision()` 里仅当本次为语音触发时才显示设备命中中间提示，避免看手模式下持续手势会话不断覆盖中间窗。
      *   非语音触发的 `Unrecognized` 不再向中间窗写入 `未识别(...)`；语音触发失败继续统一显示 `设备未命中`，看手与听声音因此完全共用同一条设备提示链。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [330] 2026-03-31 01:37:33 - 统一看手与听声音的设备提示语义

**用户指令**：
> 听声音和看手的中央窗还是不一样.
> ok,开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让看手与听声音界面的中间设备提示彻底统一，去掉看手独有文案，并统一语音触发时的准备态与未命中长驻行为。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.applyObserveMode、MainActivity.handleTriggeredPointingDecision、MainActivity.triggerPointingSessionFromVoice、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   移除看手模式独有的 `手点采样+指向识别中` 中间提示。
      *   语音触发设备识别时，无论看手还是听声音，都统一显示 `准备识别`，不再在听声音侧显示 `open命中，启动一次手势设备匹配`。
      *   语音触发失败时的 `设备未命中` 长驻提示统一到看手与听声音两边；手模式仍保留失败 Top3 调试快照展示。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [329] 2026-03-31 01:31:54 - 中间信息窗按观察模式分流

**用户指令**：
> 中间的信息窗全乱了,他在各个界面应该是一样的链路,而不是看手听声音不同.
> 中间窗口目前到底承载了哪些东西
> 那的确有点问题.这样,我们在听声音和看手界面只显示设备(声音属于设备)有关的.在看人界面只显示和房间以及人数pose那些.彻底分开
> ok
> 开始啊

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将中间信息窗按观察模式彻底分流为“设备类”和“房间/人数/presence 类”两条逻辑，避免看手/听声音与看人界面互相串消息。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.shouldShowCenterBanner、MainActivity.showCenterBanner、MainActivity.clearModeMismatchedCenterBanner、MainActivity.applyObserveMode、MainActivity.startTriggeredPointingSession、MainActivity.handleTriggeredPointingDecision、MainActivity.triggerPointingSessionFromVoice、DetectionOverlayView.clearUnlockBanner、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `MainActivity` 新增 `CenterBannerDomain`，把中间信息窗分成 `DEVICE` 与 `ROOM` 两类。
      *   所有设备识别相关提示（准备识别、设备命中、设备未命中、指向识别启动失败、手点采样+指向识别中）统一走 `DEVICE` 分流，仅在看手/听声音模式放行。
      *   房间切换、人数扣减、pose/presence 内部提示、事件匹配和校验异常等统一走 `ROOM` 分流，仅在看人模式放行。
      *   在 `DetectionOverlayView` 增加 `clearUnlockBanner()`，观察模式切换时如果上一条提示不属于新模式，就立即清掉，避免旧的持久消息跨模式残留。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [328] 2026-03-31 01:11:12 - 修复听声音日志补写时序与设备未命中文案

**用户指令**：
> 现在中间的状态写的是未识别(以及原因),右边的也还是只有open 351ms
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复语音触发设备判定失败时顶部仍显示“未识别(原因)”的问题，并修复听声音面板右侧日志因时序竞争导致第二段设备结果耗时未补写的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：KwsPanelScreen、appendLog、updateCommandLog、MainActivity.handleTriggeredPointingDecision、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `KwsPanelScreen` 增加 `pendingLogUpdates` 待补写缓存；当设备结果先于命中日志到达时，先按 token 暂存，待 `open 351ms` 这条日志真正插入后再自动补写 `设备 xxms / 未命中 xxms`。
      *   将语音触发失败时的顶部文案统一收口为 `设备未命中`，不再回退到 `未识别(原因)`。
      *   清空日志时同时清掉待补写缓存，避免旧 token 污染新日志。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [327] 2026-03-31 01:04:21 - 听声音日志补写设备结果耗时

**用户指令**：
> 在听声音面板里面。每一航日志除了。标题里面写多少毫秒命中之外，再把。多少毫秒后识别失败，也显示出来。 例如: open 351ms 设备命中 19ms 或者 open 351ms 设备未命中 250ms
> 不用显示成功失败两个字. 另外第二段时间我会按“从语音命中事件到设备窗口判定结束”的耗时来算是错的. 命中应该是命中到的时间.未命中才应该是窗口 (此时理论上上方中间的实时信息窗口应该也正好显示设备未命中(以前的识别失败改一下文字))
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让听声音面板右侧日志在保留语音命中耗时的同时，补写设备命中/未命中耗时，并把顶部失败提示改成“设备未命中”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：KwsPanelScreen、appendLog、updateCommandLog、MainActivity.syncAudioScreenMode、MainActivity.bindKwsCommandRelay、MainActivity.triggerPointingSessionFromVoice、MainActivity.handleTriggeredPointingDecision、MainActivity.startTriggeredPointingSession、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   听声音面板日志项新增 `token` 概念，直接复用 `KWS CommandEvent.timestampMs`，保证同一次语音命中与后续设备结果可以精确关联到同一行日志。
      *   新增 `AudioCommandLogUpdate` 与 `updateCommandLog()`，使声音面板可以在命中日志生成后，再补写第二段摘要为 `设备 xxms` 或 `未命中 xxms`。
      *   主界面增加 `latestKwsAudioLogUpdate` 状态，把语音触发设备识别的最终结果回传给 `KwsPanelScreen`；命中时使用“设备真正命中的耗时”，未命中时使用“窗口结束耗时”。
      *   语音触发设备识别改为以 `KWS event.timestampMs` 作为窗口中心，确保前后250ms窗口与日志耗时基准一致。
      *   顶部中间提示文案由 `识别失败` 改为 `设备未命中`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [326] 2026-03-31 00:00:00 - 语音触发手势识别支持前250ms回放

**用户指令**：
> 我们当前的方案,能不能回溯250ms?从准备识别的时间开始为中心,前后250ms作为窗口期.
> 1.这整个500ms的内容全部判断完估计需要多久?10ms? 2.250ms喂进去之后已经可以开始判断,不一定要吃满500ms,准确说是只要250里面满足了,后面的都不需要吃
> 让你开始

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将语音触发的一次手势设备识别改成“以准备识别时刻为中心，前250ms历史回放 + 后250ms实时补齐”的窗口模式，并支持在前250ms历史帧中提前命中后立即结束。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.startTriggeredPointingSession、MainActivity.handleTriggeredPointingObservation、MainActivity.triggerPointingSessionFromVoice、MainActivity.rememberPointingObservation、MainActivity.replayRecentPointingObservations、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `MainActivity` 中增加最近手势观测的环形缓冲，长期保留约 1.2s，供语音触发时回溯读取。
      *   语音触发时以 `SystemClock.uptimeMillis()` 为中心，将 session 起点回拨 250ms，并先按时间顺序回放这 250ms 内的历史 `HandObservation`。
      *   如果历史帧回放过程中已经满足快接受/正常接受，则立即结束，不再继续吃后 250ms 的实时帧；否则继续沿用现有 500ms resolver 窗口接收后续实时帧。
      *   普通“进入看手模式”的连续会话保持原样，不启用历史回放。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [325] 2026-03-31 00:00:00 - 手势设备识别窗口收紧为500ms

**用户指令**：
> 识别窗口改成500ms.其他的也要随之改
> 识别失败之类的都要同步改哦
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将语音触发的手势设备匹配窗口从1000ms收紧为500ms，并同步调整快接受/正常接受门槛及成功后调试快照保留时长。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/pointing/TriggeredPointingResolver.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：PointingConfig 默认配置、TriggeredPointingResolver.evaluateFastAccept、TriggeredPointingResolver.evaluateNormalAccept、TriggeredPointingResolver.finalizeTimeoutDecision、MainActivity.handleTriggeredPointingDecision、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `timeoutMs` 从 `1000L` 调整为 `500L`。
      *   将 `fastAcceptMinElapsedMs` 调整为 `120L`，`normalAcceptMinElapsedMs` 调整为 `220L`，并把正常接受窗口帧要求从 3 帧收紧为 2 帧，适配更短识别窗口。
      *   将识别成功后调试快照的短暂保留时长从 `1000L` 同步改为 `500L`，保证成功/失败链路与新窗口长度一致。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [324] 2026-03-31 00:00:00 - 识别失败设备同时显示峰值分与最终分

**用户指令**：
> 我们同时标记两个分数第一个分数是。这段时间的最高分，也就是这一秒识别窗口内的最高分和。最后的最终分数。 ,颜色要区分
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在识别失败暂停后显示的 Top3 设备上，同时标记“窗口内峰值分”和“最终分”，并用不同颜色区分。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/pointing/TriggeredPointingResolver.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：TriggeredPointingResolver.submitFrame、TriggeredPointingResolver.finalizeRecognized、TriggeredPointingResolver.finalizeUnrecognized、TriggeredPointingResolver.peakScoreForTarget、TriggeredPointingResolver.buildFinalTargetDebugInfos、DetectionOverlayView.drawPointingDebugOverlay、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   为 `PointingTargetDebugInfo` 增加 `peakScore` 字段，表示这一秒识别窗口内该设备曾达到的最高分。
      *   在 pending 快照中实时累计每个设备的峰值分；在最终快照中改用窗口最终加权分作为 `score`，同时保留同窗口峰值分。
      *   overlay 在设备标签上同时绘制 `峰xx` 和 `终xx` 两组分数，并用不同颜色区分。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [323] 2026-03-30 23:00:17 - 黑边纯画面预览改为按住即触发

**用户指令**：
> 好吧我说错了应该是按着的时候他就隐藏现在隐藏操作看起来没有问题，你把它这个概念改一下就行了。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将看人/看手界面的黑边纯画面预览，从“长按达到阈值后触发”改为“按住黑边空白处立即触发、松手恢复”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.dispatchTouchEvent、MainActivity.handleBlankPreviewTouch、MainActivity.enterBlankPreviewMode、MainActivity.cancelBlankPreviewTracking、MainActivity.refreshEventMarkerUi、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   去掉黑边预览的长按延时状态机，`ACTION_DOWN` 命中黑边空白处后直接进入纯画面预览。
      *   `MOVE` 只负责在手指移出空白区或超出触摸容差时恢复 UI，`UP/CANCEL` 统一立即恢复。
      *   修复 `ViewConfiguration` 导入缺失导致的编译阻塞，并重新验证 `:app:compileDebugKotlin` 成功。

---

## [322] 2026-03-30 22:54:23 - 长按黑边空白处进入纯画面预览

**用户指令**：
> 在看人和看手界面中,长按屏幕空白处(例如目前最左边和最右边都是黑的这种空白),隐藏屏幕里面所有东西,只要画面本身,好实现吗
> 关键是你准备怎么做是去隐藏这一大堆东西呢？还是说把画面直接给提到顶层来？这两种我都可以接受你看怎么实现更好一些。
> 先git一次,然后进行

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在看人/看手模式中，长按视频外侧黑边空白处时临时隐藏所有 UI，只保留视频画面，松手后恢复。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.dispatchTouchEvent、MainActivity.handleBlankPreviewTouch、MainActivity.isBlankPreviewEligible、MainActivity.isBlankAreaTouch、MainActivity.enterBlankPreviewMode、MainActivity.cancelBlankPreviewTracking、MainActivity.refreshEventMarkerUi、MainActivity.onPause、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `dispatchTouchEvent()` 中增加“黑边空白处长按”检测，仅在 `看人/看手` 模式、且非设备选择态/非编辑态时生效。
      *   通过延迟长按状态机进入纯画面预览，不改变层级，只临时隐藏 `overlay`、普通控制、事件条、编辑栏、计数器、雷达层和编辑视图。
      *   松手、取消、移动出空白区域或页面进入后台时，恢复进入预览前保存的可见性状态。
      *   在 `refreshEventMarkerUi()` 中增加预览态保护，避免识别回调在长按期间把隐藏的 UI 又刷出来。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [321] 2026-03-30 22:46:43 - 识别失败时显示Top3设备分数并保持到下次播放

**用户指令**：
> 识别失败的时候，把。嗯，评分最高的前三名。的分数显示在。对应的设备里面。
> 由于这个时候是暂停了，所以就让它一直显示着。直到下次播放。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在语音触发的一次设备窗口判定最终失败时，仅显示评分最高前三名设备的分数，并保持显示到下一次重新播放。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.handleTriggeredPointingDecision、MainActivity.buildTop3FailureSnapshot、MainActivity.togglePause、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在失败分支中，将 `pointingResolver.latestDebugSnapshot()` 裁成只包含 Top3 设备的快照，再交给 overlay 持续显示。
      *   失败快照使用 `holdMs=0L` 长驻保留，不再自动消失。
      *   在从 `STILL` 切回 `PLAYING` 时，主动清掉这份失败快照，确保“保持到下次播放”为止。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [320] 2026-03-30 22:36:33 - 新增识别失败暂停开关

**用户指令**：
> 加一个开关:识别失败暂停
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：增加 `识别失败暂停` 开关，并在语音触发的一次设备窗口判定最终失败时按配置自动切到 `STILL`。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/res/layout/fragment_settings_home.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：AppSettings.init、AppSettings.setPauseOnVoiceRecognizeFailEnabled、SettingsHomeFragment.onViewCreated、MainActivity.handleTriggeredPointingDecision、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `AppSettings` 中新增 `isPauseOnVoiceRecognizeFailEnabled` 配置并完成持久化。
      *   在设置页增加 `识别失败暂停` 开关，并接通到 `AppSettings`。
      *   在语音触发且本次窗口判定最终显示 `识别失败` 时，若开关打开、当前处于视频模式且播放状态不是 `STILL`，则自动调用现有暂停入口切到 `STILL`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [319] 2026-03-30 05:26:47 - 语音触发手势识别时补充准备识别与识别失败提示

**用户指令**：
> 当语音那边识别到一次命中(不管是open还是cloase)的时候。在看手界面上方中部提示框显示:准备识别.然后窗口期内未识别到设备要显示:识别失败. 类似的状态要保留到下一次刷新的时候.
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在看手界面中，为语音触发的设备窗口判定补充 `准备识别` 与 `识别失败` 状态提示，并让这类提示保留到下一次状态覆盖。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.showUnlockBanner、MainActivity.startTriggeredPointingSession、MainActivity.handleTriggeredPointingDecision、MainActivity.bindKwsCommandRelay、MainActivity.triggerPointingSessionFromVoice、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   为 `DetectionOverlayView.showUnlockBanner()` 增加可选 `durationMs` 参数，允许设置长驻提示。
      *   将语音侧 `OPEN/CLOSE` 都接入一次设备窗口判定触发，不再只处理 `OPEN`。
      *   在语音触发且当前处于看手模式时，窗口启动后顶部中部提示显示 `准备识别`，并使用长时长保留。
      *   在本次语音触发的窗口期内若最终未识别到设备，则显示 `识别失败`，同样使用长时长保留；成功识别后清除这次语音反馈标记。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [318] 2026-03-30 05:18:57 - 新增多人指向手归属与Firebase分发待办

**用户指令**：
> 写2个todo,1 同一时间识别到多个人的手,应该以哪个人的手为指向手目前有问题. 2Firebase分发.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将“多人同时识别到手时的指向手归属问题”以及“Firebase 分发接入”记录为正式待办项。
    *   修改文件：todo.md、codexHistory.md、dialogueHistory.md
    *   涉及方法：TODO-005新增、TODO-006新增、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `todo.md` 中新增 `TODO-005`，记录多人同时出现时的指向手归属判定问题。
      *   在 `todo.md` 中新增 `TODO-006`，记录 Firebase 分发接入事项。
      *   历史与对话归档同步更新。

---

## [317] 2026-03-30 05:17:24 - 在真实裁剪开关下增加Pose ROI比例选项

**用户指令**：
> 设置项里面增加一个ROI比例选项:选项如下:动态,(目前的),960,640,480.
> 这个加在启用真实剪裁里面作为他的选项
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在“启用 Pose ROI 真实裁剪”开关下面增加 `Pose ROI比例` 子选项，并让 `roiTracker` 按该配置生效。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/res/layout/fragment_settings_home.xml、app/src/main/res/values/arrays.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：AppSettings.init、AppSettings.setPoseRoiSizeMode、SettingsHomeFragment.onViewCreated、SettingsHomeFragment.onResume、SettingsHomeFragment.syncPoseRoiSizeVisibility、MainActivity.createPoseRoiTracker、MainActivity.syncPoseRoiTrackerConfig、MainActivity.onResume、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `AppSettings` 中新增 `poseRoiSizeMode` 配置及四档常量：`动态（目前）/960/640/480`，并完成持久化。
      *   在设置页 `switch_roi_crop` 下方增加 `Pose ROI比例` 标签和下拉框；开关打开时显示，关闭时隐藏。
      *   在 `SettingsHomeFragment` 中接通下拉选择与配置保存，并在进入页面、返回页面时同步选中值与显隐状态。
      *   在 `MainActivity` 中把 `roiTracker` 改成可按配置重建：动态档保持现有自适应逻辑，`960/640/480` 三档使用固定基础 Pose ROI 尺寸并关闭自适应扩缩。
      *   在 `onResume()` 中同步 `roiTracker` 配置，确保从设置页返回后立即生效。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [316] 2026-03-30 05:09:40 - 将人体ROI标题统一收口为Pose ROI

**用户指令**：
> 好的,标题改成Pose ROI,设置项里面的也检查下,需要改的都改成这个Pose Roi,开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把人体 ROI 相关标题统一收口为 `Pose ROI`，并同步设置页里的对应文案。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/res/layout/fragment_settings_home.xml、app/src/main/res/values/arrays.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoiLogAggregator.snapshotForPanel、DetectionOverlayView.drawDebugPanel、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将调试面板中的 `人体ROI框`、`当前人体ROI占比`、`人体ROI尺寸变化` 分别改为 `Pose ROI框`、`当前Pose ROI占比`、`Pose ROI尺寸变化`。
      *   将 `DetectionOverlayView` 顶部补充行里的 `当前人体ROI占比` 同步改成 `当前Pose ROI占比`。
      *   将设置页开关文案 `启用 ROI 真实裁剪 (Beta)` 改为 `启用 Pose ROI 真实裁剪 (Beta)`。
      *   将 ROI 日志模式下拉项里的 `ROI每次移动一次` 改为 `Pose ROI每次移动一次`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [315] 2026-03-30 05:02:49 - 临时屏蔽暂停中入口仅保留播放与静止两态

**用户指令**：
> 暂时屏蔽掉，暂停中那个。功能滞留播放和静止。记住，只是注释掉后面还会可能启用的。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：临时停用“暂停中”入口，只保留 `播放中 <-> 静止中` 两态切换，同时保留 `PAUSED` 状态与相关代码以便后续恢复三态。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.togglePause、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `togglePause()` 中加入中文注释，明确说明“暂停中”入口为临时屏蔽，后续可能恢复。
      *   将按钮切换关系从 `PLAYING -> STILL -> PAUSED -> PLAYING` 改为 `PLAYING -> STILL -> PLAYING`；如果当前已经处于 `PAUSED`，下一次切换仍回到 `PLAYING`。
      *   保留 `PlayState.PAUSED` 枚举与 `PAUSED` 分支处理代码不删除，仅关闭当前入口。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [314] 2026-03-30 04:57:59 - 调试面板补当前人体ROI占比并给扩到短边加1秒延迟

**用户指令**：
> 另外把这两个ROI搬家上面的ROI里面去。
> 要注意看一下最上面的ratio那个在我看来才是对的。
> 好.这个历史值来自于哪里？我从头到尾都没看到这个。这样你先。设计一个。遇到了。被撑大的情况？先延迟一秒再被撑大。刚才说的这些东西一起改掉。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把 ROI 相关信息在面板里排成一组，明确“当前人体ROI占比”和“历史触发占比”的区别，并让人体 ROI 只有连续超阈值 1 秒后才扩到短边。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiTracker.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoiLogAggregator.updateHumanRoiRatio、RoiLogAggregator.snapshotForPanel、RoiTracker.calculate、RoiTracker.resetSmoothing、MainActivity.pose UI更新链、DetectionOverlayView.drawDebugPanel、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `RoiLogAggregator` 中新增 `humanRoiRatio`，并在调试面板里把 ROI 相关信息重排为：`人体ROI框`、`当前人体ROI占比`、`人体ROI尺寸变化`、`手部ROI框`、`手部ROI尺寸变化`。
      *   在 `MainActivity` 中将当前实时 `roiRatio` 同步给 `RoiLogAggregator`，并把 `DetectionOverlayView` 顶部 `roiRatio` 文案改为中文 `当前人体ROI占比`。
      *   在 `RoiTracker` 中新增 `enlargePendingSinceMs`；人体 ROI 从 `640` 扩到短边时，不再一旦超过 `0.85` 就立刻扩容，而是要求连续超过 `0.85` 满 1 秒才扩到短边；中途回落或目标丢失会取消这次待扩容计时。
      *   缩回 `640` 的 `0.35` 阈值保持立即生效，ROI 其他行为不变。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [313] 2026-03-30 04:48:28 - 将人体ROI与手部ROI在调试面板中彻底拆开

**用户指令**：
> 我看到现在有两个ROI1个是ROI radio，一个是ROI尺寸变化你是把刚才那个东西写到。尺寸变化那儿了吗？尺寸变化指的是手部的?
> 这里的size写的291,明显不可能是pose roi,pose的roi现在和屏幕短边一样
> 我的意思是写到日志面板里面去改好
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复调试面板里“人体 ROI”和“手部 ROI”混用同一条尺寸变化字段的问题，避免手部 `size=291...` 被误读成 pose ROI。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiTracker.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoiLogAggregator.updateHandRoiVisual、RoiLogAggregator.updateRoiSizeChange、RoiLogAggregator.snapshotForPanel、RoiTracker.calculate、DetectionOverlayView.updateHandRoiBox、MainActivity.roiTracker/handRoiTracker 初始化、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `RoiLogAggregator` 中新增手部 ROI 框状态和手部 ROI 尺寸变化字段，调试面板改为分别显示：`人体ROI框`、`手部ROI框`、`人体ROI尺寸变化`、`手部ROI尺寸变化`。
      *   `RoiTracker` 新增 `logSource` 标识，人体 `roiTracker` 写入“人体ROI”，手部 `handRoiTracker` 写入“手部ROI”，不再共用同一个 `sizeChange` 字段。
      *   在 `DetectionOverlayView.updateHandRoiBox()` 中把手部 ROI 框状态同步进日志聚合器，确保调试面板能同时看到手部 ROI 框。
      *   保持 ROI 行为与阈值逻辑不变，只修复日志面板的来源混淆问题。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [312] 2026-03-30 04:35:54 - 将ROI尺寸变化及中文原因补进调试面板

**用户指令**：
> 后面没有更信息，一来就是。数字没有显示过什么，后面跟着中文信息。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“ROI 尺寸变化中文原因只进入日志、没有进入调试面板”的显示链问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoiLogAggregator.snapshotForPanel、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   保留原有 `sizeChange=... 原因=...` 日志格式不变。
      *   在 `snapshotForPanel()` 中新增 `roi尺寸变化=` 行，把现有 `sizeChange` 文本明确输出到调试面板。
      *   这样面板里会直接看到“尺寸变化 + 中文原因”，而不是只在 logcat 中存在。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [311] 2026-03-30 04:33:02 - 在ROI尺寸变化日志中补充中文原因

**用户指令**：
> 我们的ROI默认是六百四对吧？现在他还是不缩小。你在这个日志后面加一下他为什么会变,写在那个日志里面去。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在现有 ROI 尺寸变化日志后面直接补出中文原因，方便定位是重匹配重置、扩容到短边、缩回 640，还是手动尺寸覆盖导致的变化。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiLogAggregator.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiTracker.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoiLogAggregator.updateRoiSizeChange、RoiTracker.calculate、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `RoiLogAggregator.updateRoiSizeChange()` 扩展为接收 `reason` 参数，并把中文原因直接拼到原有 `size=... ratio=... maxSide=...` 日志后面。
      *   在 `RoiTracker.calculate()` 中针对四种来源补充具体原因文本：`新匹配目标，先重置为基础ROI`、`当前占比超过0.85，扩到短边`、`当前占比低于0.35，缩回基础ROI`、`使用手动传入的ROI尺寸`。
      *   保持 ROI 行为与倍率阈值不变，只增强日志可解释性。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [310] 2026-03-30 04:28:02 - 停用GeminiHistory强制读取并在ROI重新匹配时重算倍率

**用户指令**：
> 1.AGENTS.md里面还有要求读 GeminiHistory.md ?
> 2.roi在这个视频里面一直没到过0.85
> 1.注释掉这个要求. 2.这个视频从来没到过0.85 3.我们进行一次修改吧,每当ROI新匹配一个人时,就要重新计算倍率.
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：停用 AGENTS 中强制读取 `GeminiHistory.md` 的执行要求，并修复 ROI 在重新匹配到新目标时沿用上一人倍率状态的问题。
    *   修改文件：AGENTS.md、app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiTracker.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：AGENTS 项目规则第1条、RoiTracker.calculate、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `AGENTS.md` 中“每次改代码前读取 GeminiHistory.md”的规则改为停用说明，不再作为当前执行要求。
      *   在 `RoiTracker.calculate()` 中新增“重新匹配目标”判定：当 ROI 从未跟踪/丢失状态切回有目标状态时，先将 ROI 物理尺寸重置回 `baseRoiSizePx`，再按当前这个新目标重新执行倍率判断。
      *   这样新匹配到的人不会继承上一个人的“大ROI/短边ROI”状态，只有当前新目标自身满足阈值时才会再次扩容。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [309] 2026-03-30 04:11:30 - OPEN命中后常驻触发一次手势设备匹配

**用户指令**：
> 我们开始做下一件事情每一次，当我们open被识别的时候(很奇怪的是现在日志里面如果不打开听声音,就没有这个日志)。嗯。立即触发一次手势设备匹配。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让 `open` 口令在不打开“听声音”界面时也能被常驻接收，并在每次命中后立即触发一次现有设备手势匹配。
    *   修改文件：kws-sdk/src/main/java/com/example/roomxxx_vocie/KwsControllerImpl.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：KwsControllerImpl.setExtraCommandListener、KwsControllerImpl.onAudioFrame、MainActivity.onCreate、MainActivity.syncAudioScreenMode、MainActivity.bindKwsCommandRelay、MainActivity.triggerPointingSessionFromVoice、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `KwsControllerImpl` 中新增附加命中监听，保留原有 UI `setListener` 的同时，将口令事件额外转发给主界面常驻逻辑，避免声音界面日志监听覆盖主链监听。
      *   在 `MainActivity` 启动时常驻绑定 `OPEN` 命中回调；每次识别到 `Command.OPEN` 时，立即复用现有 `startTriggeredPointingSession()` 触发一次设备手势匹配。
      *   调整 `syncAudioScreenMode()`，不再在离开“听声音”界面时销毁 `ComposeView` 内容，从而保证 KWS 在界面隐藏时也持续运行；这样不打开声音界面时同样会有 `open` 命中和手势匹配触发。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [308] 2026-03-30 03:57:52 - 听声音界面默认开启电平表并上移左栏控件

**用户指令**：
> 继续吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：进入“听声音”界面时默认打开电平表，并将电平表与操作按钮整体上移到左栏顶部，减少来回视线切换。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：KwsPanelScreen、startMeterIfAllowed、左栏布局顺序、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `meterEnabled` 默认值改为开启状态，使进入“听声音”界面时电平表默认处于打开状态。
      *   将左栏中的“电平表开关 + 电平条 + Peak/RMS/CLIP”以及“应用/清空/堵塞/音源”按钮整体上移到左栏顶部。
      *   保留监听开关、参数滑条、状态文本原有逻辑，只调整默认开关与布局顺序，不改 KWS 主链。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [307] 2026-03-30 03:52:55 - 修复视频循环后听声音模块停摆

**用户指令**：
> 当一次正常播放完回到开头时候，整个。听声音的模块就像是。死掉了，一样。电平表不动了，右边的识别也没进行了。
> ok，然后只要进入听声音界面,电平表就打开.另外把电平表和下方的操作按钮都放到左侧顶部去.直接开始
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复视频正常循环回到开头后，播放器音频源解码退出导致电平表与 KWS 识别一起停摆的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/audio/PlaybackVideoAudioSource.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：PlaybackVideoAudioSource.decodeLoop、PlaybackVideoAudioSource.resetDecoderToPlayback、PlaybackVideoAudioSource.seekExtractorToPlayback、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在播放器音频解码循环中新增“播放器时间回绕”检测；当视频从后段回到前段时，不再让音频源停在 EOF，而是自动清空累积器并将 `extractor + decoder` seek 回当前播放位置继续解码。
      *   将原来 `outputDone=true` 后直接结束循环的处理，改成在循环视频场景下可恢复重启的处理，避免 `PlaybackVideoAudioSource` 自己退出并把状态落回 `IDLE`。
      *   保持 KWS 控制器、电平表 UI、音源切换逻辑不变，只修复播放器音频源在视频循环时的生命周期问题。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [306] 2026-03-30 03:45:11 - 修复重播清空日志信号未触发 Compose 重组

**用户指令**：
> 重置后opne这个日志没有被清空
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复长按播放重播后“听声音”右侧日志仍未清空的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.syncAudioScreenMode、MainActivity.hardRestartPlayback、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `MainActivity` 中的 `kwsLogClearSignal` 从普通整数字段改为 Compose 可观察的 `mutableIntStateOf`。
      *   `hardRestartPlayback()` 中改为更新可观察 state，确保 `KwsPanelScreen(logClearSignal=...)` 收到新值后真正触发 `LaunchedEffect(logClearSignal)`。
      *   保持现有日志清空逻辑不变，只修复“信号变化没有触发 Compose 重组”的根因。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [305] 2026-03-30 03:40:38 - 重播时清空听声音日志并收紧行距

**用户指令**：
> 重新播放时(长按播放),清空右侧的日志
> 右侧日志中间不需要隔开那么多,开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在长按播放重播时清空“听声音”右侧日志，并收紧右侧日志项之间的间距，减少视觉留白。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.syncAudioScreenMode、MainActivity.hardRestartPlayback、KwsPanelScreen、LaunchedEffect(logClearSignal)、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `MainActivity` 新增 `kwsLogClearSignal`，在 `hardRestartPlayback()` 中递增，用于向声音界面发出“清空日志”信号。
      *   `KwsPanelScreen` 新增 `logClearSignal` 参数，并通过 `LaunchedEffect(logClearSignal)` 在收到信号时清空右侧日志并收起展开项。
      *   将右侧日志区整体行距从较宽的 `8dp/6dp` 收紧到 `2dp/3dp`，让多条输出更紧凑。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [304] 2026-03-30 03:36:04 - 修复电平表跟随当前音源路径

**用户指令**：
> 好了,现在开始修复电平表

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“听声音”界面的电平表路径，使其在监听关闭时也能跟随当前选择的音源，不再固定偷偷走麦克风。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.syncAudioScreenMode、KwsPanelScreen、stopMeter、startMeterIfAllowed、权限回调、audioInputModeLabel、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `MainActivity.syncAudioScreenMode()` 新增向 `KwsPanelScreen` 透传当前播放器音频源信息和播放器音频是否可用的状态。
      *   `KwsPanelScreen` 新增独立的 `PlaybackVideoAudioSource` 电平表输入，实现监听关闭时根据当前 `音源` 在“播放器/麦克风”之间选择正确的电平来源。
      *   监听开启时，电平表仍继续复用 `controller.setAudioFrameListener` 的实时音频帧；监听关闭时才启用独立的电平音源。
      *   切换 `音源` 后，如果当前只是开着电平表而没在监听，会自动停止旧电平链并按新音源重启。
      *   麦克风权限分支已收紧：仅在切到麦克风且确实需要时才申请权限，授权后会恢复到对应的电平或监听路径。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [303] 2026-03-30 03:30:22 - 接入 KWS 双音源切换并替换录音按钮

**用户指令**：
> 先提交一次git,另外kws里面也有些识别配置文件别忘了.
> 好的.KWS项目里面有个叫录音的按钮,移植时把那个录音换成"切换麦克风",激活后把音源从分离出的音源切换为麦克风.(这一步可以后面做,先保留原有代码尽量减少问题)
> ok
> 不需要录音按钮了,录音按钮切成刚才说的音源选择
> 那电平表就先不动,然后我们先改造两条链路,
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在当前主工程内把 KWS 输入改成“播放器/麦克风”可切换双音源，并将听声音界面的“录音”按钮替换为“音源”选择按钮；电平表路径暂不调整。
    *   修改文件：kws-sdk/src/main/java/com/example/roomxxx_vocie/audio/AudioInputMode.kt、kws-sdk/src/main/java/com/example/roomxxx_vocie/audio/SwitchableAudioSource.kt、app/src/main/java/com/example/roomxxx0102/logic/audio/PlaybackVideoAudioSource.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SwitchableAudioSource.start/stop/setMode、PlaybackVideoAudioSource.start/decodeLoop/emitFrames、MainActivity.syncAudioScreenMode、MainActivity.currentKwsAudioInputMode、MainActivity.setKwsAudioInputMode、MainActivity.resolvePlaybackAudioSourceSpec、KwsPanelScreen、audioInputModeLabel、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `kws-sdk` 中新增 `AudioInputMode` 与 `SwitchableAudioSource`，为 KWS 提供统一的“播放器/麦克风”可切换音源代理。
      *   在主工程新增 `PlaybackVideoAudioSource`，通过 `MediaExtractor + MediaCodec` 从当前视频源解出音频、转为 KWS 所需格式并按播放时间推进输出。
      *   `MainActivity` 中不再直接用默认麦克风构造 `KwsControllerImpl`，而是注入 `SwitchableAudioSource`；同时新增当前视频源解析方法，供播放器音源链使用。
      *   `KwsPanelScreen` 去掉录音按钮，替换为 `音源: 播放器/麦克风` 按钮；切到麦克风时仅在必要时申请权限，不再一进声音界面就默认请求麦克风权限。
      *   保持电平表逻辑暂不调整，先只完成 KWS 主识别链的双音源切换。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [302] 2026-03-30 03:07:35 - 收口听声音界面日志样式与播放器时间

**用户指令**：
> 这个界面里面。的标题的黑色几乎看不到。不知道是不是因为透明度的原因。
> 然后右边的那些。调试日志我们做一些修改，首先把时间修改为。播放器的时间,其次，里面的参数全部折叠起来。再其次。把。时间弄短一点儿，不需要。精确到。那么多，只要时间。分钟和秒钟就可以了。命中两个字也。去掉。这样的话，基本上就总。长度控制在一排了。点击可以展开它里面具体的括号的信息展示。每一次点击一条的时候，其他的被展开的会收起来。然后右半部分更宽一些左半部分把它收窄一些。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：提升 `KwsPanelScreen` 的文字可读性，并把右侧日志改成播放器时间驱动的可折叠一行摘要；同时进一步调整左右栏宽度。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：KwsPanelScreen、SliderLine、appendLog、formatPlayerTime、MainActivity.syncAudioScreenMode、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将滑条标签等原本易发黑的文本统一改成浅色，提高深色背景下的可读性。
      *   右侧日志从 `List<String>` 改为结构化 `OutputLogEntry`，每条包含播放器时间、摘要文本、详细信息。
      *   日志时间不再使用系统时间，而是通过 `MainActivity.syncAudioScreenMode()` 传入当前播放器时间；显示格式缩短为 `mm:ss`。
      *   去掉日志前缀“命中”，默认一行显示为“时间 + 指令 + 延迟”，详细括号信息折叠到展开态。
      *   支持点击单条日志展开详细信息；同一时刻只允许展开一条，新展开时旧条目自动收起。
      *   调整两栏宽度：右栏加宽，左栏收窄，更符合“左边设置、右边动态输出”的使用方式。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [301] 2026-03-30 02:51:33 - 重构听声音界面为左右双栏

**用户指令**：
> 看起来运行没有什么太大问题，我们把。新的调试界面，切成两部分。也就是从中间分开。左边是所有的设置菜单之类的，最右边是。输出，调试结果日志的那个菜单。就是里面有命中总延迟之类的那些东西。
> 不用考虑窄屏,开始吧
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把 `KwsPanelScreen` 从原来的单列滚动布局改成左右双栏，左侧专门承载设置与控制，右侧专门承载状态输出与命中日志。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：KwsPanelScreen、SliderLine、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `KwsPanelScreen` 根布局从单个 `Column` 改为左右分栏 `Row`。
      *   左栏新增标题“KWS 设置与控制”，继续保留原有滑条参数、监听开关、电平表、应用/清空/堵塞/录音/播放按钮，以及最近录音信息。
      *   中间加入竖向分隔线，强化左右两部分的视觉分区。
      *   右栏新增标题“输出与调试日志”，单独展示监听状态、最近状态、统计状态和命中日志列表；日志区域独立滚动。
      *   保持现有 KWS 控制逻辑和录音按钮行为不变，只重构界面结构。
      *   验证通过：`:app:compileDebugKotlin` 成功。

---

## [300] 2026-03-30 02:41:00 - 接入听声音模式与 KWS 调试界面

**用户指令**：
> 我运行了没发现问题,继续吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：继续完成小项目第二阶段接入，把 KWS 调试界面嵌入当前主工程的“看人/看手/听声音”三态切换中，并确保 AUDIO 模式下不显示视频画面但保留顶部进度条。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/res/layout/activity_main.xml、app/src/main/AndroidManifest.xml、app/src/main/java/com/example/roomxxx0102/ui/audio/KwsPanelScreen.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.cycleObserveMode、MainActivity.applyObserveMode、MainActivity.syncAudioScreenMode、MainActivity.updateHandOverlayMode、DetectionOverlayView.setAudioOnlyMode、DetectionOverlayView.onDraw、KwsPanelScreen、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `activity_main.xml` 中新增 `ComposeView(composeAudioScreen)`，作为“听声音”模式的界面承载层，位置位于视频层之上、overlay 之下。
      *   在 `MainActivity` 中新增 `ObserveMode(PERSON/HAND/AUDIO)` 三态切换，并把原来的“当前看人/当前看手”按钮扩展为“当前看人/当前看手/当前听声音”循环切换。
      *   新增 `syncAudioScreenMode()`，在 AUDIO 模式下通过 `ComposeView` 加载 `KwsPanelScreen(controller = KwsControllerImpl(...))`，退出 AUDIO 时释放该 Compose 内容。
      *   在 `DetectionOverlayView` 中新增 `audioOnlyMode`，AUDIO 模式下只保留顶部事件进度条与 banner，跳过视频帧、ROI、人物/手部等其它覆盖内容。
      *   新增 `ui/audio/KwsPanelScreen.kt`，迁入小项目的主要调试界面与参数持久化、电平表、录音/播放、日志等功能；当前仍保留原有录音按钮逻辑，后续再改成“切换麦克风”。
      *   将 `KwsPanelScreen` 根容器改为不透明深色背景，确保“听声音”模式下不会透出视频画面。
      *   在 `AndroidManifest.xml` 中补充 `RECORD_AUDIO` 权限，供当前阶段的麦克风输入方案使用。
      *   验证通过：`:app:compileDebugKotlin`、`:app:assembleDebug` 均成功。

---

## [299] 2026-03-30 02:22:38 - 并入 KWS 核心模块与模型资源

**用户指令**：
> 好的.KWS项目里面有个叫录音的按钮,移植时把那个录音换成"切换麦克风",激活后把音源从分离出的音源切换为麦克风.(这一步可以后面做,先保留原有代码尽量减少问题)
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：先完成小项目第一阶段第 1 步，把 `RoomXXXvocie` 的 `kws-sdk`、Sherpa AAR、模型与关键词资源整体并入当前工程，为后续“听声音”界面接入做准备；暂不改录音按钮逻辑。
    *   修改文件：settings.gradle.kts、app/build.gradle.kts、kws-sdk/*（新增模块源码、assets、libs、Gradle 文件）、kws-sdk/build.gradle.kts、codexHistory.md、dialogueHistory.md
    *   涉及方法：Gradle include(:kws-sdk)、app dependencies、KwsControllerImpl.start、AudioRecordSource.start、AssetFileCopier.copyAssetDirToFiles、AssetFileCopier.copyAssetFileToFiles、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `RoomXXXvocie/kws-sdk` 整体复制到当前工程，连同 `libs/sherpa-onnx-1.12.20.aar`、`src/main/assets/kws_model`、`src/main/assets/kws_keywords` 一并引入。
      *   在当前工程 `settings.gradle.kts` 中注册 `:kws-sdk` 模块，并在 `app/build.gradle.kts` 中加入 `implementation(project(":kws-sdk"))` 与 Sherpa AAR 运行时依赖。
      *   将并入的 `kws-sdk/build.gradle.kts` 调整为适配当前 AGP 9/Kotlin DSL：库模块 `minSdk` 对齐到 26，移除无效的 `targetSdk`，改用 `androidResources.noCompress` 和 `kotlin.compilerOptions.jvmTarget`。
      *   验证通过：`:kws-sdk:compileDebugKotlin`、`:app:compileDebugKotlin` 均成功。

---

## [298] 2026-03-30 02:09:02 - 将长按 +1 帧改为临时正常播放

**用户指令**：
> 把长按+1帧换成正常播放(松手仍然暂停)
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 STILL 模式下 `+1帧` 的长按行为从连续步进改为临时正常播放，并在松手后立即回到 `STILL`，同时保留单击 `+1帧` 仍是原有的单步 35ms 行为。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.createSeekHoldTouchListener、MainActivity.startSeekHold、MainActivity.stopSeekHold、MainActivity.seekHoldRunnable.run、MainActivity.startForwardHoldPreviewIfNeeded、MainActivity.stopForwardHoldPreviewIfNeeded、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   仅对 `direction=1` 且 `PlayState.STILL` 的触摸链改为自行接管：短按时手动执行一次 `+1帧`，长按超过 `500ms` 后不再连续步进，而是启动临时正常播放。
      *   新增 `forwardHoldPreviewPlaying` 状态；长按触发后调用 `videoFeeder.clearStepSeekTransientState()`、`setStillMode(false)`、`resume()` 进入预览播放。
      *   在 `ACTION_UP/ACTION_CANCEL` 统一调用 `stopSeekHold()`；若此时处于预览播放，则执行 `pause()` + `setStillMode(true)`，保证松手后回到 `STILL`。
      *   `-1帧` 长按逻辑、普通 `±5s`、识别链和 overlay 未改。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [297] 2026-03-30 00:00:00 - 统一 STILL 步进排查日志到单一 tag

**用户指令**：
> 好吧。最好只让我搜一个东西然后我一次性把那个东西全部贴给你。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 STILL 步进相关的调试日志统一收口到一个 tag，避免用户需要同时搜 `RoomStepDiag`、`RoomRenderDiag`、`RoomSeekHold` 多个来源。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/FrameStepController.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：FrameStepController.Companion TAG、VideoFeeder.analyzeRunnable.run、VideoFeeder 中 PlayerEventListener.onSeekComplete、VideoFeeder.seekForwardFrame、VideoFeeder.seekBackwardFrame、VideoFeeder.forcePausedFrameRefresh、MainActivity.createSeekHoldTouchListener、MainActivity.startSeekHold、MainActivity.stopSeekHold、MainActivity.seekHoldRunnable.run、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `FrameStepController` 的主 tag 从 `RoomStepDiag` 改为 `RoomStepFullDiag`。
      *   将 `VideoFeeder` 中原 `RoomRenderDiag / RoomStepDiag` 相关日志统一改为 `RoomStepFullDiag`。
      *   将 `MainActivity` 中原 `RoomSeekHold` 相关日志统一改为 `RoomStepFullDiag`。
      *   同时把 `forcePausedFrameRefresh()` 的 begin/end 也纳入 `RoomStepFullDiag`，保证步进请求、seek、渲染观测、长按调度、刷新脉冲都能一次性搜全。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [296] 2026-03-30 00:00:00 - 增加 STILL 步进的渲染链诊断日志

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：定位 STILL 连续步进时“时间轴持续前进，但画面偶发冻结几秒后再猛跳”的渲染链问题，区分到底是 seek 后 `textureView.bitmap` 内容没有变化，还是 bitmap 已变化但显示层没及时呈现。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoFeeder 中 PlayerEventListener.onSeekComplete、VideoFeeder.analyzeRunnable.run、VideoFeeder.seekForwardFrame、VideoFeeder.seekBackwardFrame、VideoFeeder.stop、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `observedFrameSeq + lastObservedFrameDigest`，基于每次抓到的 `textureView.bitmap` 摘要变化近似记录“可见画面摘要序号”。
      *   新增 `RoomRenderDiag` 日志，串联 `stepRequest -> seekComplete -> bitmapObserved` 三个节点，打印当前 `pos / frameSeq / digest / changed`。
      *   保留原有 `RoomStepDiag`，并在 `observePending` 中追加 `frameSeq`，方便和 `RoomRenderDiag` 对照。
      *   本轮只加诊断，不改步进行为、不改识别链、不改 overlay。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [295] 2026-03-30 00:00:00 - 增加长按步进调度链日志

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：给长按 `+1/-1` 的调度链补最小日志，定位“卡住时日志也不再打印”究竟是触摸事件链中断、`stopSeekHold()` 被意外触发，还是 `seekHoldRunnable` 没再继续调度。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.createSeekHoldTouchListener、MainActivity.startSeekHold、MainActivity.stopSeekHold、MainActivity.seekHoldRunnable.run、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `RoomSeekHold` 日志，覆盖 `ACTION_DOWN/UP/CANCEL`、`start/stop`、`tick/tickAbort/tickReschedule` 等关键调度节点。
      *   本轮只加观测，不改变长按步进实际行为。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [294] 2026-03-30 00:00:00 - 去掉 STILL 静止标准帧跳过的高频刷屏日志

**用户指令**：
> 基本正常了,不过偶尔还是卡住,很奇怪的是都没按了,还在不停刷一个东西.omxxx0102              I  stillSkip pendingStep=false temporalAdvanced=false pos=32424
> ...
> 先把stillSkip 尽量干掉,这个干啥的
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：去掉 STILL 模式下 `skipUnchangedStillFrame` 触发时每 100ms 刷一次的 `stillSkip ...` 调试日志，避免干扰排查与使用体验，同时保留静止标准帧跳过行为本身不变。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoFeeder.analyzeRunnable.run、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   删除 `skipUnchangedStillFrame` 分支中的 `RoomStepDiag stillSkip ...` 高频日志输出。
      *   仍保留 `observePending ...` 等与 pending step 直接相关的必要日志，方便继续观察偶发卡住问题。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [293] 2026-03-30 00:00:00 - 修复 STILL 步进被静止标准帧跳过逻辑卡死

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 STILL 模式下第二次开始步进后长期保持 `BUSY`、看起来“后面就没动静”的问题。根因是 pending step 存在时，分析循环仍可能被 `skipUnchangedStillFrame` 提前返回，导致 `onFrameObserved(...)` 永远不执行。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoFeeder.analyzeRunnable.run、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `analyzeRunnable` 中引入 `hasPendingStep` 判断；只要当前仍有未完成的步进，就禁止走 `skipUnchangedStillFrame` 这条提前返回。
      *   新增两条最小 `RoomStepDiag` 日志：`stillSkip ...` 和 `observePending ...`，用于确认 STILL 步进时是否真的走到了 `onFrameObserved(...)`。
      *   本轮未修改步进时间、阈值、识别链、overlay 或其它播放器逻辑。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [292] 2026-03-30 00:00:00 - 将 STILL 步进退回为固定 35ms 直接步进

**用户指令**：
> 我忘了交代。我们的整个画面几乎是完全不动的只有人所在的地方会栋一点,这是固定摄像机.你可以试试直接按三十帧来，稍微多给一点点毫秒数。 然后其他不用管.
> 如果直接以。 35毫秒。然后不要其他任何东西。试一试。
> 我的意思是不是调阈值是？彻底不要那个东西了反正我们就正常往前走试试。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 STILL 模式下的 `+1/-1` 退回成最朴素的固定 `35ms` 直接步进试验版本，先验证固定机位视频里“正常往前走”是否比可见差异判定更符合体感。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/FrameStepController.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：FrameStepController.PendingStep、FrameStepController.onSeekComplete、FrameStepController.onFrameObserved、FrameStepController.startStep、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   移除阈值比较、多候选尝试和可见差异判定，`+1/-1` 改为固定以 `35ms` 为步长直接 seek。
      *   仍保留 pending step 与短 settle window，用于在 seek 完成并等待短暂稳定后确认实际落点并刷新 anchor。
      *   保留 BUSY 防重入和 `RoomStepDiag` 日志，但日志语义改为固定步长单次步进，不再输出候选 diff/threshold。
      *   本轮未修改识别链、overlay、普通播放/暂停/±5s 等其它逻辑。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [291] 2026-03-30 00:00:00 - 修复长按 +1/-1 时步进重入覆盖问题

**用户指令**：
> 五秒暂停的问题解决了，但是按住+1帧很久都没动静,必须的话可以加上log
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 STILL 模式下长按 `+1/-1` 时，由于新的步进请求不断覆盖尚未完成的 pending step，导致体感上“按住很久都没动静”的问题，并补齐步进调试日志。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/FrameStepController.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：FrameStepController.startStep、FrameStepController.onFrameObserved、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `FrameStepController.startStep(...)` 中增加不可重入保护：如果当前已有未完成的 `pendingStep`，新的 `stepForward/stepBackward` 不再覆盖旧任务，而是返回 `BUSY` 结果。
      *   新增 `RoomStepDiag` 关键日志：记录 `stepStart`、`stepRejectedBusy`、每次候选评估的 `candidateCheck(diff/threshold)`，便于判断长按时是忙碌重入还是差异阈值未通过。
      *   本轮不调整识别链、不调整 overlay、不改普通 seek，只定位并修复步进控制器的重入问题。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [290] 2026-03-30 00:00:00 - 修复 ±5 秒误暂停并增强可见步进体感

**用户指令**：
> 1.点击+-5秒会自动暂停播放 2.+-1帧仍旧几乎没有变化,偶尔动一点点
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复播放态点击 `±5s` 后被误打成暂停的问题，并增强 STILL 模式下 `±1帧` 的可见变化体感，避免只出现轻微变化就被判定为步进成功。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/FrameStepController.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：FrameStepController.Companion 中差异阈值与候选步长配置、VideoFeeder 中 PlayerEventListener.onSeekComplete、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将可见步进的差异阈值从 `0.035` 提高到 `0.06`，并把候选倍数调整为 `1.0x / 2.0x / 3.0x / 4.5x * baseStepMs`，让 `±1帧` 更偏向“明显变化”而不是“轻微变化也算成功”。
      *   移除播放态普通 seek 完成后的 `forcePausedFrameRefresh(play(); pause())` 触发条件，保留该刷新只服务 STILL 模式下存在 pending step 的可见步进流程，避免 `±5s` 把播放态误切成暂停。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [289] 2026-03-29 00:00:00 - 修复 STILL 下连续步进拿旧帧的问题

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 STILL 模式下第一下步进有效、后续连续步进又拿回旧帧，导致用户感觉“第一下有用，后面就再也没用了”的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/FrameStepController.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：FrameStepController.hasPendingStep、VideoFeeder 中 PlayerEventListener.onSeekComplete、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `FrameStepController` 新增 `hasPendingStep()`，用于暴露当前是否仍处于步进候选 seek 流程中。
      *   `VideoFeeder.onSeekComplete()` 中增加 STILL 模式下的特殊处理：如果当前仍有待判定的步进候选，就主动执行一次 `forcePausedFrameRefresh(...)`，确保 seek 后目标画面真正刷新出来。
      *   这次不调整候选步长、不调整阈值，只修 STILL 步进候选 seek 完成后的目标画面刷新时机。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [288] 2026-03-29 00:00:00 - 重构 STILL 下的可见步进/步退逻辑

**用户指令**：
> 现在只做“步进 / 步退”新方案，不要再碰识别链。
> 目标是：每点一次，画面一定明显变化；宁愿跨得稍微多一点，也不要点了没反应。
> 只改步进 / 步退逻辑，不要修改 pose / hand 分析逻辑、overlay 映射逻辑、KWS 相关代码、普通播放 / 暂停 / seek / 切视频逻辑。
> 并要求：
> 1. anchorTimeMs / anchorSignature 明确重置时机
> 2. seek 完成后增加短稳定窗口
> 3. 候选时间点边界保护和去重
> 4. 成功后记录实际确认落点
> 5. 画面摘要比较使用阈值
> 6. stepForward/stepBackward 返回明确结果

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 STILL 模式下的 `+1/-1` 从“固定时间步长 + 固定 +10ms 补偿”重构为“基于锚点画面和阈值摘要比较的可见步进/步退”，目标是每次点击都让画面明显变化。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/utils/AppLog.kt、app/src/main/java/com/example/roomxxx0102/logic/video/FrameSignatureUtils.kt、app/src/main/java/com/example/roomxxx0102/logic/video/FrameStepController.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：AppLog.d/i/w/e、FrameSignatureUtils.create/differenceScore、FrameStepController.resetAnchor/onSeekComplete/onFrameObserved/stepForward/stepBackward、VideoFeeder.analyzeRunnable.run/setStillMode/seekForward/seekBackward/seekForwardFrame/seekBackwardFrame/seekToMs/clearStepSeekTransientState/setupMediaPlayer/stop、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `FrameStepController`，内部维护 `anchorTimeMs + anchorSignature`，只负责 STILL 模式下的前进到下一张明显不同画面 / 后退到上一张明显不同画面。
      *   新增 `FrameSignatureUtils`，使用轻量灰度采样摘要并按阈值比较，不再依赖“摘要完全相同/不同”。
      *   `+1/-1` 改成按 `1.0x / 1.5x / 2.0x / 3.0x * baseStepMs` 的候选时间点尝试，带边界保护与去重；成功后记录实际确认落点，失败则回 anchor 并记录最后一次候选差异值。
      *   seek 完成不再立即判定，而是通过 `onSeekComplete + 短稳定窗口 + 下一次帧采样` 再决定是否真的出现明显变化。
      *   在切视频、普通 seek、±5s、停止、首次进入 STILL、首次进入视频模式等路径显式重置 anchor。
      *   删除旧的固定 `+10ms` 补偿链和相关状态字段，保留普通播放 / 暂停 / seek / 切视频 / 识别链不变。
      *   本轮新增日志统一通过 `AppLog` 封装，不再在新控制器里直接散落写 `Log.d()`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [287] 2026-03-29 00:00:00 - 修复 pose 回调中的第二处 Exo wrong-thread 访问

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修掉 pose 分析回调里残留的第二处后台线程播放器访问，避免 `RoiLogAggregator.updatePresenceDebug` 再次触发 ExoPlayer 的 `wrong thread` 异常。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.onCreate 中的 YoloPoseAnalyzer 回调、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   将 `RoiLogAggregator.updatePresenceDebug(... posMs = videoFeeder?.getCurrentPositionMs())` 改为读取 `videoFeeder?.peekLastAnalysisPositionMs()`。
      *   这样 pose 回调在后台线程里不再直接访问 ExoPlayer，本轮已把该回调中的两处 wrong-thread 读取都替换为缓存值。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [286] 2026-03-29 00:00:00 - 修复 pose 分析回调跨线程访问 ExoPlayer

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复 Exo 替换后，pose 分析回调在后台线程里直接读取播放器当前位置，触发 “Player is accessed on the wrong thread” 并导致 overlay 背景帧、pose 和地图覆盖层一起失效的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoFeeder.analyzeRunnable.run、VideoFeeder.peekLastAnalysisPositionMs、VideoFeeder.stop、MainActivity.onCreate 中的 YoloPoseAnalyzer 回调、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `VideoFeeder` 在主线程抓图分析循环里缓存最新的播放位置 `lastAnalysisPositionMs`。
      *   新增线程安全只读入口 `peekLastAnalysisPositionMs()`，供后台线程读取缓存值，而不直接访问 ExoPlayer。
      *   `MainActivity` 的 pose 回调里，Presence 时间戳来源从 `videoFeeder.getCurrentPositionMs()` 改为 `videoFeeder.peekLastAnalysisPositionMs()`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [285] 2026-03-29 00:00:00 - 补充分析回灌链诊断日志

**用户指令**：
> 现在地图绘制到了画面都没有覆盖到的地方.继续吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在已确认 Exo 渲染和 `TextureView.bitmap` 抓图正常后，继续沿“分析任务 -> analyzer -> overlay UI 回灌”这条链补充最小日志，定位为什么 `DetectionOverlayView.currentFrame` 一直为空。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloAnalyzer.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoFeeder.submitInferenceTask、YoloAnalyzer.detectOnBitmap、MainActivity.onCreate 中的 YoloPoseAnalyzer 回调、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `VideoFeeder.submitInferenceTask` 新增 `RoomInferenceDiag` 日志，输出当前是否走 pose 模式、bitmap 尺寸、ROI、任务开始/结束/异常。
      *   `YoloAnalyzer.detectOnBitmap` 新增结果日志，确认非 pose 模式下是否把背景 bitmap 回灌到 overlay。
      *   `MainActivity` 的 pose 回调新增 `RoomPoseUiDiag` 日志，确认 pose 结果和 bitmap 是否真正传入 `overlayView.updatePoseData`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [284] 2026-03-29 00:00:00 - 补充 Exo 抓图与 overlay 对齐诊断日志

**用户指令**：
> 先别只加通用日志，先优先确认这 3 件事：
> 1. Exo 当前是否明确渲染到原来的同一个 TextureView
> 2. 如果用了 PlayerView，确认 surface_type
> 3. 检查 adjustAspectRatio()/TextureView transform 是否仍在生效，以及是否和 Exo/PlayerView 的缩放逻辑重复叠加
> 然后再补最小日志：
> - textureView.width/height
> - textureView.bitmap 是否为 null
> - bitmap.width/height
> - 连续几帧 bitmap 的像素摘要是否变化
> - overlay 的 dstRect 和 textureView 实际显示区域
> 我优先想先知道“Exo 是否还真的在往原来的 TextureView 渲染”
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不先改行为的前提下，补充 Exo 替换后的最小诊断日志，确认 `TextureView.bitmap` 是否稳定拿到有效帧，以及 overlay 的 `dstRect` 是否与视频显示区域一致。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoFeeder.analyzeRunnable.run、VideoFeeder.maybeLogBitmapDiag、VideoFeeder.stop、DetectionOverlayView.onDraw、DetectionOverlayView.maybeLogOverlayBitmapDiag、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   先确认当前 Exo 仍然通过 `setVideoTextureView(textureView)` 渲染到原来的 `TextureView`，没有混入 `PlayerView` 或 `SurfaceView`。
      *   在 `VideoFeeder` 中新增 `RoomBitmapDiag` 日志，输出 `textureView` 的 `x/y/width/height`、`bitmap` 是否为空、位图尺寸、连续帧摘要是否变化以及当前位置。
      *   在 `DetectionOverlayView` 中新增 `RoomOverlayDiag` 日志，输出 overlay 视图尺寸、当前帧位图尺寸以及计算后的 `dstRect`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [283] 2026-03-29 00:00:00 - 第一阶段切换到 ExoPlayer 播放器 facade

**用户指令**：
> 第一阶段只做播放器替换：MediaPlayer -> Media3/ExoPlayer
> 先抽 VideoPlayerFacade，再提供 ExoVideoPlayer 实现
> 不要接音频旁路
> 不要改 TextureView.bitmap 抓图分析链
> 不要重写业务状态机，只做 ExoPlayer 状态到现有 PLAYING/STILL/PAUSED 语义的映射
> 保证 MainActivity 现有交互、进度条、marker、pause/seek 行为尽量不变

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：完成第一阶段播放器替换，在不接音频旁路、不改抓图分析链和业务状态机的前提下，把底层播放实现从 `MediaPlayer` 切到 `Media3/ExoPlayer`。
    *   修改文件：gradle/libs.versions.toml、app/build.gradle.kts、app/src/main/java/com/example/roomxxx0102/logic/video/PlayerEventListener.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoPlayerFacade.kt、app/src/main/java/com/example/roomxxx0102/logic/video/ExoVideoPlayer.kt、app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoPlayerFacade.attachTextureView/setListener/prepare/play/pause/stop/release/seekTo/isPlaying/getCurrentPositionMs/getDurationMs、ExoVideoPlayer 对上述接口的实现、VideoFeeder.setupMediaPlayer/pause/resume/seekToMs/seekByMs/stop、MainActivity.jumpToNextMarkedEvent/jumpToNextDeviceHitEvent、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `VideoPlayerFacade`、`PlayerEventListener` 和 `ExoVideoPlayer`，把 Media3/ExoPlayer 封进独立播放抽象层。
      *   为项目增加 `androidx.media3:media3-exoplayer` 依赖，但本阶段不接任何音频旁路、不新增 PCM 总线。
      *   `VideoFeeder` 内部从直接持有 `MediaPlayer` 改为持有 `VideoPlayerFacade`，对外保留原有 `start/pause/resume/seek/position/duration` 调用方式，继续使用 `TextureView.bitmap` 做抓图分析。
      *   `MainActivity` 去掉对 `MediaPlayer.SEEK_CLOSEST` 的直接依赖，事件跳转继续通过 `VideoFeeder.seekToMs(...)` 完成。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [282] 2026-03-28 12:43:30 - 给 pose 左右手腕增加红色描边

**用户指令**：
> 把手腕(就是我们现在判定ROI这个pose点吧?)都绘制成红色描边.两个手都是.
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在现有 pose 关键点绘制上单独高亮左右手腕，便于直观看到当前手部 ROI 相关的 pose 手腕位置。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/drawers/PoseDrawer.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：PoseDrawer.draw、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增红色描边画笔 `wristOutlinePaint`。
      *   在通用关键点绘制后，额外对 `9/10` 两个手腕索引单独绘制红色描边圆。
      *   不修改其它关键点颜色、不修改 ROI 选手逻辑，只增加手腕视觉高亮。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [281] 2026-03-28 11:56:20 - 设备设置切换为纯净编辑显示

**用户指令**：
> 进入设备设置,和添加设备的时候,房间图和其他东西都不应该显示.看看怎么处理,不要出错
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让“设备设置 / 添加设备”时只显示设备编辑本身，不再混入客厅图、子房间图、ROI、事件条和其它 overlay 元素。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.setDeviceEditCleanMode/onDraw、LivingRoomEditorView.onDraw、MainActivity.applyModeSelection/toggleEditModeUI、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `DetectionOverlayView` 新增设备编辑纯净显示开关，设备编辑时只保留视频底图，提前跳过 ROI、事件条、手/人可视化和其它调试绘制。
      *   `LivingRoomEditorView` 的 `DEVICE` 模式不再绘制客厅轮廓和顶点，只绘制设备本身与草稿设备。
      *   `MainActivity` 在切到设备模式和退出设备模式时同步切换这套纯净显示，避免误伤主房间/次房间编辑。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [280] 2026-03-28 11:46:05 - 修复新视频无配置时重启仍恢复旧配置

**用户指令**：
> 一个新视频,没有配置过任何,重启后还是给我加载成之前一个视频的配置文件了
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复切到一个从未配置的新视频后，虽然当前显示“无房间配置文件”，但重启仍恢复上一个视频真实配置文件的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SettingsHomeFragment.loadDefaultConfigForSelectedVideo、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在“当前视频默认配置文件不存在”的分支里，除了切到临时空配置，还会同步清空 `AppSettings.activeRoomConfigPath`。
      *   同时把 `AppSettings.isNoRoomConfigSelected` 写成 `true`，让冷启动时继续保持“无配置”状态。
      *   这样新视频无匹配配置时，当前运行态和重启后的持久状态语义保持一致，不会再恢复旧视频配置。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [279] 2026-03-28 11:39:44 - 拆分编辑态与设备选择态层级恢复

**用户指令**：
> 只有在设备选择时才需要把视频提到最上面吧,好好构思一下,怎么改合理
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把“设备选择态临时置顶”和“设置房间编辑态层级恢复”拆开，避免 overlay 的临时置顶逻辑持续污染编辑菜单层级。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.toggleEditModeUI、MainActivity.syncDeviceHitSelectionUi、MainActivity.applyUiLayerMode、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `UiLayerMode`，明确区分 `NORMAL / EDITING / DEVICE_SELECTION` 三种层级模式。
      *   `toggleEditModeUI(true)` 进入编辑模式时，显式恢复编辑态层级，不再依赖设备选择态退出时的通用兜底。
      *   设备纯选择态只在等待点击设备期间临时把 `overlayView` 提到最上层。
      *   退出设备选择态后，会根据当前是否仍在编辑模式，回到 `EDITING` 或 `NORMAL` 层级，而不是继续沿用选择态置顶结果。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [278] 2026-03-28 04:05:47 - 统一顶部进度条按看手看人切换事件源

**用户指令**：
> 还有一个问题就是当切换看手和看人的。见面的时候。最顶上进度条里面的事件记录处理的不一致。准确说就是看手的时候。如果关闭调试面板，那么事件记录还是用的。进出房间的
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复顶部进度条事件源在“当前看手但调试面板关闭”时仍回退成进出房间事件的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.refreshEventMarkerUi、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   顶部进度条的数据源切换改为只看 `isHandOverlayPressed`。
      *   `当前看手` 时，顶部进度条始终显示设备事件。
      *   `当前看人` 时，顶部进度条始终显示进出房间事件。
      *   底部按钮栏仍然按调试面板开关控制，不受这次修改影响。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [277] 2026-03-28 04:01:01 - 修复设备选择完成后菜单被 overlay 压层

**用户指令**：
> 这一次点击没有问题了，不过点击之后。整个菜单项是被盖在下面了。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复设备选择完成或取消后，底部菜单和其它控制层被 overlay 压在下面的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.syncDeviceHitSelectionUi/bringSelectionLayerToFront/restoreUiLayerOrder、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   把“设备选择态置顶 overlay”和“退出后恢复菜单层级”收进统一链路。
      *   进入选择态时只保留 overlay 在最上层。
      *   退出选择态后，会按当前可见状态把普通控制栏、事件栏、编辑栏、计数器、雷达层重新 `bringToFront()`，避免被 overlay 继续盖住。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [276] 2026-03-28 03:57:29 - 将设备纯选择态点击接管上移到 Activity

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在确认触摸始终没有进入 `DetectionOverlayView` 后，把设备纯选择态的点击接管上移到 `MainActivity.dispatchTouchEvent()`，彻底绕过当前 View 分发链。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.resolveSelectionDeviceAt、MainActivity.dispatchTouchEvent、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `DetectionOverlayView` 新增公开的设备点击命中查询入口，供 Activity 上层直接调用。
      *   `MainActivity` 在 `isAwaitingDeviceHitSelection=true` 时优先拦截 `dispatchTouchEvent()`。
      *   点击命中设备则直接确认；未命中则直接取消，不再依赖 `overlayView.onTouchEvent()`。
      *   新增 `activity dispatch ...` 日志，便于确认上层点击是否已经接管成功。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [275] 2026-03-28 03:53:37 - 进入设备纯选择态时强制 overlay 置顶并压下遮挡层

**用户指令**：
> 建议你确认好之后再下手。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：基于日志确认“触摸根本没进 DetectionOverlayView”后，修复设备纯选择态下被其它全屏层遮挡的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.syncDeviceHitSelectionUi、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   进入设备纯选择态时，会先记录 `editorView` 与雷达层原始可见性，再显式把它们压成 `GONE`。
      *   同时强制 `overlayView` 保持 `VISIBLE` 并执行 `bringToFront()`，避免触摸继续被全屏遮挡层吞掉。
      *   退出纯选择态时会恢复先前保存的可见性状态。
      *   同步补充了进入/退出选择态时各层 `visibility` 的日志，方便继续确认层级是否正确。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [274] 2026-03-28 03:46:52 - 补设备纯选择态点击链调试日志

**用户指令**：
> 在设定任意点击任何地方都没有任何响应。没办法的话就加个日志
> 开始,然后告诉我搜什么看日志

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：为设备纯选择态补充点击链日志，确认是否进入等待选择、overlay 是否收到触摸、是否命中设备以及是否触发空白取消。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.setDeviceSelectionMode/onTouchEvent/findTappedDevice、MainActivity.syncDeviceHitSelectionUi/armDeviceHitSelection/onDeviceTappedForMarker/cancelDeviceHitSelection、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增统一日志 tag：`DeviceHitSelect`。
      *   overlay 侧会记录选择态启停、收到的触摸坐标、是否命中设备 polygon、是否按空白触发取消。
      *   MainActivity 侧会记录等待选择态是否建立、冻结的时间点/帧号、设备确认回调是否触发、空白取消是否触发。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [273] 2026-03-28 03:42:52 - 修复设备纯选择态点击未被 overlay 接管

**用户指令**：
> 我点击了显示后的设备之后并没有任何的响应。
> 关键是现在点击空白处也没有取消。
> 命中范围先不改，先改我们现在真正的问题。开始吧。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复设备纯选择态下点击设备和点击空白都无响应的问题，先只处理触摸接管，不调整设备命中范围。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.setDeviceSelectionMode/onTouchEvent/performClick、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   设备选择态激活时，`DetectionOverlayView` 会显式切到 `clickable/focusable`，确保自身稳定接管触摸。
      *   设备选择态的点击处理被提升为 `onTouchEvent` 顶层优先分支：命中设备走选中回调，未命中则走取消回调，不再落到 `super.onTouchEvent()`。
      *   设备选择态下 `ACTION_DOWN / MOVE / UP / CANCEL` 都由 overlay 自己消费，避免事件被其它视图或默认链路吞掉。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [272] 2026-03-28 03:36:55 - 等待选择设备时切换为纯选择态并支持空白取消

**用户指令**：
> 等待，点击设备的时候应该。其他菜单，包括整个界面的所有显示的东西全部都隐藏而且不影响我的点击设备的。
> 是的，你就弹出一个。很小的提示说请点击设备 点击空白处时取消。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把“等待点击设备”改造成纯选择态，进入后隐藏界面其余显示，只保留设备框和小提示，并支持点空白取消。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.setOnDeviceSelectionCancelListener/setDeviceSelectionMode/onDraw/onTouchEvent/drawDevices/drawDeviceSelectionPrompt、MainActivity.setupButtons/syncDeviceHitSelectionUi/armDeviceHitSelection/onDeviceTappedForMarker/cancelDeviceHitSelection/updateHandOverlayMode/refreshDeviceHitMarkerControls、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   overlay 新增设备纯选择态，激活后直接短路为“只绘制设备框与核心点 + 顶部小提示”，不再显示手点、ROI、时间线、调试面板和其它 overlay 元素。
      *   进入等待选择设备时，普通控制栏和底部事件栏都会隐藏，不再干扰点选设备。
      *   顶部提示改为小提示文案：`请点击设备，点击空白处取消`。
      *   点中设备后正常记录；点空白处会直接取消本次选择并恢复原界面。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [271] 2026-03-28 03:29:33 - 修复看手模式下设备标注菜单被播放态隐藏

**用户指令**：
> 我没有看到记录正确命中事件""这几个字,甚至这个菜单都没出现
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“当前看手 + 调试面板开启”时，设备命中标注菜单仍被“播放中”状态隐藏，导致用户看不到“记录正确命中事件”按钮的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.refreshDeviceHitMarkerControls、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   看手模式下的设备标注菜单显示条件，改为 `视频模式 + 调试面板开启 + 当前看手`。
      *   不再沿用旧的人体/房间事件标注链里“必须暂停或静止才显示”的限制。
      *   看人模式仍保持原来的暂停后显示逻辑不变。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [270] 2026-03-28 03:24:55 - 看手模式调试面板切换为设备命中标注

**用户指令**：
> 接下来我们要做的修改是重构调试面板，当当前看手的时候调试面板。要和之前的完全不同。我们之前是调试的是关于人的一些东西，对吧？我们全部都不要了。我们现在的调试面板开启之后。要两个按钮，一是记录。正确命中事件。第二个是跳转到下一个事件。当点击记录。的时候。提示用户点击。选择设备。然后我们把这个时间点记录下来。在这里最重要的是时间和当前命中的设备应该是谁。同样，我们也有和。进出子房间。一样的删除逻辑。如果你对那个逻辑已经不太熟悉了，可以去看一下。
> 好的,注意实现方式,尽量解耦和规范.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：把“当前看手”模式下的调试面板从原来的房间/人体调试信息切换成“设备命中事件标注”流程，并与原进出子房间事件标注链解耦。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/validation/DeviceHitMarkerManager.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DeviceHitMarkerManager.init/bindVideo/addEvent/findEventsNearFrame/removeEventsNearFrame/findNextEventAfter、DetectionOverlayView.setDeviceHitMarkerState/clearDeviceHitMarkerState/setOnDeviceTapListener/setDebugPanelOverride/drawEventMarkerBar/onTouchEvent/drawDebugPanel、MainActivity.setupButtons/refreshEventMarkerUi/refreshEventMarkerOverlay/refreshDeviceHitMarkerOverlay/refreshEventMarkerControls/refreshDeviceHitMarkerControls/jumpToNextDeviceHitEvent/confirmDeleteCurrentDeviceHitEvents/bindEventMarkersToVideo/bindDeviceHitMarkersToVideo/armDeviceHitSelection/onDeviceTappedForMarker/refreshDebugPanelMode/updateHandOverlayMode、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增独立的 `DeviceHitMarkerManager`，专门按视频保存“时间点 + 设备 ID/名称”的设备命中标注，不复用 `ENTER/EXIT` 事件结构。
      *   看手模式下的底部工具栏改成“记录正确命中事件 + 跳转到下一个事件”，删除按钮只在当前帧附近已有设备命中标注时显示。
      *   “记录正确命中事件”改成两段式：先冻结点击按钮时的时间点和帧号，再等待用户点击 overlay 上的设备四边形完成标注，避免时间点录偏。
      *   `DetectionOverlayView` 新增设备点击命中和设备命中时间线绘制，同时支持在看手模式下用独立文案覆盖右侧调试面板，不再显示原 ROI/人体调试信息。
      *   视频切换时房间事件标注与设备命中标注会一起按视频绑定；编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [269] 2026-03-28 03:07:13 - 手部关键点缩小并切换为当前看手状态按钮

**用户指令**：
> 我们在手上绘制的点的直径是多少？
> 好像还有些描边什么的?直接改成2f半径,不要描边
> 同时把按住看手变 成当前看手/当前看人,开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将手部关键点绘制缩小为 2f 半径并去掉描边，同时把“按住看手”交互改为“当前看手 / 当前看人”的点击切换状态。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/res/layout/activity_main.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.drawHands、MainActivity.setupButtons/updateHandOverlayMode、btnHandOverlay 布局定义、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   手部关键点改为仅绘制实心点，半径从 10f 调整为 2f，移除白色描边层。
      *   `btnHandOverlay` 不再使用 `ACTION_DOWN / ACTION_UP` 的按住逻辑，改为点击切换。
      *   切到“当前看手”时启动手点采样与指向识别；切回“当前看人”时取消当前 session 并清空实时指向线。
      *   按钮文案改为状态文案：默认 `当前看人`，激活后显示 `当前看手`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [268] 2026-03-28 02:58:55 - 接入设备窗口级指向判定器

**用户指令**：
> 现在开始下一步：在现有“设备 = hotspot + polygon”结构已经改好的前提下，接入完整的窗口级指向设备判定算法。
> ...
> 按这个约束开始改。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在不修改射线生成和设备编辑链路的前提下，新增并接入“窗口级设备指向判定器”，以 `hotspot + polygon` 为输入完成单帧评分、窗口累计和最终高/低/未定输出。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/pointing/TriggeredPointingResolver.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingModels.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingGeometry.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingScorer.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DevicePointingWindowJudge.kt、app/src/main/java/com/example/roomxxx0102/logic/pointing/DeviceTriggeredPointingResolver.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：PointingDiagnostics/PointingConfidenceStatus、DevicePointingGeometry 全部新增方法、DevicePointingScorer.prepareTargets/scoreFrame/logFrameScores、DevicePointingWindowJudge.judge/logWindowResult、DeviceTriggeredPointingResolver.startSession/submitFrame/finalizeDecision、MainActivity.buildPointingDeviceTargets/startTriggeredPointingSession/handleTriggeredPointingDecision、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增设备专用目标结构，输入不再退化成旧 `RectF` target，直接使用 `deviceId + hotspot + polygon`。
      *   现有 `frameQuality` 在新链路中直接作为“当前帧射线置信度”使用，不再额外发明第二套置信度变量。
      *   新增几何层、单帧评分层、窗口累计层、结果层四段模块，严格按窗口级公式实现热点得分、四边形得分、纯几何得分、单帧总分与最终总分。
      *   设备模式接入点已切到 `DeviceTriggeredPointingResolver`，不再走旧 `computeGeomScore / evaluateFastAccept / evaluateNormalAccept / finalizeTimeoutDecision` 这条 `RectF` 旧链。
      *   调试输出已覆盖每帧每设备分解分数，以及每窗口结束后的平均项、峰值项、命中比例、领先比例、最终总分、第一名/第二名、最终领先倍率、动态阈值和置信状态。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [267] 2026-03-28 02:35:05 - 修复设备默认框未围绕点击点与贴边参照错误

**用户指令**：
> 出现了一些问题，当我点击一个地方时候，整个矩形不是围绕着它而画的。也就是说不是他的中心点。 我说的贴边是屏幕边缘
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复设备新建时默认框未围绕点击点居中，以及“贴边 150px”错误按整个 View 而非实际画面边缘计算的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/utils/DeviceGeometryUtils.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DeviceGeometryUtils.clampCreationHotspot/createDefaultPolygon、LivingRoomEditorView.createDraftDeviceFromHotspot、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   设备热点贴边限制改为基于 `dstRect`，也就是实际显示画面边缘，而不是整个 View 外框。
      *   默认 `200x300` 设备框的生成也改为基于 `dstRect` 的像素坐标和归一化坐标系。
      *   现在只要点击点不靠近画面边缘，默认框会严格以点击点为几何中心；只有接近画面边缘时才会被推开以避免越界。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [266] 2026-03-28 02:28:42 - 设备模型升级为热点加四边形并重构编辑流程

**用户指令**：
> 先只改“设备数据结构”和“设备创建/编辑逻辑”，不要改射线评分算法、窗口统计、命中判定、最终排序逻辑。
> 当前项目里的设备原本只有一个四边形 polygon。现在直接改成新的设备定义，不需要兼容旧数据，因为旧数据已经删除了。
> 新的设备数据结构统一为：
> - 设备 = 核心点 hotspot + 四边形 polygon
> - hotspot 是用户真正最可能指向的核心位置
> - polygon 是设备的大致区域框
> - 约束：polygon 必须始终包含 hotspot（内部或边界上都可以）
> ...
> 这次只完成设备定义与设备编辑逻辑升级。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将设备从“仅四边形”升级为“hotspot + polygon”结构，并重构设备创建/编辑/显示/存储链路，不改任何指向判定算法。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/model/DeviceConfig.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/utils/DeviceGeometryUtils.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DeviceConfig 数据结构定义、RoomRepository.serializeDevice/deserializeDevice/copyDevice、LivingRoomEditorView.commitPendingDevice/createDraftDeviceFromHotspot/getDevicePolygon/updateDeviceCorner/moveDevice/handleDeviceTouchEvent/drawDeviceRect、MainActivity.buildPointingTargetRects/enterAddDeviceMode/showSaveDeviceDialog、DetectionOverlayView.onDraw、DeviceGeometryUtils 全部新增方法、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   设备模型改为 `hotspot + polygon`，删除旧的 `left/top/right/bottom` 兼容读取，只认新结构。
      *   新建设备改成“先点 hotspot，再自动生成默认 200x300 四边形”；创建时自动把热点校正到距边缘至少 150px 的合法位置。
      *   设备整体移动时，`hotspot` 与 `polygon` 一起平移；不再允许只移动其中一部分。
      *   顶点编辑新增约束：四边形必须合法、凸、且始终包含 `hotspot`；非法拖动会保持在最近一次合法状态。
      *   编辑视图与 overlay 同步显示 `hotspot`，便于明确看到“核心指向点”和“设备框”。
      *   新增 `DeviceGeometryUtils`，整理设备相关几何辅助方法，供后续算法继续复用。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [265] 2026-03-28 02:13:02 - 修复编辑模式数量下拉刷新崩溃

**用户指令**：
> 2026-03-28 02:11:54.538 19783-19783 AndroidRuntime          com.example.roomxxx0102              E  FATAL EXCEPTION: main (Fix with AI)
> java.lang.UnsupportedOperationException
> ...
> at com.example.roomxxx0102.ui.activities.MainActivity.refreshEditModeLabels(MainActivity.kt:1687)

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复进入编辑模式时刷新模式数量文案导致的 `ArrayAdapter.clear()` 崩溃。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.setupButtons、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `setupButtons()` 中的编辑模式 `ArrayAdapter` 改为用 `MutableList<String>` 初始化，而不是直接绑定不可变数组。
      *   保留现有 `refreshEditModeLabels()` 的 `clear()/addAll()` 刷新方式，不再触发 `UnsupportedOperationException`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [264] 2026-03-28 02:09:06 - 指向识别目标切换为设备并显示编辑模式数量

**用户指令**：
> GeminiHistory我再说一次不要读了.  同时加一个新功能,次房间设置和设备设置选项的右边都加上当前的数量.也就是次房间数量和设备数量.开始吧.
> refreshOverlayDisplay是什么时候?频率高吗?应该只有点出这个菜单才能看到
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将指向识别目标从房门切换为设备，并在编辑模式下拉菜单中显示当前次房间数量与设备数量。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.buildPointingTargetRects/setupButtons/enterEditMode/transitionTo/applyModeSelection/buildEditModeLabels/refreshEditModeLabels、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `buildPointingTargetRects()` 不再从房门快照构造目标，而是改为读取当前配置中的设备列表，并使用设备四边形的外接矩形作为 pointing target。
      *   指向识别目标的 `label` 改为设备名称，不再使用次房间/房门名称。
      *   编辑模式 Spinner 改为动态文案：`主房间设置 / 次房间设置(n) / 设备设置(n)`。
      *   数量文案刷新挂在编辑菜单链路里，在进入编辑模式、模式切换、编辑状态切换时同步刷新，不放进 `refreshOverlayDisplay()`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [263] 2026-03-27 22:21:46 - 按住看手时隐藏客厅房门主ROI并显示设备框

**用户指令**：
> 按住看手被激活后,取消客厅,人体ROI框,房门的显示. 显示设备框.
> 手部的ROI别忘了也要显示.开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：调整“按住看手”激活后的显示层，隐藏客厅/房门/主人体 ROI，只保留手部相关显示并新增设备四边形显示。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：DetectionOverlayView.setDevices/onDraw、MainActivity.refreshOverlayDisplay、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `DetectionOverlayView` 新增设备列表输入与设备四边形画笔。
      *   当 `showHandOnly == true` 时，不再绘制客厅区域、房门/次房间相关显示和主 ROI 框。
      *   手部 ROI (`handRoiBox`) 继续保留显示，不受“按住看手”模式影响。
      *   同一模式下新增设备四边形显示，便于配合手部观察设备区域。
      *   `MainActivity.refreshOverlayDisplay()` 现在会把当前配置里的设备列表同步喂给 overlay。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [262] 2026-03-27 22:00:16 - 设备模型从矩形改为任意四边形

**用户指令**：
> 有个设计错误,不要矩形,而是任意四边形
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将第 1 版设备模型从 `left/top/right/bottom` 矩形改为真正的四点任意四边形，并保持旧矩形配置可兼容读取。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/model/DeviceConfig.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoomRepository.getDevices/replaceDevices/serializeDevice/deserializeDevice/copyDevice、LivingRoomEditorView.setDevices/getDevices/commitPendingDevice/copyDevice/copyDevicePoints/getDevicePoints/getDeviceBounds/findDeviceHit/updateDeviceCorner/moveDevice/drawDeviceRect、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `DeviceConfig` 不再保存矩形四边，而是改为保存固定顺序的 4 个顶点。
      *   `RoomRepository` 设备序列化改为 `points` 数组；反序列化时若发现旧字段 `left/top/right/bottom`，会自动转换成四点矩形，兼容旧配置。
      *   `RoomRepository` 与 `LivingRoomEditorView` 的设备拷贝逻辑全部改成深拷贝 `PointF`，避免拖拽顶点时共享引用串改缓存。
      *   `LivingRoomEditorView` 的设备命中、角点拖拽、整体移动、绘制显示，全部改为基于四点四边形处理，不再走 `RectF` 假设。
      *   新建设备仍然先拖出一个初始正方形，但保存后四个角完全独立，可调整成任意四边形。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [261] 2026-03-27 20:09:43 - 实现第1版设备编辑与保存链路

**用户指令**：
> 我下一步的计划是这样的。我们还是把它放在。设置房间菜单中设置房间的菜单现在目前有设备设置对吧,移动到菜单栏中,就和添加子房间一样.,现在点击添加设备。之后,菜单隐藏，然后弹出设备命名(默认给个设备+ID递增)提示请绘制矩形,然后用户用手绘制出一个正方形,然后可以对正方形的四个角进行调整.长按矩形还可以移动位置.然后绘制完成后点击保存.保存这个设备的名字和ID.单击设备矩形后,可以删除设备. 总来的说操作可以参考子房间.
> 1.后续允许四角调成一般矩形. 2.先绘制好矩形后再点击保存,弹出名字和确认,
> 1.设备设置还是没变啊,还是整页./只是选中这个菜单后,不是弹出,而是和子房间一样有个增加设备按钮
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：实现第 1 版设备编辑链路，保留“设备设置”模式，但改为像次房间一样在 `editorView` 上用左侧按钮完成设备矩形的新增、编辑、删除和保存。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/model/DeviceConfig.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/views/LivingRoomEditorView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoomRepository.getDevices/replaceDevices/serializeDevice/deserializeDevice/loadFromCurrentFile/saveToFile/buildRootJson、LivingRoomEditorView.setDevices/setOnDeviceSelectionListener/commitPendingDevice/deleteSelectedDevice/handleDeviceTouchEvent/drawDeviceRect、MainActivity.enterDeviceSettingsMode/enterAddDeviceMode/transitionTo/renderEditorMenu/applyModeSelection/suggestNextDeviceIndex/handleDeviceSave/showSaveDeviceDialog、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   新增 `DeviceConfig` 模型，并将 `.Room` 配置扩展为同时持久化 `rooms` 与 `devices`。
      *   `RoomRepository` 新增设备列表缓存与读写接口，备份/恢复、空配置、意义配置判定同步纳入设备。
      *   `LivingRoomEditorView` 新增 `DEVICE` 编辑模式，支持：
        *   点击“添加设备”后拖出初始正方形；
        *   后续拖四角调整为普通矩形；
        *   长按矩形整体移动；
        *   单击选中、删除未保存草稿或已保存设备。
      *   `MainActivity` 将“设备设置”从占位页改成真正的 `editorView + 左侧按钮` 状态机，新增 `DEVICE_IDLE / DEVICE_ADD / DEVICE_SELECTED` 三个状态。
      *   新设备的保存顺序改为“先画矩形，再点保存弹名称输入框”，默认采用 `设备1 / 设备2 / 设备3` 与 `device_1 / device_2 / device_3` 递增。
      *   已有设备拖拽/调角后点击保存即可落盘；删除设备则直接同步仓库并保存。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [260] 2026-03-27 17:03:55 - 编辑页保存即落盘并将设置页改为另存为

**用户指令**：
> 我新发现了一个bug。我们在创建。一个房间。也就是我设置了一个储房间之后这个时候应该。点了保存之后就应该有配置文件了。但是我没有去点房间配置管理设置页里面的保存，这个时候它的。配置好像没有被保存下来。你先确认一下是不是？如果是的话，我们要做下一个处理。当。保存的时候，这个时候已经被保存下来了。在设置页里面应该叫另存为。另存的时候，弹出一个。名字。然后用户点保存在保存那个配置。默认名字会加上。我们之前约定好的123之类的。
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复编辑页“保存”只改内存不落盘的问题，并把设置页原“保存”入口明确收口为“另存为”。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、app/src/main/res/layout/fragment_settings_home.xml、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoomRepository.saveAsConfigFile/saveCurrentConfigOrCreateForCurrentVideo/resolvePrimarySaveTargetFile/saveToFile、MainActivity.onCreate(btnFinish.setOnClickListener)/performSave/persistRoomConfigAfterEditorSave、SettingsHomeFragment.saveCurrentConfigAs/onViewCreated(btnExport.setOnClickListener)、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `RoomRepository` 新增“保存当前配置或为当前视频创建默认配置文件”的入口，首存时自动落到当前视频目录下的默认 `.Room` 文件。
      *   当前保存目标若不是当前视频目录下的配置文件，则不再继续覆盖旧 `room_config.json`，而是切到当前视频默认配置文件再落盘。
      *   `MainActivity` 的编辑页 `btnFinish` 现在在主房间保存、次房间门选择保存、次房间区域保存后都会立即触发真实文件保存。
      *   设置页原“保存现有房间配置”改为“当前房间配置另存为”，弹窗标题、确认按钮和成功提示同步改为“另存为”语义。
      *   另存为默认命名规则继续沿用 `VideoRoomConfigManager.suggestNextConfigNameForCurrentVideo()` 的递增后缀方案。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [259] 2026-03-27 16:42:38 - 灰色指向线独立显示并在命中后保持1秒

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让灰色指向线独立于目标识别存在，并使用 `frameQuality >= 0.45` 作为显示门槛；识别成功后保留门高亮与识别路径 1 秒。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/pointing/TriggeredPointingResolver.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：TriggeredPointingResolver.submitFrame、DetectionOverlayView.setPointingDebugOverlayEnabled/updatePointingLiveSnapshot/updatePointingDebugSnapshot/drawPointingDebugOverlay、MainActivity.startTriggeredPointingSession/handleTriggeredPointingObservation/handleTriggeredPointingDecision/setupButtons、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `TriggeredPointingResolver` 在没有 target rect 时不再立刻结束，而是继续产出实时手势证据，支持无配置场景下持续画灰色线。
      *   `MainActivity` 新增灰色线门槛 `frameQuality >= 0.45`，只有满足该质量下限时才把实时方向线送到 overlay。
      *   `DetectionOverlayView` 将 pointing 可视化拆成两层：实时灰色方向线层，以及识别命中后的保持层。
      *   命中目标后，门高亮与识别路径固定保持 1 秒；实时灰色线仍可继续刷新，不再互相覆盖。
      *   抬手时只清实时灰色线，不清 1 秒保持态。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [258] 2026-03-27 16:16:45 - 拆分 pose ROI 与 hand ROI 输入通道

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修正 `handRoi` 误影响 pose 推理输入的问题，让 pose 继续吃主 ROI，而 hand 识别单独吃 handRoi。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/video/VideoFeeder.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：VideoFeeder.analyzeRunnable、MainActivity.onCreate/hardRestartPlayback、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 `VideoFeeder` 新增 `nextHandFrameRoi`，专门给 `HandSmokeTester` 使用。
      *   `submitInferenceTask(... roi=...)` 继续只吃 `nextFrameRoi`，不再受 handRoi 影响。
      *   `handSmokeTester.detect(bitmap, roi)` 改为优先吃 `nextHandFrameRoi`，为空时回退 `nextFrameRoi`。
      *   `MainActivity` 侧改为分别回写：`nextFrameRoi = cropRoi`，`nextHandFrameRoi = handRoi ?: cropRoi`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [257] 2026-03-27 16:05:54 - 手部识别优先使用新的 handRoi 输入

**用户指令**：
> 好,就把这个新的ROI喂给手部识别吧
> 没有 handRoi就传现在的ROI,开始吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：让手部识别优先吃新的 pose 驱动 handRoi，在 handRoi 暂时不可用时再回退到当前主 ROI。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.onCreate、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   保留原有主 ROI/handRoi 的绘制与跟踪逻辑不变。
      *   将传给 `VideoFeeder.nextFrameRoi` 的输入改为 `handRoi ?: cropRoi`。
      *   这样手部识别会优先用新的手部 ROI 裁剪输入；当手部 ROI 不存在时，再退回当前主 ROI，避免直接退回全图。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [256] 2026-03-27 15:55:30 - 收紧 PoseStagnant 为连续4帧逐帧全点一致

**用户指令**：
> 我们改成连续4帧所有点都相同吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：收紧 `PoseStagnant` 解锁条件，避免“最近4帧里任意两帧接近”导致静坐目标被过早解锁。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/tracker/SimpleTrackerEngine.kt、app/src/main/java/com/example/roomxxx0102/logic/tracker/RemoteByteTrackEngine.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SimpleTrackerEngine.isPoseStagnant/track、RemoteByteTrackEngine.isPoseStagnant/processTracks、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `isPoseStagnant(...)` 不再做窗口内任意两帧两两比较，而是改成连续4帧逐帧相邻比较。
      *   只有当最近4帧中每一对相邻帧的所有关键点都保持近乎一致时，才判定为 `PoseStagnant=true`。
      *   将该判定的容差从 `1f` 收紧为 `0.001f`，使其更接近“所有点都相同”的语义。
      *   unlock 日志文案同步更新为 `tol=0.001`。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [255] 2026-03-27 05:16:42 - 为 unlock 增加固定 tag 的 logcat 日志

**用户指令**：
> ok

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：为 lock/unlock 排查补充稳定的 logcat 观测点，避免 unlock 只作为瞬时 banner 或内存消息出现而无法事后检索。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/tracker/SimpleTrackerEngine.kt、app/src/main/java/com/example/roomxxx0102/logic/tracker/RemoteByteTrackEngine.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SimpleTrackerEngine.processDetections、RemoteByteTrackEngine.processTracks、MainActivity.onCreate、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   在 simple/remote 两套 tracker 里，当生成 `unlock:` 消息时，立即额外写入 `RoomLockDiag` tag 到 logcat。
      *   在 `MainActivity` 消费 unlock 消息时，再补一条 `RoomLockDiag` 的 `ui_consume` 日志，便于确认消息是否成功传到了 UI 层。
      *   不改变任何 lock/unlock 判定逻辑，只增加可观测性。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [254] 2026-03-27 04:56:14 - 手部 ROI 改为纯 pose 手腕驱动

**用户指令**：
> 你搞错了,应该一直是用pose来确认手的ROI,和hand没关系.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修正手部 ROI 的目标来源，确保这层 ROI 只由当前 lock 人物的 pose 左右手腕决定，不再受 hand 检测结果波动影响。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：MainActivity.buildLockedHandRoiTarget、MainActivity.onCreate、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   手部 ROI 计算不再依赖 `HandSmokeTester` 回传的手索引或手腕点。
      *   现在直接读取当前 `isConfirmed=true` 人物的 pose 左右手腕关键点，在有效手腕中选择垂直更高的那个作为 ROI 中心。
      *   ROI 边长仍保持“人物框短边、最小 224px”，并继续复用现有 ROI tracker 的平滑、丢失与边界逻辑。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [253] 2026-03-27 04:48:24 - 新增锁定人物更高手腕的手部 ROI

**用户指令**：
> 我们在现在的基础上再画一个ROI,只画这只手.然后:ROI 形状：正方形
> ROI 中心：手腕
> ROI 边长：身体框短边
> 最小值：224   .这个框的出现消失逻辑移动等等逻辑全部复用我们之前ROI的逻辑(除非我们那边有些常量设置得不适合这么小的框) .我们只在垂直更高的那个手上画这个ROI,而且人必须处于lock状态.

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在现有主 ROI 之外新增一层“手部 ROI”，仅在人物处于 lock 状态时，围绕更高那只手的手腕绘制正方形 ROI，并复用现有 ROI 的跟踪/消失逻辑。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/RoiTracker.kt、app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、app/src/main/java/com/example/roomxxx0102/ui/views/DetectionOverlayView.kt、app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoiTracker.calculate、HandSmokeTester.onLiveStreamResult、DetectionOverlayView.updateHandRoiBox/updateHandData/onDraw/drawHands、MainActivity.onCreate/buildLockedHandRoiTarget/hardRestartPlayback、tools/dialogue_archive.py append-turn
    *   关键改动：
      *   `RoiTracker` 新增可选目标边长参数，支持在保留原主 ROI 行为的同时，给手部 ROI 复用同一套移动、死区、EMA、丢失保持与边界裁剪逻辑。
      *   `HandSmokeTester` 在回调手部 landmarks 时，同时回传“垂直更高”的手索引，避免界面层再重复判定。
      *   `MainActivity` 新增手部 ROI 跟踪器，仅当存在 `isConfirmed=true` 的锁定人物且当前有选中的更高手时才驱动；ROI 中心取手腕，边长取人体框短边并设置最小值 `224px`。
      *   `DetectionOverlayView` 新增手部 ROI 图层，并在手部显示模式下只绘制选中的那只手，避免同时画两只手造成干扰。
      *   重置播放运行态时，会同步清空手部 ROI 与已缓存手部结果，避免旧状态残留。
      *   编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [252] 2026-03-27 03:18:43 - 暂不配置状态改为冷启动持久生效

**用户指令**：
> 我已经执行了一次不选择,怎么重启app后又给我回来了

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修复“暂不配置”只在当前运行期生效、重启后又被 `room_config.json` 回退逻辑覆盖的问题。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/AppSettings.kt、app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：AppSettings.setNoRoomConfigSelected/init、RoomRepository.init/switchToConfigFile/saveAsConfigFile、SettingsHomeFragment.buildNoConfigRow、tools/dialogue_archive.py append-turn
    *   关键改动：
      * 在 `AppSettings` 新增 `isNoRoomConfigSelected` 持久标记，用于记录用户是否明确选择了“暂不配置”。
      * `RoomRepository.init()` 启动时优先检查该标记；若为真，则直接进入临时空配置，不再回退到 `room_config.json`。
      * 用户重新读取已有配置文件或保存新配置文件时，自动清除该标记，恢复正常冷启动配置逻辑。
      * “暂不配置”按钮现在会同时切到临时空配置、清空 `activeRoomConfigPath`，并写入 `isNoRoomConfigSelected=true`。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [251] 2026-03-27 03:15:02 - 配置列表新增暂不配置并清除配置记忆

**用户指令**：
> 我们再加一个功能就是在读取房间配置的。列表里面加一个。暂不配置项,点击后清除选择. 用户就可以去建立新的配置了

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：在“读取房间配置”列表中新增“暂不配置”入口，让用户可以主动清除当前配置绑定并从空白状态开始新建配置。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：SettingsHomeFragment.buildNoConfigRow、refreshConfigListContent、tools/dialogue_archive.py append-turn
    *   关键改动：
      * 在读取配置的展开列表顶部新增固定项“0. 暂不配置”。
      * 点击“选择”后调用 `RoomRepository.loadTemporaryEmptyConfig()` 切到临时空配置。
      * 同时调用 `AppSettings.setActiveRoomConfigPath(null)` 清除“上一次真正加载过的配置文件”记忆，避免重启恢复旧文件。
      * 当前处于临时空配置时，“暂不配置”会显示 `[当前]`，便于用户识别当前状态。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [250] 2026-03-27 03:08:02 - 无匹配视频时切换为临时空配置

**用户指令**：
> 好的,开始吧,我们确认下,对话日志的写入是用的一个脚本,不是你一个字一个字的写

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：修正“当前视频无匹配配置时仍像是挂着旧文件”的行为，使其切到临时空配置，同时保留上一次真实加载配置文件的记忆。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/data/repository/RoomRepository.kt、app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt、codexHistory.md、dialogueHistory.md
    *   涉及方法：RoomRepository.loadTemporaryEmptyConfig、SettingsHomeFragment.loadDefaultConfigForSelectedVideo、tools/dialogue_archive.py append-turn
    *   关键改动：
      * 在 `RoomRepository` 新增 `loadTemporaryEmptyConfig()`，用于显式切到“无文件绑定的临时空配置”内存态。
      * 当前视频默认配置文件存在时，仍按原逻辑加载并持久化选择。
      * 当前视频默认配置文件不存在时，设置页改为调用 `loadTemporaryEmptyConfig()`，不再继续挂着一个不存在的目标文件。
      * 这样既能保证当前无匹配视频不加载任何已保存配置文件，也不会抹掉 `activeRoomConfigPath` 对上一次真实加载文件的记忆。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

## [249] 2026-03-27 02:52:36 - 维护项目方法总览文档并补齐新增模块

**用户指令**：
> 直接去读然后维护吧

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：维护 `AI_PROJECT_CONTEXT.md` 这份项目方法总览文档，使其重新反映当前项目结构与主链路。
    *   修改文件：AI_PROJECT_CONTEXT.md、codexHistory.md、dialogueHistory.md
    *   涉及方法：AI_PROJECT_CONTEXT 顶部结构快照、Current Delta Summary、Current Hot Paths、tools/dialogue_archive.py append-turn
    *   关键改动：
      * 将 `AI_PROJECT_CONTEXT.md` 的生成时间更新到本次维护时间。
      * 重写文档顶部的项目文件结构，补齐 `VideoRoomConfigManager`、`pointing`、`presence`、`tracker`、`validation` 等 1 月后新增模块。
      * 新增 `Current Delta Summary`，明确这份文档相较旧快照新增的主线模块及职责。
      * 新增 `Current Hot Paths`，总结当前项目最常维护的跨文件主链路，方便后续快速定位。

---

## [248] 2026-03-27 00:18:00 - 放开双手检测并在pointing中优先选择更高的手

**用户指令**：
> 我们只选择垂直方向更高的一只手。现在开始改。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：放开 Hand Landmarker 的双手检测能力，并在 pointing 输入层改为优先选择画面里更高（y 更小）的那只手。
    *   修改文件：app/src/main/java/com/example/roomxxx0102/logic/analyzer/HandSmokeTester.kt、dialogueHistory.md、codexHistory.md
    *   涉及方法：HandSmokeTester.setupHandLandmarker/onLiveStreamResult/selectHigherHandIndex
    *   关键改动：
      * `numHands` 从 1 调整为 2，允许同一帧返回两只手。
      * `onHandsResult` 继续保留所有检测到的手，overlay 仍可同时绘制多手。
      * pointing 不再固定取第一只手，而是按整手平均 y 值选择画面里更高的那只手作为输入。
      * 编译验证通过：`:app:compileDebugKotlin` 成功。

---

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
