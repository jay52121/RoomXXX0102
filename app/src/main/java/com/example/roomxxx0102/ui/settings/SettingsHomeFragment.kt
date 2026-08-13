package com.example.roomxxx0102.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.roomxxx0102.R
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.BuildConfig
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.data.repository.VideoRoomConfigManager
import com.example.roomxxx0102.databinding.FragmentSettingsHomeBinding
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.roundToInt

/**
 * **设置主页 (Settings Home)**
 *
 * 包含调试开关和功能入口。
 */
class SettingsHomeFragment : Fragment() {

    private enum class SispCoreConnectionState {
        Disconnected,
        Connecting,
        Connected
    }

    private var _binding: FragmentSettingsHomeBinding? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null
    private val httpClient = OkHttpClient()
    private val binding get() = _binding!!
    private var presenceAlgoOptions: List<PresenceAlgorithmRegistry.AlgorithmOption> = emptyList()
    private var syncingPresenceSpinner = false
    private var isConfigListExpanded = false
    private var isVideoListExpanded = false
    private var isHandDetectionParamsExpanded = false
    private var isSispCoreExpanded = false
    private var isSispManualFormExpanded = false
    private var sispCoreConnectionState = SispCoreConnectionState.Disconnected
    private var sispConnectingHost = ""
    private var sispConnectingPort = ""
    private var sispConnectRunnable: Runnable? = null

    private fun createChoiceAdapter(labels: List<String>, selectedPositionProvider: () -> Int): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.text = "${labels.getOrNull(position).orEmpty()}  ▾"
                view.setTextColor(Color.parseColor("#2F6BFF"))
                view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                view.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                view.textSize = 13f
                view.includeFontPadding = false
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                val selected = position == selectedPositionProvider()
                view.text = labels.getOrNull(position).orEmpty()
                view.setTextColor(Color.parseColor(if (selected) "#2563FF" else "#263447"))
                view.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                view.setBackgroundColor(Color.parseColor(if (selected) "#EAF2FF" else "#FFFFFF"))
                view.setPadding(24, 18, 24, 18)
                return view
            }
        }.also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun buildPresenceOptionsInStableOrder(): List<PresenceAlgorithmRegistry.AlgorithmOption> {
        val options = mutableListOf(
            PresenceAlgorithmRegistry.AlgorithmOption(
                PresenceAlgorithmRegistry.AUTO_LATEST,
                "自动(最新)"
            )
        )
        PresenceAlgorithmRegistry.allVersionIds().forEach { versionId ->
            options.add(PresenceAlgorithmRegistry.AlgorithmOption(versionId, versionId))
        }
        return options
    }

    private fun resolvePresenceSelectionIndex(): Int {
        val currentVersion = AppSettings.presenceAlgorithmVersion
        return presenceAlgoOptions.indexOfFirst { it.id == currentVersion }
            .takeIf { it >= 0 } ?: 0
    }

    private fun syncPresenceSpinnerSelection() {
        if (_binding == null || presenceAlgoOptions.isEmpty()) return
        val selectedIndex = resolvePresenceSelectionIndex().coerceIn(0, (presenceAlgoOptions.size - 1).coerceAtLeast(0))
        syncingPresenceSpinner = true
        binding.spnPresenceAlgorithmVersion.setSelection(selectedIndex, false)
        syncingPresenceSpinner = false
    }


    private fun startTrackerStatusPolling() {
        stopTrackerStatusPolling()
        statusRunnable = object : Runnable {
            override fun run() {
                fetchTrackerStatus()
                statusHandler.postDelayed(this, 3000L)
            }
        }
        statusHandler.post(statusRunnable!!)
    }

    private fun stopTrackerStatusPolling() {
        statusRunnable?.let { statusHandler.removeCallbacks(it) }
        statusRunnable = null
    }

    private fun fetchTrackerStatus() {
        val request = Request.Builder()
            .url("http://192.168.50.161:8000/health")
            .get()
            .build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                val label = "ByteTrack 服务状态：不可用"
                statusHandler.post { _binding?.tvTrackerStatus?.text = label }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val ok = it.isSuccessful
                    val label = if (ok) "ByteTrack 服务状态：可用" else "ByteTrack 服务状态：不可用"
                    statusHandler.post { _binding?.tvTrackerStatus?.text = label }
                }
            }
        })
    }

    // --- SAF Launchers ---

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                val jsonString = RoomRepository.getBackupJson()
                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonString.toByteArray())
                }
                Toast.makeText(context, "导出成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val content = requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).readText()
                } ?: ""
                
                if (content.isNotEmpty()) {
                    val success = RoomRepository.restoreFromBackup(content)
                    if (success) {
                        Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "文件格式错误或无数据", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val selectVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null) {
            try {
                persistVideoUriPermission(uri, result.data?.flags ?: 0)
                loadSelectedVideo(uri.toString(), addToHistory = true)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "设置失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun persistVideoUriPermission(uri: Uri, resultFlags: Int) {
        val allowedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persistFlags = resultFlags and allowedFlags
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val finalFlags = if (persistFlags and readFlag != 0) persistFlags else readFlag
        requireContext().contentResolver.takePersistableUriPermission(uri, finalFlags)
    }

    private fun buildVideoPickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    private fun launchVideoPicker() {
        selectVideoLauncher.launch(buildVideoPickerIntent())
    }

    private fun loadSelectedVideo(uriString: String, addToHistory: Boolean) {
        AppSettings.setTestVideoUri(uriString)
        if (addToHistory) {
            AppSettings.pushTestVideoHistory(uriString)
        }
        loadDefaultConfigForSelectedVideo()
        refreshConfiguredVideoListContent()
        refreshVideoListContent()
    }

    private fun loadDefaultConfigForSelectedVideo() {
        val defaultFile = VideoRoomConfigManager.defaultConfigFileForCurrentVideo()
        if (defaultFile == null) {
            Toast.makeText(context, "请先选择测试视频", Toast.LENGTH_SHORT).show()
            refreshConfigListContent()
            return
        }
        val exists = defaultFile.exists()
        val message = if (exists) {
            RoomRepository.switchToConfigFile(
                file = defaultFile,
                persistSelection = true,
                createIfMissing = false
            )
            "已加载默认配置: ${defaultFile.nameWithoutExtension}"
        } else {
            RoomRepository.loadTemporaryEmptyConfig()
            AppSettings.setActiveRoomConfigPath(null)
            AppSettings.setNoRoomConfigSelected(true)
            "无房间配置文件"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        refreshConfigListContent()
    }

    private fun saveCurrentConfigAs(file: File) {
        val success = RoomRepository.saveAsConfigFile(file)
        if (success) {
            Toast.makeText(context, "已另存为配置: ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
            refreshConfigListContent()
        } else {
            Toast.makeText(context, "另存为失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmOverwriteAndSave(file: File) {
        AlertDialog.Builder(context)
            .setTitle("文件已存在")
            .setMessage("确定覆盖配置文件 ${file.nameWithoutExtension} 吗？")
            .setPositiveButton("覆盖") { _, _ -> saveCurrentConfigAs(file) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setConfigListExpanded(expanded: Boolean) {
        isConfigListExpanded = expanded
        binding.layoutConfigList.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.btnImport.text = if (expanded) "收起当前视频配置列表" else "读取当前视频配置"
        if (expanded) {
            refreshConfigListContent()
        }
    }

    private fun setVideoListExpanded(expanded: Boolean) {
        isVideoListExpanded = expanded
        binding.layoutVideoList.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.btnSelectVideo.text = if (expanded) "收起测试视频来源" else "选择测试视频"
        if (expanded) {
            refreshVideoListContent()
        }
    }

    private fun showConfigFileDetails(file: File, index: Int) {
        val currentMark = if (RoomRepository.currentConfigFile()?.canonicalPath == file.canonicalPath) "是" else "否"
        val lastModified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(java.util.Date(file.lastModified()))
        AlertDialog.Builder(context)
            .setTitle("配置详情")
            .setMessage(
                "序号：${index + 1}\n" +
                    "名称：${file.nameWithoutExtension}\n" +
                    "文件：${file.name}\n" +
                    "当前已加载：$currentMark\n" +
                    "修改时间：$lastModified"
            )
            .setPositiveButton("确定", null)
            .show()
    }

    private fun confirmLoadConfigFile(file: File) {
        AlertDialog.Builder(context)
            .setTitle("确认读取配置")
            .setMessage("确认读取配置 ${file.nameWithoutExtension} 吗？")
            .setPositiveButton("读取") { _, _ ->
                RoomRepository.switchToConfigFile(
                    file = file,
                    persistSelection = true,
                    createIfMissing = false
                )
                Toast.makeText(context, "已加载配置: ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
                refreshConfigListContent()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImportOtherVideoConfigDialog() {
        val currentContext = VideoRoomConfigManager.currentVideoContext()
        if (currentContext == null) {
            Toast.makeText(context, "请先选择测试视频", Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = VideoRoomConfigManager.listAllConfigFiles()
            .filterNot { VideoRoomConfigManager.isCurrentVideoConfigFile(it) }
        if (candidates.isEmpty()) {
            Toast.makeText(context, "未找到其他视频的配置文件", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.mapIndexed { index, file ->
            val sourceVideo = file.parentFile?.name ?: "未知视频"
            "${index + 1}. ${file.nameWithoutExtension}（来源：$sourceVideo）"
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("选择其他视频配置")
            .setItems(labels) { _, which ->
                candidates.getOrNull(which)?.let(::confirmImportOtherVideoConfig)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmImportOtherVideoConfig(sourceFile: File) {
        val targetFile = VideoRoomConfigManager.buildImportedConfigFileForCurrentVideo(sourceFile)
        if (targetFile == null) {
            Toast.makeText(context, "请先选择测试视频", Toast.LENGTH_SHORT).show()
            return
        }
        val sourceVideo = sourceFile.parentFile?.name ?: "未知视频"
        AlertDialog.Builder(context)
            .setTitle("确认载入其他视频配置")
            .setMessage(
                "将配置 ${sourceFile.nameWithoutExtension}\n" +
                    "（来源视频：$sourceVideo）\n\n" +
                    "复制为当前视频配置 ${targetFile.nameWithoutExtension} 并立即应用，是否继续？"
            )
            .setPositiveButton("载入") { _, _ ->
                importOtherVideoConfig(sourceFile, targetFile)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importOtherVideoConfig(sourceFile: File, targetFile: File) {
        try {
            targetFile.parentFile?.mkdirs()
            sourceFile.copyTo(targetFile, overwrite = false)
            RoomRepository.switchToConfigFile(
                file = targetFile,
                persistSelection = true,
                createIfMissing = false
            )
            Toast.makeText(
                context,
                "已载入其他视频配置: ${targetFile.nameWithoutExtension}",
                Toast.LENGTH_SHORT
            ).show()
            refreshConfigListContent()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "载入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildConfigRow(file: File, index: Int): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp
            }
        }

        val isCurrent = try {
            RoomRepository.currentConfigFile()?.canonicalPath == file.canonicalPath
        } catch (_: Exception) {
            false
        }

        val titleView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = buildString {
                append("${index + 1}. ${file.nameWithoutExtension}")
                if (isCurrent) append("  [当前]")
            }
            textSize = 13f
            setTextColor(if (isCurrent) 0xFF4CAF50.toInt() else 0xFF333333.toInt())
        }

        val detailButton = com.google.android.material.button.MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "查看"
            setOnClickListener { showConfigFileDetails(file, index) }
        }

        val loadButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "读取"
            setOnClickListener { confirmLoadConfigFile(file) }
        }

        row.addView(titleView)
        row.addView(detailButton)
        row.addView(loadButton)
        return row
    }

    private fun buildNoConfigRow(): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp
            }
        }

        val isCurrent = RoomRepository.currentConfigFile() == null

        val titleView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = buildString {
                append("0. 暂不配置")
                if (isCurrent) append("  [当前]")
            }
            textSize = 13f
            setTextColor(if (isCurrent) 0xFF4CAF50.toInt() else 0xFF333333.toInt())
        }

        val clearButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "选择"
            setOnClickListener {
                RoomRepository.loadTemporaryEmptyConfig()
                AppSettings.setActiveRoomConfigPath(null)
                AppSettings.setNoRoomConfigSelected(true)
                Toast.makeText(context, "已清除当前配置选择", Toast.LENGTH_SHORT).show()
                refreshConfigListContent()
            }
        }

        row.addView(titleView)
        row.addView(clearButton)
        return row
    }

    private fun refreshConfigListContent() {
        refreshConfiguredVideoListContent()
        if (!isConfigListExpanded) return
        val container = binding.layoutConfigList
        container.removeAllViews()
        val configFiles = VideoRoomConfigManager.listConfigFilesForCurrentVideo()
        if (VideoRoomConfigManager.currentVideoContext() == null) {
            container.addView(TextView(requireContext()).apply {
                text = "请先选择测试视频"
                textSize = 13f
                setTextColor(0xFF999999.toInt())
            })
            return
        }
        container.addView(buildNoConfigRow())
        if (configFiles.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "当前视频暂无房间配置"
                textSize = 13f
                setTextColor(0xFF999999.toInt())
            })
            return
        }
        configFiles.forEachIndexed { index, file ->
            container.addView(buildConfigRow(file, index))
        }
    }

    private fun resolveVideoHistoryLabel(uriString: String): String {
        return VideoRoomConfigManager.contextForUri(uriString)?.baseName ?: "video"
    }

    private fun showVideoHistoryDetails(uriString: String, index: Int) {
        val label = resolveVideoHistoryLabel(uriString)
        val currentMark = if (AppSettings.testVideoUri == uriString) "是" else "否"
        AlertDialog.Builder(context)
            .setTitle("视频详情")
            .setMessage(
                "序号：${index + 1}\n" +
                    "名称：$label\n" +
                    "当前已选：$currentMark\n" +
                    "URI：$uriString"
            )
            .setPositiveButton("确定", null)
            .show()
    }

    private fun confirmLoadVideoHistory(uriString: String) {
        val label = resolveVideoHistoryLabel(uriString)
        AlertDialog.Builder(context)
            .setTitle("确认读取视频")
            .setMessage("确认切换到测试视频 $label 吗？")
            .setPositiveButton("读取") { _, _ ->
                loadSelectedVideo(uriString, addToHistory = true)
                Toast.makeText(context, "已切换视频: $label", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteVideoHistory(uriString: String) {
        val label = resolveVideoHistoryLabel(uriString)
        AlertDialog.Builder(context)
            .setTitle("确认删除历史")
            .setMessage("确认删除历史视频 $label 吗？")
            .setPositiveButton("删除") { _, _ ->
                AppSettings.removeTestVideoHistory(uriString)
                Toast.makeText(context, "已删除历史视频: $label", Toast.LENGTH_SHORT).show()
                refreshVideoListContent()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildVideoHistoryRow(uriString: String, index: Int): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp
            }
        }
        val isCurrent = AppSettings.testVideoUri == uriString
        val label = resolveVideoHistoryLabel(uriString)
        val titleView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = buildString {
                append("${index + 1}. $label")
                if (isCurrent) append("  [当前]")
            }
            textSize = 13f
            setTextColor(if (isCurrent) 0xFF009688.toInt() else 0xFF333333.toInt())
        }
        val detailButton = com.google.android.material.button.MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "查看"
            setOnClickListener { showVideoHistoryDetails(uriString, index) }
        }
        val loadButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "读取"
            setOnClickListener { confirmLoadVideoHistory(uriString) }
        }
        val deleteButton = com.google.android.material.button.MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "删除"
            setOnClickListener { confirmDeleteVideoHistory(uriString) }
        }
        row.addView(titleView)
        row.addView(detailButton)
        row.addView(loadButton)
        row.addView(deleteButton)
        return row
    }

    private fun refreshVideoListContent() {
        if (!isVideoListExpanded) return
        val container = binding.layoutVideoList
        container.removeAllViews()

        val loadFromFileButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "从文件中加载"
            setOnClickListener { launchVideoPicker() }
        }
        container.addView(loadFromFileButton)

        val historyTitle = TextView(requireContext()).apply {
            text = "历史加载过的视频"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 12.dp, 0, 0)
        }
        container.addView(historyTitle)

        val history = AppSettings.getTestVideoHistory()
        if (history.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "暂无历史视频"
                textSize = 13f
                setTextColor(0xFF999999.toInt())
                setPadding(0, 8.dp, 0, 0)
            })
            return
        }
        history.forEachIndexed { index, uriString ->
            container.addView(buildVideoHistoryRow(uriString, index))
        }
    }

    private fun refreshConfiguredVideoListContent() {
        val container = binding.layoutConfiguredVideoList
        container.removeAllViews()
        val configuredVideos = AppSettings.getTestVideoHistory()
            .filter(VideoRoomConfigManager::hasConfigFilesForVideo)

        if (configuredVideos.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "暂无已有配置的视频"
                textSize = 13f
                setTextColor(0xFF9FB0C4.toInt())
                setPadding(0, 6.dp, 0, 0)
            })
            return
        }

        configuredVideos.forEach { uriString ->
            val isCurrent = AppSettings.testVideoUri == uriString
            val label = resolveVideoHistoryLabel(uriString)
            container.addView(com.google.android.material.button.MaterialButton(
                requireContext(),
                null,
                if (isCurrent) {
                    com.google.android.material.R.attr.materialButtonStyle
                } else {
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                }
            ).apply {
                text = if (isCurrent) "$label  ·  当前" else label
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(Color.parseColor(if (isCurrent) "#FFFFFF" else "#2563FF"))
                if (isCurrent) {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#2563FF")
                    )
                }
                setOnClickListener {
                    if (!isCurrent) {
                        loadSelectedVideo(uriString, addToHistory = true)
                        Toast.makeText(context, "已切换视频: $label", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun formatConfidence(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun progressToConfidence(progress: Int): Float = (progress.coerceIn(0, 100) / 100f)

    private fun confidenceToProgress(value: Float): Int = (value.coerceIn(0f, 1f) * 100f).toInt()

    private fun handTranslationFullRangeToProgress(value: Float): Int {
        return ((value - AppSettings.HAND_TRANSLATION_FULL_RANGE_MIN) /
            AppSettings.HAND_TRANSLATION_FULL_RANGE_STEP).roundToInt().coerceIn(0, 25)
    }

    private fun progressToHandTranslationFullRange(progress: Int): Float {
        return AppSettings.HAND_TRANSLATION_FULL_RANGE_MIN +
            progress.coerceIn(0, 25) * AppSettings.HAND_TRANSLATION_FULL_RANGE_STEP
    }

    private fun formatHandTranslationFullRange(value: Float): String {
        return String.format(Locale.US, "%.1f×", value)
    }

    private fun setHandDetectionParamsExpanded(expanded: Boolean) {
        isHandDetectionParamsExpanded = expanded
        binding.layoutHandDetectionParams.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.tvHandDetectionParamsArrow.text = if (expanded) "收起" else "展开"
    }

    private fun setSispCoreExpanded(expanded: Boolean) {
        isSispCoreExpanded = expanded
        updateSispCoreUi()
    }

    private fun setSispManualFormExpanded(expanded: Boolean) {
        isSispManualFormExpanded = expanded
        updateSispCoreUi()
    }

    private fun buildSispTerminalIdentity(): String {
        val version = Build.VERSION.RELEASE.orEmpty().trim()
        val maker = Build.MANUFACTURER.orEmpty().trim()
            .ifBlank { Build.BRAND.orEmpty().trim() }
        val model = Build.MODEL.orEmpty().trim()
        if (version.isBlank() || maker.isBlank() || model.isBlank()) {
            return "Android 终端"
        }
        return "Android $version · $maker $model"
    }

    private fun updateSispCoreUi() {
        if (_binding == null) return
        val statusValue = when (sispCoreConnectionState) {
            SispCoreConnectionState.Disconnected -> "未连接"
            SispCoreConnectionState.Connecting -> "连接中"
            SispCoreConnectionState.Connected -> "已连接"
        }
        val detailText = when (sispCoreConnectionState) {
            SispCoreConnectionState.Disconnected -> "自动搜索中 · 未发现本地 Core 服务"
            SispCoreConnectionState.Connecting -> "正在连接 $sispConnectingHost:$sispConnectingPort"
            SispCoreConnectionState.Connected -> "已连接到 SISP Core"
        }
        val connecting = sispCoreConnectionState == SispCoreConnectionState.Connecting
        val autoSearching = sispCoreConnectionState == SispCoreConnectionState.Disconnected
        binding.tvSispCoreStatus.text = buildSispStatusText(statusValue)
        binding.progressSispAutoSearch.visibility = if (autoSearching) View.VISIBLE else View.GONE
        binding.tvSispCoreDetail.text = detailText
        binding.tvSispCoreArrow.text = if (isSispCoreExpanded) "收起" else "展开"
        binding.tvSispTerminalIdentity.text = buildSispTerminalIdentity()
        binding.layoutSispCoreExpanded.visibility = if (isSispCoreExpanded) View.VISIBLE else View.GONE
        binding.layoutSispManualForm.visibility = if (isSispManualFormExpanded) View.VISIBLE else View.GONE
        binding.btnSispManualConnect.text = if (isSispManualFormExpanded) "收起手动连接" else "手动连接"
        binding.btnSispConnect.text = if (connecting) "连接中…" else "连接"
        binding.btnSispConnect.isEnabled = !connecting
        binding.etSispHost.isEnabled = !connecting
        binding.etSispPort.isEnabled = !connecting
    }

    private fun buildSispStatusText(statusValue: String): SpannableString {
        val title = "SISP Core："
        val fullText = "$title$statusValue"
        return SpannableString(fullText).apply {
            setSpan(
                ForegroundColorSpan(Color.parseColor("#1976D2")),
                0,
                title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (statusValue == "未连接") {
                setSpan(
                    ForegroundColorSpan(Color.parseColor("#D32F2F")),
                    title.length,
                    fullText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun startSispManualConnection() {
        val host = binding.etSispHost.text.toString().trim()
        val portText = binding.etSispPort.text.toString().trim()
        if (host.isBlank()) {
            Toast.makeText(context, "请输入服务端地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (portText.isBlank()) {
            Toast.makeText(context, "请输入端口", Toast.LENGTH_SHORT).show()
            return
        }
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(context, "端口范围应为 1-65535", Toast.LENGTH_SHORT).show()
            return
        }
        clearSispConnectionRunnable()
        sispConnectingHost = host
        sispConnectingPort = port.toString()
        sispCoreConnectionState = SispCoreConnectionState.Connecting
        updateSispCoreUi()
        sispConnectRunnable = Runnable { finishSispConnectionFailure() }
        statusHandler.postDelayed(sispConnectRunnable!!, 2500L)
    }

    private fun finishSispConnectionFailure() {
        sispConnectRunnable = null
        sispCoreConnectionState = SispCoreConnectionState.Disconnected
        updateSispCoreUi()
        Toast.makeText(context, "暂未连接成功，请检查地址与端口", Toast.LENGTH_SHORT).show()
    }

    private fun clearSispConnectionRunnable() {
        sispConnectRunnable?.let { statusHandler.removeCallbacks(it) }
        sispConnectRunnable = null
    }

    private fun syncHandDetectionConfidenceViews() {
        binding.sbHandDetectionConfidence.progress = confidenceToProgress(AppSettings.handDetectionConfidence)
        binding.tvHandDetectionConfidenceValue.text = formatConfidence(AppSettings.handDetectionConfidence)
        binding.sbHandPresenceConfidence.progress = confidenceToProgress(AppSettings.handPresenceConfidence)
        binding.tvHandPresenceConfidenceValue.text = formatConfidence(AppSettings.handPresenceConfidence)
        binding.sbHandTrackingConfidence.progress = confidenceToProgress(AppSettings.handTrackingConfidence)
        binding.tvHandTrackingConfidenceValue.text = formatConfidence(AppSettings.handTrackingConfidence)
        binding.sbHandTranslationFullRange.progress =
            handTranslationFullRangeToProgress(AppSettings.handTranslationFullRange)
        binding.tvHandTranslationFullRangeValue.text =
            formatHandTranslationFullRange(AppSettings.handTranslationFullRange)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsHomeBinding.inflate(inflater, container, false)
        binding.tvAppVersion.text =
            "SISP ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Build ${BuildConfig.BUILD_TIME}"
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setSispCoreExpanded(false)
        setSispManualFormExpanded(false)
        setConfigListExpanded(false)
        setVideoListExpanded(false)
        setHandDetectionParamsExpanded(false)

        binding.spnPoseRoiSizeMode.adapter = createChoiceAdapter(
            resources.getStringArray(R.array.pose_roi_size_labels).toList()
        ) { binding.spnPoseRoiSizeMode.selectedItemPosition }
        binding.spnRoiLogMode.adapter = createChoiceAdapter(
            resources.getStringArray(R.array.roi_log_mode_labels).toList()
        ) { binding.spnRoiLogMode.selectedItemPosition }
        binding.spnPointingDisplayMode.adapter = createChoiceAdapter(
            resources.getStringArray(R.array.pointing_debug_display_mode_labels).toList()
        ) { binding.spnPointingDisplayMode.selectedItemPosition }

        // 初始化开关状态
        binding.switchShowBox.isChecked = AppSettings.isDebugBoxShown
        binding.switchShowPoint.isChecked = AppSettings.isCenterPointShown
        binding.switchPoseMode.isChecked = AppSettings.isPoseModeEnabled
        binding.switchRoiCrop.isChecked = AppSettings.isRoiRealCropEnabled
        binding.spnPoseRoiSizeMode.setSelection(AppSettings.poseRoiSizeMode)
        syncPoseRoiSizeVisibility(AppSettings.isRoiRealCropEnabled)
        binding.switchStillStandardFrame.isChecked = AppSettings.isStillStandardFrameEnabled
        binding.switchClipboardDebug.isChecked = AppSettings.isClipboardDebugOnStepEnabled
        binding.switchPointingDebugOverlay.isChecked = AppSettings.isPointingDebugOverlayEnabled
        binding.spnPointingDisplayMode.setSelection(AppSettings.pointingDebugDisplayMode)
        syncPointingDisplayModeVisibility(AppSettings.isPointingDebugOverlayEnabled)
        binding.switchNewTracker.isChecked = AppSettings.isNewTrackerPredictionEnabled
        binding.switchPauseOnRoomSwitch.isChecked = AppSettings.isPauseOnRoomSwitchEnabled
        binding.switchPauseDecisionLogOnSwitch.isChecked = AppSettings.isPauseDecisionLogOnSwitchEnabled
        binding.switchPauseOnVoiceRecognizeFail.isChecked = AppSettings.isPauseOnVoiceRecognizeFailEnabled
        binding.switchSmartMatchPause.isChecked = AppSettings.isSmartMatchPauseEnabled
        val initialWindowMs = AppSettings.eventMissPauseWindowMs
        binding.sbEventMissPauseWindow.progress = ((initialWindowMs - 100) / 100).coerceIn(0, 9)
        binding.tvEventMissPauseWindowValue.text = "${initialWindowMs} ms"
        syncHandDetectionConfidenceViews()
        binding.tvTrackerStatus.text = if (AppSettings.isNewTrackerPredictionEnabled) "ByteTrack 服务状态：检测中" else "ByteTrack 服务状态：未启用"

        binding.spnRoiLogMode.setSelection(AppSettings.roiLogMode)
        binding.spnRoiLogMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.setRoiLogMode(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spnPoseRoiSizeMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.setPoseRoiSizeMode(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spnPointingDisplayMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.setPointingDebugDisplayMode(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 人数算法版本选择（与“日志更新频率”同款 Spinner）
        presenceAlgoOptions = buildPresenceOptionsInStableOrder()
        val labels = presenceAlgoOptions.map { option -> option.label }
        val presenceAdapter = createChoiceAdapter(labels) {
            binding.spnPresenceAlgorithmVersion.selectedItemPosition
        }
        binding.spnPresenceAlgorithmVersion.adapter = presenceAdapter
        syncPresenceSpinnerSelection()
        binding.spnPresenceAlgorithmVersion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (syncingPresenceSpinner) return
                val option = presenceAlgoOptions.getOrNull(position) ?: return
                if (AppSettings.presenceAlgorithmVersion != option.id) {
                    AppSettings.setPresenceAlgorithmVersion(option.id)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 开关监听
        binding.switchShowBox.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setDebugBoxShown(isChecked)
        }

        binding.switchShowPoint.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setCenterPointShown(isChecked)
        }

        binding.switchPoseMode.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setPoseModeEnabled(isChecked)
        }

        binding.switchRoiCrop.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setRoiRealCropEnabled(isChecked)
            syncPoseRoiSizeVisibility(isChecked)
        }

        binding.switchStillStandardFrame.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setStillStandardFrameEnabled(isChecked)
        }

        binding.switchClipboardDebug.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setClipboardDebugOnStepEnabled(isChecked)
            if (isChecked) {
                Toast.makeText(context, "已开启：+1帧触发后若发生unlock将写入剪贴板", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchPointingDebugOverlay.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setPointingDebugOverlayEnabled(isChecked)
            syncPointingDisplayModeVisibility(isChecked)
        }

        binding.switchNewTracker.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setNewTrackerPredictionEnabled(isChecked)
            binding.tvTrackerStatus.text = if (isChecked) "ByteTrack 服务状态：检测中" else "ByteTrack 服务状态：未启用"
            if (isChecked) startTrackerStatusPolling() else stopTrackerStatusPolling()
        }

        binding.switchPauseOnRoomSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setPauseOnRoomSwitchEnabled(isChecked)
        }

        binding.switchPauseDecisionLogOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setPauseDecisionLogOnSwitchEnabled(isChecked)
        }

        binding.switchPauseOnVoiceRecognizeFail.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setPauseOnVoiceRecognizeFailEnabled(isChecked)
        }

        binding.switchSmartMatchPause.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setSmartMatchPauseEnabled(isChecked)
        }

        binding.layoutSispHeader.setOnClickListener {
            setSispCoreExpanded(!isSispCoreExpanded)
        }

        binding.btnSispManualConnect.setOnClickListener {
            setSispManualFormExpanded(!isSispManualFormExpanded)
        }

        binding.btnSispConnect.setOnClickListener {
            startSispManualConnection()
        }

        binding.layoutHandDetectionParamsHeader.setOnClickListener {
            setHandDetectionParamsExpanded(!isHandDetectionParamsExpanded)
        }

        binding.sbHandDetectionConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progressToConfidence(progress)
                binding.tvHandDetectionConfidenceValue.text = formatConfidence(value)
                if (fromUser) {
                    AppSettings.setHandDetectionConfidence(value)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.sbHandPresenceConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progressToConfidence(progress)
                binding.tvHandPresenceConfidenceValue.text = formatConfidence(value)
                if (fromUser) {
                    AppSettings.setHandPresenceConfidence(value)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.sbHandTrackingConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progressToConfidence(progress)
                binding.tvHandTrackingConfidenceValue.text = formatConfidence(value)
                if (fromUser) {
                    AppSettings.setHandTrackingConfidence(value)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.sbHandTranslationFullRange.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progressToHandTranslationFullRange(progress)
                binding.tvHandTranslationFullRangeValue.text = formatHandTranslationFullRange(value)
                if (fromUser) {
                    AppSettings.setHandTranslationFullRange(value)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.sbEventMissPauseWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val windowMs = 100 + progress.coerceIn(0, 9) * 100
                binding.tvEventMissPauseWindowValue.text = "${windowMs} ms"
                if (fromUser) {
                    AppSettings.setEventMissPauseWindowMs(windowMs)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // 区域设置入口
        binding.cardRoomSetup.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RoomListFragment())
                .addToBackStack(null)
                .commit()
        }

        // 导出按钮
        binding.btnExport.setOnClickListener {
            val videoContext = VideoRoomConfigManager.currentVideoContext()
            if (videoContext == null) {
                Toast.makeText(context, "请先选择测试视频", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!RoomRepository.hasMeaningfulConfig()) {
                Toast.makeText(context, "当前房间为空，无法保存", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentFile = RoomRepository.currentConfigFile()
            val isCurrentVideoScopedFile = VideoRoomConfigManager.isCurrentVideoConfigFile(currentFile)
            if (isCurrentVideoScopedFile && !RoomRepository.hasUnsavedChanges()) {
                Toast.makeText(context, "当前配置没有改动，无需保存", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val suggestedName = VideoRoomConfigManager.suggestNextConfigNameForCurrentVideo()
                ?: videoContext.baseName
            val input = EditText(context).apply {
                setText(suggestedName)
                hint = "输入文件名"
            }

            AlertDialog.Builder(context)
                .setTitle("另存为")
                .setMessage("请输入新配置文件名 (无需后缀)")
                .setView(input)
                .setPositiveButton("另存为") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isBlank()) {
                        Toast.makeText(context, "文件名不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val targetFile = VideoRoomConfigManager.buildConfigFileForCurrentVideo(name)
                    if (targetFile == null) {
                        Toast.makeText(context, "请先选择测试视频", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (targetFile.exists()) {
                        confirmOverwriteAndSave(targetFile)
                    } else {
                        saveCurrentConfigAs(targetFile)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 读取配置入口（折叠列表）
        binding.btnImport.setOnClickListener {
            if (VideoRoomConfigManager.currentVideoContext() == null) {
                Toast.makeText(context, "请先选择测试视频", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setConfigListExpanded(!isConfigListExpanded)
        }

        binding.btnImportOtherVideoConfig.setOnClickListener {
            showImportOtherVideoConfigDialog()
        }

        // 测试视频选择
        binding.btnSelectVideo.setOnClickListener {
            if (isVideoListExpanded) {
                setVideoListExpanded(false)
            } else {
                launchVideoPicker()
            }
        }

        binding.btnClearRoomCounts.setOnClickListener {
            val rooms = RoomRepository.getAllRooms()
            rooms.forEach { room ->
                room.personCount = 0
                room.persistentPersonCount = 0
                RoomRepository.updateRoom(room)
            }
            Toast.makeText(context, "已清空所有房间人数", Toast.LENGTH_SHORT).show()
        }

        // 重置按钮
        binding.btnResetTrackers.setOnClickListener {
            RoomRepository.resetAllStatus() 
            Toast.makeText(context, "追踪器状态已重置 (需重启识别)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        syncPresenceSpinnerSelection()
        binding.spnPoseRoiSizeMode.setSelection(AppSettings.poseRoiSizeMode)
        syncPoseRoiSizeVisibility(AppSettings.isRoiRealCropEnabled)
        binding.switchPointingDebugOverlay.isChecked = AppSettings.isPointingDebugOverlayEnabled
        binding.spnPointingDisplayMode.setSelection(AppSettings.pointingDebugDisplayMode)
        syncPointingDisplayModeVisibility(AppSettings.isPointingDebugOverlayEnabled)
        binding.switchSmartMatchPause.isChecked = AppSettings.isSmartMatchPauseEnabled
        syncHandDetectionConfidenceViews()
        val windowMs = AppSettings.eventMissPauseWindowMs
        binding.sbEventMissPauseWindow.progress = ((windowMs - 100) / 100).coerceIn(0, 9)
        binding.tvEventMissPauseWindowValue.text = "${windowMs} ms"
        if (AppSettings.isNewTrackerPredictionEnabled) startTrackerStatusPolling() else stopTrackerStatusPolling()
        refreshConfiguredVideoListContent()
        refreshVideoListContent()
        refreshConfigListContent()
    }

    override fun onPause() {
        super.onPause()
        stopTrackerStatusPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTrackerStatusPolling()
        clearSispConnectionRunnable()
        _binding = null
    }

    private fun syncPoseRoiSizeVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        binding.layoutPoseRoiSizeChoice.visibility = visibility
    }

    private fun syncPointingDisplayModeVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        binding.layoutPointingDisplayModeChoice.visibility = visibility
    }
}
