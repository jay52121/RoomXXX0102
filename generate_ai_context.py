import os
import re
import datetime

# ================= 配置区域 =================
SRC_DIR = "./app/src/main/java"
OUTPUT_FILE = "AI_PROJECT_CONTEXT.md"

# 1. 核心数据文件 (VIP名单)：这些文件会保留所有属性，因为它们是"真理"
CORE_DATA_FILES = [
    "RoomConfig.kt",
    "EntranceSegment.kt",
    "PoseData.kt" # 如果你需要它的话
]

# 2. 完全忽略的名单 (噪音)
IGNORE_PATTERNS = [
    r"ExampleInstrumentedTest",
    r"UnitTest",
    r"BuildConfig",
    r"databinding",
    r"Resource",
    r"Theme",
    r"Color",
    r"Type"
]
# ===========================================

def generate_tree(startpath):
    """生成目录树，让 AI 知道文件在哪"""
    tree_str = "## 1. Project File Structure\n```text\n"
    for root, dirs, files in os.walk(startpath):
        level = root.replace(startpath, '').count(os.sep)
        indent = ' ' * 4 * (level)
        if level == 0:
            tree_str += f"{os.path.basename(root)}/\n"
        else:
            tree_str += f"{indent}{os.path.basename(root)}/\n"
        subindent = ' ' * 4 * (level + 1)
        for f in files:
            if f.endswith(".kt"):
                tree_str += f"{subindent}{f}\n"
    tree_str += "```\n\n"
    return tree_str

def extract_file_content(file_path):
    filename = os.path.basename(file_path)

    # 判断是否是 VIP 文件
    is_vip = filename in CORE_DATA_FILES

    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    result = f"### File: `{filename}`\n"

    # 提取 KDoc (类说明)
    file_kdoc_match = re.search(r"/\*\*(.*?)\*/", content, re.DOTALL)
    if file_kdoc_match:
        kdoc = file_kdoc_match.group(1).strip()
        clean_kdoc = "\n".join([line.strip().lstrip('*').strip() for line in kdoc.split('\n') if line.strip()])
        if len(clean_kdoc) > 0:
            result += f"> **Description**:\n{clean_kdoc}\n\n"

    result += "```kotlin\n"

    lines = content.split('\n')
    skip_mode = False

    for line in lines:
        raw_line = line
        line = line.strip()

        # 1. 基础清理
        if not line or line.startswith("package ") or line.startswith("import "):
            continue
        if line.startswith("//"): # 跳过单行注释
            continue

        # 2. 智能过滤逻辑

        # A. 类/对象定义 (总是保留)
        if re.search(r"\b(class|interface|object|data class|enum class)\b", line):
            if line.endswith("{"):
                result += f"{raw_line}\n"
            else:
                result += f"{raw_line} ...\n"
            continue

        # B. 变量 (val/var)
        if line.startswith("val ") or line.startswith("var ") or line.startswith("private val ") or line.startswith("private var "):
            # 如果是 VIP 文件，保留所有变量
            if is_vip:
                result += f"{raw_line}\n"
            # 如果是普通文件，只保留 public 变量，且跳过 private
            elif not line.startswith("private"):
                # 只保留定义部分，去掉 "= ..." 具体的实现值，节省 token
                if "=" in line:
                    simple_def = line.split("=")[0].strip()
                    result += f"    {simple_def}...\n"
                else:
                    result += f"{raw_line}\n"
            continue

        # C. 函数 (fun)
        if re.search(r"\bfun\b", line):
            # 总是跳过 private fun (除非它是 VIP，但通常不需要)
            if "private fun" in line:
                continue

            # 提取签名
            if "{" in line:
                sig = line.split('{')[0].strip()
            else:
                sig = line # 处理多行参数的情况可能需要更复杂的逻辑，这里简化处理

            result += f"    {sig} {{ ... }}\n"
            continue

    result += "```\n\n"
    return result

def main():
    print(f"🚀 Starting Optimized Scan in: {SRC_DIR}")
    full_content = f"# Android Project Context: RoomFlow (Optimized)\n"
    full_content += f"> Generated at: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
    full_content += f"> Strategy: Core Data (Full) | Logic (API Only) | Private Members (Hidden)\n\n"

    full_content += generate_tree(SRC_DIR)
    full_content += "## 2. Key Classes & Signatures\n\n"

    file_count = 0
    for root, dirs, files in os.walk(SRC_DIR):
        for file in files:
            if file.endswith(".kt"):
                if any(re.search(pattern, file) for pattern in IGNORE_PATTERNS):
                    continue

                path = os.path.join(root, file)
                full_content += extract_file_content(path)
                file_count += 1

    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        f.write(full_content)

    print(f"✅ Done! Scanned {file_count} files.")
    print(f"📄 Output saved to: {os.path.abspath(OUTPUT_FILE)}")

if __name__ == "__main__":
    main()