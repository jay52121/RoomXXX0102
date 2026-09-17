package com.example.roomxxx0102.logic.validation

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class EventType {
    ENTER,
    EXIT
}

data class MarkedEvent(
    val type: EventType,
    val frameIndex: Int,
    val timestampMs: Long,
    val portalRoomId: String? = null
)

data class RuntimeRoomEvent(
    val type: EventType,
    val frameIndex: Int,
    val timestampMs: Long
)

/**
 * 人工进出门事件的“当前待绑定事件”桥接。
 *
 * MainActivity 原有“跳转到下一个事件”会调用 EventMarkerManager.findNextEventAfter()，
 * 因此无需让 UI 再维护一份事件索引：findNextEventAfter() 选中的事件就是当前绑定目标。
 * 调试覆盖层只通过这里读取/覆盖 portalRoomId，正式房间算法不读取该状态。
 */
object MarkedEventPortalBinding {
    private data class EventKey(
        val type: EventType,
        val frameIndex: Int,
        val timestampMs: Long
    )

    @Volatile
    private var activeManager: EventMarkerManager? = null
    @Volatile
    private var selectedKey: EventKey? = null
    @Volatile
    private var onChangedListener: (() -> Unit)? = null

    internal fun attach(manager: EventMarkerManager) {
        activeManager = manager
        selectedKey = null
        notifyChanged()
    }

    internal fun select(event: MarkedEvent?) {
        selectedKey = event?.let { EventKey(it.type, it.frameIndex, it.timestampMs) }
        notifyChanged()
    }

    internal fun onEventsChanged() {
        val key = selectedKey
        if (key != null && activeManager?.findExactEvent(key.type, key.frameIndex, key.timestampMs) == null) {
            selectedKey = null
        }
        notifyChanged()
    }

    fun setOnChangedListener(listener: (() -> Unit)?) {
        onChangedListener = listener
        listener?.invoke()
    }

    fun selectedEvent(): MarkedEvent? {
        val key = selectedKey ?: return null
        return activeManager?.findExactEvent(key.type, key.frameIndex, key.timestampMs)
    }

    fun bindSelectedPortal(portalRoomId: String): MarkedEvent? {
        val key = selectedKey ?: return null
        val updated = activeManager?.bindPortal(
            type = key.type,
            frameIndex = key.frameIndex,
            timestampMs = key.timestampMs,
            portalRoomId = portalRoomId
        )
        if (updated == null) selectedKey = null
        notifyChanged()
        return updated
    }

    private fun notifyChanged() {
        onChangedListener?.invoke()
    }
}

class EventMarkerManager {
    companion object {
        const val MATCH_WINDOW_MS: Long = 1000L
    }

    enum class AddResult {
        ADDED,
        DUPLICATE
    }

    private val eventMap: MutableMap<String, MutableList<MarkedEvent>> = mutableMapOf()
    private var storageDir: File? = null
    private var boundVideoKey: String? = null

    fun init(context: Context) {
        storageDir = File(context.filesDir, "event_markers").apply {
            if (!exists()) mkdirs()
        }
    }

    fun bindVideo(videoKey: String?) {
        boundVideoKey = videoKey
        MarkedEventPortalBinding.attach(this)
        if (videoKey.isNullOrBlank()) return
        if (eventMap.containsKey(videoKey)) return
        eventMap[videoKey] = loadEventsForVideo(videoKey).toMutableList()
    }

    fun clearBoundVideoEvents() {
        val key = boundVideoKey ?: return
        eventMap.remove(key)
        deleteEventsFile(key)
        MarkedEventPortalBinding.select(null)
    }

    fun getEvents(): List<MarkedEvent> {
        val key = boundVideoKey ?: return emptyList()
        return eventMap[key]?.toList() ?: emptyList()
    }

    fun addEvent(type: EventType, frameIndex: Int, timestampMs: Long): AddResult {
        val key = boundVideoKey ?: return AddResult.DUPLICATE
        val list = eventMap.getOrPut(key) { mutableListOf() }
        if (list.any { it.type == type && it.frameIndex == frameIndex }) {
            return AddResult.DUPLICATE
        }
        list.add(
            MarkedEvent(
                type = type,
                frameIndex = frameIndex,
                timestampMs = timestampMs
            )
        )
        list.sortBy { it.timestampMs }
        saveEventsForVideo(key, list)
        MarkedEventPortalBinding.onEventsChanged()
        return AddResult.ADDED
    }

    fun findEventsNearFrame(frameIndex: Int, toleranceFrames: Int = 1): List<MarkedEvent> {
        val key = boundVideoKey ?: return emptyList()
        val list = eventMap[key] ?: return emptyList()
        return list.filter { kotlin.math.abs(it.frameIndex - frameIndex) <= toleranceFrames }
    }

    fun removeEventsNearFrame(frameIndex: Int, toleranceFrames: Int = 1): List<MarkedEvent> {
        val key = boundVideoKey ?: return emptyList()
        val list = eventMap[key] ?: return emptyList()
        val matched = list.filter { kotlin.math.abs(it.frameIndex - frameIndex) <= toleranceFrames }
        if (matched.isEmpty()) return emptyList()
        list.removeAll(matched.toSet())
        saveEventsForVideo(key, list)
        MarkedEventPortalBinding.onEventsChanged()
        return matched
    }

    fun findNextEventAfter(timestampMs: Long): MarkedEvent? {
        val key = boundVideoKey
        if (key == null) {
            MarkedEventPortalBinding.select(null)
            return null
        }
        val list = eventMap[key]
        if (list == null) {
            MarkedEventPortalBinding.select(null)
            return null
        }
        val next = list.firstOrNull { it.timestampMs > timestampMs }
        MarkedEventPortalBinding.select(next)
        return next
    }

    internal fun findExactEvent(
        type: EventType,
        frameIndex: Int,
        timestampMs: Long
    ): MarkedEvent? {
        val key = boundVideoKey ?: return null
        return eventMap[key]?.firstOrNull {
            it.type == type && it.frameIndex == frameIndex && it.timestampMs == timestampMs
        }
    }

    internal fun bindPortal(
        type: EventType,
        frameIndex: Int,
        timestampMs: Long,
        portalRoomId: String
    ): MarkedEvent? {
        val key = boundVideoKey ?: return null
        val list = eventMap[key] ?: return null
        val index = list.indexOfFirst {
            it.type == type && it.frameIndex == frameIndex && it.timestampMs == timestampMs
        }
        if (index < 0) return null
        val current = list[index]
        val updated = current.copy(portalRoomId = portalRoomId)
        list[index] = updated
        saveEventsForVideo(key, list)
        return updated
    }

    /**
     * 预留：后续阶段用于“标注事件 vs 运行时事件流”匹配。
     * 本阶段仅提供签名与常量，不接入暂停逻辑。
     */
    fun isMarkedEventMatched(
        markedEvent: MarkedEvent,
        runtimeEvents: List<RuntimeRoomEvent>,
        windowMs: Long = MATCH_WINDOW_MS
    ): Boolean {
        return runtimeEvents.any { runtime ->
            if (runtime.type != markedEvent.type) return@any false
            kotlin.math.abs(runtime.timestampMs - markedEvent.timestampMs) <= windowMs
        }
    }

    private fun loadEventsForVideo(videoKey: String): List<MarkedEvent> {
        val file = resolveEventsFile(videoKey) ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val jsonString = file.readText(Charsets.UTF_8)
            val root = JSONObject(jsonString)
            val array = root.optJSONArray("events") ?: JSONArray()
            val list = mutableListOf<MarkedEvent>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val typeRaw = obj.optString("type")
                val type = runCatching { EventType.valueOf(typeRaw) }.getOrNull() ?: continue
                val frameIndex = obj.optInt("frameIndex", -1)
                val timestampMs = obj.optLong("timestampMs", -1L)
                if (frameIndex < 0 || timestampMs < 0L) continue
                list.add(
                    MarkedEvent(
                        type = type,
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        portalRoomId = obj.optString("portalRoomId", "")
                            .takeIf { it.isNotBlank() }
                    )
                )
            }
            list.sortedBy { it.timestampMs }
        } catch (e: Exception) {
            Log.w("EventMarkerManager", "loadEventsForVideo failed key=$videoKey", e)
            emptyList()
        }
    }

    private fun saveEventsForVideo(videoKey: String, events: List<MarkedEvent>) {
        val file = resolveEventsFile(videoKey) ?: return
        try {
            val root = JSONObject()
            root.put("videoKey", videoKey)
            val array = JSONArray()
            events.sortedBy { it.timestampMs }.forEach { event ->
                val obj = JSONObject()
                obj.put("type", event.type.name)
                obj.put("frameIndex", event.frameIndex)
                obj.put("timestampMs", event.timestampMs)
                event.portalRoomId?.let { obj.put("portalRoomId", it) }
                array.put(obj)
            }
            root.put("events", array)
            file.writeText(root.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("EventMarkerManager", "saveEventsForVideo failed key=$videoKey", e)
        }
    }

    private fun deleteEventsFile(videoKey: String) {
        val file = resolveEventsFile(videoKey) ?: return
        if (!file.exists()) return
        runCatching { file.delete() }
            .onFailure { e -> Log.w("EventMarkerManager", "deleteEventsFile failed key=$videoKey", e) }
    }

    private fun resolveEventsFile(videoKey: String): File? {
        val dir = storageDir ?: return null
        val baseName = resolveVideoBaseName(videoKey)
        return File(dir, "$baseName.events.json")
    }

    private fun resolveVideoBaseName(videoKey: String): String {
        val rawName = when {
            videoKey.startsWith("file:") -> {
                val path = videoKey.removePrefix("file:")
                File(path).name
            }
            videoKey.startsWith("uri:") -> {
                val uriString = videoKey.removePrefix("uri:")
                val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                uri?.lastPathSegment?.substringAfterLast('/') ?: ""
            }
            else -> ""
        }
        val candidate = if (rawName.isNotBlank()) rawName else "video_${videoKey.hashCode().toUInt().toString(16)}"
        return candidate.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
