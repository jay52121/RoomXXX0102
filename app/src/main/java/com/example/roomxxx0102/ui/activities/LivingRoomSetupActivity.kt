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
import com.example.roomxxx0102.R
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.ui.views.LivingRoomEditorView
import com.example.roomxxx0102.utils.BitmapTransfer

class LivingRoomSetupActivity : AppCompatActivity() {

    private lateinit var editorView: LivingRoomEditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        editorView = LivingRoomEditorView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(editorView)

        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#80000000"))
        }
        val containerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 64
        }
        rootLayout.addView(btnContainer, containerParams)

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

        btnContainer.addView(createBtn(getString(R.string.undo)) { editorView.undo() })
        btnContainer.addView(createBtn(getString(R.string.clear)) { editorView.clear() })
        btnContainer.addView(createBtn(getString(R.string.finish)) { saveAndFinish() })

        setContentView(rootLayout)

        loadInitialData()
    }

    private fun loadInitialData() {
        val bmp = BitmapTransfer.capturedFrame
        if (bmp != null) {
            editorView.backgroundBitmap = bmp
        } else {
            Log.w("SetupActivity", "未收到截图，显示黑屏")
            Toast.makeText(this, getString(R.string.toast_error_no_screenshot), Toast.LENGTH_LONG).show()
        }

        val room = RoomRepository.getAllRooms().find { it.id == "living_room" }
        if (room != null && room.boundaryVertices.isNotEmpty()) {
            editorView.setHistoryVertices(room.boundaryVertices)
            Toast.makeText(this, getString(R.string.toast_load_history_success), Toast.LENGTH_SHORT).show()
        }
        editorView.setSubRooms(RoomRepository.getSubRooms())
    }

    private fun saveAndFinish() {
        val resultVertices = editorView.getResult()
        
        if (resultVertices.size < 3) {
            Toast.makeText(this, getString(R.string.toast_invalid_area), Toast.LENGTH_SHORT).show()
            return
        }

        RoomRepository.updateRoomBoundary("living_room", resultVertices)
        
        Toast.makeText(this, getString(R.string.toast_save_success), Toast.LENGTH_SHORT).show()
        
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
