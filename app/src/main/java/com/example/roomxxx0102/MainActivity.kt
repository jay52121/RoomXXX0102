package com.example.roomxxx0102

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var previewView: PreviewView
    private lateinit var textureView: TextureView
    private lateinit var overlayView: DetectionOverlayView
    
    private var yoloAnalyzer: YoloAnalyzer? = null
    private var poseAnalyzer: YoloPoseAnalyzer? = null
    private var videoFeeder: VideoFeeder? = null

    private var isVideoMode = true
    private var isDebugBoxShown = false
    private var isCenterPointShown = true
    private var isPoseMode = false

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val videoGranted = if (Build.VERSION.SDK_INT >= 33) {
            permissions[Manifest.permission.READ_MEDIA_VIDEO] ?: false
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        }

        if (cameraGranted && videoGranted) {
            if (isVideoMode) startVideoMode() else startCameraMode()
        } else if (isVideoMode && videoGranted) {
             startVideoMode()
        } else if (!isVideoMode && cameraGranted) {
             startCameraMode()
        } else {
            Toast.makeText(this, "需要权限才能运行", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()

        val frameLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }
        frameLayout.addView(textureView)

        previewView = PreviewView(this).apply { 
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frameLayout.addView(previewView)

        overlayView = DetectionOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frameLayout.addView(overlayView)

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#44000000"))
            setPadding(10, 10, 10, 10) // 减小内边距，挤一挤
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM; bottomMargin = 80
        }

        // --- 按钮 ---
        val btnRewind = Button(this).apply {
            text = "⏪ -5s"
            setOnClickListener { videoFeeder?.seekBackward(5) }
        }
        
        val btnForward = Button(this).apply {
            text = "⏩ +5s"
            setOnClickListener { videoFeeder?.seekForward(5) }
        }

        val btnReset = Button(this).apply {
            text = "重置"
            setOnClickListener { yoloAnalyzer?.reset() }
        }
        
        val btnSwitchMode = Button(this).apply {
            text = "模式"
            setOnClickListener { toggleMode() }
        }
        
        val btnBoxSwitch = Button(this).apply {
            text = "框:关"
            setOnClickListener {
                isDebugBoxShown = !isDebugBoxShown
                text = if (isDebugBoxShown) "框:开" else "框:关"
                overlayView.setDebugBoxState(isDebugBoxShown)
            }
        }

        val btnPointSwitch = Button(this).apply {
            text = "点:开"
            setOnClickListener {
                isCenterPointShown = !isCenterPointShown
                text = if (isCenterPointShown) "点:开" else "点:关"
                overlayView.setCenterPointState(isCenterPointShown)
            }
        }

        val btnPoseSwitch = Button(this).apply {
            text = "Pose:关"
            setOnClickListener {
                isPoseMode = !isPoseMode
                text = if (isPoseMode) "Pose:开" else "Pose:关"
                overlayView.setPoseState(isPoseMode)
                if (isVideoMode) videoFeeder?.isPoseMode = isPoseMode
                else { unbindCamera(); startCameraMode() }
            }
        }

        // 添加到布局
        val spacer = 15 // 稍微紧凑点
        btnLayout.addView(btnRewind); btnLayout.addView(createSpacer(spacer))
        btnLayout.addView(btnForward); btnLayout.addView(createSpacer(spacer))
        btnLayout.addView(btnReset); btnLayout.addView(createSpacer(spacer))
        btnLayout.addView(btnSwitchMode); btnLayout.addView(createSpacer(spacer))
        btnLayout.addView(btnBoxSwitch); btnLayout.addView(createSpacer(spacer))
        btnLayout.addView(btnPointSwitch); btnLayout.addView(createSpacer(spacer))
        btnLayout.addView(btnPoseSwitch)

        frameLayout.addView(btnLayout, params)

        setContentView(frameLayout)

        yoloAnalyzer = YoloAnalyzer(this, overlayView)
        poseAnalyzer = YoloPoseAnalyzer(this) { results, bitmap, time ->
            runOnUiThread {
                overlayView.updatePoseData(results, bitmap, time)
            }
        }

        videoFeeder = VideoFeeder(this, textureView).apply {
            this.yoloAnalyzer = this@MainActivity.yoloAnalyzer
            this.poseAnalyzer = this@MainActivity.poseAnalyzer
        }

        checkPermissionsAndStart()
    }

    private fun createSpacer(width: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoFeeder?.stop()
        unbindCamera()
    }

    private fun toggleMode() {
        if (isVideoMode) {
            isVideoMode = false
            videoFeeder?.stop()
            yoloAnalyzer?.reset()
            textureView.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            checkPermissionsAndStart()
        } else {
            isVideoMode = true
            unbindCamera()
            yoloAnalyzer?.reset()
            previewView.visibility = View.GONE
            textureView.visibility = View.VISIBLE
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        val storagePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, storagePermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(storagePermission)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            if (isVideoMode) startVideoMode() else startCameraMode()
        }
    }

    private fun startVideoMode() {
        textureView.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        val hardcodedPath = "/storage/emulated/0/Android/media/com.example.roomxxx0102/test_video.mp4"
        val file = File(hardcodedPath)
        if (file.exists()) {
            videoFeeder?.isPoseMode = isPoseMode
            videoFeeder?.start(hardcodedPath)
        } else {
            Toast.makeText(this, "找不到视频文件", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCameraMode() {
        textureView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 960))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                
                if (isPoseMode) {
                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), poseAnalyzer!!)
                } else {
                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), yoloAnalyzer!!)
                }
                
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) { Log.e("Main", "Camera Error", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) {}
    }
}