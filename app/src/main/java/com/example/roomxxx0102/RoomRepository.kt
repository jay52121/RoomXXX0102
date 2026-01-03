package com.example.roomxxx0102

import android.content.Context
import android.graphics.PointF
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * **房间配置仓库 (Room Repository)**
 *
 * 负责 [RoomRegion] 数据的持久化存储与读取。
 * 使用 JSON 格式保存到外部存储 (App-Specific External Storage)，方便用户查看或备份。
 *
 * **文件位置**: /sdcard/Android/data/com.example.roomxxx0102/files/room_config.json
 */
class RoomRepository(private val context: Context) {

    companion object {
        private const val FILE_NAME = "room_config.json"
        private const val TAG = "RoomRepository"
    }

    private val configFile: File
        get() = File(context.getExternalFilesDir(null), FILE_NAME)

    /**
     * **保存单个房间配置**
     *
     * 如果已存在相同 ID 的房间，则更新它；否则追加到列表末尾。
     */
    fun saveRoom(room: RoomRegion) {
        val currentRooms = loadAllRooms().toMutableList()
        
        // 查找并替换，或者添加
        val index = currentRooms.indexOfFirst { it.id == room.id }
        if (index != -1) {
            currentRooms[index] = room
        } else {
            currentRooms.add(room)
        }

        saveToDisk(currentRooms)
    }

    /**
     * **加载所有房间配置**
     */
    fun loadAllRooms(): List<RoomRegion> {
        val file = configFile
        if (!file.exists()) return emptyList()

        return try {
            val jsonString = file.readText()
            parseJson(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "读取配置文件失败", e)
            emptyList()
        }
    }

    /**
     * **删除指定房间**
     */
    fun deleteRoom(roomId: String) {
        val currentRooms = loadAllRooms().filter { it.id != roomId }
        saveToDisk(currentRooms)
    }

    // --- Private Helper Methods ---

    private fun saveToDisk(rooms: List<RoomRegion>) {
        try {
            val jsonArray = JSONArray()
            for (room in rooms) {
                val roomObj = JSONObject()
                roomObj.put("id", room.id)
                roomObj.put("name", room.name)
                roomObj.put("type", room.type.name)

                val pointsArray = JSONArray()
                for (p in room.boundaryPoints) {
                    val pObj = JSONObject()
                    pObj.put("x", p.x.toDouble()) // PointF uses Float, JSON better with Double
                    pObj.put("y", p.y.toDouble())
                    pointsArray.put(pObj)
                }
                roomObj.put("boundary", pointsArray)

                jsonArray.put(roomObj)
            }

            val rootObj = JSONObject()
            rootObj.put("rooms", jsonArray)

            configFile.writeText(rootObj.toString(2)) // Indent 2 spaces
            Log.d(TAG, "配置已保存至: ${configFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "保存配置文件失败", e)
        }
    }

    private fun parseJson(jsonString: String): List<RoomRegion> {
        val list = ArrayList<RoomRegion>()
        try {
            val rootObj = JSONObject(jsonString)
            val jsonArray = rootObj.optJSONArray("rooms") ?: return emptyList()

            for (i in 0 until jsonArray.length()) {
                val roomObj = jsonArray.getJSONObject(i)
                val id = roomObj.getString("id")
                val name = roomObj.getString("name")
                val typeStr = roomObj.getString("type")
                val type = try {
                    RoomType.valueOf(typeStr)
                } catch (e: Exception) {
                    RoomType.SOVEREIGN_TERRITORY // Default fallback
                }

                val boundaryList = ArrayList<PointF>()
                val pointsArray = roomObj.optJSONArray("boundary")
                if (pointsArray != null) {
                    for (j in 0 until pointsArray.length()) {
                        val pObj = pointsArray.getJSONObject(j)
                        val x = pObj.getDouble("x").toFloat()
                        val y = pObj.getDouble("y").toFloat()
                        boundaryList.add(PointF(x, y))
                    }
                }

                list.add(RoomRegion(id, name, type, boundaryList))
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 JSON 失败", e)
        }
        return list
    }
}
