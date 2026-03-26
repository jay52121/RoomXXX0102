项目规则

GeminiHistory 已读编号：026（读取新条目后需更新）

[//]: # (1&#41; 每次改代码前，必须用 UTF-8 读取 GeminiHistory.md，仅读取未读编号的新内容；AINoRead 段跳过。)
2) 每次文件级改动后输出任务简报，格式固定为：任务简报：任务目的=...；修改文件=...；涉及方法=...
3) 维护 codexHistory.md：仅当指令导致代码改动时追加；采用与 GeminiHistory.md 相同格式；“用户指令”为原始需求概述，“实现方案”写修改摘要；全部中文、UTF-8。
3.1) codexHistory.md 必须按倒叙维护（最新在最前）；新增条目插入到最前面的最新位置，禁止追加到文件末尾。
4) 所有思考与输出必须中文。
5) 改代码前先给出简要方案并等待确认。
6) 禁止用 shell 重定向写日志；只用编辑文件方式（apply_patch 或脚本写入 UTF-8 文件）。
7) 添加调试日志无需另行确认。
8) 修改代码时优先使用“精确编辑/替换”（Edit/replace）而不是整文件重写（write_file）。
读取大文件时必须用 read_file 的 offset/limit 只读取相关行；禁止为了改几行把整文件读完。
如果必须使用 write_file：必须保持除目标改动之外的内容逐字不变，并在回复里说明改动位置（文件+函数+行号范围）。
9) 维护对话原文归档：使用 `tools/dialogue_archive.py` 追加到 `dialogueHistory.md`，必须原文直存，不得改写。
10) 对话归档调用方法（UTF-8）：
`python tools/dialogue_archive.py append-turn --user-file <user.txt> --assistant-file <assistant.txt> --title "<标题>" [--time "YYYY-MM-DD HH:MM:SS"] [--history-file dialogueHistory.md]`
10.1) 对话归档临时文件固定为循环复用：
`tools/_dialogue_user_current.txt`
`tools/_dialogue_assistant_current.txt`
每次归档前直接覆盖写入 UTF-8 内容；归档后不再按编号生成/删除临时文件。
11) 每次开始处理新任务前，先读取 `dialogueHistory.md` 最新条目（只读必要范围），用于对齐最近沟通上下文。
