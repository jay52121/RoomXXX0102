package com.example.roomxxx0102.logic.webdebug

import kotlin.math.abs

/** 纯结构化事件匹配；绝不修改 SISP 房间算法和人工真值。 */
internal data class WebMarkedEvent(
    val key: String,
    val timeMs: Long,
    val type: String,
    val portalRoomId: String?
)

internal data class WebRuntimeEvent(
    val key: String,
    val timeMs: Long,
    val type: String,
    val portalRoomId: String
)

internal data class WebEventMatch(
    val key: String,
    val classification: String,
    val runtimeKey: String?,
    val deltaMs: Long?
)

internal object WebDebugMatching {
    const val WINDOW_MS = 1000L

    fun classify(
        marked: List<WebMarkedEvent>,
        runtime: List<WebRuntimeEvent>,
        playbackMs: Long,
        observedFromMs: Long,
        windowMs: Long = WINDOW_MS,
        analyzedEventKeys: Set<String>? = null
    ): List<WebEventMatch> {
        val ordered = marked.sortedWith(compareBy<WebMarkedEvent> { it.timeMs }.thenBy { it.key })
        val used = hashSetOf<String>()
        val matched = linkedMapOf<String, Pair<WebRuntimeEvent, String>>()

        fun nearest(gt: WebMarkedEvent, predicate: (WebRuntimeEvent) -> Boolean): WebRuntimeEvent? =
            runtime.asSequence()
                .filter { it.key !in used && abs(it.timeMs - gt.timeMs) <= windowMs && predicate(it) }
                .minWithOrNull(compareBy<WebRuntimeEvent> { abs(it.timeMs - gt.timeMs) }.thenBy { it.key })

        // 第一轮优先为全部 GT 找到真正正确的门/方向，避免较早事件吞掉较晚事件的正确输出。
        ordered.forEach { gt ->
            val hit = nearest(gt) {
                it.type == gt.type && (gt.portalRoomId == null || it.portalRoomId == gt.portalRoomId)
            }
            if (hit != null) {
                used += hit.key
                matched[gt.key] = hit to "MATCH"
            }
        }
        // 第二轮才为未匹配 GT 归因错门/错方向，其它 GT 的正确输出已经受到保护。
        ordered.forEach { gt ->
            if (gt.key in matched) return@forEach
            val wrongPortal = if (gt.portalRoomId != null) nearest(gt) { it.type == gt.type } else null
            val wrongDirection = if (wrongPortal == null) nearest(gt) { true } else null
            val chosen = wrongPortal ?: wrongDirection
            if (chosen != null) {
                used += chosen.key
                matched[gt.key] = chosen to (if (wrongPortal != null) "WRONG_PORTAL" else "WRONG_DIRECTION")
            }
        }
        return ordered.map { gt ->
            val assignment = matched[gt.key]
            val classification = assignment?.second ?: when {
                gt.timeMs + windowMs > playbackMs -> "PENDING"
                gt.timeMs + windowMs < observedFromMs -> "UNOBSERVED"
                analyzedEventKeys != null && gt.key !in analyzedEventKeys -> "UNOBSERVED"
                else -> "MISS"
            }
            WebEventMatch(
                key = gt.key,
                classification = classification,
                runtimeKey = assignment?.first?.key,
                deltaMs = assignment?.first?.let { it.timeMs - gt.timeMs }
            )
        }
    }
}
