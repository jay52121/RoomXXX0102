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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.roomxxx0102.R
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.databinding.FragmentSettingsHomeBinding
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
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

    private fun syncPresenceSelector() {
        if (_binding == null || presenceAlgoOptions.isEmpty()) return
        val selected = presenceAlgoOptions.getOrNull(resolvePresenceSelectionIndex())
            ?: presenceAlgoOptions.first()
        binding.btnPresenceAlgorithmVersion.text = selected.label
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
                AppSettings.setTestVideoUri(uri.toString())
                Toast.makeText(context, "已设置测试视频", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "设置失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

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

        // 初始化开关状态
        binding.switchShowBox.isChecked = AppSettings.isDebugBoxShown
        binding.switchShowPoint.isChecked = AppSettings.isCenterPointShown
        binding.switchPoseMode.isChecked = AppSettings.isPoseModeEnabled
        binding.switchRoiCrop.isChecked = AppSettings.isRoiRealCropEnabled
        binding.switchStillStandardFrame.isChecked = AppSettings.isStillStandardFrameEnabled
        binding.switchClipboardDebug.isChecked = AppSettings.isClipboardDebugOnStepEnabled
        binding.switchNewTracker.isChecked = AppSettings.isNewTrackerPredictionEnabled
        binding.switchPauseOnRoomSwitch.isChecked = AppSettings.isPauseOnRoomSwitchEnabled
        binding.tvTrackerStatus.text = if (AppSettings.isNewTrackerPredictionEnabled) "ByteTrack：检测中" else "ByteTrack：未启用"

        binding.spnRoiLogMode.setSelection(AppSettings.roiLogMode)
        binding.spnRoiLogMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.setRoiLogMode(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 人数算法版本选择（按钮 + 单选弹窗，避免 Spinner 在当前主题下显示异常）
        presenceAlgoOptions = buildPresenceOptionsInStableOrder()
        syncPresenceSelector()
        binding.btnPresenceAlgorithmVersion.setOnClickListener {
            val labels = presenceAlgoOptions.map { option -> option.label }.toTypedArray()
            var selectedIndex = resolvePresenceSelectionIndex()
            AlertDialog.Builder(requireContext())
                .setTitle("人数算法版本")
                .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setPositiveButton("确定") { _, _ ->
                    val option = presenceAlgoOptions.getOrNull(selectedIndex)
                    if (option != null) {
                        AppSettings.setPresenceAlgorithmVersion(option.id)
                        syncPresenceSelector()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
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

        binding.switchNewTracker.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setNewTrackerPredictionEnabled(isChecked)
            binding.tvTrackerStatus.text = if (isChecked) "ByteTrack：检测中" else "ByteTrack：未启用"
            if (isChecked) startTrackerStatusPolling() else stopTrackerStatusPolling()
        }

        binding.switchPauseOnRoomSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setPauseOnRoomSwitchEnabled(isChecked)
        }

        // 区域设置入口
        binding.cardRoomSetup.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RoomListFragment())
                .addToBackStack(null)
                .commit()
        }

        // 导出按钮
        binding.btnExport.setOnClickListener {
            val sdf = SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault())
            val defaultName = "ROOM${sdf.format(Date())}"
            val input = EditText(context).apply { 
                setText(defaultName)
                hint = "输入文件名"
            }
            
            AlertDialog.Builder(context)
                .setTitle("导出配置")
                .setMessage("请输入文件名 (无需后缀)")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        exportLauncher.launch("$name.Room")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 导入按钮
        binding.btnImport.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("警告")
                .setMessage("确定要覆盖当前所有房间配置吗？此操作不可撤销。")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("确定") { _, _ ->
                    importLauncher.launch(arrayOf("*/*")) // 允许所有类型，以便能选到 .Room 文件
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 测试视频选择
        binding.btnSelectVideo.setOnClickListener {
            selectVideoLauncher.launch(arrayOf("video/*"))
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
        syncPresenceSelector()
        if (AppSettings.isNewTrackerPredictionEnabled) startTrackerStatusPolling() else stopTrackerStatusPolling()
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
}
