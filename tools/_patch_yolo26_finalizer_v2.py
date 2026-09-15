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
    "import java.io.FileInputStream\\nimport java.nio.ByteBuffer\\nimport java.nio.channels.FileChannel\\n",
    "",
    "runner stale imports",
)
once(
    '''        val mapped = mapAsset(context, modelAssetName)\n        val prepared = try {\n            prepare(mapped, Accelerator.GPU, "GPU")\n''',
    '''        val prepared = try {\n            prepare(context, Accelerator.GPU, "GPU")\n''',
    "runner GPU prepare",
)
once(
    '''            prepare(mapped, Accelerator.CPU, "CPU/XNNPACK")\n''',
    '''            prepare(context, Accelerator.CPU, "CPU/XNNPACK")\n''',
    "runner CPU prepare",
)
once(
    '''    private fun prepare(modelBuffer: ByteBuffer, accelerator: Accelerator, backendName: String): Prepared {\n''',
    '''    private fun prepare(context: Context, accelerator: Accelerator, backendName: String): Prepared {\n''',
    "prepare signature",
)
once(
    '''        val compiled = CompiledModel.create(modelBuffer.duplicate().apply { rewind() }, options)\n''',
    '''        val compiled = CompiledModel.create(context.assets, modelAssetName, options, null)\n''',
    "compiled model create",
)
once(
    '''    private fun mapAsset(context: Context, assetName: String): ByteBuffer {\n        val afd = context.assets.openFd(assetName)\n        FileInputStream(afd.fileDescriptor).use { stream ->\n            return stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)\n        }\n    }\n\n''',
    "",
    "remove mmap helper",
)

# CompiledModel assets must remain mmappable/uncompressed in the APK.
once(
    '''    write(APP_GRADLE, gradle)\n\n\ndef inspect_models() -> dict:\n''',
    '''    gradle = replace_once(\n        gradle,\n        """    buildFeatures {\n        compose = true\n        buildConfig = true\n        viewBinding = true // 🔥 开启 ViewBinding\n    }\n}\n""",\n        """    buildFeatures {\n        compose = true\n        buildConfig = true\n        viewBinding = true // 🔥 开启 ViewBinding\n    }\n    androidResources {\n        noCompress += \"tflite\"\n    }\n}\n""",\n        "LiteRT uncompressed assets",\n    )\n    write(APP_GRADLE, gradle)\n\n\ndef inspect_models() -> dict:\n''',
    "add noCompress patch",
)

# This patch helper is temporary too; the successful finalizer removes it before the real commit.
once(
    'SELF = Path(__file__).resolve()\nREPORT = ROOT / "yolo26_litert_report.json"\n',
    'SELF = Path(__file__).resolve()\nPATCH_V2 = ROOT / "tools/_patch_yolo26_finalizer_v2.py"\nREPORT = ROOT / "yolo26_litert_report.json"\n',
    "patch path constant",
)
once(
    '    for path in (WORKFLOW, SELF, REPORT):\n',
    '    for path in (WORKFLOW, SELF, PATCH_V2, REPORT):\n',
    "cleanup patch helper",
)
once(
    '        "tools/_yolo26_litert_finalize.py",\n',
    '        "tools/_yolo26_litert_finalize.py",\n        "tools/_patch_yolo26_finalizer_v2.py",\n',
    "allow patch helper deletion",
)

path.write_text(text, encoding="utf-8", newline="\n")
print("Patched temporary YOLO26 finalizer for LiteRT 2.2.0 public API")
