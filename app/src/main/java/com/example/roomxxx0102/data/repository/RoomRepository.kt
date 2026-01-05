package com.example.roomxxx0102.data.repository

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.example.roomxxx0102.data.model.BoundaryVertex
import com.example.roomxxx0102.data.model.RoomConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object RoomRepository {

    private const val TAG = "RoomRepository"
    private const val FILE_NAME = "room_config.json"

    private val cachedRooms = CopyOnWriteArrayList<RoomConfig>()
    private var configFile: File? = null

    fun init(context: Context) {
        configFile = File(context.filesDir, FILE_NAME)
        loadFromFile()
    }

    fun getAllRooms(): List<RoomConfig> {
        return cachedRooms.toList()
    }

    fun getSubRooms(): List<RoomConfig> {
        return cachedRooms.filter { !it.isSovereignTerritory }
    }

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
            saveToFile()
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

    private fun loadFromFile() {
        cachedRooms.clear()
        val file = configFile ?: return

        if (!file.exists()) {
            Log.i(TAG, "Config file missing, creating defaults")
            createDefaultRoom()
            return
        }

        try {
            val jsonString = file.readText()
            val rootObj = JSONObject(jsonString)
            val jsonArray = rootObj.optJSONArray("rooms") ?: JSONArray()

            for (i in 0 until jsonArray.length()) {
                val roomObj = jsonArray.getJSONObject(i)

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

                val occupiedWallIds = ArrayList<Int>()
                val occupiedArray = roomObj.optJSONArray("occupiedWallIds")
                if (occupiedArray != null) {
                    for (j in 0 until occupiedArray.length()) {
                        occupiedWallIds.add(occupiedArray.optInt(j))
                    }
                }

                val room = RoomConfig(
                    id = roomObj.getString("id"),
                    name = roomObj.getString("name"),
                    isSovereignTerritory = roomObj.optBoolean("isSovereign", false),
                    isRecorded = roomObj.optBoolean("isRecorded", false),
                    boundaryVertices = boundaryVertices,
                    occupiedWallIds = occupiedWallIds,
                    anchorPoint = anchor
                )
                cachedRooms.add(room)
            }
            Log.i(TAG, "Loaded ${cachedRooms.size} rooms")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load room config", e)
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

                jsonArray.put(roomObj)
            }

            rootObj.put("rooms", jsonArray)
            file.writeText(rootObj.toString(2))
            Log.d(TAG, "Config saved: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save room config", e)
        }
    }
}
