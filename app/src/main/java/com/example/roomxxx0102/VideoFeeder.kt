package com.example.roomxxx0102

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
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
            if (!isAnalyzing || mediaPlayer == null || !mediaPlayer!!.isPlaying) return

            val bitmap = textureView.bitmap 

            if (bitmap != null) {
                Thread {
                    if (isPoseMode) {
                        poseAnalyzer?.detectOnBitmap(bitmap, drawOnOverlay = true)
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

    // 🔥 快进
    fun seekForward(seconds: Int) {
        mediaPlayer?.let { mp ->
            val target = mp.currentPosition + seconds * 1000
            mp.seekTo(target.coerceAtMost(mp.duration))
        }
    }

    // 🔥 快退
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