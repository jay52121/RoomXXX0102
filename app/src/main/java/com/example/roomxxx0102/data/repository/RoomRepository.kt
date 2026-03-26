package com.example.roomxxx0102.data.repository

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.example.roomxxx0102.data.model.BoundaryVertex
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.utils.RoomColorPalette
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object RoomRepository {

    private const val TAG = "RoomRepository"
    private const val LEGACY_FILE_NAME = "room_config.json"

    private val cachedRooms = CopyOnWriteArrayList<RoomConfig>()
    private var configFile: File? = null
    private var loadedBaselineCanonicalJson: String = ""

    fun init(context: Context) {
        VideoRoomConfigManager.init(context)
        val legacyFile = File(context.filesDir, LEGACY_FILE_NAME)
        val persistedFile = AppSettings.activeRoomConfigPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }
        configFile = persistedFile ?: legacyFile
        loadFromCurrentFile(createIfMissing = persistedFile == null)
    }

    fun getAllRooms(): List<RoomConfig> {
        return cachedRooms.toList()
    }

    fun getSubRooms(): List<RoomConfig> {
        return cachedRooms.filter { !it.isSovereignTerritory }
    }

    fun addNewRoom(
        name: String,
        anchor: PointF? = null,
        isEntranceDoor: Boolean = false,
        isLivingBlindZone: Boolean = false
    ) {
        val newRoom = RoomConfig(
            name = name,
            isSovereignTerritory = false,
            isRecorded = false,
            anchorPoint = anchor,
            isEntranceDoor = isEntranceDoor,
            isLivingBlindZone = isLivingBlindZone
        )
        cachedRooms.add(newRoom)
    }

    fun updateRoom(room: RoomConfig) {
        val index = cachedRooms.indexOfFirst { it.id == room.id }
        if (index != -1) {
            ensureRoomThemeColor(room)
            cachedRooms[index] = room
        }
    }

    fun deleteRoom(roomId: String): Boolean {
        val room = cachedRooms.find { it.id == roomId } ?: return false
        if (room.isSovereignTerritory) return false

        cachedRooms.remove(room)
        return true
    }

    fun markRoomAsRecorded(roomId: String) {
        val index = cachedRooms.indexOfFirst { it.id == roomId }
        if (index != -1) {
            val room = cachedRooms[index]
            cachedRooms[index] = room.copy(isRecorded = true)
        }
    }

    fun updateRoomBoundary(roomId: String, vertices: List<BoundaryVertex>) {
        val index = cachedRooms.indexOfFirst { it.id == roomId }
        if (index != -1) {
            val room = cachedRooms[index]
            val isRecorded = vertices.size >= 3
            cachedRooms[index] = room.copy(
                boundaryVertices = copyBoundaryVertices(vertices),
                isRecorded = isRecorded
            )
            Log.d(TAG, "Updated room [$roomId] boundary: ${vertices.size} points")
        } else {
            Log.w(TAG, "Room not found: $roomId")
        }
    }

    fun saveRoomBoundary(roomId: String, vertices: List<BoundaryVertex>) {
        updateRoomBoundary(roomId, vertices)
    }

    fun resetAllStatus() {
        // ...
    }

    fun currentConfigFile(): File? = configFile

    fun currentConfigDisplayName(): String? = configFile?.nameWithoutExtension

    fun hasMeaningfulConfig(): Boolean {
        val hasSubRooms = cachedRooms.any { !it.isSovereignTerritory }
        val livingRoomRecorded = cachedRooms.any { it.isSovereignTerritory && it.boundaryVertices.size >= 3 }
        return hasSubRooms || livingRoomRecorded
    }

    fun hasUnsavedChanges(): Boolean {
        return currentCanonicalJson() != loadedBaselineCanonicalJson
    }

    fun switchToConfigFile(file: File, persistSelection: Boolean = true, createIfMissing: Boolean = false): Boolean {
        configFile = file
        loadFromCurrentFile(createIfMissing = createIfMissing)
        if (persistSelection && file.exists()) {
            AppSettings.setActiveRoomConfigPath(file.absolutePath)
        }
        return true
    }

    fun saveAsConfigFile(file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()
            configFile = file
            saveToFile()
            loadedBaselineCanonicalJson = currentCanonicalJson()
            AppSettings.setActiveRoomConfigPath(file.absolutePath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config file: ${file.absolutePath}", e)
            false
        }
    }

    // --- 纯数据处理：备份与恢复 ---

    fun getBackupJson(): String {
        return try {
            val rootObj = JSONObject()
            val jsonArray = JSONArray()
            for (room in cachedRooms) {
                val roomObj = serializeRoom(room)
                jsonArray.put(roomObj)
            }
            rootObj.put("rooms", jsonArray)
            rootObj.toString(2)
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            "{}"
        }
    }

    fun restoreFromBackup(jsonString: String): Boolean {
        return try {
            val rootObj = JSONObject(jsonString)
            val jsonArray = rootObj.optJSONArray("rooms") ?: return false
            if (jsonArray.length() == 0) return false

            val restoredRooms = ArrayList<RoomConfig>()
            for (i in 0 until jsonArray.length()) {
                val roomObj = jsonArray.getJSONObject(i)
                val room = deserializeRoom(roomObj)
                restoredRooms.add(room)
            }

            if (restoredRooms.isNotEmpty()) {
                cachedRooms.clear()
                cachedRooms.addAll(restoredRooms)
                ensureMissingThemeColors()
                saveToFile()
                loadedBaselineCanonicalJson = currentCanonicalJson()
                Log.i(TAG, "Restored ${restoredRooms.size} rooms from backup")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            false
        }
    }

    // --- 私有辅助方法 ---

    private fun serializeRoom(room: RoomConfig): JSONObject {
        val roomObj = JSONObject()
        roomObj.put("id", room.id)
        roomObj.put("name", room.name)
        roomObj.put("isSovereign", room.isSovereignTerritory)
        roomObj.put("isRecorded", room.isRecorded)
        roomObj.put("isEntranceDoor", room.isEntranceDoor)
        roomObj.put("isLivingBlindZone", room.isLivingBlindZone)

        val boundaryArray = JSONArray()
        for (v in room.boundaryVertices) {
            val vObj = JSONObject()
            vObj.put("id", v.id)
            vObj.put("x", v.point.x.toDouble())
            vObj.put("y", v.point.y.toDouble())
            vObj.put("edgeIdToNext", v.edgeIdToNext)
            boundaryArray.put(vObj)
        }
        roomObj.put("boundaryVertices", boundaryArray)

        val occupiedArray = JSONArray()
        for (wallId in room.occupiedWallIds) {
            occupiedArray.put(wallId)
        }
        roomObj.put("occupiedWallIds", occupiedArray)

        room.anchorPoint?.let { p ->
            val anchorObj = JSONObject()
            anchorObj.put("x", p.x.toDouble())
            anchorObj.put("y", p.y.toDouble())
            roomObj.put("anchor", anchorObj)
        }
        room.labelPoint?.let { p ->
            val labelObj = JSONObject()
            labelObj.put("x", p.x.toDouble())
            labelObj.put("y", p.y.toDouble())
            roomObj.put("label", labelObj)
        }
        room.themeColor?.let { color ->
            roomObj.put("themeColor", color)
        }
        return roomObj
    }

    private fun deserializeRoom(roomObj: JSONObject): RoomConfig {
        val boundaryVertices = ArrayList<BoundaryVertex>()
        val boundaryVerticesArray = roomObj.optJSONArray("boundaryVertices")
        if (boundaryVerticesArray != null) {
            for (j in 0 until boundaryVerticesArray.length()) {
                val vObj = boundaryVerticesArray.getJSONObject(j)
                val id = vObj.optInt("id", j + 1)
                val x = vObj.optDouble("x", 0.0).toFloat()
                val y = vObj.optDouble("y", 0.0).toFloat()
                val edgeId = vObj.optInt("edgeIdToNext", j + 1)
                boundaryVertices.add(BoundaryVertex(id, PointF(x, y), edgeId))
            }
        } else {
            val boundaryList = ArrayList<PointF>()
            val boundaryArray = roomObj.optJSONArray("boundary")
            if (boundaryArray != null) {
                for (j in 0 until boundaryArray.length()) {
                    val pObj = boundaryArray.getJSONObject(j)
                    val x = pObj.optDouble("x", 0.0).toFloat()
                    val y = pObj.optDouble("y", 0.0).toFloat()
                    boundaryList.add(PointF(x, y))
                }
            }
            boundaryVertices.addAll(buildVerticesFromPoints(boundaryList))
        }

        var anchor: PointF? = null
        val anchorObj = roomObj.optJSONObject("anchor")
        if (anchorObj != null) {
            val x = anchorObj.optDouble("x", 0.0).toFloat()
            val y = anchorObj.optDouble("y", 0.0).toFloat()
            anchor = PointF(x, y)
        }
        var label: PointF? = null
        val labelObj = roomObj.optJSONObject("label")
        if (labelObj != null) {
            val x = labelObj.optDouble("x", 0.0).toFloat()
            val y = labelObj.optDouble("y", 0.0).toFloat()
            label = PointF(x, y)
        }

        val occupiedWallIds = ArrayList<Int>()
        val occupiedArray = roomObj.optJSONArray("occupiedWallIds")
        if (occupiedArray != null) {
            for (j in 0 until occupiedArray.length()) {
                occupiedWallIds.add(occupiedArray.optInt(j))
            }
        }

        return RoomConfig(
            id = roomObj.getString("id"),
            name = roomObj.getString("name"),
            isSovereignTerritory = roomObj.optBoolean("isSovereign", false),
            isRecorded = roomObj.optBoolean("isRecorded", false),
            boundaryVertices = boundaryVertices,
            occupiedWallIds = occupiedWallIds,
            anchorPoint = anchor,
            labelPoint = label,
            themeColor = if (roomObj.has("themeColor")) roomObj.optInt("themeColor") else null,
            isEntranceDoor = roomObj.optBoolean("isEntranceDoor", false),
            isLivingBlindZone = roomObj.optBoolean("isLivingBlindZone", false)
        )
    }

    private fun copyBoundaryVertices(vertices: List<BoundaryVertex>): MutableList<BoundaryVertex> {
        return vertices.map {
            BoundaryVertex(it.id, PointF(it.point.x, it.point.y), it.edgeIdToNext)
        }.toMutableList()
    }

    private fun buildVerticesFromPoints(points: List<PointF>): MutableList<BoundaryVertex> {
        val vertices = ArrayList<BoundaryVertex>(points.size)
        var nextVertexId = 1
        var nextEdgeId = 1
        for (p in points) {
            vertices.add(BoundaryVertex(nextVertexId++, PointF(p.x, p.y), nextEdgeId++))
        }
        return vertices
    }

    private fun loadFromCurrentFile(createIfMissing: Boolean) {
        cachedRooms.clear()
        val file = configFile ?: return

        if (!file.exists()) {
            Log.i(TAG, "Config file missing: ${file.absolutePath}")
            createDefaultRoom(persist = createIfMissing)
            loadedBaselineCanonicalJson = currentCanonicalJson()
            return
        }

        try {
            val jsonString = file.readText()
            val rootObj = JSONObject(jsonString)
            val jsonArray = rootObj.optJSONArray("rooms") ?: JSONArray()

            for (i in 0 until jsonArray.length()) {
                val roomObj = jsonArray.getJSONObject(i)
                cachedRooms.add(deserializeRoom(roomObj))
            }
            Log.i(TAG, "Loaded ${cachedRooms.size} rooms")
            if (ensureMissingThemeColors()) {
                saveToFile()
            }
            loadedBaselineCanonicalJson = currentCanonicalJson()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load room config", e)
            createDefaultRoom(persist = false)
            loadedBaselineCanonicalJson = currentCanonicalJson()
        }
    }

    private fun createDefaultRoom(persist: Boolean) {
        cachedRooms.clear()
        cachedRooms.add(
            RoomConfig(
                id = "living_room",
                name = "客厅",
                isSovereignTerritory = true,
                isRecorded = false
            )
        )
        if (persist) {
            saveToFile()
        }
    }

    private fun saveToFile() {
        val file = configFile ?: return
        try {
            file.parentFile?.mkdirs()
            val rootObj = JSONObject()
            val jsonArray = JSONArray()
            for (room in cachedRooms) {
                jsonArray.put(serializeRoom(room))
            }
            rootObj.put("rooms", jsonArray)
            file.writeText(rootObj.toString(2))
            Log.d(TAG, "Config saved: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save room config", e)
        }
    }

    private fun currentCanonicalJson(): String {
        return buildRootJson().toString()
    }

    private fun buildRootJson(): JSONObject {
        val rootObj = JSONObject()
        val jsonArray = JSONArray()
        for (room in cachedRooms) {
            jsonArray.put(serializeRoom(room))
        }
        rootObj.put("rooms", jsonArray)
        return rootObj
    }

    private fun ensureRoomThemeColor(room: RoomConfig) {
        if (room.isSovereignTerritory) return
        if (room.occupiedWallIds.isEmpty()) {
            room.themeColor = null
            return
        }
        if (room.themeColor != null) return
        val used = cachedRooms
            .filter { it.id != room.id }
            .mapNotNull { it.themeColor }
            .toSet()
        room.themeColor = RoomColorPalette.nextAvailable(used)
    }

    private fun ensureMissingThemeColors(): Boolean {
        var changed = false
        val used = cachedRooms.mapNotNull { it.themeColor }.toMutableSet()
        for (room in cachedRooms) {
            if (room.isSovereignTerritory) continue
            if (room.occupiedWallIds.isEmpty()) {
                if (room.themeColor != null) {
                    room.themeColor = null
                    changed = true
                }
                continue
            }
            if (room.themeColor == null) {
                val next = RoomColorPalette.nextAvailable(used)
                if (next != null) {
                    room.themeColor = next
                    used.add(next)
                    changed = true
                }
            }
        }
        return changed
    }
}
