package com.example.roomxxx0102.ui.activities

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.example.roomxxx0102.logic.analyzer.RoiTracker
import com.example.roomxxx0102.logic.analyzer.YoloAnalyzer
import com.example.roomxxx0102.logic.analyzer.YoloPoseAnalyzer
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

    // 🔥 ROI Tracker
    private val roiTracker = RoiTracker()
    private var roiMissingFrameCount = 0

    private var yoloAnalyzer: YoloAnalyzer? = null
    private var poseAnalyzer: YoloPoseAnalyzer? = null
    private var videoFeeder: VideoFeeder? = null

    private var isVideoMode = true
    private var isPaused = false
    private var currentLivingRoomBoundary: List<PointF> = emptyList()
    private var lastVideoSourceKey: String? = null

    private var isAddSubRoomMode = false
    private var btnAddSubRoom: Button? = null
    private var isDoorSelectMode = false
    private var isRoomAreaEditMode = false
    private var btnSelectDoor: Button? = null
    private var btnEditRoomArea: Button? = null
    private var editorMenuState = EditorMenuState.LIVING_ROOM

    private enum class EditorMenuState {
        LIVING_ROOM,
        SUBROOM_IDLE,
        SUBROOM_ADD,
        SUBROOM_SELECTED,
        SUBROOM_DOOR_SELECT,
        SUBROOM_AREA_EDIT
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()
        setContentView(R.layout.activity_main)

        yoloAnalyzer = YoloAnalyzer(this, overlayView)
        poseAnalyzer = YoloPoseAnalyzer(this) { results, bitmap, time ->
            // 获取所有房间引用
            val allRooms = RoomRepository.getAllRooms()
            
            // 重置计数
            allRooms.forEach { it.personCount = 0 }

            // 收集所有人的位置，用于雷达图
            val personLocations = ArrayList<PointF>()

            // 遍历每个人，判断他在哪个房间
            for (pose in results) {
                personLocations.add(pose.landingPoint) // 收集位置
                for (room in allRooms) {
                    if (room.boundaryPoints.size >= 3) {
                        if (GeometryUtils.isPointInPolygon(pose.landingPoint, room.boundaryPoints)) {
                            room.personCount++
                        }
                    }
                }
            }

            // 更新 UI
            val livingRoom = allRooms.find { it.isSovereignTerritory }
            val livingRoomCount = livingRoom?.personCount ?: 0

            // 🔥 ROI 计算与状态机逻辑
            val srcW = if (bitmap != null) bitmap.width else 1920
            val srcH = if (bitmap != null) bitmap.height else 1080
            val targetBox = if (results.isNotEmpty()) results[0].box else null

            // 状态流转
            if (targetBox != null) {
                // 找到目标 -> 重置丢失计数
                roiMissingFrameCount = 0
            } else {
                // 丢失目标 -> 计数++
                roiMissingFrameCount++
            }

            val isSearching = roiMissingFrameCount >= 10
            val roi = if (isSearching) {
                // 超过阈值，全屏搜索，不给 ROI (RoiTracker 内部虽然会算，但我们传 null 给 Analyzer)
                null
            } else {
                // 还在追踪或丢失缓冲期，计算 ROI
                roiTracker.calculate(srcW, srcH, targetBox)
            }
            
            // 决定是否启用真实裁剪
            // 1. 如果还在 Searching 阶段，或者 ROI 为 null，强制全屏 (不裁剪)
            // 2. 如果开关没开，强制全屏 (不裁剪)
            val useRealCrop = AppSettings.isRoiRealCropEnabled && roi != null
            val cropRoi = if (useRealCrop) roi else null
            
            videoFeeder?.nextFrameRoi = cropRoi

            // UI 显示逻辑
            val isTracking = roiMissingFrameCount < 10 && targetBox != null
            val showRoiBox = roi != null
            val displayRoi = if (showRoiBox) roi else null
            
            // 🔥 如果启用了真实裁剪，说明我们真的很关注这个区域，虚线可以密集一点（或者实线）
            // 如果没启用真实裁剪（useRealCrop == false），说明只是“模拟追踪”，给用户看个大概，所以虚线要稀疏
            // 参数名是 isSparse (是否稀疏)，所以：开启裁剪 -> 不稀疏；关闭裁剪 -> 稀疏
            val isSparse = !AppSettings.isRoiRealCropEnabled
            
            Log.d("ROI_DEBUG", "Main ROI: $displayRoi, Tracking: $isTracking, Lost: $roiMissingFrameCount, Crop: $useRealCrop, Sparse: $isSparse")

            val roiRatio = if (roi != null && targetBox != null) {
                val personW = targetBox.width() * srcW
                val personH = targetBox.height() * srcH
                val maxPersonSide = kotlin.math.max(personW, personH)
                val roiW = roi.width() * srcW
                val roiH = roi.height() * srcH
                val roiSize = kotlin.math.min(roiW, roiH)
                if (roiSize > 0f) maxPersonSide / roiSize else null
            } else {
                null
            }

            runOnUiThread {
                overlayView.updatePoseData(results, bitmap, time)
                // 强制刷新 overlayView 以重新绘制房间人数
                overlayView.postInvalidate() 
                tvRoomCount.text = getString(R.string.room_people_count, livingRoomCount)
                
                // 🔥 如果雷达图可见，更新雷达数据
                if (flRadarContainer.visibility == View.VISIBLE) {
                    tacticalMapView.updateData(allRooms, personLocations)
                }

                // 🔥 更新 ROI 显示 (传入 isSparse)
                overlayView.updateRoiBox(displayRoi, isTracking, isSparse)
                overlayView.setRoiRatio(roiRatio)
            }
        }

        videoFeeder = VideoFeeder(this, textureView).apply {
            this.yoloAnalyzer = this@MainActivity.yoloAnalyzer
            this.poseAnalyzer = this@MainActivity.poseAnalyzer
        }

        setupButtons()
        checkPermissionsAndStart()
        refreshOverlayDisplay()
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnPause).setOnClickListener { togglePause(it as Button) }
        findViewById<Button>(R.id.btnRewind).setOnClickListener { videoFeeder?.seekBackward(5) }
        findViewById<Button>(R.id.btnForward).setOnClickListener { videoFeeder?.seekForward(5) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            captureCurrentFrame()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnSetupRoom).setOnClickListener { enterEditMode() }
        
        // 🔥 雷达入口
        findViewById<Button>(R.id.btnRadar).setOnClickListener {
            flRadarContainer.visibility = View.VISIBLE
            llNormalControls.visibility = View.GONE
            cardCounter.visibility = View.GONE // 也可以隐藏计数器，因为雷达图很干净
        }
        
        // 🔥 关闭雷达
        btnCloseRadar.setOnClickListener {
            flRadarContainer.visibility = View.GONE
            llNormalControls.visibility = View.VISIBLE
            cardCounter.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnModeSwitcher).setOnClickListener { toggleRoomMode(it as Button) }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { editorView.undo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { editorView.clear() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            when (editorMenuState) {
                EditorMenuState.SUBROOM_DOOR_SELECT -> editorView.clearPendingDoorSelection()
                EditorMenuState.SUBROOM_ADD -> setAddSubRoomMode(false)
                else -> exitEditMode(save = false)
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
            } else {
                exitEditMode(save = true)
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

        val editorControls = llEditorControls as? LinearLayout
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

    private fun enterEditMode() {
        // 保持视频播放 (不调用 pause)
        // 关键逻辑：
        // 1. 截取当前帧，仅用于让 EditorView 计算正确的宽高比和坐标 (dstRect)
        captureCurrentFrame()
        editorView.backgroundBitmap = BitmapTransfer.capturedFrame
        
        // 2. 设置不绘制背景，从而透视到底层的 TextureView (视频)
        editorView.drawBackground = false

        applyModeSelection(LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL)
        toggleEditModeUI(true)
    }

    private fun toggleRoomMode(btn: Button) {
        val nextMode = if (editorView.currentMode == LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL) {
            LivingRoomEditorView.EditorMode.SUB_ROOM_ANCHOR
        } else {
            LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL
        }
        applyModeSelection(nextMode)
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
        if (editorView.selectedRoomId == null) return
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
        }
        renderEditorMenu(state)
    }

    private fun renderEditorMenu(state: EditorMenuState) {
        val btnSwitcher = findViewById<Button>(R.id.btnModeSwitcher)
        val btnUndo = findViewById<Button>(R.id.btnUndo)
        val btnClear = findViewById<Button>(R.id.btnClear)
        val btnRename = findViewById<Button>(R.id.btnRenameRoom)
        val btnDelete = findViewById<Button>(R.id.btnDeleteRoom)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        val btnFinish = findViewById<Button>(R.id.btnFinish)
        val colorPrimary = ColorStateList.valueOf(Color.parseColor("#2196F3"))
        val colorSubRoom = ColorStateList.valueOf(Color.parseColor("#9C27B0"))

        when (state) {
            EditorMenuState.LIVING_ROOM -> {
                btnSwitcher.visibility = View.VISIBLE
                btnSwitcher.text = getString(R.string.switch_to_sub_room)
                btnSwitcher.backgroundTintList = colorPrimary
                btnUndo.visibility = View.VISIBLE
                btnClear.visibility = View.VISIBLE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnCancel.text = getString(R.string.cancel)
                btnFinish.visibility = View.VISIBLE
            }
            EditorMenuState.SUBROOM_IDLE -> {
                btnSwitcher.visibility = View.VISIBLE
                btnSwitcher.text = getString(R.string.switch_to_living_room)
                btnSwitcher.backgroundTintList = colorSubRoom
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.VISIBLE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnCancel.text = getString(R.string.cancel)
                btnFinish.visibility = View.VISIBLE
            }
            EditorMenuState.SUBROOM_ADD -> {
                btnSwitcher.visibility = View.GONE
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnCancel.text = getString(R.string.cancel)
                btnFinish.visibility = View.GONE
            }
            EditorMenuState.SUBROOM_SELECTED,
            EditorMenuState.SUBROOM_AREA_EDIT -> {
                btnSwitcher.visibility = View.VISIBLE
                btnSwitcher.text = getString(R.string.switch_to_living_room)
                btnSwitcher.backgroundTintList = colorSubRoom
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.VISIBLE
                btnEditRoomArea?.visibility = View.VISIBLE
                btnRename.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                btnCancel.text = getString(R.string.cancel)
                btnFinish.visibility = View.VISIBLE
            }
            EditorMenuState.SUBROOM_DOOR_SELECT -> {
                btnSwitcher.visibility = View.GONE
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                btnCancel.text = "解除房门绑定"
                btnFinish.visibility = View.VISIBLE
            }
        }
    }

    private fun applyModeSelection(mode: LivingRoomEditorView.EditorMode) {
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
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_title_new_room)).setView(input)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    RoomRepository.addNewRoom(name, point)
                    editorView.setSubRooms(RoomRepository.getSubRooms())
                }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun showRenameDialog(room: RoomConfig) {
        val input = EditText(this).apply { setText(room.name) }
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_title_rename)).setView(input)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                room.name = input.text.toString()
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
        
        // 🔥 修改：在编辑模式下，强制保持 overlayView 可见 (VISIBLE)
        // 之前是：overlayView.visibility = if (isEditing) View.GONE else View.VISIBLE
        overlayView.visibility = View.VISIBLE
        
        // 🔥 新增：同步编辑模式状态给 overlayView，消除重影
        overlayView.setEditMode(isEditing)
    }

    private fun captureCurrentFrame() {
        if (isVideoMode && textureView.isAvailable) {
            BitmapTransfer.capturedFrame = textureView.getBitmap()
        }
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        refreshOverlayDisplay()
        if (isVideoMode) {
            val desiredKey = resolveVideoSourceKey()
            if (desiredKey != lastVideoSourceKey) {
                startVideoMode()
            } else if (!isPaused) {
                videoFeeder?.resume()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isVideoMode) videoFeeder?.pause()
    }

    private fun applySettings() {
        overlayView.setDebugBoxState(AppSettings.isDebugBoxShown)
        overlayView.setCenterPointState(AppSettings.isCenterPointShown)
        overlayView.setPoseState(AppSettings.isPoseModeEnabled)
        videoFeeder?.isPoseMode = AppSettings.isPoseModeEnabled
        if (!isVideoMode) { unbindCamera(); startCameraMode() }
    }

    private fun togglePause(btn: Button) {
        isPaused = !isPaused
        btn.text = if (isPaused) getString(R.string.video_play) else getString(R.string.video_pause)
        if (isVideoMode) {
            if (isPaused) videoFeeder?.pause() else videoFeeder?.resume()
        } else {
            btnAddSubRoom?.visibility = View.VISIBLE
            if (isPaused) unbindCamera() else startCameraMode()
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
