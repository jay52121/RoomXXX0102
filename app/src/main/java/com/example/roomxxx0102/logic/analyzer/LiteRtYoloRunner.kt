package com.example.roomxxx0102.logic.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType

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
        val prepared = try {
            prepare(context, Accelerator.GPU, "GPU")
        } catch (gpuError: Throwable) {
            Log.w(
                TAG,
                "model=$modelAssetName architecture=$architecture quantization=w8a32 gpu=false fallback=CPU reason=${gpuError.message}",
                gpuError,
            )
            prepare(context, Accelerator.CPU, "CPU/XNNPACK")
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

    private fun prepare(context: Context, accelerator: Accelerator, backendName: String): Prepared {
        val options = CompiledModel.Options(accelerator)
        if (accelerator == Accelerator.CPU) {
            options.cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
        }
        val compiled = CompiledModel.create(context.assets, modelAssetName, options, null)
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

    companion object {
        private const val TAG = "LiteRtYoloRunner"
    }
}
