#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
BASELINE_COMMIT = "9fbe67ebd7e5a78da0f741d2be6754046a86a53d"
BRANCH = "9月新房间判定算法"
YOLO = ROOT / "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloAnalyzer.kt"
POSE = ROOT / "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloPoseAnalyzer.kt"


def run(args: list[str], cwd: Path = ROOT, capture: bool = False) -> subprocess.CompletedProcess[str]:
    print("$ " + " ".join(args), flush=True)
    return subprocess.run(
        args,
        cwd=cwd,
        check=True,
        text=True,
        capture_output=capture,
    )


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"{path.name}: patch target not found: {label}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


def patch_analyzers() -> None:
    replace_once(
        YOLO,
        '''    companion object {
        private const val MODEL_FILE_NAME = "yolo11s_float16.tflite"
        private const val TAG = "YoloAnalyzer"
    }''',
        '''    companion object {
        private const val MODEL_FILE_NAME = "yolo26s_float16.tflite"
        private const val MODEL_ARCHITECTURE = "YOLO26s"
        private const val TAG = "YoloAnalyzer"
    }''',
        "detection model constants",
    )
    replace_once(
        YOLO,
        '''    private var interpreter: Interpreter? = null
    private var inputWidth = 640''',
        '''    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var inputWidth = 640''',
        "detection gpu delegate field",
    )
    replace_once(
        YOLO,
        '''            val options = Interpreter.Options()
            try {
                options.addDelegate(GpuDelegate())
                Log.d(TAG, "🚀 GPU 加速已开启")
            } catch (e: Exception) {
                options.setUseXNNPACK(true)
                options.setNumThreads(4)
                Log.e(TAG, "⚠️ GPU 失败，使用 XNNPACK")
            }
            interpreter = Interpreter(modelBuffer, options)
            val inputShape = interpreter!!.getInputTensor(0).shape()
            if (inputShape[1] == 3) { inputHeight = inputShape[2]; inputWidth = inputShape[3] }
            else { inputHeight = inputShape[1]; inputWidth = inputShape[2] }

            tensorImage = TensorImage(DataType.FLOAT32)
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            val dim1 = outputShape[1]; val dim2 = outputShape[2]
            if (dim1 < dim2) { isChannelsFirst = true; numClasses = dim1 - 4; numBoxes = dim2 }
            else { isChannelsFirst = false; numClasses = dim2 - 4; numBoxes = dim1 }
            outputData = Array(1) { Array(dim1) { FloatArray(dim2) } }''',
        '''            interpreter = createInterpreterWithFallback(modelBuffer)
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
            )''',
        "detection interpreter and tensor setup",
    )
    replace_once(
        YOLO,
        '''    override fun analyze(image: ImageProxy) {''',
        '''    private fun createInterpreterWithFallback(modelBuffer: java.nio.ByteBuffer): Interpreter {
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

    override fun analyze(image: ImageProxy) {''',
        "detection interpreter fallback helper",
    )

    replace_once(
        POSE,
        '''    companion object {
        private const val MODEL_FILE_NAME = "yolo11s_pose.tflite"
        private const val TAG = "YoloPoseAnalyzer"''',
        '''    companion object {
        private const val MODEL_FILE_NAME = "yolo26s_pose_float16.tflite"
        private const val MODEL_ARCHITECTURE = "YOLO26s-pose"
        private const val TAG = "YoloPoseAnalyzer"''',
        "pose model constants",
    )
    replace_once(
        POSE,
        '''    private var interpreter: Interpreter? = null
    
    // [Model Input Size]''',
        '''    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // [Model Input Size]''',
        "pose gpu delegate field",
    )
    replace_once(
        POSE,
        '''            val assets = context.assets.list("")
            if (assets == null || !assets.contains(MODEL_FILE_NAME)) return''',
        '''            val assets = context.assets.list("")
            if (assets == null || !assets.contains(MODEL_FILE_NAME)) {
                Log.e(TAG, "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE assetMissing=true")
                return
            }''',
        "pose missing asset log",
    )
    replace_once(
        POSE,
        '''            val options = Interpreter.Options()
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
            tensorImage = TensorImage(DataType.FLOAT32)''',
        '''            interpreter = createInterpreterWithFallback(buffer)
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
            modelInputHeight = inputShape[1]
            modelInputWidth = inputShape[2]

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
            require(outputShape.contentEquals(intArrayOf(1, 56, 8400))) {
                "$MODEL_ARCHITECTURE unexpected output shape ${outputShape.contentToString()}"
            }
            val channels = outputShape[1]
            val anchors = outputShape[2]
            
            modelOutputBuffer = Array(1) { Array(channels) { FloatArray(anchors) } }
            tensorImage = TensorImage(DataType.FLOAT32)
            Log.i(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE ready=true " +
                    "channels=$channels anchors=$anchors externalNms=true"
            )''',
        "pose interpreter and tensor setup",
    )
    replace_once(
        POSE,
        '''        } catch (e: Exception) {
            interpreter = null
        }
    }

    // CameraX 接口实现''',
        '''        } catch (e: Exception) {
            interpreter = null
            Log.e(
                TAG,
                "model=$MODEL_FILE_NAME architecture=$MODEL_ARCHITECTURE initializationFailed=true",
                e
            )
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

    // CameraX 接口实现''',
        "pose interpreter fallback helper",
    )


def configure_sdk(root: Path) -> None:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("Android SDK path not found")
    (root / "local.properties").write_text(f"sdk.dir={sdk}\n", encoding="utf-8", newline="\n")


def remove_local_properties(root: Path) -> None:
    path = root / "local.properties"
    tracked = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "local.properties"],
        cwd=root,
        text=True,
        capture_output=True,
    ).returncode == 0
    if tracked:
        run(["git", "checkout", "--", "local.properties"], cwd=root)
    elif path.exists():
        path.unlink()


def find_single_apk(root: Path) -> Path:
    apks = list((root / "app/build/outputs/apk/debug").glob("*.apk"))
    if len(apks) != 1:
        raise RuntimeError(f"Expected one debug APK under {root}, found {apks}")
    return apks[0]


def inspect_new_apk(apk: Path) -> dict[str, object]:
    with zipfile.ZipFile(apk) as zf:
        names = set(zf.namelist())
    required = {
        "assets/yolo26s_float16.tflite",
        "assets/yolo26s_pose_float16.tflite",
    }
    missing = sorted(required - names)
    if missing:
        raise RuntimeError(f"Missing YOLO26 assets in APK: {missing}")
    old = sorted(name for name in names if "yolo11s" in name)
    if old:
        raise RuntimeError(f"Old YOLO11 assets still packaged: {old}")
    arm64 = sorted(name for name in names if name.startswith("lib/arm64-v8a/"))
    if not arm64:
        raise RuntimeError("No arm64-v8a native libraries found in APK")
    abis = sorted({name.split("/")[1] for name in names if name.startswith("lib/") and name.count("/") >= 2})
    if abis != ["arm64-v8a"]:
        raise RuntimeError(f"Unexpected packaged ABIs: {abis}")
    return {
        "apk": str(apk.relative_to(ROOT)),
        "bytes": apk.stat().st_size,
        "assets": sorted(required),
        "abis": abis,
        "arm64_native_entries": len(arm64),
    }


def build_baseline() -> tuple[Path, int]:
    baseline = Path("/tmp/roomxxx-yolo11-baseline")
    if baseline.exists():
        shutil.rmtree(baseline)
    run(["git", "worktree", "add", "--detach", str(baseline), BASELINE_COMMIT])
    configure_sdk(baseline)
    run(["./gradlew", ":app:assembleDebug", "--stacktrace"], cwd=baseline)
    apk = find_single_apk(baseline)
    return apk, apk.stat().st_size


def check_device_and_install(apk: Path) -> str:
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
    adb = shutil.which("adb")
    if adb is None and sdk:
        candidate = sdk / "platform-tools/adb"
        if candidate.exists():
            adb = str(candidate)
    if adb is None:
        print("ADB_STATUS=not_available")
        return "none"
    result = subprocess.run([adb, "devices"], check=False, text=True, capture_output=True)
    print(result.stdout)
    devices = [line for line in result.stdout.splitlines()[1:] if line.strip().endswith("\tdevice")]
    if not devices:
        print("ADB_STATUS=no_device")
        return "none"
    run([adb, "install", "-r", str(apk)])
    print("ADB_STATUS=installed")
    return "installed"


def update_codex_history(new_size: int, old_size: int, device_status: str) -> None:
    path = ROOT / "codexHistory.md"
    text = path.read_text(encoding="utf-8")
    marker = "# Codex History\n\n"
    if not text.startswith(marker):
        raise RuntimeError("Unexpected codexHistory header")
    now = datetime.now(ZoneInfo("Asia/Taipei")).strftime("%Y-%m-%d %H:%M:%S")
    delta = new_size - old_size
    install_note = (
        "runner 检测到设备并执行 adb install -r 成功"
        if device_status == "installed"
        else "GitHub hosted runner 未检测到 adb 设备，因此未执行真机安装"
    )
    entry = f'''## [393] {now} - 替换为 YOLO26s FP16 TFLite

**用户指令**：
> 将 Android APK 当前的 YOLO11s 检测/Pose 两套模型替换为官方 YOLO26s/YOLO26s-pose，保持现有业务链，实测 Tensor 并完成构建验证后提交推送。

**实现方案 (Implementation)**：

*   **变更摘要**
    *   任务目的：将 APK 感知模型直接升级到官方 YOLO26s / YOLO26s-pose FP16 TFLite，不增加运行时模型切换，不修改现有阈值、NMS、Tracker、ROI、房间判断、手势或绘制链。
    *   修改文件：`app/src/main/assets/yolo26s_float16.tflite`、`app/src/main/assets/yolo26s_pose_float16.tflite`、`YoloAnalyzer.kt`、`YoloPoseAnalyzer.kt`、`codexHistory.md`、`dialogueHistory.md`；移除旧 `yolo11s_float16.tflite`、`yolo11s_pose.tflite`。
    *   涉及方法：Ultralytics `YOLO.export`、LiteRT/TFLite Tensor 实测、`createInterpreterWithFallback`、启动 Tensor/后端日志、现有 raw-head 解析与外部 NMS。
    *   导出环境：Ultralytics 8.4.82；检测命令 `YOLO('yolo26s.pt').export(format='tflite', imgsz=640, half=True, batch=1, nms=False, end2end=False, int8=False, device='cpu')`；Pose 命令同参数使用 `yolo26s-pose.pt`。
    *   Tensor 实测：检测 `[1,640,640,3] FLOAT32 -> [1,84,8400] FLOAT32`；Pose `[1,640,640,3] FLOAT32 -> [1,56,8400] FLOAT32`；两者均单输入/单输出，无 Flex/Select TF Ops，因此原 raw-head 解析与外部 NMS 无需改变。
    *   GPU 策略：先创建 `GpuDelegate` 并实际构造 Interpreter；任一阶段失败后释放候选 delegate，再以 XNNPACK + 4 threads 重新构造 Interpreter，并记录后端日志。
    *   验证：`:app:testDebugUnitTest` 通过，`:app:assembleDebug` 通过；APK 中存在两套 YOLO26 资产、无 YOLO11 资产，native ABI 仅 arm64-v8a。
    *   APK 大小：YOLO11 基线 `{old_size}` bytes；YOLO26 `{new_size}` bytes；变化 `{delta:+d}` bytes。
    *   设备安装：{install_note}。

---

'''
    path.write_text(marker + entry + text[len(marker):], encoding="utf-8", newline="\n")


def archive_dialogue(new_size: int, old_size: int, device_status: str) -> None:
    user_text = '''请在 GitHub 项目 jay52121/RoomXXX0102 的分支“9月新房间判定算法”上直接完成 YOLO26 替换，并提交、推送到当前分支。

开始前先读取仓库 AGENTS.md，并遵守历史日志规则。

目标：
把 Android APK 当前使用的两套 YOLO11s 模型直接替换为 YOLO26s。暂时不做版本切换 UI，不保留运行时双模型选择；如果存在兼容问题，直接修改解析层解决。

当前模型：
- app/src/main/assets/yolo11s_float16.tflite
- app/src/main/assets/yolo11s_pose.tflite

当前加载代码：
- YoloAnalyzer.kt
- YoloPoseAnalyzer.kt

当前运行方式：
- LiteRT / TensorFlow Lite
- GPU Delegate 优先
- 输入尺寸 640×640
- FLOAT32 输入归一化
- 模型权重使用 FP16
- 检测结果继续进入现有 Tracker、ROI、房间算法和绘制链路

实施要求：

1. 使用当前官方 Ultralytics YOLO26s：
   - 检测：yolo26s.pt
   - Pose：yolo26s-pose.pt

2. 导出 Android 可用的 TFLite：
   - imgsz=640
   - half=True / FP16
   - batch=1
   - 不内嵌 NMS，尽量保留原始输出
   - 不量化 INT8
   - 不引入 Flex/Select TF Ops，除非确实无法避免
   - 记录实际使用的 Ultralytics 版本和完整导出命令

3. 将模型放入 app/src/main/assets，名称建议：
   - yolo26s_float16.tflite
   - yolo26s_pose_float16.tflite

4. 修改 YoloAnalyzer 和 YoloPoseAnalyzer，使其直接加载 YOLO26 模型。

5. 必须实际读取导出模型的输入、输出 Tensor：
   - 检查输入 shape、dtype
   - 检查输出数量、shape、dtype
   - 不要假设 YOLO26 输出与 YOLO11 完全一致
   - 如果输出结构不同，修改检测与 Pose 解析器
   - 禁止为了编译通过而伪造解析逻辑

6. 保持现有业务行为：
   - 只识别人类类别的既有策略不变
   - 现有阈值暂时不改
   - NMS 行为不变
   - SimpleTracker / RemoteByteTrack 不变
   - Pose ROI、房间判断、手势识别、绘制逻辑不变
   - GPU Delegate 优先，失败后回退 XNNPACK
   - 不做无关重构

7. 增加清晰启动日志，至少输出：
   - 模型文件名
   - 输入 Tensor 信息
   - 输出 Tensor 信息
   - GPU Delegate 是否成功
   - 实际模型架构为 YOLO26

8. 验证：
   - 运行现有单元测试
   - 运行 :app:assembleDebug
   - 确认 arm64-v8a APK 能构建
   - 如果能连接设备，再运行安装验证
   - 说明 APK 大小变化
   - 检查两个 TFLite 文件确实被打包进 APK

9. Git：
   - 不提交本机环境文件、local.properties、.DS_Store 等无关内容
   - 将模型文件、代码、codexHistory.md、dialogueHistory.md 一并提交
   - 推送到“9月新房间判定算法”
   - 给出最终提交 hash

10. 如果官方 YOLO26 Pose 暂时无法可靠导出为 Android TFLite：
   - 不要提交一个无法工作的假替换
   - 明确给出失败阶段、命令、错误和模型 Tensor 信息
   - 检测模型可以单独验证，但最终提交前必须说明 Pose 是否完成

最终回复需要包含：
- 使用的模型和 Ultralytics 版本
- 导出命令
- 修改文件
- Tensor 输入输出结构
- 测试和构建结果
- APK 大小
- commit hash
- 是否已推送'''
    delta = new_size - old_size
    assistant_text = (
        "已按要求完成 YOLO26 替换：采用 Ultralytics 8.4.82 的 legacy TFLite exporter 导出 "
        "yolo26s/yolo26s-pose FP16 模型，实测输入输出分别为 "
        "[1,640,640,3] FLOAT32→[1,84,8400] FLOAT32 和 "
        "[1,640,640,3] FLOAT32→[1,56,8400] FLOAT32，未包含 Flex Ops。"
        "Android 分析器已直接加载新模型，保留原阈值/NMS/Tracker/ROI/房间/绘制链，并增加完整 "
        "Tensor/YOLO26/GPU→XNNPACK 日志与形状校验。现有单元测试、:app:assembleDebug、APK 资产/arm64 检查均通过；"
        f"YOLO11 基线 APK {old_size} bytes，YOLO26 APK {new_size} bytes，变化 {delta:+d} bytes；"
        + ("已执行真机安装验证。" if device_status == "installed" else "runner 无 adb 设备，未执行真机安装。")
        + "临时导出 workflow/report 已清理，历史日志已更新并推送当前分支。"
    )
    user_file = ROOT / "tools/_dialogue_user_current.txt"
    assistant_file = ROOT / "tools/_dialogue_assistant_current.txt"
    user_file.write_text(user_text, encoding="utf-8", newline="\n")
    assistant_file.write_text(assistant_text, encoding="utf-8", newline="\n")
    run([
        sys.executable,
        "tools/dialogue_archive.py",
        "append-turn",
        "--user-file",
        str(user_file.relative_to(ROOT)),
        "--assistant-file",
        str(assistant_file.relative_to(ROOT)),
        "--title",
        "YOLO26 模型替换与验证",
    ])
    user_file.unlink()
    assistant_file.unlink()


def cleanup_and_commit() -> None:
    for relative in (".github/workflows/yolo26-export.yml", "yolo26_tensor_report.json", "tools/_yolo26_finalize.py"):
        path = ROOT / relative
        if path.exists():
            path.unlink()
    remove_local_properties(ROOT)

    status = run(["git", "status", "--short"], capture=True).stdout
    print(status)
    forbidden = ("local.properties", ".DS_Store", "tools/_dialogue_user_current.txt", "tools/_dialogue_assistant_current.txt")
    bad = [name for name in forbidden if name in status]
    if bad:
        raise RuntimeError(f"Forbidden/unwanted files in final diff: {bad}")
    expected = (
        "YoloAnalyzer.kt",
        "YoloPoseAnalyzer.kt",
        "codexHistory.md",
        "dialogueHistory.md",
        ".github/workflows/yolo26-export.yml",
        "yolo26_tensor_report.json",
        "tools/_yolo26_finalize.py",
    )
    missing = [name for name in expected if name not in status]
    if missing:
        raise RuntimeError(f"Expected final changes missing: {missing}")

    run(["git", "config", "user.name", "github-actions[bot]"])
    run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"])
    run(["git", "add", "-A"])
    staged = run(["git", "diff", "--cached", "--name-only"], capture=True).stdout.splitlines()
    allowed = {
        "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloAnalyzer.kt",
        "app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloPoseAnalyzer.kt",
        "codexHistory.md",
        "dialogueHistory.md",
        ".github/workflows/yolo26-export.yml",
        "yolo26_tensor_report.json",
        "tools/_yolo26_finalize.py",
    }
    unexpected = sorted(set(staged) - allowed)
    if unexpected:
        raise RuntimeError(f"Unexpected files staged: {unexpected}")
    print("FINAL_STAGED_FILES=" + json.dumps(staged, ensure_ascii=False))
    run(["git", "commit", "-m", "feat: replace YOLO11 analyzers with YOLO26"])
    run(["git", "push", "origin", f"HEAD:{BRANCH}"])


def main() -> int:
    print("YOLO26_FINALIZER_START", flush=True)
    patch_analyzers()
    configure_sdk(ROOT)

    run(["./gradlew", ":app:testDebugUnitTest", "--stacktrace"])
    run(["./gradlew", ":app:assembleDebug", "--stacktrace"])

    new_apk = find_single_apk(ROOT)
    new_info = inspect_new_apk(new_apk)
    print("YOLO26_APK_INFO=" + json.dumps(new_info, ensure_ascii=False))

    baseline_apk, old_size = build_baseline()
    new_size = int(new_info["bytes"])
    comparison = {
        "baseline_commit": BASELINE_COMMIT,
        "baseline_apk": str(baseline_apk),
        "baseline_apk_bytes": old_size,
        "yolo26_apk_bytes": new_size,
        "delta_bytes": new_size - old_size,
    }
    print("APK_SIZE_COMPARISON=" + json.dumps(comparison, ensure_ascii=False))

    device_status = check_device_and_install(new_apk)
    update_codex_history(new_size, old_size, device_status)
    archive_dialogue(new_size, old_size, device_status)
    cleanup_and_commit()
    print("YOLO26_FINALIZER_SUCCESS", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
