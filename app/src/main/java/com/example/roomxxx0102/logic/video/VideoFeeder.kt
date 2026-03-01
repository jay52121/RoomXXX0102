package com.example.roomxxx0102.logic.video

import android.content.Context
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.example.roomxxx0102.logic.analyzer.YoloAnalyzer
import com.example.roomxxx0102.logic.analyzer.YoloPoseAnalyzer
import java.io.File
import java.io.FileInputStream
import kotlin.math.roundToInt

class VideoFeeder(
    private val context: Context,
    private val textureView: TextureView
) {
    var yoloAnalyzer: YoloAnalyzer? = null
    var poseAnalyzer: YoloPoseAnalyzer? = null
    
    var isPoseMode = false
    
    // 🔥 下一帧的 ROI，由 MainActivity 设置
    var nextFrameRoi: RectF? = null
    
    // 🔥 新增：静止模式开关
    private var isStillMode = false
    // 按“逐帧步进”时使用的时间步长（毫秒）；由视频 metadata 估算，失败时回退到 33ms
    private var frameStepMs = 33
    // 逐帧连续点击时的目标位置游标，避免异步 seek 导致“只生效一次”
    private var lastSeekTargetMs: Int? = null

    private var mediaPlayer: MediaPlayer? = null
    private var isAnalyzing = false
    private val handler = Handler(Looper.getMainLooper())

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            // 如果分析开关关闭，则彻底停止
            if (!isAnalyzing || mediaPlayer == null) {
                return
            }
            
            // 🔥 新逻辑：只要正在播放，或者处于静止模式，就继续识别
            if (mediaPlayer!!.isPlaying || isStillMode) {
                val bitmap = textureView.bitmap
                if (bitmap != null) {
                    val roi = nextFrameRoi
                    Thread {
                        if (isPoseMode) {
                            poseAnalyzer?.analyzeBitmapAndTrackPoses(bitmap, roi, drawOnOverlay = true)
                        } else {
                            yoloAnalyzer?.detectOnBitmap(bitmap, drawOnOverlay = true)
                        }
                    }.start()
                }
                // 正常频率
                handler.postDelayed(this, 100)
            } else {
                // 暂停状态（非静止），低频轮询
                handler.postDelayed(this, 200)
            }
        }
    }

    fun start(filePath: String) {
        setupMediaPlayer(filePath = filePath)
    }
    
    fun start(uri: Uri) {
        setupMediaPlayer(uri = uri)
    }

    private fun setupMediaPlayer(filePath: String? = null, uri: Uri? = null) {
        stop()
        frameStepMs = estimateFrameStepMs(filePath, uri)
        lastSeekTargetMs = null

        try {
            Log.d("VideoFeeder", "🎬 初始化 MediaPlayer...")
            textureView.visibility = android.view.View.VISIBLE
            
            val preparePlayer = { surfaceTexture: SurfaceTexture ->
                try {
                    val surface = Surface(surfaceTexture)
                    mediaPlayer = MediaPlayer().apply {
                        if (filePath != null) {
                            val file = File(filePath)
                            if (!file.exists()) {
                                Log.e("VideoFeeder", "❌ 文件不存在: $filePath")
                                return@apply
                            }
                            setDataSource(FileInputStream(file).fd)
                        } else if (uri != null) {
                            setDataSource(context, uri)
                        }
                        
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

            if (textureView.isAvailable) {
                preparePlayer(textureView.surfaceTexture!!)
            } else {
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        preparePlayer(surface)
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
    
    fun setStillMode(isStill: Boolean) {
        isStillMode = isStill
        if (isStill) {
            // 每次进入静止模式都重置逐帧游标，避免串用上一次静止会话的位置
            lastSeekTargetMs = null
        }
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
    }

    fun resume() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
            }
        }
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }

    fun seekForward(seconds: Int) {
        seekByMs(seconds * 1000)
    }

    fun seekBackward(seconds: Int) {
        seekByMs(-seconds * 1000)
    }

    // “按帧”本质上仍是时间 seek：MediaPlayer 不提供逐帧接口
    fun seekForwardFrame() {
        seekByMs(frameStepMs)
    }

    fun seekBackwardFrame() {
        seekByMs(-frameStepMs)
    }

    private fun seekByMs(deltaMs: Int) {
        mediaPlayer?.let { mp ->
            val base = lastSeekTargetMs ?: mp.currentPosition
            val target = (base + deltaMs).coerceIn(0, mp.duration)
            lastSeekTargetMs = target
            // 使用 SEEK_CLOSEST，尽量按最近时间点跳转，减少小步进卡在同一关键帧的问题
            mp.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        }
    }

    private fun estimateFrameStepMs(filePath: String?, uri: Uri?): Int {
        val fallback = 33
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                !filePath.isNullOrBlank() -> retriever.setDataSource(filePath)
                uri != null -> retriever.setDataSource(context, uri)
                else -> return fallback
            }
            val fpsText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val fps = fpsText?.toFloatOrNull()
            if (fps != null && fps > 1f) {
                (1000f / fps).roundToInt().coerceIn(8, 200)
            } else {
                fallback
            }
        } catch (e: Exception) {
            fallback
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
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
        isStillMode = false
        lastSeekTargetMs = null
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
