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
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.logic.analyzer.RoiLogAggregator
import com.example.roomxxx0102.logic.analyzer.RoiTracker
import com.example.roomxxx0102.logic.analyzer.YoloAnalyzer
import com.example.roomxxx0102.logic.analyzer.YoloPoseAnalyzer
import com.example.roomxxx0102.logic.presence.PresenceOutsideMode
import com.example.roomxxx0102.logic.presence.PresenceKeypoint
import com.example.roomxxx0102.logic.presence.PresencePoint
import com.example.roomxxx0102.logic.presence.PresenceRect
import com.example.roomxxx0102.logic.presence.PresenceRoomSnapshot
import com.example.roomxxx0102.logic.presence.PresenceStrength
import com.example.roomxxx0102.logic.presence.PresenceTrackObservation
import com.example.roomxxx0102.logic.presence.PresenceDoorSnapshot
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmEngine
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.RoomPresenceChangeLogger
import com.example.roomxxx0102.logic.video.VideoFeeder
import com.example.roomxxx0102.ui.views.DetectionOverlayView
import com.example.roomxxx0102.ui.views.LivingRoomEditorView
import com.example.roomxxx0102.ui.views.TacticalMapView
import com.example.roomxxx0102.utils.BitmapTransfer
import com.example.roomxxx0102.utils.GeometryUtils
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val previewView: PreviewView by lazy { findViewById(R.id.previewView) }
    private val textureView: android.view.TextureView by lazy { findViewById(R.id.textureView) }
    private val overlayView: DetectionOverlayView by lazy { findViewById(R.id.overlayView) }
    private val editorView: LivingRoomEditorView by lazy { findViewById(R.id.editorView) }
    private val llNormalControls: View by lazy { findViewById(R.id.llNormalControls) }
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
    private var roiMissingFrameCount = 0

    private var yoloAnalyzer: YoloAnalyzer? = null
    private var poseAnalyzer: YoloPoseAnalyzer? = null
    private var videoFeeder: VideoFeeder? = null

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
    private var seekHoldActive = false
    private var seekHoldDirection = 0 // -1: 后退, +1: 前进
    private val seekHoldStartDelayMs = 500L

    // Presence 估计引擎（位置判定/房间切换事件）
    private lateinit var roomPresenceAlgorithm: PresenceAlgorithmEngine
    private val roomPresenceChangeLogger = RoomPresenceChangeLogger("ROOM_PRESENCE_CHANGE")

    private var isAddSubRoomMode = false
    private var btnAddSubRoom: Button? = null
    private var isDoorSelectMode = false
    private var isRoomAreaEditMode = false
    private var btnSelectDoor: Button? = null
    private var btnEditRoomArea: Button? = null
    
    // 🔥 模式切换 Spinner
    private var spnEditMode: Spinner? = null
    
    // 编辑状态机
    private var editorMenuState = EditorMenuState.LIVING_ROOM

    private enum class EditorMenuState {
        LIVING_ROOM,
        SUBROOM_IDLE,
        SUBROOM_ADD,
        SUBROOM_SELECTED,
        SUBROOM_DOOR_SELECT,
        SUBROOM_AREA_EDIT,
        DEVICE_SETTINGS // 🔥 新增设备设置状态
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
        RoomRepository.init(applicationContext)
        AppSettings.init(applicationContext)
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
            val observedTargets = results.map { pose ->
                val box = pose.box
                PresenceTrackObservation(
                    trackId = pose.id,
                    landingPoint = PresencePoint(
                        x = pose.landingPoint.x.toDouble(),
                        y = pose.landingPoint.y.toDouble()
                    ),
                    strength = toPresenceStrength(pose),
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
            val roomNameById = allRooms.associate { it.id to it.name }
            RoiLogAggregator.updatePresenceDebug(
                algoVersion = roomPresenceAlgorithm.versionId,
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
                Log.d("RoomPresence", "$line algo=${roomPresenceAlgorithm.versionId}")
            }
            val presenceSwitchBanner = presenceResult.events.lastOrNull()?.let { event ->
                val fromName = roomNameById[event.fromRoomId] ?: event.fromRoomId
                val toName = roomNameById[event.toRoomId] ?: event.toRoomId
                "位置切换: $fromName->$toName (${event.reason})"
            }

            val livingRoomCount = livingRoom?.personCount ?: 0

            // ROI 计算
            val srcW = if (bitmap != null) bitmap.width else 1920
            val srcH = if (bitmap != null) bitmap.height else 1080
            val targetBox = if (logicResults.isNotEmpty()) logicResults[0].box else null

            if (targetBox != null) {
                roiMissingFrameCount = 0
            } else {
                roiMissingFrameCount++
            }

            val isSearching = roiMissingFrameCount >= 10
            val roi = if (isSearching) {
                roiTracker.resetSmoothing()
                null
            } else {
                roiTracker.calculate(srcW, srcH, targetBox)
            }
            
            val cropRoi = if (AppSettings.isRoiRealCropEnabled && roi != null) roi else null
            videoFeeder?.nextFrameRoi = cropRoi

            val isTracking = roiMissingFrameCount < 10 && targetBox != null
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
                if (presenceResult.events.isNotEmpty() &&
                    seekHoldActive &&
                    seekHoldDirection > 0 &&
                    currentPlayState == PlayState.STILL
                ) {
                    stopSeekHold()
                    overlayView.showUnlockBanner("检测到房间切换，已停止+1帧长按")
                }
                if (presenceResult.events.isNotEmpty() &&
                    AppSettings.isPauseOnRoomSwitchEnabled &&
                    currentPlayState == PlayState.PLAYING
                ) {
                    val pauseButton = findViewById<Button>(R.id.btnPause)
                    togglePause(pauseButton)
                    overlayView.showUnlockBanner("检测到房间切换，已自动暂停")
                }
                overlayView.updatePoseData(results, bitmap, time)
                overlayView.postInvalidate() 
                tvRoomCount.text = getString(R.string.room_people_count, livingRoomCount)
                
                if (flRadarContainer.visibility == View.VISIBLE) {
                    tacticalMapView.updateData(allRooms, personLocations)
                }

                overlayView.updateRoiBox(roi, isTracking, isSparse)
                overlayView.setRoiRatio(roiRatio)
                poseAnalyzer?.consumeUnlockMessage()?.let { msg ->
                    overlayView.showUnlockBanner(msg)
                    tryCaptureUnlockDebugToClipboard(msg)
                }
                if (presenceSwitchBanner != null) {
                    overlayView.showUnlockBanner(presenceSwitchBanner)
                }
            }
        }

        videoFeeder = VideoFeeder(this, textureView).apply {
            this.yoloAnalyzer = this@MainActivity.yoloAnalyzer
            this.poseAnalyzer = this@MainActivity.poseAnalyzer
            this.onStepNudge = { msg ->
                overlayView.showUnlockBanner(msg)
            }
        }

        setupButtons()
        checkPermissionsAndStart()
        refreshOverlayDisplay()
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
            "targetRoomContainmentRatio" to "trc",
            "sourceRoomContainmentRatio" to "src",
            "sourceRoomOutsidePoseScore" to "sops",
            "exitOutsidePoseScoreThreshold" to "sopsTh",
            "poseAverageConfidence" to "pac",
            "sourceRoomStayScore" to "srss",
            "poseTransitionScore" to "pts",
            "doorAssistScore" to "das",
            "switchConfidenceScore" to "scs",
            "switchThreshold" to "scsTh",
            "exitSourceRoomScoreThreshold" to "srssTh",
            "dynamicNearDist" to "dnd",
            "doorAdvanceDelta" to "dad",
            "doorLateralDelta" to "dld",
            "doorAdvanceLateralRatio" to "dalr",
            "exitPoseMinConfidence" to "pacMin",
            "grayPoseMinConfidence" to "gpm"
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
            "dd", "dps", "gpc", "des", "trc", "src", "sops",
            "pac", "srss", "pts", "das", "scs", "scsTh",
            "srssTh", "sopsTh", "dnd", "dad", "dld", "dalr"
        )
        val metrics = metricKeys.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        ) { key ->
            kv[key] ?: "-"
        }

        val extraKeys = listOf("pacMin", "gpm")
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
        return "h=[f,t,fr,md,er,lc,sg] m=[dd,dps,gpc,des,trc,src,sops,pac,srss,pts,das,scs,scsTh,srssTh,sopsTh,dnd,dad,dld,dalr] x=[pacMin,gpm]"
    }

    private fun setupButtons() {
        val btnPause = findViewById<Button>(R.id.btnPause)
        val btnRewind = findViewById<Button>(R.id.btnRewind)
        val btnForward = findViewById<Button>(R.id.btnForward)
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
        }
        btnDebugPanel.setOnLongClickListener {
            val report = buildDebugPanelClipboardReport(System.currentTimeMillis())
            val copied = copyTextToClipboard("debug_panel_report", report)
            if (copied) {
                Toast.makeText(this, "已复制调试面板信息", Toast.LENGTH_SHORT).show()
            }
            true
        }
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
            cardCounter.visibility = View.VISIBLE
        }
        
        // 🔥 关闭编辑按钮 (通用)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { 
            exitEditMode(save = false) 
        }
        
        // 🔥 初始化 Spinner
        spnEditMode = findViewById(R.id.spnEditMode)
        val modes = arrayOf("主房间设置", "次房间设置", "设备设置")
        // 🔥 使用自定义布局 spinner_item_dark
        val adapter = ArrayAdapter(this, R.layout.spinner_item_dark, modes)
        // 设置下拉列表的 item 样式 (可以使用 android.R.layout.simple_spinner_dropdown_item, 因为背景是 dark theme)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spnEditMode?.adapter = adapter
        
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
                EditorMenuState.DEVICE_SETTINGS -> {}
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
                editorView.endSubRoomRegionEdit()
                transitionTo(EditorMenuState.SUBROOM_SELECTED)
            } else if (editorMenuState == EditorMenuState.DEVICE_SETTINGS) {
                Toast.makeText(this, "设备设置已保存", Toast.LENGTH_SHORT).show()
            } else {
                // 保存不退出
                performSave()
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnRenameRoom).setOnClickListener {
            val roomId = editorView.selectedRoomId
            val room = RoomRepository.getSubRooms().find { it.id == roomId }
            room?.let { showRenameDialog(it) }
        }
        findViewById<Button>(R.id.btnDeleteRoom).setOnClickListener {
            val roomId = editorView.selectedRoomId
            if (roomId != null) {
                RoomRepository.deleteRoom(roomId)
                editorView.setSubRooms(RoomRepository.getSubRooms())
                editorView.clearSelection()
            }
        }
        
        // 设备管理按钮 (占位)
        btnAddDevice.setOnClickListener {
            Toast.makeText(this, "添加设备功能开发中...", Toast.LENGTH_SHORT).show()
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

    private fun enterEditMode() {
        captureCurrentFrame()
        editorView.backgroundBitmap = BitmapTransfer.capturedFrame
        editorView.drawBackground = false
        // 默认进入主房间模式
        spnEditMode?.setSelection(0)
        applyModeSelection(LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL)
        toggleEditModeUI(true)
    }
    
    private fun enterDeviceSettingsMode() {
        transitionTo(EditorMenuState.DEVICE_SETTINGS)
        // 隐藏 EditorView, 显示设备 UI
        editorView.visibility = View.GONE
        llDeviceSettings.visibility = View.VISIBLE
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
        isAddSubRoomMode = nextAdd
        isDoorSelectMode = nextDoor
        isRoomAreaEditMode = nextArea
        editorView.setAddSubRoomArmed(nextAdd)
        editorView.setDoorSelectArmed(nextDoor)
        editorView.setRoomAreaEditArmed(nextArea)
        if (prev == EditorMenuState.SUBROOM_DOOR_SELECT && state != EditorMenuState.SUBROOM_DOOR_SELECT) {
            editorView.discardPendingDoorSelection()
        editorView.endSubRoomRegionEdit()
        }
        if (prev == EditorMenuState.SUBROOM_AREA_EDIT && state != EditorMenuState.SUBROOM_AREA_EDIT) {
            editorView.endSubRoomRegionEdit()
        }
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
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnFinish.visibility = View.VISIBLE
            }
            EditorMenuState.DEVICE_SETTINGS -> {
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.GONE
                btnFinish.visibility = View.GONE
            }
        }
    }

    private fun applyModeSelection(mode: LivingRoomEditorView.EditorMode) {
        // 恢复 EditorView 显示 (如果之前在设备模式)
        editorView.visibility = View.VISIBLE
        llDeviceSettings.visibility = View.GONE
        
        editorView.currentMode = mode
        transitionTo(
            if (mode == LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL)
                EditorMenuState.LIVING_ROOM
            else
                EditorMenuState.SUBROOM_IDLE
        )

        if (mode == LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL) {
            val livingRoom = RoomRepository.getAllRooms().find { it.isSovereignTerritory }
            editorView.setHistoryVertices(livingRoom?.boundaryVertices ?: emptyList())
            editorView.setSubRooms(RoomRepository.getSubRooms())
        } else {
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
    }

    private fun toggleEditModeUI(isEditing: Boolean) {
        llNormalControls.visibility = if (isEditing) View.GONE else View.VISIBLE
        llEditorControls.visibility = if (isEditing) View.VISIBLE else View.GONE
        cardCounter.visibility = if (isEditing) View.GONE else View.VISIBLE
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
            roomPresenceAlgorithm = PresenceAlgorithmRegistry.create(selectedId)
            roomPresenceChangeLogger.reset()
            Log.i("RoomPresence", "Presence算法已切换: ${roomPresenceAlgorithm.versionId}")
        }
    }

    private fun applySettings() {
        overlayView.setDebugBoxState(AppSettings.isDebugBoxShown)
        overlayView.setCenterPointState(AppSettings.isCenterPointShown)
        overlayView.setPoseState(AppSettings.isPoseModeEnabled)
        videoFeeder?.isPoseMode = AppSettings.isPoseModeEnabled
        if (!isVideoMode) { unbindCamera(); startCameraMode() }
    }

    private fun onSeekBackwardRequested() {
        if (currentPlayState == PlayState.STILL) {
            videoFeeder?.seekBackwardFrame()
        } else {
            videoFeeder?.seekBackward(5)
        }
    }

    private fun onSeekForwardRequested() {
        if (currentPlayState == PlayState.STILL) {
            if (AppSettings.isClipboardDebugOnStepEnabled) {
                armUnlockClipboardCapture("+1帧")
            }
            lastPlusOneSeekDebug = videoFeeder?.seekForwardFrame()
        } else {
            videoFeeder?.seekForward(5)
        }
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
        builder.appendLine("timeMs=$nowMs")
        builder.appendLine("trigger=$unlockClipboardTrigger")
        builder.appendLine("playState=$currentPlayState")
        builder.appendLine(
            "settings newTracker=${AppSettings.isNewTrackerPredictionEnabled} " +
                "stillStandard=${AppSettings.isStillStandardFrameEnabled} " +
                "roiCrop=${AppSettings.isRoiRealCropEnabled} " +
                "roiLogMode=${AppSettings.roiLogMode} " +
                "pauseOnSwitch=${AppSettings.isPauseOnRoomSwitchEnabled}"
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
        builder.appendLine("timeMs=$nowMs")
        builder.appendLine("playState=$currentPlayState")
        builder.appendLine("videoPosMs=${currentPos ?: -1}")
        builder.appendLine(
            "settings newTracker=${AppSettings.isNewTrackerPredictionEnabled} " +
                "stillStandard=${AppSettings.isStillStandardFrameEnabled} " +
                "roiCrop=${AppSettings.isRoiRealCropEnabled} " +
                "roiLogMode=${AppSettings.roiLogMode} " +
                "pauseOnSwitch=${AppSettings.isPauseOnRoomSwitchEnabled} " +
                "presenceAlgo=${roomPresenceAlgorithm.versionId}"
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
    }

    private fun refreshDebugPanelButton() {
        val btn = findViewById<Button>(R.id.btnDebugPanel)
        btn.text = if (isDebugPanelEnabled) "调试面板:开" else "调试面板:关"
    }

    private fun togglePause(btn: Button) {
        currentPlayState = when (currentPlayState) {
            PlayState.PLAYING -> PlayState.STILL
            PlayState.STILL -> PlayState.PAUSED
            PlayState.PAUSED -> PlayState.PLAYING
        }
        
        when (currentPlayState) {
            PlayState.PLAYING -> {
                btn.text = "[ 播放中 ]"
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
        refreshSeekButtons()
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
        roiMissingFrameCount = 0
        videoFeeder?.nextFrameRoi = null
        overlayView.updateRoiBox(null, isTracking = false, isSparse = false)
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
                return
            } catch (e: Exception) {
                Log.e("Main", "Invalid video uri: $uriString", e)
            }
        }
        val path = "/storage/emulated/0/Android/media/com.example.roomxxx0102/test_video.mp4"
        if (File(path).exists()) {
            videoFeeder?.start(path)
            lastVideoSourceKey = "file:$path"
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
