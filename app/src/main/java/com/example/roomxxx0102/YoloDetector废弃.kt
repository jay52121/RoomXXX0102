//package com.example.roomxxx0102
//
//import android.Manifest
//import android.content.Context
//import android.content.pm.PackageManager
//import android.graphics.Bitmap
//import android.graphics.Matrix
//import android.graphics.RectF
//import android.os.Bundle
//import android.util.Log
//import android.util.Size as AndroidSize
//import android.view.ViewGroup
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.compose.setContent
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.camera.core.*
//import androidx.camera.core.resolutionselector.AspectRatioStrategy
//import androidx.camera.core.resolutionselector.ResolutionSelector
//import androidx.camera.core.resolutionselector.ResolutionStrategy
//import androidx.camera.lifecycle.ProcessCameraProvider
//import androidx.camera.view.PreviewView
//import androidx.compose.foundation.Canvas
//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.Surface
//import androidx.compose.material3.Text
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.geometry.Offset
//import androidx.compose.ui.geometry.Size
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.graphics.Paint
//import androidx.compose.ui.graphics.drawscope.Stroke
//import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
//import androidx.compose.ui.graphics.nativeCanvas
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.platform.LocalLifecycleOwner
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import androidx.compose.ui.viewinterop.AndroidView
//import androidx.core.content.ContextCompat
//import org.tensorflow.lite.DataType
//import org.tensorflow.lite.Interpreter
//import org.tensorflow.lite.gpu.CompatibilityList
//import org.tensorflow.lite.gpu.GpuDelegate
//import org.tensorflow.lite.support.common.ops.CastOp
//import org.tensorflow.lite.support.common.ops.NormalizeOp
//import org.tensorflow.lite.support.image.ImageProcessor
//import org.tensorflow.lite.support.image.TensorImage
//import org.tensorflow.lite.support.image.ops.ResizeOp
//import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
//import java.io.IOException
//import java.util.concurrent.Executors
//import kotlin.math.max
//import kotlin.math.min
//
//// ==========================================
//// 1. 数据类定义 (放在最外面，确保能找到)
//// ==========================================
//data class YoloResult(
//    val rect: RectF,
//    val confidence: Float,
//    val label: String
//)
//
//// ==========================================
//// 2. YOLO 检测器类 (合并在此，无需单独文件)
//// ==========================================
//class YoloDetector(
//    private val context: Context,
//    private val modelPath: String = "yolo11s_float16.tflite", // ⚠️ 确保文件名正确
//    private val useGpu: Boolean = true
//) {
//    private var interpreter: Interpreter? = null
//    private var inputImageWidth = 0
//    private var inputImageHeight = 0
//    private var outputBoxCount = 0
//
//    init {
//        initInterpreter()
//    }
//
//    private fun initInterpreter() {
//        try {
//            val options = Interpreter.Options()
//            // 强制开启 GPU
//            if (useGpu) {
//                try {
//                    options.addDelegate(GpuDelegate())
//                    Log.d("YOLO", "GPU Delegate Enabled")
//                } catch (e: Exception) {
//                    Log.e("YOLO", "GPU Failed, fallback to CPU", e)
//                    options.setNumThreads(4)
//                }
//            } else {
//                options.setNumThreads(4)
//            }
//
//            val assetFileDescriptor = context.assets.openFd(modelPath)
//            val fileInputStream = java.io.FileInputStream(assetFileDescriptor.fileDescriptor)
//            val fileChannel = fileInputStream.channel
//            val startOffset = assetFileDescriptor.startOffset
//            val declaredLength = assetFileDescriptor.declaredLength
//            val mappedByteBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
//
//            interpreter = Interpreter(mappedByteBuffer, options)
//
//            // 读取输入尺寸 [1, Height, Width, 3]
//            val inputShape = interpreter!!.getInputTensor(0).shape()
//            inputImageHeight = inputShape[1]
//            inputImageWidth = inputShape[2]
//
//            // 兜底修正
//            if (inputImageWidth < 100 || inputImageHeight < 100) {
//                inputImageWidth = 960
//                inputImageHeight = 960
//            }
//
//            // 读取输出尺寸
//            val outputShape = interpreter!!.getOutputTensor(0).shape()
//            outputBoxCount = outputShape[2]
//
//            Log.d("YOLO", "Model Loaded: $inputImageWidth x $inputImageHeight")
//
//        } catch (e: IOException) {
//            e.printStackTrace()
//            Log.e("YOLO", "Model load failed", e)
//        }
//    }
//
//    fun detect(bitmap: Bitmap): List<YoloResult> {
//        if (interpreter == null) return emptyList()
//
//        val imageProcessor = ImageProcessor.Builder()
//            .add(ResizeOp(inputImageHeight, inputImageWidth, ResizeOp.ResizeMethod.BILINEAR))
//            .add(NormalizeOp(0f, 255f))
//            .add(CastOp(DataType.FLOAT32))
//            .build()
//
//        var tensorImage = TensorImage(DataType.FLOAT32)
//        tensorImage.load(bitmap)
//        tensorImage = imageProcessor.process(tensorImage)
//
//        val outputTensor = interpreter!!.getOutputTensor(0)
//        val outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), DataType.FLOAT32)
//
//        interpreter!!.run(tensorImage.buffer, outputBuffer.buffer.rewind())
//
//        val outputArray = outputBuffer.floatArray
//        val results = mutableListOf<YoloResult>()
//        val cols = outputBoxCount
//
//        for (i in 0 until cols) {
//            // Class 0 (Person) 置信度在索引 4
//            val score = outputArray[4 * cols + i]
//
//            if (score > 0.50f) {
//                val cx = outputArray[0 * cols + i]
//                val cy = outputArray[1 * cols + i]
//                val w = outputArray[2 * cols + i]
//                val h = outputArray[3 * cols + i]
//
//                val x1 = (cx - w / 2) * bitmap.width / inputImageWidth
//                val y1 = (cy - h / 2) * bitmap.height / inputImageHeight
//                val x2 = (cx + w / 2) * bitmap.width / inputImageWidth
//                val y2 = (cy + h / 2) * bitmap.height / inputImageHeight
//
//                results.add(YoloResult(RectF(x1, y1, x2, y2), score, "Person"))
//            }
//        }
//
//        return nms(results)
//    }
//
//    private fun nms(boxes: List<YoloResult>, threshold: Float = 0.5f): List<YoloResult> {
//        if (boxes.isEmpty()) return emptyList()
//        val sorted = boxes.sortedByDescending { it.confidence }
//        val selected = mutableListOf<YoloResult>()
//        val active = BooleanArray(sorted.size) { true }
//
//        for (i in sorted.indices) {
//            if (!active[i]) continue
//            val boxA = sorted[i]
//            selected.add(boxA)
//
//            for (j in i + 1 until sorted.size) {
//                if (!active[j]) continue
//                val boxB = sorted[j]
//
//                val interLeft = max(boxA.rect.left, boxB.rect.left)
//                val interTop = max(boxA.rect.top, boxB.rect.top)
//                val interRight = min(boxA.rect.right, boxB.rect.right)
//                val interBottom = min(boxA.rect.bottom, boxB.rect.bottom)
//
//                if (interLeft < interRight && interTop < interBottom) {
//                    val interArea = (interRight - interLeft) * (interBottom - interTop)
//                    val areaA = boxA.rect.width() * boxA.rect.height()
//                    val areaB = boxB.rect.width() * boxB.rect.height()
//                    val iou = interArea / (areaA + areaB - interArea)
//                    if (iou > threshold) active[j] = false
//                }
//            }
//        }
//        return selected
//    }
//}
//
//// ==========================================
//// 3. 主界面 Activity
//// ==========================================
//class MainActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContent {
//            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
//                YoloApp()
//            }
//        }
//    }
//}
//
//@Composable
//fun YoloApp() {
//    val context = LocalContext.current
//    var hasCameraPermission by remember {
//        mutableStateOf(
//            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
//        )
//    }
//
//    val launcher = rememberLauncherForActivityResult(
//        contract = ActivityResultContracts.RequestPermission(),
//        onResult = { granted -> hasCameraPermission = granted }
//    )
//
//    LaunchedEffect(Unit) {
//        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
//    }
//
//    if (hasCameraPermission) {
//        YoloDetectionScreen()
//    } else {
//        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
//            Text("需要摄像头权限", color = Color.Black)
//        }
//    }
//}
//
//@Composable
//fun YoloDetectionScreen() {
//    val context = LocalContext.current
//    val lifecycleOwner = LocalLifecycleOwner.current
//
//    // UI状态
//    var detectedResults by remember { mutableStateOf<List<YoloResult>>(emptyList()) }
//    var personDetectedState by remember { mutableStateOf(false) }
//    var inferenceTime by remember { mutableLongStateOf(0L) }
//
//    // 图片源尺寸 (用于绘图对齐)
//    var sourceImageWidth by remember { mutableFloatStateOf(1f) }
//    var sourceImageHeight by remember { mutableFloatStateOf(1f) }
//
//    var lastTimePersonSeen by remember { mutableLongStateOf(0L) }
//    val turnOffDelayMs = 5000L
//
//    // 初始化检测器
//    val yoloDetector = remember {
//        YoloDetector(context, "yolo11s_float16.tflite", useGpu = true)
//    }
//
//    LaunchedEffect(detectedResults) {
//        val currentTime = System.currentTimeMillis()
//        if (detectedResults.isNotEmpty()) {
//            lastTimePersonSeen = currentTime
//            personDetectedState = true
//        } else {
//            if (currentTime - lastTimePersonSeen > turnOffDelayMs) {
//                personDetectedState = false
//            }
//        }
//    }
//
//    Box(modifier = Modifier.fillMaxSize()) {
//        // 1. 相机预览
//        AndroidView(
//            factory = { ctx ->
//                PreviewView(ctx).apply {
//                    scaleType = PreviewView.ScaleType.FILL_CENTER
//                    layoutParams = ViewGroup.LayoutParams(-1, -1)
//
//                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
//                    cameraProviderFuture.addListener({
//                        try {
//                            val cameraProvider = cameraProviderFuture.get()
//
//                            val resolutionSelector = ResolutionSelector.Builder()
//                                .setAspectRatioStrategy(
//                                    AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
//                                )
//                                .setResolutionStrategy(
//                                    ResolutionStrategy(
//                                        AndroidSize(1600, 1200),
//                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
//                                    )
//                                )
//                                .build()
//
//                            val preview = Preview.Builder()
//                                .setResolutionSelector(resolutionSelector)
//                                .build()
//                            preview.setSurfaceProvider(this.surfaceProvider)
//
//                            val analyzer = ImageAnalysis.Builder()
//                                .setResolutionSelector(resolutionSelector)
//                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
//                                .build()
//
//                            analyzer.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
//                                val start = System.currentTimeMillis()
//                                val rotation = imageProxy.imageInfo.rotationDegrees
//                                val bitmap = imageProxy.toBitmap()
//
//                                val rotatedBitmap = if (rotation != 0) {
//                                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
//                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
//                                } else {
//                                    bitmap
//                                }
//
//                                sourceImageWidth = rotatedBitmap.width.toFloat()
//                                sourceImageHeight = rotatedBitmap.height.toFloat()
//
//                                val results = yoloDetector.detect(rotatedBitmap)
//
//                                inferenceTime = System.currentTimeMillis() - start
//                                detectedResults = results
//                                imageProxy.close()
//                            }
//
//                            cameraProvider.unbindAll()
//                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
//                        } catch (e: Exception) {
//                            Log.e("Camera", "Error", e)
//                        }
//                    }, ContextCompat.getMainExecutor(ctx))
//                }
//            },
//            modifier = Modifier.fillMaxSize()
//        )
//
//        // 2. 绘图层 (修正后的坐标算法)
//        Canvas(modifier = Modifier.fillMaxSize()) {
//            val viewWidth = size.width
//            val viewHeight = size.height
//
//            // 模拟 FILL_CENTER 算法
//            val scale = max(viewWidth / sourceImageWidth, viewHeight / sourceImageHeight)
//            val scaledW = sourceImageWidth * scale
//            val scaledH = sourceImageHeight * scale
//            val offsetX = (viewWidth - scaledW) / 2f
//            val offsetY = (viewHeight - scaledH) / 2f
//
//            detectedResults.forEach { res ->
//                val left = res.rect.left * scale + offsetX
//                val top = res.rect.top * scale + offsetY
//                val right = res.rect.right * scale + offsetX
//                val bottom = res.rect.bottom * scale + offsetY
//
//                drawRect(
//                    color = Color.Green,
//                    topLeft = Offset(left, top),
//                    size = Size(right - left, bottom - top),
//                    style = Stroke(width = 8f)
//                )
//
//                drawIntoCanvas { canvas ->
//                    val paint = Paint().asFrameworkPaint().apply {
//                        textSize = 50f
//                        color = android.graphics.Color.GREEN
//                        isAntiAlias = true
//                        typeface = android.graphics.Typeface.DEFAULT_BOLD
//                        setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
//                    }
//                    val text = "ID ${(res.confidence * 100).toInt()}%"
//                    canvas.nativeCanvas.drawText(text, left, top - 20f, paint)
//                }
//            }
//        }
//
//        // 3. 状态面板
//        Column(
//            modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
//            horizontalAlignment = Alignment.CenterHorizontally
//        ) {
//            Box(
//                modifier = Modifier
//                    .size(120.dp, 60.dp)
//                    .background(if (personDetectedState) Color.Green else Color.Gray, shape = MaterialTheme.shapes.medium),
//                contentAlignment = Alignment.Center
//            ) {
//                Text(
//                    text = if (personDetectedState) "ON" else "OFF",
//                    color = Color.White,
//                    fontSize = 30.sp,
//                    style = MaterialTheme.typography.titleLarge
//                )
//            }
//
//            Spacer(modifier = Modifier.height(10.dp))
//
//            Text(
//                text = "Time: ${inferenceTime}ms",
//                color = if (inferenceTime > 100) Color.Red else Color.Green,
//                fontSize = 20.sp,
//                modifier = Modifier.background(Color.Black.copy(alpha=0.5f)).padding(4.dp)
//            )
//        }
//    }
//}