package com.example.roomxxx0102.logic.presence

/**
 * Presence 变化日志工具。
 *
 * 设计要点：
 * 1) 仅当 presenceCounts 发生变化时输出
 * 2) 输出稳定格式，便于 logcat/grep 检索
 * 3) 日志以 searchKey 开头
 */
class RoomPresenceChangeLogger(
    private val searchKey: String = "ROOM_PRESENCE_CHANGE"
) {
    private var lastCountsSnapshot: Map<String, Int>? = null

    /**
     * 清空内部快照，用于“重置会话”场景。
     */
    fun reset() {
        lastCountsSnapshot = null
    }

    /**
     * 如果本帧计数有变化，返回一条日志文本；否则返回 null。
     */
    fun buildLogLineIfChanged(
        timestampMs: Long,
        events: List<PresenceSwitchEvent>,
        counts: Map<String, Int>,
        roomNameById: Map<String, String>
    ): String? {
        val normalizedCounts = counts.toSortedMap()
        if (lastCountsSnapshot == normalizedCounts) {
            return null
        }
        lastCountsSnapshot = normalizedCounts

        val eventText = if (events.isEmpty()) {
            "[]"
        } else {
            events.joinToString(prefix = "[", postfix = "]") { event ->
                val fromName = roomNameById[event.fromRoomId] ?: event.fromRoomId
                val toName = roomNameById[event.toRoomId] ?: event.toRoomId
                "${fromName}->${toName}@${event.doorId}:${event.reason}"
            }
        }

        val countsText = normalizedCounts.entries.joinToString(prefix = "{", postfix = "}") { entry ->
            val roomName = roomNameById[entry.key] ?: entry.key
            "$roomName:${entry.value}"
        }

        return "$searchKey t=$timestampMs changed=true events=$eventText counts=$countsText"
    }
}
