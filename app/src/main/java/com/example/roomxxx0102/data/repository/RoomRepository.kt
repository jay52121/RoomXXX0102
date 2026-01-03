package com.example.roomxxx0102.data.repository

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.example.roomxxx0102.data.model.RoomConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **房间数据仓库 (Room Repository)**
 *
 * 负责管理房间数据的内存缓存与本地文件持久化。
 * 使用 JSON 格式存储。
 */
object RoomRepository {

    private const val TAG = "RoomRepository"
    private const val FILE_NAME = "room_config.json"

    private val cachedRooms = CopyOnWriteArrayList<RoomConfig>()
    private var configFile: File? = null

    /**
     * **初始化仓库**
     * 必须在应用启动时调用 (如 MainActivity.onCreate)
     */
    fun init(context: Context) {
        configFile = File(context.filesDir, FILE_NAME)
        loadFromFile()
    }

    // --- 公开查询接口 ---

    fun getAllRooms(): List<RoomConfig> {
        return cachedRooms.toList()
    }

    fun getSubRooms(): List<RoomConfig> {
        return cachedRooms.filter { !it.isSovereignTerritory }
    }

    // --- 增删改查与自动保存 ---

    fun addNewRoom(name: String, anchor: PointF? = null) {
        val newRoom = RoomConfig(
            name = name,
            isSovereignTerritory = false,
            isRecorded = false,
            anchorPoint = anchor
        )
        cachedRooms.add(newRoom)
        saveToFile()
    }
    
    fun updateRoom(room: RoomConfig) {
        val index = cachedRooms.indexOfFirst { it.id == room.id }
        if (index != -1) {
            cachedRooms[index] = room
            saveToFile()
        }
    }

    fun deleteRoom(roomId: String): Boolean {
        val room = cachedRooms.find { it.id == roomId } ?: return false
        if (room.isSovereignTerritory) return false
        
        cachedRooms.remove(room)
        saveToFile()
        return true
    }

    fun markRoomAsRecorded(roomId: String) {
        val index = cachedRooms.indexOfFirst { it.id == roomId }
        if (index != -1) {
            val room = cachedRooms[index]
            cachedRooms[index] = room.copy(isRecorded = true)
            saveToFile()
        }
    }

    /**
     * **更新房间边界数据并保存**
     */
    fun updateRoomBoundary(roomId: String, points: List<PointF>) {
        val index = cachedRooms.indexOfFirst { it.id == roomId }
        if (index != -1) {
            val room = cachedRooms[index]
            val isRecorded = points.size >= 3
            cachedRooms[index] = room.copy(
                boundaryPoints = points,
                isRecorded = isRecorded
            )
            Log.d(TAG, "更新房间 [$roomId] 边界: ${points.size} 个点")
            saveToFile()
        } else {
            Log.w(TAG, "未找到房间 ID: $roomId")
        }
    }
    
    fun saveRoomBoundary(roomId: String, points: List<PointF>) {
        updateRoomBoundary(roomId, points)
    }

    fun resetAllStatus() {
        // ...
    }

    // --- 内部持久化逻辑 (JSON) ---

    private fun loadFromFile() {
        cachedRooms.clear()
        val file = configFile ?: return

        if (!file.exists()) {
            Log.i(TAG, "配置文件不存在，创建默认配置")
            createDefaultRoom()
            return
        }

        try {
            val jsonString = file.readText()
            val rootObj = JSONObject(jsonString)
            val jsonArray = rootObj.optJSONArray("rooms") ?: JSONArray()

            for (i in 0 until jsonArray.length()) {
                val roomObj = jsonArray.getJSONObject(i)
                
                // 解析边界点
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
                
                // 解析锚点
                var anchor: PointF? = null
                val anchorObj = roomObj.optJSONObject("anchor")
                if (anchorObj != null) {
                    val x = anchorObj.optDouble("x", 0.0).toFloat()
                    val y = anchorObj.optDouble("y", 0.0).toFloat()
                    anchor = PointF(x, y)
                }

                val room = RoomConfig(
                    id = roomObj.getString("id"),
                    name = roomObj.getString("name"),
                    isSovereignTerritory = roomObj.optBoolean("isSovereign", false),
                    isRecorded = roomObj.optBoolean("isRecorded", false),
                    boundaryPoints = boundaryList,
                    anchorPoint = anchor
                )
                cachedRooms.add(room)
            }
            Log.i(TAG, "成功加载 ${cachedRooms.size} 个房间配置")

        } catch (e: Exception) {
            Log.e(TAG, "加载配置文件失败", e)
            createDefaultRoom() 
        }
    }

    private fun createDefaultRoom() {
        cachedRooms.clear()
        cachedRooms.add(
            RoomConfig(
                id = "living_room",
                name = "客厅",
                isSovereignTerritory = true,
                isRecorded = false
            )
        )
        saveToFile()
    }

    private fun saveToFile() {
        val file = configFile ?: return
        try {
            val rootObj = JSONObject()
            val jsonArray = JSONArray()

            for (room in cachedRooms) {
                val roomObj = JSONObject()
                roomObj.put("id", room.id)
                roomObj.put("name", room.name)
                roomObj.put("isSovereign", room.isSovereignTerritory)
                roomObj.put("isRecorded", room.isRecorded)

                // 序列化边界点
                val boundaryArray = JSONArray()
                for (p in room.boundaryPoints) {
                    val pObj = JSONObject()
                    pObj.put("x", p.x.toDouble())
                    pObj.put("y", p.y.toDouble())
                    boundaryArray.put(pObj)
                }
                roomObj.put("boundary", boundaryArray)
                
                // 序列化锚点
                room.anchorPoint?.let { p ->
                    val anchorObj = JSONObject()
                    anchorObj.put("x", p.x.toDouble())
                    anchorObj.put("y", p.y.toDouble())
                    roomObj.put("anchor", anchorObj)
                }

                jsonArray.put(roomObj)
            }

            rootObj.put("rooms", jsonArray)
            file.writeText(rootObj.toString(2))
            Log.d(TAG, "配置已保存至: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "保存配置文件失败", e)
        }
    }
}
