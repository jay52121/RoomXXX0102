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
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.logic.analyzer.RoiLogAggregator
import com.example.roomxxx0102.logic.analyzer.YoloAnalyzer
import com.example.roomxxx0102.logic.analyzer.YoloPoseAnalyzer
import java.io.File
import java.io.FileInputStream
import kotlin.math.roundToInt

class VideoFeeder(
    private val context: Context,
    private val textureView: TextureView
) {
    private data class PendingSeekState(
        val beforeMs: Int,
        val deltaMs: Int,
        val captureAsStep: Boolean,
        var backwardGuardRetries: Int
    )

    data class StepSeekDebug(
        val beforeMs: Int,
        val targetMs: Int,
        val afterCallMs: Int,
        val deltaMs: Int,
        val issuedAtMs: Long,
        val baseDigest: String?,
        var nudgeApplied: Boolean = false,
        var nudgeCount: Int = 0,
        var nudgeDeltaMs: Int = 0,
        var nudgeBeforeMs: Int? = null,
        var nudgeAfterCallMs: Int? = null
    )

    var yoloAnalyzer: YoloAnalyzer? = null
    var poseAnalyzer: YoloPoseAnalyzer? = null
    
    var isPoseMode = false
    
    // 🔥 下一帧的 ROI，由 MainActivity 设置
    var nextFrameRoi: RectF? = null
    
    // 🔥 新增：静止模式开关
    private var isStillMode = false
    // 切到静止瞬间，短暂抑制 PoseStagnant 解锁，避免状态切换抖动导致误解锁
    private var suppressStagnantUnlockUntilMs: Long = 0L
    // 按“逐帧步进”时使用的时间步长（毫秒）；由视频 metadata 估算，失败时回退到 33ms
    private var frameStepMs = 33
    // 上一次进入分析时的播放位置，用于判断“时间是否真正前进”
    private var lastAnalyzedPositionMs: Int? = null
    private var lastAnalyzedFrameDigest: String? = null
    private var lastStepSeekDebug: StepSeekDebug? = null
    private var pendingForwardNudgeDebug: StepSeekDebug? = null
    private var pendingForwardNudgeBaseDigest: String? = null
    private var pendingForwardNudgeRemain: Int = 0
    private var pendingSeekState: PendingSeekState? = null
    private var lastSeekCompletePositionMs: Int? = null
    private var lastSeekCompleteAtMs: Long = 0L
    private var lastPlaybackDiagPosMs: Int? = null
    private var playbackStallCount: Int = 0
    // +1 帧补偿触发时回调给上层 UI，用于显示横幅提示。
    var onStepNudge: ((String) -> Unit)? = null

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
                val temporalAdvanced = computeTemporalAdvanced(mediaPlayer!!)
                val suppressStagnantUnlock =
                    isStillMode && System.currentTimeMillis() < suppressStagnantUnlockUntilMs
                val skipUnchangedStillFrame =
                    AppSettings.isStillStandardFrameEnabled &&
                        isStillMode &&
                        !mediaPlayer!!.isPlaying &&
                        !temporalAdvanced

                if (skipUnchangedStillFrame) {
                    // 开启“静止时使用标准帧”后：画面未推进则不喂帧
                    handler.postDelayed(this, 100)
                    return
                }
                val bitmap = textureView.bitmap
                if (bitmap != null) {
                    val currentPosMs = mediaPlayer!!.currentPosition
                    val frameDigest = computeFrameDigest(bitmap)
                    lastAnalyzedFrameDigest = frameDigest
                    RoiLogAggregator.updateFrameDigest(
                        digest = frameDigest,
                        positionMs = currentPosMs,
                        temporalAdvanced = temporalAdvanced
                    )
                    if (applyOneTimeForwardNudgeIfNeeded(frameDigest)) {
                        handler.postDelayed(this, 100)
                        return
                    }
                    val roi = nextFrameRoi
                    Thread {
                        if (isPoseMode) {
                            poseAnalyzer?.analyzeBitmapAndTrackPoses(
                                bitmap = bitmap,
                                roi = roi,
                                drawOnOverlay = true,
                                temporalAdvanced = temporalAdvanced,
                                suppressStagnantUnlock = suppressStagnantUnlock
                            )
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
        lastAnalyzedPositionMs = null

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
                        setOnSeekCompleteListener { mp ->
                            val currentPos = mp.currentPosition
                            lastSeekCompletePositionMs = mp.currentPosition
                            lastSeekCompleteAtMs = System.currentTimeMillis()
                            logPlayerDiag(
                                "seekComplete pos=$currentPos isPlaying=${mp.isPlaying} " +
                                    "stillMode=$isStillMode pending=${pendingSeekState != null}"
                            )
                            val pending = pendingSeekState
                            if (pending != null) {
                                if (
                                    pending.captureAsStep &&
                                    pending.deltaMs < 0 &&
                                    pending.backwardGuardRetries > 0 &&
                                    currentPos >= pending.beforeMs
                                ) {
                                    pending.backwardGuardRetries -= 1
                                    val correctedTarget = (pending.beforeMs - 1).coerceAtLeast(0)
                                    mp.seekTo(
                                        correctedTarget.toLong(),
                                        MediaPlayer.SEEK_PREVIOUS_SYNC
                                    )
                                    return@setOnSeekCompleteListener
                                }
                                pendingSeekState = null
                            }
                            if (!isStillMode && !mp.isPlaying) {
                                forcePausedFrameRefresh(mp)
                            }
                        }
                        setOnPreparedListener { mp ->
                            Log.d("VideoFeeder", "✅ 视频准备就绪: ${mp.videoWidth}x${mp.videoHeight}")
                            adjustAspectRatio(mp.videoWidth, mp.videoHeight)
                            logPlayerDiag("prepared video=${mp.videoWidth}x${mp.videoHeight} duration=${mp.duration}")
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
        val wasStillMode = isStillMode
        isStillMode = isStill
        logPlayerDiag("setStillMode from=$wasStillMode to=$isStill")
        if (isStill && !wasStillMode) {
            // 对齐一次时间基准，避免切换状态的临界帧被误判成“连续静止”
            mediaPlayer?.let { mp -> lastAnalyzedPositionMs = mp.currentPosition }
            suppressStagnantUnlockUntilMs = System.currentTimeMillis() + 500L
        }
        if (!isStill) {
            suppressStagnantUnlockUntilMs = 0L
        }
    }

    fun pause() {
        mediaPlayer?.let {
            val beforePos = runCatching { it.currentPosition }.getOrElse { -1 }
            val beforePlaying = it.isPlaying
            if (it.isPlaying) {
                it.pause()
            }
            logPlayerDiag(
                "pause beforePos=$beforePos afterPos=${runCatching { it.currentPosition }.getOrElse { -1 }} " +
                    "beforePlaying=$beforePlaying afterPlaying=${it.isPlaying}"
            )
        }
    }

    fun resume() {
        mediaPlayer?.let {
            val beforePos = runCatching { it.currentPosition }.getOrElse { -1 }
            val beforePlaying = it.isPlaying
            if (!it.isPlaying) {
                it.start()
            }
            logPlayerDiag(
                "resume beforePos=$beforePos afterPos=${runCatching { it.currentPosition }.getOrElse { -1 }} " +
                    "beforePlaying=$beforePlaying afterPlaying=${it.isPlaying}"
            )
        }
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }

    fun seekForward(seconds: Int) {
        clearForwardStepNudgeState()
        seekByMs(
            deltaMs = seconds * 1000,
            seekMode = MediaPlayer.SEEK_NEXT_SYNC
        )
    }

    fun seekBackward(seconds: Int) {
        clearForwardStepNudgeState()
        seekByMs(
            deltaMs = -seconds * 1000,
            seekMode = MediaPlayer.SEEK_PREVIOUS_SYNC
        )
    }

    // “按帧”本质上仍是时间 seek：MediaPlayer 不提供逐帧接口
    fun seekForwardFrame(): StepSeekDebug? {
        val debug = seekByMs(
            deltaMs = frameStepMs,
            captureAsStep = true,
            baseDigest = lastAnalyzedFrameDigest
        )
        pendingForwardNudgeDebug = debug
        pendingForwardNudgeBaseDigest = debug?.baseDigest
        pendingForwardNudgeRemain = if (debug != null) 2 else 0
        return debug
    }

    fun seekBackwardFrame(): StepSeekDebug? {
        clearForwardStepNudgeState()
        return seekByMs(-frameStepMs, captureAsStep = true)
    }

    fun seekToMs(positionMs: Int, seekMode: Int = MediaPlayer.SEEK_CLOSEST): StepSeekDebug? {
        val current = getCurrentPositionMs() ?: return null
        val delta = positionMs - current
        return seekByMs(deltaMs = delta, seekMode = seekMode)
    }

    private fun seekByMs(
        deltaMs: Int,
        captureAsStep: Boolean = false,
        baseDigest: String? = null,
        seekMode: Int = MediaPlayer.SEEK_CLOSEST
    ): StepSeekDebug? {
        mediaPlayer?.let { mp ->
            val before = mp.currentPosition
            val target = (before + deltaMs).coerceIn(0, mp.duration)
            pendingSeekState = PendingSeekState(
                beforeMs = before,
                deltaMs = deltaMs,
                captureAsStep = captureAsStep,
                backwardGuardRetries = if (captureAsStep && deltaMs < 0) 1 else 0
            )
            logPlayerDiag(
                "seekByMs request delta=$deltaMs mode=$seekMode captureAsStep=$captureAsStep " +
                    "before=$before target=$target duration=${mp.duration} isPlaying=${mp.isPlaying}"
            )
            mp.seekTo(target.toLong(), seekMode)
            val debug = StepSeekDebug(
                beforeMs = before,
                targetMs = target,
                afterCallMs = mp.currentPosition,
                deltaMs = deltaMs,
                issuedAtMs = System.currentTimeMillis(),
                baseDigest = baseDigest
            )
            if (captureAsStep) {
                lastStepSeekDebug = debug
            }
            return debug
        }
        return null
    }

    private fun clearForwardStepNudgeState() {
        pendingForwardNudgeDebug = null
        pendingForwardNudgeBaseDigest = null
        pendingForwardNudgeRemain = 0
    }

    /**
     * 清理逐帧步进/补偿相关的短期状态。
     * 用于从静止/暂停切回播放时，避免历史 seek 残留继续拉扯画面。
     */
    fun clearStepSeekTransientState() {
        clearForwardStepNudgeState()
        pendingSeekState = null
        lastStepSeekDebug = null
    }

    private fun forcePausedFrameRefresh(mp: MediaPlayer) {
        try {
            mp.start()
            mp.pause()
        } catch (e: IllegalStateException) {
            Log.w("VideoFeeder", "forcePausedFrameRefresh skipped: ${e.message}")
        }
    }

    fun peekLastStepSeekDebug(): StepSeekDebug? = lastStepSeekDebug

    fun getCurrentPositionMs(): Int? {
        val mp = mediaPlayer ?: return null
        return try {
            mp.currentPosition
        } catch (_: IllegalStateException) {
            Log.w("VideoFeeder", "getCurrentPositionMs skipped: MediaPlayer state invalid")
            null
        }
    }

    fun getDurationMs(): Int? {
        val mp = mediaPlayer ?: return null
        return try {
            mp.duration
        } catch (_: IllegalStateException) {
            Log.w("VideoFeeder", "getDurationMs skipped: MediaPlayer state invalid")
            null
        }
    }

    fun getLastSeekCompletePositionMs(): Int? = lastSeekCompletePositionMs

    fun getLastSeekCompleteAtMs(): Long = lastSeekCompleteAtMs

    fun getFrameStepMs(): Int = frameStepMs

    /**
     * 仅针对 +1 帧：
     * 若本次 seek 后取到的帧摘要仍与 seek 前一致，则自动补 +10ms。
     * 最多补两次，每次补偿都会回调上层显示横幅提示。
     * 返回 true 表示本轮已执行补偿，应跳过当前帧分析等待下一轮。
     */
    private fun applyOneTimeForwardNudgeIfNeeded(currentDigest: String): Boolean {
        val pending = pendingForwardNudgeDebug ?: return false
        if (pendingForwardNudgeRemain <= 0) {
            pendingForwardNudgeDebug = null
            pendingForwardNudgeBaseDigest = null
            return false
        }
        val baseline = pendingForwardNudgeBaseDigest ?: pending.baseDigest ?: return false
        if (currentDigest != baseline) {
            pendingForwardNudgeDebug = null
            pendingForwardNudgeBaseDigest = null
            pendingForwardNudgeRemain = 0
            return false
        }
        mediaPlayer?.let { mp ->
            val before = mp.currentPosition
            val target = (before + 10).coerceIn(0, mp.duration)
            mp.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            pending.nudgeApplied = true
            pending.nudgeCount += 1
            pending.nudgeDeltaMs += 10
            if (pending.nudgeBeforeMs == null) {
                pending.nudgeBeforeMs = before
            }
            pending.nudgeAfterCallMs = mp.currentPosition
            lastStepSeekDebug = pending
            pendingForwardNudgeRemain -= 1
            if (pendingForwardNudgeRemain <= 0) {
                pendingForwardNudgeDebug = null
                pendingForwardNudgeBaseDigest = null
            }
            onStepNudge?.invoke("步进补偿 +10ms (第${pending.nudgeCount}次)")
            return true
        }
        return false
    }

    /**
     * 仅用于“追踪状态机计数是否应推进”判断：
     * - 播放中：视为时间前进
     * - 静止中：只有 currentPosition 变化才视为前进（例如 ±1帧）
     */
    private fun computeTemporalAdvanced(mp: MediaPlayer): Boolean {
        val currentPos = mp.currentPosition
        val lastDiagPos = lastPlaybackDiagPosMs
        if (mp.isPlaying && lastDiagPos != null) {
            val delta = currentPos - lastDiagPos
            playbackStallCount = if (delta <= 0) playbackStallCount + 1 else 0
            if (delta < -80 || playbackStallCount >= 4) {
                logPlayerDiag(
                    "playLoopAnomaly pos=$currentPos last=$lastDiagPos delta=$delta " +
                        "stallCount=$playbackStallCount isPlaying=${mp.isPlaying} stillMode=$isStillMode"
                )
            }
        } else {
            playbackStallCount = 0
        }
        lastPlaybackDiagPosMs = currentPos
        val advanced = if (mp.isPlaying) {
            true
        } else {
            val last = lastAnalyzedPositionMs
            last == null || currentPos != last
        }
        lastAnalyzedPositionMs = currentPos
        return advanced
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

    /**
     * 轻量帧摘要：固定网格采样亮度并做 FNV-1a 哈希。
     * 用于判断“+1帧后是否拿到重复帧/近似帧”。
     */
    private fun computeFrameDigest(bitmap: android.graphics.Bitmap): String {
        val sampleCount = 8
        val stepX = (bitmap.width - 1).coerceAtLeast(1).toFloat() / (sampleCount - 1)
        val stepY = (bitmap.height - 1).coerceAtLeast(1).toFloat() / (sampleCount - 1)
        var hash = -3750763034362895579L // FNV-1a 64 offset basis (signed)
        val prime = 1099511628211L
        for (sy in 0 until sampleCount) {
            val py = (sy * stepY).toInt().coerceIn(0, bitmap.height - 1)
            for (sx in 0 until sampleCount) {
                val px = (sx * stepX).toInt().coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(px, py)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val gray = (r * 30 + g * 59 + b * 11) / 100
                hash = hash xor gray.toLong()
                hash *= prime
            }
        }
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    fun stop() {
        isAnalyzing = false
        isStillMode = false
        suppressStagnantUnlockUntilMs = 0L
        lastAnalyzedPositionMs = null
        lastPlaybackDiagPosMs = null
        playbackStallCount = 0
        lastAnalyzedFrameDigest = null
        lastStepSeekDebug = null
        pendingForwardNudgeDebug = null
        pendingForwardNudgeBaseDigest = null
        pendingForwardNudgeRemain = 0
        pendingSeekState = null
        lastSeekCompletePositionMs = null
        lastSeekCompleteAtMs = 0L
        handler.removeCallbacks(analyzeRunnable)
        val mp = mediaPlayer
        mediaPlayer = null
        try {
            if (mp?.isPlaying == true) {
                mp.stop()
            }
            mp?.release()
        } catch (e: Exception) {}
    }

    private fun logPlayerDiag(message: String) {
        if (!AppSettings.isPauseDecisionLogOnSwitchEnabled) return
        Log.i("RoomPlayerDiag", message)
    }
}
