package com.example.roomxxx0102.logic.video

import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalFrameHub
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateRuntime
import com.example.roomxxx0102.logic.roomalgorithm.gate.GateSamplingPermit
import android.os.SystemClock
import android.content.Context
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import com.example.roomxxx0102.data.repository.AppSettings
import com.example.roomxxx0102.logic.analyzer.HandSmokeTester
import com.example.roomxxx0102.logic.analyzer.RoiLogAggregator
import com.example.roomxxx0102.logic.analyzer.YoloAnalyzer
import com.example.roomxxx0102.logic.analyzer.YoloPoseAnalyzer
import com.example.roomxxx0102.utils.AppLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class VideoFeeder(
    private val context: Context,
    private val textureView: TextureView
) {
    private data class PendingSeekState(
        val beforeMs: Int,
        val deltaMs: Int
    )

    data class StepSeekDebug(
        val beforeMs: Int,
        var targetMs: Int,
        var afterCallMs: Int,
        var deltaMs: Int,
        val issuedAtMs: Long,
        val baseDigest: String?,
        var nudgeApplied: Boolean = false,
        var nudgeCount: Int = 0,
        var nudgeDeltaMs: Int = 0,
        var nudgeBeforeMs: Int? = null,
        var nudgeAfterCallMs: Int? = null,
        var resolved: Boolean = false,
        var success: Boolean = false,
        var failureReason: String? = null,
        var confirmedPosMs: Int? = null,
        var confirmedDiffScore: Float? = null,
        var lastCandidateDiffScore: Float? = null,
        var candidateCount: Int = 0,
        var successCandidateIndex: Int? = null
    )

    var yoloAnalyzer: YoloAnalyzer? = null
    var poseAnalyzer: YoloPoseAnalyzer? = null
    var handSmokeTester: HandSmokeTester? = null
    
    var isPoseMode = false
    
    // 🔥 下一帧的 pose ROI，由 MainActivity 设置
    var nextFrameRoi: RectF? = null
    // 🔥 下一帧的 hand ROI，由 MainActivity 设置；为空时回退到 pose ROI
    var nextHandFrameRoi: RectF? = null
    
    // 🔥 新增：静止模式开关
    private var isStillMode = false
    // 切到静止瞬间，短暂抑制 PoseStagnant 解锁，避免状态切换抖动导致误解锁
    private var suppressStagnantUnlockUntilMs: Long = 0L
    // 按“逐帧步进”时使用的时间步长（毫秒）；由视频 metadata 估算，失败时回退到 33ms
    private var frameStepMs = 33
    // 上一次进入分析时的播放位置，用于判断“时间是否真正前进”
    private var lastAnalyzedPositionMs: Int? = null
    private var lastStepSeekDebug: StepSeekDebug? = null
    private var pendingSeekState: PendingSeekState? = null
    private var lastSeekCompletePositionMs: Int? = null
    private var lastSeekCompleteAtMs: Long = 0L
    private var lastPlaybackDiagPosMs: Int? = null
    private var lastAppliedVideoLayout: Pair<Int, Int>? = null
    @Volatile
    private var lastAnalysisPositionMs: Int? = null
    private var playbackStallCount: Int = 0
    private var bitmapDiagCounter: Int = 0
    private var lastBitmapDiagDigest: String? = null
    private var observedFrameSeq: Long = 0L
    private var lastObservedFrameDigest: String? = null
    // +1 帧补偿触发时回调给上层 UI，用于显示横幅提示。
    var onStepNudge: ((String) -> Unit)? = null
    /** 诊断回放使用：关闭循环，并在真实播放到末尾后生成一次完成回调。 */
    var loopPlayback: Boolean = true
    var onPlaybackCompleted: (() -> Unit)? = null
    private var playbackCompletionDelivered = false
    private val frameStepController = FrameStepController(
        getDurationMs = { videoPlayer?.getDurationMs() },
        issueSeekToMs = { targetMs ->
            videoPlayer?.seekTo(targetMs.toLong(), VideoSeekMode.CLOSEST)
        }
    )

    private var videoPlayer: VideoPlayerFacade? = null
    private var isAnalyzing = false
    private val handler = Handler(Looper.getMainLooper())
    // 推理必须串行：TFLite Interpreter/GPU Delegate 非线程安全，禁止并发 run()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val inferenceInFlight = AtomicBoolean(false)
    private var inferenceSkipStreak = 0
    private val gateSampling = GateSamplingPermit()

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            // 如果分析开关关闭，则彻底停止
            val player = videoPlayer
            if (!isAnalyzing || player == null) {
                return
            }
            if (!loopPlayback && !playbackCompletionDelivered && !isStillMode && !player.isPlaying()) {
                val duration = player.getDurationMs() ?: -1
                val position = player.getCurrentPositionMs() ?: -1
                val endTolerance = maxOf(180, frameStepMs * 3)
                if (duration > 0 && position >= duration - endTolerance) {
                    playbackCompletionDelivered = true
                    isAnalyzing = false
                    onPlaybackCompleted?.invoke()
                    return
                }
            }
            
            // 🔥 新逻辑：只要正在播放，或者处于静止模式，就继续识别
            if (player.isPlaying() || isStillMode) {
                val temporalAdvanced = computeTemporalAdvanced(player)
                val hasPendingStep = frameStepController.hasPendingStep()
                val suppressStagnantUnlock =
                    isStillMode && System.currentTimeMillis() < suppressStagnantUnlockUntilMs
                val skipUnchangedStillFrame =
                    AppSettings.isStillStandardFrameEnabled &&
                        isStillMode &&
                        !player.isPlaying() &&
                        !hasPendingStep &&
                        !temporalAdvanced

                if (skipUnchangedStillFrame) {
                    // 开启“静止时使用标准帧”后：画面未推进则不喂帧
                    handler.postDelayed(this, 100)
                    return
                }
                if (GateRuntime.enabled && isPoseMode && player.isPlaying() && !hasPendingStep && !isStillMode) {
                    sampleGateFrame(player, temporalAdvanced, suppressStagnantUnlock)
                    handler.postDelayed(this, 8)
                    return
                }
                val bitmap = textureView.bitmap
                if (bitmap != null) {
                    val currentPosMs = player.getCurrentPositionMs() ?: 0
                    val portalStamp = PortalFrameHub.capture(bitmap, currentPosMs.toLong(), temporalAdvanced)
                    val poseRoi = nextFrameRoi
                    val handRoi = nextHandFrameRoi ?: poseRoi
                    Log.i(
                        "HandSmokeTester",
                        "HSMOKE|CALL_SITE|bitmap=${bitmap.width}x${bitmap.height}" +
                            "|detectorRoi=${handRoi ?: "FULL_FRAME"}"
                    )
                    handSmokeTester?.detect(bitmap, handRoi)
                    lastAnalysisPositionMs = currentPosMs
                    val frameSignature = FrameSignatureUtils.create(bitmap)
                    val frameDigest = frameSignature.summary
                    val frameSeqChanged = frameDigest != lastObservedFrameDigest
                    if (frameSeqChanged) {
                        observedFrameSeq += 1L
                        lastObservedFrameDigest = frameDigest
                    }
                    maybeLogBitmapDiag(bitmap, currentPosMs, frameDigest)
                    RoiLogAggregator.updateFrameDigest(
                        digest = frameDigest,
                        positionMs = currentPosMs,
                        temporalAdvanced = temporalAdvanced
                    )
                    if (hasPendingStep) {
                        AppLog.i(
                            "RoomStepFullDiag",
                            "bitmapObserved pos=$currentPosMs frameSeq=$observedFrameSeq changed=$frameSeqChanged " +
                                "digest=$frameDigest temporalAdvanced=$temporalAdvanced"
                        )
                        AppLog.i(
                            "RoomStepFullDiag",
                            "observePending pos=$currentPosMs temporalAdvanced=$temporalAdvanced " +
                                "frameSeq=$observedFrameSeq digest=$frameDigest"
                        )
                    }
                    if (frameStepController.onFrameObserved(currentPosMs, frameSignature)) {
                        handler.postDelayed(this, 100)
                        return
                    }
                    submitInferenceTask(
                        bitmap = bitmap,
                        roi = poseRoi,
                        temporalAdvanced = temporalAdvanced,
                        suppressStagnantUnlock = suppressStagnantUnlock,
                        frameStamp = portalStamp
                    )
                } else {
                    maybeLogBitmapDiag(null, player.getCurrentPositionMs() ?: -1, null)
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
        playbackCompletionDelivered = false
        lastAppliedVideoLayout = null
        frameStepController.resetAnchor("video_start")
        frameStepMs = estimateFrameStepMs(filePath, uri)
        lastAnalyzedPositionMs = null

        try {
            Log.d("VideoFeeder", "🎬 初始化 ExoVideoPlayer...")
            textureView.visibility = android.view.View.VISIBLE
            textureView.alpha = 0f

            if (filePath != null) {
                val file = File(filePath)
                if (!file.exists()) {
                    Log.e("VideoFeeder", "❌ 文件不存在: $filePath")
                    return
                }
            }

            videoPlayer = ExoVideoPlayer(context).apply {
                attachTextureView(textureView)
                setListener(object : PlayerEventListener {
                    override fun onReady() {
                        val duration = getDurationMs() ?: -1
                        logPlayerDiag("prepared videoReady=true duration=$duration")
                        isAnalyzing = true
                        handler.post(analyzeRunnable)
                    }

                    override fun onVideoSizeChanged(videoWidth: Int, videoHeight: Int) {
                        Log.d("VideoFeeder", "✅ 视频准备就绪: ${videoWidth}x${videoHeight}")
                        adjustAspectRatio(videoWidth, videoHeight)
                        logPlayerDiag(
                            "videoSizeChanged video=${videoWidth}x${videoHeight} duration=${getDurationMs() ?: -1}"
                        )
                    }

                    override fun onSeekComplete() {
                        val currentPos = getCurrentPositionMs() ?: return
                        lastSeekCompletePositionMs = currentPos
                        lastSeekCompleteAtMs = System.currentTimeMillis()
                        frameStepController.onSeekComplete(currentPos, lastSeekCompleteAtMs)
                        AppLog.i(
                            "RoomStepFullDiag",
                            "seekComplete pos=$currentPos frameSeq=$observedFrameSeq " +
                                "lastDigest=${lastObservedFrameDigest ?: "-"} " +
                                "playing=${isPlaying()} still=$isStillMode"
                        )
                        logPlayerDiag(
                            "seekComplete pos=$currentPos isPlaying=${isPlaying()} " +
                                "stillMode=$isStillMode pending=${pendingSeekState != null}"
                        )
                        pendingSeekState = null
                        if (isStillMode && !isPlaying() && frameStepController.hasPendingStep()) {
                            forcePausedFrameRefresh(this@apply)
                        }
                    }

                    override fun onError(error: Throwable) {
                        Log.e("VideoFeeder", "❌ ExoVideoPlayer 错误", error)
                    }
                })
                prepare(filePath = filePath, uri = uri, looping = loopPlayback)
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
            frameStepController.resetAnchor("enter_still")
            // 对齐一次时间基准，避免切换状态的临界帧被误判成“连续静止”
            videoPlayer?.getCurrentPositionMs()?.let { lastAnalyzedPositionMs = it }
            suppressStagnantUnlockUntilMs = System.currentTimeMillis() + 500L
        }
        if (!isStill) {
            suppressStagnantUnlockUntilMs = 0L
        }
    }

    fun pause() {
        videoPlayer?.let {
            val beforePos = it.getCurrentPositionMs() ?: -1
            val beforePlaying = it.isPlaying()
            if (it.isPlaying()) {
                it.pause()
            }
            logPlayerDiag(
                "pause beforePos=$beforePos afterPos=${it.getCurrentPositionMs() ?: -1} " +
                    "beforePlaying=$beforePlaying afterPlaying=${it.isPlaying()}"
            )
        }
    }

    fun resume() {
        videoPlayer?.let {
            val beforePos = it.getCurrentPositionMs() ?: -1
            val beforePlaying = it.isPlaying()
            if (!it.isPlaying()) {
                it.play()
            }
            logPlayerDiag(
                "resume beforePos=$beforePos afterPos=${it.getCurrentPositionMs() ?: -1} " +
                    "beforePlaying=$beforePlaying afterPlaying=${it.isPlaying()}"
            )
        }
    }

    fun isPlaying(): Boolean {
        return videoPlayer?.isPlaying() ?: false
    }

    fun seekForward(seconds: Int) {
        frameStepController.resetAnchor("seek_forward_${seconds}s")
        seekByMs(
            deltaMs = seconds * 1000,
            seekMode = VideoSeekMode.NEXT_SYNC
        )
    }

    fun seekBackward(seconds: Int) {
        frameStepController.resetAnchor("seek_backward_${seconds}s")
        seekByMs(
            deltaMs = -seconds * 1000,
            seekMode = VideoSeekMode.PREVIOUS_SYNC
        )
    }

    // “按帧”重定义为：前进到下一张明显不同的画面。
    fun seekForwardFrame(): StepSeekDebug? {
        AppLog.i(
            "RoomStepFullDiag",
            "stepRequest direction=1 pos=${videoPlayer?.getCurrentPositionMs() ?: -1} " +
                "frameSeq=$observedFrameSeq lastDigest=${lastObservedFrameDigest ?: "-"}"
        )
        val debug = frameStepController.stepForward(frameStepMs)
        lastStepSeekDebug = debug
        return debug
    }

    fun seekBackwardFrame(): StepSeekDebug? {
        AppLog.i(
            "RoomStepFullDiag",
            "stepRequest direction=-1 pos=${videoPlayer?.getCurrentPositionMs() ?: -1} " +
                "frameSeq=$observedFrameSeq lastDigest=${lastObservedFrameDigest ?: "-"}"
        )
        val debug = frameStepController.stepBackward(frameStepMs)
        lastStepSeekDebug = debug
        return debug
    }

    fun seekToMs(positionMs: Int, seekMode: Int = VideoSeekMode.CLOSEST): StepSeekDebug? {
        val current = getCurrentPositionMs() ?: return null
        val delta = positionMs - current
        frameStepController.resetAnchor("seek_to_${positionMs}ms")
        return seekByMs(deltaMs = delta, seekMode = seekMode)
    }

    private fun seekByMs(
        deltaMs: Int,
        seekMode: Int = VideoSeekMode.CLOSEST
    ): StepSeekDebug? {
        videoPlayer?.let { player ->
            val before = player.getCurrentPositionMs() ?: return null
            val duration = player.getDurationMs() ?: return null
            val target = (before + deltaMs).coerceIn(0, duration)
            pendingSeekState = PendingSeekState(
                beforeMs = before,
                deltaMs = deltaMs
            )
            logPlayerDiag(
                "seekByMs request delta=$deltaMs mode=$seekMode " +
                    "before=$before target=$target duration=$duration isPlaying=${player.isPlaying()}"
            )
            if (GateRuntime.enabled) PortalFrameHub.resetSource()
            player.seekTo(target.toLong(), seekMode)
            val debug = StepSeekDebug(
                beforeMs = before,
                targetMs = target,
                afterCallMs = player.getCurrentPositionMs() ?: before,
                deltaMs = deltaMs,
                issuedAtMs = System.currentTimeMillis(),
                baseDigest = null
            )
            return debug
        }
        return null
    }

    /**
     * 清理逐帧步进/补偿相关的短期状态。
     * 用于从静止/暂停切回播放时，避免历史 seek 残留继续拉扯画面。
     */
    fun clearStepSeekTransientState() {
        frameStepController.resetAnchor("clear_step_state")
        pendingSeekState = null
        lastStepSeekDebug = null
    }

    private fun forcePausedFrameRefresh(player: VideoPlayerFacade) {
        try {
            AppLog.i(
                "RoomStepFullDiag",
                "forceRefresh begin pos=${player.getCurrentPositionMs() ?: -1} playing=${player.isPlaying()}"
            )
            player.play()
            player.pause()
            AppLog.i(
                "RoomStepFullDiag",
                "forceRefresh end pos=${player.getCurrentPositionMs() ?: -1} playing=${player.isPlaying()}"
            )
        } catch (e: Exception) {
            Log.w("VideoFeeder", "forcePausedFrameRefresh skipped: ${e.message}")
        }
    }

    fun peekLastStepSeekDebug(): StepSeekDebug? = lastStepSeekDebug

    fun getCurrentPositionMs(): Int? {
        return videoPlayer?.getCurrentPositionMs()
    }

    fun getDurationMs(): Int? {
        return videoPlayer?.getDurationMs()
    }

    fun getLastSeekCompletePositionMs(): Int? = lastSeekCompletePositionMs

    fun getLastSeekCompleteAtMs(): Long = lastSeekCompleteAtMs

    fun getFrameStepMs(): Int = frameStepMs

    fun peekLastAnalysisPositionMs(): Int? = lastAnalysisPositionMs

    /**
     * 仅用于“追踪状态机计数是否应推进”判断：
     * - 播放中：视为时间前进
     * - 静止中：只有 currentPosition 变化才视为前进（例如 ±1帧）
     */
    private fun computeTemporalAdvanced(player: VideoPlayerFacade): Boolean {
        val currentPos = player.getCurrentPositionMs() ?: return false
        val lastDiagPos = lastPlaybackDiagPosMs
        if (player.isPlaying() && lastDiagPos != null) {
            val delta = currentPos - lastDiagPos
            playbackStallCount = if (delta <= 0) playbackStallCount + 1 else 0
            if (delta < -80 || playbackStallCount >= 4) {
                logPlayerDiag(
                    "playLoopAnomaly pos=$currentPos last=$lastDiagPos delta=$delta " +
                        "stallCount=$playbackStallCount isPlaying=${player.isPlaying()} stillMode=$isStillMode"
                )
            }
        } else {
            playbackStallCount = 0
        }
        lastPlaybackDiagPosMs = currentPos
        val advanced = if (player.isPlaying()) {
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
        if (screenW == 0 || screenH == 0) {
            handler.post { adjustAspectRatio(videoW, videoH) }
            return
        }

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
            if (lastAppliedVideoLayout == finalW to finalH && textureView.alpha == 1f) {
                return@post
            }
            params.width = finalW
            params.height = finalH
            textureView.layoutParams = params
            lastAppliedVideoLayout = finalW to finalH
            textureView.visibility = android.view.View.VISIBLE
            textureView.alpha = 1f
        }
    }

    /**
     * 轻量帧摘要：固定网格采样亮度并做 FNV-1a 哈希。
     * 用于判断“+1帧后是否拿到重复帧/近似帧”。
     */
    private fun maybeLogBitmapDiag(
        bitmap: android.graphics.Bitmap?,
        currentPosMs: Int,
        frameDigest: String?
    ) {
        bitmapDiagCounter += 1
        val digestChanged = frameDigest != null && frameDigest != lastBitmapDiagDigest
        if (bitmapDiagCounter % 10 != 0 && bitmap != null && !digestChanged) {
            return
        }
        Log.i(
            "RoomBitmapDiag",
            "texture x=${textureView.x} y=${textureView.y} " +
                "w=${textureView.width} h=${textureView.height} " +
                "bitmapNull=${bitmap == null} " +
                "bitmap=${bitmap?.width ?: -1}x${bitmap?.height ?: -1} " +
                "digest=${frameDigest ?: "-"} changed=$digestChanged posMs=$currentPosMs"
        )
        if (frameDigest != null) {
            lastBitmapDiagDigest = frameDigest
        }
    }

    fun stop() {
        PortalFrameHub.resetSource()
        isAnalyzing = false
        isStillMode = false
        suppressStagnantUnlockUntilMs = 0L
        lastAnalyzedPositionMs = null
        lastPlaybackDiagPosMs = null
        playbackStallCount = 0
        frameStepController.resetAnchor("stop")
        lastStepSeekDebug = null
        pendingSeekState = null
        lastSeekCompletePositionMs = null
        lastSeekCompleteAtMs = 0L
        lastAnalysisPositionMs = null
        inferenceSkipStreak = 0
        bitmapDiagCounter = 0
        lastBitmapDiagDigest = null
        observedFrameSeq = 0L
        lastObservedFrameDigest = null
        handler.removeCallbacks(analyzeRunnable)
        val player = videoPlayer
        videoPlayer = null
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {}
    }

    /** Reserve capacity BEFORE GPU readback. Busy periods contain no captures or pending image tasks. */
    private fun sampleGateFrame(player: VideoPlayerFacade, temporalAdvanced: Boolean, suppressUnlock: Boolean) {
        val start = SystemClock.elapsedRealtime()
        if (!gateSampling.acquire(start, GateRuntime.sampleMs)) {
            GateRuntime.skipped = gateSampling.skipped
            return
        }
        if (!inferenceInFlight.compareAndSet(false, true)) { gateSampling.release(); return }
        var submitted = false
        try {
            if (!textureView.isAvailable || textureView.width <= 0 || textureView.height <= 0) return
            val originalEdge = maxOf(textureView.width, textureView.height)
            // V4 readback resolution is explicit; use 2560 when testing small hand/ROI details.
            val edge = GateRuntime.captureEdge
            val scale = minOf(1.0, edge.toDouble() / originalEdge)
            val w = (textureView.width * scale).toInt().coerceAtLeast(2)
            val h = (textureView.height * scale).toInt().coerceAtLeast(2)
            val bitmap = textureView.getBitmap(w, h) ?: return
            val pos = player.getCurrentPositionMs() ?: 0
            val stamp = PortalFrameHub.capture(bitmap, pos.toLong(), temporalAdvanced)
            val roi = nextFrameRoi?.let(::RectF)
            val handRoi = nextHandFrameRoi?.let(::RectF) ?: roi
            GateRuntime.captureCostMs = SystemClock.elapsedRealtime() - start
            lastAnalysisPositionMs = pos
            handSmokeTester?.detect(bitmap, handRoi)
            inferenceExecutor.execute {
                try {
                    if (stamp.epoch != PortalFrameHub.epoch) return@execute
                    poseAnalyzer?.analyzeBitmapAndTrackPoses(bitmap, roi, drawOnOverlay = true,
                        temporalAdvanced = temporalAdvanced, suppressStagnantUnlock = suppressUnlock, frameStamp = stamp)
                } catch (e: Exception) {
                    Log.e("PortalV4", "sample failed; not an empty detection", e)
                } finally {
                    GateRuntime.pipelineCostMs = SystemClock.elapsedRealtime() - start
                    inferenceInFlight.set(false)
                    gateSampling.release()
                }
            }
            submitted = true
        } catch (e: Exception) {
            Log.e("PortalV4", "capture failed", e)
        } finally {
            if (!submitted) { inferenceInFlight.set(false); gateSampling.release() }
        }
    }

    private fun submitInferenceTask(
        bitmap: android.graphics.Bitmap,
        roi: RectF?,
        temporalAdvanced: Boolean,
        suppressStagnantUnlock: Boolean,
        frameStamp: PortalFrameHub.Stamp
    ) {
        if (!inferenceInFlight.compareAndSet(false, true)) {
            inferenceSkipStreak += 1
            if (inferenceSkipStreak == 10 || inferenceSkipStreak % 30 == 0) {
                logPlayerDiag(
                    "inferenceBackpressure " +
                        "skipStreak=$inferenceSkipStreak " +
                        "poseMode=$isPoseMode stillMode=$isStillMode"
                )
            }
            return
        }
        inferenceSkipStreak = 0
        inferenceExecutor.execute {
            try {
                Log.i(
                    "RoomInferenceDiag",
                    "submit start poseMode=$isPoseMode bitmap=${bitmap.width}x${bitmap.height} " +
                        "roi=${roi ?: "-"} temporalAdvanced=$temporalAdvanced suppress=$suppressStagnantUnlock"
                )
                if (isPoseMode) {
                    poseAnalyzer?.analyzeBitmapAndTrackPoses(
                        bitmap = bitmap,
                        roi = roi,
                        drawOnOverlay = true,
                        temporalAdvanced = temporalAdvanced,
                        suppressStagnantUnlock = suppressStagnantUnlock,
                        frameStamp = frameStamp
                    )
                } else {
                    yoloAnalyzer?.detectOnBitmap(bitmap, drawOnOverlay = true)
                }
                Log.i("RoomInferenceDiag", "submit end poseMode=$isPoseMode")
            } catch (t: Throwable) {
                Log.e("RoomInferenceDiag", "submit failed poseMode=$isPoseMode", t)
                Log.e("VideoFeeder", "inference task failed", t)
            } finally {
                inferenceInFlight.set(false)
            }
        }
    }

    private fun logPlayerDiag(message: String) {
        if (!AppSettings.isPauseDecisionLogOnSwitchEnabled) return
        Log.i("RoomPlayerDiag", message)
    }
}
