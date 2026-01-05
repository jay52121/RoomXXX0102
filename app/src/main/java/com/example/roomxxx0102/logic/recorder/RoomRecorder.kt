package com.example.roomxxx0102.logic.recorder

import android.graphics.PointF
import android.util.Log
import com.example.roomxxx0102.data.model.BoundaryVertex
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.utils.GeometryUtils

enum class RecorderState {
    IDLE,
    RECORDING_SOVEREIGN,
    RECORDING_PORTAL
}

class RoomRecorder {

    private var currentState = RecorderState.IDLE
    private var currentRoomId: String = ""
    private var currentRoomName: String = ""

    private val recordedFootprintPoints = ArrayList<PointF>()
    private val recordedEscapePoints = ArrayList<PointF>()
    private var referenceSovereignHull: List<PointF> = emptyList()
    private val lastKnownPositions = HashMap<Int, PointF>()

    fun startRecordingSovereign(id: String, name: String) {
        currentState = RecorderState.RECORDING_SOVEREIGN
        currentRoomId = id
        currentRoomName = name
        recordedFootprintPoints.clear()
        Log.d("RoomRecorder", "Start recording living room: $name")
    }

    fun startRecordingPortal(id: String, name: String, livingRoomHull: List<PointF>) {
        if (livingRoomHull.isEmpty()) {
            Log.e("RoomRecorder", "Cannot record portal: living room hull missing")
            return
        }
        currentState = RecorderState.RECORDING_PORTAL
        currentRoomId = id
        currentRoomName = name
        referenceSovereignHull = livingRoomHull
        recordedEscapePoints.clear()
        lastKnownPositions.clear()
        Log.d("RoomRecorder", "Start recording portal: $name")
    }

    fun stopRecording(): RoomConfig? {
        val roomConfig = when (currentState) {
            RecorderState.RECORDING_SOVEREIGN -> {
                val hull = GeometryUtils.computeConvexHull(recordedFootprintPoints)
                if (hull.size >= 3) {
                    RoomConfig(
                        id = currentRoomId,
                        name = currentRoomName,
                        isSovereignTerritory = true,
                        isRecorded = true,
                        boundaryVertices = buildVerticesFromPoints(hull)
                    )
                } else {
                    Log.w("RoomRecorder", "Not enough points to build hull")
                    null
                }
            }
            RecorderState.RECORDING_PORTAL -> {
                if (recordedEscapePoints.isNotEmpty()) {
                    val box = computeBoundingBox(recordedEscapePoints)
                    RoomConfig(
                        id = currentRoomId,
                        name = currentRoomName,
                        isSovereignTerritory = false,
                        isRecorded = true,
                        boundaryVertices = buildVerticesFromPoints(box)
                    )
                } else {
                    Log.w("RoomRecorder", "No escape points captured")
                    null
                }
            }
            else -> null
        }

        currentState = RecorderState.IDLE
        recordedFootprintPoints.clear()
        recordedEscapePoints.clear()
        lastKnownPositions.clear()

        return roomConfig
    }

    fun processFrame(results: List<PoseResult>) {
        if (currentState == RecorderState.IDLE) return

        val currentFrameIds = HashSet<Int>()

        for (pose in results) {
            if (!pose.isConfirmed) continue

            val footPoint = extractFootPoint(pose) ?: continue
            currentFrameIds.add(pose.id)

            when (currentState) {
                RecorderState.RECORDING_SOVEREIGN -> {
                    recordedFootprintPoints.add(footPoint)
                }
                RecorderState.RECORDING_PORTAL -> {
                    lastKnownPositions[pose.id] = footPoint
                }
                else -> {}
            }
        }

        if (currentState == RecorderState.RECORDING_PORTAL) {
            val disappearedIds = lastKnownPositions.keys - currentFrameIds
            for (id in disappearedIds) {
                val lastPos = lastKnownPositions[id] ?: continue
                recordedEscapePoints.add(lastPos)
                Log.d("RoomRecorder", "Captured escape point: $lastPos (ID: $id)")
                lastKnownPositions.remove(id)
            }
        }
    }

    private fun extractFootPoint(pose: PoseResult): PointF? {
        val kpts = pose.keypoints
        if (kpts.size <= 16) return null

        val leftAnkle = kpts[15]
        val rightAnkle = kpts[16]

        if (leftAnkle.conf > 0.5f && rightAnkle.conf > 0.5f) {
            return PointF((leftAnkle.x + rightAnkle.x) / 2, (leftAnkle.y + rightAnkle.y) / 2)
        }
        return null
    }

    private fun computeBoundingBox(points: List<PointF>): List<PointF> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }

        val padding = 0.05f
        minX -= padding
        maxX += padding
        minY -= padding
        maxY += padding

        return listOf(
            PointF(minX, minY),
            PointF(maxX, minY),
            PointF(maxX, maxY),
            PointF(minX, maxY)
        )
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
}
