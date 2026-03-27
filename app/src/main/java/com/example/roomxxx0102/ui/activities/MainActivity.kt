package com.example.roomxxx0102.ui.activities

import com.example.roomxxx0102.data.model.BoundaryVertex
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
import android.media.MediaPlayer
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.roomxxx0102.logic.pointing.HandObservation
import com.example.roomxxx0102.logic.pointing.PointingDecision
import com.example.roomxxx0102.logic.pointing.TargetRect
import com.example.roomxxx0102.logic.pointing.TriggeredPointingResolver
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
import com.example.roomxxx0102.logic.validation.EventMarkerManager
import com.example.roomxxx0102.logic.validation.EventType
import com.example.roomxxx0102.logic.validation.MarkedEvent
import com.example.roomxxx0102.logic.validation.RuntimeRoomEvent
import com.example.roomxxx0102.logic.video.VideoFeeder
import com.example.roomxxx0102.ui.views.DetectionOverlayView
import com.example.roomxxx0102.ui.views.LivingRoomEditorView
import com.example.roomxxx0102.ui.views.TacticalMapView
import com.example.roomxxx0102.utils.BitmapTransfer
import com.example.roomxxx0102.utils.GeometryUtils
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

class MainActivity : ComponentActivity() {

    private val previewView: PreviewView by lazy { findViewById(R.id.previewView) }
    private val textureView: android.view.TextureView by lazy { findViewById(R.id.textureView) }
    private val overlayView: DetectionOverlayView by lazy { findViewById(R.id.overlayView) }
    private val editorView: LivingRoomEditorView by lazy { findViewById(R.id.editorView) }
    private val llNormalControls: View by lazy { findViewById(R.id.llNormalControls) }
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
    private val roiTracker = RoiTracker()
    private val handRoiTracker = RoiTracker(baseRoiSizePx = 224f, adaptiveResizeEnabled = false)
    private var roiMissingFrameCount = 0
    private var handRoiMissingFrameCount = 0

    private var yoloAnalyzer: YoloAnalyzer? = null
    private var poseAnalyzer: YoloPoseAnalyzer? = null
    private var handSmokeTester: HandSmokeTester? = null
    private var videoFeeder: VideoFeeder? = null
    private var isHandOverlayPressed = false
    @Volatile private var latestHandResults: List<List<HandSmokeTester.HandPoint>> = emptyList()
    @Volatile private var latestSelectedHandIndex: Int? = null
    private val pointingResolver = TriggeredPointingResolver()
    private val pointingGuideMinQuality = 0.45f
    private var pointingTargetLabelById: Map<String, String> = emptyMap()

    private var isVideoMode = true
    private var currentLivingRoomBoundary: List<PointF> = emptyList()
    private var lastVideoSourceKey: String? = null

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
    private var seekHoldActive = false
    private var seekHoldDirection = 0 // -1: 后退, +1: 前进
    private val seekHoldStartDelayMs = 500L
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
    private val runtimeValidationEvents: ArrayDeque<RuntimeRoomEvent> = ArrayDeque()
    private val matchedMarkedEventKeys: MutableSet<String> = mutableSetOf()
    private val alertedMarkedEventKeys: MutableSet<String> = mutableSetOf()
    private val matchedRuntimeByMarkedKey: MutableMap<String, ValidationRuntimeEvent> = mutableMapOf()
    private var boundEventVideoKey: String? = null

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
        AppSettings.init(applicationContext)
        RoomRepository.init(applicationContext)
        eventMarkerManager.init(applicationContext)
        ensurePresenceAlgorithmVersion()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()
        setContentView(R.layout.activity_main)

        yoloAnalyzer = YoloAnalyzer(this, overlayView)
        poseAnalyzer = YoloPoseAnalyzer(this) { results, bitmap, time ->
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
            val presenceNowMs = videoFeeder?.getCurrentPositionMs()?.toLong() ?: -1L
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
                posMs = videoFeeder?.getCurrentPositionMs()
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
                        overlayView.showUnlockBanner(
                            "检测到房间切换($switchTypeLabel): $fromName->$toName，偏差=$switchOffsetText，已停止+1帧长按"
                        )
                    }
                    val shouldAutoPause = AppSettings.isPauseOnRoomSwitchEnabled &&
                        playStateBefore != PlayState.PAUSED
                    var didAutoPause = false
                    if (shouldAutoPause) {
                        val pauseButton = findViewById<Button>(R.id.btnPause)
                        togglePause(pauseButton)
                        overlayView.showUnlockBanner(
                            "检测到房间切换($switchTypeLabel): $fromName->$toName，偏差=$switchOffsetText，已自动暂停"
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
                            overlayView.showUnlockBanner(
                                "检测到人数扣减($likelyCause): $deltaText，已自动暂停"
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
                poseAnalyzer?.consumeUnlockMessage()?.let { msg ->
                    Log.i("RoomLockDiag", "ui_consume $msg")
                    overlayView.showUnlockBanner(msg)
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
                        overlayView.updateHandData(hands, latestSelectedHandIndex)
                    }
                }
                it.onPointingObservation = { observation ->
                    handleTriggeredPointingObservation(observation)
                }
            }
            this.onStepNudge = { msg ->
                overlayView.showUnlockBanner(msg)
            }
        }

        setupButtons()
        refreshEventMarkerUi()
        checkPermissionsAndStart()
        refreshOverlayDisplay()
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

    private fun buildPointingTargetRects(
        imageWidth: Int,
        imageHeight: Int
    ): Pair<List<TargetRect>, Map<String, String>> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList<TargetRect>() to emptyMap()
        val devices = RoomRepository.getDevices()
        val imageDiagonal = hypot(imageWidth.toFloat(), imageHeight.toFloat())
        val paddingPx = max(imageDiagonal * 0.02f, 24f)
        val targets = devices.mapNotNull { device ->
            if (device.polygon.isEmpty()) return@mapNotNull null
            val xs = device.polygon.map { it.x * imageWidth }
            val ys = device.polygon.map { it.y * imageHeight }
            val rect = RectF(
                (xs.minOrNull() ?: return@mapNotNull null) - paddingPx,
                (ys.minOrNull() ?: return@mapNotNull null) - paddingPx,
                (xs.maxOrNull() ?: return@mapNotNull null) + paddingPx,
                (ys.maxOrNull() ?: return@mapNotNull null) + paddingPx
            )
            if (rect.width() <= 0f || rect.height() <= 0f) {
                null
            } else {
                TargetRect(
                    id = device.id,
                    rect = rect
                )
            }
        }
        val labels = devices.associate { device ->
            device.id to device.name
        }
        return targets to labels
    }

    private fun startTriggeredPointingSession() {
        val bitmap = textureView.bitmap
        if (bitmap == null) {
            overlayView.showUnlockBanner("指向识别启动失败")
            return
        }
        val (targets, labels) = buildPointingTargetRects(bitmap.width, bitmap.height)
        pointingTargetLabelById = labels
        val startTimestampMs = SystemClock.uptimeMillis()
        pointingResolver.startSession(targets, startTimestampMs)
        Log.i(
            "TriggeredPointingResolver",
            "POINTING|START|targets=${targets.joinToString { "${it.id}:${it.rect.centerX().toInt()},${it.rect.centerY().toInt()}" }}"
        )
    }

    private fun handleTriggeredPointingObservation(observation: HandObservation) {
        if (!pointingResolver.isActive()) return
        val decision = pointingResolver.submitFrame(observation)
        val liveSnapshot = pointingResolver.latestDebugSnapshot()?.takeIf { snapshot ->
            snapshot.smoothedOrigin != null &&
                snapshot.smoothedDir != null &&
                snapshot.frameQuality >= pointingGuideMinQuality
        }
        runOnUiThread {
            overlayView.updatePointingLiveSnapshot(liveSnapshot)
        }
        if (decision !is PointingDecision.Pending) {
            runOnUiThread {
                handleTriggeredPointingDecision(decision)
                if (isHandOverlayPressed) {
                    startTriggeredPointingSession()
                }
            }
        }
    }

    private fun handleTriggeredPointingDecision(decision: PointingDecision) {
        when (decision) {
            is PointingDecision.Pending -> Unit
            is PointingDecision.Recognized -> {
                val label = pointingTargetLabelById[decision.targetId] ?: decision.targetId
                val message = "命中：$label (${String.format(Locale.US, "%.2f", decision.score)})"
                overlayView.showUnlockBanner(message)
                Log.i(
                    "TriggeredPointingResolver",
                    "POINTING|RECOGNIZED|target=${decision.targetId}|label=$label|score=${String.format(Locale.US, "%.3f", decision.score)}|elapsed=${decision.elapsedMs}|path=${decision.diagnostics.acceptPath}|top3=${decision.diagnostics.top3Targets}"
                )
                overlayView.updatePointingDebugSnapshot(
                    pointingResolver.latestDebugSnapshot(),
                    holdMs = 1000L
                )
            }
            is PointingDecision.Unrecognized -> {
                val message = "未识别(${decision.reason})"
                overlayView.showUnlockBanner(message)
                Log.i(
                    "TriggeredPointingResolver",
                    "POINTING|UNRECOGNIZED|reason=${decision.reason}|score=${String.format(Locale.US, "%.3f", decision.score)}|elapsed=${decision.elapsedMs}|path=${decision.diagnostics.acceptPath}|top3=${decision.diagnostics.top3Targets}"
                )
            }
        }
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
        val btnHandOverlay = findViewById<Button>(R.id.btnHandOverlay)
        btnPause.setOnClickListener { togglePause(it as Button) }
        btnPause.setOnLongClickListener {
            hardRestartPlayback()
            true
        }
        btnRewind.setOnClickListener { onSeekBackwardRequested() }
        btnForward.setOnClickListener { onSeekForwardRequested() }
        btnRewind.setOnTouchListener(createSeekHoldTouchListener(direction = -1))
        btnForward.setOnTouchListener(createSeekHoldTouchListener(direction = 1))
        val btnDebugPanel = findViewById<Button>(R.id.btnDebugPanel)
        btnDebugPanel.setOnClickListener {
            isDebugPanelEnabled = !isDebugPanelEnabled
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
        btnHandOverlay.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isHandOverlayPressed = true
                    handSmokeTester?.startConfidenceProbeSession()
                    startTriggeredPointingSession()
                    if (pointingResolver.isActive()) {
                        overlayView.showUnlockBanner("手点采样+指向识别中")
                    }
                    updateHandOverlayMode()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isHandOverlayPressed = false
                    overlayView.updatePointingLiveSnapshot(null)
                    updateHandOverlayMode()
                    true
                }
                else -> false
            }
        }
        overlayView.setOnUnlockBannerLongPressListener {
            onValidationBannerLongPressed()
        }
        btnMarkEnterEvent.setOnClickListener { addMarkedEvent(EventType.ENTER) }
        btnMarkExitEvent.setOnClickListener { addMarkedEvent(EventType.EXIT) }
        btnJumpNextEvent.setOnClickListener { jumpToNextMarkedEvent() }
        btnDeleteCurrentEvent.setOnClickListener { confirmDeleteCurrentMarkedEvents() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            captureCurrentFrame()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        refreshSeekButtons()
        refreshDebugPanelButton()

        findViewById<Button>(R.id.btnSetupRoom).setOnClickListener { enterEditMode() }
        
        findViewById<Button>(R.id.btnRadar).setOnClickListener {
            flRadarContainer.visibility = View.VISIBLE
            llNormalControls.visibility = View.GONE
            cardCounter.visibility = View.GONE
        }
        
        btnCloseRadar.setOnClickListener {
            flRadarContainer.visibility = View.GONE
            llNormalControls.visibility = View.VISIBLE
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

    private fun updateHandOverlayMode() {
        overlayView.setHandOnlyState(isHandOverlayPressed)
        overlayView.setPoseState(AppSettings.isPoseModeEnabled && !isHandOverlayPressed)
    }

    private fun toggleEditModeUI(isEditing: Boolean) {
        llNormalControls.visibility = if (isEditing) View.GONE else View.VISIBLE
        llEventMarkerControls.visibility = View.GONE
        llEditorControls.visibility = if (isEditing) View.VISIBLE else View.GONE
        cardCounter.visibility = View.GONE
        editorView.visibility = if (isEditing) View.VISIBLE else View.GONE
        
        overlayView.visibility = View.VISIBLE
        overlayView.setEditMode(isEditing)
    }

    private fun captureCurrentFrame() {
        if (isVideoMode && textureView.isAvailable) {
            BitmapTransfer.capturedFrame = textureView.getBitmap()
        }
    }

    override fun onResume() {
        super.onResume()
        ensurePresenceAlgorithmVersion()
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
        if (isVideoMode) videoFeeder?.pause()
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
        overlayView.setPointingDebugOverlayEnabled(AppSettings.isPointingDebugOverlayEnabled)
        updateHandOverlayMode()
        videoFeeder?.isPoseMode = AppSettings.isPoseModeEnabled
        if (!isVideoMode) { unbindCamera(); startCameraMode() }
    }

    private fun refreshEventMarkerUi() {
        refreshEventMarkerOverlay()
        refreshEventMarkerControls()
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

    private fun refreshEventMarkerControls() {
        val shouldShow = isVideoMode &&
            isDebugPanelEnabled &&
            currentPlayState != PlayState.PLAYING
        llEventMarkerControls.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow) return

        val btnDelete = findViewById<Button>(R.id.btnDeleteCurrentEvent)
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
        videoFeeder?.seekToMs(next.timestampMs.toInt(), MediaPlayer.SEEK_CLOSEST)
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

    private fun bindEventMarkersToVideo(videoKey: String?) {
        if (boundEventVideoKey == videoKey) return
        boundEventVideoKey = videoKey
        eventMarkerManager.bindVideo(videoKey)
        resetEventValidationTracking(clearRuntimeEvents = true)
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
            overlayView.showUnlockBanner(message)
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
        overlayView.showUnlockBanner(message)
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
        val panelLines = RoiLogAggregator.snapshotForPanel(includePresenceHistory = false)
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
        return View.OnTouchListener { _, event ->
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
            false
        }
    }

    private fun startSeekHold(direction: Int) {
        stopSeekHold()
        if (currentPlayState != PlayState.STILL) return
        seekHoldActive = true
        seekHoldDirection = direction
        seekHoldHandler.postDelayed(seekHoldRunnable, seekHoldStartDelayMs)
    }

    private fun stopSeekHold() {
        seekHoldActive = false
        seekHoldDirection = 0
        seekHoldHandler.removeCallbacks(seekHoldRunnable)
    }

    private val seekHoldRunnable = object : Runnable {
        override fun run() {
            if (!seekHoldActive || currentPlayState != PlayState.STILL) return
            if (seekHoldDirection > 0) {
                onSeekForwardRequested()
            } else if (seekHoldDirection < 0) {
                onSeekBackwardRequested()
            }
            val stepMs = videoFeeder?.getFrameStepMs() ?: 33
            val interval = (stepMs * 2).coerceAtLeast(16)
            seekHoldHandler.postDelayed(this, interval.toLong())
        }
    }

    private fun refreshSeekButtons() {
        val btnRewind = findViewById<Button>(R.id.btnRewind)
        val btnForward = findViewById<Button>(R.id.btnForward)
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
        btn.text = if (isDebugPanelEnabled) "调试面板:开" else "调试面板:关"
        refreshEventMarkerControls()
    }

    private fun togglePause(btn: Button) {
        stopSeekHold()
        val oldState = currentPlayState
        val beforePos = videoFeeder?.getCurrentPositionMs()
        val beforePlaying = videoFeeder?.isPlaying()
        currentPlayState = when (currentPlayState) {
            PlayState.PLAYING -> PlayState.STILL
            PlayState.STILL -> PlayState.PAUSED
            PlayState.PAUSED -> PlayState.PLAYING
        }
        when (currentPlayState) {
            PlayState.PLAYING -> {
                btn.text = "[ 播放中 ]"
                videoFeeder?.clearStepSeekTransientState()
                videoFeeder?.setStillMode(false)
                videoFeeder?.resume()
            }
            PlayState.STILL -> {
                btn.text = "[ 静止中 ]"
                videoFeeder?.pause()
                videoFeeder?.setStillMode(true)
            }
            PlayState.PAUSED -> {
                btn.text = "[ 暂停中 ]"
                videoFeeder?.setStillMode(false)
                videoFeeder?.pause()
            }
        }
        logPlayerDiag(
            "togglePause from=$oldState to=$currentPlayState " +
                "beforePos=${beforePos ?: -1} afterPos=${videoFeeder?.getCurrentPositionMs() ?: -1} " +
                "beforePlaying=${beforePlaying ?: false} afterPlaying=${videoFeeder?.isPlaying() ?: false}"
        )
        refreshSeekButtons()
        refreshEventMarkerUi()
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
        findViewById<Button>(R.id.btnPause).text = "[ 播放中 ]"
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
        textureView.visibility = View.VISIBLE
        previewView.visibility = View.GONE
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
                if (AppSettings.isPoseModeEnabled) imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), poseAnalyzer!!)
                else imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), yoloAnalyzer!!)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) { Log.e("Main", "Camera Error", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) {}
    }
}
