package com.example.roomxxx0102.logic.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.example.roomxxx0102.logic.analyzer.YoloAnalyzer
import com.example.roomxxx0102.logic.analyzer.YoloPoseAnalyzer
import java.io.File
import java.io.FileInputStream

class VideoFeeder(
    private val context: Context,
    private val textureView: TextureView
) {
    var yoloAnalyzer: YoloAnalyzer? = null
    var poseAnalyzer: YoloPoseAnalyzer? = null
    
    var isPoseMode = false

    private var mediaPlayer: MediaPlayer? = null
    private var isAnalyzing = false
    private val handler = Handler(Looper.getMainLooper())

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            // 🔥 修改：只有在 isAnalyzing 且正在播放时才分析
            if (!isAnalyzing || mediaPlayer == null || !mediaPlayer!!.isPlaying) {
                // 如果只是暂停，我们还在 isAnalyzing = true 状态，但 mediaPlayer.isPlaying = false
                // 此时我们需要持续检查是否恢复播放，或者完全停止
                if (isAnalyzing) {
                    handler.postDelayed(this, 200) // 暂停时降低检查频率
                }
                return
            }

            val bitmap = textureView.bitmap 

            if (bitmap != null) {
                Thread {
                    if (isPoseMode) {
                        poseAnalyzer?.analyzeBitmapAndTrackPoses(bitmap, drawOnOverlay = true)
                    } else {
                        yoloAnalyzer?.detectOnBitmap(bitmap, drawOnOverlay = true)
                    }
                }.start()
            }

            handler.postDelayed(this, 100) 
        }
    }

    fun start(filePath: String) {
        stop()

        val file = File(filePath)
        if (!file.exists()) {
            Log.e("VideoFeeder", "❌ 文件不存在: $filePath")
            return
        }

        try {
            Log.d("VideoFeeder", "🎬 初始化 MediaPlayer...")
            textureView.visibility = android.view.View.VISIBLE
            
            if (textureView.isAvailable) {
                startMediaPlayer(file, textureView.surfaceTexture!!)
            } else {
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        startMediaPlayer(file, surface)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        } catch (e: Exception) {
            Log.e("VideoFeeder", "❌ 启动失败", e)
        }
    }

    private fun startMediaPlayer(file: File, surfaceTexture: SurfaceTexture) {
        try {
            val surface = Surface(surfaceTexture)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(FileInputStream(file).fd)
                setSurface(surface)
                isLooping = true
                setOnPreparedListener { mp ->
                    Log.d("VideoFeeder", "✅ 视频准备就绪: ${mp.videoWidth}x${mp.videoHeight}")
                    adjustAspectRatio(mp.videoWidth, mp.videoHeight)
                    mp.start()
                    isAnalyzing = true
                    handler.post(analyzeRunnable)
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("VideoFeeder", "❌ MediaPlayer 错误", e)
        }
    }

    // 🔥 新增：暂停功能
    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                // isAnalyzing 保持为 true，这样 analyzeRunnable 会进入低频轮询等待恢复
            }
        }
    }

    // 🔥 新增：恢复功能
    fun resume() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                // analyzeRunnable 会自动检测到 isPlaying = true 并恢复分析
            }
        }
    }

    // 🔥 新增：判断是否正在播放
    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }

    fun seekForward(seconds: Int) {
        mediaPlayer?.let { mp ->
            val target = mp.currentPosition + seconds * 1000
            mp.seekTo(target.coerceAtMost(mp.duration))
        }
    }

    fun seekBackward(seconds: Int) {
        mediaPlayer?.let { mp ->
            val target = mp.currentPosition - seconds * 1000
            mp.seekTo(target.coerceAtLeast(0))
        }
    }

    private fun adjustAspectRatio(videoW: Int, videoH: Int) {
        if (videoW == 0 || videoH == 0) return
        val parent = textureView.parent as? android.view.View ?: return
        val screenW = parent.width
        val screenH = parent.height
        if (screenW == 0 || screenH == 0) return

        val videoRatio = videoW.toFloat() / videoH
        val screenRatio = screenW.toFloat() / screenH

        val finalW: Int
        val finalH: Int

        if (videoRatio > screenRatio) {
            finalW = screenW
            finalH = (screenW / videoRatio).toInt()
        } else {
            finalH = screenH
            finalW = (screenH * videoRatio).toInt()
        }

        handler.post {
            val params = textureView.layoutParams
            params.width = finalW
            params.height = finalH
            textureView.layoutParams = params
        }
    }

    fun stop() {
        isAnalyzing = false
        handler.removeCallbacks(analyzeRunnable)
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
    }
}
