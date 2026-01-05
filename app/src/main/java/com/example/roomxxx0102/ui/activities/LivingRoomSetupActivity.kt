package com.example.roomxxx0102.ui.activities

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.ui.views.LivingRoomEditorView
import com.example.roomxxx0102.utils.BitmapTransfer

class LivingRoomSetupActivity : AppCompatActivity() {

    private lateinit var editorView: LivingRoomEditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 全屏设置
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()

        // 2. 动态构建布局
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 底层：编辑器 View
        editorView = LivingRoomEditorView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(editorView)

        // 顶层：按钮容器
        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#80000000")) // 半透明背景
            
            // 圆角背景 (简单起见略过 ShapeDrawable，直接用颜色)
        }
        val containerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 64
        }
        rootLayout.addView(btnContainer, containerParams)

        // 添加按钮
        fun createBtn(text: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 0, 16, 0)
                }
            }
        }

        btnContainer.addView(createBtn("↩️ 撤销") { editorView.undo() })
        btnContainer.addView(createBtn("🗑️ 清空") { editorView.clear() })
        btnContainer.addView(createBtn("✅ 完成") { saveAndFinish() })

        setContentView(rootLayout)

        // 3. 加载数据
        loadInitialData()
    }

    private fun loadInitialData() {
        // 加载底图
        val bmp = BitmapTransfer.capturedFrame
        if (bmp != null) {
            editorView.backgroundBitmap = bmp
        } else {
            Log.w("SetupActivity", "未收到截图，显示黑屏")
            Toast.makeText(this, "错误：无法获取视频截图", Toast.LENGTH_LONG).show()
        }

        // 加载历史数据 (Living Room ID 固定为 "living_room")
        val room = RoomRepository.getAllRooms().find { it.id == "living_room" }
        if (room != null && room.boundaryVertices.isNotEmpty()) {
            editorView.setHistoryVertices(room.boundaryVertices)
            Toast.makeText(this, "已加载历史区域配置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndFinish() {
        val resultVertices = editorView.getResult()
        
        if (resultVertices.size < 3) {
            Toast.makeText(this, "请至少设置 3 个点以构成有效区域", Toast.LENGTH_SHORT).show()
            return
        }

        // 保存到仓库
        RoomRepository.updateRoomBoundary("living_room", resultVertices)
        
        Toast.makeText(this, "区域设置已保存", Toast.LENGTH_SHORT).show()
        
        // 释放图片内存 (可选，视是否要在 finish 后立即释放)
        // BitmapTransfer.capturedFrame = null 
        
        finish()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
