package com.example.roomxxx0102.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

/**
 * **设置主页 (Settings Home)**
 *
 * 包含调试开关和功能入口。
 */
class SettingsHomeFragment : Fragment() {

    private var _binding: FragmentSettingsHomeBinding? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null
    private val httpClient = OkHttpClient()
    private val binding get() = _binding!!
    private var presenceAlgoOptions: List<PresenceAlgorithmRegistry.AlgorithmOption> = emptyList()
    private var syncingPresenceSpinner = false
    private var isConfigListExpanded = false
    private var isVideoListExpanded = false

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
                val label = "ByteTrack：不可用"
                statusHandler.post { _binding?.tvTrackerStatus?.text = label }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val ok = it.isSuccessful
                    val label = if (ok) "ByteTrack：可用" else "ByteTrack：不可用"
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

    private val selectVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                loadSelectedVideo(uri.toString(), addToHistory = true)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "设置失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadSelectedVideo(uriString: String, addToHistory: Boolean) {
        AppSettings.setTestVideoUri(uriString)
        if (addToHistory) {
            AppSettings.pushTestVideoHistory(uriString)
        }
        loadDefaultConfigForSelectedVideo()
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
            setOnClickListener { selectVideoLauncher.launch(arrayOf("video/*")) }
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

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setConfigListExpanded(false)
        setVideoListExpanded(false)

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
        binding.switchNewTracker.isChecked = AppSettings.isNewTrackerPredictionEnabled
        binding.switchPauseOnRoomSwitch.isChecked = AppSettings.isPauseOnRoomSwitchEnabled
        binding.switchPauseDecisionLogOnSwitch.isChecked = AppSettings.isPauseDecisionLogOnSwitchEnabled
        binding.switchPauseOnVoiceRecognizeFail.isChecked = AppSettings.isPauseOnVoiceRecognizeFailEnabled
        binding.switchSmartMatchPause.isChecked = AppSettings.isSmartMatchPauseEnabled
        val initialWindowMs = AppSettings.eventMissPauseWindowMs
        binding.sbEventMissPauseWindow.progress = ((initialWindowMs - 100) / 100).coerceIn(0, 9)
        binding.tvEventMissPauseWindowValue.text = "${initialWindowMs} ms"
        binding.tvTrackerStatus.text = if (AppSettings.isNewTrackerPredictionEnabled) "ByteTrack：检测中" else "ByteTrack：未启用"

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

        // 人数算法版本选择（与“日志更新频率”同款 Spinner）
        presenceAlgoOptions = buildPresenceOptionsInStableOrder()
        val labels = presenceAlgoOptions.map { option -> option.label }
        val presenceAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            labels
        )
        presenceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
        }

        binding.switchNewTracker.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setNewTrackerPredictionEnabled(isChecked)
            binding.tvTrackerStatus.text = if (isChecked) "ByteTrack：检测中" else "ByteTrack：未启用"
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

        // 测试视频选择
        binding.btnSelectVideo.setOnClickListener {
            setVideoListExpanded(!isVideoListExpanded)
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
        binding.switchSmartMatchPause.isChecked = AppSettings.isSmartMatchPauseEnabled
        val windowMs = AppSettings.eventMissPauseWindowMs
        binding.sbEventMissPauseWindow.progress = ((windowMs - 100) / 100).coerceIn(0, 9)
        binding.tvEventMissPauseWindowValue.text = "${windowMs} ms"
        if (AppSettings.isNewTrackerPredictionEnabled) startTrackerStatusPolling() else stopTrackerStatusPolling()
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
        _binding = null
    }

    private fun syncPoseRoiSizeVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        binding.tvPoseRoiSizeLabel.visibility = visibility
        binding.spnPoseRoiSizeMode.visibility = visibility
    }
}

