package com.example.roomxxx0102.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.roomxxx0102.R
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.databinding.FragmentSettingsHomeBinding
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
    private val binding get() = _binding!!

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

        // 重置按钮
        binding.btnResetTrackers.setOnClickListener {
            RoomRepository.resetAllStatus() 
            Toast.makeText(context, "追踪器状态已重置 (需重启识别)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
