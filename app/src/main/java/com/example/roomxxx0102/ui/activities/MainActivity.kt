package com.example.roomxxx0102.ui.activities

import com.example.roomxxx0102.data.model.BoundaryVertex
import android.animation.ValueAnimator
import android.widget.CheckBox
import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.roomxxx0102.R
import com.example.roomxxx0102.data.model.DeviceConfig
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.logic.analyzer.RoiLogAggregator
import com.example.roomxxx0102.logic.analyzer.RoiTracker
import com.example.roomxxx0102.logic.analyzer.HandSmokeTester
import com.example.roomxxx0102.logic.analyzer.YoloAnalyzer
import com.example.roomxxx0102.logic.analyzer.YoloPoseAnalyzer
import com.example.roomxxx0102.logic.audio.PlaybackVideoAudioSource
import com.example.roomxxx0102.logic.gesture.HandTranslationController
import com.example.roomxxx0102.logic.pointing.DevicePointingTarget
import com.example.roomxxx0102.logic.pointing.DeviceTriggeredPointingResolver
import com.example.roomxxx0102.logic.pointing.HandObservation
import com.example.roomxxx0102.logic.pointing.PointingDecision
import com.example.roomxxx0102.logic.pointing.PointingDebugSnapshot
import com.example.roomxxx0102.logic.pointing.PointingConfidenceStatus
import com.example.roomxxx0102.logic.presence.PresenceOutsideMode
import com.example.roomxxx0102.logic.presence.PresenceKeypoint
import com.example.roomxxx0102.logic.presence.PresencePoint
import com.example.roomxxx0102.logic.presence.PresenceRect
import com.example.roomxxx0102.logic.presence.PresenceRoomSnapshot
import com.example.roomxxx0102.logic.presence.PresenceSwitchDisplayType
import com.example.roomxxx0102.logic.presence.PresenceStrength
import com.example.roomxxx0102.logic.presence.PresenceTrackObservation
import com.example.roomxxx0102.logic.presence.PresenceDoorSnapshot
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmEngine
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceEstimatorParams
import com.example.roomxxx0102.logic.presence.RoomPresenceChangeLogger
import com.example.roomxxx0102.logic.presence.PresenceSwitchEvent
import com.example.roomxxx0102.logic.validation.DeviceHitMarkedEvent
import com.example.roomxxx0102.logic.validation.DeviceHitMarkerManager
import com.example.roomxxx0102.logic.validation.EventMarkerManager
import com.example.roomxxx0102.logic.validation.EventType
import com.example.roomxxx0102.logic.validation.MarkedEvent
import com.example.roomxxx0102.logic.validation.RuntimeRoomEvent
import com.example.roomxxx0102.logic.video.VideoFeeder
import com.example.roomxxx0102.ui.audio.AudioCommandLogUpdate
import com.example.roomxxx0102.ui.audio.KwsPanelScreen
import com.example.roomxxx0102.ui.views.DetectionOverlayView
import com.example.roomxxx0102.ui.views.LivingRoomEditorView
import com.example.roomxxx0102.ui.views.TacticalMapView
import com.example.roomxxx0102.utils.AppLog
import com.example.roomxxx0102.utils.BitmapTransfer
import com.example.roomxxx0102.utils.GeometryUtils
import com.example.roomxxx_vocie.Command
import com.example.roomxxx_vocie.KwsControllerImpl
import com.example.roomxxx_vocie.audio.AudioInputMode
import com.example.roomxxx_vocie.audio.AudioRecordSource
import com.example.roomxxx_vocie.audio.SwitchableAudioSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MAIN_SPLASH_MIN_DURATION_MS = 1_000L

class MainActivity : ComponentActivity() {

    private val previewView: PreviewView by lazy { findViewById(R.id.previewView) }
    private val textureView: android.view.TextureView by lazy { findViewById(R.id.textureView) }
    private val liveFrozenFrameView: ImageView by lazy { findViewById(R.id.liveFrozenFrameView) }
    private val composeAudioScreen: ComposeView by lazy { findViewById(R.id.composeAudioScreen) }
    private val overlayView: DetectionOverlayView by lazy { findViewById(R.id.overlayView) }
    private val editorView: LivingRoomEditorView by lazy { findViewById(R.id.editorView) }
    private val llNormalControls: View by lazy { findViewById(R.id.llNormalControls) }
    private val llRightActionControls: View by lazy { findViewById(R.id.llRightActionControls) }
    private val llEventMarkerControls: View by lazy { findViewById(R.id.llEventMarkerControls) }
    private val llEditorControls: View by lazy { findViewById(R.id.llEditorControls) }
    private val tvRoomCount: TextView by lazy { findViewById(R.id.tvRoomCount) }
    private val cardCounter: View by lazy { findViewById(R.id.cardCounter) }
    
    // 雷达相关 View
    private val flRadarContainer: FrameLayout by lazy { findViewById(R.id.flRadarContainer) }
    private val tacticalMapView: TacticalMapView by lazy { findViewById(R.id.tacticalMapView) }
    private val btnCloseRadar: ImageButton by lazy { findViewById(R.id.btnCloseRadar) }
    
    // 🔥 设备设置 View
    private val llDeviceSettings: LinearLayout by lazy { findViewById(R.id.llDeviceSettings) }
    private val btnAddDevice: Button by lazy { findViewById(R.id.btnAddDevice) }

    // 🔥 ROI Tracker
    private var roiTracker = createPoseRoiTracker()
    private val handRoiTracker = RoiTracker(
        baseRoiSizePx = 224f,
        adaptiveResizeEnabled = false,
        logSource = "手部ROI"
    )
    private var lastPoseRoiSizeMode = AppSettings.poseRoiSizeMode
    private var roiMissingFrameCount = 0
    private var handRoiMissingFrameCount = 0

    private var yoloAnalyzer: YoloAnalyzer? = null
    private var poseAnalyzer: YoloPoseAnalyzer? = null
    private var handSmokeTester: HandSmokeTester? = null
    private var videoFeeder: VideoFeeder? = null
    private enum class ObserveMode { PERSON, HAND, AUDIO }
    private enum class CenterBannerDomain { DEVICE, ROOM }
    private var currentObserveMode = ObserveMode.PERSON
    @Volatile private var latestHandResults: List<List<HandSmokeTester.HandPoint>> = emptyList()
    @Volatile private var latestSelectedHandIndex: Int? = null
    private val pointingResolver = DeviceTriggeredPointingResolver()
    private val handTranslationController = HandTranslationController {
        AppSettings.handTranslationFullRange
    }
    private val pointingGuideMinQuality = 0.45f
    private var pointingTargetLabelById: Map<String, String> = emptyMap()
    private var pendingVoicePointingFeedback = false
    private val persistentHandBannerDurationMs = 60 * 60 * 1000L
    private val pointingReplayHistory = ArrayDeque<HandObservation>()
    private val pointingReplayHistoryWindowMs = 250L
    private val pointingReplayRetentionMs = 1200L
    private val kwsAudioSource by lazy {
        SwitchableAudioSource(
            microphoneSource = AudioRecordSource(applicationContext),
            playbackSource = PlaybackVideoAudioSource(
                context = applicationContext,
                sourceProvider = { resolvePlaybackAudioSourceSpec() },
                playbackPositionProvider = { videoFeeder?.peekLastAnalysisPositionMs()?.toLong() },
                playbackActiveProvider = { isVideoMode }
            ),
            initialMode = AudioInputMode.PLAYBACK
        )
    }
    private val kwsController by lazy { KwsControllerImpl(applicationContext, kwsAudioSource) }
    private var isAudioScreenBound = false
    private val kwsLogClearSignal = mutableIntStateOf(0)
    private val latestKwsAudioLogUpdate = mutableStateOf<AudioCommandLogUpdate?>(null)
    private var pendingVoiceCommandToken: Long? = null
    private var lastCenterBannerDomain: CenterBannerDomain? = null

    private fun createPoseRoiTracker(): RoiTracker {
        return when (AppSettings.poseRoiSizeMode) {
            AppSettings.POSE_ROI_SIZE_960 -> RoiTracker(
                baseRoiSizePx = 960f,
                adaptiveResizeEnabled = false,
                logSource = "人体ROI"
            )
            AppSettings.POSE_ROI_SIZE_640 -> RoiTracker(
                baseRoiSizePx = 640f,
                adaptiveResizeEnabled = false,
                logSource = "人体ROI"
            )
            AppSettings.POSE_ROI_SIZE_480 -> RoiTracker(
                baseRoiSizePx = 480f,
                adaptiveResizeEnabled = false,
                logSource = "人体ROI"
            )
            else -> RoiTracker(logSource = "人体ROI")
        }
    }

    private fun syncPoseRoiTrackerConfig() {
        val mode = AppSettings.poseRoiSizeMode
        if (mode == lastPoseRoiSizeMode) return
        lastPoseRoiSizeMode = mode
        roiTracker = createPoseRoiTracker()
    }

    private var isVideoMode = true
    private var currentLivingRoomBoundary: List<PointF> = emptyList()
    private var lastVideoSourceKey: String? = null
    private var lastMissingReviewVideoToastAtMs = 0L
    private var btnHandOverlay: Button? = null

    // 播放状态机
    private enum class PlayState { PLAYING, STILL, PAUSED }
    private var currentPlayState = PlayState.PLAYING
    private var isDebugPanelEnabled = false
    private var unlockClipboardArmedUntilMs = 0L
    private var unlockClipboardCaptured = false
    private var unlockClipboardTrigger: String = ""
    private val unlockClipboardWindowMs = 1500L
    private var lastPlusOneSeekDebug: VideoFeeder.StepSeekDebug? = null
    private val seekHoldHandler = Handler(Looper.getMainLooper())
    private val eventUiHandler = Handler(Looper.getMainLooper())
    private var pendingVoiceTimeoutToken: Long? = null
    private val pendingVoiceTimeoutRunnable = Runnable {
        val token = pendingVoiceTimeoutToken ?: return@Runnable
        if (!pendingVoicePointingFeedback || pendingVoiceCommandToken != token) return@Runnable
        if (!pointingResolver.isActive()) return@Runnable
        val decision = pointingResolver.submitFrame(null)
        if (decision !is PointingDecision.Pending) {
            handleTriggeredPointingDecision(decision)
        }
    }
    private var seekHoldActive = false
    private var seekHoldDirection = 0 // -1: 后退, +1: 前进
    private val seekHoldStartDelayMs = 500L
    private var forwardHoldPreviewPlaying = false
    private var blankPreviewActive = false
    private var blankPreviewDownRawX = 0f
    private var blankPreviewDownRawY = 0f
    private val blankPreviewTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private var savedOverlayVisibility: Int? = null
    private var savedNormalControlsVisibility: Int? = null
    private var savedRightActionControlsVisibility: Int? = null
    private var savedEventControlsVisibility: Int? = null
    private var savedEditorControlsVisibility: Int? = null
    private var savedCounterVisibility: Int? = null
    private var savedRadarVisibility: Int? = null
    private var savedEditorViewVisibility: Int? = null
    private val splashHideHandler = Handler(Looper.getMainLooper())
    private val runtimeSwitchHandler = Handler(Looper.getMainLooper())
    private var splashLoadingAnimator: ValueAnimator? = null
    private var splashShownAtMs: Long = 0L
    private var isMainStartupInitialized = false
    private var isMainStartupInitializing = false
    private var lastPresenceCountsForPause: Map<String, Int>? = null
    private var lastPresenceAnomalyDumpKey: String? = null
    private val beijingTimeFormatter: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
    }

    // Presence 估计引擎（位置判定/房间切换事件）
    private lateinit var roomPresenceAlgorithm: PresenceAlgorithmEngine
    private val roomPresenceChangeLogger = RoomPresenceChangeLogger("ROOM_PRESENCE_CHANGE")
    private val eventMarkerManager = EventMarkerManager()
    private val deviceHitMarkerManager = DeviceHitMarkerManager()
    private val runtimeValidationEvents: ArrayDeque<RuntimeRoomEvent> = ArrayDeque()
    private val matchedMarkedEventKeys: MutableSet<String> = mutableSetOf()
    private val alertedMarkedEventKeys: MutableSet<String> = mutableSetOf()
    private val matchedRuntimeByMarkedKey: MutableMap<String, ValidationRuntimeEvent> = mutableMapOf()
    private var boundEventVideoKey: String? = null
    private var boundDeviceHitVideoKey: String? = null
    private var isAwaitingDeviceHitSelection = false
    private var pendingDeviceHitTimestampMs: Long? = null
    private var pendingDeviceHitFrameIndex: Int? = null
    private var selectionSavedEditorVisibility: Int? = null
    private var selectionSavedRadarVisibility: Int? = null

    private enum class UiLayerMode {
        NORMAL,
        EDITING,
        DEVICE_SELECTION
    }

    private var isAddSubRoomMode = false
    private var btnAddSubRoom: Button? = null
    private var isDoorSelectMode = false
    private var isRoomAreaEditMode = false
    private var isAddDeviceMode = false
    private var btnSelectDoor: Button? = null
    private var btnEditRoomArea: Button? = null
    private var btnAddDeviceEditor: Button? = null
    
    // 🔥 模式切换 Spinner
    private var spnEditMode: Spinner? = null
    private var editModeAdapter: ArrayAdapter<String>? = null
    
    // 编辑状态机
    private var editorMenuState = EditorMenuState.LIVING_ROOM

    private enum class EditorMenuState {
        LIVING_ROOM,
        SUBROOM_IDLE,
        SUBROOM_ADD,
        SUBROOM_SELECTED,
        SUBROOM_DOOR_SELECT,
        SUBROOM_AREA_EDIT,
        DEVICE_IDLE,
        DEVICE_ADD,
        DEVICE_SELECTED
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true || permissions[Manifest.permission.READ_MEDIA_VIDEO] == true) {
            if (isVideoMode) startVideoMode() else startCameraMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()
        setContentView(R.layout.activity_main)
        startMainSplashOverlay()
        findViewById<View>(R.id.mainSplashOverlay).post {
            initializeAfterSplashFirstFrame()
        }
    }

    private fun initializeAfterSplashFirstFrame() {
        if (isMainStartupInitialized || isMainStartupInitializing) return
        isMainStartupInitializing = true
        AppSettings.init(applicationContext)
        RoomRepository.init(applicationContext)
        eventMarkerManager.init(applicationContext)
        deviceHitMarkerManager.init(applicationContext)
        ensurePresenceAlgorithmVersion()

        yoloAnalyzer = YoloAnalyzer(this, overlayView)
        poseAnalyzer = YoloPoseAnalyzer(this) { results, bitmap, time ->
            Log.i(
                "RoomPoseUiDiag",
                "poseCallback results=${results.size} bitmapNull=${bitmap == null} " +
                    "bitmap=${bitmap?.width ?: -1}x${bitmap?.height ?: -1} timeMs=$time"
            )
            // 过滤有效目标
            val logicResults = results.filter { result ->
                val kpts = result.keypoints
                val shouldersTrusted = if (kpts.size > 6) {
                    val leftShoulder = kpts[5]
                    val rightShoulder = kpts[6]
                    leftShoulder.conf >= 0.7f &&
                        rightShoulder.conf >= 0.7f
                } else {
                    false
                }
                val isWeakTarget = !result.isConfirmed && !shouldersTrusted
                !isWeakTarget
            }

            val allRooms = RoomRepository.getAllRooms()
            val livingRoom = allRooms.find { it.isSovereignTerritory }
            
            // 重置瞬时人数
            allRooms.forEach { it.personCount = 0 }

            val personLocations = ArrayList<PointF>()

            for (pose in logicResults) {
                personLocations.add(pose.landingPoint)

                val currentRoom = findRoomForPoint(pose.landingPoint, allRooms)
                if (currentRoom != null) {
                    currentRoom.personCount++
                }
            }

            // Presence 估计：独立工具类统一处理“位置判定/房间切换事件/持久化人数”
            val presenceNowMs = videoFeeder?.peekLastAnalysisPositionMs()?.toLong() ?: -1L
            val observedTargets = results.map { pose ->
                val box = pose.box
                PresenceTrackObservation(
                    trackId = pose.id,
                    landingPoint = PresencePoint(
                        x = pose.landingPoint.x.toDouble(),
                        y = pose.landingPoint.y.toDouble()
                    ),
                    strength = toPresenceStrength(pose),
                    timestampMs = presenceNowMs,
                    groundConfidence = estimateGroundConfidence(pose),
                    personBox = PresenceRect(
                        left = minOf(box.left, box.right).toDouble(),
                        top = minOf(box.top, box.bottom).toDouble(),
                        right = maxOf(box.left, box.right).toDouble(),
                        bottom = maxOf(box.top, box.bottom).toDouble()
                    ),
                    keypoints = pose.keypoints.map { keypoint ->
                        PresenceKeypoint(
                            x = keypoint.x.toDouble(),
                            y = keypoint.y.toDouble(),
                            confidence = keypoint.conf.toDouble()
                        )
                    }
                )
            }
            val presenceResult = roomPresenceAlgorithm.processFrame(
                rooms = buildPresenceRoomSnapshots(allRooms),
                doors = buildPresenceDoorSnapshots(allRooms),
                observations = observedTargets,
                outsideMode = PresenceOutsideMode.INVISIBLE
            )
            val poseSwitchDisplayByTrackId = presenceResult.trackSwitchScores.mapNotNull { (trackId, hint) ->
                val type = when (hint.type) {
                    PresenceSwitchDisplayType.ENTER_SUB_ROOM -> EventType.ENTER
                    PresenceSwitchDisplayType.EXIT_SUB_ROOM -> EventType.EXIT
                    PresenceSwitchDisplayType.UNKNOWN -> null
                } ?: return@mapNotNull null
                trackId to (hint.score.toFloat().coerceIn(0f, 1f) to type)
            }.toMap()
            val poseSwitchDisplayForConfirmed = poseSwitchDisplayByTrackId.filterKeys { trackId ->
                results.any { it.id == trackId && it.isConfirmed }
            }
            val roomNameById = allRooms.associate { it.id to it.name }
            RoiLogAggregator.updatePresenceDebug(
                algoVersion = roomPresenceAlgorithm.runtimeTag,
                eventText = buildPresenceEventText(presenceResult.events, roomNameById),
                decisionText = toReadablePresenceDecision(
                    presenceResult.rejectedReasons.firstOrNull() ?: "NO_DECISION",
                    roomNameById
                ),
                countsText = buildPresenceCountsText(presenceResult.presenceCounts, roomNameById),
                posMs = videoFeeder?.peekLastAnalysisPositionMs()
            )
            allRooms.forEach { room ->
                room.persistentPersonCount = presenceResult.presenceCounts[room.id] ?: 0
            }
            roomPresenceChangeLogger.buildLogLineIfChanged(
                timestampMs = System.currentTimeMillis(),
                events = presenceResult.events,
                counts = presenceResult.presenceCounts,
                roomNameById = roomNameById
            )?.let { line ->
                Log.d("RoomPresence", "$line algo=${roomPresenceAlgorithm.runtimeTag}")
            }
            val livingRoomCount = livingRoom?.personCount ?: 0
            val livingPersistentCount = livingRoom?.persistentPersonCount ?: 0
            RoiLogAggregator.updateLivingRoomCounts(
                persistentCount = livingPersistentCount,
                currentCount = livingRoomCount
            )

            // ROI 计算
            val srcW = if (bitmap != null) bitmap.width else 1920
            val srcH = if (bitmap != null) bitmap.height else 1080
            val targetBox = if (logicResults.isNotEmpty()) logicResults[0].box else null
            val lockedPose = logicResults.firstOrNull { it.isConfirmed }
            val handRoiTarget = buildLockedHandRoiTarget(
                pose = lockedPose,
                imageWidth = srcW,
                imageHeight = srcH
            )
            val handTargetBox = handRoiTarget?.first
            val handTargetSizePx = handRoiTarget?.second

            if (targetBox != null) {
                roiMissingFrameCount = 0
            } else {
                roiMissingFrameCount++
            }
            if (handTargetBox != null) {
                handRoiMissingFrameCount = 0
            } else {
                handRoiMissingFrameCount++
            }

            val isSearching = roiMissingFrameCount >= 10
            val roi = if (isSearching) {
                roiTracker.resetSmoothing()
                null
            } else {
                roiTracker.calculate(srcW, srcH, targetBox)
            }
            val isHandSearching = handRoiMissingFrameCount >= 10
            val handRoi = if (isHandSearching) {
                handRoiTracker.resetSmoothing()
                null
            } else {
                handRoiTracker.calculate(srcW, srcH, handTargetBox, handTargetSizePx)
            }
            
            val cropRoi = if (AppSettings.isRoiRealCropEnabled && roi != null) roi else null
            videoFeeder?.nextFrameRoi = cropRoi
            videoFeeder?.nextHandFrameRoi = handRoi ?: cropRoi

            val isTracking = roiMissingFrameCount < 10 && targetBox != null
            val isHandTracking = handRoiMissingFrameCount < 10 && handTargetBox != null
            val isSparse = !AppSettings.isRoiRealCropEnabled
            
            val roiRatio = if (roi != null && targetBox != null) {
                val personW = targetBox.width() * srcW
                val personH = targetBox.height() * srcH
                val maxPersonSide = kotlin.math.max(personW, personH)
                val roiSize = kotlin.math.min(roi.width() * srcW, roi.height() * srcH)
                if (roiSize > 0f) maxPersonSide / roiSize else null
            } else {
                null
            }

            runOnUiThread {
                Log.i(
                    "RoomPoseUiDiag",
                    "poseUiUpdate logicResults=${logicResults.size} bitmapNull=${bitmap == null} " +
                        "bitmap=${bitmap?.width ?: -1}x${bitmap?.height ?: -1}"
                )
                val nowMs = currentVideoTimestampMs()
                val frameIndex = currentEstimatedFrameIndex(nowMs)
                val validationRuntimeEvents = appendRuntimeEventsForValidation(
                    events = presenceResult.events,
                    livingRoomId = livingRoom?.id,
                    roomNameById = roomNameById,
                    timestampMs = nowMs,
                    frameIndex = frameIndex
                )
                if (presenceResult.events.isNotEmpty()) {
                    val lastEvent = presenceResult.events.last()
                    val fromName = roomNameById[lastEvent.fromRoomId] ?: lastEvent.fromRoomId
                    val toName = roomNameById[lastEvent.toRoomId] ?: lastEvent.toRoomId
                    val switchType = mapPresenceEventType(lastEvent, livingRoom?.id ?: "")
                    val switchTypeLabel = switchType?.let { eventTypeLabel(it) } ?: "房间切换"
                    val switchOffsetText = if (switchType != null) {
                        val nearestOffset = nearestRuntimeDeltaMs(
                            runtime = RuntimeRoomEvent(
                                type = switchType,
                                frameIndex = frameIndex,
                                timestampMs = nowMs
                            ),
                            markedEvents = eventMarkerManager.getEvents()
                        )
                        nearestOffset?.let { formatSignedOffsetMs(it) } ?: "无可比事件"
                    } else {
                        "无可比事件"
                    }
                    val playStateBefore = currentPlayState
                    if (seekHoldActive &&
                        seekHoldDirection > 0 &&
                        currentPlayState == PlayState.STILL
                    ) {
                        stopSeekHold()
                        showCenterBanner(
                            "检测到房间切换($switchTypeLabel): $fromName->$toName，偏差=$switchOffsetText，已停止+1帧长按",
                            CenterBannerDomain.ROOM
                        )
                    }
                    val shouldAutoPause = AppSettings.isPauseOnRoomSwitchEnabled &&
                        playStateBefore != PlayState.PAUSED
                    var didAutoPause = false
                    if (shouldAutoPause) {
                        val pauseButton = findViewById<Button>(R.id.btnPause)
                        togglePause(pauseButton)
                        showCenterBanner(
                            "检测到房间切换($switchTypeLabel): $fromName->$toName，偏差=$switchOffsetText，已自动暂停",
                            CenterBannerDomain.ROOM
                        )
                        didAutoPause = true
                    }
                    if (AppSettings.isPauseDecisionLogOnSwitchEnabled) {
                        Log.i(
                            "RoomPauseSwitch",
                            "switch=$fromName->$toName@${lastEvent.doorId}:${lastEvent.reason} " +
                                "events=${presenceResult.events.size} " +
                                "pauseOnSwitch=${AppSettings.isPauseOnRoomSwitchEnabled} " +
                                "playStateBefore=$playStateBefore " +
                                "shouldAutoPause=$shouldAutoPause " +
                                "didAutoPause=$didAutoPause " +
                                "playStateAfter=$currentPlayState " +
                                "switchType=$switchTypeLabel " +
                                "offset=$switchOffsetText"
                        )
                    }
                }
                if (presenceResult.events.isEmpty()) {
                    val negativeDelta = extractNegativeCountDelta(
                        previousCounts = lastPresenceCountsForPause,
                        currentCounts = presenceResult.presenceCounts
                    )
                    if (negativeDelta.isNotEmpty()) {
                        val playStateBefore = currentPlayState
                        val shouldAutoPause = AppSettings.isPauseOnRoomSwitchEnabled &&
                            playStateBefore != PlayState.PAUSED
                        val likelyCause = resolveCountDeltaLikelyCause(presenceResult.rejectedReasons)
                        val deltaText = formatNegativeCountDelta(
                            delta = negativeDelta,
                            roomNameById = roomNameById
                        )
                        var didAutoPause = false
                        if (shouldAutoPause) {
                            val pauseButton = findViewById<Button>(R.id.btnPause)
                            togglePause(pauseButton)
                            showCenterBanner(
                                "检测到人数扣减($likelyCause): $deltaText，已自动暂停",
                                CenterBannerDomain.ROOM
                            )
                            didAutoPause = true
                        }
                        if (AppSettings.isPauseDecisionLogOnSwitchEnabled) {
                            Log.i(
                                "RoomPauseSwitch",
                                "switch=COUNT_DELTA_FALLBACK " +
                                    "events=0 " +
                                    "pauseOnSwitch=${AppSettings.isPauseOnRoomSwitchEnabled} " +
                                    "playStateBefore=$playStateBefore " +
                                    "shouldAutoPause=$shouldAutoPause " +
                                    "didAutoPause=$didAutoPause " +
                                    "playStateAfter=$currentPlayState " +
                                    "deltaMap=$deltaText " +
                                    "likelyCause=$likelyCause"
                            )
                        }
                    }
                }
                val anomalyReason = presenceResult.rejectedReasons.firstOrNull { reason ->
                    reason.contains("identityResetApplied=true") ||
                        reason.contains("pendingDisabled=true") ||
                        reason.contains("pendingDropped=true") ||
                        reason.contains("ledgerBlockApplied=true")
                }
                if (anomalyReason != null) {
                    logPresenceAnomalyDiagnostics(
                        reason = anomalyReason,
                        frameIndex = frameIndex,
                        nowMs = nowMs
                    )
                }
                lastPresenceCountsForPause = presenceResult.presenceCounts.toMap()
                maybeRunSmartMatchValidation(
                    runtimeEvents = validationRuntimeEvents,
                    nowMs = nowMs
                )
                overlayView.updatePoseData(
                    results = results,
                    bitmap = bitmap,
                    timeMs = time,
                    switchHints = poseSwitchDisplayForConfirmed
                )
                overlayView.postInvalidate() 
                tvRoomCount.text = getString(R.string.room_people_count, livingRoomCount)
                
                if (flRadarContainer.visibility == View.VISIBLE) {
                    tacticalMapView.updateData(allRooms, personLocations)
                }

                overlayView.updateRoiBox(roi, isTracking, isSparse)
                overlayView.updateHandRoiBox(handRoi, isHandTracking)
                overlayView.setRoiRatio(roiRatio)
                RoiLogAggregator.updateHumanRoiRatio(roiRatio)
                poseAnalyzer?.consumeUnlockMessage()?.let { msg ->
                    Log.i("RoomLockDiag", "ui_consume $msg")
                    showCenterBanner(msg, CenterBannerDomain.ROOM)
                    tryCaptureUnlockDebugToClipboard(msg)
                }
                refreshEventMarkerUi()
            }
        }

        videoFeeder = VideoFeeder(this, textureView).apply {
            this.yoloAnalyzer = this@MainActivity.yoloAnalyzer
            this.poseAnalyzer = this@MainActivity.poseAnalyzer
            this.handSmokeTester = HandSmokeTester(this@MainActivity).also {
                this@MainActivity.handSmokeTester = it
                it.onHandsResult = { hands, selectedIndex ->
                    latestHandResults = hands
                    latestSelectedHandIndex = selectedIndex?.takeIf { index -> index in hands.indices }
                    runOnUiThread {
                        if (isHandObserveMode()) {
                            overlayView.updateHandData(hands, null)
                            updateHandTranslationControl(hands)
                        } else {
                            overlayView.updateHandData(hands, latestSelectedHandIndex)
                        }
                    }
                }
                it.onPointingObservation = { observation ->
                    handleTriggeredPointingObservation(observation)
                }
            }
            this.onStepNudge = { msg ->
                showCenterBanner(msg, CenterBannerDomain.ROOM)
            }
        }

        setupButtons()
        bindKwsCommandRelay()
        syncRuntimeAnalyzerMode()
        refreshEventMarkerUi()
        checkPermissionsAndStart()
        refreshOverlayDisplay()
        isMainStartupInitialized = true
        isMainStartupInitializing = false
        hideMainSplashOverlayWhenReady()
    }

    private fun startMainSplashOverlay() {
        val overlay = findViewById<View>(R.id.mainSplashOverlay)
        splashShownAtMs = SystemClock.uptimeMillis()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 1f
        startMainSplashLoading()
    }

    private fun hideMainSplashOverlayWhenReady() {
        val overlay = findViewById<View>(R.id.mainSplashOverlay)
        val elapsedMs = SystemClock.uptimeMillis() - splashShownAtMs
        val delayMs = (MAIN_SPLASH_MIN_DURATION_MS - elapsedMs).coerceAtLeast(0L)
        splashHideHandler.postDelayed({
            overlay.animate()
                .alpha(0f)
                .setDuration(260L)
                .withEndAction {
                    overlay.visibility = View.GONE
                    splashLoadingAnimator?.cancel()
                    splashLoadingAnimator = null
                }
                .start()
        }, delayMs)
    }

    private fun startMainSplashLoading() {
        val dots = listOf<View>(
            findViewById(R.id.mainSplashDotOne),
            findViewById(R.id.mainSplashDotTwo),
            findViewById(R.id.mainSplashDotThree)
        )
        splashLoadingAnimator?.cancel()
        splashLoadingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                dots.forEachIndexed { index, dot ->
                    val phase = ((progress * dots.size) - index).coerceIn(0f, 1f)
                    val pulse = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                    dot.alpha = 0.35f + 0.65f * pulse
                    val scale = 0.78f + 0.34f * pulse
                    dot.scaleX = scale
                    dot.scaleY = scale
                }
            }
            start()
        }
    }

    private fun buildLockedHandRoiTarget(
        pose: com.example.roomxxx0102.data.model.PoseResult?,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<RectF, Float>? {
        if (pose == null || !pose.isConfirmed) return null
        if (pose.keypoints.size <= 10) return null

        val leftWrist = pose.keypoints[9]
        val rightWrist = pose.keypoints[10]
        val wrist = when {
            leftWrist.conf > 0f && rightWrist.conf > 0f -> {
                if (leftWrist.y <= rightWrist.y) leftWrist else rightWrist
            }
            leftWrist.conf > 0f -> leftWrist
            rightWrist.conf > 0f -> rightWrist
            else -> return null
        }

        val minImageSide = kotlin.math.min(imageWidth, imageHeight).toFloat().coerceAtLeast(224f)
        val bodyShortSidePx = kotlin.math.min(
            pose.box.width() * imageWidth,
            pose.box.height() * imageHeight
        )
        val roiSidePx = kotlin.math.max(224f, bodyShortSidePx).coerceAtMost(minImageSide)
        val halfWidthNorm = roiSidePx / imageWidth / 2f
        val halfHeightNorm = roiSidePx / imageHeight / 2f
        return RectF(
            wrist.x - halfWidthNorm,
            wrist.y - halfHeightNorm,
            wrist.x + halfWidthNorm,
            wrist.y + halfHeightNorm
        ) to roiSidePx
    }

    private fun findRoomForPoint(point: PointF, rooms: List<RoomConfig>): RoomConfig? {
        val subRoom = rooms.firstOrNull {
            !it.isSovereignTerritory && it.boundaryPoints.size >= 3 && GeometryUtils.isPointInPolygon(point, it.boundaryPoints)
        }
        if (subRoom != null) return subRoom
        return rooms.firstOrNull {
            it.isSovereignTerritory && it.boundaryPoints.size >= 3 && GeometryUtils.isPointInPolygon(point, it.boundaryPoints)
        }
    }

    /**
     * 将当前 Pose 目标映射为 Presence 模块的强度分层。
     * - CONFIRMED：已 lock
     * - STRONG：未 lock 但双肩可信
     * - WEAK：其余目标
     */
    private fun toPresenceStrength(pose: com.example.roomxxx0102.data.model.PoseResult): PresenceStrength {
        if (pose.isConfirmed) {
            return PresenceStrength.CONFIRMED
        }
        val kpts = pose.keypoints
        val shouldersTrusted = if (kpts.size > 6) {
            val leftShoulder = kpts[5]
            val rightShoulder = kpts[6]
            leftShoulder.conf >= 0.7f &&
                rightShoulder.conf >= 0.7f
        } else {
            false
        }
        return if (shouldersTrusted) PresenceStrength.STRONG else PresenceStrength.WEAK
    }

    /**
     * 估算地面落点可信度（A_conf）。
     *
     * 说明：
     * 1) 优先依赖脚踝关键点置信度。
     * 2) 脚踝弱时，退化参考 lock 状态与双肩可信度。
     * 3) 该值仅用于 Presence 进入评分，不影响现有框绘制与 lock 逻辑。
     */
    private fun estimateGroundConfidence(pose: com.example.roomxxx0102.data.model.PoseResult): Double {
        val kpts = pose.keypoints
        if (kpts.size < 17) return 0.25

        val leftAnkle = kpts[15].conf
        val rightAnkle = kpts[16].conf
        val bothAnklesHigh = leftAnkle >= 0.50f && rightAnkle >= 0.50f
        val oneAnkleHigh = leftAnkle >= 0.50f || rightAnkle >= 0.50f
        val oneAnkleMedium = leftAnkle >= 0.20f || rightAnkle >= 0.20f

        val shouldersTrusted = if (kpts.size > 6) {
            val leftShoulder = kpts[5]
            val rightShoulder = kpts[6]
            leftShoulder.conf >= 0.7f &&
                rightShoulder.conf >= 0.7f
        } else {
            false
        }

        val conf = when {
            bothAnklesHigh -> 1.00
            oneAnkleHigh -> 0.85
            oneAnkleMedium -> 0.65
            pose.isConfirmed && shouldersTrusted -> 0.50
            shouldersTrusted -> 0.40
            else -> 0.25
        }
        return conf.coerceIn(0.0, 1.0)
    }

    /**
     * 构建 Presence 房间快照。
     * 说明：
     * 1) 盲区房间按不可视处理（polygon 传空），避免被粗判直接命中。
     * 2) 非盲区房间只有 >=3 点时才视为可视 polygon。
     */
    private fun buildPresenceRoomSnapshots(rooms: List<RoomConfig>): List<PresenceRoomSnapshot> {
        return rooms.map { room ->
            val polygon = if (!room.isLivingBlindZone && room.boundaryPoints.size >= 3) {
                room.boundaryPoints.map { p ->
                    PresencePoint(p.x.toDouble(), p.y.toDouble())
                }
            } else {
                emptyList()
            }
            PresenceRoomSnapshot(
                roomId = room.id,
                roomName = room.name,
                polygon = polygon,
                isLivingRoom = room.isSovereignTerritory,
                isBlindZone = room.isLivingBlindZone,
                isEntranceRoom = room.isEntranceDoor
            )
        }
    }

    /**
     * 基于“客厅边ID + 子房间 occupiedWallIds”构建门线快照。
     * 每条门线连接：客厅 <-> 子房间。
     */
    private fun buildPresenceDoorSnapshots(rooms: List<RoomConfig>): List<PresenceDoorSnapshot> {
        val livingRoom = rooms.find { it.isSovereignTerritory } ?: return emptyList()
        val vertices = livingRoom.boundaryVertices
        if (vertices.size < 2) return emptyList()

        val doors = mutableListOf<PresenceDoorSnapshot>()
        val subRooms = rooms.filter { !it.isSovereignTerritory }
        for (room in subRooms) {
            for (edgeId in room.occupiedWallIds.distinct()) {
                val index = vertices.indexOfFirst { it.id == edgeId }
                if (index == -1) continue
                val a = vertices[index].point
                val b = vertices[(index + 1) % vertices.size].point
                doors.add(
                    PresenceDoorSnapshot(
                        doorId = "${room.id}#$edgeId",
                        a = PresencePoint(a.x.toDouble(), a.y.toDouble()),
                        b = PresencePoint(b.x.toDouble(), b.y.toDouble()),
                        roomAId = livingRoom.id,
                        roomBId = room.id,
                        isEntranceDoor = room.isEntranceDoor
                    )
                )
            }
        }
        return doors
    }

    private fun buildPointingDeviceTargets(): Pair<List<DevicePointingTarget>, Map<String, String>> {
        val devices = RoomRepository.getDevices()
        val targets = devices.mapNotNull { device ->
            if (device.polygon.size != 4) return@mapNotNull null
            DevicePointingTarget(
                id = device.id,
                hotspot = PointF(device.hotspot.x, device.hotspot.y),
                polygon = device.polygon.map { PointF(it.x, it.y) }
            )
        }
        val labels = devices.associate { device ->
            device.id to device.name
        }
        return targets to labels
    }

    private fun startTriggeredPointingSession(
        preRollMs: Long = 0L,
        centerTimestampMs: Long = SystemClock.uptimeMillis()
    ): PointingDecision? {
        val bitmap = textureView.bitmap
        if (bitmap == null) {
            cancelPendingVoiceTimeout()
            if (pendingVoicePointingFeedback) {
                showCenterBanner("指向识别启动失败", CenterBannerDomain.DEVICE)
            }
            pendingVoicePointingFeedback = false
            pendingVoiceCommandToken = null
            return null
        }
        val (targets, labels) = buildPointingDeviceTargets()
        pointingTargetLabelById = labels
        val startTimestampMs = (centerTimestampMs - preRollMs).coerceAtLeast(0L)
        pointingResolver.startSession(targets, bitmap.width, bitmap.height, startTimestampMs)
        val replayDecision = if (preRollMs > 0L) {
            replayRecentPointingObservations(startTimestampMs, centerTimestampMs)
        } else {
            null
        }
        Log.i(
            "DevicePointingJudge",
            "DEVICE_POINTING|START|targets=${targets.joinToString { "${it.id}:hot=${String.format(Locale.US, "%.3f", it.hotspot.x)},${String.format(Locale.US, "%.3f", it.hotspot.y)}" }}"
        )
        return replayDecision
    }

    private fun handleTriggeredPointingObservation(observation: HandObservation) {
        rememberPointingObservation(observation)
        if (!pointingResolver.isActive()) {
            if (isHandObserveMode()) {
                startTriggeredPointingSession()
            } else {
                return
            }
        }
        if (!pointingResolver.isActive()) return
        val decision = pointingResolver.submitFrame(observation)
        val latestSnapshot = pointingResolver.latestDebugSnapshot()
        val liveSnapshot = if (shouldShowLivePointingDebug()) {
            latestSnapshot?.takeIf { snapshot ->
                snapshot.smoothedOrigin != null &&
                    snapshot.smoothedDir != null &&
                    snapshot.frameQuality >= pointingGuideMinQuality
            }
        } else {
            null
        }
        runOnUiThread {
            overlayView.updatePointingPanelSnapshot(latestSnapshot)
            overlayView.updatePointingLiveSnapshot(liveSnapshot)
        }
        if (decision !is PointingDecision.Pending) {
            runOnUiThread {
                handleTriggeredPointingDecision(decision)
                if (isHandObserveMode()) {
                    startTriggeredPointingSession()
                }
            }
        }
    }

    private fun handleTriggeredPointingDecision(decision: PointingDecision) {
        cancelPendingVoiceTimeout()
        when (decision) {
            is PointingDecision.Pending -> Unit
            is PointingDecision.Recognized -> {
                val label = pointingTargetLabelById[decision.targetId] ?: decision.targetId
                val confidenceText = when (decision.diagnostics.confidenceStatus) {
                    PointingConfidenceStatus.HIGH_CONFIDENCE -> "高置信度"
                    PointingConfidenceStatus.LOW_CONFIDENCE -> "低置信度"
                    PointingConfidenceStatus.UNDETERMINED -> "未定"
                }
                val message = "命中：$label [$confidenceText] (${String.format(Locale.US, "%.2f", decision.score)})"
                if (pendingVoicePointingFeedback) {
                    showCenterBanner(message, CenterBannerDomain.DEVICE)
                }
                Log.i(
                    "DevicePointingJudge",
                    "DEVICE_POINTING|RECOGNIZED|target=${decision.targetId}|label=$label|score=${String.format(Locale.US, "%.3f", decision.score)}|elapsed=${decision.elapsedMs}|path=${decision.diagnostics.acceptPath}|confidence=${decision.diagnostics.confidenceStatus}|lead=${String.format(Locale.US, "%.3f", decision.diagnostics.finalLeadRatio)}|threshold=${String.format(Locale.US, "%.3f", decision.diagnostics.dynamicFinalThreshold)}|top3=${decision.diagnostics.top3Targets}"
                )
                overlayView.updatePointingDebugSnapshot(
                    pointingResolver.latestDebugSnapshot(),
                    holdMs = 500L
                )
                pendingVoiceCommandToken?.let { token ->
                    val deviceElapsedMs = (decision.elapsedMs - pointingReplayHistoryWindowMs).coerceAtLeast(0L)
                    latestKwsAudioLogUpdate.value = AudioCommandLogUpdate(
                        token = token,
                        summarySuffix = "设备 ${deviceElapsedMs}ms",
                        detailSuffix = "设备命中耗时: ${deviceElapsedMs}ms"
                    )
                }
                pendingVoiceCommandToken = null
                pendingVoicePointingFeedback = false
            }
            is PointingDecision.Unrecognized -> {
                val message = "设备未命中"
                Log.i(
                    "DevicePointingJudge",
                    "DEVICE_POINTING|UNRECOGNIZED|reason=${decision.reason}|score=${String.format(Locale.US, "%.3f", decision.score)}|elapsed=${decision.elapsedMs}|path=${decision.diagnostics.acceptPath}|confidence=${decision.diagnostics.confidenceStatus}|lead=${String.format(Locale.US, "%.3f", decision.diagnostics.finalLeadRatio)}|threshold=${String.format(Locale.US, "%.3f", decision.diagnostics.dynamicFinalThreshold)}|top3=${decision.diagnostics.top3Targets}"
                )
                if (pendingVoicePointingFeedback) {
                    showCenterBanner(message, CenterBannerDomain.DEVICE, persistentHandBannerDurationMs)
                }
                if (pendingVoicePointingFeedback && isHandObserveMode()) {
                    overlayView.updatePointingDebugSnapshot(
                        buildTop3FailureSnapshot(pointingResolver.latestDebugSnapshot()),
                        holdMs = 0L
                    )
                }
                pendingVoiceCommandToken?.let { token ->
                    val deviceElapsedMs = (decision.elapsedMs - pointingReplayHistoryWindowMs).coerceAtLeast(0L)
                    latestKwsAudioLogUpdate.value = AudioCommandLogUpdate(
                        token = token,
                        summarySuffix = "未命中 ${deviceElapsedMs}ms",
                        detailSuffix = "设备未命中耗时: ${deviceElapsedMs}ms"
                    )
                }
                pendingVoiceCommandToken = null
                if (pendingVoicePointingFeedback &&
                    AppSettings.isPauseOnVoiceRecognizeFailEnabled &&
                    isVideoMode &&
                    currentPlayState != PlayState.STILL
                ) {
                    val pauseButton = findViewById<Button>(R.id.btnPause)
                    togglePause(pauseButton)
                }
                pendingVoicePointingFeedback = false
            }
        }
    }

    private fun buildTop3FailureSnapshot(snapshot: PointingDebugSnapshot?): PointingDebugSnapshot? {
        if (snapshot == null) return null
        val topIds = snapshot.top3Targets.map { it.first }.toSet()
        if (topIds.isEmpty()) return snapshot.copy(targets = emptyList())
        return snapshot.copy(
            targets = snapshot.targets.filter { it.id in topIds }
        )
    }

    private fun buildPresenceEventText(
        events: List<com.example.roomxxx0102.logic.presence.PresenceSwitchEvent>,
        roomNameById: Map<String, String>
    ): String {
        val event = events.lastOrNull() ?: return "-"
        val fromName = roomNameById[event.fromRoomId] ?: event.fromRoomId
        val toName = roomNameById[event.toRoomId] ?: event.toRoomId
        return "$fromName->$toName (${event.reason})"
    }

    private fun buildPresenceCountsText(
        counts: Map<String, Int>,
        roomNameById: Map<String, String>
    ): String {
        val nonZero = counts
            .filterValues { it > 0 }
            .map { (roomId, value) ->
                val roomName = roomNameById[roomId] ?: roomId
                roomName to value
            }
            .sortedBy { it.first }
        if (nonZero.isEmpty()) return "{}"
        return nonZero.joinToString(prefix = "{", postfix = "}") { "${it.first}:${it.second}" }
    }

    private fun toReadablePresenceDecision(
        raw: String,
        roomNameById: Map<String, String>
    ): String {
        var text = raw
        val sortedIds = roomNameById.keys.sortedByDescending { it.length }
        for (id in sortedIds) {
            val name = roomNameById[id] ?: continue
            text = text.replace(id, name)
        }
        // 门ID一般包含房间UUID，调试面板里去掉可读性更高。
        text = text.replace(Regex("door=[^\\s]+\\s*"), "")
        // 去掉 track ID，避免阅读时干扰。
        text = text.replace(Regex("track=\\d+\\s*"), "")
        val shortKeyMap = linkedMapOf(
            "from" to "f",
            "to" to "t",
            "frames" to "fr",
            "mode" to "md",
            "exitRule" to "er",
            "lowConf" to "lc",
            "scoreGap" to "sg",
            "doorDist" to "dd",
            "doorProximityScore" to "dps",
            "groundPointConfidence" to "gpc",
            "doorEvidenceScore" to "des",
            "doorProximityScoreEff" to "dpe",
            "targetRoomContainmentRatio" to "trc",
            "sourceRoomContainmentRatio" to "src",
            "sourceRoomOutsidePoseScore" to "sops",
            "exitOutsidePoseScoreThreshold" to "sopsTh",
            "poseAverageConfidence" to "pac",
            "sourceRoomStayScore" to "srss",
            "poseTransitionScore" to "pts",
            "doorAssistScore" to "das",
            "switchConfidenceScore" to "scs",
            "switchScore" to "ss",
            "evidenceScore" to "e",
            "evidenceThreshold" to "eth",
            "switchThreshold" to "scsTh",
            "exitSourceRoomScoreThreshold" to "srssTh",
            "dynamicNearDist" to "dnd",
            "nearDist" to "nd",
            "dpsRaw" to "dpsR",
            "dpsFinal" to "dpsF",
            "dpsEnter" to "dpsE",
            "enterDpsGamma" to "edg",
            "dpsTailRatio" to "dpsTr",
            "poseEffectiveConfidence" to "pacE",
            "insideScore" to "ins",
            "inwardTrendScore" to "itr",
            "passBySuppress" to "pbs",
            "crossScore" to "crs",
            "enterPhase" to "eph",
            "enterProxFactor" to "epf",
            "enterProxFloor" to "epfl",
            "enterCrossPart" to "ecp",
            "enterSwitchScore" to "ess",
            "enterInsideScoreRef" to "sRef",
            "enterInwardTrendRef" to "vRef",
            "enterPassByRef" to "rRef",
            "doorAdvanceDelta" to "dad",
            "doorLateralDelta" to "dld",
            "doorAdvanceLateralRatio" to "dalr",
            "doorAdvanceDeltaShort" to "dadS",
            "doorAdvanceDeltaLong" to "dadL",
            "motionWindowUsed" to "mwu",
            "exitDpsHoldApplied" to "xvdh",
            "poseGate" to "pg",
            "clearGate" to "cg",
            "nearGateForPose" to "ngp",
            "doorScoreGap" to "dsg",
            "candidate" to "cand",
            "prevCandidate" to "pc",
            "bestScore" to "bsc",
            "secondScore" to "ssc",
            "prevScore" to "psc",
            "bestMinusPrev" to "bmp",
            "stickApplied" to "stk",
            "stickReason" to "stkr",
            "fallbackFrozen" to "fbf",
            "bounceApplied" to "bap",
            "bounceDtMs" to "bdt",
            "bounceFactor" to "bfac",
            "lastSwitch" to "lsw",
            "ssBeforeAfter" to "ssba",
            "exitPoseMinConfidence" to "pacMin",
            "poseHardRejectMinConfidence" to "phrMin",
            "grayPoseMinConfidence" to "gpm",
            "enterVisibleRequireNearDoor" to "evnR",
            "enterVisibleRequireContainment" to "evcR",
            "exitVisibleRequireNearDoor" to "xvnR",
            "enterVisibleRequireDoorEvidence" to "evdeR",
            "enterVisibleDoorEvidencePass" to "evdeP",
            "enterVisibleDoorAssistMin" to "evdaM",
            "visibleExitPoseTransitionModel" to "xvpm",
            "allowVisibleExitPolygonRecovery" to "xvpr"
        )
        for ((longKey, shortKey) in shortKeyMap) {
            text = text.replace("$longKey=", "$shortKey=")
        }

        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return "-"

        val reason = tokens.first()
        val kv = linkedMapOf<String, String>()
        for (token in tokens.drop(1)) {
            val idx = token.indexOf('=')
            if (idx <= 0 || idx >= token.length - 1) continue
            val key = token.substring(0, idx).trim()
            val value = token.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                kv[key] = value
            }
        }
        if (kv.isEmpty()) return reason

        val headerKeys = listOf("f", "t", "fr", "md", "er", "lc", "sg")
        val headValues = headerKeys.map { key -> kv[key] ?: "-" }

        val metricKeys = listOf(
            "dd", "dps", "gpc", "des", "dpe", "trc", "src", "sops",
            "pac", "srss", "pts", "das", "scs", "ss", "e", "eth",
            "scsTh", "srssTh", "sopsTh", "dnd", "nd", "dpsR", "dpsF", "dpsE", "dpsTr",
            "pacE", "ins", "itr", "pbs", "crs", "eph", "epf", "epfl", "ecp", "ess", "sRef", "vRef", "rRef",
            "dad", "dld", "dalr", "dadS", "dadL", "bsc", "ssc", "psc", "bmp",
            "pg", "cg", "ngp", "dsg", "bdt", "bfac"
        )
        val metrics = metricKeys.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        ) { key ->
            kv[key] ?: "-"
        }

        val extraKeys = listOf(
            "pacMin", "phrMin", "gpm", "edg", "mwu", "evnR", "evcR", "xvnR",
            "evdeR", "evdeP", "evdaM", "xvdh", "xvpm", "xvpr",
            "cand", "pc", "stk", "stkr", "fbf", "bap", "lsw", "ssba"
        )
        val extraValues = extraKeys.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        ) { key ->
            kv[key] ?: "-"
        }

        return buildString {
            append(reason)
            append("|h[")
            append(headValues.joinToString(","))
            append("]|m")
            append(metrics)
            append("|x")
            append(extraValues)
        }
    }

    private fun buildPresenceShortKeyLegend(): String {
        return "h=[f,t,fr,md,er,lc,sg] m=[dd,dps,gpc,des,dpe,trc,src,sops,pac,srss,pts,das,scs,ss,e,eth,scsTh,srssTh,sopsTh,dnd,nd,dpsR,dpsF,dpsE,dpsTr,pacE,ins,itr,pbs,crs,eph,epf,epfl,ecp,ess,sRef,vRef,rRef,dad,dld,dalr,dadS,dadL,bsc,ssc,psc,bmp,pg,cg,ngp,dsg,bdt,bfac] x=[pacMin,phrMin,gpm,edg,mwu,evnR,evcR,xvnR,evdeR,evdeP,evdaM,xvdh,xvpm,xvpr,cand,pc,stk,stkr,fbf,bap,lsw,ssba]"
    }

    private fun extractNegativeCountDelta(
        previousCounts: Map<String, Int>?,
        currentCounts: Map<String, Int>
    ): Map<String, Int> {
        val previous = previousCounts ?: return emptyMap()
        val keys = linkedSetOf<String>()
        keys.addAll(previous.keys)
        keys.addAll(currentCounts.keys)
        val delta = linkedMapOf<String, Int>()
        for (roomId in keys) {
            val old = previous[roomId] ?: 0
            val now = currentCounts[roomId] ?: 0
            val d = now - old
            if (d < 0) {
                delta[roomId] = d
            }
        }
        return delta
    }

    private fun formatNegativeCountDelta(
        delta: Map<String, Int>,
        roomNameById: Map<String, String>
    ): String {
        return delta.entries
            .sortedBy { roomNameById[it.key] ?: it.key }
            .joinToString(separator = ",") { entry ->
                val roomName = roomNameById[entry.key] ?: entry.key
                "$roomName:${entry.value}"
            }
    }

    private fun resolveCountDeltaLikelyCause(rejectedReasons: List<String>): String {
        val identityLine = rejectedReasons.firstOrNull { it.contains("identityResetApplied=true") }
        if (identityLine != null) {
            val resetReason = Regex("resetReason=([^\\s]+)")
                .find(identityLine)
                ?.groupValues
                ?.getOrNull(1)
                ?: "UNKNOWN"
            val trackId = Regex("track=([-\\d]+)")
                .find(identityLine)
                ?.groupValues
                ?.getOrNull(1)
                ?: "-"
            val gapFrames = Regex("gapFrames=([-\\d]+)")
                .find(identityLine)
                ?.groupValues
                ?.getOrNull(1)
                ?: "-"
            val jumpDist = Regex("jumpDist=([-\\d.]+)")
                .find(identityLine)
                ?.groupValues
                ?.getOrNull(1)
                ?: "-"
            return "IDENTITY_RESET:$resetReason(track=$trackId,gap=$gapFrames,jump=$jumpDist)"
        }
        if (rejectedReasons.any { it.contains("pendingDropped=true") || it.contains("pendingDisabled=true") }) {
            return "PENDING_DROP"
        }
        if (rejectedReasons.any { it.contains("ledgerBlockApplied=true") }) {
            return "LEDGER_BLOCK"
        }
        return "UNKNOWN"
    }

    private fun logPresenceAnomalyDiagnostics(
        reason: String,
        frameIndex: Int,
        nowMs: Long
    ) {
        if (!AppSettings.isPauseDecisionLogOnSwitchEnabled) return
        val presenceHistory = RoiLogAggregator.snapshotPresenceHistory(8)
        val recentFrames = RoiLogAggregator.snapshotRecentFrames(8)
        val dedupeKey = buildString {
            append(frameIndex)
            append('|')
            append(reason)
            append('|')
            append(presenceHistory.lastOrNull() ?: "-")
        }
        if (dedupeKey == lastPresenceAnomalyDumpKey) return
        lastPresenceAnomalyDumpKey = dedupeKey

        Log.i(
            "RoomPauseSwitch",
            "presenceAnomaly frame=$frameIndex posMs=$nowMs reason=$reason " +
                "presenceRecentCount=${presenceHistory.size} recentFrameCount=${recentFrames.size}"
        )
        presenceHistory.forEach { line ->
            Log.i("RoomPauseSwitch", "presenceRecent $line")
        }
        recentFrames.forEach { line ->
            Log.i("RoomPauseSwitch", "recentFrame $line")
        }
    }

    private fun setupButtons() {
        val btnPause = findViewById<Button>(R.id.btnPause)
        val btnRewind = findViewById<Button>(R.id.btnRewind)
        val btnForward = findViewById<Button>(R.id.btnForward)
        val btnMarkEnterEvent = findViewById<Button>(R.id.btnMarkEnterEvent)
        val btnMarkExitEvent = findViewById<Button>(R.id.btnMarkExitEvent)
        val btnJumpNextEvent = findViewById<Button>(R.id.btnJumpNextEvent)
        val btnDeleteCurrentEvent = findViewById<Button>(R.id.btnDeleteCurrentEvent)
        btnHandOverlay = findViewById(R.id.btnHandOverlay)
        btnPause.setOnClickListener { togglePause(it as Button) }
        btnPause.setOnLongClickListener {
            hardRestartPlayback()
            true
        }
        btnRewind.setOnClickListener { onSeekBackwardRequested() }
        btnForward.setOnClickListener { onSeekForwardRequested() }
        refreshPlayStateButton(btnPause)
        btnRewind.setOnTouchListener(createSeekHoldTouchListener(direction = -1))
        btnForward.setOnTouchListener(createSeekHoldTouchListener(direction = 1))
        val btnDebugPanel = findViewById<Button>(R.id.btnDebugPanel)
        btnDebugPanel.setOnClickListener {
            isDebugPanelEnabled = !isDebugPanelEnabled
            if (!isDebugPanelEnabled) {
                isAwaitingDeviceHitSelection = false
                pendingDeviceHitTimestampMs = null
                pendingDeviceHitFrameIndex = null
                syncDeviceHitSelectionUi()
            }
            overlayView.setDebugPanelEnabled(isDebugPanelEnabled)
            refreshDebugPanelButton()
            refreshEventMarkerUi()
        }
        btnDebugPanel.setOnLongClickListener {
            val report = buildDebugPanelClipboardReport(System.currentTimeMillis())
            val copied = copyTextToClipboard("debug_panel_report", report)
            if (copied) {
                Toast.makeText(this, "已复制调试面板信息", Toast.LENGTH_SHORT).show()
            }
            true
        }
        btnHandOverlay?.setOnClickListener {
            cycleObserveMode()
        }
        findViewById<Button>(R.id.btnRuntimeMode).setOnClickListener {
            toggleRuntimeMode()
        }
        overlayView.setOnUnlockBannerLongPressListener {
            onValidationBannerLongPressed()
        }
        overlayView.setOnDeviceTapListener { device ->
            onDeviceTappedForMarker(device)
        }
        overlayView.setOnDeviceSelectionCancelListener {
            cancelDeviceHitSelection()
        }
        btnMarkEnterEvent.setOnClickListener {
            if (isHandDebugMarkerMode()) {
                armDeviceHitSelection()
            } else {
                addMarkedEvent(EventType.ENTER)
            }
        }
        btnMarkExitEvent.setOnClickListener {
            if (!isHandDebugMarkerMode()) {
                addMarkedEvent(EventType.EXIT)
            }
        }
        btnJumpNextEvent.setOnClickListener {
            if (isHandDebugMarkerMode()) {
                jumpToNextDeviceHitEvent()
            } else {
                jumpToNextMarkedEvent()
            }
        }
        btnDeleteCurrentEvent.setOnClickListener {
            if (isHandDebugMarkerMode()) {
                confirmDeleteCurrentDeviceHitEvents()
            } else {
                confirmDeleteCurrentMarkedEvents()
            }
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            captureCurrentFrame()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        refreshSeekButtons()
        refreshDebugPanelButton()

        findViewById<Button>(R.id.btnSetupRoom).setOnClickListener {
            Toast.makeText(this, "SISP Core 未启动，切换至手动简易房间编辑模式", Toast.LENGTH_SHORT).show()
            enterEditMode()
        }
        refreshRuntimeModeButton()
        
        findViewById<Button>(R.id.btnRadar).setOnClickListener {
            flRadarContainer.visibility = View.VISIBLE
            llNormalControls.visibility = View.GONE
            llRightActionControls.visibility = View.GONE
            cardCounter.visibility = View.GONE
            if (!RoomRepository.hasMeaningfulConfig()) {
                Toast.makeText(
                    this,
                    "无房间信息，请下发房间户型信息或手动配置房间户型，当前仅显示人体识别。",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        
        btnCloseRadar.setOnClickListener {
            flRadarContainer.visibility = View.GONE
            llNormalControls.visibility = View.VISIBLE
            llRightActionControls.visibility = View.VISIBLE
            cardCounter.visibility = View.GONE
        }
        
        // 🔥 关闭编辑按钮 (通用)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { 
            exitEditMode(save = false) 
        }
        
        // 🔥 初始化 Spinner
        spnEditMode = findViewById(R.id.spnEditMode)
        // 🔥 使用自定义布局 spinner_item_dark
        editModeAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item_dark,
            buildEditModeLabels().toMutableList()
        )
        // 设置下拉列表的 item 样式 (可以使用 android.R.layout.simple_spinner_dropdown_item, 因为背景是 dark theme)
        editModeAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spnEditMode?.adapter = editModeAdapter
        
        spnEditMode?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (llEditorControls.visibility == View.VISIBLE) {
                    when (position) {
                        0 -> applyModeSelection(LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL)
                        1 -> applyModeSelection(LivingRoomEditorView.EditorMode.SUB_ROOM_ANCHOR)
                        2 -> enterDeviceSettingsMode()
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            if (editorMenuState == EditorMenuState.SUBROOM_AREA_EDIT) {
                editorView.restoreRegionEdit()
            }
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            if (editorMenuState == EditorMenuState.SUBROOM_AREA_EDIT) {
                val roomId = editorView.getRegionEditRoomId()
                val room = RoomRepository.getSubRooms().firstOrNull { it.id == roomId }
                if (room != null) {
                    room.boundaryVertices.clear()
                    RoomRepository.updateRoom(room)
                }
                editorView.deleteRegionEdit()
                editorView.endSubRoomRegionEdit()
                refreshOverlayDisplay()
                transitionTo(EditorMenuState.SUBROOM_SELECTED)
            } else {
                editorView.clear()
            }
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            when (editorMenuState) {
                EditorMenuState.SUBROOM_DOOR_SELECT -> {
                    editorView.clearPendingDoorSelection()
                    transitionTo(EditorMenuState.SUBROOM_SELECTED)
                }
                EditorMenuState.SUBROOM_AREA_EDIT -> {
                    editorView.endSubRoomRegionEdit()
                    refreshOverlayDisplay()
                    transitionTo(EditorMenuState.SUBROOM_SELECTED)
                }
                EditorMenuState.SUBROOM_ADD -> setAddSubRoomMode(false)
                EditorMenuState.DEVICE_ADD,
                EditorMenuState.DEVICE_SELECTED,
                EditorMenuState.DEVICE_IDLE -> {
                    applyModeSelection(LivingRoomEditorView.EditorMode.DEVICE)
                    Toast.makeText(this, "已还原未保存的设备修改", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    refreshOverlayDisplay()
                    applyModeSelection(editorView.currentMode)
                    Toast.makeText(this, "已还原未保存的修改", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btnFinish).setOnClickListener {
            if (editorMenuState == EditorMenuState.SUBROOM_DOOR_SELECT) {
                val saved = editorView.commitDoorSelection()
                if (!saved) {
                    Toast.makeText(this, "未保存", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                persistRoomConfigAfterEditorSave()
                transitionTo(EditorMenuState.SUBROOM_SELECTED)
            } else if (editorMenuState == EditorMenuState.SUBROOM_AREA_EDIT) {
                val roomId = editorView.getRegionEditRoomId()
                val room = RoomRepository.getSubRooms().firstOrNull { it.id == roomId }
                if (room != null) {
                    val points = editorView.getRegionEditPoints()
                    if (points.size >= 3) {
                        val vertices = buildVerticesFromPoints(points)
                        room.boundaryVertices.clear()
                        room.boundaryVertices.addAll(vertices)
                        RoomRepository.updateRoom(room)
                    }
                }
                persistRoomConfigAfterEditorSave()
                editorView.endSubRoomRegionEdit()
                transitionTo(EditorMenuState.SUBROOM_SELECTED)
            } else if (
                editorMenuState == EditorMenuState.DEVICE_ADD ||
                editorMenuState == EditorMenuState.DEVICE_SELECTED
            ) {
                handleDeviceSave()
            } else {
                // 保存不退出
                performSave()
                persistRoomConfigAfterEditorSave()
            }
        }

        findViewById<Button>(R.id.btnRenameRoom).setOnClickListener {
            val roomId = editorView.selectedRoomId
            val room = RoomRepository.getSubRooms().find { it.id == roomId }
            room?.let { showRenameDialog(it) }
        }
        findViewById<Button>(R.id.btnDeleteRoom).setOnClickListener {
            if (editorView.currentMode == LivingRoomEditorView.EditorMode.DEVICE) {
                if (editorView.deleteSelectedDevice()) {
                    RoomRepository.replaceDevices(editorView.getDevices())
                    persistRoomConfigAfterEditorSave()
                    transitionTo(EditorMenuState.DEVICE_IDLE)
                }
            } else {
                val roomId = editorView.selectedRoomId
                if (roomId != null) {
                    RoomRepository.deleteRoom(roomId)
                    editorView.setSubRooms(RoomRepository.getSubRooms())
                    editorView.clearSelection()
                }
            }
        }

        val editorControls = findViewById<LinearLayout>(R.id.llEditorLeft)
        btnAddSubRoom = Button(this).apply {
            text = getString(R.string.btn_add_sub_room)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { enterAddSubRoomMode() }
        }
        btnAddSubRoom?.let { editorControls?.addView(it) }

        btnSelectDoor = Button(this).apply {
            text = getString(R.string.btn_select_door)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { enterDoorSelectMode() }
        }
        btnSelectDoor?.let { editorControls?.addView(it) }

        btnEditRoomArea = Button(this).apply {
            text = getString(R.string.btn_edit_room_area)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9C27B0"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { enterRoomAreaEditMode() }
        }
        btnEditRoomArea?.let { editorControls?.addView(it) }

        btnAddDeviceEditor = Button(this).apply {
            text = "添加设备"
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { enterAddDeviceMode() }
        }
        btnAddDeviceEditor?.let { editorControls?.addView(it) }
    }
    
    // 🔥 抽取保存逻辑，供 btnFinish 调用且不退出
    private fun buildVerticesFromPoints(points: List<PointF>): MutableList<BoundaryVertex> {
        val vertices = ArrayList<BoundaryVertex>(points.size)
        var nextVertexId = 1
        var nextEdgeId = 1
        for (p in points) {
            vertices.add(BoundaryVertex(nextVertexId++, PointF(p.x, p.y), nextEdgeId++))
        }
        return vertices
    }

    private fun performSave() {
        if (editorView.currentMode == LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL) {
            val vertices = editorView.getResult()
            if (vertices.size >= 3) {
                RoomRepository.saveRoomBoundary("living_room", vertices)
                val removedEdges = editorView.consumeRemovedEdgeIds()
                unbindRoomsFromRemovedEdges(removedEdges)
            }
        }
        // SubRoom 模式下的修改大多是即时保存的，或者在 finish 子状态时保存
        refreshOverlayDisplay()
    }

    private fun persistRoomConfigAfterEditorSave(): Boolean {
        val savedFile = RoomRepository.saveCurrentConfigOrCreateForCurrentVideo()
        if (savedFile != null) {
            Toast.makeText(this, "配置已保存: ${savedFile.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
            return true
        }
        Toast.makeText(this, "请先选择测试视频后再保存配置", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun enterEditMode() {
        captureCurrentFrame()
        editorView.backgroundBitmap = BitmapTransfer.capturedFrame
        editorView.drawBackground = false
        refreshEditModeLabels()
        // 默认进入主房间模式
        spnEditMode?.setSelection(0)
        applyModeSelection(LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL)
        toggleEditModeUI(true)
    }
    
    private fun enterDeviceSettingsMode() {
        applyModeSelection(LivingRoomEditorView.EditorMode.DEVICE)
    }

    private fun toggleRoomMode(btn: Button) {
        // 已弃用，由 Spinner 接管
    }
    private fun enterAddSubRoomMode() {
        setAddSubRoomMode(true)
        Toast.makeText(this, getString(R.string.toast_click_to_add), Toast.LENGTH_SHORT).show()
    }

    private fun enterDoorSelectMode() {
        if (editorView.selectedRoomId == null) return
        setSubRoomActionMode(doorSelect = true, roomAreaEdit = false)
        Toast.makeText(this, getString(R.string.toast_select_door), Toast.LENGTH_SHORT).show()
    }

    private fun enterRoomAreaEditMode() {
        val selectedId = editorView.selectedRoomId ?: return
        val room = RoomRepository.getSubRooms().firstOrNull { it.id == selectedId } ?: return
        val started = editorView.startSubRoomRegionEdit(room)
        if (!started) {
            return
        }
        setSubRoomActionMode(doorSelect = false, roomAreaEdit = true)
        Toast.makeText(this, getString(R.string.toast_edit_area), Toast.LENGTH_SHORT).show()
    }

    private fun enterAddDeviceMode() {
        transitionTo(EditorMenuState.DEVICE_ADD)
        Toast.makeText(this, "请先点击最可能指向的地方", Toast.LENGTH_SHORT).show()
    }

    private fun setSubRoomActionMode(doorSelect: Boolean, roomAreaEdit: Boolean) {
        if (editorView.currentMode != LivingRoomEditorView.EditorMode.SUB_ROOM_ANCHOR) return
        val hasSelection = editorView.selectedRoomId != null
        if (!hasSelection) {
            transitionTo(EditorMenuState.SUBROOM_IDLE)
            return
        }
        val nextState = when {
            doorSelect -> EditorMenuState.SUBROOM_DOOR_SELECT
            roomAreaEdit -> EditorMenuState.SUBROOM_AREA_EDIT
            else -> EditorMenuState.SUBROOM_SELECTED
        }
        transitionTo(nextState)
    }

    private fun setAddSubRoomMode(active: Boolean) {
        if (editorView.currentMode != LivingRoomEditorView.EditorMode.SUB_ROOM_ANCHOR) return
        if (active) {
            transitionTo(EditorMenuState.SUBROOM_ADD)
        } else {
            val hasSelection = editorView.selectedRoomId != null
            transitionTo(if (hasSelection) EditorMenuState.SUBROOM_SELECTED else EditorMenuState.SUBROOM_IDLE)
        }
    }

    private fun setEditorMenuState(state: EditorMenuState) {
        editorMenuState = state
        renderEditorMenu(state)
    }

    private fun transitionTo(state: EditorMenuState) {
        val prev = editorMenuState
        editorMenuState = state
        val nextAdd = state == EditorMenuState.SUBROOM_ADD
        val nextDoor = state == EditorMenuState.SUBROOM_DOOR_SELECT
        val nextArea = state == EditorMenuState.SUBROOM_AREA_EDIT
        val nextDeviceAdd = state == EditorMenuState.DEVICE_ADD
        isAddSubRoomMode = nextAdd
        isDoorSelectMode = nextDoor
        isRoomAreaEditMode = nextArea
        isAddDeviceMode = nextDeviceAdd
        editorView.setAddSubRoomArmed(nextAdd)
        editorView.setDoorSelectArmed(nextDoor)
        editorView.setRoomAreaEditArmed(nextArea)
        editorView.setAddDeviceArmed(nextDeviceAdd)
        if (prev == EditorMenuState.SUBROOM_DOOR_SELECT && state != EditorMenuState.SUBROOM_DOOR_SELECT) {
            editorView.discardPendingDoorSelection()
        }
        if (prev == EditorMenuState.SUBROOM_AREA_EDIT && state != EditorMenuState.SUBROOM_AREA_EDIT) {
            editorView.endSubRoomRegionEdit()
        }
        refreshEditModeLabels()
        renderEditorMenu(state)
    }

    private fun renderEditorMenu(state: EditorMenuState) {
        val btnUndo = findViewById<Button>(R.id.btnUndo)
        val btnClear = findViewById<Button>(R.id.btnClear)
        val btnRename = findViewById<Button>(R.id.btnRenameRoom)
        val btnDelete = findViewById<Button>(R.id.btnDeleteRoom)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        val btnFinish = findViewById<Button>(R.id.btnFinish)

        btnRename.text = "属性"
        btnCancel.text = "不保存"
        btnFinish.text = "保存"
        btnUndo.text = getString(R.string.undo)
        btnClear.text = getString(R.string.clear)

        when (state) {
            EditorMenuState.LIVING_ROOM -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.VISIBLE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnAddDeviceEditor?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnFinish.visibility = View.VISIBLE
            }
            EditorMenuState.SUBROOM_IDLE -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.VISIBLE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnAddDeviceEditor?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.GONE
                btnFinish.visibility = View.GONE
            }
            EditorMenuState.SUBROOM_ADD -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnAddDeviceEditor?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.GONE
                btnFinish.visibility = View.GONE
            }
            EditorMenuState.SUBROOM_SELECTED -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.VISIBLE
                btnEditRoomArea?.visibility = View.VISIBLE
                btnAddDeviceEditor?.visibility = View.GONE
                btnRename.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
                btnCancel.visibility = View.GONE
                btnFinish.visibility = View.GONE
            }
            EditorMenuState.SUBROOM_AREA_EDIT -> {
                btnUndo.visibility = View.VISIBLE
                btnClear.visibility = View.VISIBLE
                btnUndo.text = "还原"
                btnClear.text = "删除区域"
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnAddDeviceEditor?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnFinish.visibility = View.VISIBLE
            }
            EditorMenuState.SUBROOM_DOOR_SELECT -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnAddDeviceEditor?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnFinish.visibility = View.VISIBLE
            }
            EditorMenuState.DEVICE_IDLE -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnAddDeviceEditor?.visibility = View.VISIBLE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.GONE
                btnFinish.visibility = View.GONE
            }
            EditorMenuState.DEVICE_ADD -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnAddDeviceEditor?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnFinish.visibility = View.VISIBLE
            }
            EditorMenuState.DEVICE_SELECTED -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnAddDeviceEditor?.visibility = View.VISIBLE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                btnFinish.visibility = View.VISIBLE
            }
        }
    }

    private fun applyModeSelection(mode: LivingRoomEditorView.EditorMode) {
        // 恢复 EditorView 显示 (如果之前在设备模式)
        editorView.visibility = View.VISIBLE
        llDeviceSettings.visibility = View.GONE
        refreshEditModeLabels()
        
        editorView.currentMode = mode
        transitionTo(
            when (mode) {
                LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL -> EditorMenuState.LIVING_ROOM
                LivingRoomEditorView.EditorMode.SUB_ROOM_ANCHOR -> EditorMenuState.SUBROOM_IDLE
                LivingRoomEditorView.EditorMode.DEVICE -> EditorMenuState.DEVICE_IDLE
            }
        )

        if (mode == LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL) {
            val livingRoom = RoomRepository.getAllRooms().find { it.isSovereignTerritory }
            editorView.setHistoryVertices(livingRoom?.boundaryVertices ?: emptyList())
            editorView.setSubRooms(RoomRepository.getSubRooms())
        } else if (mode == LivingRoomEditorView.EditorMode.SUB_ROOM_ANCHOR) {
            editorView.setSubRooms(RoomRepository.getSubRooms())
            editorView.setOnSubRoomListener(
                onAdd = { point ->
                    setAddSubRoomMode(false)
                    showAddSubRoomDialog(point)
                },
                onSelected = { room ->
                    if (room == null) {
                        transitionTo(EditorMenuState.SUBROOM_IDLE)
                        return@setOnSubRoomListener
                    }
                    val nextState = when (editorMenuState) {
                        EditorMenuState.SUBROOM_DOOR_SELECT -> EditorMenuState.SUBROOM_DOOR_SELECT
                        EditorMenuState.SUBROOM_AREA_EDIT -> EditorMenuState.SUBROOM_AREA_EDIT
                        else -> EditorMenuState.SUBROOM_SELECTED
                    }
                    transitionTo(nextState)
                },
                onUpdated = { room ->
                    RoomRepository.updateRoom(room)
                }
            )
        } else {
            editorView.setDevices(RoomRepository.getDevices())
            editorView.setOnDeviceSelectionListener { device ->
                val nextState = when {
                    editorView.hasPendingDeviceDraft() -> EditorMenuState.DEVICE_ADD
                    device != null -> EditorMenuState.DEVICE_SELECTED
                    else -> EditorMenuState.DEVICE_IDLE
                }
                if (editorMenuState != nextState) {
                    transitionTo(nextState)
                }
            }
        }
        overlayView.setDeviceEditCleanMode(
            llEditorControls.visibility == View.VISIBLE && mode == LivingRoomEditorView.EditorMode.DEVICE
        )
    }

    private fun buildEditModeLabels(): Array<String> {
        val subRoomCount = RoomRepository.getSubRooms().size
        val deviceCount = RoomRepository.getDevices().size
        return arrayOf(
            "主房间设置",
            "次房间设置($subRoomCount)",
            "设备设置($deviceCount)"
        )
    }

    private fun refreshEditModeLabels() {
        val adapter = editModeAdapter ?: return
        val spinner = spnEditMode ?: return
        val selected = spinner.selectedItemPosition.coerceAtLeast(0)
        val labels = buildEditModeLabels()
        adapter.clear()
        adapter.addAll(*labels)
        adapter.notifyDataSetChanged()
        if (selected in labels.indices && spinner.selectedItemPosition != selected) {
            spinner.setSelection(selected, false)
        }
    }
    private fun showAddSubRoomDialog(point: PointF) {
        val input = EditText(this).apply { hint = getString(R.string.hint_room_name) }
        val checkbox = CheckBox(this).apply { text = "入户门" }
        val blindCheckbox = CheckBox(this).apply { text = "主房间盲区" }
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && blindCheckbox.isChecked) {
                blindCheckbox.isChecked = false
            }
        }
        blindCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && checkbox.isChecked) {
                checkbox.isChecked = false
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
            addView(checkbox)
            addView(blindCheckbox)
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_title_new_room)).setView(layout)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val isEntrance = checkbox.isChecked
                    val isBlindZone = blindCheckbox.isChecked
                    if (isEntrance && isBlindZone) {
                        Toast.makeText(this, "入户门与主房间盲区互斥,无法保存.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (isEntrance) {
                        val existed = RoomRepository.getSubRooms().firstOrNull { it.isEntranceDoor }
                        if (existed != null) {
                            Toast.makeText(this, "已经选择${existed.name}房间作为入户门,无法保存.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                    }
                    RoomRepository.addNewRoom(name, point, isEntrance, isBlindZone)
                    editorView.setSubRooms(RoomRepository.getSubRooms())
                }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun showRenameDialog(room: RoomConfig) {
        val input = EditText(this).apply { setText(room.name) }
        val checkbox = CheckBox(this).apply {
            text = "入户门"
            isChecked = room.isEntranceDoor
        }
        val blindCheckbox = CheckBox(this).apply {
            text = "主房间盲区"
            isChecked = room.isLivingBlindZone
        }
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && blindCheckbox.isChecked) {
                blindCheckbox.isChecked = false
            }
        }
        blindCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && checkbox.isChecked) {
                checkbox.isChecked = false
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
            addView(checkbox)
            addView(blindCheckbox)
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_title_rename)).setView(layout)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                val name = input.text.toString().trim()
                val isEntrance = checkbox.isChecked
                val isBlindZone = blindCheckbox.isChecked
                if (isEntrance && isBlindZone) {
                    Toast.makeText(this, "入户门与主房间盲区互斥,无法保存.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isEntrance) {
                    val existed = RoomRepository.getSubRooms().firstOrNull { it.id != room.id && it.isEntranceDoor }
                    if (existed != null) {
                        Toast.makeText(this, "已经选择${existed.name}房间作为入户门,无法保存.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }
                room.name = name
                room.isEntranceDoor = isEntrance
                room.isLivingBlindZone = isBlindZone
                RoomRepository.updateRoom(room)
                editorView.setSubRooms(RoomRepository.getSubRooms())
            }.show()
    }

    private fun suggestNextDeviceIndex(): Int {
        val existingIds = editorView.getDevices().map { it.id }.toSet()
        var index = 1
        while (existingIds.contains("device_$index")) {
            index += 1
        }
        return index
    }

    private fun handleDeviceSave() {
        if (editorView.currentMode != LivingRoomEditorView.EditorMode.DEVICE) return
        if (editorView.hasPendingDeviceDraft()) {
            showSaveDeviceDialog()
            return
        }
        RoomRepository.replaceDevices(editorView.getDevices())
        persistRoomConfigAfterEditorSave()
        transitionTo(if (editorView.selectedDeviceId != null) EditorMenuState.DEVICE_SELECTED else EditorMenuState.DEVICE_IDLE)
    }

    private fun showSaveDeviceDialog() {
        val index = suggestNextDeviceIndex()
        val defaultName = "设备$index"
        val defaultId = "device_$index"
        val input = EditText(this).apply {
            setText(defaultName)
            hint = "设备名称"
        }
        AlertDialog.Builder(this)
            .setTitle("保存设备")
            .setMessage("请输入设备名称")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "设备名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val saved = editorView.commitPendingDevice(name, defaultId) ?: run {
                    Toast.makeText(this, "当前设备框未包含核心点，未保存", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                RoomRepository.replaceDevices(editorView.getDevices())
                persistRoomConfigAfterEditorSave()
                transitionTo(EditorMenuState.DEVICE_SELECTED)
                Toast.makeText(this, "已保存设备: ${saved.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exitEditMode(save: Boolean) {
        if (save) {
            if (editorView.currentMode == LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL) {
                val vertices = editorView.getResult()
                if (vertices.size >= 3) {
                    RoomRepository.saveRoomBoundary("living_room", vertices)
                    val removedEdges = editorView.consumeRemovedEdgeIds()
                    unbindRoomsFromRemovedEdges(removedEdges)
                }
            }
        }
        refreshOverlayDisplay()
        toggleEditModeUI(false)
    }

    private fun unbindRoomsFromRemovedEdges(removedEdgeIds: Set<Int>) {
        if (removedEdgeIds.isEmpty()) return
        val subRooms = RoomRepository.getSubRooms()
        for (room in subRooms) {
            val before = room.occupiedWallIds.size
            room.occupiedWallIds.removeAll(removedEdgeIds)
            if (room.occupiedWallIds.size != before) {
                RoomRepository.updateRoom(room)
            }
        }
    }

    private fun refreshOverlayDisplay() {
        val allRooms = RoomRepository.getAllRooms()
        val livingRoom = allRooms.find { it.isSovereignTerritory }
        if (livingRoom != null) {
            currentLivingRoomBoundary = livingRoom.boundaryPoints
            overlayView.setLivingRoomBoundary(livingRoom.boundaryPoints)
            overlayView.setLivingRoomVertices(livingRoom.boundaryVertices)
        }
        overlayView.setSubRooms(allRooms.filter { !it.isSovereignTerritory })
        overlayView.setDevices(RoomRepository.getDevices())
        updateHandOverlayMode()
    }

    fun onRoomConfigChangedFromSettings() {
        refreshOverlayDisplay()
        if (editorView.visibility == View.VISIBLE) {
            applyModeSelection(editorView.currentMode)
        }
    }

    fun onVideoSourceChangedFromSettings() {
        if (isVideoMode) {
            startVideoMode()
        }
    }

    private fun isHandObserveMode(): Boolean = currentObserveMode == ObserveMode.HAND

    private fun updateHandTranslationControl(
        hands: List<List<HandSmokeTester.HandPoint>>
    ) {
        if (!isHandObserveMode()) return
        val snapshot = handTranslationController.updateHands(
            hands = hands,
            timestampMs = SystemClock.elapsedRealtime()
        )
        applyHandTranslationSnapshot(snapshot)
    }

    private fun resetHandTranslationControl() {
        applyHandTranslationSnapshot(handTranslationController.reset())
    }

    private fun applyHandTranslationSnapshot(snapshot: HandTranslationController.Snapshot) {
        val lines = when (snapshot.state) {
            HandTranslationController.State.IDLE -> listOf(
                "二维控制：等待手势",
                "手势：拇指、食指张开，其余三指卷曲"
            )

            HandTranslationController.State.HOLDING -> listOf(
                "二维控制：激活中 ${String.format(Locale.US, "%.1f", snapshot.holdElapsedMs / 1000f)}s / 1.0s"
            )

            HandTranslationController.State.ACTIVE -> listOf(
                "二维控制：ACTIVE",
                "X: ${handTranslationController.formatValue(snapshot.x)}",
                "Y: ${handTranslationController.formatValue(snapshot.y)}"
            )
        }
        overlayView.setHandTranslationControlState(
            active = snapshot.state == HandTranslationController.State.ACTIVE,
            activeHandIndex = snapshot.selectedHandIndex,
            lines = lines
        )
    }

    private fun isAudioObserveMode(): Boolean = currentObserveMode == ObserveMode.AUDIO

    private fun shouldShowCenterBanner(domain: CenterBannerDomain): Boolean {
        return when (domain) {
            CenterBannerDomain.DEVICE -> isHandObserveMode() || isAudioObserveMode()
            CenterBannerDomain.ROOM -> currentObserveMode == ObserveMode.PERSON
        }
    }

    private fun showCenterBanner(
        message: String,
        domain: CenterBannerDomain,
        durationMs: Long = 5000L
    ) {
        if (!shouldShowCenterBanner(domain)) return
        lastCenterBannerDomain = domain
        overlayView.showUnlockBanner(message, durationMs)
    }

    private fun clearModeMismatchedCenterBanner() {
        val domain = lastCenterBannerDomain ?: return
        if (shouldShowCenterBanner(domain)) return
        overlayView.clearUnlockBanner()
        lastCenterBannerDomain = null
    }

    private fun cycleObserveMode() {
        val nextMode = when (currentObserveMode) {
            ObserveMode.PERSON -> ObserveMode.HAND
            ObserveMode.HAND -> ObserveMode.AUDIO
            ObserveMode.AUDIO -> ObserveMode.PERSON
        }
        applyObserveMode(nextMode)
    }

    private fun applyObserveMode(mode: ObserveMode) {
        if (currentObserveMode == mode) return
        val leavingHandMode = currentObserveMode == ObserveMode.HAND && mode != ObserveMode.HAND
        currentObserveMode = mode
        if (leavingHandMode) {
            pointingResolver.cancelSession()
            overlayView.updatePointingLiveSnapshot(null)
        }
        resetHandTranslationControl()
        if (mode == ObserveMode.HAND) {
            handSmokeTester?.startConfidenceProbeSession()
            startTriggeredPointingSession()
        }
        updateHandOverlayMode()
        clearModeMismatchedCenterBanner()
    }

    private fun syncAudioScreenMode(active: Boolean) {
        if (!isAudioScreenBound) {
            composeAudioScreen.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            composeAudioScreen.setContent {
                MaterialTheme {
                    KwsPanelScreen(
                        modifier = Modifier.fillMaxSize(),
                        controller = kwsController,
                        currentPlayerTimeMsProvider = { currentVideoTimestampMs() },
                        currentAudioInputModeProvider = { currentKwsAudioInputMode() },
                        onSelectAudioInputMode = { mode -> setKwsAudioInputMode(mode) },
                        currentPlaybackAudioSourceSpecProvider = { resolvePlaybackAudioSourceSpec() },
                        playbackAudioActiveProvider = { isVideoMode },
                        logClearSignal = kwsLogClearSignal.intValue,
                        latestDeviceResultUpdate = latestKwsAudioLogUpdate.value
                    )
                }
            }
            isAudioScreenBound = true
        }
        composeAudioScreen.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun bindKwsCommandRelay() {
        kwsController.setExtraCommandListener { event ->
            if (event.command != Command.OPEN && event.command != Command.CLOSE) return@setExtraCommandListener
            runOnUiThread {
                AppLog.i(
                    "KwsOpenPointing",
                    "${event.command}命中，立即触发一次手势设备匹配 score=${event.score ?: -1f}"
                )
                triggerPointingSessionFromVoice(
                    command = event.command,
                    commandTimestampMs = event.timestampMs
                )
            }
        }
    }

    private fun triggerPointingSessionFromVoice(command: Command, commandTimestampMs: Long) {
        if (!isVideoMode) {
            AppLog.i("KwsOpenPointing", "忽略${command.name}触发：当前不是视频模式")
            return
        }
        cancelPendingVoiceTimeout()
        pendingVoicePointingFeedback = true
        pendingVoiceCommandToken = commandTimestampMs
        showCenterBanner(
            "${command.name.lowercase(Locale.US)}命中，启动一次手势设备匹配",
            CenterBannerDomain.DEVICE,
            persistentHandBannerDurationMs
        )
        val immediateDecision = startTriggeredPointingSession(
            preRollMs = pointingReplayHistoryWindowMs,
            centerTimestampMs = commandTimestampMs
        )
        if (immediateDecision != null) {
            handleTriggeredPointingDecision(immediateDecision)
            return
        }
        if (pointingResolver.isActive()) {
            schedulePendingVoiceTimeout(commandTimestampMs)
        }
    }

    private fun schedulePendingVoiceTimeout(commandTimestampMs: Long) {
        pendingVoiceTimeoutToken = commandTimestampMs
        eventUiHandler.removeCallbacks(pendingVoiceTimeoutRunnable)
        eventUiHandler.postDelayed(pendingVoiceTimeoutRunnable, pointingReplayHistoryWindowMs)
    }

    private fun cancelPendingVoiceTimeout() {
        pendingVoiceTimeoutToken = null
        eventUiHandler.removeCallbacks(pendingVoiceTimeoutRunnable)
    }

    private fun rememberPointingObservation(observation: HandObservation) {
        pointingReplayHistory.addLast(observation)
        val keepFrom = observation.timestampMs - pointingReplayRetentionMs
        while (pointingReplayHistory.isNotEmpty() &&
            pointingReplayHistory.first().timestampMs < keepFrom
        ) {
            pointingReplayHistory.removeFirst()
        }
    }

    private fun replayRecentPointingObservations(
        fromTimestampMs: Long,
        toTimestampMs: Long
    ): PointingDecision? {
        val replayFrames = pointingReplayHistory.filter { observation ->
            observation.timestampMs in fromTimestampMs..toTimestampMs
        }
        for (observation in replayFrames) {
            val decision = pointingResolver.submitFrame(observation)
            if (decision !is PointingDecision.Pending) {
                return decision
            }
        }
        return null
    }

    private fun updateHandOverlayMode() {
        val handMode = isHandObserveMode()
        val audioMode = isAudioObserveMode()
        overlayView.setHandOnlyState(handMode)
        overlayView.setAudioOnlyMode(audioMode)
        overlayView.setPoseState(AppSettings.isPoseModeEnabled && !handMode && !audioMode)
        btnHandOverlay?.text = when (currentObserveMode) {
            ObserveMode.PERSON -> "看人视图"
            ObserveMode.HAND -> "看手视图"
            ObserveMode.AUDIO -> "声音视图"
        }
        syncAudioScreenMode(audioMode)
        if (!handMode) {
            isAwaitingDeviceHitSelection = false
            pendingDeviceHitTimestampMs = null
            pendingDeviceHitFrameIndex = null
        }
        syncDeviceHitSelectionUi()
        refreshEventMarkerUi()
    }

    private fun toggleEditModeUI(isEditing: Boolean) {
        llNormalControls.visibility = if (isEditing) View.GONE else View.VISIBLE
        llRightActionControls.visibility = if (isEditing) View.GONE else View.VISIBLE
        llEventMarkerControls.visibility = View.GONE
        llEditorControls.visibility = if (isEditing) View.VISIBLE else View.GONE
        cardCounter.visibility = View.GONE
        editorView.visibility = if (isEditing) View.VISIBLE else View.GONE
        
        overlayView.visibility = View.VISIBLE
        overlayView.setEditMode(isEditing)
        overlayView.setDeviceEditCleanMode(isEditing && editorView.currentMode == LivingRoomEditorView.EditorMode.DEVICE)
        if (!isAwaitingDeviceHitSelection) {
            applyUiLayerMode(if (isEditing) UiLayerMode.EDITING else UiLayerMode.NORMAL)
        }
    }

    private fun captureCurrentFrame() {
        if (isVideoMode && textureView.isAvailable) {
            BitmapTransfer.capturedFrame = textureView.getBitmap()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isMainStartupInitialized) {
            hideSystemUI()
            return
        }
        ensurePresenceAlgorithmVersion()
        syncPoseRoiTrackerConfig()
        applySettings()
        refreshOverlayDisplay()
        bindEventMarkersToVideo(resolveVideoSourceKey())
        refreshEventMarkerUi()
        if (isVideoMode) {
            val desiredKey = resolveVideoSourceKey()
            if (desiredKey != lastVideoSourceKey) {
                startVideoMode()
            } else if (currentPlayState == PlayState.PLAYING) {
                videoFeeder?.resume()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopSeekHold()
        cancelBlankPreviewTracking(restoreUi = true)
        if (isVideoMode) videoFeeder?.pause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isAwaitingDeviceHitSelection) {
            Log.i(
                "DeviceHitSelect",
                "activity dispatch action=${ev.actionMasked} x=${ev.x} y=${ev.y}"
            )
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val tappedDevice = overlayView.resolveSelectionDeviceAt(ev.x, ev.y)
                if (tappedDevice != null) {
                    Log.i(
                        "DeviceHitSelect",
                        "activity dispatch hit device id=${tappedDevice.id} name=${tappedDevice.name}"
                    )
                    onDeviceTappedForMarker(tappedDevice)
                } else {
                    Log.i("DeviceHitSelect", "activity dispatch blank -> cancel")
                    cancelDeviceHitSelection()
                }
                return true
            }
            return true
        }
        if (super.dispatchTouchEvent(ev)) {
            return true
        }
        if (handleBlankPreviewTouch(ev)) {
            return true
        }
        return false
    }

    private fun handleBlankPreviewTouch(ev: MotionEvent): Boolean {
        if (!isBlankPreviewEligible()) {
            AppLog.i(
                "RoomBlankPreview",
                "ignore action=${ev.actionMasked} eligible=false mode=$currentObserveMode video=$isVideoMode awaiting=$isAwaitingDeviceHitSelection editor=${llEditorControls.visibility}"
            )
            cancelBlankPreviewTracking(restoreUi = true)
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val blank = isBlankAreaTouch(ev.rawX, ev.rawY)
                AppLog.i(
                    "RoomBlankPreview",
                    "down raw=(${ev.rawX},${ev.rawY}) blank=$blank"
                )
                if (!blank) return false
                blankPreviewDownRawX = ev.rawX
                blankPreviewDownRawY = ev.rawY
                enterBlankPreviewMode()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!blankPreviewActive) return false
                val moved = kotlin.math.hypot(
                    (ev.rawX - blankPreviewDownRawX).toDouble(),
                    (ev.rawY - blankPreviewDownRawY).toDouble()
                ).toFloat()
                val blank = isBlankAreaTouch(ev.rawX, ev.rawY)
                AppLog.i(
                    "RoomBlankPreview",
                    "move raw=(${ev.rawX},${ev.rawY}) moved=$moved slop=$blankPreviewTouchSlop blank=$blank active=$blankPreviewActive"
                )
                if (moved > blankPreviewTouchSlop || !blank) {
                    AppLog.i("RoomBlankPreview", "cancel on move")
                    cancelBlankPreviewTracking(restoreUi = true)
                }
                return blankPreviewActive
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = blankPreviewActive
                AppLog.i(
                    "RoomBlankPreview",
                    "end action=${ev.actionMasked} consumed=$consumed active=$blankPreviewActive"
                )
                cancelBlankPreviewTracking(restoreUi = true)
                return consumed
            }
        }
        return blankPreviewActive
    }

    private fun isBlankPreviewEligible(): Boolean {
        return isVideoMode &&
            (currentObserveMode == ObserveMode.PERSON || currentObserveMode == ObserveMode.HAND) &&
            !isAwaitingDeviceHitSelection &&
            llEditorControls.visibility != View.VISIBLE &&
            !isAudioObserveMode()
    }

    private fun isBlankAreaTouch(rawX: Float, rawY: Float): Boolean {
        if (!textureView.isShown) return false
        if (isTouchInsideView(rawX, rawY, llRightActionControls) ||
            isTouchInsideView(rawX, rawY, llNormalControls) ||
            isTouchInsideView(rawX, rawY, llEventMarkerControls)
        ) {
            AppLog.i("RoomBlankPreview", "hitTest raw=($rawX,$rawY) blank=false reason=control")
            return false
        }
        val rect = android.graphics.Rect()
        textureView.getGlobalVisibleRect(rect)
        val blank = !rect.contains(rawX.toInt(), rawY.toInt())
        AppLog.i(
            "RoomBlankPreview",
            "hitTest raw=($rawX,$rawY) texture=(${rect.left},${rect.top},${rect.right},${rect.bottom}) blank=$blank"
        )
        return blank
    }

    private fun isTouchInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        if (view.visibility != View.VISIBLE || !view.isShown) return false
        val rect = android.graphics.Rect()
        view.getGlobalVisibleRect(rect)
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    private fun enterBlankPreviewMode() {
        if (blankPreviewActive) return
        blankPreviewActive = true
        AppLog.i("RoomBlankPreview", "enter preview")
        savedOverlayVisibility = overlayView.visibility
        savedNormalControlsVisibility = llNormalControls.visibility
        savedRightActionControlsVisibility = llRightActionControls.visibility
        savedEventControlsVisibility = llEventMarkerControls.visibility
        savedEditorControlsVisibility = llEditorControls.visibility
        savedCounterVisibility = cardCounter.visibility
        savedRadarVisibility = flRadarContainer.visibility
        savedEditorViewVisibility = editorView.visibility
        overlayView.visibility = View.GONE
        llNormalControls.visibility = View.GONE
        llRightActionControls.visibility = View.GONE
        llEventMarkerControls.visibility = View.GONE
        llEditorControls.visibility = View.GONE
        cardCounter.visibility = View.GONE
        flRadarContainer.visibility = View.GONE
        editorView.visibility = View.GONE
    }

    private fun cancelBlankPreviewTracking(restoreUi: Boolean) {
        if (!blankPreviewActive) return
        blankPreviewActive = false
        AppLog.i("RoomBlankPreview", "restore preview restoreUi=$restoreUi")
        if (!restoreUi) return
        savedOverlayVisibility?.let { overlayView.visibility = it }
        savedNormalControlsVisibility?.let { llNormalControls.visibility = it }
        savedRightActionControlsVisibility?.let { llRightActionControls.visibility = it }
        savedEventControlsVisibility?.let { llEventMarkerControls.visibility = it }
        savedEditorControlsVisibility?.let { llEditorControls.visibility = it }
        savedCounterVisibility?.let { cardCounter.visibility = it }
        savedRadarVisibility?.let { flRadarContainer.visibility = it }
        savedEditorViewVisibility?.let { editorView.visibility = it }
        savedOverlayVisibility = null
        savedNormalControlsVisibility = null
        savedRightActionControlsVisibility = null
        savedEventControlsVisibility = null
        savedEditorControlsVisibility = null
        savedCounterVisibility = null
        savedRadarVisibility = null
        savedEditorViewVisibility = null
    }

    /**
     * 同步设置中的 Presence 算法版本。
     * 仅当版本变化时重建引擎，避免运行中状态被频繁打断。
     */
    private fun ensurePresenceAlgorithmVersion() {
        val selectedId = AppSettings.presenceAlgorithmVersion
        val resolvedId = PresenceAlgorithmRegistry.resolveVersionId(selectedId)
        if (!::roomPresenceAlgorithm.isInitialized || roomPresenceAlgorithm.versionId != resolvedId) {
            roomPresenceAlgorithm = PresenceAlgorithmRegistry.create(selectedId, PresenceEstimatorParams())
            roomPresenceChangeLogger.reset()
            Log.i("RoomPresence", "Presence算法已切换: ${roomPresenceAlgorithm.runtimeTag}")
        }
    }

    private fun applySettings() {
        overlayView.setDebugBoxState(AppSettings.isDebugBoxShown)
        overlayView.setCenterPointState(AppSettings.isCenterPointShown)
        overlayView.setPointingDebugOverlayEnabled(shouldEnablePointingDebugOverlay())
        if (!shouldShowLivePointingDebug()) {
            overlayView.updatePointingLiveSnapshot(null)
        }
        if (!isHandObserveMode()) {
            overlayView.updatePointingPanelSnapshot(null)
        }
        syncRuntimeAnalyzerMode()
        refreshRuntimeModeButton()
        if (!isVideoMode) { unbindCamera(); startCameraMode() }
    }

    private fun syncRuntimeAnalyzerMode() {
        updateHandOverlayMode()
        videoFeeder?.isPoseMode = AppSettings.isPoseModeEnabled
    }

    private fun toggleRuntimeMode() {
        if (isVideoMode) {
            switchToCameraRuntimeMode()
        } else {
            switchToVideoRuntimeMode()
        }
    }

    private fun switchToCameraRuntimeMode() {
        if (!isVideoMode) return
        showRuntimeSwitchLoading("正在进入 Live…")
        isVideoMode = false
        videoFeeder?.pause()
        textureView.visibility = View.GONE
        textureView.alpha = 1f
        clearLiveFrozenFrame()
        startCameraMode()
        refreshRuntimeModeButton()
        refreshPlayStateButton()
        refreshSeekButtons()
        hideRuntimeSwitchLoadingSoon()
    }

    private fun switchToVideoRuntimeMode() {
        if (isVideoMode) return
        showRuntimeSwitchLoading("正在进入回顾…")
        isVideoMode = true
        clearLiveFrozenFrame()
        unbindCamera()
        previewView.visibility = View.GONE
        startVideoMode()
        refreshRuntimeModeButton()
        refreshPlayStateButton()
        refreshSeekButtons()
        hideRuntimeSwitchLoadingSoon()
    }

    private fun showRuntimeSwitchLoading(message: String) {
        runtimeSwitchHandler.removeCallbacksAndMessages(null)
        findViewById<TextView>(R.id.runtimeSwitchLoading).apply {
            text = message
            alpha = 1f
            visibility = View.VISIBLE
            bringToFront()
        }
    }

    private fun hideRuntimeSwitchLoadingSoon(delayMs: Long = 700L) {
        val loading = findViewById<TextView>(R.id.runtimeSwitchLoading)
        runtimeSwitchHandler.postDelayed({
            loading.animate()
                .alpha(0f)
                .setDuration(160L)
                .withEndAction {
                    loading.visibility = View.GONE
                    loading.alpha = 1f
                }
                .start()
        }, delayMs)
    }

    private fun refreshRuntimeModeButton() {
        val btn = findViewById<Button>(R.id.btnRuntimeMode)
        btn.text = if (isVideoMode) "回顾模式" else "实时模式"
        btn.setBackgroundResource(
            if (isVideoMode) {
                R.drawable.bg_side_action_button_active
            } else {
                R.drawable.bg_side_action_button
            }
        )
        btn.setTextColor(Color.parseColor(if (isVideoMode) "#FFFFFF" else "#F2F7FF"))
    }

    private fun shouldEnablePointingDebugOverlay(): Boolean {
        return AppSettings.isPointingDebugOverlayEnabled &&
            AppSettings.pointingDebugDisplayMode != AppSettings.POINTING_DEBUG_DISPLAY_NEVER
    }

    private fun shouldShowLivePointingDebug(): Boolean {
        if (!shouldEnablePointingDebugOverlay()) return false
        return when (AppSettings.pointingDebugDisplayMode) {
            AppSettings.POINTING_DEBUG_DISPLAY_ALWAYS -> true
            AppSettings.POINTING_DEBUG_DISPLAY_WINDOW_ONLY -> pendingVoicePointingFeedback
            AppSettings.POINTING_DEBUG_DISPLAY_NEVER -> false
            else -> false
        }
    }

    private fun refreshEventMarkerUi() {
        if (blankPreviewActive) {
            llEventMarkerControls.visibility = View.GONE
            llNormalControls.visibility = View.GONE
            llRightActionControls.visibility = View.GONE
            cardCounter.visibility = View.GONE
            overlayView.visibility = View.GONE
            flRadarContainer.visibility = View.GONE
            return
        }
        if (isHandObserveMode()) {
            refreshDeviceHitMarkerOverlay()
        } else {
            refreshEventMarkerOverlay()
        }

        if (isHandDebugMarkerMode()) {
            refreshDeviceHitMarkerControls()
        } else {
            refreshEventMarkerControls()
        }
        refreshDebugPanelMode()
    }

    private fun scheduleEventMarkerUiRefresh(delayMs: Long = 120L) {
        eventUiHandler.postDelayed({ refreshEventMarkerUi() }, delayMs)
    }

    private fun currentVideoTimestampMs(): Long {
        return (videoFeeder?.getCurrentPositionMs() ?: 0).toLong()
    }

    private fun currentEstimatedFrameIndex(timestampMs: Long = currentVideoTimestampMs()): Int {
        val stepMs = (videoFeeder?.getFrameStepMs() ?: 33).coerceAtLeast(1)
        return (timestampMs / stepMs).toInt()
    }

    private fun refreshEventMarkerOverlay() {
        overlayView.clearDeviceHitMarkerState()
        if (!isVideoMode) {
            overlayView.setEventMarkerState(0L, 0L, emptyList(), emptySet())
            return
        }
        val currentMs = currentVideoTimestampMs()
        val durationMs = (videoFeeder?.getDurationMs() ?: 0).toLong()
        overlayView.setEventMarkerState(
            currentMs = currentMs,
            durationMs = durationMs,
            events = eventMarkerManager.getEvents(),
            matchedEventKeys = matchedMarkedEventKeys
        )
    }

    private fun refreshDeviceHitMarkerOverlay() {
        overlayView.setEventMarkerState(0L, 0L, emptyList(), emptySet())
        if (!isVideoMode) {
            overlayView.clearDeviceHitMarkerState()
            return
        }
        val currentMs = currentVideoTimestampMs()
        val durationMs = (videoFeeder?.getDurationMs() ?: 0).toLong()
        overlayView.setDeviceHitMarkerState(
            currentMs = currentMs,
            durationMs = durationMs,
            events = deviceHitMarkerManager.getEvents()
        )
    }

    private fun refreshEventMarkerControls() {
        val shouldShow = isVideoMode &&
            isDebugPanelEnabled &&
            currentPlayState != PlayState.PLAYING
        llEventMarkerControls.visibility = if (shouldShow) View.VISIBLE else View.GONE
        val btnMarkEnter = findViewById<Button>(R.id.btnMarkEnterEvent)
        val btnMarkExit = findViewById<Button>(R.id.btnMarkExitEvent)
        val btnJumpNext = findViewById<Button>(R.id.btnJumpNextEvent)
        val btnDelete = findViewById<Button>(R.id.btnDeleteCurrentEvent)
        btnMarkEnter.visibility = View.VISIBLE
        btnMarkExit.visibility = View.VISIBLE
        btnJumpNext.visibility = View.VISIBLE
        btnDelete.visibility = View.VISIBLE
        btnMarkEnter.text = "记录进子房间事件"
        btnMarkEnter.isEnabled = true
        btnMarkEnter.alpha = 1f
        btnMarkExit.text = "记录出子房间事件"
        btnJumpNext.text = "跳转到下一个事件"
        if (!shouldShow) return

        val frame = currentEstimatedFrameIndex()
        val matched = eventMarkerManager.findEventsNearFrame(frameIndex = frame, toleranceFrames = 1)
        if (matched.isEmpty()) {
            btnDelete.isEnabled = false
            btnDelete.alpha = 0.5f
            btnDelete.text = "删除当前事件"
            return
        }

        btnDelete.isEnabled = true
        btnDelete.alpha = 1f
        btnDelete.text = if (matched.size == 1) {
            when (matched.first().type) {
                EventType.ENTER -> "删除 进子房间事件"
                EventType.EXIT -> "删除 出子房间事件"
            }
        } else {
            "删除 ${matched.size} 个事件"
        }
    }

    private fun refreshDeviceHitMarkerControls() {
        val shouldShow = isVideoMode &&
            isDebugPanelEnabled &&
            isHandObserveMode() &&
            !isAwaitingDeviceHitSelection
        llEventMarkerControls.visibility = if (shouldShow) View.VISIBLE else View.GONE

        val btnMarkEnter = findViewById<Button>(R.id.btnMarkEnterEvent)
        val btnMarkExit = findViewById<Button>(R.id.btnMarkExitEvent)
        val btnJumpNext = findViewById<Button>(R.id.btnJumpNextEvent)
        val btnDelete = findViewById<Button>(R.id.btnDeleteCurrentEvent)
        if (!shouldShow) return

        btnMarkEnter.visibility = View.VISIBLE
        btnMarkExit.visibility = View.GONE
        btnJumpNext.visibility = View.VISIBLE
        btnMarkEnter.text = if (isAwaitingDeviceHitSelection) "等待点击设备..." else "记录正确命中事件"
        btnMarkEnter.isEnabled = !isAwaitingDeviceHitSelection
        btnMarkEnter.alpha = if (isAwaitingDeviceHitSelection) 0.6f else 1f
        btnJumpNext.text = "跳转到下一个事件"

        val frame = currentEstimatedFrameIndex()
        val matched = deviceHitMarkerManager.findEventsNearFrame(frameIndex = frame, toleranceFrames = 1)
        if (matched.isEmpty()) {
            btnDelete.visibility = View.GONE
            return
        }

        btnDelete.visibility = View.VISIBLE
        btnDelete.isEnabled = true
        btnDelete.alpha = 1f
        btnDelete.text = if (matched.size == 1) {
            "删除 ${matched.first().deviceName} 事件"
        } else {
            "删除 ${matched.size} 个事件"
        }
    }

    private fun addMarkedEvent(type: EventType) {
        if (!isVideoMode) {
            Toast.makeText(this, "当前不是视频模式", Toast.LENGTH_SHORT).show()
            return
        }
        val timestampMs = currentVideoTimestampMs()
        val frameIndex = currentEstimatedFrameIndex(timestampMs)
        when (eventMarkerManager.addEvent(type, frameIndex, timestampMs)) {
            EventMarkerManager.AddResult.ADDED -> {
                Toast.makeText(this, "已记录 ${eventTypeLabel(type)} @f=$frameIndex", Toast.LENGTH_SHORT).show()
            }
            EventMarkerManager.AddResult.DUPLICATE -> {
                Toast.makeText(this, "同类型同帧事件已存在，已忽略", Toast.LENGTH_SHORT).show()
            }
        }
        refreshEventMarkerUi()
    }

    private fun jumpToNextMarkedEvent() {
        val currentMs = currentVideoTimestampMs()
        val next = eventMarkerManager.findNextEventAfter(currentMs)
        if (next == null) {
            Toast.makeText(this, "无事件", Toast.LENGTH_SHORT).show()
            return
        }
        videoFeeder?.seekToMs(next.timestampMs.toInt())
        scheduleEventMarkerUiRefresh()
    }

    private fun jumpToNextDeviceHitEvent() {
        val currentMs = currentVideoTimestampMs()
        val next = deviceHitMarkerManager.findNextEventAfter(currentMs)
        if (next == null) {
            Toast.makeText(this, "无事件", Toast.LENGTH_SHORT).show()
            return
        }
        videoFeeder?.seekToMs(next.timestampMs.toInt())
        scheduleEventMarkerUiRefresh()
    }

    private fun confirmDeleteCurrentMarkedEvents() {
        val frame = currentEstimatedFrameIndex()
        val matched = eventMarkerManager.findEventsNearFrame(frameIndex = frame, toleranceFrames = 1)
        if (matched.isEmpty()) {
            Toast.makeText(this, "当前帧无事件", Toast.LENGTH_SHORT).show()
            refreshEventMarkerControls()
            return
        }
        val summary = if (matched.size == 1) {
            "${eventTypeLabel(matched.first().type)}事件"
        } else {
            "${matched.size}个事件"
        }
        AlertDialog.Builder(this)
            .setTitle("删除事件")
            .setMessage("确认删除当前帧附近的$summary？")
            .setPositiveButton("删除") { _, _ ->
                val removed = eventMarkerManager.removeEventsNearFrame(
                    frameIndex = frame,
                    toleranceFrames = 1
                )
                Toast.makeText(this, "已删除 ${removed.size} 个事件", Toast.LENGTH_SHORT).show()
                resetEventValidationTracking(clearRuntimeEvents = false)
                refreshEventMarkerUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteCurrentDeviceHitEvents() {
        val frame = currentEstimatedFrameIndex()
        val matched = deviceHitMarkerManager.findEventsNearFrame(frameIndex = frame, toleranceFrames = 1)
        if (matched.isEmpty()) {
            Toast.makeText(this, "当前帧无事件", Toast.LENGTH_SHORT).show()
            refreshEventMarkerUi()
            return
        }
        val summary = if (matched.size == 1) {
            "${matched.first().deviceName} 事件"
        } else {
            "${matched.size}个事件"
        }
        AlertDialog.Builder(this)
            .setTitle("删除事件")
            .setMessage("确认删除当前帧附近的$summary？")
            .setPositiveButton("删除") { _, _ ->
                val removed = deviceHitMarkerManager.removeEventsNearFrame(
                    frameIndex = frame,
                    toleranceFrames = 1
                )
                Toast.makeText(this, "已删除 ${removed.size} 个事件", Toast.LENGTH_SHORT).show()
                refreshEventMarkerUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun bindEventMarkersToVideo(videoKey: String?) {
        if (boundEventVideoKey == videoKey && boundDeviceHitVideoKey == videoKey) return
        boundEventVideoKey = videoKey
        eventMarkerManager.bindVideo(videoKey)
        bindDeviceHitMarkersToVideo(videoKey)
        resetEventValidationTracking(clearRuntimeEvents = true)
    }

    private fun bindDeviceHitMarkersToVideo(videoKey: String?) {
        if (boundDeviceHitVideoKey == videoKey) return
        boundDeviceHitVideoKey = videoKey
        deviceHitMarkerManager.bindVideo(videoKey)
    }

    private fun syncDeviceHitSelectionUi() {
        val selecting = isAwaitingDeviceHitSelection
        Log.i(
            "DeviceHitSelect",
            "syncUi selecting=$selecting handMode=${isHandObserveMode()} audioMode=${isAudioObserveMode()} debug=$isDebugPanelEnabled " +
                "normalControls=${llNormalControls.visibility} eventControls=${llEventMarkerControls.visibility} " +
                "editor=${editorView.visibility} radar=${flRadarContainer.visibility} overlay=${overlayView.visibility}"
        )
        overlayView.setDeviceSelectionMode(
            active = selecting,
            prompt = if (selecting) "请点击设备，点击空白处取消" else null
        )
        if (selecting) {
            if (selectionSavedEditorVisibility == null) {
                selectionSavedEditorVisibility = editorView.visibility
            }
            if (selectionSavedRadarVisibility == null) {
                selectionSavedRadarVisibility = flRadarContainer.visibility
            }
            editorView.visibility = View.GONE
            flRadarContainer.visibility = View.GONE
            llNormalControls.visibility = View.GONE
            llRightActionControls.visibility = View.GONE
            llEventMarkerControls.visibility = View.GONE
            cardCounter.visibility = View.GONE
            overlayView.visibility = View.VISIBLE
            applyUiLayerMode(UiLayerMode.DEVICE_SELECTION)
            overlayView.invalidate()
            Log.i(
                "DeviceHitSelect",
                "syncUi applied selecting=true editor=${editorView.visibility} radar=${flRadarContainer.visibility} overlay=${overlayView.visibility}"
            )
            return
        }
        selectionSavedEditorVisibility?.let { editorView.visibility = it }
        selectionSavedRadarVisibility?.let { flRadarContainer.visibility = it }
        selectionSavedEditorVisibility = null
        selectionSavedRadarVisibility = null
        if (llEditorControls.visibility != View.VISIBLE && flRadarContainer.visibility != View.VISIBLE) {
            llNormalControls.visibility = View.VISIBLE
            llRightActionControls.visibility = View.VISIBLE
        }
        applyUiLayerMode(if (llEditorControls.visibility == View.VISIBLE) UiLayerMode.EDITING else UiLayerMode.NORMAL)
        Log.i(
            "DeviceHitSelect",
            "syncUi applied selecting=false editor=${editorView.visibility} radar=${flRadarContainer.visibility} overlay=${overlayView.visibility}"
        )
    }

    private fun applyUiLayerMode(mode: UiLayerMode) {
        when (mode) {
            UiLayerMode.NORMAL -> {
                overlayView.bringToFront()
                if (cardCounter.visibility == View.VISIBLE) {
                    cardCounter.bringToFront()
                }
                if (editorView.visibility == View.VISIBLE) {
                    editorView.bringToFront()
                }
                if (llNormalControls.visibility == View.VISIBLE) {
                    llNormalControls.bringToFront()
                }
                if (llRightActionControls.visibility == View.VISIBLE) {
                    llRightActionControls.bringToFront()
                }
                if (llEventMarkerControls.visibility == View.VISIBLE) {
                    llEventMarkerControls.bringToFront()
                }
                if (flRadarContainer.visibility == View.VISIBLE) {
                    flRadarContainer.bringToFront()
                }
                if (llEditorControls.visibility == View.VISIBLE) {
                    llEditorControls.bringToFront()
                }
            }
            UiLayerMode.EDITING -> {
                overlayView.bringToFront()
                if (editorView.visibility == View.VISIBLE) {
                    editorView.bringToFront()
                }
                if (flRadarContainer.visibility == View.VISIBLE) {
                    flRadarContainer.bringToFront()
                }
                if (llEditorControls.visibility == View.VISIBLE) {
                    llEditorControls.bringToFront()
                }
            }
            UiLayerMode.DEVICE_SELECTION -> {
                overlayView.bringToFront()
            }
        }
    }

    private fun isHandDebugMarkerMode(): Boolean {
        return isHandObserveMode() && isDebugPanelEnabled
    }

    private fun armDeviceHitSelection() {
        if (!isVideoMode) {
            Toast.makeText(this, "当前不是视频模式", Toast.LENGTH_SHORT).show()
            return
        }
        if (RoomRepository.getDevices().isEmpty()) {
            Toast.makeText(this, "当前没有设备可选", Toast.LENGTH_SHORT).show()
            return
        }
        pendingDeviceHitTimestampMs = currentVideoTimestampMs()
        pendingDeviceHitFrameIndex = currentEstimatedFrameIndex(pendingDeviceHitTimestampMs ?: 0L)
        isAwaitingDeviceHitSelection = true
        Log.i(
            "DeviceHitSelect",
            "armSelection ts=$pendingDeviceHitTimestampMs frame=$pendingDeviceHitFrameIndex devices=${RoomRepository.getDevices().size}"
        )
        syncDeviceHitSelectionUi()
        refreshEventMarkerUi()
    }

    private fun onDeviceTappedForMarker(device: DeviceConfig): Boolean {
        if (!isHandDebugMarkerMode() || !isAwaitingDeviceHitSelection) return false
        if (!isVideoMode) return false
        val timestampMs = pendingDeviceHitTimestampMs ?: currentVideoTimestampMs()
        val frameIndex = pendingDeviceHitFrameIndex ?: currentEstimatedFrameIndex(timestampMs)
        Log.i(
            "DeviceHitSelect",
            "confirmDeviceTap id=${device.id} name=${device.name} frame=$frameIndex ts=$timestampMs"
        )
        when (
            deviceHitMarkerManager.addEvent(
                deviceId = device.id,
                deviceName = device.name,
                frameIndex = frameIndex,
                timestampMs = timestampMs
            )
        ) {
            DeviceHitMarkerManager.AddResult.ADDED -> {
                Toast.makeText(this, "已记录 ${device.name} @f=$frameIndex", Toast.LENGTH_SHORT).show()
            }
            DeviceHitMarkerManager.AddResult.DUPLICATE_FRAME -> {
                Toast.makeText(this, "当前帧已有设备命中事件，已忽略", Toast.LENGTH_SHORT).show()
            }
        }
        isAwaitingDeviceHitSelection = false
        pendingDeviceHitTimestampMs = null
        pendingDeviceHitFrameIndex = null
        syncDeviceHitSelectionUi()
        refreshEventMarkerUi()
        return true
    }

    private fun cancelDeviceHitSelection(): Boolean {
        if (!isAwaitingDeviceHitSelection) return false
        Log.i(
            "DeviceHitSelect",
            "cancelSelection pendingFrame=$pendingDeviceHitFrameIndex pendingTs=$pendingDeviceHitTimestampMs"
        )
        isAwaitingDeviceHitSelection = false
        pendingDeviceHitTimestampMs = null
        pendingDeviceHitFrameIndex = null
        syncDeviceHitSelectionUi()
        refreshEventMarkerUi()
        Toast.makeText(this, "已取消选择设备", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun refreshDebugPanelMode() {
        if (!isDebugPanelEnabled || !isHandObserveMode()) {
            overlayView.setDebugPanelOverride(null, null)
            overlayView.setHandDebugPanelExtraLines(emptyList())
            return
        }
        val currentFrame = currentEstimatedFrameIndex()
        val currentEvent = deviceHitMarkerManager
            .findEventsNearFrame(frameIndex = currentFrame, toleranceFrames = 1)
            .firstOrNull()
        val lines = mutableListOf<String>()
        lines += "模式=设备命中事件标注"
        lines += "状态=${if (isAwaitingDeviceHitSelection) "等待点击设备" else "可记录"}"
        lines += "当前视频标注数=${deviceHitMarkerManager.getEvents().size}"
        lines += if (currentEvent != null) {
            "当前帧事件=${currentEvent.deviceName}"
        } else {
            "当前帧事件=无"
        }
        lines += "操作=记录后点击设备，跳转/删除沿用时间线"
        overlayView.setDebugPanelOverride(null, null)
        overlayView.setHandDebugPanelExtraLines(lines)
    }

    private fun resetEventValidationTracking(clearRuntimeEvents: Boolean) {
        if (clearRuntimeEvents) {
            runtimeValidationEvents.clear()
        }
        matchedMarkedEventKeys.clear()
        alertedMarkedEventKeys.clear()
        matchedRuntimeByMarkedKey.clear()
    }

    private data class ValidationRuntimeEvent(
        val runtime: RuntimeRoomEvent,
        val fromName: String,
        val toName: String
    )

    private fun appendRuntimeEventsForValidation(
        events: List<PresenceSwitchEvent>,
        livingRoomId: String?,
        roomNameById: Map<String, String>,
        timestampMs: Long,
        frameIndex: Int
    ): List<ValidationRuntimeEvent> {
        if (events.isEmpty() || livingRoomId == null) return emptyList()
        val mappedEvents = mutableListOf<ValidationRuntimeEvent>()
        for (event in events) {
            val mappedType = mapPresenceEventType(event, livingRoomId) ?: continue
            val runtimeEvent = RuntimeRoomEvent(
                type = mappedType,
                frameIndex = frameIndex,
                timestampMs = timestampMs
            )
            runtimeValidationEvents.addLast(runtimeEvent)
            val fromName = roomNameById[event.fromRoomId] ?: event.fromRoomId
            val toName = roomNameById[event.toRoomId] ?: event.toRoomId
            mappedEvents.add(
                ValidationRuntimeEvent(
                    runtime = runtimeEvent,
                    fromName = fromName,
                    toName = toName
                )
            )
        }
        val keepFrom = timestampMs - 15_000L
        while (runtimeValidationEvents.isNotEmpty() &&
            runtimeValidationEvents.first().timestampMs < keepFrom
        ) {
            runtimeValidationEvents.removeFirst()
        }
        return mappedEvents
    }

    private fun mapPresenceEventType(
        event: PresenceSwitchEvent,
        livingRoomId: String
    ): EventType? {
        return when {
            event.fromRoomId == livingRoomId -> EventType.ENTER
            event.toRoomId == livingRoomId -> EventType.EXIT
            else -> null
        }
    }

    private fun markedEventKey(event: MarkedEvent): String {
        return "${event.type}|${event.frameIndex}|${event.timestampMs}"
    }

    private fun markedEventRef(event: MarkedEvent, markedEvents: List<MarkedEvent>): String {
        val key = markedEventKey(event)
        val seq = markedEvents.indexOfFirst { markedEventKey(it) == key }
            .let { if (it >= 0) it + 1 else -1 }
        val seqText = if (seq > 0) "#$seq" else "#?"
        return "事件[$seqText,type=${eventTypeLabel(event.type)},f=${event.frameIndex},ms=${event.timestampMs}]"
    }

    private fun eventTypeLabel(type: EventType): String {
        return when (type) {
            EventType.ENTER -> "进子房间"
            EventType.EXIT -> "出子房间"
        }
    }

    private fun formatSignedOffsetMs(deltaMs: Long): String {
        return if (deltaMs >= 0L) "+${deltaMs}ms" else "${deltaMs}ms"
    }

    private fun formatBeijingTime(epochMs: Long): String {
        return synchronized(beijingTimeFormatter) {
            beijingTimeFormatter.format(Date(epochMs))
        }
    }

    private fun buildMissMatchMessage(
        type: EventType,
        route: String?,
        offsetText: String
    ): String {
        val routePart = if (route.isNullOrBlank()) "" else " $route"
        return "漏匹配:${eventTypeLabel(type)}$routePart,$offsetText"
    }

    private fun buildNoReasonableMatchMessage(
        type: EventType,
        route: String?,
        nearestOffsetText: String
    ): String {
        val routePart = if (route.isNullOrBlank()) "" else " $route"
        return "无合理匹配:${eventTypeLabel(type)}$routePart, 最近合理匹配=$nearestOffsetText"
    }

    private fun nearestRuntimeDeltaMs(
        runtime: RuntimeRoomEvent,
        markedEvents: List<MarkedEvent>
    ): Long? {
        val nearest = markedEvents
            .asSequence()
            .filter { it.type == runtime.type }
            .minByOrNull { kotlin.math.abs(it.timestampMs - runtime.timestampMs) }
            ?: return null
        return runtime.timestampMs - nearest.timestampMs
    }

    private fun buildNearestOffsetForRuntime(
        runtime: RuntimeRoomEvent,
        markedEvents: List<MarkedEvent>
    ): String {
        val delta = nearestRuntimeDeltaMs(runtime, markedEvents)
        if (delta == null) {
            return "最近偏差=无可比事件"
        }
        return "最近偏差=${formatSignedOffsetMs(delta)}"
    }

    private fun buildNearestOffsetForMarked(marked: MarkedEvent, nowMs: Long): String {
        val nearestRuntime = runtimeValidationEvents
            .asSequence()
            .filter { it.type == marked.type }
            .minByOrNull { kotlin.math.abs(it.timestampMs - marked.timestampMs) }
        val delta = if (nearestRuntime != null) {
            nearestRuntime.timestampMs - marked.timestampMs
        } else {
            nowMs - marked.timestampMs
        }
        return "最近偏差=${formatSignedOffsetMs(delta)}"
    }

    private fun maybeRunSmartMatchValidation(
        runtimeEvents: List<ValidationRuntimeEvent>,
        nowMs: Long
    ) {
        if (!isVideoMode) return
        val pauseEnabled = AppSettings.isSmartMatchPauseEnabled
        val markedEvents = eventMarkerManager.getEvents()
        if (markedEvents.isEmpty()) return
        val windowMs = AppSettings.eventMissPauseWindowMs.toLong()
        var didReturnByRuntimeBranch = false
        var didScanOverdueBranch = false
        var overdueTriggered = false
        for (runtimeEvent in runtimeEvents) {
            if (handleRuntimeEventMatching(runtimeEvent, markedEvents, windowMs, pauseEnabled)) {
                didReturnByRuntimeBranch = true
                logValidationTick(
                    nowMs = nowMs,
                    windowMs = windowMs,
                    runtimeEventsCount = runtimeEvents.size,
                    markedEventsCount = markedEvents.size,
                    didReturnByRuntimeBranch = didReturnByRuntimeBranch,
                    didScanOverdueBranch = didScanOverdueBranch,
                    overdueTriggered = overdueTriggered
                )
                refreshEventMarkerUi()
                return
            }
        }
        if (!pauseEnabled) {
            logValidationTick(
                nowMs = nowMs,
                windowMs = windowMs,
                runtimeEventsCount = runtimeEvents.size,
                markedEventsCount = markedEvents.size,
                didReturnByRuntimeBranch = didReturnByRuntimeBranch,
                didScanOverdueBranch = didScanOverdueBranch,
                overdueTriggered = overdueTriggered
            )
            refreshEventMarkerUi()
            return
        }
        if (currentPlayState != PlayState.PLAYING) {
            logValidationTick(
                nowMs = nowMs,
                windowMs = windowMs,
                runtimeEventsCount = runtimeEvents.size,
                markedEventsCount = markedEvents.size,
                didReturnByRuntimeBranch = didReturnByRuntimeBranch,
                didScanOverdueBranch = didScanOverdueBranch,
                overdueTriggered = overdueTriggered
            )
            refreshEventMarkerUi()
            return
        }
        didScanOverdueBranch = true
        for (marked in markedEvents) {
            val key = markedEventKey(marked)
            if (key in matchedMarkedEventKeys || key in alertedMarkedEventKeys) {
                continue
            }
            if (nowMs < marked.timestampMs + windowMs) {
                continue
            }
            alertedMarkedEventKeys.add(key)
            val waitedMs = (nowMs - marked.timestampMs).coerceAtLeast(0L)
            val nearestOffset = buildNearestOffsetForMarked(marked, nowMs)
            val missOffset = "实际等待=${formatSignedOffsetMs(waitedMs)}"
            val message = buildMissMatchMessage(
                type = marked.type,
                route = null,
                offsetText = missOffset
            )
            copySmartMatchDiagnostic(
                reason = "无匹配事件(标注超窗)",
                runtimeEvent = null,
                markedEvents = markedEvents,
                windowMs = windowMs,
                nearestOffset = nearestOffset
            )
            pauseForSmartMatchAnomaly(message)
            Log.w("EventValidation", "overdue_unmatched $message windowMs=$windowMs nowMs=$nowMs")
            overdueTriggered = true
            break
        }
        logValidationTick(
            nowMs = nowMs,
            windowMs = windowMs,
            runtimeEventsCount = runtimeEvents.size,
            markedEventsCount = markedEvents.size,
            didReturnByRuntimeBranch = didReturnByRuntimeBranch,
            didScanOverdueBranch = didScanOverdueBranch,
            overdueTriggered = overdueTriggered
        )
        refreshEventMarkerUi()
    }

    private fun logValidationTick(
        nowMs: Long,
        windowMs: Long,
        runtimeEventsCount: Int,
        markedEventsCount: Int,
        didReturnByRuntimeBranch: Boolean,
        didScanOverdueBranch: Boolean,
        overdueTriggered: Boolean
    ) {
        if (!AppSettings.isPauseDecisionLogOnSwitchEnabled) return
        Log.i(
            "EventValidationTick",
            "nowMs=$nowMs windowMs=$windowMs runtimeEvents=$runtimeEventsCount " +
                "markedEvents=$markedEventsCount didReturnByRuntime=$didReturnByRuntimeBranch " +
                "didScanOverdue=$didScanOverdueBranch overdueTriggered=$overdueTriggered " +
                "playState=$currentPlayState"
        )
    }

    private fun handleRuntimeEventMatching(
        runtimeEvent: ValidationRuntimeEvent,
        markedEvents: List<MarkedEvent>,
        windowMs: Long,
        pauseEnabled: Boolean
    ): Boolean {
        val runtime = runtimeEvent.runtime
        val candidates = markedEvents.filter { marked ->
            marked.type == runtime.type &&
                kotlin.math.abs(marked.timestampMs - runtime.timestampMs) <= windowMs
        }
        if (candidates.isEmpty()) {
            if (!pauseEnabled) return false
            val route = "${runtimeEvent.fromName}->${runtimeEvent.toName}"
            val nearestDelta = nearestRuntimeDeltaMs(runtime, markedEvents)
            val offsetText = nearestDelta?.let { formatSignedOffsetMs(it) } ?: "无可比事件"
            val nearestOffset = buildNearestOffsetForRuntime(runtime, markedEvents)
            val message = buildNoReasonableMatchMessage(
                type = runtime.type,
                route = route,
                nearestOffsetText = offsetText
            )
            copySmartMatchDiagnostic(
                reason = "无匹配事件(运行时事件)",
                runtimeEvent = runtimeEvent,
                markedEvents = markedEvents,
                windowMs = windowMs,
                nearestOffset = nearestOffset
            )
            pauseForSmartMatchAnomaly(message)
            Log.w("EventValidation", "runtime_no_match $message")
            return true
        }

        val unmatchedCandidates = candidates.filter { candidate ->
            markedEventKey(candidate) !in matchedMarkedEventKeys
        }
        if (unmatchedCandidates.isNotEmpty()) {
            val chosen = unmatchedCandidates.minWithOrNull(
                compareBy<MarkedEvent> { kotlin.math.abs(it.timestampMs - runtime.timestampMs) }
                    .thenBy { kotlin.math.abs(it.frameIndex - runtime.frameIndex) }
            ) ?: return false
            val chosenKey = markedEventKey(chosen)
            matchedMarkedEventKeys.add(chosenKey)
            alertedMarkedEventKeys.remove(chosenKey)
            matchedRuntimeByMarkedKey[chosenKey] = runtimeEvent
            val delta = runtime.timestampMs - chosen.timestampMs
            val eventRef = markedEventRef(chosen, markedEvents)
            val message = "事件类型:${eventTypeLabel(runtime.type)} ${runtimeEvent.fromName}->${runtimeEvent.toName} (已经匹配 $eventRef, 偏差=${formatSignedOffsetMs(delta)})"
            showCenterBanner(message, CenterBannerDomain.ROOM)
            Log.i("EventValidation", "runtime_matched $message")
            return false
        }

        val duplicated = candidates.minWithOrNull(
            compareBy<MarkedEvent> { kotlin.math.abs(it.timestampMs - runtime.timestampMs) }
                .thenBy { kotlin.math.abs(it.frameIndex - runtime.frameIndex) }
        ) ?: return false
        val nearestOffset = buildNearestOffsetForRuntime(runtime, markedEvents)
        val duplicatedRef = markedEventRef(duplicated, markedEvents)
        val message = "事件类型:${eventTypeLabel(runtime.type)} ${runtimeEvent.fromName}->${runtimeEvent.toName} (异常重复匹配 $duplicatedRef, $nearestOffset)"
        if (!pauseEnabled) {
            Log.w("EventValidation", "runtime_duplicate_match_ignored $message")
            return false
        }
        copySmartMatchDiagnostic(
            reason = "异常重复匹配",
            runtimeEvent = runtimeEvent,
            markedEvents = markedEvents,
            windowMs = windowMs,
            nearestOffset = nearestOffset
        )
        pauseForSmartMatchAnomaly(message)
        Log.w("EventValidation", "runtime_duplicate_match $message")
        return true
    }

    private fun copySmartMatchDiagnostic(
        reason: String,
        runtimeEvent: ValidationRuntimeEvent?,
        markedEvents: List<MarkedEvent>,
        windowMs: Long,
        nearestOffset: String?
    ) {
        val nowMs = System.currentTimeMillis()
        val report = buildSmartMatchDiagnosticReport(
            nowMs = nowMs,
            reason = reason,
            runtimeEvent = runtimeEvent,
            markedEvents = markedEvents,
            windowMs = windowMs,
            nearestOffset = nearestOffset
        )
        try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("smart_match_diag", report))
        } catch (e: Exception) {
            Log.w("EventValidation", "copy diagnostic failed: ${e.message}")
        }
    }

    private fun buildSmartMatchDiagnosticReport(
        nowMs: Long,
        reason: String,
        runtimeEvent: ValidationRuntimeEvent?,
        markedEvents: List<MarkedEvent>,
        windowMs: Long,
        nearestOffset: String?
    ): String {
        val videoPos = currentVideoTimestampMs()
        val currentFrame = currentEstimatedFrameIndex(videoPos)
        val builder = StringBuilder()
        builder.appendLine("=== 智能匹配诊断快照 ===")
        builder.appendLine("timeMs=$nowMs (北京时间=${formatBeijingTime(nowMs)})")
        builder.appendLine("playState=$currentPlayState")
        builder.appendLine("videoPosMs=$videoPos frame=$currentFrame")
        builder.appendLine("windowMs=$windowMs")
        builder.appendLine("reason=$reason")
        nearestOffset?.let { builder.appendLine("nearestOffset=$it") }
        if (runtimeEvent != null) {
            val runtime = runtimeEvent.runtime
            builder.appendLine(
                "runtimeEvent=事件类型:${eventTypeLabel(runtime.type)} " +
                    "${runtimeEvent.fromName}->${runtimeEvent.toName} " +
                    "frame=${runtime.frameIndex} ms=${runtime.timestampMs}"
            )
        } else {
            builder.appendLine("runtimeEvent=-")
        }
        builder.appendLine("--- markedEvents(all) ---")
        if (markedEvents.isEmpty()) {
            builder.appendLine("(empty)")
        } else {
            markedEvents.forEachIndexed { index, marked ->
                val key = markedEventKey(marked)
                val matchedRuntime = matchedRuntimeByMarkedKey[key]
                val state = when {
                    matchedRuntime != null -> {
                        val delta = matchedRuntime.runtime.timestampMs - marked.timestampMs
                        "已匹配(${eventTypeLabel(marked.type)}), 路径=${matchedRuntime.fromName}->${matchedRuntime.toName}, runtimeF=${matchedRuntime.runtime.frameIndex}, runtimeMs=${matchedRuntime.runtime.timestampMs}, 偏差=${formatSignedOffsetMs(delta)}"
                    }
                    key in alertedMarkedEventKeys -> "已告警未匹配"
                    else -> "未匹配"
                }
                builder.appendLine(
                    "[${index + 1}] type=${eventTypeLabel(marked.type)} frame=${marked.frameIndex} ms=${marked.timestampMs} state=$state"
                )
            }
        }
        builder.appendLine("--- runtimeRecent ---")
        if (runtimeValidationEvents.isEmpty()) {
            builder.appendLine("(empty)")
        } else {
            runtimeValidationEvents.toList().takeLast(12).forEachIndexed { index, runtime ->
                builder.appendLine(
                    "[${index + 1}] type=${eventTypeLabel(runtime.type)} frame=${runtime.frameIndex} ms=${runtime.timestampMs}"
                )
            }
        }
        return builder.toString()
    }

    private fun pauseForSmartMatchAnomaly(message: String) {
        if (currentPlayState == PlayState.PLAYING) {
            val pauseButton = findViewById<Button>(R.id.btnPause)
            togglePause(pauseButton)
        }
        showCenterBanner(message, CenterBannerDomain.ROOM)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun onValidationBannerLongPressed() {
        copySmartMatchDiagnostic(
            reason = "长按匹配信息区",
            runtimeEvent = null,
            markedEvents = eventMarkerManager.getEvents(),
            windowMs = AppSettings.eventMissPauseWindowMs.toLong(),
            nearestOffset = null
        )
    }

    private fun onSeekBackwardRequested() {
        val beforePos = videoFeeder?.getCurrentPositionMs()
        val beforePlay = videoFeeder?.isPlaying()
        if (currentPlayState == PlayState.STILL) {
            videoFeeder?.seekBackwardFrame()
        } else {
            videoFeeder?.seekBackward(5)
        }
        val afterPos = videoFeeder?.getCurrentPositionMs()
        val action = if (currentPlayState == PlayState.STILL) "-1frame" else "-5s"
        logPlayerDiag(
            "seek action=$action playState=$currentPlayState " +
                "beforePos=${beforePos ?: -1} afterPos=${afterPos ?: -1} " +
                "beforePlaying=${beforePlay ?: false} afterPlaying=${videoFeeder?.isPlaying() ?: false}"
        )
        refreshEventMarkerUi()
        scheduleEventMarkerUiRefresh()
    }

    private fun onSeekForwardRequested() {
        val beforePos = videoFeeder?.getCurrentPositionMs()
        val beforePlay = videoFeeder?.isPlaying()
        if (currentPlayState == PlayState.STILL) {
            if (AppSettings.isClipboardDebugOnStepEnabled) {
                armUnlockClipboardCapture("+1帧")
            }
            lastPlusOneSeekDebug = videoFeeder?.seekForwardFrame()
        } else {
            videoFeeder?.seekForward(5)
        }
        val afterPos = videoFeeder?.getCurrentPositionMs()
        val action = if (currentPlayState == PlayState.STILL) "+1frame" else "+5s"
        val step = lastPlusOneSeekDebug
        logPlayerDiag(
            "seek action=$action playState=$currentPlayState " +
                "beforePos=${beforePos ?: -1} afterPos=${afterPos ?: -1} " +
                "beforePlaying=${beforePlay ?: false} afterPlaying=${videoFeeder?.isPlaying() ?: false} " +
                "stepBefore=${step?.beforeMs ?: -1} stepTarget=${step?.targetMs ?: -1} stepAfter=${step?.afterCallMs ?: -1}"
        )
        refreshEventMarkerUi()
        scheduleEventMarkerUiRefresh()
    }

    /**
     * 武装一次 unlock 调试抓取窗口：
     * 在 +1帧 后的短时间内，如果发生 unlock，就把快照复制到剪贴板。
     */
    private fun armUnlockClipboardCapture(trigger: String) {
        unlockClipboardArmedUntilMs = System.currentTimeMillis() + unlockClipboardWindowMs
        unlockClipboardCaptured = false
        unlockClipboardTrigger = trigger
        lastPlusOneSeekDebug = null
    }

    private fun tryCaptureUnlockDebugToClipboard(unlockMessage: String) {
        if (!AppSettings.isClipboardDebugOnStepEnabled) return
        val now = System.currentTimeMillis()
        if (unlockClipboardCaptured) return
        if (unlockClipboardArmedUntilMs <= 0L || now > unlockClipboardArmedUntilMs) return

        val report = buildUnlockClipboardReport(unlockMessage, now)
        val copied = copyTextToClipboard("unlock_debug", report)
        if (copied) {
            unlockClipboardCaptured = true
            unlockClipboardArmedUntilMs = 0L
            Toast.makeText(this, "已复制 unlock 调试信息到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildUnlockClipboardReport(unlockMessage: String, nowMs: Long): String {
        val panelLines = RoiLogAggregator.snapshotForPanel(includePresenceHistory = false)
        val presenceHistory = RoiLogAggregator.snapshotPresenceHistory(8)
        val recentLines = RoiLogAggregator.snapshotRecentFrames(8)
        val currentPos = videoFeeder?.getCurrentPositionMs()
        val seekDonePos = videoFeeder?.getLastSeekCompletePositionMs()
        val seekDoneTs = videoFeeder?.getLastSeekCompleteAtMs() ?: 0L
        val step = lastPlusOneSeekDebug ?: videoFeeder?.peekLastStepSeekDebug()
        val builder = StringBuilder()
        builder.appendLine("=== RoomFlow Unlock 调试快照 ===")
        builder.appendLine("timeMs=$nowMs (北京时间=${formatBeijingTime(nowMs)})")
        builder.appendLine("trigger=$unlockClipboardTrigger")
        builder.appendLine("playState=$currentPlayState")
        builder.appendLine(
            "settings newTracker=${AppSettings.isNewTrackerPredictionEnabled} " +
                "stillStandard=${AppSettings.isStillStandardFrameEnabled} " +
                "roiCrop=${AppSettings.isRoiRealCropEnabled} " +
                "roiLogMode=${AppSettings.roiLogMode} " +
                "pauseOnSwitch=${AppSettings.isPauseOnRoomSwitchEnabled} " +
                "pauseSwitchLog=${AppSettings.isPauseDecisionLogOnSwitchEnabled}"
        )
        if (step != null) {
            builder.appendLine(
                "stepSeek before=${step.beforeMs} target=${step.targetMs} afterCall=${step.afterCallMs} " +
                    "delta=${step.deltaMs} issuedAt=${step.issuedAtMs}"
            )
            builder.appendLine(
                "stepNudge applied=${step.nudgeApplied} delta=${step.nudgeDeltaMs} " +
                    "before=${step.nudgeBeforeMs ?: -1} afterCall=${step.nudgeAfterCallMs ?: -1} " +
                    "baseDigest=${step.baseDigest ?: "null"}"
            )
        } else {
            builder.appendLine("stepSeek unavailable")
        }
        builder.appendLine("seekState currentPos=${currentPos ?: -1} seekCompletePos=${seekDonePos ?: -1} seekCompleteAt=$seekDoneTs")
        builder.appendLine("unlock=$unlockMessage")
        builder.appendLine("--- panel ---")
        panelLines.forEach { builder.appendLine(it) }
        builder.appendLine("--- presenceRecent ---")
        if (presenceHistory.isEmpty()) {
            builder.appendLine("(empty)")
        } else {
            presenceHistory.forEach { builder.appendLine(it) }
        }
        builder.appendLine("--- recentFrames ---")
        recentLines.forEach { builder.appendLine(it) }
        return builder.toString()
    }

    /**
     * 长按“调试面板”按钮时导出的即时快照。
     * 用于排查 Presence/ROI 状态，不依赖 unlock 触发。
     */
    private fun buildDebugPanelClipboardReport(nowMs: Long): String {
        val panelLines = overlayView.snapshotCurrentDebugPanelLines()
        val presenceHistory = RoiLogAggregator.snapshotPresenceHistory(8)
        val currentPos = videoFeeder?.getCurrentPositionMs()
        val builder = StringBuilder()
        builder.appendLine("=== RoomFlow 调试面板快照 ===")
        builder.appendLine("timeMs=$nowMs (北京时间=${formatBeijingTime(nowMs)})")
        builder.appendLine("playState=$currentPlayState")
        builder.appendLine("videoPosMs=${currentPos ?: -1}")
        builder.appendLine(
            "settings newTracker=${AppSettings.isNewTrackerPredictionEnabled} " +
                "stillStandard=${AppSettings.isStillStandardFrameEnabled} " +
                "roiCrop=${AppSettings.isRoiRealCropEnabled} " +
                "roiLogMode=${AppSettings.roiLogMode} " +
                "pauseOnSwitch=${AppSettings.isPauseOnRoomSwitchEnabled} " +
                "pauseSwitchLog=${AppSettings.isPauseDecisionLogOnSwitchEnabled} " +
                "presenceAlgo=${roomPresenceAlgorithm.runtimeTag}"
        )
        builder.appendLine("--- panel ---")
        panelLines.forEach { builder.appendLine(it) }
        builder.appendLine("--- presenceRecent ---")
        if (presenceHistory.isEmpty()) {
            builder.appendLine("(empty)")
        } else {
            presenceHistory.forEach { builder.appendLine(it) }
        }
        return builder.toString()
    }

    private fun copyTextToClipboard(label: String, content: String): Boolean {
        return try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            if (clipboard == null) {
                Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show()
                false
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
                true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "写入剪贴板失败: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun createSeekHoldTouchListener(direction: Int): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            AppLog.i(
                "RoomStepFullDiag",
                "touch direction=$direction action=${event.actionMasked} playState=$currentPlayState " +
                    "active=$seekHoldActive holdDirection=$seekHoldDirection preview=$forwardHoldPreviewPlaying"
            )
            val shouldInterceptForwardStill = direction > 0 && currentPlayState == PlayState.STILL
            if (!shouldInterceptForwardStill) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (currentPlayState == PlayState.STILL) {
                            startSeekHold(direction)
                        }
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        stopSeekHold()
                    }
                }
                return@OnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startSeekHold(direction)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val previewWasPlaying = forwardHoldPreviewPlaying
                    stopSeekHold()
                    if (event.actionMasked == MotionEvent.ACTION_UP && !previewWasPlaying) {
                        view.performClick()
                        onSeekForwardRequested()
                    }
                }
            }
            true
        }
    }

    private fun currentKwsAudioInputMode(): AudioInputMode = kwsAudioSource.getMode()

    private fun setKwsAudioInputMode(mode: AudioInputMode): AudioInputMode {
        kwsAudioSource.setMode(mode)
        return kwsAudioSource.getMode()
    }

    private fun startSeekHold(direction: Int) {
        stopSeekHold()
        if (currentPlayState != PlayState.STILL) {
            AppLog.i("RoomStepFullDiag", "startIgnored direction=$direction playState=$currentPlayState")
            return
        }
        seekHoldActive = true
        seekHoldDirection = direction
        AppLog.i(
            "RoomStepFullDiag",
            "start direction=$direction delayMs=$seekHoldStartDelayMs frameStepMs=${videoFeeder?.getFrameStepMs() ?: 33}"
        )
        seekHoldHandler.postDelayed(seekHoldRunnable, seekHoldStartDelayMs)
    }

    private fun stopSeekHold() {
        AppLog.i(
            "RoomStepFullDiag",
            "stop active=$seekHoldActive direction=$seekHoldDirection playState=$currentPlayState preview=$forwardHoldPreviewPlaying"
        )
        seekHoldActive = false
        seekHoldDirection = 0
        seekHoldHandler.removeCallbacks(seekHoldRunnable)
        stopForwardHoldPreviewIfNeeded()
    }

    private val seekHoldRunnable = object : Runnable {
        override fun run() {
            AppLog.i(
                "RoomStepFullDiag",
                "tick active=$seekHoldActive direction=$seekHoldDirection playState=$currentPlayState preview=$forwardHoldPreviewPlaying"
            )
            if (!seekHoldActive || currentPlayState != PlayState.STILL) {
                AppLog.i(
                    "RoomStepFullDiag",
                    "tickAbort active=$seekHoldActive direction=$seekHoldDirection playState=$currentPlayState preview=$forwardHoldPreviewPlaying"
                )
                return
            }
            if (seekHoldDirection > 0) {
                startForwardHoldPreviewIfNeeded()
                return
            } else if (seekHoldDirection < 0) {
                onSeekBackwardRequested()
            }
            val stepMs = videoFeeder?.getFrameStepMs() ?: 33
            val interval = (stepMs * 2).coerceAtLeast(16)
            AppLog.i("RoomStepFullDiag", "tickReschedule intervalMs=$interval")
            seekHoldHandler.postDelayed(this, interval.toLong())
        }
    }

    private fun startForwardHoldPreviewIfNeeded() {
        if (forwardHoldPreviewPlaying) {
            AppLog.i("RoomStepFullDiag", "previewAlreadyPlaying playState=$currentPlayState")
            return
        }
        AppLog.i(
            "RoomStepFullDiag",
            "previewStart playState=$currentPlayState beforePos=${videoFeeder?.getCurrentPositionMs() ?: -1}"
        )
        forwardHoldPreviewPlaying = true
        videoFeeder?.clearStepSeekTransientState()
        videoFeeder?.setStillMode(false)
        videoFeeder?.resume()
    }

    private fun stopForwardHoldPreviewIfNeeded() {
        if (!forwardHoldPreviewPlaying) return
        AppLog.i(
            "RoomStepFullDiag",
            "previewStop playState=$currentPlayState beforePos=${videoFeeder?.getCurrentPositionMs() ?: -1}"
        )
        forwardHoldPreviewPlaying = false
        videoFeeder?.pause()
        videoFeeder?.setStillMode(true)
        refreshEventMarkerUi()
        scheduleEventMarkerUiRefresh()
    }

    private fun refreshSeekButtons() {
        val btnRewind = findViewById<Button>(R.id.btnRewind)
        val btnForward = findViewById<Button>(R.id.btnForward)
        if (!isVideoMode) {
            btnRewind.visibility = View.INVISIBLE
            btnForward.visibility = View.INVISIBLE
            refreshEventMarkerControls()
            return
        }
        btnRewind.visibility = View.VISIBLE
        btnForward.visibility = View.VISIBLE
        if (currentPlayState == PlayState.STILL) {
            btnRewind.text = "-1帧"
            btnForward.text = "+1帧"
        } else {
            btnRewind.text = "-5s"
            btnForward.text = "+5s"
        }
        refreshEventMarkerControls()
    }

    private fun refreshDebugPanelButton() {
        val btn = findViewById<Button>(R.id.btnDebugPanel)
        btn.text = "调试面板"
        btn.setBackgroundResource(
            if (isDebugPanelEnabled) {
                R.drawable.bg_side_action_button_active
            } else {
                R.drawable.bg_side_action_button_dim
            }
        )
        btn.setTextColor(Color.parseColor(if (isDebugPanelEnabled) "#FFFFFF" else "#9FB4D0"))
        refreshEventMarkerControls()
    }

    private fun togglePause(btn: Button) {
        stopSeekHold()
        if (!isVideoMode) {
            toggleLiveStillState(btn)
            return
        }
        val oldState = currentPlayState
        val beforePos = videoFeeder?.getCurrentPositionMs()
        val beforePlaying = videoFeeder?.isPlaying()
        // 暂时屏蔽“暂停中”入口，只保留“播放中 <-> 静止中”两态切换。
        // 注意：PAUSED 状态及其处理逻辑仍保留，后面如需恢复三态，只需要改回这里的切换关系。
        currentPlayState = when (currentPlayState) {
            PlayState.PLAYING -> PlayState.STILL
            PlayState.STILL -> PlayState.PLAYING
            PlayState.PAUSED -> PlayState.PLAYING
        }
        when (currentPlayState) {
            PlayState.PLAYING -> {
                videoFeeder?.clearStepSeekTransientState()
                videoFeeder?.setStillMode(false)
                overlayView.updatePointingDebugSnapshot(null)
                videoFeeder?.resume()
            }
            PlayState.STILL -> {
                videoFeeder?.pause()
                videoFeeder?.setStillMode(true)
            }
            PlayState.PAUSED -> {
                videoFeeder?.setStillMode(false)
                videoFeeder?.pause()
            }
        }
        refreshPlayStateButton(btn)
        logPlayerDiag(
            "togglePause from=$oldState to=$currentPlayState " +
                "beforePos=${beforePos ?: -1} afterPos=${videoFeeder?.getCurrentPositionMs() ?: -1} " +
                "beforePlaying=${beforePlaying ?: false} afterPlaying=${videoFeeder?.isPlaying() ?: false}"
        )
        refreshSeekButtons()
        refreshEventMarkerUi()
    }

    private fun refreshPlayStateButton(btn: Button = findViewById(R.id.btnPause)) {
        btn.text = when (currentPlayState) {
            PlayState.PLAYING -> if (isVideoMode) "[ 播放中 ]" else "[ Live 中 ]"
            PlayState.STILL -> "[ 静止中 ]"
            PlayState.PAUSED -> if (isVideoMode) "[ 播放中 ]" else "[ Live 中 ]"
        }
    }

    private fun toggleLiveStillState(btn: Button) {
        currentPlayState = when (currentPlayState) {
            PlayState.PLAYING -> PlayState.STILL
            PlayState.STILL -> PlayState.PLAYING
            PlayState.PAUSED -> PlayState.PLAYING
        }
        if (currentPlayState == PlayState.STILL) {
            freezeLivePreviewFrame()
        } else {
            clearLiveFrozenFrame()
        }
        refreshPlayStateButton(btn)
        refreshSeekButtons()
        refreshEventMarkerUi()
    }

    private fun freezeLivePreviewFrame() {
        val frozen = previewView.bitmap ?: return
        liveFrozenFrameView.setImageBitmap(frozen)
        liveFrozenFrameView.visibility = View.VISIBLE
    }

    private fun clearLiveFrozenFrame() {
        liveFrozenFrameView.setImageDrawable(null)
        liveFrozenFrameView.visibility = View.GONE
    }

    private fun logPlayerDiag(message: String) {
        if (!AppSettings.isPauseDecisionLogOnSwitchEnabled) return
        Log.i("RoomPlayerDiag", message)
    }

    /**
     * 长按播放键：执行一次“接近重启 App”的重置并从头播放视频。
     * 目标是清除追踪/ROI/Presence/人数等运行期状态，避免历史状态污染。
     */
    private fun hardRestartPlayback() {
        kwsLogClearSignal.intValue += 1
        yoloAnalyzer?.reset()
        poseAnalyzer?.resetTrackingState()
        roomPresenceAlgorithm.reset()
        roomPresenceChangeLogger.reset()
        roiTracker.resetSmoothing()
        handRoiTracker.resetSmoothing()
        roiMissingFrameCount = 0
        handRoiMissingFrameCount = 0
        latestHandResults = emptyList()
        latestSelectedHandIndex = null
        resetHandTranslationControl()
        videoFeeder?.nextFrameRoi = null
        videoFeeder?.nextHandFrameRoi = null
        resetEventValidationTracking(clearRuntimeEvents = true)
        lastPresenceCountsForPause = null
        lastPresenceAnomalyDumpKey = null
        overlayView.updateRoiBox(null, isTracking = false, isSparse = false)
        overlayView.updateHandRoiBox(null, isTracking = false)
        overlayView.updateHandData(emptyList(), null)
        overlayView.setRoiRatio(null)

        val allRooms = RoomRepository.getAllRooms()
        allRooms.forEach { room ->
            room.personCount = 0
            room.persistentPersonCount = 0
        }
        refreshOverlayDisplay()
        tvRoomCount.text = getString(R.string.room_people_count, 0)

        currentPlayState = PlayState.PLAYING
        refreshPlayStateButton()
        refreshSeekButtons()

        if (isVideoMode) {
            startVideoMode()
            Toast.makeText(this, "已重置并从头播放", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        splashHideHandler.removeCallbacksAndMessages(null)
        runtimeSwitchHandler.removeCallbacksAndMessages(null)
        splashLoadingAnimator?.cancel()
        splashLoadingAnimator = null
        super.onDestroy()
        videoFeeder?.stop()
        handSmokeTester?.close()
        handSmokeTester = null
        unbindCamera()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        else permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun startVideoMode() {
        previewView.visibility = View.GONE
        applyDefaultVideoTextureLayout()
        textureView.visibility = View.VISIBLE
        textureView.alpha = 0f
        val uriString = AppSettings.testVideoUri
        if (!uriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(uriString)
                videoFeeder?.start(uri)
                lastVideoSourceKey = "uri:$uriString"
                bindEventMarkersToVideo(lastVideoSourceKey)
                refreshEventMarkerUi()
                return
            } catch (e: Exception) {
                Log.e("Main", "Invalid video uri: $uriString", e)
            }
        }
        val path = "/storage/emulated/0/Android/media/com.example.roomxxx0102/test_video.mp4"
        if (File(path).exists()) {
            videoFeeder?.start(path)
            lastVideoSourceKey = "file:$path"
            bindEventMarkersToVideo(lastVideoSourceKey)
            refreshEventMarkerUi()
        } else {
            bindEventMarkersToVideo(null)
            refreshEventMarkerUi()
            showMissingReviewVideoToast()
        }
    }

    private fun showMissingReviewVideoToast() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMissingReviewVideoToastAtMs < 1500L) return
        lastMissingReviewVideoToastAtMs = now
        Toast.makeText(
            this,
            "未配置回顾视频。请在设置中配置视频或切换为实时模式",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun applyDefaultVideoTextureLayout() {
        val parent = textureView.parent as? View ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth <= 0 || parentHeight <= 0) {
            textureView.post { applyDefaultVideoTextureLayout() }
            return
        }
        val defaultRatio = 16f / 9f
        val parentRatio = parentWidth.toFloat() / parentHeight
        val finalWidth: Int
        val finalHeight: Int
        if (defaultRatio > parentRatio) {
            finalWidth = parentWidth
            finalHeight = (parentWidth / defaultRatio).toInt()
        } else {
            finalHeight = parentHeight
            finalWidth = (parentHeight * defaultRatio).toInt()
        }
        val params = textureView.layoutParams
        if (params.width != finalWidth || params.height != finalHeight) {
            params.width = finalWidth
            params.height = finalHeight
            textureView.layoutParams = params
        }
    }

    private fun resolveVideoSourceKey(): String? {
        val uriString = AppSettings.testVideoUri
        if (!uriString.isNullOrBlank()) {
            return "uri:$uriString"
        }
        val path = "/storage/emulated/0/Android/media/com.example.roomxxx0102/test_video.mp4"
        return if (File(path).exists()) "file:$path" else null
    }

    private fun resolvePlaybackAudioSourceSpec(): PlaybackVideoAudioSource.SourceSpec? {
        val uriString = AppSettings.testVideoUri
        if (!uriString.isNullOrBlank()) {
            return try {
                PlaybackVideoAudioSource.SourceSpec(uri = Uri.parse(uriString))
            } catch (_: Throwable) {
                null
            }
        }
        val path = "/storage/emulated/0/Android/media/com.example.roomxxx0102/test_video.mp4"
        return if (File(path).exists()) {
            PlaybackVideoAudioSource.SourceSpec(filePath = path)
        } else {
            null
        }
    }

    private fun startCameraMode() {
        textureView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        bindEventMarkersToVideo(null)
        refreshEventMarkerUi()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 960))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                    try {
                        val bitmap = image.toBitmap()
                        if (AppSettings.isPoseModeEnabled) {
                            poseAnalyzer?.analyzeBitmapAndTrackPoses(bitmap, null, drawOnOverlay = false)
                        } else {
                            yoloAnalyzer?.detectOnBitmap(bitmap, drawOnOverlay = false)
                        }
                        if (isHandObserveMode()) {
                            handSmokeTester?.detect(bitmap)
                        }
                    } catch (t: Throwable) {
                        Log.e("Main", "Camera analysis failed", t)
                    } finally {
                        image.close()
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) { Log.e("Main", "Camera Error", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) {}
    }
}
