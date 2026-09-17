package com.example.roomxxx0102.logic.validation

import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.logic.presence.PresenceRoomSnapshot
import com.example.roomxxx0102.logic.roomalgorithm.RoomAlgorithmEngine
import com.example.roomxxx0102.logic.roomalgorithm.RoomAlgorithmFrameInput
import com.example.roomxxx0102.logic.roomalgorithm.RoomAlgorithmFrameResult
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 当前视频的人工事件快照。
 * EventMarkerManager 只在绑定视频、增删标注时更新；逐帧观察端只做一次 volatile 读取。
 */
internal object MarkedEventRuntimeSource {
    internal data class Snapshot(
        val videoKey: String?,
        val revision: Long,
        val events: List<MarkedEvent>,
    )

    @Volatile
    private var latest = Snapshot(null, 0L, emptyList())

    @Synchronized
    fun update(videoKey: String?, events: List<MarkedEvent>) {
        val sorted = events.sortedBy { it.timestampMs }
        val previous = latest
        if (previous.videoKey == videoKey && previous.events == sorted) return
        latest = Snapshot(videoKey, previous.revision + 1L, sorted)
        MarkedPortalInferenceRuntime.onMarkedSourceChanged(latest)
    }

    fun snapshot(): Snapshot = latest
}

internal data class PortalInferenceWindow(
    val startMs: Long,
    val endMs: Long,
    val previousGapMs: Long?,
    val nextGapMs: Long?,
)

internal data class MarkedPortalInferenceResult(
    val eventIndex: Int,
    val event: MarkedEvent,
    val window: PortalInferenceWindow,
    val inferred: InferredPortalTruth?,
    val topCandidates: List<InferredPortalTruth>,
)

/**
 * 人工事件 -> Portal 的轻量常驻旁路推断。
 *
 * 重要约束：
 * 1) 正常播放就运行，不依赖“诊断回放”。
 * 2) 只读 Pose + 静态 Portal 几何；不读 V4 候选门、锁、FSM、Ledger、最终房间结果。
 * 3) 不读 Bitmap，不做 Mask/光流/网格，因此不能把诊断回放的重型开销带进日常播放。
 * 4) 人工打点允许早于真正可见的“人体被门吸收”时刻；因此窗口明显偏向打点之后。
 * 5) 相邻人工事件以时间中点切开窗口，避免“刚出卫生间的尾巴”污染紧接着的卧室进入事件。
 */
internal object MarkedPortalInferenceRuntime {
    const val PRE_MS = 400L
    const val POST_MS = 1400L

    private data class PortalAggregate(
        var bestAny: InferredPortalTruth? = null,
        var bestPost: InferredPortalTruth? = null,
        var supportFrames: Int = 0,
        var postSupportFrames: Int = 0,
        var firstEvidenceMs: Long? = null,
        var lastEvidenceMs: Long? = null,
    ) {
        fun add(candidate: InferredPortalTruth, markerMs: Long) {
            supportFrames += 1
            if (candidate.sampleTimeMs >= markerMs) postSupportFrames += 1
            firstEvidenceMs = firstEvidenceMs?.let { min(it, candidate.sampleTimeMs) } ?: candidate.sampleTimeMs
            lastEvidenceMs = lastEvidenceMs?.let { max(it, candidate.sampleTimeMs) } ?: candidate.sampleTimeMs
            if (bestAny == null || candidate.score > bestAny!!.score) bestAny = candidate
            if (candidate.sampleTimeMs >= markerMs &&
                (bestPost == null || candidate.score > bestPost!!.score)
            ) {
                bestPost = candidate
            }
        }

        fun selected(): InferredPortalTruth? {
            val base = bestPost ?: bestAny ?: return null
            return base.copy(
                supportFrames = supportFrames,
                postSupportFrames = postSupportFrames,
                firstEvidenceTimeMs = firstEvidenceMs ?: base.sampleTimeMs,
                lastEvidenceTimeMs = lastEvidenceMs ?: base.sampleTimeMs,
            )
        }
    }

    private data class EventState(
        val index: Int,
        val event: MarkedEvent,
        val window: PortalInferenceWindow,
        val portals: MutableMap<String, PortalAggregate> = linkedMapOf(),
        var finalized: Boolean = false,
    )

    private var sourceRevision = -1L
    private var states: List<EventState> = emptyList()
    private val resultsByKey = linkedMapOf<String, MarkedPortalInferenceResult>()
    private var lastObservedTimeMs = -1L
    private var livingRoomName = "客厅"

    @Synchronized
    fun onMarkedSourceChanged(source: MarkedEventRuntimeSource.Snapshot) {
        rebuild(source, clearBanner = true)
    }

    @Synchronized
    fun resetPlayback() {
        val source = MarkedEventRuntimeSource.snapshot()
        rebuild(source, clearBanner = true)
    }

    @Synchronized
    fun observe(
        timeMs: Long,
        poses: List<PoseResult>,
        rooms: List<PresenceRoomSnapshot>,
    ) {
        val source = MarkedEventRuntimeSource.snapshot()
        if (source.revision != sourceRevision) rebuild(source, clearBanner = true)
        if (source.events.isEmpty()) {
            lastObservedTimeMs = timeMs
            return
        }

        // 视频回卷/重新从头播放：重新计算同一批人工事件，不沿用上一轮的证据。
        if (lastObservedTimeMs >= 0L && timeMs + 100L < lastObservedTimeMs) {
            rebuild(source, clearBanner = true)
        }
        lastObservedTimeMs = timeMs

        rooms.firstOrNull { it.isLivingRoom }?.roomName?.takeIf { it.isNotBlank() }?.let {
            livingRoomName = it
        }

        val activeStates = states.filter { !it.finalized && timeMs in it.window.startMs..it.window.endMs }
        if (activeStates.isNotEmpty() && poses.isNotEmpty()) {
            val regions = MarkedPortalTruthInference.regions(rooms)
            if (regions.isNotEmpty()) {
                // 中点裁窗后正常最多只有一个人工事件活跃；即便异常重叠，也只算一次几何。
                val frameCandidates = MarkedPortalTruthInference.inferAll(poses, regions, timeMs)
                for (state in activeStates) {
                    for (candidate in frameCandidates) {
                        val aggregate = state.portals.getOrPut(candidate.portalRoomId) { PortalAggregate() }
                        aggregate.add(candidate, state.event.timestampMs)
                    }
                }
            }
        }

        states.filter { !it.finalized && timeMs > it.window.endMs }.forEach(::finalizeState)
    }

    @Synchronized
    fun finishPending(): List<MarkedPortalInferenceResult> {
        states.filter { !it.finalized }.forEach(::finalizeState)
        return states.mapNotNull { resultsByKey[eventKey(it.event)] }
    }

    @Synchronized
    fun resultFor(event: MarkedEvent): MarkedPortalInferenceResult? = resultsByKey[eventKey(event)]

    @Synchronized
    fun resultsFor(events: List<MarkedEvent>): List<MarkedPortalInferenceResult?> =
        events.map { resultsByKey[eventKey(it)] }

    @Synchronized
    fun snapshotResults(): List<MarkedPortalInferenceResult> =
        states.mapNotNull { resultsByKey[eventKey(it.event)] }

    private fun rebuild(source: MarkedEventRuntimeSource.Snapshot, clearBanner: Boolean) {
        sourceRevision = source.revision
        resultsByKey.clear()
        lastObservedTimeMs = -1L
        val events = source.events.sortedBy { it.timestampMs }
        states = events.mapIndexed { index, event ->
            EventState(index, event, windowFor(events, index))
        }
        if (clearBanner) MarkedPortalInferenceOverlayBus.clear()
    }

    private fun finalizeState(state: EventState) {
        if (state.finalized) return
        state.finalized = true

        val ranked = state.portals.values
            .mapNotNull(PortalAggregate::selected)
            // 人工点通常稍早，所以只要某个门在打点之后出现过，就优先使用其 post 证据；
            // 同一门内部也始终用 post 峰值，避免上一个事件的尾巴占掉当前事件。
            .sortedWith(
                compareByDescending<InferredPortalTruth> { it.postSupportFrames > 0 }
                    .thenByDescending { it.score }
                    .thenByDescending { it.postSupportFrames }
                    .thenByDescending { it.supportFrames }
            )
        val inferred = ranked.firstOrNull()
        val result = MarkedPortalInferenceResult(
            eventIndex = state.index,
            event = state.event,
            window = state.window,
            inferred = inferred,
            topCandidates = ranked.take(3),
        )
        resultsByKey[eventKey(state.event)] = result
        publish(result)
    }

    private fun publish(result: MarkedPortalInferenceResult) {
        val inferred = result.inferred
        if (inferred == null) {
            val startDt = result.window.startMs - result.event.timestampMs
            val endDt = result.window.endMs - result.event.timestampMs
            MarkedPortalInferenceOverlayBus.publish(
                "【推断进出】#${result.eventIndex + 1} 未找到门口人体证据 ｜ 窗口=${startDt}~+${endDt}ms"
            )
            return
        }

        val route = if (result.event.type == EventType.ENTER) {
            "$livingRoomName → ${inferred.portalName}"
        } else {
            "${inferred.portalName} → $livingRoomName"
        }
        val lag = inferred.sampleTimeMs - result.event.timestampMs
        val lagText = if (lag >= 0L) "+${lag}ms" else "${lag}ms"
        MarkedPortalInferenceOverlayBus.publish(
            "【推断进出】#${result.eventIndex + 1} $route ｜ $lagText ｜ " +
                "人体覆盖=${percent(inferred.personCoverage)} ｜ " +
                "关键点=${percent(inferred.keypointCoverage)} ｜ " +
                "门填充=${percent(inferred.portalCoverage)}"
        )
    }

    private fun percent(value: Double): String = String.format(Locale.US, "%.0f%%", value * 100.0)

    internal fun windowFor(events: List<MarkedEvent>, index: Int): PortalInferenceWindow {
        val event = events[index]
        val previous = events.getOrNull(index - 1)
        val next = events.getOrNull(index + 1)
        val previousMidpoint = previous?.let { midpoint(it.timestampMs, event.timestampMs) }
        val nextMidpoint = next?.let { midpoint(event.timestampMs, it.timestampMs) }
        val start = max(event.timestampMs - PRE_MS, previousMidpoint?.plus(1L) ?: Long.MIN_VALUE)
        val end = min(event.timestampMs + POST_MS, nextMidpoint ?: Long.MAX_VALUE)
        return PortalInferenceWindow(
            startMs = min(start, end),
            endMs = max(start, end),
            previousGapMs = previous?.let { event.timestampMs - it.timestampMs },
            nextGapMs = next?.let { it.timestampMs - event.timestampMs },
        )
    }

    private fun midpoint(a: Long, b: Long): Long = a + (b - a) / 2L

    private fun eventKey(event: MarkedEvent): String =
        "${event.type.name}|${event.frameIndex}|${event.timestampMs}"
}

/**
 * 给所有房间算法套一层透明旁路观察器。
 * delegate 的输入、输出、configurationKey/runtimeTag 全部不改；诊断开关也不参与这层逻辑。
 */
internal class MarkedPortalInferenceRoomAlgorithm(
    private val delegate: RoomAlgorithmEngine,
) : RoomAlgorithmEngine {
    override val algorithmId: String get() = delegate.algorithmId
    override val runtimeTag: String get() = delegate.runtimeTag
    override val configurationKey: String get() = delegate.configurationKey

    override fun processFrame(input: RoomAlgorithmFrameInput): RoomAlgorithmFrameResult {
        if (input.sceneInfo.isVideoPlayback) {
            val timeMs = input.poseMetadata?.stamp?.timestampMs ?: input.timestampMs
            MarkedPortalInferenceRuntime.observe(timeMs, input.poses, input.rooms)
        }
        return delegate.processFrame(input)
    }

    override fun reset() {
        MarkedPortalInferenceRuntime.resetPlayback()
        delegate.reset()
    }
}
