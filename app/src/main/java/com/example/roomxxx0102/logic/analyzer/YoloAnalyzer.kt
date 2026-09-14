package com.example.roomxxx0102.logic.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.roomxxx0102.ui.views.DetectionOverlayView
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// --- 公共数据类 (修改：增加了 w, h) ---
data class TrackedDetection(
    val id: Int,
    val cx: Float,      // 归一化中心坐标 X (0..1)
    val cy: Float,      // 归一化中心坐标 Y (0..1)
    val w: Float,       // 归一化宽度 (0..1)
    val h: Float,       // 归一化高度 (0..1)
    val score: Float,
    val isMoving: Boolean
)

// --- 内部辅助类 ---
data class LetterboxResult(val bitmap: Bitmap, val scale: Float, val dx: Float, val dy: Float)
private data class ObjectHistory(var cx: Float, var cy: Float, var staticFrameCount: Int = 0, var missingFrameCount: Int = 0)
private data class RawDetection(val rect: RectF, val score: Float)

class YoloAnalyzer(
    private val context: Context,
    private val overlayView: DetectionOverlayView
) : ImageAnalysis.Analyzer {

    companion object {
        private const val MODEL_FILE_NAME = "yolo26s_float16.tflite"
        private const val MODEL_ARCHITECTURE = "YOLO26s"
        private const val TAG = "YoloAnalyzer"
    }

    private val detectThreshold = 0.30f
    private val nmsThreshold = 0.45f
    private val staticConfirmThreshold = 0.60f
    private val movementThreshold = 0.02f

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
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
        .add(org.tensorflow.lite.support.common.ops.NormalizeOp(0f, 255f))
        .add(org.tensorflow.lite.support.common.ops.CastOp(DataType.FLOAT32))
        .build()

    init {
        setupInterpreter()
    }

    fun reset() {
        trackerMap.clear()
        nextObjectId = 0
        Log.d(TAG, "🚫 追踪器已重置")
    }

    private fun setupInterpreter() {
        try {
            val afd = context.assets.openFd(MODEL_FILE_NAME)
            val fis = FileInputStream(afd.fileDescriptor)
            val modelBuffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            interpreter = createInterpreterWithFallback(modelBuffer)
            val activeInterpreter = interpreter!!
            require(activeInterpreter.getInputTensorCount() == 1) {
                "$MODEL_ARCHITECTURE expects exactly 1 input tensor, got ${activeInterpreter.getInputTensorCount()}"
            }
            require(activeInterpreter.getOutputTensorCount() == 1) {
                "$MODEL_ARCHITECTURE expects exactly 1 output tensor, got ${activeInterpreter.getOutputTensorCount()}"
            }

            val inputTensor = activeInterpreter.getInputTensor(0)
            val inputShape = inputTensor.shape()
            Log.i(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE input[0]=" +
                    "shape=${inputShape.contentToString()} dtype=${inputTensor.dataType()}"
            )
            require(inputTensor.dataType() == DataType.FLOAT32) {
                "$MODEL_ARCHITECTURE input dtype must be FLOAT32, got ${inputTensor.dataType()}"
            }
            require(inputShape.contentEquals(intArrayOf(1, 640, 640, 3))) {
                "$MODEL_ARCHITECTURE unexpected input shape ${inputShape.contentToString()}"
            }
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]

            tensorImage = TensorImage(DataType.FLOAT32)
            val outputTensor = activeInterpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            Log.i(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE output[0]=" +
                    "shape=${outputShape.contentToString()} dtype=${outputTensor.dataType()}"
            )
            require(outputTensor.dataType() == DataType.FLOAT32) {
                "$MODEL_ARCHITECTURE output dtype must be FLOAT32, got ${outputTensor.dataType()}"
            }
            require(outputShape.contentEquals(intArrayOf(1, 84, 8400))) {
                "$MODEL_ARCHITECTURE unexpected output shape ${outputShape.contentToString()}"
            }
            val dim1 = outputShape[1]
            val dim2 = outputShape[2]
            isChannelsFirst = true
            numClasses = dim1 - 4
            numBoxes = dim2
            outputData = Array(1) { Array(dim1) { FloatArray(dim2) } }
            Log.i(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE ready=true " +
                    "classes=$numClasses boxes=$numBoxes externalNms=true"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ 模型加载失败", e)
        }
    }

    private fun createInterpreterWithFallback(modelBuffer: java.nio.ByteBuffer): Interpreter {
        var candidateDelegate: GpuDelegate? = null
        try {
            candidateDelegate = GpuDelegate()
            val gpuOptions = Interpreter.Options().apply { addDelegate(candidateDelegate) }
            val gpuInterpreter = Interpreter(modelBuffer, gpuOptions)
            gpuDelegate = candidateDelegate
            Log.i(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE gpuDelegate=true backend=GPU"
            )
            return gpuInterpreter
        } catch (gpuError: Throwable) {
            try {
                candidateDelegate?.close()
            } catch (_: Throwable) {
            }
            gpuDelegate = null
            Log.w(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE gpuDelegate=false fallback=XNNPACK",
                gpuError
            )
        }

        modelBuffer.rewind()
        val cpuOptions = Interpreter.Options().apply {
            setUseXNNPACK(true)
            setNumThreads(4)
        }
        return Interpreter(modelBuffer, cpuOptions).also {
            Log.i(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE gpuDelegate=false backend=XNNPACK"
            )
        }
    }

    override fun analyze(image: ImageProxy) {
        if (interpreter == null) { image.close(); return }
        try {
            val bitmap = image.toBitmap()
            detectOnBitmap(bitmap, drawOnOverlay = false)
        } catch (e: Exception) {
            Log.e(TAG, "CameraX Error", e)
        } finally {
            image.close()
        }
    }

    fun detectOnBitmap(bitmap: Bitmap, drawOnOverlay: Boolean = true) {
        if (interpreter == null) return
        val t1 = System.currentTimeMillis()
        var letterboxedBitmap: Bitmap? = null

        try {
            val lb = letterbox(bitmap, inputWidth, inputHeight)
            letterboxedBitmap = lb.bitmap

            tensorImage!!.load(letterboxedBitmap)
            val input = imageProcessor.process(tensorImage)
            interpreter!!.run(input.buffer, outputData)

            val trackedResults = processAndTrack(lb.scale, lb.dx, lb.dy, bitmap.width, bitmap.height)

            val bgBitmap = if (drawOnOverlay) bitmap else null
            Log.i(
                TAG,
                "detectOnBitmap results=${trackedResults.size} drawOnOverlay=$drawOnOverlay " +
                    "bitmap=${bitmap.width}x${bitmap.height} bgBitmapNull=${bgBitmap == null}"
            )
            overlayView.updateData(trackedResults, bgBitmap, System.currentTimeMillis() - t1)

        } catch (e: Exception) {
            Log.e(TAG, "Bitmap Detect Error", e)
        } finally {
            letterboxedBitmap?.recycle()
        }
    }

    private fun processAndTrack(scale: Float, dx: Float, dy: Float, origW: Int, origH: Int): List<TrackedDetection> {
        val rawList = ArrayList<RawDetection>()
        val matrix = outputData!![0]

        // 1. 粗筛
        for (i in 0 until numBoxes) {
            val score = if (isChannelsFirst) matrix[4][i] else matrix[i][4]
            if (score > detectThreshold) {
                var cx: Float; var cy: Float; var w: Float; var h: Float
                if (isChannelsFirst) {
                    cx = matrix[0][i]; cy = matrix[1][i]; w = matrix[2][i]; h = matrix[3][i]
                } else {
                    val row = matrix[i]; cx = row[0]; cy = row[1]; w = row[2]; h = row[3]
                }
                if (w < 1.0f) { cx *= inputWidth; cy *= inputHeight; w *= inputWidth; h *= inputHeight }

                val realCx = (cx - dx) / scale / origW
                val realCy = (cy - dy) / scale / origH
                val realW = w / scale / origW
                val realH = h / scale / origH
                val left = realCx - realW / 2
                val top = realCy - realH / 2
                val right = realCx + realW / 2
                val bottom = realCy + realH / 2

                if (left >= 0 && top >= 0 && right <= 1 && bottom <= 1) {
                    rawList.add(RawDetection(RectF(left, top, right, bottom), score))
                }
            }
        }

        // 2. NMS
        val nmsResults = nms(rawList, nmsThreshold)

        // 3. 追踪与状态更新
        val finalResults = ArrayList<TrackedDetection>()
        val usedTrackerIds = HashSet<Int>()

        for (cand in nmsResults) {
            var bestMatchId = -1
            var minDist = Float.MAX_VALUE
            val candCx = cand.rect.centerX()
            val candCy = cand.rect.centerY()
            // 提取宽和高
            val candW = cand.rect.width()
            val candH = cand.rect.height()

            for ((id, history) in trackerMap) {
                if (id in usedTrackerIds) continue
                val dist = sqrt((candCx - history.cx).pow(2) + (candCy - history.cy).pow(2))
                if (dist < 0.1f && dist < minDist) {
                    minDist = dist
                    bestMatchId = id
                }
            }

            if (bestMatchId != -1) {
                val history = trackerMap[bestMatchId]!!
                val moveDist = sqrt((candCx - history.cx).pow(2) + (candCy - history.cy).pow(2))
                val isMoving = moveDist > movementThreshold

                if (!isMoving) history.staticFrameCount++ else history.staticFrameCount = 0
                history.cx = candCx; history.cy = candCy; history.missingFrameCount = 0
                usedTrackerIds.add(bestMatchId)

                if (isMoving || cand.score > staticConfirmThreshold) {
                    // 🔥 传入 w 和 h
                    finalResults.add(TrackedDetection(bestMatchId, candCx, candCy, candW, candH, cand.score, isMoving))
                }
            } else {
                val newId = nextObjectId++
                trackerMap[newId] = ObjectHistory(candCx, candCy)
                if (cand.score > staticConfirmThreshold) {
                    // 🔥 传入 w 和 h
                    finalResults.add(TrackedDetection(newId, candCx, candCy, candW, candH, cand.score, false))
                }
            }
        }

        val iterator = trackerMap.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!usedTrackerIds.contains(entry.key)) {
                entry.value.missingFrameCount++
                if (entry.value.missingFrameCount > 5) iterator.remove()
            }
        }
        return finalResults
    }

    private fun nms(list: MutableList<RawDetection>, threshold: Float): List<RawDetection> {
        val result = ArrayList<RawDetection>()
        list.sortByDescending { it.score }
        while (list.isNotEmpty()) {
            val current = list.removeAt(0)
            result.add(current)
            val iterator = list.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(current.rect, next.rect) > threshold) iterator.remove()
            }
        }
        return result
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val iLeft = max(a.left, b.left); val iTop = max(a.top, b.top)
        val iRight = min(a.right, b.right); val iBottom = min(a.bottom, b.bottom)
        if (iRight < iLeft || iBottom < iTop) return 0f
        val iArea = (iRight - iLeft) * (iBottom - iTop)
        val uArea = (a.width() * a.height()) + (b.width() * b.height()) - iArea
        return iArea / uArea
    }

    private fun letterbox(src: Bitmap, dstW: Int, dstH: Int): LetterboxResult {
        val srcW = src.width; val srcH = src.height
        val scale = min(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val newW = srcW * scale; val newH = srcH * scale
        val dx = (dstW - newW) / 2f; val dy = (dstH - newH) / 2f
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val matrix = Matrix().apply { postScale(scale, scale); postTranslate(dx, dy) }
        canvas.drawBitmap(src, matrix, null)
        return LetterboxResult(out, scale, dx, dy)
    }
}
