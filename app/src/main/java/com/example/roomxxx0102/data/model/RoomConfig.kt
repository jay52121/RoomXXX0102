package com.example.roomxxx0102.data.model

import android.graphics.PointF
import java.util.UUID

/**
 * Entrance segment in absolute coordinates.
 */
data class EntranceSegment(
    val id: String = UUID.randomUUID().toString(),
    var startPoint: PointF,
    var endPoint: PointF
)

/**
 * Boundary vertex with a stable edge id to the next vertex.
 */
data class BoundaryVertex(
    val id: Int,
    var point: PointF,
    var edgeIdToNext: Int
)

/**
 * Room configuration model.
 */
data class RoomConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String,

    // Boundary with vertex ids and edge ids.
    var boundaryVertices: MutableList<BoundaryVertex> = mutableListOf(),

    // Entrance segments (absolute coordinates).
    var entrances: MutableList<EntranceSegment> = mutableListOf(),

    // true = living room, false = sub room
    val isSovereignTerritory: Boolean = false,

    // Record status
    var isRecorded: Boolean = false,

    // Bound wall ids (typically from living room).
    var occupiedWallIds: MutableList<Int> = mutableListOf(),

    // Anchor point for sub rooms
    var anchorPoint: PointF? = null
) {
    val boundaryPoints: List<PointF>
        get() = boundaryVertices.map { it.point }
}
