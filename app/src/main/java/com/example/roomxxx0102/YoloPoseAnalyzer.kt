package com.example.roomxxx0102

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.common.ops.CastOp
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

private data class PoseHistory(
    var cx: Float, 
    var cy: Float, 
    var staticFrameCount: Int = 0, 
    var missingFrameCount: Int = 0,
    var maxScore: Float = 0f,
    var hasMoved: Boolean = false
)

class YoloPoseAnalyzer(
    private val context: Context,
    private val updateCallback: (List<PoseResult>, Bitmap?, Long) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val MODEL_FILE = "yolo11s_pose.tflite"
        private const val TAG = "YoloPose"
        
        // 🔥 1. 回调准入门槛，拒绝垃圾信号
        private const val BASE_CONF_THRESHOLD = 0.4f 
        private const val DISPLAY_THRESHOLD = 0.45f
        
        // 🔥 2. 提高锁定门槛：必须是非常确信的人 (>0.6) 且移动过，才给“免死金牌”
        private const val LOCK_SCORE_THRESHOLD = 0.6f 
        
        private const val NMS_THRESHOLD = 0.5f
        
        // 🔥 3. 提高移动判定阈值，防止检测框抖动被误判为移动
        // 0.05f 意味着要在横向上移动 5% 的屏幕宽度才算动
        private const val MOVEMENT_THRESHOLD = 0.05f 
        
        private const val MAX_MISSING_FRAMES = 60
    }

    private var interpreter: Interpreter? = null
    private var inputW = 640
    private var inputH = 640
    private var tensorImage: TensorImage? = null
    private var outputData: Array<Array<FloatArray>>? = null

    private val trackerMap = ConcurrentHashMap<Int, PoseHistory>()
    private var nextObjectId = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(640, 640, ResizeOp.ResizeMethod.BILINEAR)) 
        .add(NormalizeOp(0f, 255f))
        .add(CastOp(DataType.FLOAT32))
        .build()

    init {
        setupInterpreter()
    }

    fun reset() {
        trackerMap.clear()
        nextObjectId = 0
        Log.d(TAG, "🚫 Pose追踪器已重置")
    }

    private fun setupInterpreter() {
        try {
            val assets = context.assets.list("")
            if (assets == null || !assets.contains(MODEL_FILE)) return

            val afd = context.assets.openFd(MODEL_FILE)
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
                if (inputShape[1] == 3) { inputH = inputShape[2]; inputW = inputShape[3] } 
                else { inputH = inputShape[1]; inputW = inputShape[2] }
            }
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val channels = outputShape[1]
            val anchors = outputShape[2]
            outputData = Array(1) { Array(channels) { FloatArray(anchors) } }
            tensorImage = TensorImage(DataType.FLOAT32)
        } catch (e: Exception) {
            interpreter = null
        }
    }

    override fun analyze(image: ImageProxy) {
        if (interpreter == null) { image.close(); return }
        try {
            val bitmap = image.toBitmap()
            detectOnBitmap(bitmap, drawOnOverlay = false)
        } catch (e: Exception) {
            Log.e(TAG, "Analyze Error", e)
        } finally {
            image.close()
        }
    }

    fun detectOnBitmap(bitmap: Bitmap, drawOnOverlay: Boolean = true) {
        if (interpreter == null) {
            if (drawOnOverlay) updateCallback(emptyList(), bitmap, 0L)
            return
        }
        
        val t1 = System.currentTimeMillis()
        try {
            tensorImage!!.load(bitmap)
            val input = imageProcessor.process(tensorImage)
            interpreter!!.run(input.buffer, outputData)

            val rawResults = parseOutput(outputData!![0])
            val nmsResults = nms(rawResults)
            
            val finalResults = processTrackingAndFiltering(nmsResults)
            
            val bgBitmap = if (drawOnOverlay) bitmap else null
            updateCallback(finalResults, bgBitmap, System.currentTimeMillis() - t1)

        } catch (e: Exception) {
            Log.e(TAG, "Pose Detect Error", e)
            if (drawOnOverlay) updateCallback(emptyList(), bitmap, 0L)
        }
    }

    private fun processTrackingAndFiltering(candidates: List<PoseResult>): List<PoseResult> {
        val finalResults = ArrayList<PoseResult>()
        val usedTrackerIds = HashSet<Int>()

        for (cand in candidates) {
            var bestMatchId = -1
            var minDist = Float.MAX_VALUE
            val candCx = cand.box.centerX()
            val candCy = cand.box.centerY()

            for ((id, history) in trackerMap) {
                if (id in usedTrackerIds) continue
                val dist = sqrt((candCx - history.cx).pow(2) + (candCy - history.cy).pow(2))
                if (dist < 0.15f && dist < minDist) {
                    minDist = dist
                    bestMatchId = id
                }
            }

            var currentId = -1
            var isLocked = false
            var isMoving = false

            if (bestMatchId != -1) {
                currentId = bestMatchId
                val history = trackerMap[bestMatchId]!!
                
                // 移动判定：如果之前没动过，才检查这次动没动
                // 一旦被判定为“动过”，hasMoved 就永久为 true
                val moveDist = sqrt((candCx - history.cx).pow(2) + (candCy - history.cy).pow(2))
                val movingNow = moveDist > MOVEMENT_THRESHOLD
                
                if (movingNow) history.hasMoved = true
                
                // 当前状态是否在动
                isMoving = movingNow

                history.maxScore = max(history.maxScore, cand.score)
                history.cx = candCx; history.cy = candCy; history.missingFrameCount = 0
                usedTrackerIds.add(bestMatchId)

                // 锁定逻辑：曾高分 (>0.6) 且 曾移动过
                isLocked = (history.maxScore > LOCK_SCORE_THRESHOLD) && history.hasMoved

            } else {
                currentId = nextObjectId++
                val newHistory = PoseHistory(candCx, candCy)
                newHistory.maxScore = cand.score
                trackerMap[currentId] = newHistory
            }

            // 保留逻辑
            if (cand.score > DISPLAY_THRESHOLD || isLocked) {
                finalResults.add(cand.copy(
                    id = currentId, 
                    isMoving = isMoving, 
                    isConfirmed = isLocked
                ))
            }
        }

        val iterator = trackerMap.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!usedTrackerIds.contains(entry.key)) {
                entry.value.missingFrameCount++
                if (entry.value.missingFrameCount > MAX_MISSING_FRAMES) iterator.remove()
            }
        }

        return finalResults
    }

    private fun parseOutput(data: Array<FloatArray>): MutableList<PoseResult> {
        val results = ArrayList<PoseResult>()
        if (data.isEmpty() || data[0].isEmpty()) return results
        
        val numAnchors = data[0].size 
        val numChannels = data.size
        if (numChannels < 56) return results

        for (i in 0 until numAnchors) {
            val score = data[4][i]
            if (score > BASE_CONF_THRESHOLD) {
                var cx = data[0][i]; var cy = data[1][i]; var w = data[2][i]; var h = data[3][i]
                if (cx > 1.0f) { cx /= inputW; cy /= inputH; w /= inputW; h /= inputH }
                val normRect = RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2)

                val keypoints = ArrayList<Keypoint>(17)
                for (k in 0 until 17) {
                    var kx = data[5 + k * 3][i]; var ky = data[6 + k * 3][i]
                    val kConf = data[7 + k * 3][i]
                    if (kx > 1.0f) { kx /= inputW; ky /= inputH }
                    keypoints.add(Keypoint(kx, ky, kConf))
                }
                results.add(PoseResult(0, normRect, keypoints, score))
            }
        }
        return results
    }

    private fun nms(list: MutableList<PoseResult>): List<PoseResult> {
        val keep = ArrayList<PoseResult>()
        list.sortByDescending { it.score }
        while (list.isNotEmpty()) {
            val current = list.removeAt(0)
            keep.add(current)
            val iter = list.iterator()
            while (iter.hasNext()) {
                val next = iter.next()
                if (iou(current.box, next.box) > NMS_THRESHOLD) iter.remove()
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left); val top = max(a.top, b.top)
        val right = min(a.right, b.right); val bottom = min(a.bottom, b.bottom)
        if (right < left || bottom < top) return 0f
        val intersect = (right - left) * (bottom - top)
        val union = (a.width() * a.height()) + (b.width() * b.height()) - intersect
        return intersect / union
    }
}