#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import textwrap
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_COMMIT = "36dd69a5da3de78b72b631d1e2d13b3f92710b50"
TARGET_BRANCH = "9月新房间判定算法"
TMP_BRANCH = "codex/yolo26-litert-w8a32"

DETECT_URL = "https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.6.6/yolo26s_w8a32.tflite"
POSE_URL = "https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.6.6/yolo26s-pose_w8a32.tflite"
DETECT_SHA256 = "7a598838082251ef8e1d3b8f06f356ad532f8fe3a24c7eeeed68b52d28525036"
POSE_SHA256 = "8409d9109f1bb374e28a9ff925205580e62cbcf73528b11777b667f8e28611f8"
DETECT_SIZE = 10086787
POSE_SIZE = 11071540

ASSETS = ROOT / "app/src/main/assets"
DETECT_MODEL = ASSETS / "yolo26s_w8a32.tflite"
POSE_MODEL = ASSETS / "yolo26s-pose_w8a32.tflite"
OLD_DETECT = ASSETS / "yolo26s_float16.tflite"
OLD_POSE = ASSETS / "yolo26s_pose_float16.tflite"
YOLO = ROOT / "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloAnalyzer.kt"
POSE = ROOT / "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloPoseAnalyzer.kt"
RUNNER = ROOT / "app/src/main/java/com/example/roomxxx0102/logic/analyzer/LiteRtYoloRunner.kt"
VERSIONS = ROOT / "gradle/libs.versions.toml"
APP_GRADLE = ROOT / "app/build.gradle.kts"
CODEX_HISTORY = ROOT / "codexHistory.md"
DIALOGUE_HISTORY = ROOT / "dialogueHistory.md"
USER_TMP = ROOT / "tools/_dialogue_user_current.txt"
ASSISTANT_TMP = ROOT / "tools/_dialogue_assistant_current.txt"
WORKFLOW = ROOT / ".github/workflows/yolo26-litert.yml"
SELF = Path(__file__).resolve()
REPORT = ROOT / "yolo26_litert_report.json"

USER_TEXT = "好啊，你准备怎么改，开始吧"
ASSISTANT_TEXT = """已按确认开始执行：将 YOLO26 Android 主链从 legacy FP16 TFLite/Interpreter 迁移到 Ultralytics 当前官方 LiteRT w8a32 资产与 LiteRT 2.x CompiledModel；保留现有阈值、外部 NMS、Tracker、ROI、房间与绘制业务链，并完成 Tensor、单测、Debug APK、资产与 ABI 验证后推送。"""


def run(args: list[str], cwd: Path = ROOT, capture: bool = False, check: bool = True) -> subprocess.CompletedProcess[str]:
    print("$ " + " ".join(args), flush=True)
    return subprocess.run(
        args,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=check,
    )


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 exact match, got {count}")
    return text.replace(old, new, 1)


def regex_replace_once(text: str, pattern: str, repl: str, label: str) -> str:
    updated, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 regex match, got {count}")
    return updated


def download_verified(url: str, target: Path, expected_sha: str, expected_size: int) -> None:
    print(f"Downloading {url}", flush=True)
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    actual_sha = hashlib.sha256(data).hexdigest()
    if len(data) != expected_size:
        raise RuntimeError(f"{target.name}: size {len(data)} != expected {expected_size}")
    if actual_sha != expected_sha:
        raise RuntimeError(f"{target.name}: sha256 {actual_sha} != expected {expected_sha}")
    target.write_bytes(data)
    print(f"Verified {target.name}: {len(data)} bytes sha256={actual_sha}", flush=True)


def runner_source() -> str:
    return r'''package com.example.roomxxx0102.logic.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * YOLO26 LiteRT 2.x 运行器。
 *
 * 只负责模型加载、Tensor 契约、RGB->FLOAT32 输入打包和推理；检测/Pose 的阈值、NMS、Tracker、ROI 与房间逻辑
 * 仍由原 Analyzer 负责。GPU 必须完成一次真实 warmup 才算成功，否则整图回退 CPU/XNNPACK。
 */
internal class LiteRtYoloRunner(
    context: Context,
    private val modelAssetName: String,
    private val architecture: String,
    private val expectedOutputFeatures: Int,
) : AutoCloseable {

    enum class OutputLayout { FEATURES_FIRST, ANCHORS_FIRST }

    private data class Prepared(
        val model: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
        val nativeInputShape: IntArray,
        val inputUsesNchw: Boolean,
        val outputShape: IntArray,
        val outputLayout: OutputLayout,
        val inputType: TensorType.ElementType,
        val outputType: TensorType.ElementType,
        val backend: String,
    )

    private val model: CompiledModel
    private val inputBuffers: List<TensorBuffer>
    private val outputBuffers: List<TensorBuffer>
    val nativeInputShape: IntArray
    val inputUsesNchw: Boolean
    val outputShape: IntArray
    val outputLayout: OutputLayout
    val backend: String
    val inputWidth: Int
    val inputHeight: Int
    val numFeatures: Int
    val numAnchors: Int

    private val pixelBuffer: IntArray
    private val floatInput: FloatArray

    init {
        val mapped = mapAsset(context, modelAssetName)
        val prepared = try {
            prepare(mapped, Accelerator.GPU, "GPU")
        } catch (gpuError: Throwable) {
            Log.w(
                TAG,
                "model=$modelAssetName architecture=$architecture quantization=w8a32 gpu=false fallback=CPU reason=${gpuError.message}",
                gpuError,
            )
            prepare(mapped, Accelerator.CPU, "CPU/XNNPACK")
        }

        model = prepared.model
        inputBuffers = prepared.inputs
        outputBuffers = prepared.outputs
        nativeInputShape = prepared.nativeInputShape
        inputUsesNchw = prepared.inputUsesNchw
        outputShape = prepared.outputShape
        outputLayout = prepared.outputLayout
        backend = prepared.backend

        inputWidth = if (inputUsesNchw) nativeInputShape[3] else nativeInputShape[2]
        inputHeight = if (inputUsesNchw) nativeInputShape[2] else nativeInputShape[1]
        numFeatures = if (outputLayout == OutputLayout.FEATURES_FIRST) outputShape[1] else outputShape[2]
        numAnchors = if (outputLayout == OutputLayout.FEATURES_FIRST) outputShape[2] else outputShape[1]

        pixelBuffer = IntArray(inputWidth * inputHeight)
        floatInput = FloatArray(inputWidth * inputHeight * 3)

        Log.i(
            TAG,
            "model=$modelAssetName architecture=$architecture quantization=w8a32 " +
                "inputShape=${nativeInputShape.contentToString()} inputDtype=${prepared.inputType} " +
                "layout=${if (inputUsesNchw) "NCHW" else "NHWC"} " +
                "outputShape=${outputShape.contentToString()} outputDtype=${prepared.outputType} " +
                "outputLayout=$outputLayout backend=$backend gpu=${backend == "GPU"} externalNms=true ready=true",
        )
    }

    fun run(bitmap: Bitmap): FloatArray {
        require(bitmap.width == inputWidth && bitmap.height == inputHeight) {
            "$architecture expected ${inputWidth}x${inputHeight} bitmap, got ${bitmap.width}x${bitmap.height}"
        }
        packRgb(bitmap)
        inputBuffers[0].writeFloat(floatInput)
        model.run(inputBuffers, outputBuffers)
        val output = outputBuffers[0].readFloat()
        require(output.size == numFeatures * numAnchors) {
            "$architecture output element count ${output.size} != ${numFeatures * numAnchors}"
        }
        return output
    }

    fun value(flat: FloatArray, feature: Int, anchor: Int): Float {
        return if (outputLayout == OutputLayout.FEATURES_FIRST) {
            flat[feature * numAnchors + anchor]
        } else {
            flat[anchor * numFeatures + feature]
        }
    }

    private fun packRgb(bitmap: Bitmap) {
        bitmap.getPixels(pixelBuffer, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val plane = inputWidth * inputHeight
        if (inputUsesNchw) {
            for (i in 0 until plane) {
                val px = pixelBuffer[i]
                floatInput[i] = ((px shr 16) and 0xFF) / 255f
                floatInput[plane + i] = ((px shr 8) and 0xFF) / 255f
                floatInput[plane * 2 + i] = (px and 0xFF) / 255f
            }
        } else {
            var out = 0
            for (i in 0 until plane) {
                val px = pixelBuffer[i]
                floatInput[out++] = ((px shr 16) and 0xFF) / 255f
                floatInput[out++] = ((px shr 8) and 0xFF) / 255f
                floatInput[out++] = (px and 0xFF) / 255f
            }
        }
    }

    private fun prepare(modelBuffer: ByteBuffer, accelerator: Accelerator, backendName: String): Prepared {
        val options = CompiledModel.Options(accelerator)
        if (accelerator == Accelerator.CPU) {
            options.cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
        }
        val compiled = CompiledModel.create(modelBuffer.duplicate().apply { rewind() }, options)
        val inputs = compiled.createInputBuffers()
        val outputs = compiled.createOutputBuffers()
        try {
            require(inputs.size == 1) { "$architecture expects 1 input tensor, got ${inputs.size}" }
            require(outputs.size == 1) { "$architecture expects 1 output tensor, got ${outputs.size}" }

            val inputType = findInputType(compiled)
                ?: throw IllegalStateException("$architecture cannot resolve input tensor type/name")
            val outputType = findOutputType(compiled)
                ?: throw IllegalStateException("$architecture cannot resolve output tensor type/name")
            val inputShape = inputType.layout?.dimensions?.toIntArray()
                ?: throw IllegalStateException("$architecture input shape missing")
            val outShape = outputType.layout?.dimensions?.toIntArray()
                ?: throw IllegalStateException("$architecture output shape missing")

            require(inputType.elementType == TensorType.ElementType.FLOAT) {
                "$architecture input dtype must be FLOAT32, got ${inputType.elementType}"
            }
            require(outputType.elementType == TensorType.ElementType.FLOAT) {
                "$architecture output dtype must be FLOAT32, got ${outputType.elementType}"
            }

            val nchw = inputShape.contentEquals(intArrayOf(1, 3, 640, 640))
            val nhwc = inputShape.contentEquals(intArrayOf(1, 640, 640, 3))
            require(nchw || nhwc) {
                "$architecture unexpected input shape ${inputShape.contentToString()}"
            }
            require(outShape.size == 3 && outShape[0] == 1) {
                "$architecture unexpected output shape ${outShape.contentToString()}"
            }
            val layout = when {
                outShape[1] == expectedOutputFeatures && outShape[2] > expectedOutputFeatures -> OutputLayout.FEATURES_FIRST
                outShape[2] == expectedOutputFeatures && outShape[1] > expectedOutputFeatures -> OutputLayout.ANCHORS_FIRST
                else -> throw IllegalStateException(
                    "$architecture expected raw output with $expectedOutputFeatures features, got ${outShape.contentToString()}"
                )
            }

            // 实际执行一次零输入；GPU 只有连 warmup 都成功才算可用，失败会回退 CPU。
            inputs[0].writeFloat(FloatArray(640 * 640 * 3))
            compiled.run(inputs, outputs)
            val warmup = outputs[0].readFloat()
            val expectedElements = outShape[1] * outShape[2]
            require(warmup.size == expectedElements) {
                "$architecture warmup output ${warmup.size} != $expectedElements"
            }

            return Prepared(
                model = compiled,
                inputs = inputs,
                outputs = outputs,
                nativeInputShape = inputShape,
                inputUsesNchw = nchw,
                outputShape = outShape,
                outputLayout = layout,
                inputType = inputType.elementType,
                outputType = outputType.elementType,
                backend = backendName,
            )
        } catch (t: Throwable) {
            closeBuffers(inputs, outputs)
            runCatching { compiled.close() }
            throw t
        }
    }

    private fun findInputType(model: CompiledModel): TensorType? {
        return sequenceOf("args_0", "images", "input", "input_1", "serving_default_input")
            .firstNotNullOfOrNull { name -> runCatching { model.getInputTensorType(inputName = name) }.getOrNull() }
    }

    private fun findOutputType(model: CompiledModel): TensorType? {
        return sequenceOf("output_0", "output0", "Identity")
            .firstNotNullOfOrNull { name -> runCatching { model.getOutputTensorType(outputName = name) }.getOrNull() }
    }

    override fun close() {
        closeBuffers(inputBuffers, outputBuffers)
        runCatching { model.close() }
    }

    private fun closeBuffers(inputs: List<TensorBuffer>, outputs: List<TensorBuffer>) {
        inputs.forEach { runCatching { it.close() } }
        outputs.forEach { runCatching { it.close() } }
    }

    private fun mapAsset(context: Context, assetName: String): ByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { stream ->
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    companion object {
        private const val TAG = "LiteRtYoloRunner"
    }
}
'''


def patch_yolo_analyzer() -> None:
    text = read(YOLO)
    text = replace_once(text, '        private const val MODEL_FILE_NAME = "yolo26s_float16.tflite"', '        private const val MODEL_FILE_NAME = "yolo26s_w8a32.tflite"', "detect model name")
    for line in [
        "import org.tensorflow.lite.DataType\n",
        "import org.tensorflow.lite.Interpreter\n",
        "import org.tensorflow.lite.gpu.GpuDelegate\n",
        "import org.tensorflow.lite.support.image.ImageProcessor\n",
        "import org.tensorflow.lite.support.image.TensorImage\n",
        "import java.io.FileInputStream\n",
        "import java.nio.channels.FileChannel\n",
    ]:
        text = text.replace(line, "")
    text = replace_once(text, "import kotlin.math.max\n", "import kotlin.math.abs\nimport kotlin.math.max\n", "detect abs import")

    old_fields = '''    private var interpreter: Interpreter? = null
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
'''
    new_fields = '''    private var runner: LiteRtYoloRunner? = null
    private var inputWidth = 640
    private var inputHeight = 640

    private val trackerMap = ConcurrentHashMap<Int, ObjectHistory>()
    private var nextObjectId = 0

    private var numClasses = 80
    private var numBoxes = 8400
'''
    text = replace_once(text, old_fields, new_fields, "detect fields")

    setup_pattern = r'''    private fun setupInterpreter\(\) \{.*?\n    \}\n\n    override fun analyze\(image: ImageProxy\) \{'''
    setup_repl = '''    private fun setupInterpreter() {
        try {
            val activeRunner = LiteRtYoloRunner(
                context = context,
                modelAssetName = MODEL_FILE_NAME,
                architecture = MODEL_ARCHITECTURE,
                expectedOutputFeatures = 84,
            )
            runner = activeRunner
            inputWidth = activeRunner.inputWidth
            inputHeight = activeRunner.inputHeight
            numClasses = activeRunner.numFeatures - 4
            numBoxes = activeRunner.numAnchors
            require(numClasses == 80) {
                "$MODEL_ARCHITECTURE expected 80 classes, got $numClasses"
            }
            Log.i(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE quantization=w8a32 " +
                    "input=${activeRunner.nativeInputShape.contentToString()} output=${activeRunner.outputShape.contentToString()} " +
                    "backend=${activeRunner.backend} personClass=0 externalNms=true",
            )
        } catch (e: Throwable) {
            runner?.close()
            runner = null
            Log.e(TAG, "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE initializationFailed=true", e)
        }
    }

    override fun analyze(image: ImageProxy) {'''
    text = regex_replace_once(text, setup_pattern, setup_repl, "detect setup")
    text = text.replace("        if (interpreter == null) { image.close(); return }", "        if (runner == null) { image.close(); return }")
    text = text.replace("        if (interpreter == null) return", "        val activeRunner = runner ?: return")

    old_infer = '''            tensorImage!!.load(letterboxedBitmap)
            val input = imageProcessor.process(tensorImage)
            interpreter!!.run(input.buffer, outputData)

            val trackedResults = processAndTrack(lb.scale, lb.dx, lb.dy, bitmap.width, bitmap.height)
'''
    new_infer = '''            val output = activeRunner.run(letterboxedBitmap)
            val trackedResults = processAndTrack(
                output,
                activeRunner,
                lb.scale,
                lb.dx,
                lb.dy,
                bitmap.width,
                bitmap.height,
            )
'''
    text = replace_once(text, old_infer, new_infer, "detect inference")

    start = '''    private fun processAndTrack(scale: Float, dx: Float, dy: Float, origW: Int, origH: Int): List<TrackedDetection> {
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
'''
    replacement = '''    private fun processAndTrack(
        output: FloatArray,
        activeRunner: LiteRtYoloRunner,
        scale: Float,
        dx: Float,
        dy: Float,
        origW: Int,
        origH: Int,
    ): List<TrackedDetection> {
        val rawList = ArrayList<RawDetection>()

        // 1. 粗筛。保持既有“只取 person(class 0)”策略：raw head 的 feature 4 即 person 分数。
        for (i in 0 until numBoxes) {
            val score = activeRunner.value(output, 4, i)
            if (score > detectThreshold) {
                var cx = activeRunner.value(output, 0, i)
                var cy = activeRunner.value(output, 1, i)
                var w = activeRunner.value(output, 2, i)
                var h = activeRunner.value(output, 3, i)
                val normalized = max(max(abs(cx), abs(cy)), max(abs(w), abs(h))) <= 2f
                if (normalized) {
                    cx *= inputWidth
                    cy *= inputHeight
                    w *= inputWidth
                    h *= inputHeight
                }
'''
    text = replace_once(text, start, replacement, "detect flat parsing")
    write(YOLO, text)


def patch_pose_analyzer() -> None:
    text = read(POSE)
    text = replace_once(text, '        private const val MODEL_FILE_NAME = "yolo26s_pose_float16.tflite"', '        private const val MODEL_FILE_NAME = "yolo26s-pose_w8a32.tflite"', "pose model name")
    for line in [
        "import org.tensorflow.lite.DataType\n",
        "import org.tensorflow.lite.Interpreter\n",
        "import org.tensorflow.lite.gpu.GpuDelegate\n",
        "import org.tensorflow.lite.support.common.ops.CastOp\n",
        "import org.tensorflow.lite.support.common.ops.NormalizeOp\n",
        "import org.tensorflow.lite.support.image.ImageProcessor\n",
        "import org.tensorflow.lite.support.image.TensorImage\n",
        "import org.tensorflow.lite.support.image.ops.ResizeOp\n",
        "import java.io.FileInputStream\n",
        "import java.nio.channels.FileChannel\n",
    ]:
        text = text.replace(line, "")
    text = replace_once(text, "import kotlin.math.max\n", "import kotlin.math.abs\nimport kotlin.math.max\n", "pose abs import")

    old_fields = '''    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // [Model Input Size]: 通常为 640x640
    private var modelInputWidth = 640
    private var modelInputHeight = 640
    
    private var tensorImage: TensorImage? = null
    private var modelOutputBuffer: Array<Array<FloatArray>>? = null
'''
    new_fields = '''    private var runner: LiteRtYoloRunner? = null
    
    // [Model Input Size]: YOLO26 官方移动端标准为 640x640
    private var modelInputWidth = 640
    private var modelInputHeight = 640
'''
    text = replace_once(text, old_fields, new_fields, "pose fields")

    preprocess = '''    // [Preprocessing]: 图像预处理管线
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(640, 640, ResizeOp.ResizeMethod.BILINEAR)) 
        .add(NormalizeOp(0f, 255f))
        .add(CastOp(DataType.FLOAT32))
        .build()

'''
    text = replace_once(text, preprocess, "", "pose support preprocessing")

    setup_pattern = r'''    private fun initializeInterpreter\(\) \{.*?\n    \}\n\n    // CameraX 接口实现'''
    setup_repl = '''    private fun initializeInterpreter() {
        try {
            val assets = context.assets.list("")
            if (assets == null || !assets.contains(MODEL_FILE_NAME)) {
                Log.e(TAG, "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE assetMissing=true")
                return
            }
            val activeRunner = LiteRtYoloRunner(
                context = context,
                modelAssetName = MODEL_FILE_NAME,
                architecture = MODEL_ARCHITECTURE,
                expectedOutputFeatures = 56,
            )
            runner = activeRunner
            modelInputWidth = activeRunner.inputWidth
            modelInputHeight = activeRunner.inputHeight
            Log.i(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE quantization=w8a32 " +
                    "input=${activeRunner.nativeInputShape.contentToString()} output=${activeRunner.outputShape.contentToString()} " +
                    "backend=${activeRunner.backend} poseKeypoints=17 externalNms=true",
            )
        } catch (e: Throwable) {
            runner?.close()
            runner = null
            Log.e(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE initializationFailed=true",
                e,
            )
        }
    }

    // CameraX 接口实现'''
    text = regex_replace_once(text, setup_pattern, setup_repl, "pose setup")
    text = text.replace("        if (interpreter == null) { image.close(); return }", "        if (runner == null) { image.close(); return }")
    text = text.replace("        if (interpreter == null) {", "        if (runner == null) {")

    old_infer = '''            // 2. 加载与预处理
            tensorImage!!.load(inputBitmap)
            val input = imageProcessor.process(tensorImage)
            
            // 3. 模型推理
            interpreter!!.run(input.buffer, modelOutputBuffer)

            // 4. 提取原始数据 (Raw Parsing)
            val rawPoseCandidates = extractRawPosesFromModelOutput(modelOutputBuffer!![0], roiPx, bitmap.width, bitmap.height)
'''
    new_infer = '''            // 2. 保持原有 ResizeOp 的几何行为：ROI/全图直接双线性缩放到 640x640，不改成 letterbox。
            val modelBitmap = if (inputBitmap.width == modelInputWidth && inputBitmap.height == modelInputHeight) {
                inputBitmap
            } else {
                Bitmap.createScaledBitmap(inputBitmap, modelInputWidth, modelInputHeight, true)
            }

            // 3. 模型推理。LiteRT-Torch 模型通常为 NCHW，Runner 会按实际 Tensor layout 打包 RGB。
            val activeRunner = runner ?: return
            val output = try {
                activeRunner.run(modelBitmap)
            } finally {
                if (modelBitmap !== inputBitmap) modelBitmap.recycle()
            }

            // 4. 提取原始数据 (Raw Parsing)
            val rawPoseCandidates = extractRawPosesFromModelOutput(output, activeRunner, roiPx, bitmap.width, bitmap.height)
'''
    text = replace_once(text, old_infer, new_infer, "pose inference")

    old_sig = '''    private fun extractRawPosesFromModelOutput(
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
'''
    new_sig = '''    private fun extractRawPosesFromModelOutput(
        outputTensor: FloatArray,
        activeRunner: LiteRtYoloRunner,
        roiPx: RectF?,
        fullWidth: Int,
        fullHeight: Int,
    ): MutableList<PoseResult> {
        val results = ArrayList<PoseResult>()
        val numAnchors = activeRunner.numAnchors
        val numChannels = activeRunner.numFeatures
        if (outputTensor.isEmpty() || numChannels < 56) return results

        for (i in 0 until numAnchors) {
            val score = activeRunner.value(outputTensor, 4, i)
            
            if (score > MIN_CANDIDATE_SCORE_THRESHOLD) {
                var cx = activeRunner.value(outputTensor, 0, i)
                var cy = activeRunner.value(outputTensor, 1, i)
                var w = activeRunner.value(outputTensor, 2, i)
                var h = activeRunner.value(outputTensor, 3, i)

                // 新 LiteRT raw head 可能输出模型像素坐标，也可能是 0..1；统一归一化后再进入既有 ROI 映射。
                val outputIsNormalized = max(max(abs(cx), abs(cy)), max(abs(w), abs(h))) <= 2f
                if (!outputIsNormalized) {
                    cx /= modelInputWidth
                    cy /= modelInputHeight
                    w /= modelInputWidth
                    h /= modelInputHeight
                }
'''
    text = replace_once(text, old_sig, new_sig, "pose flat parser signature")

    old_kp = '''                    val rawKx = outputTensor[5 + k * 3][i]
                    val rawKy = outputTensor[6 + k * 3][i]
                    var kx = rawKx
                    var ky = rawKy
                    val kConf = outputTensor[7 + k * 3][i]
                    
                    // 关键点按归一化坐标处理（允许轻微越界，例如 >1）
'''
    new_kp = '''                    val rawKx = activeRunner.value(outputTensor, 5 + k * 3, i)
                    val rawKy = activeRunner.value(outputTensor, 6 + k * 3, i)
                    var kx = if (outputIsNormalized) rawKx else rawKx / modelInputWidth
                    var ky = if (outputIsNormalized) rawKy else rawKy / modelInputHeight
                    val kConf = activeRunner.value(outputTensor, 7 + k * 3, i)
                    
                    // 关键点与 bbox 使用同一输出坐标尺度，归一化后继续既有 ROI 逆映射。
'''
    text = replace_once(text, old_kp, new_kp, "pose keypoints")
    write(POSE, text)


def patch_dependencies() -> None:
    versions = read(VERSIONS)
    versions = replace_once(versions, 'litert = "1.4.1"', 'litert = "2.1.5"', "LiteRT core version")
    write(VERSIONS, versions)

    gradle = read(APP_GRADLE)
    old = '''    // LiteRT
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.tensorflow.lite.support)
'''
    new = '''    // LiteRT 2.x CompiledModel；GPU accelerator 已包含在 core，无需旧 litert-gpu / TFLite Support。
    implementation(libs.litert)
'''
    gradle = replace_once(gradle, old, new, "app LiteRT dependencies")
    write(APP_GRADLE, gradle)


def inspect_models() -> dict:
    script = r'''
import json
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from pathlib import Path

models = [
    (Path("app/src/main/assets/yolo26s_w8a32.tflite"), 84),
    (Path("app/src/main/assets/yolo26s-pose_w8a32.tflite"), 56),
]
report = {}
for path, features in models:
    interpreter = Interpreter(model_path=str(path))
    interpreter.allocate_tensors()
    ins = interpreter.get_input_details()
    outs = interpreter.get_output_details()
    assert len(ins) == 1, (path, ins)
    assert len(outs) == 1, (path, outs)
    inp = ins[0]
    out = outs[0]
    shape = tuple(int(v) for v in inp["shape"])
    out_shape = tuple(int(v) for v in out["shape"])
    assert inp["dtype"] == np.float32, (path, inp["dtype"])
    assert out["dtype"] == np.float32, (path, out["dtype"])
    assert shape in ((1, 3, 640, 640), (1, 640, 640, 3)), (path, shape)
    assert len(out_shape) == 3 and out_shape[0] == 1, (path, out_shape)
    assert (out_shape[1] == features and out_shape[2] > features) or (out_shape[2] == features and out_shape[1] > features), (path, out_shape)
    interpreter.set_tensor(inp["index"], np.zeros(shape, dtype=np.float32))
    interpreter.invoke()
    result = interpreter.get_tensor(out["index"])
    assert result.size == out_shape[1] * out_shape[2]
    assert np.isfinite(result).all()
    report[path.name] = {
        "input_name": inp["name"],
        "input_shape": list(shape),
        "input_dtype": str(inp["dtype"]),
        "output_name": out["name"],
        "output_shape": list(out_shape),
        "output_dtype": str(out["dtype"]),
        "zero_inference_finite": True,
        "size_bytes": path.stat().st_size,
    }
print(json.dumps(report, ensure_ascii=False, indent=2))
Path("yolo26_litert_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
'''
    result = run([sys.executable, "-c", script], capture=True)
    print(result.stdout, flush=True)
    return json.loads(REPORT.read_text(encoding="utf-8"))


def build_and_verify() -> tuple[int, int | None, list[str]]:
    run(["bash", "gradlew", ":app:testDebugUnitTest", ":app:assembleDebug", "--stacktrace"])
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    if not apk.exists():
        raise RuntimeError("Debug APK not found")
    new_size = apk.stat().st_size
    with zipfile.ZipFile(apk) as zf:
        names = set(zf.namelist())
        required = {
            "assets/yolo26s_w8a32.tflite",
            "assets/yolo26s-pose_w8a32.tflite",
        }
        missing = sorted(required - names)
        if missing:
            raise RuntimeError(f"APK missing models: {missing}")
        forbidden = [name for name in names if "yolo26" in name and ("float16" in name or "pose_float16" in name)]
        if forbidden:
            raise RuntimeError(f"APK still contains legacy FP16 models: {forbidden}")
        abi_entries = sorted(name for name in names if name.startswith("lib/") and name.endswith(".so"))
        abis = sorted({name.split("/")[1] for name in abi_entries})
        if abis != ["arm64-v8a"]:
            raise RuntimeError(f"Unexpected APK ABIs: {abis}")

    # 同一 runner 上构建任务开始前的 FP16 基线，获得可比 APK 大小。
    baseline_size = None
    baseline_dir = Path("/tmp/roomxxx-yolo26-fp16-baseline")
    if baseline_dir.exists():
        shutil.rmtree(baseline_dir)
    try:
        run(["git", "worktree", "add", "--detach", str(baseline_dir), BASE_COMMIT])
        run(["bash", "gradlew", ":app:assembleDebug", "--stacktrace"], cwd=baseline_dir)
        baseline_apk = baseline_dir / "app/build/outputs/apk/debug/app-debug.apk"
        baseline_size = baseline_apk.stat().st_size
    finally:
        run(["git", "worktree", "remove", "--force", str(baseline_dir)], check=False)

    return new_size, baseline_size, abis


def prepend_codex(report: dict, apk_size: int, baseline_size: int | None) -> None:
    text = read(CODEX_HISTORY)
    marker = "# Codex History\n\n"
    if not text.startswith(marker):
        raise RuntimeError("Unexpected codexHistory header")
    det = report[DETECT_MODEL.name]
    pose = report[POSE_MODEL.name]
    base_text = str(baseline_size) if baseline_size is not None else "未取得"
    delta = apk_size - baseline_size if baseline_size is not None else None
    delta_text = f"{delta:+d}" if delta is not None else "未知"
    entry = f'''## [394] 2026-09-15 21:42:00 - YOLO26 迁移到官方 LiteRT w8a32\n\n**用户指令**：\n> 将当前 YOLO26 legacy FP16 Android 部署升级为当前官方 LiteRT/w8a32 长期方案；无需 A/B，直接实施、验证并推送。\n\n**实现方案 (Implementation)**：\n\n*   **变更摘要**\n    *   任务目的：将 YOLO26s / YOLO26s-pose 从 Ultralytics 8.4.82 legacy FP16 TFLite + Interpreter/GpuDelegate 迁移到当前官方 w8a32 LiteRT 资产 + LiteRT 2.x CompiledModel，保持现有业务后处理行为。\n    *   官方模型：Ultralytics `yolo-flutter-app` v0.6.6 标准 Android 资产 `yolo26s_w8a32.tflite`（SHA256 `{DETECT_SHA256}`）和 `yolo26s-pose_w8a32.tflite`（SHA256 `{POSE_SHA256}`）；当前 Ultralytics v8.4.152 的等价导出契约为 `format=\"litert\", quantize=\"w8a32\", imgsz=640`。\n    *   Tensor 实测：Detection `{det['input_shape']} FLOAT32 -> {det['output_shape']} FLOAT32`；Pose `{pose['input_shape']} FLOAT32 -> {pose['output_shape']} FLOAT32`；均实际执行零输入推理通过。\n    *   Runtime：`com.google.ai.edge.litert:litert:2.1.5` `CompiledModel`，GPU 整图编译并完成 warmup 后才视为 GPU 成功，否则回退 CPU/XNNPACK 4 threads；按实际 NCHW/NHWC 输入布局打包 RGB。\n    *   解析兼容：Detection 继续只读取 person(class 0)、0.30 阈值、0.45 外部 NMS；Pose 继续 0.15 候选阈值、0.5 NMS、现有 Tracker/ROI，新增 raw head 的 features-first/anchors-first 与归一化/模型像素坐标兼容。\n    *   修改文件：`LiteRtYoloRunner.kt`、`YoloAnalyzer.kt`、`YoloPoseAnalyzer.kt`、`app/build.gradle.kts`、`gradle/libs.versions.toml`、两套 w8a32 模型、`codexHistory.md`、`dialogueHistory.md`；删除两套 legacy FP16 模型。\n    *   验证：`:app:testDebugUnitTest` 通过，`:app:assembleDebug` 通过；APK 仅含两套 w8a32 YOLO26 模型，未含旧 FP16 模型，native ABI 仅 arm64-v8a。\n    *   APK 大小：FP16 基线 `{base_text}` bytes；LiteRT w8a32 `{apk_size}` bytes；变化 `{delta_text}` bytes。\n    *   设备安装：GitHub hosted runner 无 adb 设备，不伪称完成真机安装。\n\n---\n\n'''
    write(CODEX_HISTORY, marker + entry + text[len(marker):])


def archive_dialogue() -> None:
    write(USER_TMP, USER_TEXT + "\n")
    write(ASSISTANT_TMP, ASSISTANT_TEXT + "\n")
    run([
        sys.executable,
        "tools/dialogue_archive.py",
        "append-turn",
        "--user-file",
        str(USER_TMP.relative_to(ROOT)),
        "--assistant-file",
        str(ASSISTANT_TMP.relative_to(ROOT)),
        "--title",
        "确认迁移 YOLO26 到 LiteRT w8a32",
        "--time",
        "2026-09-15 21:42:00",
    ])
    # 这两个临时文件在仓库中本来已跟踪，归档后恢复基线内容，禁止把临时正文带入提交。
    run(["git", "checkout", "--", str(USER_TMP.relative_to(ROOT)), str(ASSISTANT_TMP.relative_to(ROOT))])


def validate_source_cleanliness() -> None:
    source = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in (ROOT / "app/src/main/java").rglob("*.kt"))
    forbidden = [
        "org.tensorflow.lite.Interpreter",
        "org.tensorflow.lite.gpu.GpuDelegate",
        "org.tensorflow.lite.support.image.TensorImage",
    ]
    remaining = [token for token in forbidden if token in source]
    if remaining:
        raise RuntimeError(f"Legacy TFLite API remains in app sources: {remaining}")


def finalize_commit(report: dict, apk_size: int, baseline_size: int | None, abis: list[str]) -> None:
    # 临时 CI/报告不进入最终提交。
    for path in (WORKFLOW, SELF, REPORT):
        if path.exists():
            path.unlink()

    status = run(["git", "status", "--short"], capture=True).stdout
    print(status, flush=True)
    if "local.properties" in status or ".DS_Store" in status:
        raise RuntimeError("Unwanted local files in final diff")

    expected_paths = {
        "app/build.gradle.kts",
        "gradle/libs.versions.toml",
        "app/src/main/java/com/example/roomxxx0102/logic/analyzer/LiteRtYoloRunner.kt",
        "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloAnalyzer.kt",
        "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloPoseAnalyzer.kt",
        "app/src/main/assets/yolo26s_w8a32.tflite",
        "app/src/main/assets/yolo26s-pose_w8a32.tflite",
        "app/src/main/assets/yolo26s_float16.tflite",
        "app/src/main/assets/yolo26s_pose_float16.tflite",
        "codexHistory.md",
        "dialogueHistory.md",
        ".github/workflows/yolo26-litert.yml",
        "tools/_yolo26_litert_finalize.py",
    }
    changed = set(run(["git", "status", "--short"], capture=True).stdout.splitlines())
    changed_paths = {line[3:] for line in changed if len(line) >= 4}
    unexpected = changed_paths - expected_paths
    if unexpected:
        raise RuntimeError(f"Unexpected final changes: {sorted(unexpected)}")

    run(["git", "config", "user.name", "github-actions[bot]"])
    run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"])
    run(["git", "add", "-A"])
    run(["git", "diff", "--cached", "--check"])
    staged = run(["git", "diff", "--cached", "--name-only"], capture=True).stdout.splitlines()
    print("Staged files:\n" + "\n".join(staged), flush=True)
    required = {
        "app/src/main/assets/yolo26s_w8a32.tflite",
        "app/src/main/assets/yolo26s-pose_w8a32.tflite",
        "app/src/main/java/com/example/roomxxx0102/logic/analyzer/LiteRtYoloRunner.kt",
        "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloAnalyzer.kt",
        "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloPoseAnalyzer.kt",
        "codexHistory.md",
        "dialogueHistory.md",
    }
    if not required.issubset(set(staged)):
        raise RuntimeError(f"Missing required staged files: {sorted(required - set(staged))}")

    summary = {
        "ultralytics_contract_version": "8.4.152",
        "official_asset_release": "yolo-flutter-app v0.6.6",
        "litert_android": "2.1.5",
        "models": report,
        "apk_size": apk_size,
        "baseline_apk_size": baseline_size,
        "apk_delta": apk_size - baseline_size if baseline_size is not None else None,
        "abis": abis,
    }
    print("FINAL_REPORT=" + json.dumps(summary, ensure_ascii=False, separators=(",", ":")), flush=True)

    run(["git", "commit", "-m", "feat: migrate YOLO26 Android runtime to LiteRT w8a32"])
    run(["git", "push", "origin", f"HEAD:{TMP_BRANCH}"])


def main() -> int:
    run(["git", "status", "--short"])
    download_verified(DETECT_URL, DETECT_MODEL, DETECT_SHA256, DETECT_SIZE)
    download_verified(POSE_URL, POSE_MODEL, POSE_SHA256, POSE_SIZE)
    OLD_DETECT.unlink(missing_ok=True)
    OLD_POSE.unlink(missing_ok=True)

    write(RUNNER, runner_source())
    patch_yolo_analyzer()
    patch_pose_analyzer()
    patch_dependencies()
    validate_source_cleanliness()

    report = inspect_models()
    apk_size, baseline_size, abis = build_and_verify()
    prepend_codex(report, apk_size, baseline_size)
    archive_dialogue()
    finalize_commit(report, apk_size, baseline_size, abis)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
