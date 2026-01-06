package com.example.roomxxx0102.logic.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.roomxxx0102.data.model.Keypoint
import com.example.roomxxx0102.data.model.PoseResult
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * [AI Role]: 内部状态记录类 (Internal State Record)
 * [Responsibility]: 记录单个被追踪主体 (Subject) 的历史轨迹和状态信息。
 * [Key Logic]: 用于支持 "防误触与锁定 (Anti-Ghost & Locking)" 机制，
 * 只要 [hasEverMoved] 为 true 且 [maxHistoricalScore] 达标，该主体即被视为 "真确目标 (Confirmed)"。
 */
private data class TrackedSubjectHistory(
    var centerX: Float,
    var centerY: Float,
    var consecutiveStaticFrames: Int = 0, // 连续静止帧数
    var consecutiveMissingFrames: Int = 0, // 连续丢失帧数
    var maxHistoricalScore: Float = 0f,   // 历史最高置信度
    var hasEverMoved: Boolean = false     // 是否曾经发生过显著移动
)

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
         * [Input Barrier]: 基础候选门槛 (0.4)。
         * 低于此分数的检测框将被视为纯噪音直接丢弃，不进入追踪逻辑。
         */
        private const val MIN_CANDIDATE_SCORE_THRESHOLD = 0.3f

        /**
         * [Output Barrier]: 显示门槛 (0.45)。
         * 对于未锁定的新目标，必须超过此分数才会在 UI 上显示。
         */
        private const val MIN_DISPLAY_SCORE_THRESHOLD = 0.5f

        /**
         * [Locking Condition]: 锁定所需最高分 (0.6)。
         * 只有历史最高分超过此值，且发生过移动，目标才会被标记为 [isConfirmed]。
         */
        private const val MIN_SCORE_FOR_LOCKING = 0.6f

        /**
         * [NMS]: 非极大值抑制阈值 (0.5)。用于去除重叠框。
         */
        private const val NMS_IOU_THRESHOLD = 0.5f

        /**
         * [Movement]: 显著移动判定阈值 (0.05 = 屏幕宽度的 5%)。
         * 防止因检测框抖动而误判为移动。
         */
        private const val MIN_MOVEMENT_DISTANCE_RATIO = 0.01f

        /**
         * [Memory]: 最大丢失容忍帧数 (60帧 ≈ 2-6秒)。
         * 即使目标暂时消失或被遮挡，ID 也会在内存中保留这么久。
         */
        private const val MAX_MISSING_FRAMES_TOLERANCE = 60
    }

    private var interpreter: Interpreter? = null
    
    // [Model Input Size]: 通常为 640x640
    private var modelInputWidth = 640
    private var modelInputHeight = 640
    
    private var tensorImage: TensorImage? = null
    private var modelOutputBuffer: Array<Array<FloatArray>>? = null

    // [Tracking State]: 当前活跃的追踪器映射表 (ID -> History)
    private val activeTrackersMap = ConcurrentHashMap<Int, TrackedSubjectHistory>()
    private var nextSubjectId = 0

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
        activeTrackersMap.clear()
        nextSubjectId = 0
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
            // 🔥 这里需要将 roiPx 传进去，用于坐标反变换
            val rawPoseCandidates = extractRawPosesFromModelOutput(modelOutputBuffer!![0], roiPx, bitmap.width, bitmap.height)
            
            // 5. 非极大值抑制 (NMS)
            val nmsFilteredCandidates = applyNonMaximumSuppression(rawPoseCandidates)
            
            // 6. 追踪与业务逻辑过滤 (Tracking & Ghost Filtering)
            val finalTrackedSubjects = updateTrackingStateAndFilterGhosts(nmsFilteredCandidates)
            
            val inferenceTimeMs = System.currentTimeMillis() - startTimeMs
            val backgroundBitmap = if (drawOnOverlay) bitmap else null
            
            onPoseAnalysisResultsUpdated(finalTrackedSubjects, backgroundBitmap, inferenceTimeMs)

        } catch (e: Exception) {
            Log.e(TAG, "Pose Detection Error", e)
            if (drawOnOverlay) onPoseAnalysisResultsUpdated(emptyList(), bitmap, 0L)
        }
    }

    /**
     * [Core Logic]: 核心追踪与过滤算法。
     * 负责将当前的检测框与历史追踪器匹配，更新状态，并根据 "锁定逻辑" 决定是否输出。
     * 
     * @param candidates 当前帧经过 NMS 后的候选框列表。
     * @return 经过筛选后的最终输出列表。
     */
    private fun updateTrackingStateAndFilterGhosts(candidates: List<PoseResult>): List<PoseResult> {
        val finalOutputList = ArrayList<PoseResult>()
        val matchedTrackerIds = HashSet<Int>()

        for (candidate in candidates) {
            // --- 贪婪匹配 (Greedy Matching) ---
            var bestMatchId = -1
            var minDistance = Float.MAX_VALUE
            val candidateCx = candidate.box.centerX()
            val candidateCy = candidate.box.centerY()

            // 寻找最近的历史目标
            for ((id, history) in activeTrackersMap) {
                if (id in matchedTrackerIds) continue
                // 计算欧氏距离 (Euclidean Distance)
                val distance = sqrt((candidateCx - history.centerX).pow(2) + (candidateCy - history.centerY).pow(2))
                
                // 距离阈值判定 (0.15 归一化距离)
                if (distance < 0.15f && distance < minDistance) {
                    minDistance = distance
                    bestMatchId = id
                }
            }

            var currentId = -1
            var isLocked = false
            var isMoving = false

            if (bestMatchId != -1) {
                // --- 匹配成功：更新老兵 (Veteran) ---
                currentId = bestMatchId
                val history = activeTrackersMap[bestMatchId]!!
                
                // 移动判定逻辑：距离超过阈值才算移动
                val moveDistance = sqrt((candidateCx - history.centerX).pow(2) + (candidateCy - history.centerY).pow(2))
                val isMovingNow = moveDistance > MIN_MOVEMENT_DISTANCE_RATIO
                
                if (isMovingNow) history.hasEverMoved = true
                isMoving = isMovingNow

                // 更新历史最高分
                history.maxHistoricalScore = max(history.maxHistoricalScore, candidate.score)
                
                // 更新位置
                history.centerX = candidateCx
                history.centerY = candidateCy
                history.consecutiveMissingFrames = 0
                
                matchedTrackerIds.add(bestMatchId)

                // 判定锁定状态: 历史分高 + 动过 = 真人
                isLocked = (history.maxHistoricalScore > MIN_SCORE_FOR_LOCKING) && history.hasEverMoved

            } else {
                // --- 匹配失败：注册新兵 (Rookie) ---
                currentId = nextSubjectId++
                val newHistory = TrackedSubjectHistory(candidateCx, candidateCy)
                newHistory.maxHistoricalScore = candidate.score
                activeTrackersMap[currentId] = newHistory
            }

            // --- 最终保留决策 (Final Decision) ---
            // 规则 A: 未锁定目标，必须分数够高 (Display Threshold)。
            // 规则 B: 已锁定目标 (Confirmed)，无视当前分数，强制保留 (Hysteresis)。
            if (candidate.score > MIN_DISPLAY_SCORE_THRESHOLD || isLocked) {
                finalOutputList.add(candidate.copy(
                    id = currentId, 
                    isMoving = isMoving, 
                    isConfirmed = isLocked
                ))
            }
        }

        // --- 清理垃圾 (Garbage Collection) ---
        // 移除长时间丢失的追踪器
        val iterator = activeTrackersMap.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!matchedTrackerIds.contains(entry.key)) {
                entry.value.consecutiveMissingFrames++
                if (entry.value.consecutiveMissingFrames > MAX_MISSING_FRAMES_TOLERANCE) {
                    iterator.remove()
                }
            }
        }

        return finalOutputList
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
        
        // 校验通道数 (Box(4) + Score(1) + Kpt(17*3) = 56)
        if (numChannels < 56) return results

        for (i in 0 until numAnchors) {
            val score = outputTensor[4][i]
            
            // 第一道筛选：低分直接丢弃
            if (score > MIN_CANDIDATE_SCORE_THRESHOLD) {
                var cx = outputTensor[0][i]
                var cy = outputTensor[1][i]
                var w = outputTensor[2][i]
                var h = outputTensor[3][i]

                // 自适应归一化：如果值 > 1.0，视为像素坐标，需除以输入尺寸
                // 注意：这里的输入尺寸是送进模型的图（即 640x640）
                // 如果是 ROI 模式，这里得到的是相对 ROI 的 0..1 坐标（或 0..640 坐标）
                if (cx > 1.0f || cy > 1.0f || w > 1.0f || h > 1.0f) {
                    cx /= modelInputWidth
                    cy /= modelInputHeight
                    w /= modelInputWidth
                    h /= modelInputHeight
                }

                // 坐标变换：如果使用了 ROI，需要把 0..1 的相对坐标映射回全图的 0..1
                if (roiPx != null) {
                    val roiW = roiPx.width()
                    val roiH = roiPx.height()
                    val roiX = roiPx.left
                    val roiY = roiPx.top
                    
                    // 局部 0..1 -> 局部像素 -> 全局像素 -> 全局 0..1
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
                    var kx = outputTensor[5 + k * 3][i]
                    var ky = outputTensor[6 + k * 3][i]
                    val kConf = outputTensor[7 + k * 3][i]
                    
                    // 同样处理关键点坐标
                    if (kx > 1.0f || ky > 1.0f) {
                        kx /= modelInputWidth
                        ky /= modelInputHeight
                    }
                    
                    if (roiPx != null) {
                        val roiW = roiPx.width()
                        val roiH = roiPx.height()
                        val roiX = roiPx.left
                        val roiY = roiPx.top
                        
                        kx = (kx * roiW + roiX) / fullWidth
                        ky = (ky * roiH + roiY) / fullHeight
                    }
                    
                    keypoints.add(Keypoint(kx, ky, kConf))
                }

                results.add(PoseResult(0, normRect, keypoints, score))
            }
        }
        return results
    }

    /**
     * [NMS]: 应用非极大值抑制，去除重叠的检测框。
     */
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

    /**
     * 计算两个矩形的交并比 (IoU)。
     */
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
