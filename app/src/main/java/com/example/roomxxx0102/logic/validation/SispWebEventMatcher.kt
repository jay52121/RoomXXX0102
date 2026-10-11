package com.example.roomxxx0102.logic.validation

import kotlin.math.abs

/** 网页调试用的轻量实时事件，不读取 Bitmap，也不参与房间算法决策。 */
internal data class WebRuntimeEvent(
    val type: String,
    val timeMs: Long,
    val fromRoomId: String,
    val toRoomId: String,
    val doorId: String,
    val trackId: Int?,
    val reason: String
) {
    val portalRoomId: String
        get() = if (type == EventType.ENTER.name) toRoomId else fromRoomId
}

internal data class WebEventMatch(
    val markedIndex: Int,
    val runtimeIndex: Int?,
    val status: String,
    val deltaMs: Long?
)

/**
 * 匹配的观察窗口应等到人工时间 + 允许偏差后才关闭；窗口尚未结束不可判成 MISS。
 * 一对一配对，同方向同门优先，最后才考虑方向错误。不得写回人工真值。
 */
internal object SispWebEventMatcher {
    const val WINDOW_MS = 1_000L

    fun match(
        marked: List<MarkedEvent>,
        runtime: List<WebRuntimeEvent>,
        observedUntilMs: Long,
        windowMs: Long = WINDOW_MS,
        observedFromMs: Long = 0L,
        observedSpans: List<LongRange>? = null
    ): List<WebEventMatch> {
        val occupied = mutableSetOf<Int>()
        return marked.mapIndexed { index, event ->
            // 无法观察事件前后完整窗口时不能把没有证据误报为 MISS。
            val ready = observedUntilMs >= event.timestampMs + windowMs
            if (!ready) return@mapIndexed WebEventMatch(index, null, "PENDING", null)
            val windowStartMs = (event.timestampMs - windowMs).coerceAtLeast(0L)
            val hasWholeWindow = observedSpans?.any { span ->
                span.first <= windowStartMs && span.last >= event.timestampMs + windowMs
            } ?: (windowStartMs >= observedFromMs)
            if (!hasWholeWindow) {
                return@mapIndexed WebEventMatch(index, null, "UNOBSERVED", null)
            }

            val possible = runtime.indices.filter { id ->
                id !in occupied && abs(runtime[id].timeMs - event.timestampMs) <= windowMs
            }
            val sameDirection = possible.filter { runtime[it].type == event.type.name }
            val sameDoor = sameDirection.filter { id ->
                event.portalRoomId == null || event.portalRoomId == runtime[id].portalRoomId
            }
            val matchedIndex = (sameDoor.ifEmpty {
                sameDirection.ifEmpty { possible }
            }).minByOrNull { abs(runtime[it].timeMs - event.timestampMs) }

            if (matchedIndex == null) {
                WebEventMatch(index, null, "MISS", null)
            } else {
                occupied.add(matchedIndex)
                val actual = runtime[matchedIndex]
                val status = when {
                    actual.type != event.type.name -> "WRONG_DIRECTION"
                    event.portalRoomId != null && event.portalRoomId != actual.portalRoomId -> "WRONG_PORTAL"
                    else -> "MATCH"
                }
                WebEventMatch(index, matchedIndex, status, actual.timeMs - event.timestampMs)
            }
        }
    }
}
