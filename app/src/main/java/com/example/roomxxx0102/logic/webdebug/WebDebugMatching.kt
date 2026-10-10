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
        windowMs: Long = WINDOW_MS
    ): List<WebEventMatch> {
        val used = hashSetOf<String>()
        return marked.sortedWith(compareBy<WebMarkedEvent> { it.timeMs }.thenBy { it.key }).map { gt ->
            val candidates = runtime.filter {
                it.key !in used && abs(it.timeMs - gt.timeMs) <= windowMs
            }
            val good = candidates
                .filter { it.type == gt.type && (gt.portalRoomId == null || it.portalRoomId == gt.portalRoomId) }
                .minWithOrNull(compareBy<WebRuntimeEvent> { abs(it.timeMs - gt.timeMs) }.thenBy { it.key })
            val wrongPortal = if (good == null && gt.portalRoomId != null) {
                candidates.filter { it.type == gt.type }
                    .minWithOrNull(compareBy<WebRuntimeEvent> { abs(it.timeMs - gt.timeMs) }.thenBy { it.key })
            } else null
            val wrongDirection = if (good == null && wrongPortal == null) {
                candidates.minWithOrNull(compareBy<WebRuntimeEvent> { abs(it.timeMs - gt.timeMs) }.thenBy { it.key })
            } else null
            val chosen = good ?: wrongPortal ?: wrongDirection
            if (chosen != null) used.add(chosen.key)
            val classification = when {
                good != null -> "MATCH"
                wrongPortal != null -> "WRONG_PORTAL"
                wrongDirection != null -> "WRONG_DIRECTION"
                gt.timeMs + windowMs > playbackMs -> "PENDING"
                gt.timeMs + windowMs < observedFromMs -> "UNOBSERVED"
                else -> "MISS"
            }
            WebEventMatch(
                key = gt.key,
                classification = classification,
                runtimeKey = chosen?.key,
                deltaMs = chosen?.let { it.timeMs - gt.timeMs }
            )
        }
    }
}
