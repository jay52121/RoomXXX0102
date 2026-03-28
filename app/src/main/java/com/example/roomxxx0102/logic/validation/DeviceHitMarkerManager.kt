package com.example.roomxxx0102.logic.validation

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DeviceHitMarkedEvent(
    val deviceId: String,
    val deviceName: String,
    val frameIndex: Int,
    val timestampMs: Long
)

class DeviceHitMarkerManager {
    enum class AddResult {
        ADDED,
        DUPLICATE_FRAME
    }

    private val eventMap: MutableMap<String, MutableList<DeviceHitMarkedEvent>> = mutableMapOf()
    private var storageDir: File? = null
    private var boundVideoKey: String? = null

    fun init(context: Context) {
        storageDir = File(context.filesDir, "device_hit_markers").apply {
            if (!exists()) mkdirs()
        }
    }

    fun bindVideo(videoKey: String?) {
        boundVideoKey = videoKey
        if (videoKey.isNullOrBlank()) return
        if (eventMap.containsKey(videoKey)) return
        eventMap[videoKey] = loadEventsForVideo(videoKey).toMutableList()
    }

    fun clearBoundVideoEvents() {
        val key = boundVideoKey ?: return
        eventMap.remove(key)
        deleteEventsFile(key)
    }

    fun getEvents(): List<DeviceHitMarkedEvent> {
        val key = boundVideoKey ?: return emptyList()
        return eventMap[key]?.toList() ?: emptyList()
    }

    fun addEvent(
        deviceId: String,
        deviceName: String,
        frameIndex: Int,
        timestampMs: Long
    ): AddResult {
        val key = boundVideoKey ?: return AddResult.DUPLICATE_FRAME
        val list = eventMap.getOrPut(key) { mutableListOf() }
        if (list.any { it.frameIndex == frameIndex }) {
            return AddResult.DUPLICATE_FRAME
        }
        list.add(
            DeviceHitMarkedEvent(
                deviceId = deviceId,
                deviceName = deviceName,
                frameIndex = frameIndex,
                timestampMs = timestampMs
            )
        )
        list.sortBy { it.timestampMs }
        saveEventsForVideo(key, list)
        return AddResult.ADDED
    }

    fun findEventsNearFrame(frameIndex: Int, toleranceFrames: Int = 1): List<DeviceHitMarkedEvent> {
        val key = boundVideoKey ?: return emptyList()
        val list = eventMap[key] ?: return emptyList()
        return list.filter { kotlin.math.abs(it.frameIndex - frameIndex) <= toleranceFrames }
    }

    fun removeEventsNearFrame(frameIndex: Int, toleranceFrames: Int = 1): List<DeviceHitMarkedEvent> {
        val key = boundVideoKey ?: return emptyList()
        val list = eventMap[key] ?: return emptyList()
        val matched = list.filter { kotlin.math.abs(it.frameIndex - frameIndex) <= toleranceFrames }
        if (matched.isEmpty()) return emptyList()
        list.removeAll(matched.toSet())
        saveEventsForVideo(key, list)
        return matched
    }

    fun findNextEventAfter(timestampMs: Long): DeviceHitMarkedEvent? {
        val key = boundVideoKey ?: return null
        val list = eventMap[key] ?: return null
        return list.firstOrNull { it.timestampMs > timestampMs }
    }

    private fun loadEventsForVideo(videoKey: String): List<DeviceHitMarkedEvent> {
        val file = resolveEventsFile(videoKey) ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val jsonString = file.readText(Charsets.UTF_8)
            val root = JSONObject(jsonString)
            val array = root.optJSONArray("events") ?: JSONArray()
            val list = mutableListOf<DeviceHitMarkedEvent>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val deviceId = obj.optString("deviceId")
                val deviceName = obj.optString("deviceName")
                val frameIndex = obj.optInt("frameIndex", -1)
                val timestampMs = obj.optLong("timestampMs", -1L)
                if (deviceId.isBlank() || frameIndex < 0 || timestampMs < 0L) continue
                list.add(
                    DeviceHitMarkedEvent(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        frameIndex = frameIndex,
                        timestampMs = timestampMs
                    )
                )
            }
            list.sortedBy { it.timestampMs }
        } catch (e: Exception) {
            Log.w("DeviceHitMarkerMgr", "loadEventsForVideo failed key=$videoKey", e)
            emptyList()
        }
    }

    private fun saveEventsForVideo(videoKey: String, events: List<DeviceHitMarkedEvent>) {
        val file = resolveEventsFile(videoKey) ?: return
        try {
            val root = JSONObject()
            root.put("videoKey", videoKey)
            val array = JSONArray()
            events.sortedBy { it.timestampMs }.forEach { event ->
                val obj = JSONObject()
                obj.put("deviceId", event.deviceId)
                obj.put("deviceName", event.deviceName)
                obj.put("frameIndex", event.frameIndex)
                obj.put("timestampMs", event.timestampMs)
                array.put(obj)
            }
            root.put("events", array)
            file.writeText(root.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("DeviceHitMarkerMgr", "saveEventsForVideo failed key=$videoKey", e)
        }
    }

    private fun deleteEventsFile(videoKey: String) {
        val file = resolveEventsFile(videoKey) ?: return
        if (!file.exists()) return
        runCatching { file.delete() }
            .onFailure { e -> Log.w("DeviceHitMarkerMgr", "deleteEventsFile failed key=$videoKey", e) }
    }

    private fun resolveEventsFile(videoKey: String): File? {
        val dir = storageDir ?: return null
        val baseName = resolveVideoBaseName(videoKey)
        return File(dir, "$baseName.device_hits.json")
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
