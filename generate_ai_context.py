import os
import re
import datetime

# ================= 配置区域 =================
# 扫描的源码路径 (根据你的实际包名路径修改，或者保持默认扫描整个 java 目录)
SRC_DIR = "./app/src/main/java"
# 输出的文件名
OUTPUT_FILE = "AI_PROJECT_CONTEXT.md"
# 忽略的目录或文件 (支持正则)
IGNORE_PATTERNS = [
    r"ExampleInstrumentedTest", 
    r"UnitTest", 
    r"BuildConfig", 
    r"databinding"
]
# ===========================================

def generate_tree(startpath):
    """生成文件目录树结构"""
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
    """提取单个文件的关键签名和注释"""
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    filename = os.path.basename(file_path)
    result = f"### File: `{filename}`\n"

    # 1. 提取文件顶部的 AI Context 注释 (如果有)
    # 匹配 /** [AI Role] ... */ 这种格式
    file_kdoc_match = re.search(r"/\*\*(.*?)\*/", content, re.DOTALL)
    if file_kdoc_match:
        kdoc = file_kdoc_match.group(1).strip()
        # 简单清洗一下 * 号
        clean_kdoc = "\n".join([line.strip().lstrip('*').strip() for line in kdoc.split('\n')])
        result += f"> **Description**:\n{clean_kdoc}\n\n"

    result += "```kotlin\n"

    lines = content.split('\n')
    for line in lines:
        line = line.strip()
        
        # 忽略 import 和 package
        if line.startswith("import ") or line.startswith("package "):
            continue
        
        # 忽略空行
        if not line:
            continue

        # 提取 Class / Interface / Object 定义
        if re.search(r"\b(class|interface|object|data class|enum class)\b", line):
            if not line.endswith("{"): 
                # 处理单行定义或不规范换行，尽量截取主要部分
                result += f"{line} ...\n"
            else:
                result += f"{line}\n"
            continue

        # 提取变量 (只提取 val/var，忽略 private 除非它是核心状态)
        # 这里为了给 AI 看，我们保留 public/protected 的属性
        if (line.startswith("val ") or line.startswith("var ") or 
            line.startswith("private val ") or line.startswith("private var ")):
            # 简化：如果是 private，且看起来像关键配置，才保留。
            # 为了全面，这里全部保留，但在 Markdown 里你可以选择性折叠。
            # 为了节省 Token，我们可以只保留 变量名和类型
            result += f"    {line}\n"
            continue

        # 提取方法签名 (忽略 private fun，除非你觉得重要)
        # 匹配 fun xxx(...): xxx
        if re.search(r"\bfun\b", line):
            # 去掉方法体的大括号
            sig = line.split('{')[0].strip()
            # 如果是 private fun，在开头加个标记或者直接跳过
            if "private fun" in sig:
                # 策略：跳过私有辅助方法，只看公有接口
                continue 
            result += f"    {sig}\n"
            continue

    result += "```\n\n"
    return result

def main():
    print(f"🚀 Starting scan in: {SRC_DIR}")
    full_content = f"# Android Project Context: RoomFlow\n"
    full_content += f"> Generated at: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n"
    
    # 1. 生成树状图
    full_content += generate_tree(SRC_DIR)

    # 2. 遍历所有 kt 文件提取详情
    full_content += "## 2. Key Classes & Signatures\n\n"
    
    file_count = 0
    for root, dirs, files in os.walk(SRC_DIR):
        for file in files:
            if file.endswith(".kt"):
                # 检查是否在忽略列表中
                if any(re.search(pattern, file) for pattern in IGNORE_PATTERNS):
                    continue
                
                path = os.path.join(root, file)
                full_content += extract_file_content(path)
                file_count += 1

    # 3. 写入文件
    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        f.write(full_content)
    
    print(f"✅ Done! Scanned {file_count} files.")
    print(f"📄 Output saved to: {os.path.abspath(OUTPUT_FILE)}")

if __name__ == "__main__":
    main()
