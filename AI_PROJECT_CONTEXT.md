# Android Project Context: RoomFlow
> Generated at: 2026-01-05 15:27:00

## 1. Project File Structure
```text
java/
    com/
        example/
            roomxxx0102/
                data/
                    model/
                        PoseData.kt
                        RoomConfig.kt
                    repository/
                        AppSettings.kt
                        RoomRepository.kt
                logic/
                    analyzer/
                        YoloAnalyzer.kt
                        YoloPoseAnalyzer.kt
                    recorder/
                        RoomRecorder.kt
                    video/
                        VideoFeeder.kt
                ui/
                    activities/
                        LivingRoomSetupActivity.kt
                        MainActivity.kt
                        SettingsActivity.kt
                    drawers/
                        DebugBoxDrawer.kt
                        PoseDrawer.kt
                    settings/
                        RoomListFragment.kt
                        SettingsActivity.kt
                        SettingsHomeFragment.kt
                    theme/
                        Color.kt
                        Theme.kt
                        Type.kt
                    views/
                        DetectionOverlayView.kt
                        LivingRoomEditorView.kt
                utils/
                    BitmapTransfer.kt
                    GeometryUtils.kt
```

## 2. Key Classes & Signatures

### File: `PoseData.kt`
> **Description**:
**计算落地坐标 (Landing Point)**

遵循 Single Source of Truth 原则，该逻辑现在从 UI 层移至数据层。
返回归一化坐标 (0.0 ~ 1.0)。

逻辑：
1. 优先取双脚踝中点。
2. 其次取单脚踝。
3. 兜底取检测框底部中心。

```kotlin
data class Keypoint( ...
    val x: Float,
    val y: Float,
    val conf: Float
data class PoseResult( ...
    val id: Int,
    val box: RectF,
    val keypoints: List<Keypoint>,
    val score: Float,
    val isMoving: Boolean = false,
    val isConfirmed: Boolean = false // 🔥 新增：是否已锁定(曾高分且移动过)
    fun getVisibleKeypointCount(threshold: Float = 0.5f): Int
    val landingPoint: PointF
    val leftAnkle = keypoints[15]
    val rightAnkle = keypoints[16]
    val CONF_THRESHOLD = 0.5f
```

### File: `RoomConfig.kt`
> **Description**:
**房间类型枚举 (Room Type)**

定义房间在空间逻辑中的角色。

```kotlin
enum class RoomType {
data class RoomRegion( ...
    val id: String,
    val name: String,
    val type: RoomType,
    val boundaryPoints: List<PointF> = emptyList()
data class RoomConfig( ...
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val isSovereignTerritory: Boolean = false,
    var isRecorded: Boolean = false,
    var boundaryPoints: List<PointF> = emptyList(),
    var anchorPoint: PointF? = null // 🔥 新增：次房间锚点
```

### File: `AppSettings.kt`
> **Description**:
**应用全局配置 (App Settings)**

管理调试开关和全局参数。

```kotlin
object AppSettings {
    var isDebugBoxShown: Boolean = false
    var isCenterPointShown: Boolean = true
    var isPoseModeEnabled: Boolean = true // 🔥 默认开启 Pose
    fun init(context: Context)
    fun setDebugBoxShown(show: Boolean)
    fun setCenterPointShown(show: Boolean)
    fun setPoseModeEnabled(enable: Boolean)
```

### File: `RoomRepository.kt`
> **Description**:
**房间数据仓库 (Room Repository)**

负责管理房间数据的内存缓存与本地文件持久化。
使用 JSON 格式存储。

```kotlin
object RoomRepository {
    private val cachedRooms = CopyOnWriteArrayList<RoomConfig>()
    private var configFile: File? = null
    fun init(context: Context)
    fun getAllRooms(): List<RoomConfig>
    fun getSubRooms(): List<RoomConfig>
    fun addNewRoom(name: String, anchor: PointF? = null)
    val newRoom = RoomConfig(
    fun updateRoom(room: RoomConfig)
    val index = cachedRooms.indexOfFirst { it.id == room.id }
    fun deleteRoom(roomId: String): Boolean
    val room = cachedRooms.find { it.id == roomId } ?: return false
    fun markRoomAsRecorded(roomId: String)
    val index = cachedRooms.indexOfFirst { it.id == roomId }
    val room = cachedRooms[index]
    fun updateRoomBoundary(roomId: String, points: List<PointF>)
    val index = cachedRooms.indexOfFirst { it.id == roomId }
    val room = cachedRooms[index]
    val isRecorded = points.size >= 3
    fun saveRoomBoundary(roomId: String, points: List<PointF>)
    fun resetAllStatus()
    val file = configFile ?: return
    val jsonString = file.readText()
    val rootObj = JSONObject(jsonString)
    val jsonArray = rootObj.optJSONArray("rooms") ?: JSONArray()
    val roomObj = jsonArray.getJSONObject(i)
    val boundaryList = ArrayList<PointF>()
    val boundaryArray = roomObj.optJSONArray("boundary")
    val pObj = boundaryArray.getJSONObject(j)
    val x = pObj.optDouble("x", 0.0).toFloat()
    val y = pObj.optDouble("y", 0.0).toFloat()
    var anchor: PointF? = null
    val anchorObj = roomObj.optJSONObject("anchor")
    val x = anchorObj.optDouble("x", 0.0).toFloat()
    val y = anchorObj.optDouble("y", 0.0).toFloat()
    val room = RoomConfig(
    val file = configFile ?: return
    val rootObj = JSONObject()
    val jsonArray = JSONArray()
    val roomObj = JSONObject()
    val boundaryArray = JSONArray()
    val pObj = JSONObject()
    val anchorObj = JSONObject()
```

### File: `YoloAnalyzer.kt`
```kotlin
data class TrackedDetection( ...
    val id: Int,
    val cx: Float,      // 归一化中心坐标 X (0..1)
    val cy: Float,      // 归一化中心坐标 Y (0..1)
    val w: Float,       // 归一化宽度 (0..1)
    val h: Float,       // 归一化高度 (0..1)
    val score: Float,
    val isMoving: Boolean
data class LetterboxResult(val bitmap: Bitmap, val scale: Float, val dx: Float, val dy: Float) ...
private data class ObjectHistory(var cx: Float, var cy: Float, var staticFrameCount: Int = 0, var missingFrameCount: Int = 0) ...
private data class RawDetection(val rect: RectF, val score: Float) ...
class YoloAnalyzer( ...
    private val context: Context,
    private val overlayView: DetectionOverlayView
companion object {
    private val detectThreshold = 0.30f
    private val nmsThreshold = 0.45f
    private val staticConfirmThreshold = 0.60f
    private val movementThreshold = 0.02f
    private var interpreter: Interpreter? = null
    private var inputWidth = 640
    private var inputHeight = 640
    private var tensorImage: TensorImage? = null
    private var outputData: Array<Array<FloatArray>>? = null
    private val trackerMap = ConcurrentHashMap<Int, ObjectHistory>()
    private var nextObjectId = 0
    private var isChannelsFirst = true
    private var numClasses = 80
    private var numBoxes = 8400
    private val imageProcessor = ImageProcessor.Builder()
    fun reset()
    val afd = context.assets.openFd(MODEL_FILE_NAME)
    val fis = FileInputStream(afd.fileDescriptor)
    val modelBuffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    val options = Interpreter.Options()
    val inputShape = interpreter!!.getInputTensor(0).shape()
    val outputShape = interpreter!!.getOutputTensor(0).shape()
    val dim1 = outputShape[1]; val dim2 = outputShape[2]
    override fun analyze(image: ImageProxy)
    val bitmap = image.toBitmap()
    fun detectOnBitmap(bitmap: Bitmap, drawOnOverlay: Boolean = true)
    val t1 = System.currentTimeMillis()
    var letterboxedBitmap: Bitmap? = null
    val lb = letterbox(bitmap, inputWidth, inputHeight)
    val input = imageProcessor.process(tensorImage)
    val trackedResults = processAndTrack(lb.scale, lb.dx, lb.dy, bitmap.width, bitmap.height)
    val bgBitmap = if (drawOnOverlay) bitmap else null
    val rawList = ArrayList<RawDetection>()
    val matrix = outputData!![0]
    val score = if (isChannelsFirst) matrix[4][i] else matrix[i][4]
    var cx: Float; var cy: Float; var w: Float; var h: Float
    val row = matrix[i]; cx = row[0]; cy = row[1]; w = row[2]; h = row[3]
    val realCx = (cx - dx) / scale / origW
    val realCy = (cy - dy) / scale / origH
    val realW = w / scale / origW
    val realH = h / scale / origH
    val left = realCx - realW / 2
    val top = realCy - realH / 2
    val right = realCx + realW / 2
    val bottom = realCy + realH / 2
    val nmsResults = nms(rawList, nmsThreshold)
    val finalResults = ArrayList<TrackedDetection>()
    val usedTrackerIds = HashSet<Int>()
    var bestMatchId = -1
    var minDist = Float.MAX_VALUE
    val candCx = cand.rect.centerX()
    val candCy = cand.rect.centerY()
    val candW = cand.rect.width()
    val candH = cand.rect.height()
    val dist = sqrt((candCx - history.cx).pow(2) + (candCy - history.cy).pow(2))
    val history = trackerMap[bestMatchId]!!
    val moveDist = sqrt((candCx - history.cx).pow(2) + (candCy - history.cy).pow(2))
    val isMoving = moveDist > movementThreshold
    val newId = nextObjectId++
    val iterator = trackerMap.iterator()
    val entry = iterator.next()
    val result = ArrayList<RawDetection>()
    val current = list.removeAt(0)
    val iterator = list.iterator()
    val next = iterator.next()
    val iLeft = max(a.left, b.left); val iTop = max(a.top, b.top)
    val iRight = min(a.right, b.right); val iBottom = min(a.bottom, b.bottom)
    val iArea = (iRight - iLeft) * (iBottom - iTop)
    val uArea = (a.width() * a.height()) + (b.width() * b.height()) - iArea
    val srcW = src.width; val srcH = src.height
    val scale = min(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
    val newW = srcW * scale; val newH = srcH * scale
    val dx = (dstW - newW) / 2f; val dy = (dstH - newH) / 2f
    val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val matrix = Matrix().apply { postScale(scale, scale); postTranslate(dx, dy) }
```

### File: `YoloPoseAnalyzer.kt`
> **Description**:
[AI Role]: 内部状态记录类 (Internal State Record)
[Responsibility]: 记录单个被追踪主体 (Subject) 的历史轨迹和状态信息。
[Key Logic]: 用于支持 "防误触与锁定 (Anti-Ghost & Locking)" 机制，
只要 [hasEverMoved] 为 true 且 [maxHistoricalScore] 达标，该主体即被视为 "真确目标 (Confirmed)"。

```kotlin
private data class TrackedSubjectHistory( ...
    var centerX: Float,
    var centerY: Float,
    var consecutiveStaticFrames: Int = 0, // 连续静止帧数
    var consecutiveMissingFrames: Int = 0, // 连续丢失帧数
    var maxHistoricalScore: Float = 0f,   // 历史最高置信度
    var hasEverMoved: Boolean = false     // 是否曾经发生过显著移动
class YoloPoseAnalyzer( ...
    private val context: Context,
    private val onPoseAnalysisResultsUpdated: (List<PoseResult>, Bitmap?, Long) -> Unit
companion object {
    private var interpreter: Interpreter? = null
    private var modelInputWidth = 640
    private var modelInputHeight = 640
    private var tensorImage: TensorImage? = null
    private var modelOutputBuffer: Array<Array<FloatArray>>? = null
    private val activeTrackersMap = ConcurrentHashMap<Int, TrackedSubjectHistory>()
    private var nextSubjectId = 0
    private val imageProcessor = ImageProcessor.Builder()
    fun resetTrackingState()
    val assets = context.assets.list("")
    val afd = context.assets.openFd(MODEL_FILE_NAME)
    val fis = FileInputStream(afd.fileDescriptor)
    val buffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    val options = Interpreter.Options()
    val inputTensor = interpreter!!.getInputTensor(0)
    val inputShape = inputTensor.shape()
    val outputTensor = interpreter!!.getOutputTensor(0)
    val outputShape = outputTensor.shape()
    val channels = outputShape[1]
    val anchors = outputShape[2]
    override fun analyze(image: ImageProxy)
    val bitmap = image.toBitmap()
    fun analyzeBitmapAndTrackPoses(bitmap: Bitmap, drawOnOverlay: Boolean = true)
    val startTimeMs = System.currentTimeMillis()
    val input = imageProcessor.process(tensorImage)
    val rawPoseCandidates = extractRawPosesFromModelOutput(modelOutputBuffer!![0])
    val nmsFilteredCandidates = applyNonMaximumSuppression(rawPoseCandidates)
    val finalTrackedSubjects = updateTrackingStateAndFilterGhosts(nmsFilteredCandidates)
    val inferenceTimeMs = System.currentTimeMillis() - startTimeMs
    val backgroundBitmap = if (drawOnOverlay) bitmap else null
    val finalOutputList = ArrayList<PoseResult>()
    val matchedTrackerIds = HashSet<Int>()
    var bestMatchId = -1
    var minDistance = Float.MAX_VALUE
    val candidateCx = candidate.box.centerX()
    val candidateCy = candidate.box.centerY()
    val distance = sqrt((candidateCx - history.centerX).pow(2) + (candidateCy - history.centerY).pow(2))
    var currentId = -1
    var isLocked = false
    var isMoving = false
    val history = activeTrackersMap[bestMatchId]!!
    val moveDistance = sqrt((candidateCx - history.centerX).pow(2) + (candidateCy - history.centerY).pow(2))
    val isMovingNow = moveDistance > MIN_MOVEMENT_DISTANCE_RATIO
    val newHistory = TrackedSubjectHistory(candidateCx, candidateCy)
    val iterator = activeTrackersMap.iterator()
    val entry = iterator.next()
    val results = ArrayList<PoseResult>()
    val numAnchors = outputTensor[0].size
    val numChannels = outputTensor.size
    val score = outputTensor[4][i]
    var cx = outputTensor[0][i]
    var cy = outputTensor[1][i]
    var w = outputTensor[2][i]
    var h = outputTensor[3][i]
    val normRect = RectF(
    val keypoints = ArrayList<Keypoint>(17)
    var kx = outputTensor[5 + k * 3][i]
    var ky = outputTensor[6 + k * 3][i]
    val kConf = outputTensor[7 + k * 3][i]
    val keep = ArrayList<PoseResult>()
    val current = candidates.removeAt(0)
    val iterator = candidates.iterator()
    val next = iterator.next()
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    val intersectionArea = (right - left) * (bottom - top)
    val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - intersectionArea
```

### File: `RoomRecorder.kt`
> **Description**:
**录制状态 (Recording State)**

```kotlin
enum class RecorderState {
class RoomRecorder {
    private var currentState = RecorderState.IDLE
    private var currentRoomId: String = ""
    private var currentRoomName: String = ""
    private val recordedFootprintPoints = ArrayList<PointF>()
    private val recordedEscapePoints = ArrayList<PointF>()
    private var referenceSovereignHull: List<PointF> = emptyList()
    private val lastKnownPositions = HashMap<Int, PointF>()
    fun startRecordingSovereign(id: String, name: String)
    fun startRecordingPortal(id: String, name: String, livingRoomHull: List<PointF>)
    fun stopRecording(): RoomRegion?
    val region = when (currentState) {
    val hull = GeometryUtils.computeConvexHull(recordedFootprintPoints)
    val box = computeBoundingBox(recordedEscapePoints)
    fun processFrame(results: List<PoseResult>)
    val currentFrameIds = HashSet<Int>()
    val footPoint = extractFootPoint(pose) ?: continue
    val disappearedIds = lastKnownPositions.keys - currentFrameIds
    val lastPos = lastKnownPositions[id]!!
    val kpts = pose.keypoints
    val leftAnkle = kpts[15]
    val rightAnkle = kpts[16]
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = Float.MIN_VALUE
    var maxY = Float.MIN_VALUE
    val padding = 0.05f // 5% 屏幕宽度的容差
```

### File: `VideoFeeder.kt`
```kotlin
class VideoFeeder( ...
    private val context: Context,
    private val textureView: TextureView
    var yoloAnalyzer: YoloAnalyzer? = null
    var poseAnalyzer: YoloPoseAnalyzer? = null
    var isPoseMode = false
    private var mediaPlayer: MediaPlayer? = null
    private var isAnalyzing = false
    private val handler = Handler(Looper.getMainLooper())
private val analyzeRunnable = object : Runnable {
    override fun run()
    val bitmap = textureView.bitmap
    fun start(filePath: String)
    val file = File(filePath)
textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int)
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture)
    val surface = Surface(surfaceTexture)
    fun pause()
    fun resume()
    fun isPlaying(): Boolean
    fun seekForward(seconds: Int)
    val target = mp.currentPosition + seconds * 1000
    fun seekBackward(seconds: Int)
    val target = mp.currentPosition - seconds * 1000
    val parent = textureView.parent as? android.view.View ?: return
    val screenW = parent.width
    val screenH = parent.height
    val videoRatio = videoW.toFloat() / videoH
    val screenRatio = screenW.toFloat() / screenH
    val finalW: Int
    val finalH: Int
    val params = textureView.layoutParams
    fun stop()
```

### File: `LivingRoomSetupActivity.kt`
```kotlin
class LivingRoomSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?)
    val rootLayout = FrameLayout(this).apply {
    val btnContainer = LinearLayout(this).apply {
    val containerParams = FrameLayout.LayoutParams(
    fun createBtn(text: String, onClick: () -> Unit): Button
    val bmp = BitmapTransfer.capturedFrame
    val room = RoomRepository.getAllRooms().find { it.id == "living_room" }
    val resultPoints = editorView.getResult()
```

### File: `MainActivity.kt`
```kotlin
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
    private val requestPermissionsLauncher = registerForActivityResult(
    override fun onCreate(savedInstanceState: Bundle?)
    var peopleInLivingRoom = 0
startActivity(Intent(this, SettingsActivity::class.java)) ...
    val roomId = editorView.selectedRoomId
    val room = RoomRepository.getSubRooms().find { it.id == roomId }
    val roomId = editorView.selectedRoomId
    val nextMode = if (editorView.currentMode == LivingRoomEditorView.EditorMode.LIVING_ROOM_HULL) {
    val btnSwitcher = findViewById<Button>(R.id.btnModeSwitcher)
    val btnUndo = findViewById<Button>(R.id.btnUndo)
    val btnClear = findViewById<Button>(R.id.btnClear)
    val btnRename = findViewById<Button>(R.id.btnRenameRoom)
    val btnDelete = findViewById<Button>(R.id.btnDeleteRoom)
    val livingRoom = RoomRepository.getAllRooms().find { it.isSovereignTerritory }
    val visibility = if (room != null) View.VISIBLE else View.GONE
    val input = EditText(this).apply { hint = "房间名称" }
    val name = input.text.toString().trim()
    val input = EditText(this).apply { setText(room.name) }
    val points = editorView.getResult()
    val allRooms = RoomRepository.getAllRooms()
    val livingRoom = allRooms.find { it.isSovereignTerritory }
    override fun onResume()
    override fun onPause()
    override fun onDestroy()
    val permissions = mutableListOf(Manifest.permission.CAMERA)
    val path = "/storage/emulated/0/Android/media/com.example.roomxxx0102/test_video.mp4"
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    val cameraProvider = cameraProviderFuture.get()
    val preview = Preview.Builder().build()
    val imageAnalysis = ImageAnalysis.Builder()
```

### File: `SettingsActivity.kt`
```kotlin
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?)
    override fun onOptionsItemSelected(item: MenuItem): Boolean
    override fun onWindowFocusChanged(hasFocus: Boolean)
```

### File: `DebugBoxDrawer.kt`
> **Description**:
绘制调试信息

@param canvas 画布
@param objects 追踪对象列表
@param drawLeft 绘制区域的左偏移
@param drawTop 绘制区域的上偏移
@param drawWidth 绘制区域的实际宽度
@param drawHeight 绘制区域的实际高度

```kotlin
class DebugBoxDrawer {
    private val boxPaint = Paint().apply {
    private val bottomCenterPaint = Paint().apply {
    fun draw(
    val normLeft = obj.cx - obj.w / 2
    val normTop = obj.cy - obj.h / 2
    val normRight = obj.cx + obj.w / 2
    val normBottom = obj.cy + obj.h / 2
    val screenLeft = drawLeft + normLeft * drawWidth
    val screenTop = drawTop + normTop * drawHeight
    val screenRight = drawLeft + normRight * drawWidth
    val screenBottom = drawTop + normBottom * drawHeight
    val rect = RectF(screenLeft, screenTop, screenRight, screenBottom)
    val centerX = drawLeft + obj.cx * drawWidth
```

### File: `PoseDrawer.kt`
```kotlin
class PoseDrawer {
    private val boxPaint = Paint().apply {
    private val skeletonLinePaint = Paint().apply {
    private val kptPaint = Paint().apply {
    private val landingPointPaint = Paint().apply {
    private val scoreTextPaint = Paint().apply {
    private val skeletonConnections = listOf(
    private val kptConfThreshold = 0.3f
    fun draw(
    val kpts = result.keypoints
    val box = result.box
    val scoreColor = getScoreColor(result.score)
    val mainColor = if (result.isConfirmed) scoreColor else Color.WHITE
    val screenLeft = drawLeft + box.left * drawWidth
    val screenTop = drawTop + box.top * drawHeight
    val screenRight = drawLeft + box.right * drawWidth
    val screenBottom = drawTop + box.bottom * drawHeight
    val screenRect = RectF(screenLeft, screenTop, screenRight, screenBottom)
    val p1 = kpts[idx1]
    val p2 = kpts[idx2]
    val x1 = drawLeft + p1.x * drawWidth
    val y1 = drawTop + p1.y * drawHeight
    val x2 = drawLeft + p2.x * drawWidth
    val y2 = drawTop + p2.y * drawHeight
    val cx = drawLeft + p.x * drawWidth
    val cy = drawTop + p.y * drawHeight
    val landingPoint = result.landingPoint
    val lx = drawLeft + landingPoint.x * drawWidth
    val ly = drawTop + landingPoint.y * drawHeight
    val lockStatus = if (result.isConfirmed) "Lock" else ""
    val infoText = "ID:${result.id} %.2f %s".format(result.score, lockStatus)
```

### File: `RoomListFragment.kt`
> **Description**:
**房间列表 Fragment (Room List)**

核心设置页面。
展示所有已配置的房间，提供添加、删除、录制入口等功能。

```kotlin
class RoomListFragment : Fragment() {
    private var _binding: FragmentRoomListBinding? = null
    private val binding get() = _binding!!
    private val roomAdapter = RoomAdapter()
    override fun onCreateView(
    override fun onViewCreated(view: View, savedInstanceState: Bundle?)
    override fun onResume()
    val rooms = RoomRepository.getAllRooms()
    val input = EditText(context)
    val padding = (16 * resources.displayMetrics.density).toInt()
    val name = input.text.toString().trim()
    override fun onDestroyView()
inner class RoomAdapter : RecyclerView.Adapter<RoomAdapter.RoomViewHolder>() {
    private var roomList = listOf<RoomConfig>()
    fun submitList(list: List<RoomConfig>)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder
    val inflater = LayoutInflater.from(parent.context)
    val binding = ItemRoomConfigBinding.inflate(inflater, parent, false)
    override fun onBindViewHolder(holder: RoomViewHolder, position: Int)
    override fun getItemCount(): Int = roomList.size
inner class RoomViewHolder(private val binding: ItemRoomConfigBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(room: RoomConfig)
    val btn = binding.btnRecord
    val color = if (room.isRecorded) {
val intent = Intent(context, LivingRoomSetupActivity::class.java) ...
    val color = if (room.isRecorded) {
```

### File: `SettingsActivity.kt`
```kotlin
```

### File: `SettingsHomeFragment.kt`
> **Description**:
**设置主页 (Settings Home)**

包含调试开关和功能入口。

```kotlin
class SettingsHomeFragment : Fragment() {
    private var _binding: FragmentSettingsHomeBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(
    override fun onViewCreated(view: View, savedInstanceState: Bundle?)
    override fun onDestroyView()
```

### File: `Color.kt`
```kotlin
    val Purple80 = Color(0xFFD0BCFF)
    val PurpleGrey80 = Color(0xFFCCC2DC)
    val Pink80 = Color(0xFFEFB8C8)
    val Purple40 = Color(0xFF6650a4)
    val PurpleGrey40 = Color(0xFF625b71)
    val Pink40 = Color(0xFF7D5260)
```

### File: `Theme.kt`
```kotlin
    private val DarkColorScheme = darkColorScheme(
    private val LightColorScheme = lightColorScheme(
    fun RoomXXX0102Theme(
    val colorScheme = when {
    val context = LocalContext.current
```

### File: `Type.kt`
```kotlin
    val Typography = Typography(
```

### File: `DetectionOverlayView.kt`
```kotlin
class DetectionOverlayView @JvmOverloads constructor( ...
    private var trackedObjects: List<TrackedDetection> = emptyList()
    private var poseResults: List<PoseResult> = emptyList()
    private var livingRoomBoundary: List<PointF> = emptyList()
    private var subRooms: List<RoomConfig> = emptyList()
    private var debugInfo = "Waiting..."
    private var currentFrame: Bitmap? = null
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }
    private var showDebugBoxes = false
    private var showCenterPoints = true
    private var showPose = false
    private val debugDrawer = DebugBoxDrawer()
    private val poseDrawer = PoseDrawer()
    private val movingPaint = Paint().apply {
    private val staticPaint = Paint().apply {
    private val infoPaint = Paint().apply {
    private val regionFillPaint = Paint().apply {
    private val regionStrokePaint = Paint().apply {
    private val pawnFillPaint = Paint().apply {
    private val pawnStrokePaint = Paint().apply {
    private val textPaint = Paint().apply {
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false
    fun updateData(objects: List<TrackedDetection>, bitmap: Bitmap?, timeMs: Long)
    fun updatePoseData(results: List<PoseResult>, bitmap: Bitmap?, timeMs: Long)
    fun setLivingRoomBoundary(points: List<PointF>)
    fun setSubRooms(rooms: List<RoomConfig>)
    fun setDebugBoxState(show: Boolean)
    fun setCenterPointState(show: Boolean)
    fun setPoseState(show: Boolean)
    override fun onDraw(canvas: Canvas)
    val w = width.toFloat()
    val h = height.toFloat()
    var drawLeft = 0f
    var drawTop = 0f
    var drawWidth = w
    var drawHeight = h
    val bmp = currentFrame
    val bmpW = bmp.width.toFloat()
    val bmpH = bmp.height.toFloat()
    val scale = Math.min(w / bmpW, h / bmpH)
    val path = Path()
    val startX = drawLeft + livingRoomBoundary[0].x * drawWidth
    val startY = drawTop + livingRoomBoundary[0].y * drawHeight
    val px = drawLeft + livingRoomBoundary[i].x * drawWidth
    val py = drawTop + livingRoomBoundary[i].y * drawHeight
    val px = drawLeft + anchor.x * drawWidth
    val py = drawTop + anchor.y * drawHeight
    val screenX = drawLeft + obj.cx * drawWidth
    val screenY = drawTop + obj.cy * drawHeight
    val paint = if (obj.isMoving) movingPaint else staticPaint
    val r = 12f
```

### File: `LivingRoomEditorView.kt`
> **Description**:
**客厅区域编辑器 View (Living Room Editor)**

```kotlin
class LivingRoomEditorView @JvmOverloads constructor( ...
enum class EditorMode {
    var currentMode: EditorMode = EditorMode.LIVING_ROOM_HULL
    private val manualPoints = mutableListOf<PointF>()
    private var subRooms: MutableList<RoomConfig> = mutableListOf()
    var selectedRoomId: String? = null
    private var activeRoomId: String? = null // 正在拖拽的 ID
    private var onAddSubRoom: ((PointF) -> Unit)? = null
    private var onRoomSelected: ((RoomConfig?) -> Unit)? = null // 选中回调
    var backgroundBitmap: Bitmap? = null
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val polygonFillPaint = Paint().apply {
    private val polygonStrokePaint = Paint().apply {
    private val vertexPaint = Paint().apply {
    private val pawnFillPaint = Paint().apply {
    private val pawnSelectedPaint = Paint().apply {
    private val pawnStrokePaint = Paint().apply {
    private val textPaint = Paint().apply {
    private var draggingIndex = -1
    private val touchRadius = 70f // 增大判定范围
    private val edgeClickRadius = 60f
    private val snapThreshold = 0.03f
private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDoubleTap(e: MotionEvent): Boolean
    override fun onSingleTapUp(e: MotionEvent): Boolean
    val clickedRoom = findRoomAt(e.x, e.y)
    fun setSubRooms(rooms: List<RoomConfig>)
    fun setOnSubRoomListener(onAdd: (PointF) -> Unit, onSelected: (RoomConfig?) -> Unit)
    fun clearSelection()
    fun setHistoryPoints(points: List<PointF>)
    fun undo()
    fun clear()
    fun getResult(): List<PointF>
    val nx = (screenX - dstRect.left) / dstRect.width()
    val ny = (screenY - dstRect.top) / dstRect.height()
    val sx = dstRect.left + normX * dstRect.width()
    val sy = dstRect.top + normY * dstRect.height()
    val screenP = toScreen(anchor.x, anchor.y)
    val p = manualPoints[i]
    val screenP = toScreen(p.x, p.y)
    var bestIndex = -1
    var minDistance = Float.MAX_VALUE
    val size = manualPoints.size
    val p1 = toScreen(manualPoints[i].x, manualPoints[i].y)
    val p2 = toScreen(manualPoints[(i + 1) % size].x, manualPoints[(i + 1) % size].y)
    val dist = pointToSegmentDistance(x, y, p1.x, p1.y, p2.x, p2.y)
    val l2 = (x1 - x2).pow(2) + (y1 - y2).pow(2)
    var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
    override fun onTouchEvent(event: MotionEvent): Boolean
    val x = event.x; val y = event.y
    val p = manualPoints[i]
    val screenP = toScreen(p.x, p.y)
    val edgeIndex = getClosestEdgeIndex(x, y)
    val clickedRoom = findRoomAt(x, y)
    val normP = toNorm(x, y)
    val normP = toNorm(x, y)
    var nx = normP.x; var ny = normP.y
    override fun onDraw(canvas: Canvas)
    val w = width.toFloat(); val h = height.toFloat()
    val bmp = backgroundBitmap
    val scale = min(w / bmp.width, h / bmp.height)
    val drawW = bmp.width * scale; val drawH = bmp.height * scale
    val left = (w - drawW) / 2; val top = (h - drawH) / 2
    val path = Path()
    val start = toScreen(manualPoints[0].x, manualPoints[0].y)
    val p = toScreen(manualPoints[i].x, manualPoints[i].y)
    val screenP = toScreen(p.x, p.y)
    val screenP = toScreen(anchor.x, anchor.y)
    val isSelected = room.id == selectedRoomId
    val r = 15f
    val paint = if (isSelected) pawnSelectedPaint else pawnFillPaint
```

### File: `BitmapTransfer.kt`
> **Description**:
**Bitmap 传输工具 (Bitmap Transfer)**

用于在 Activity 之间传递大图，避免 Intent TransactionTooLargeException。
这是一个简单的单例持有者。

```kotlin
object BitmapTransfer {
    var capturedFrame: Bitmap? = null
```

### File: `GeometryUtils.kt`
> **Description**:
**几何算法工具类 (GeometryUtils)**

该类负责处理“主权领土 (Sovereign Territory)”和“传送门陷阱 (Portal Traps)”底层的空间几何计算。

主要功能：
1. **凸包计算 (Convex Hull)**: 用于根据用户在客厅行走的足迹点云，生成客厅的“最大物理边界”。
2. **点在多边形内判定 (Point in Polygon)**: 用于判断目标是否位于客厅内，或是否落入某个房间的“传送门”区域。

```kotlin
object GeometryUtils {
    fun computeConvexHull(points: List<PointF>): List<PointF>
    val sortedPoints = points.sortedWith(compareBy({ it.x }, { it.y }))
    val upperHull = ArrayList<PointF>()
    val p1 = upperHull[upperHull.size - 2]
    val p2 = upperHull[upperHull.size - 1]
    val lowerHull = ArrayList<PointF>()
    val p = sortedPoints[i]
    val p1 = lowerHull[lowerHull.size - 2]
    val p2 = lowerHull[lowerHull.size - 1]
    val hull = ArrayList<PointF>(upperHull)
    fun isPointInPolygon(point: PointF, polygon: List<PointF>): Boolean
    var intersectCount = 0
    val p1 = polygon[i]
    val p2 = polygon[(i + 1) % polygon.size]
    val xIntersect = (p2.x - p1.x) * (point.y - p1.y) / (p2.y - p1.y) + p1.x
```

