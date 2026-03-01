package com.example.roomxxx0102.logic.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.roomxxx0102.data.model.Keypoint
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.data.model.IdSource
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.logic.tracker.RemoteByteTrackEngine
import com.example.roomxxx0102.logic.tracker.SimpleTrackerEngine
import com.example.roomxxx0102.logic.tracker.TrackDetection
import com.example.roomxxx0102.logic.tracker.TrackResult
import com.example.roomxxx0102.logic.tracker.TrackerEngine
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * [AI Role]: 感知层核心分析器 (Perception Layer Core Analyzer)
 * [Responsibility]:
 * 1. 运行 YOLO-Pose 模型进行推理。
 * 2. 将 Tensor 数据转换为业务可理解的 PoseResult。
 * 3. 执行 ID 追踪 (Tracking) 和 误检过滤 (Ghost Filtering)。
 * [Key Logic]:
 * - 采用 "宽进严出" 策略：低门槛接纳候选框，高门槛或锁定状态才允许输出。
 * - 实现了 "迟滞阈值"：一旦目标被锁定 (Confirmed)，即使置信度下降也不会立即消失。
 */
class YoloPoseAnalyzer(
    private val context: Context,
    /**
     * 回调函数，用于将处理后的结果传递给 UI 层或数据采集层。
     * @param results 经过追踪和过滤后的姿态列表。
     * @param inputBitmap 用于绘制覆盖层的原图 (可选)。
     * @param inferenceTimeMs 推理耗时 (毫秒)。
     */
    private val onPoseAnalysisResultsUpdated: (List<PoseResult>, Bitmap?, Long) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val MODEL_FILE_NAME = "yolo11s_pose.tflite"
        private const val TAG = "YoloPoseAnalyzer"

        // --- 阈值策略 (Threshold Strategy) ---

        /**
         * [Input Barrier]: 基础候选门槛 (0.15)。
         * 低于此分数的检测框将被视为纯噪音直接丢弃，不进入追踪逻辑。
         */
        private const val MIN_CANDIDATE_SCORE_THRESHOLD = 0.15f

        /**
         * [NMS]: 非极大值抑制阈值 (0.5)。用于去除重叠框。
         */
        private const val NMS_IOU_THRESHOLD = 0.5f

    }

    private var interpreter: Interpreter? = null
    
    // [Model Input Size]: 通常为 640x640
    private var modelInputWidth = 640
    private var modelInputHeight = 640
    
    private var tensorImage: TensorImage? = null
    private var modelOutputBuffer: Array<Array<FloatArray>>? = null

    // [Tracking Engine]: 追踪引擎 (可切换本地/远程)
    private var trackerEngine: TrackerEngine = SimpleTrackerEngine()
    private var isUsingRemoteTracker = false
    private var lastShieldZones: List<RectF> = emptyList()
    private var heartbeatFrameId = 0
    private var lastUnlockMessage: String? = null

    // [Preprocessing]: 图像预处理管线
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(640, 640, ResizeOp.ResizeMethod.BILINEAR)) 
        .add(NormalizeOp(0f, 255f))
        .add(CastOp(DataType.FLOAT32))
        .build()

    init {
        initializeInterpreter()
    }

    /**
     * 重置所有追踪状态。
     * 通常在切换视频源或重置场景时调用。
     */
    fun resetTrackingState() {
        trackerEngine.reset()
        Log.d(TAG, "🚫 追踪器状态已重置")
    }

    private fun initializeInterpreter() {
        try {
            val assets = context.assets.list("")
            if (assets == null || !assets.contains(MODEL_FILE_NAME)) return

            val afd = context.assets.openFd(MODEL_FILE_NAME)
            val fis = FileInputStream(afd.fileDescriptor)
            val buffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            
            val options = Interpreter.Options()
            try {
                options.addDelegate(GpuDelegate())
            } catch (e: Exception) {
                options.setUseXNNPACK(true)
                options.setNumThreads(4)
            }
            
            interpreter = Interpreter(buffer, options)
            val inputTensor = interpreter!!.getInputTensor(0)
            val inputShape = inputTensor.shape()
            if (inputShape.size == 4) {
                if (inputShape[1] == 3) { modelInputHeight = inputShape[2]; modelInputWidth = inputShape[3] } 
                else { modelInputHeight = inputShape[1]; modelInputWidth = inputShape[2] }
            }
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val channels = outputShape[1]
            val anchors = outputShape[2]
            
            modelOutputBuffer = Array(1) { Array(channels) { FloatArray(anchors) } }
            tensorImage = TensorImage(DataType.FLOAT32)
        } catch (e: Exception) {
            interpreter = null
        }
    }

    // CameraX 接口实现
    override fun analyze(image: ImageProxy) {
        if (interpreter == null) { image.close(); return }
        try {
            val bitmap = image.toBitmap()
            analyzeBitmapAndTrackPoses(bitmap, null, drawOnOverlay = false)
        } catch (e: Exception) {
            Log.e(TAG, "Analysis Failed", e)
        } finally {
            image.close()
        }
    }

    /**
     * 执行核心分析流程：预处理 -> 推理 -> 后处理 -> 追踪 -> 过滤。
     * 
     * @param bitmap 输入图像。
     * @param roi 可选的感兴趣区域 (0.0~1.0)。如果不为 null，将裁剪该区域进行推理。
     * @param drawOnOverlay 是否将原图传递给回调用于绘制背景 (调试用)。
     */
    fun analyzeBitmapAndTrackPoses(bitmap: Bitmap, roi: RectF? = null, drawOnOverlay: Boolean = true) {
        val frameId = ++heartbeatFrameId
        if (interpreter == null) {
            if (drawOnOverlay) onPoseAnalysisResultsUpdated(emptyList(), bitmap, 0L)
            return
        }
        
        val startTimeMs = System.currentTimeMillis()
        try {
            // 🔥 Step 1: 准备输入图像 (裁剪或全图)
            val inputBitmap: Bitmap
            val roiPx: RectF? 

            if (roi != null) {
                // 计算像素级 ROI
                val w = bitmap.width
                val h = bitmap.height
                val left = (roi.left * w).toInt().coerceIn(0, w - 1)
                val top = (roi.top * h).toInt().coerceIn(0, h - 1)
                val width = (roi.width() * w).toInt().coerceIn(1, w - left)
                val height = (roi.height() * h).toInt().coerceIn(1, h - top)
                
                inputBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
                roiPx = RectF(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
            } else {
                inputBitmap = bitmap
                roiPx = null
            }

            // 2. 加载与预处理
            tensorImage!!.load(inputBitmap)
            val input = imageProcessor.process(tensorImage)
            
            // 3. 模型推理
            interpreter!!.run(input.buffer, modelOutputBuffer)

            // 4. 提取原始数据 (Raw Parsing)
            val rawPoseCandidates = extractRawPosesFromModelOutput(modelOutputBuffer!![0], roiPx, bitmap.width, bitmap.height)
            if (roiPx != null) {
                val tb = rawPoseCandidates.firstOrNull()?.box
                RoiLogAggregator.updateRoiCoord(roiPx, tb)
            }
            
            // 5. 非极大值抑制 (NMS)
            val nmsFilteredCandidates = applyNonMaximumSuppression(rawPoseCandidates)
//
            // 6. 追踪与业务逻辑过滤 (Tracking & Ghost Filtering)
            updateTrackerEngineIfNeeded()
            val detections = nmsFilteredCandidates.map {
                toPixelDetection(it, bitmap.width, bitmap.height)
            }
            val tracked = trackerEngine.track(detections, bitmap.width, bitmap.height)
            lastUnlockMessage = trackerEngine.consumeUnlockMessage()
            lastShieldZones = trackerEngine.getShieldZones().map { toNormalizedRect(it, bitmap.width, bitmap.height) }
            val finalTrackedSubjects = tracked.map {
                toNormalizedResult(it, bitmap.width, bitmap.height)
            }
            RoiLogAggregator.updateHeartbeat(
                frameId,
                rawPoseCandidates.size,
                nmsFilteredCandidates.size,
                finalTrackedSubjects.size,
                roi != null
            )
            
            val inferenceTimeMs = System.currentTimeMillis() - startTimeMs
            val backgroundBitmap = if (drawOnOverlay) bitmap else null
            
            onPoseAnalysisResultsUpdated(finalTrackedSubjects, backgroundBitmap, inferenceTimeMs)

        } catch (e: Exception) {
            Log.e(TAG, "Pose Detection Error", e)
            if (drawOnOverlay) onPoseAnalysisResultsUpdated(emptyList(), bitmap, 0L)
        }
    }

    fun getShieldZones(): List<RectF> = lastShieldZones

    fun consumeUnlockMessage(): String? {
        val msg = lastUnlockMessage
        lastUnlockMessage = null
        return msg
    }

    private fun updateTrackerEngineIfNeeded() {
        val useRemote = AppSettings.isNewTrackerPredictionEnabled
        if (useRemote == isUsingRemoteTracker) return
        trackerEngine = if (useRemote) {
            RemoteByteTrackEngine(SimpleTrackerEngine())
        } else {
            SimpleTrackerEngine()
        }
        trackerEngine.reset()
        isUsingRemoteTracker = useRemote
    }

    private fun toPixelDetection(
        result: PoseResult,
        fullWidth: Int,
        fullHeight: Int
    ): TrackDetection {
        val box = RectF(
            result.box.left * fullWidth,
            result.box.top * fullHeight,
            result.box.right * fullWidth,
            result.box.bottom * fullHeight
        )
        val kpts = result.keypoints.map {
            Keypoint(it.x * fullWidth, it.y * fullHeight, it.conf)
        }
        return TrackDetection(box = box, keypoints = kpts, score = result.score)
    }

    private fun toNormalizedRect(
        rect: RectF,
        fullWidth: Int,
        fullHeight: Int
    ): RectF {
        return RectF(
            rect.left / fullWidth,
            rect.top / fullHeight,
            rect.right / fullWidth,
            rect.bottom / fullHeight
        )
    }

    private fun toNormalizedResult(
        track: TrackResult,
        fullWidth: Int,
        fullHeight: Int
    ): PoseResult {
        val box = RectF(
            track.box.left / fullWidth,
            track.box.top / fullHeight,
            track.box.right / fullWidth,
            track.box.bottom / fullHeight
        )
        val kpts = track.keypoints.map {
            Keypoint(it.x / fullWidth, it.y / fullHeight, it.conf)
        }
        return PoseResult(
            id = track.trackId,
            box = box,
            keypoints = kpts,
            score = track.score,
            isMoving = track.isMoving,
            isConfirmed = track.isConfirmed,
            idSource = if (track.isRemote) IdSource.REMOTE else IdSource.LOCAL,
            isShielded = track.isShielded
        )
    }

    /**
     * [Raw Parsing]: 从模型输出张量中提取结构化数据。
     * 🔥 新增：支持 ROI 坐标逆映射
     */
    private fun extractRawPosesFromModelOutput(
        outputTensor: Array<FloatArray>, 
        roiPx: RectF?, 
        fullWidth: Int, 
        fullHeight: Int
    ): MutableList<PoseResult> {
        val results = ArrayList<PoseResult>()
        if (outputTensor.isEmpty() || outputTensor[0].isEmpty()) return results
        
        val numAnchors = outputTensor[0].size 
        val numChannels = outputTensor.size
        
        if (numChannels < 56) return results

        for (i in 0 until numAnchors) {
            val score = outputTensor[4][i]
            
            if (score > MIN_CANDIDATE_SCORE_THRESHOLD) {
                var cx = outputTensor[0][i]
                var cy = outputTensor[1][i]
                var w = outputTensor[2][i]
                var h = outputTensor[3][i]

                // 模型输出按归一化坐标处理（允许轻微越界，例如 >1）

                if (roiPx != null) {
                    val roiW = roiPx.width()
                    val roiH = roiPx.height()
                    val roiX = roiPx.left
                    val roiY = roiPx.top
                    
                    cx = (cx * roiW + roiX) / fullWidth
                    cy = (cy * roiH + roiY) / fullHeight
                    w = (w * roiW) / fullWidth
                    h = (h * roiH) / fullHeight
                }

                val normRect = RectF(
                    cx - w / 2,
                    cy - h / 2,
                    cx + w / 2,
                    cy + h / 2
                )

                val keypoints = ArrayList<Keypoint>(17)
                for (k in 0 until 17) {
                    val rawKx = outputTensor[5 + k * 3][i]
                    val rawKy = outputTensor[6 + k * 3][i]
                    var kx = rawKx
                    var ky = rawKy
                    val kConf = outputTensor[7 + k * 3][i]
                    
                    // 关键点按归一化坐标处理（允许轻微越界，例如 >1）
                    
                    if (roiPx != null) {
                        val roiW = roiPx.width()
                        val roiH = roiPx.height()
                        val roiX = roiPx.left
                        val roiY = roiPx.top
                        
                        kx = (kx * roiW + roiX) / fullWidth
                        ky = (ky * roiH + roiY) / fullHeight
                    }

                    if ((k == 15 || k == 16) &&
                        (rawKx <= 0.01f && rawKy <= 0.01f || kx <= 0.01f && ky <= 0.01f)
                    ) {
                        Log.d(
                            TAG,
                            "AnkleKP near zero: k=$k raw=($rawKx,$rawKy) norm=($kx,$ky) " +
                                "conf=$kConf roi=$roiPx box=$normRect"
                        )
                    }
                    
                    keypoints.add(Keypoint(kx, ky, kConf))
                }

                results.add(PoseResult(0, normRect, keypoints, score))
            }
        }
        return results
    }

    private fun applyNonMaximumSuppression(candidates: MutableList<PoseResult>): List<PoseResult> {
        val keep = ArrayList<PoseResult>()
        candidates.sortByDescending { it.score }
        
        while (candidates.isNotEmpty()) {
            val current = candidates.removeAt(0)
            keep.add(current)
            val iterator = candidates.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIntersectionOverUnion(current.box, next.box) > NMS_IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }
        return keep
    }

    private fun calculateIntersectionOverUnion(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        
        if (right < left || bottom < top) return 0f
        
        val intersectionArea = (right - left) * (bottom - top)
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - intersectionArea
        
        return intersectionArea / unionArea
    }
}
