package com.example.roomxxx_vocie

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.example.roomxxx_vocie.audio.AudioFrame
import com.example.roomxxx_vocie.audio.AudioRecordSource
import com.example.roomxxx_vocie.audio.AudioSource
import com.example.roomxxx_vocie.audio.SilenceDetector
import com.example.roomxxx_vocie.kws.KwsEngine
import com.example.roomxxx_vocie.kws.SherpaKwsEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class KwsControllerImpl(
    context: Context,
    private val audioSource: AudioSource = AudioRecordSource(context.applicationContext),
    private val engine: KwsEngine = SherpaKwsEngine()
) : KwsController {
    private val appContext = context.applicationContext

    private var listener: KwsListener? = null
    // 状态回调：用于把“开始/停止/静音重置/冷却开始/冷却结束”等信息主动抛给 UI。
    private var statusListener: ((String) -> Unit)? = null
    // 音频帧回调：用于 UI 侧实时展示电平表，避免与监听争用麦克风。
    private var audioFrameListener: ((AudioFrame) -> Unit)? = null
    // 最近一次命中时的延迟拆解文本，便于 UI 读取并展示。
    private var lastDelayDetail: String = ""
    private var state: KwsState = KwsState.IDLE
    private lateinit var config: KwsConfig
    private var cooldownUntilMs: Long = 0
    private var lastNonSilentMs: Long = 0
    private var lastCaptureMs: Long = 0
    // 最近一次电平超过 -30dBFS 的音频时间，用于估算音频延迟。
    private var lastPeakOverCaptureMs: Long = 0
    // 最近一次帧的音频时间，用于 skew 自检。
    private var lastFrameCaptureMs: Long = 0
    // WARN 上升沿检测，避免持续刷日志。
    private var warnActive: Boolean = false
    // 上升沿/回滞计时，避免在阈值附近抖动。
    private var warnOverSinceMs: Long = 0
    private var warnClearSinceMs: Long = 0
    // WARN 最小打印间隔，避免抖动反复刷屏。
    private var lastWarnLogMs: Long = 0
    // 实例标识：用于排查是否重复创建导致日志重复。
    private var instanceId: Long = 0
    // 静音检测器：用于长静音后清空上下文，避免跨很久的拼接触发。
    private val silenceDetector = SilenceDetector()
    // 统计窗口：用于每秒输出一次平均/最大耗时。
    private var statWindowStartMs: Long = 0
    private var statCount: Int = 0
    private var statReadGapSum: Long = 0
    private var statReadGapMax: Long = 0
    private var statProcCostSum: Long = 0
    private var statProcCostMax: Long = 0
    private var statProcCpuSum: Long = 0
    private var statProcCpuMax: Long = 0
    private var statBacklogSum: Long = 0
    private var statBacklogMax: Long = 0
    private var statDispatchSum: Long = 0
    private var statDispatchMax: Long = 0
    private var statBacklogFramesSum: Long = 0
    private var statBacklogFramesMax: Long = 0
    // 帧队列：用于将音频回调与 KWS 消费解耦，便于做积压丢帧保险丝。
    private var frameChannel: Channel<AudioFrame>? = null
    private val pendingCount = AtomicInteger(0)
    private var consumeJob: Job? = null
    // 丢帧日志节流，避免反复刷屏。
    private var lastDropLogMs: Long = 0
    // 调试阻塞：用于人为制造积压，验证丢帧保险丝。
    @Volatile
    private var debugBlockMs: Long = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false

    override fun setListener(listener: KwsListener?) {
        this.listener = listener
    }

    /**
     * 仅实现类提供的状态回调，不改变稳定接口。
     * message 为中文短文本，便于 App 直接展示。
     */
    fun setStatusListener(listener: ((String) -> Unit)?) {
        this.statusListener = listener
    }

    /**
     * 仅实现类提供的音频帧回调，不改变稳定接口。
     * frame 为实时 PCM 帧，可用于电平表或调试。
     */
    fun setAudioFrameListener(listener: ((AudioFrame) -> Unit)?) {
        this.audioFrameListener = listener
    }

    /**
     * 对外暴露的监听开关接口：主应用可直接调用此方法控制监听。
     * enabled=true 等价于 start(config)，enabled=false 等价于 stop()。
     */
    fun setListeningEnabled(enabled: Boolean, config: KwsConfig = KwsConfig()) {
        if (enabled) {
            start(config)
        } else {
            stop()
        }
    }

    /**
     * 仅实现类提供的调试入口：人为阻塞消费线程，用于制造积压并验证丢帧保险丝。
     */
    fun triggerDebugBlock(blockMs: Long) {
        if (blockMs <= 0) {
            return
        }
        debugBlockMs = blockMs
    }

    /**
     * 获取最近一次命中的延迟拆解文本（delay/readGap/procCost/backlog）。
     */
    fun getLastDelayDetail(): String = lastDelayDetail

    override fun start(config: KwsConfig) {
        if (state != KwsState.IDLE) {
            stop()
        }

        this.config = config
        state = KwsState.LISTENING
        cooldownUntilMs = 0
        lastNonSilentMs = System.currentTimeMillis()
        lastCaptureMs = 0
        lastPeakOverCaptureMs = 0
        lastFrameCaptureMs = 0
        warnActive = false
        warnOverSinceMs = 0
        warnClearSinceMs = 0
        lastWarnLogMs = 0
        instanceId += 1
        statWindowStartMs = System.currentTimeMillis()
        statCount = 0
        statReadGapSum = 0
        statReadGapMax = 0
        statProcCostSum = 0
        statProcCostMax = 0
        statProcCpuSum = 0
        statProcCpuMax = 0
        statBacklogSum = 0
        statBacklogMax = 0
        statDispatchSum = 0
        statDispatchMax = 0
        statBacklogFramesSum = 0
        statBacklogFramesMax = 0
        lastDropLogMs = 0
        pendingCount.set(0)
        frameChannel = Channel(Channel.UNLIMITED)
        consumeJob = scope.launch {
            val channel = frameChannel ?: return@launch
            consumeLoop(channel)
        }

        try {
            engine.init(appContext, config)
            engine.start()
            audioSource.start(config) { frame ->
                // 回调线程只负责入队，KWS 处理在独立消费线程里完成。
                audioFrameListener?.invoke(frame)
                enqueueFrame(frame)
            }
            running = true
            statusListener?.invoke("开始监听")
        } catch (e: Exception) {
            state = KwsState.ERROR
            stop()
            state = KwsState.ERROR
            Log.e(TAG, "KwsController start failed", e)
        }
    }

    override fun stop() {
        if (!running && state == KwsState.IDLE) {
            return
        }
        audioSource.stop()
        engine.stop()
        state = KwsState.IDLE
        cooldownUntilMs = 0
        lastNonSilentMs = 0
        lastCaptureMs = 0
        lastPeakOverCaptureMs = 0
        lastFrameCaptureMs = 0
        warnActive = false
        warnOverSinceMs = 0
        warnClearSinceMs = 0
        lastWarnLogMs = 0
        lastDropLogMs = 0
        debugBlockMs = 0
        pendingCount.set(0)
        consumeJob?.cancel()
        consumeJob = null
        frameChannel?.close()
        frameChannel = null
        statWindowStartMs = 0
        statCount = 0
        statReadGapSum = 0
        statReadGapMax = 0
        statProcCostSum = 0
        statProcCostMax = 0
        statProcCpuSum = 0
        statProcCpuMax = 0
        statBacklogSum = 0
        statBacklogMax = 0
        statDispatchSum = 0
        statDispatchMax = 0
        statBacklogFramesSum = 0
        statBacklogFramesMax = 0
        running = false
        statusListener?.invoke("停止监听")
    }

    override fun getState(): KwsState = state

    // 入队：仅在音频回调线程调用，避免直接在回调中做重计算。
    private fun enqueueFrame(frame: AudioFrame) {
        val channel = frameChannel ?: return
        pendingCount.incrementAndGet()
        val result = channel.trySend(frame)
        if (result.isFailure) {
            // 发送失败时回退计数，避免队列深度失真。
            pendingCount.decrementAndGet()
        }
    }

    // KWS 消费线程：从队列取帧并处理。
    private suspend fun consumeLoop(channel: Channel<AudioFrame>) {
        while (true) {
            val frame = channel.receiveCatching().getOrNull() ?: return
            pendingCount.decrementAndGet()
            val blockMs = debugBlockMs
            if (blockMs > 0L) {
                // 调试阻塞：用于制造积压，验证丢帧保险丝。
                debugBlockMs = 0
                Thread.sleep(blockMs)
            }
            val frameToProcess = applyBacklogFuse(frame, channel)
            onAudioFrame(frameToProcess)
        }
    }

    // 积压丢帧保险丝：触发时只保留最新 1 帧，其余全部丢弃。
    private fun applyBacklogFuse(frame: AudioFrame, channel: Channel<AudioFrame>): AudioFrame {
        val nowMonoMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
        val dispatchDelayMs = nowMonoMs - frame.captureMs
        val backlogFrames = (dispatchDelayMs / 100L).toInt()
        val queueDepthBefore = pendingCount.get() + 1
        val shouldDrop = backlogFrames >= config.dropBacklogFrames ||
            dispatchDelayMs >= config.dropDispatchDelayMs ||
            queueDepthBefore >= config.dropQueueDepth
        if (!shouldDrop || queueDepthBefore <= 1) {
            return frame
        }
        var latest = frame
        while (true) {
            val result = channel.tryReceive().getOrNull() ?: break
            latest = result
            pendingCount.decrementAndGet()
        }
        val dropped = queueDepthBefore - 1
        val queueDepthAfter = pendingCount.get() + 1
        val nowAfterMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
        val dispatchDelayAfterMs = nowAfterMs - latest.captureMs
        val backlogFramesAfter = (dispatchDelayAfterMs / 100L).toInt()
        logDropIfNeeded(
            dropped,
            queueDepthBefore,
            dispatchDelayMs,
            backlogFrames,
            queueDepthAfter,
            dispatchDelayAfterMs,
            backlogFramesAfter,
            nowAfterMs
        )
        return latest
    }

    // 音频帧处理：严格按“静音重置 → 冷却判断 → KWS”顺序执行。
    private fun onAudioFrame(frame: AudioFrame) {
        if (state != KwsState.LISTENING) {
            return
        }
        try {
            val nowMs = System.currentTimeMillis()
            val processStartMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
            val readGapMs = if (lastCaptureMs > 0L) {
                frame.captureMs - lastCaptureMs
            } else {
                0L
            }
            lastCaptureMs = frame.captureMs
            lastFrameCaptureMs = frame.captureMs
            // 记录最近一次电平超过 -30dBFS 的时间，用于估算总延迟。
            if (isPeakOverThreshold(frame.pcm)) {
                lastPeakOverCaptureMs = frame.captureMs
            }
            // 先计算当前帧是否静音，以决定是否需要清空上下文。
            val silent = silenceDetector.isSilent(frame.pcm)
            if (!silent) {
                lastNonSilentMs = nowMs
            } else if (nowMs - lastNonSilentMs >= config.silenceResetMs) {
                engine.resetStream()
                lastNonSilentMs = nowMs
                Log.i(TAG, "KWS silence reset")
                // 静音重置提示：用于区分“静音导致的重置”与“命中后的冷却”。
                statusListener?.invoke("静音重置")
            }

            // 冷却结束提示：只有在过期后且尚未通知时触发。
            if (cooldownUntilMs > 0 && nowMs >= cooldownUntilMs) {
                cooldownUntilMs = 0
                statusListener?.invoke("冷却结束")
            }

            // 冷却期间不接受/解码，避免重复触发。
            if (nowMs < cooldownUntilMs) {
                updateStats(readGapMs, 0L, 0L, processStartMs - frame.captureMs, 0L, 0)
                return
            }

            val procStartMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
            val dispatchDelayMs = procStartMs - frame.captureMs
            val procStartCpuMs = Debug.threadCpuTimeNanos() / 1_000_000
            engine.accept(frame)
            val hit = engine.poll()
            val procEndMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
            val procEndCpuMs = Debug.threadCpuTimeNanos() / 1_000_000
            val procCostMs = procEndMs - procStartMs
            val procCpuMs = procEndCpuMs - procStartCpuMs
            val backlogMs = procEndMs - frame.captureMs
            val backlogFrames = (dispatchDelayMs / 100L).toInt()
            val queueDepth = pendingCount.get() + 1
            updateStats(readGapMs, procCostMs, procCpuMs, backlogMs, dispatchDelayMs, backlogFrames)
            logWarnIfNeeded(dispatchDelayMs, queueDepth, procStartMs)
            if (hit != null) {
                val cmd = CommandMapper.map(hit.keywordRaw)
                if (cmd != null) {
                    lastDelayDetail =
                        "readGapMs=$readGapMs procCostMs=$procCostMs(procCpuMs=$procCpuMs) " +
                            "backlogMs=$backlogMs dispatchDelayMs=$dispatchDelayMs " +
                            "backlogFrames=$backlogFrames"
                    val audioLatencyMs = if (lastPeakOverCaptureMs > 0L) {
                        frame.captureMs - lastPeakOverCaptureMs
                    } else {
                        -1L
                    }
                    listener?.onCommand(
                        CommandEvent(cmd, nowMs, hit.keywordRaw, hit.score)
                    )
                    // 命中后进入冷却，并重置流上下文，避免重复触发。
                    cooldownUntilMs = nowMs + config.cooldownMs
                    engine.resetStream()
                    statusListener?.invoke("冷却开始")
                    Log.i(
                        TAG,
                        "HIT cmd=$cmd, keyword=${hit.keywordRaw}, audioLatencyMs=$audioLatencyMs " +
                            "$lastDelayDetail"
                    )
                }
            }
        } catch (e: Exception) {
            state = KwsState.ERROR
            stop()
            state = KwsState.ERROR
            Log.e(TAG, "KwsController onAudioFrame failed", e)
        }
    }

    private fun updateStats(
        readGapMs: Long,
        procCostMs: Long,
        procCpuMs: Long,
        backlogMs: Long,
        dispatchDelayMs: Long,
        backlogFrames: Int
    ) {
        statCount += 1
        statReadGapSum += readGapMs
        statProcCostSum += procCostMs
        statProcCpuSum += procCpuMs
        statBacklogSum += backlogMs
        statDispatchSum += dispatchDelayMs
        statBacklogFramesSum += backlogFrames.toLong()
        if (readGapMs > statReadGapMax) {
            statReadGapMax = readGapMs
        }
        if (procCostMs > statProcCostMax) {
            statProcCostMax = procCostMs
        }
        if (procCpuMs > statProcCpuMax) {
            statProcCpuMax = procCpuMs
        }
        if (backlogMs > statBacklogMax) {
            statBacklogMax = backlogMs
        }
        if (dispatchDelayMs > statDispatchMax) {
            statDispatchMax = dispatchDelayMs
        }
        if (backlogFrames.toLong() > statBacklogFramesMax) {
            statBacklogFramesMax = backlogFrames.toLong()
        }

        val now = System.currentTimeMillis()
        if (statWindowStartMs == 0L) {
            statWindowStartMs = now
        }
        if (now - statWindowStartMs >= 1000 && statCount > 0) {
            val avgReadGap = statReadGapSum / statCount
            val avgProcCost = statProcCostSum / statCount
            val avgProcCpu = statProcCpuSum / statCount
            val avgBacklog = statBacklogSum / statCount
            val avgDispatch = statDispatchSum / statCount
            val avgBacklogFrames = statBacklogFramesSum / statCount
            val skewMs = SystemClock.elapsedRealtimeNanos() / 1_000_000 - lastFrameCaptureMs
            statusListener?.invoke(
                "KWS_STAT avg:${avgReadGap}/${avgProcCost}(cpu=${avgProcCpu})/" +
                    "${avgBacklog}/dispatch=${avgDispatch}/frames=${avgBacklogFrames}/skew=${skewMs} " +
                    "max:${statReadGapMax}/${statProcCostMax}(cpu=${statProcCpuMax})/" +
                    "${statBacklogMax}/dispatch=${statDispatchMax}/frames=${statBacklogFramesMax}"
            )
            statWindowStartMs = now
            statCount = 0
            statReadGapSum = 0
            statReadGapMax = 0
            statProcCostSum = 0
            statProcCostMax = 0
            statProcCpuSum = 0
            statProcCpuMax = 0
            statBacklogSum = 0
            statBacklogMax = 0
            statDispatchSum = 0
            statDispatchMax = 0
            statBacklogFramesSum = 0
            statBacklogFramesMax = 0
        }
    }

    private fun logWarnIfNeeded(
        dispatchDelayMs: Long,
        queueDepth: Int,
        nowMonoMs: Long
    ) {
        val wasWarn = warnActive
        val over = dispatchDelayMs >= 200
        val clear = dispatchDelayMs <= 120
        val requireMs = 0L
        val minLogIntervalMs = 1000L

        if (over) {
            if (warnOverSinceMs == 0L) {
                warnOverSinceMs = nowMonoMs
            }
            warnClearSinceMs = 0L
        } else if (clear) {
            if (warnClearSinceMs == 0L) {
                warnClearSinceMs = nowMonoMs
            }
            warnOverSinceMs = 0L
        } else {
            warnOverSinceMs = 0L
            warnClearSinceMs = 0L
        }

        if (!warnActive && warnOverSinceMs > 0L && nowMonoMs - warnOverSinceMs >= requireMs) {
            warnActive = true
            // 仅在达到最小间隔时打印，避免状态抖动刷屏。
            if (nowMonoMs - lastWarnLogMs >= minLogIntervalMs) {
                Log.w(
                    TAG,
                    "WARN instanceId=$instanceId inWarn=$wasWarn->${warnActive} " +
                        "dispatchDelayMs=$dispatchDelayMs queueDepth=$queueDepth"
                )
                lastWarnLogMs = nowMonoMs
            }
        } else if (warnActive && warnClearSinceMs > 0L && nowMonoMs - warnClearSinceMs >= requireMs) {
            warnActive = false
            // 仅在达到最小间隔时打印，避免状态抖动刷屏。
            if (nowMonoMs - lastWarnLogMs >= minLogIntervalMs) {
                Log.w(
                    TAG,
                    "WARN instanceId=$instanceId inWarn=$wasWarn->${warnActive} " +
                        "dispatchDelayMs=$dispatchDelayMs queueDepth=$queueDepth"
                )
                lastWarnLogMs = nowMonoMs
            }
        }
    }

    private fun logDropIfNeeded(
        dropped: Int,
        queueDepthBefore: Int,
        dispatchDelayMs: Long,
        backlogFrames: Int,
        queueDepthAfter: Int,
        dispatchDelayAfterMs: Long,
        backlogFramesAfter: Int,
        nowMonoMs: Long
    ) {
        if (dropped <= 0) {
            return
        }
        // 丢帧日志节流：避免连续触发时刷屏。
        if (nowMonoMs - lastDropLogMs < config.dropMinIntervalMs) {
            return
        }
        lastDropLogMs = nowMonoMs
        Log.w(
            TAG,
            "WARN_DROP_BACKLOG dropped=$dropped queueDepthBefore=$queueDepthBefore " +
                "dispatchDelayMs=$dispatchDelayMs backlogFrames=$backlogFrames " +
                "queueDepthAfter=$queueDepthAfter dispatchDelayAfter=$dispatchDelayAfterMs " +
                "backlogFramesAfter=$backlogFramesAfter"
        )
    }

    private fun isPeakOverThreshold(pcm: ShortArray): Boolean {
        // -30dBFS 对应幅度约 0.0316，这里用近似阈值。
        val threshold = 1036
        for (sample in pcm) {
            val absVal = if (sample >= 0) sample.toInt() else -sample.toInt()
            if (absVal >= threshold) {
                return true
            }
        }
        return false
    }

    private companion object {
        private const val TAG = "KwsController"
    }
}
