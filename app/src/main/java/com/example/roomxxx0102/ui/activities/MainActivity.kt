package com.example.roomxxx0102.ui.activities

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import com.example.roomxxx0102.logic.analyzer.YoloAnalyzer
import com.example.roomxxx0102.logic.analyzer.YoloPoseAnalyzer
import com.example.roomxxx0102.logic.video.VideoFeeder
import com.example.roomxxx0102.ui.views.DetectionOverlayView
import com.example.roomxxx0102.ui.views.LivingRoomEditorView
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

    private var yoloAnalyzer: YoloAnalyzer? = null
    private var poseAnalyzer: YoloPoseAnalyzer? = null
    private var videoFeeder: VideoFeeder? = null

    private var isVideoMode = true
    private var isPaused = false
    private var currentLivingRoomBoundary: List<PointF> = emptyList()

    private var isAddSubRoomMode = false
    private var btnAddSubRoom: Button? = null
    private var isDoorSelectMode = false
    private var isRoomAreaEditMode = false
    private var btnSelectDoor: Button? = null
    private var btnEditRoomArea: Button? = null
    private var editorMenuState = EditorMenuState.LIVING_ROOM

    // 缁熶竴绠＄悊缂栬緫鑿滃崟鐨勫眰绾х姸鎬?
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
            var peopleInLivingRoom = 0
            if (currentLivingRoomBoundary.size >= 3) {
                for (pose in results) {
                    if (GeometryUtils.isPointInPolygon(pose.landingPoint, currentLivingRoomBoundary)) {
                        peopleInLivingRoom++
                    }
                }
            } else {
                peopleInLivingRoom = results.size
            }
            runOnUiThread {
                overlayView.updatePoseData(results, bitmap, time)
                tvRoomCount.text = "瀹㈠巺浜烘暟: $peopleInLivingRoom"
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

        // --- ??????? ---
        findViewById<Button>(R.id.btnModeSwitcher).setOnClickListener { toggleRoomMode(it as Button) }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { editorView.undo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { editorView.clear() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            if (isAddSubRoomMode) {
                setAddSubRoomMode(false)
            } else {
                exitEditMode(save = false)
            }
        }
        findViewById<Button>(R.id.btnFinish).setOnClickListener { exitEditMode(save = true) }

        // ?? ??????
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
                editorView.clearSelection() // ??????
            }
        }

        val editorControls = llEditorControls as? LinearLayout
        btnAddSubRoom = Button(this).apply {
            text = "澧炲姞娆℃埧闂?
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { enterAddSubRoomMode() }
        }
        btnAddSubRoom?.let { editorControls?.addView(it) }

        btnSelectDoor = Button(this).apply {
            text = "鎴块棬閫夋嫨"
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { enterDoorSelectMode() }
        }
        btnSelectDoor?.let { editorControls?.addView(it) }

        btnEditRoomArea = Button(this).apply {
            text = "鎴块棿鍖哄煙缂栬緫"
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9C27B0"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { enterRoomAreaEditMode() }
        }
        btnEditRoomArea?.let { editorControls?.addView(it) }
    }

    private fun enterEditMode() {
        if (isVideoMode) videoFeeder?.pause()
        isPaused = true
        findViewById<Button>(R.id.btnPause).text = "鈻讹笍 鎾斁"

        captureCurrentFrame()
        editorView.backgroundBitmap = BitmapTransfer.capturedFrame

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
        Toast.makeText(this, "璇风偣鍑婚渶瑕佸鍔犵殑浣嶇疆", Toast.LENGTH_SHORT).show()
    }

    private fun enterDoorSelectMode() {
        setSubRoomActionMode(doorSelect = true, roomAreaEdit = false)
        Toast.makeText(this, "璇风偣鍑绘煇涓€鏉¤竟浣滀负鍑哄叆鍙?, Toast.LENGTH_SHORT).show()
    }

    private fun enterRoomAreaEditMode() {
        setSubRoomActionMode(doorSelect = false, roomAreaEdit = true)
        Toast.makeText(this, "璇锋嫋鍔ㄦ埧闂翠綅缃繘琛岀紪杈?, Toast.LENGTH_SHORT).show()
    }

    private fun setSubRoomActionMode(doorSelect: Boolean, roomAreaEdit: Boolean) {
        isDoorSelectMode = doorSelect
        isRoomAreaEditMode = roomAreaEdit
        editorView.setDoorSelectArmed(doorSelect)
        editorView.setRoomAreaEditArmed(roomAreaEdit)
        if (editorView.currentMode != LivingRoomEditorView.EditorMode.SUB_ROOM_ANCHOR) return
        val hasSelection = editorView.selectedRoomId != null
        if (!hasSelection) {
            setEditorMenuState(EditorMenuState.SUBROOM_IDLE)
            return
        }
        val nextState = when {
            doorSelect -> EditorMenuState.SUBROOM_DOOR_SELECT
            roomAreaEdit -> EditorMenuState.SUBROOM_AREA_EDIT
            else -> EditorMenuState.SUBROOM_SELECTED
        }
        setEditorMenuState(nextState)
    }

    private fun setAddSubRoomMode(active: Boolean) {
        isAddSubRoomMode = active
        editorView.setAddSubRoomArmed(active)
        if (editorView.currentMode != LivingRoomEditorView.EditorMode.SUB_ROOM_ANCHOR) return
        if (active) {
            setEditorMenuState(EditorMenuState.SUBROOM_ADD)
        } else {
            val hasSelection = editorView.selectedRoomId != null
            val nextState = if (hasSelection) {
                when {
                    isDoorSelectMode -> EditorMenuState.SUBROOM_DOOR_SELECT
                    isRoomAreaEditMode -> EditorMenuState.SUBROOM_AREA_EDIT
                    else -> EditorMenuState.SUBROOM_SELECTED
                }
            } else {
                EditorMenuState.SUBROOM_IDLE
            }
            setEditorMenuState(nextState)
        }
    }

    private fun setEditorMenuState(state: EditorMenuState) {
        editorMenuState = state
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
                btnSwitcher.text = "鍒囪嚦娆℃埧闂?
                btnSwitcher.backgroundTintList = colorPrimary
                btnUndo.visibility = View.VISIBLE
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
                btnSwitcher.visibility = View.VISIBLE
                btnSwitcher.text = "鍒囪嚦瀹㈠巺"
                btnSwitcher.backgroundTintList = colorSubRoom
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.VISIBLE
                btnSelectDoor?.visibility = View.GONE
                btnEditRoomArea?.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
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
                btnFinish.visibility = View.GONE
            }
            EditorMenuState.SUBROOM_SELECTED,
            EditorMenuState.SUBROOM_DOOR_SELECT,
            EditorMenuState.SUBROOM_AREA_EDIT -> {
                btnSwitcher.visibility = View.VISIBLE
                btnSwitcher.text = "鍒囪嚦瀹㈠巺"
                btnSwitcher.backgroundTintList = colorSubRoom
                btnUndo.visibility = View.GONE
                btnClear.visibility = View.GONE
                btnAddSubRoom?.visibility = View.GONE
                btnSelectDoor?.visibility = View.VISIBLE
                btnEditRoomArea?.visibility = View.VISIBLE
                btnRename.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                btnFinish.visibility = View.VISIBLE
            }
        }
    }

    private fun applyModeSelection(mode: LivingRoomEditorView.EditorMode) {
        editorView.currentMode = mode
        isAddSubRoomMode = false
        editorView.setAddSubRoomArmed(false)
        setSubRoomActionMode(doorSelect = false, roomAreaEdit = false)

        if (mode == LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL) {
            setEditorMenuState(EditorMenuState.LIVING_ROOM)
            val livingRoom = RoomRepository.getAllRooms().find { it.isSovereignTerritory }
            editorView.setHistoryVertices(livingRoom?.boundaryVertices ?: emptyList())
        } else {
            setEditorMenuState(EditorMenuState.SUBROOM_IDLE)
            editorView.setSubRooms(RoomRepository.getSubRooms())
            editorView.setOnSubRoomListener(
                onAdd = { point ->
                    setAddSubRoomMode(false)
                    showAddSubRoomDialog(point)
                },
                onSelected = { room ->
                    if (room == null) {
                        setSubRoomActionMode(doorSelect = false, roomAreaEdit = false)
                        return@setOnSubRoomListener
                    }
                    val nextState = when {
                        isDoorSelectMode -> EditorMenuState.SUBROOM_DOOR_SELECT
                        isRoomAreaEditMode -> EditorMenuState.SUBROOM_AREA_EDIT
                        else -> EditorMenuState.SUBROOM_SELECTED
                    }
                    setEditorMenuState(nextState)
                },
                onUpdated = { room ->
                    RoomRepository.updateRoom(room)
                }
            )
        }
    }
    private fun showAddSubRoomDialog(point: PointF) {
        val input = EditText(this).apply { hint = "鎴块棿鍚嶇О" }
        AlertDialog.Builder(this).setTitle("鏂板缓鎴块棿").setView(input)
            .setPositiveButton("纭畾") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    RoomRepository.addNewRoom(name, point)
                    editorView.setSubRooms(RoomRepository.getSubRooms())
                }
            }.setNegativeButton("鍙栨秷", null).show()
    }

    private fun showRenameDialog(room: RoomConfig) {
        val input = EditText(this).apply { setText(room.name) }
        AlertDialog.Builder(this).setTitle("淇敼鍚嶇О").setView(input)
            .setPositiveButton("纭畾") { _, _ ->
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
        }
        overlayView.setSubRooms(allRooms.filter { !it.isSovereignTerritory })
    }

    private fun toggleEditModeUI(isEditing: Boolean) {
        llNormalControls.visibility = if (isEditing) View.GONE else View.VISIBLE
        llEditorControls.visibility = if (isEditing) View.VISIBLE else View.GONE
        cardCounter.visibility = if (isEditing) View.GONE else View.VISIBLE
        editorView.visibility = if (isEditing) View.VISIBLE else View.GONE
        overlayView.visibility = if (isEditing) View.GONE else View.VISIBLE
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
        if (isVideoMode && !isPaused) videoFeeder?.resume()
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
        btn.text = if (isPaused) "鈻讹笍 鎾斁" else "鈴笍 鏆傚仠"
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
        val path = "/storage/emulated/0/Android/media/com.example.roomxxx0102/test_video.mp4"
        if (File(path).exists()) videoFeeder?.start(path)
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




