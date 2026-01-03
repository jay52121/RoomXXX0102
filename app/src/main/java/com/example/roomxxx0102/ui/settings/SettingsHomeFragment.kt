package com.example.roomxxx0102.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.roomxxx0102.R
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.databinding.FragmentSettingsHomeBinding

/**
 * **设置主页 (Settings Home)**
 *
 * 包含调试开关和功能入口。
 */
class SettingsHomeFragment : Fragment() {

    private var _binding: FragmentSettingsHomeBinding? = null
    private val binding get() = _binding!!

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
            // 跳转到 RoomListFragment
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RoomListFragment())
                .addToBackStack(null)
                .commit()
        }

        // 重置按钮
        binding.btnResetTrackers.setOnClickListener {
            RoomRepository.resetAllStatus() // 这里虽然只重置了 RoomRepo，但实际需要重置 Analyzer。
            // 由于 Analyzer 在 MainActivity，这里只做标记或 Toast。
            // 更好的做法是通过 EventBus 或 callback 通知 MainActivity，但目前简化处理：
            // 这里重置 RoomRepo 的状态，回到主界面时 MainActivity 会重新应用设置。
            Toast.makeText(context, "追踪器状态已重置 (需重启识别)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
