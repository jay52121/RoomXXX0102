#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).with_name("_yolo26_litert_finalize.py")
text = path.read_text(encoding="utf-8")


def once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, got {count}")
    text = text.replace(old, new, 1)


# LiteRT 2.2.0 is the current public Google sample line exposing createInputBuffers/createOutputBuffers.
once("'litert = \"1.4.1\"', 'litert = \"2.1.5\"'", "'litert = \"1.4.1\"', 'litert = \"2.2.0\"'", "version patch")
once("`com.google.ai.edge.litert:litert:2.1.5`", "`com.google.ai.edge.litert:litert:2.2.0`", "history runtime version")
once('"litert_android": "2.1.5"', '"litert_android": "2.2.0"', "report runtime version")

# CompiledModel public Android API takes AssetManager + asset name (or file path), not a ByteBuffer.
once(
    """import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
""",
    "",
    "runner stale imports",
)
once(
    """        val mapped = mapAsset(context, modelAssetName)
        val prepared = try {
            prepare(mapped, Accelerator.GPU, "GPU")
""",
    """        val prepared = try {
            prepare(context, Accelerator.GPU, "GPU")
""",
    "runner GPU prepare",
)
once(
    """            prepare(mapped, Accelerator.CPU, "CPU/XNNPACK")
""",
    """            prepare(context, Accelerator.CPU, "CPU/XNNPACK")
""",
    "runner CPU prepare",
)
once(
    """    private fun prepare(modelBuffer: ByteBuffer, accelerator: Accelerator, backendName: String): Prepared {
""",
    """    private fun prepare(context: Context, accelerator: Accelerator, backendName: String): Prepared {
""",
    "prepare signature",
)
once(
    """        val compiled = CompiledModel.create(modelBuffer.duplicate().apply { rewind() }, options)
""",
    """        val compiled = CompiledModel.create(context.assets, modelAssetName, options, null)
""",
    "compiled model create",
)
once(
    """    private fun mapAsset(context: Context, assetName: String): ByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { stream ->
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

""",
    "",
    "remove mmap helper",
)

# CompiledModel assets must remain mmappable/uncompressed in the APK.
once(
    """    write(APP_GRADLE, gradle)


def inspect_models() -> dict:
""",
    """    gradle = replace_once(
        gradle,
        \"\"\"    buildFeatures {\n        compose = true\n        buildConfig = true\n        viewBinding = true // 🔥 开启 ViewBinding\n    }\n}\n\"\"\",
        \"\"\"    buildFeatures {\n        compose = true\n        buildConfig = true\n        viewBinding = true // 🔥 开启 ViewBinding\n    }\n    androidResources {\n        noCompress += \\\"tflite\\\"\n    }\n}\n\"\"\",
        \"LiteRT uncompressed assets\",
    )
    write(APP_GRADLE, gradle)


def inspect_models() -> dict:
""",
    "add noCompress patch",
)

# This patch helper is temporary too; the successful finalizer removes it before the real commit.
once(
    """SELF = Path(__file__).resolve()
REPORT = ROOT / "yolo26_litert_report.json"
""",
    """SELF = Path(__file__).resolve()
PATCH_V2 = ROOT / "tools/_patch_yolo26_finalizer_v2.py"
REPORT = ROOT / "yolo26_litert_report.json"
""",
    "patch path constant",
)
once(
    """    for path in (WORKFLOW, SELF, REPORT):
""",
    """    for path in (WORKFLOW, SELF, PATCH_V2, REPORT):
""",
    "cleanup patch helper",
)
once(
    """        "tools/_yolo26_litert_finalize.py",
""",
    """        "tools/_yolo26_litert_finalize.py",
        "tools/_patch_yolo26_finalizer_v2.py",
""",
    "allow patch helper deletion",
)

path.write_text(text, encoding="utf-8", newline="\n")
print("Patched temporary YOLO26 finalizer for LiteRT 2.2.0 public API")
