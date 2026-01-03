package com.example.roomxxx0102.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.databinding.FragmentRoomListBinding
import com.example.roomxxx0102.databinding.ItemRoomConfigBinding
import com.example.roomxxx0102.ui.activities.LivingRoomSetupActivity

/**
 * **房间列表 Fragment (Room List)**
 *
 * 核心设置页面。
 * 展示所有已配置的房间，提供添加、删除、录制入口等功能。
 */
class RoomListFragment : Fragment() {

    private var _binding: FragmentRoomListBinding? = null
    private val binding get() = _binding!!

    private val roomAdapter = RoomAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoomListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupBottomBar()
        loadData()
    }
    
    override fun onResume() {
        super.onResume()
        // 每次回到这个页面刷新数据，因为 LivingRoomSetupActivity 可能更新了数据
        loadData()
    }

    private fun setupRecyclerView() {
        binding.rvRoomList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = roomAdapter
        }
    }

    private fun setupBottomBar() {
        // 添加房间按钮
        binding.btnAddRoom.setOnClickListener {
            showAddRoomDialog()
        }

        // 离家模式/初始化按钮
        binding.btnHomeMode.setOnClickListener {
            RoomRepository.resetAllStatus()
            Toast.makeText(context, "所有计数已归零", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadData() {
        val rooms = RoomRepository.getAllRooms()
        roomAdapter.submitList(rooms)
    }

    private fun showAddRoomDialog() {
        val input = EditText(context)
        input.hint = "输入房间名称 (如: 厨房)"
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)

        AlertDialog.Builder(requireContext())
            .setTitle("添加新房间")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    RoomRepository.addNewRoom(name)
                    loadData() // 刷新列表
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * **列表适配器 (Internal Adapter)**
     */
    inner class RoomAdapter : RecyclerView.Adapter<RoomAdapter.RoomViewHolder>() {

        private var roomList = listOf<RoomConfig>()

        fun submitList(list: List<RoomConfig>) {
            roomList = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemRoomConfigBinding.inflate(inflater, parent, false)
            return RoomViewHolder(binding)
        }

        override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
            holder.bind(roomList[position])
        }

        override fun getItemCount(): Int = roomList.size

        inner class RoomViewHolder(private val binding: ItemRoomConfigBinding) : RecyclerView.ViewHolder(binding.root) {

            fun bind(room: RoomConfig) {
                // 1. 设置房间名
                binding.tvRoomName.text = if (room.isSovereignTerritory) {
                    "${room.name} (主监控区)"
                } else {
                    room.name
                }

                // 2. 设置状态文本
                binding.tvRoomStatus.text = if (room.isRecorded) "状态: 已配置" else "状态: 未配置"

                // 3. 设置录制按钮样式与逻辑
                setupRecordButton(room)

                // 4. 设置删除按钮逻辑
                if (room.isSovereignTerritory) {
                    binding.btnDelete.visibility = View.GONE // 客厅禁止删除
                } else {
                    binding.btnDelete.visibility = View.VISIBLE
                    binding.btnDelete.setOnClickListener {
                        confirmDelete(room)
                    }
                }
            }

            private fun setupRecordButton(room: RoomConfig) {
                val btn = binding.btnRecord
                
                if (room.isSovereignTerritory) {
                    // --- 客厅逻辑 ---
                    btn.text = "✍️ 人工绘制"
                    
                    val color = if (room.isRecorded) {
                        Color.parseColor("#FF9800") // 橙色 (重绘)
                    } else {
                        Color.parseColor("#2196F3") // 蓝色 (首次绘制)
                    }
                    btn.backgroundTintList = ColorStateList.valueOf(color)

                    btn.setOnClickListener {
                        // 跳转到人工绘制界面
                        val intent = Intent(context, LivingRoomSetupActivity::class.java)
                        startActivity(intent)
                    }

                } else {
                    // --- 普通房间逻辑 (保持不变) ---
                    btn.text = if (room.isRecorded) "🚪 重录出入口" else "🚪 录制出入口"
                    
                    val color = if (room.isRecorded) {
                        Color.parseColor("#4CAF50") // 绿色
                    } else {
                        Color.LTGRAY // 灰色
                    }
                    btn.backgroundTintList = ColorStateList.valueOf(color)

                    btn.setOnClickListener {
                        Toast.makeText(context, "TODO: 返回 MainActivity 开始录制 [${room.name}] 的出入口", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            private fun confirmDelete(room: RoomConfig) {
                AlertDialog.Builder(requireContext())
                    .setTitle("删除房间")
                    .setMessage("确定要删除 [${room.name}] 吗？")
                    .setPositiveButton("删除") { _, _ ->
                        if (RoomRepository.deleteRoom(room.id)) {
                            loadData() // 刷新列表
                        } else {
                            Toast.makeText(context, "无法删除该房间", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}
