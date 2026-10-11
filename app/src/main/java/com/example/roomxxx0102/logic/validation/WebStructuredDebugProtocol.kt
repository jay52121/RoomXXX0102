/**
 * SISP 结构化网页调试台 V1 的协议数据模型。
 * 不包含 Bitmap、视频帧或图片编码；Android 是唯一权威时间轴和标注数据源。
 */
package com.example.roomxxx0102.logic.validation

data class WebDebugPosePoint(val x: Float, val y: Float, val confidence: Float)
data class WebDebugRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class WebDebugPose(
    val poseId: Int,
    val trackId: Int?,
    val bbox: WebDebugRect,
    val keypoints: List<WebDebugPosePoint>,
    val confidence: Float
)
data class WebDebugPortal(
    val roomId: String,
    val roomName: String,
    val vertices: List<WebDebugPosePoint>
)
data class WebDebugMarkedEvent(
    val eventId: String,
    val timestampMs: Long,
    val frameIndex: Long,
    val type: String,
    val trackId: Int?,
    val portalRoomId: String?,
    val revision: Long
)
data class WebDebugMatch(
    val markedEventId: String?,
    val algorithmEventId: String?,
    val status: String,
    val lagMs: Long?,
    val reason: String?
)
data class WebDebugFrame(
    val sessionId: String,
    val sequence: Long,
    val videoTimestampMs: Long,
    val frameIndex: Long,
    val poses: List<WebDebugPose>,
    val roomStates: Map<String, String>,
    val matches: List<WebDebugMatch>
)
data class WebDebugSnapshot(
    val sessionId: String,
    val videoKey: String,
    val durationMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val portals: List<WebDebugPortal>,
    val markedEvents: List<WebDebugMarkedEvent>,
    val isPlaying: Boolean,
    val currentTimestampMs: Long
)
data class WebDebugCommand(
    val requestId: String,
    val type: String,
    val timestampMs: Long? = null,
    val eventId: String? = null,
    val roomId: String? = null,
    val eventType: String? = null
)
data class WebDebugAck(
    val requestId: String,
    val ok: Boolean,
    val currentTimestampMs: Long,
    val isPlaying: Boolean,
    val error: String? = null
)

/** 开关关闭时完全禁用；触发一次后须先离开事件窗口才能再次触发。 */
class UnmatchedEventJumpGuard {
    var enabled: Boolean = false
    private val triggered = mutableSetOf<String>()
    fun shouldJump(eventId: String, isUnmatched: Boolean): Boolean =
        enabled && isUnmatched && triggered.add(eventId)
    fun resetForNewSession() = triggered.clear()
    fun allowAgain(eventId: String) { triggered.remove(eventId) }
}
