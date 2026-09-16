from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str, marker: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    if marker in text:
        return
    if old not in text:
        raise RuntimeError(f"anchor not found: {path}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


main = "app/src/main/java/com/example/roomxxx0102/ui/activities/MainActivity.kt"

replace_once(
    main,
    "import com.example.roomxxx0102.logic.validation.EventDiagnosticRecorder\n",
    "import com.example.roomxxx0102.logic.validation.EventDiagnosticRecorder\n"
    "import com.example.roomxxx0102.logic.validation.GitHubDiagnosticUploader\n",
    "import com.example.roomxxx0102.logic.validation.GitHubDiagnosticUploader",
)

replace_once(
    main,
    "        btnDiagnosticReplay?.setOnClickListener { startDiagnosticReplay() }\n",
    "        btnDiagnosticReplay?.setOnClickListener { startDiagnosticReplay() }\n"
    "        btnDiagnosticReplay?.setOnLongClickListener {\n"
    "            showDiagnosticGitHubTokenDialog(startAfterSave = false)\n"
    "            true\n"
    "        }\n",
    "showDiagnosticGitHubTokenDialog(startAfterSave = false)",
)

refresh_anchor = '''    private fun refreshDiagnosticReplayButton() {
        val button = btnDiagnosticReplay ?: return
        val show = isDiagnosticReplayActive || (isDebugPanelEnabled && isVideoMode && currentObserveMode == ObserveMode.PERSON)
        button.visibility = if (show) View.VISIBLE else View.GONE
        button.isEnabled = !isDiagnosticReplayActive
        button.alpha = if (isDiagnosticReplayActive) 0.65f else 1f
        button.text = if (isDiagnosticReplayActive) "诊断录制中" else "诊断回放"
    }

'''
refresh_replacement = refresh_anchor + '''    private fun showDiagnosticGitHubTokenDialog(startAfterSave: Boolean) {
        val hasSavedToken = GitHubDiagnosticUploader.hasToken(applicationContext)
        val input = EditText(this).apply {
            hint = if (hasSavedToken) "已保存 Token；输入新 Token 可替换" else "github_pat_..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("GitHub 诊断上传")
            .setMessage(
                "请输入 GitHub Fine-grained Token。只需要 jay52121/RoomXXX0102 的 Contents: Read and write。\\n\\n" +
                    "诊断 JSON 会上传到 diagnostics 分支的 v4a/日期/ 目录。Token 只保存在本机应用私有 noBackupFilesDir，不进入源码、日志或诊断 JSON。"
            )
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isBlank()) {
                    Toast.makeText(this, "Token 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runCatching { GitHubDiagnosticUploader.saveToken(applicationContext, token) }
                    .onSuccess {
                        Toast.makeText(this, "GitHub Token 已保存在本机", Toast.LENGTH_SHORT).show()
                        if (startAfterSave) startDiagnosticReplay()
                    }
                    .onFailure { error ->
                        Toast.makeText(this, "保存 Token 失败：${error.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("取消", null)
        if (hasSavedToken) {
            builder.setNeutralButton("清除 Token") { _, _ ->
                GitHubDiagnosticUploader.clearToken(applicationContext)
                Toast.makeText(this, "已清除 GitHub Token", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

'''
replace_once(
    main,
    refresh_anchor,
    refresh_replacement,
    "private fun showDiagnosticGitHubTokenDialog(startAfterSave: Boolean)",
)

start_anchor = '''        if (!isVideoMode) {
            Toast.makeText(this, "诊断回放仅支持回顾视频", Toast.LENGTH_SHORT).show()
            return
        }
        val rooms = RoomRepository.getAllRooms()
'''
start_replacement = '''        if (!isVideoMode) {
            Toast.makeText(this, "诊断回放仅支持回顾视频", Toast.LENGTH_SHORT).show()
            return
        }
        if (!GitHubDiagnosticUploader.hasToken(applicationContext)) {
            showDiagnosticGitHubTokenDialog(startAfterSave = true)
            return
        }
        val rooms = RoomRepository.getAllRooms()
'''
replace_once(
    main,
    start_anchor,
    start_replacement,
    "showDiagnosticGitHubTokenDialog(startAfterSave = true)",
)

finish_anchor = '''        fileResult.onSuccess { file ->
            if (file != null) {
                copyTextToClipboard("event_diagnostic_path", file.absolutePath)
                Toast.makeText(this, "诊断完成：${file.name}", Toast.LENGTH_LONG).show()
                showCenterBanner("诊断完成：${file.absolutePath}", CenterBannerDomain.ROOM, 10000L)
            }
        }.onFailure { error ->
'''
finish_replacement = '''        fileResult.onSuccess { file ->
            if (file != null) {
                copyTextToClipboard("event_diagnostic_path", file.absolutePath)
                Toast.makeText(this, "诊断完成，正在上传 GitHub：${file.name}", Toast.LENGTH_LONG).show()
                showCenterBanner("诊断完成，正在上传 GitHub…", CenterBannerDomain.ROOM, 10000L)
                GitHubDiagnosticUploader.uploadAsync(applicationContext, file) { upload ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (upload.success) {
                            copyTextToClipboard("event_diagnostic_github", "diagnostics:${upload.remotePath}")
                            Toast.makeText(this, "诊断已上传 GitHub", Toast.LENGTH_LONG).show()
                            showCenterBanner(
                                "诊断已上传 GitHub：diagnostics/${upload.remotePath}",
                                CenterBannerDomain.ROOM,
                                10000L
                            )
                        } else {
                            Toast.makeText(this, "GitHub 上传失败，本地 JSON 已保留", Toast.LENGTH_LONG).show()
                            showCenterBanner(
                                "GitHub 上传失败：${upload.message}；本地文件已保留",
                                CenterBannerDomain.ROOM,
                                10000L
                            )
                            Log.w("EventDiagnostic", "GitHub upload failed: ${upload.message}")
                        }
                    }
                }
            }
        }.onFailure { error ->
'''
replace_once(
    main,
    finish_anchor,
    finish_replacement,
    "GitHubDiagnosticUploader.uploadAsync(applicationContext, file)",
)

history = ROOT / "codexHistory.md"
text = history.read_text(encoding="utf-8")
marker = "## [409] 2026-09-17 03:05:00 - 诊断 JSON 自动上传 GitHub"
if marker not in text:
    entry = '''## [409] 2026-09-17 03:05:00 - 诊断 JSON 自动上传 GitHub

**用户指令**：
> 1.我希望这个录制完成之后，它自动传到 GitHub 上面，这样你读起来就很方便 2.我不需要在云端编译，我本地会去做编译（除非你觉得什么测试是必要 ）

**实现方案**：

* **自动上传**：新增 `GitHubDiagnosticUploader`，诊断录制完成并落本地 JSON 后，后台通过 GitHub Contents API 自动上传，不阻塞 UI；上传失败时保留本地 JSON，并明确提示失败原因。
* **独立数据分支**：新建 `diagnostics` 分支，上传路径固定为 `v4a/YYYY-MM-DD/V4A_事件诊断_*.json`，避免诊断数据污染 `9月新房间判定算法` 的代码提交历史，同时 ChatGPT 后续可以直接从 GitHub 读取最新诊断。
* **凭据安全**：GitHub Fine-grained Token 只保存在 Android 应用私有 `noBackupFilesDir`，不写入源码、SharedPreferences、日志或诊断 JSON，也不参与 Android 自动备份；首次点击“诊断回放”缺 Token 时自动弹出配置，长按该按钮可替换/清除 Token。
* **最小权限**：界面提示 Token 仅需 `jay52121/RoomXXX0102` 仓库的 `Contents: Read and write` 权限；仓库、分支和目录固定在代码中，减少误传目标。
* **构建策略调整**：本轮及以后默认不再使用 GitHub Actions 做 Android 云端编译；由用户本地编译。仅在确有必要验证纯逻辑或回归风险时才补必要测试。本轮未运行云端 Gradle 编译。

---
'''
    if not text.startswith("# Codex History\n\n"):
        raise RuntimeError("unexpected codexHistory header")
    text = "# Codex History\n\n" + entry + text[len("# Codex History\n\n"):]
    history.write_text(text, encoding="utf-8")

print("diagnostic auto upload patch applied")
