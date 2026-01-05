package com.example.roomxxx0102.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.example.roomxxx0102.R
import com.example.roomxxx0102.data.model.BoundaryVertex
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.utils.GeometryUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class LivingRoomEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class EditorMode {
        LIVING_ROOM_HULL,
        SUB_ROOM_ANCHOR
    }

    private companion object {
        private const val TAG = "LivingRoomEditorView"
    }

    var currentMode: EditorMode = EditorMode.LIVING_ROOM_HULL
        set(value) {
            field = value
            selectedRoomId = null
            invalidate()
        }

    private val boundaryVertices = mutableListOf<BoundaryVertex>()
    private var subRooms: MutableList<RoomConfig> = mutableListOf()

    var selectedRoomId: String? = null
        private set

    private var activeRoomId: String? = null
    private var draggingLabelRoomId: String? = null

    private var onAddSubRoom: ((PointF) -> Unit)? = null
    private var onRoomSelected: ((RoomConfig?) -> Unit)? = null
    private var onRoomUpdated: ((RoomConfig) -> Unit)? = null

    // 🔥 新增：是否绘制背景图
    var drawBackground: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var backgroundBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    private val srcRect = Rect()
    private val dstRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val polygonFillPaint = Paint().apply {
        color = Color.parseColor("#40FFA500")
        style = Paint.Style.FILL
    }
    private val polygonStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val vertexPaint = Paint().apply {
        color = Color.parseColor("#FFA500")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pawnFillPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val pawnStrokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val pawnSelectedStrokePaint = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val edgeAvailablePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    private val edgeOccupiedPaint = Paint().apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    private val edgeSelectedPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        isAntiAlias = true
    }
    private val edgeThemePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val regionFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val regionVertexPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val regionStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private var draggingIndex = -1
    private var regionDraggingIndex = -1
    private val touchRadius = 70f
    private val edgeClickRadius = 60f
    private val snapThreshold = 0.03f

    private var nextVertexId = 1
    private var nextEdgeId = 1
    private val removedEdgeIds = mutableSetOf<Int>()
    private var isAddSubRoomArmed = false
    private var isDoorSelectArmed = false
    private var isRoomAreaEditArmed = false
    private var pendingDoorEdgeId: Int? = null
    private var pendingClearDoor = false
    private var regionEditPoints = mutableListOf<PointF>()
    private var regionEditInitial = emptyList<PointF>()
    private var regionEditRoomId: String? = null
    private val regionEditHistory = ArrayDeque<List<PointF>>()
    private var regionEditDebugLogged = false
    private var regionEditDrawLogged = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentMode == EditorMode.LIVING_ROOM_HULL) {
                return handleDeleteVertex(e.x, e.y)
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (currentMode == EditorMode.SUB_ROOM_ANCHOR) {
                val clickedRoom = findRoomAt(e.x, e.y)
                if (clickedRoom != null) {
                    selectedRoomId = clickedRoom.id
                    onRoomSelected?.invoke(clickedRoom)
                    invalidate()
                    return true
                }
                return false
            }
            return false
        }
    })

    fun setSubRooms(rooms: List<RoomConfig>) {
        subRooms.clear()
        subRooms.addAll(rooms)
        invalidate()
    }

    fun setOnSubRoomListener(
        onAdd: (PointF) -> Unit,
        onSelected: (RoomConfig?) -> Unit,
        onUpdated: (RoomConfig) -> Unit
    ) {
        this.onAddSubRoom = onAdd
        this.onRoomSelected = onSelected
        this.onRoomUpdated = onUpdated
    }

    fun setAddSubRoomArmed(armed: Boolean) {
        isAddSubRoomArmed = armed
    }

    fun setDoorSelectArmed(armed: Boolean) {
        isDoorSelectArmed = armed
        if (armed) {
            pendingDoorEdgeId = null
            pendingClearDoor = false
        } else {
            discardPendingDoorSelection()
        }
    }

    fun setRoomAreaEditArmed(armed: Boolean) {
        isRoomAreaEditArmed = armed
        if (!armed) {
            regionDraggingIndex = -1
        }
    }

    fun clearPendingDoorSelection() {
        if (!isDoorSelectArmed) return
        pendingDoorEdgeId = null
        pendingClearDoor = true
        invalidate()
    }

    fun discardPendingDoorSelection() {
        pendingDoorEdgeId = null
        pendingClearDoor = false
        invalidate()
    }

    fun startSubRoomRegionEdit(room: RoomConfig): Boolean {
        if (room.occupiedWallIds.isEmpty()) {
            Toast.makeText(context, "区域编辑失败：未绑定房门", Toast.LENGTH_SHORT).show()
            return false
        }
        val anchor = room.labelPoint ?: room.anchorPoint ?: run {
            Toast.makeText(context, "区域编辑失败：缺少锚点", Toast.LENGTH_SHORT).show()
            return false
        }
        val edgeId = room.occupiedWallIds.first()
        val edgeIndex = boundaryVertices.indexOfFirst { it.id == edgeId }
        if (boundaryVertices.size < 2) {
            Toast.makeText(context, "区域编辑失败：客厅边界不足", Toast.LENGTH_SHORT).show()
            return false
        }
        if (edgeIndex == -1) {
            val edgeIds = boundaryVertices.joinToString(",") { it.id.toString() }
            Toast.makeText(
                context,
                "区域编辑失败：未找到门边ID=$edgeId，客厅边ID=[$edgeIds]",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        val a = boundaryVertices[edgeIndex].point
        val b = boundaryVertices[(edgeIndex + 1) % boundaryVertices.size].point
        val existing = room.boundaryVertices.map { it.point }
        regionEditInitial = listOf(PointF(a.x, a.y), PointF(b.x, b.y), PointF(anchor.x, anchor.y))
        regionEditPoints = if (existing.size >= 3 && isRegionEdgeMatched(existing, a, b)) {
            existing.map { PointF(it.x, it.y) }.toMutableList()
        } else {
            regionEditInitial.map { PointF(it.x, it.y) }.toMutableList()
        }
        regionEditRoomId = room.id
        regionEditHistory.clear()
        regionEditDebugLogged = false
        regionEditDrawLogged = false
        Log.d(
            TAG,
            "RegionEdit start: room=${room.id} edge=$edgeId idx=$edgeIndex " +
                "A=(${a.x},${a.y}) B=(${b.x},${b.y}) P=(${anchor.x},${anchor.y}) " +
                "points=${regionEditPoints.size} usedExisting=${existing.size >= 3 && isRegionEdgeMatched(existing, a, b)}"
        )
        invalidate()
        return true
    }

    fun endSubRoomRegionEdit() {
        regionEditRoomId = null
        regionEditPoints.clear()
        regionEditInitial = emptyList()
        regionEditHistory.clear()
        regionDraggingIndex = -1
        regionEditDebugLogged = false
        regionEditDrawLogged = false
        invalidate()
    }

    fun getRegionEditPoints(): List<PointF> {
        return regionEditPoints.map { PointF(it.x, it.y) }
    }

    fun undoRegionEdit(): Boolean {
        val prev = regionEditHistory.removeLastOrNull() ?: return false
        regionEditPoints = prev.map { PointF(it.x, it.y) }.toMutableList()
        invalidate()
        return true
    }

    fun restoreRegionEdit(): Boolean {
        if (regionEditInitial.isEmpty()) return false
        regionEditPoints = regionEditInitial.map { PointF(it.x, it.y) }.toMutableList()
        regionEditHistory.clear()
        invalidate()
        return true
    }

    fun deleteRegionEdit(): Boolean {
        regionEditPoints.clear()
        invalidate()
        return true
    }

    fun commitDoorSelection(): Boolean {
        if (!isDoorSelectArmed) return false
        val selectedRoom = subRooms.find { it.id == selectedRoomId } ?: return false
        if (pendingDoorEdgeId == null && !pendingClearDoor) return false
        val prevEdgeId = selectedRoom.occupiedWallIds.firstOrNull()
        selectedRoom.occupiedWallIds.clear()
        if (!pendingClearDoor) {
            pendingDoorEdgeId?.let { selectedRoom.occupiedWallIds.add(it) }
        }
        val newEdgeId = selectedRoom.occupiedWallIds.firstOrNull()
        if (pendingClearDoor || (prevEdgeId != null && newEdgeId != prevEdgeId)) {
            selectedRoom.boundaryVertices.clear()
        }
        onRoomUpdated?.invoke(selectedRoom)
        pendingDoorEdgeId = null
        pendingClearDoor = false
        invalidate()
        return true
    }

    fun clearSelection() {
        selectedRoomId = null
        onRoomSelected?.invoke(null)
        invalidate()
    }

    fun getRegionEditRoomId(): String? = regionEditRoomId

    fun setSelectedRoomId(roomId: String?, notify: Boolean = true) {
        selectedRoomId = roomId
        if (notify) {
            val room = roomId?.let { id -> subRooms.find { it.id == id } }
            onRoomSelected?.invoke(room)
        }
        invalidate()
    }

    fun setHistoryVertices(vertices: List<BoundaryVertex>) {
        boundaryVertices.clear()
        removedEdgeIds.clear()
        if (vertices.isEmpty()) {
            initDefaultPolygon()
        } else {
            boundaryVertices.addAll(copyVertices(vertices))
            syncNextIds()
        }
        invalidate()
    }

    fun undo() {
        if (currentMode == EditorMode.LIVING_ROOM_HULL && boundaryVertices.size > 3) {
            if (removeVertexAt(boundaryVertices.lastIndex)) {
                updateSubRoomRegionsForLivingRoomChange()
                invalidate()
            }
        }
    }

    fun clear() {
        if (currentMode == EditorMode.LIVING_ROOM_HULL) {
            markAllEdgesRemoved()
            initDefaultPolygon()
            updateSubRoomRegionsForLivingRoomChange()
            invalidate()
        }
    }

    fun getResult(): List<BoundaryVertex> {
        return copyVertices(boundaryVertices)
    }

    fun consumeRemovedEdgeIds(): Set<Int> {
        val removed = removedEdgeIds.toSet()
        removedEdgeIds.clear()
        return removed
    }

    private fun initDefaultPolygon() {
        boundaryVertices.clear()
        resetIdCounters()
        boundaryVertices.add(newVertex(PointF(0.25f, 0.25f)))
        boundaryVertices.add(newVertex(PointF(0.75f, 0.25f)))
        boundaryVertices.add(newVertex(PointF(0.75f, 0.75f)))
        boundaryVertices.add(newVertex(PointF(0.25f, 0.75f)))
    }

    private fun resetIdCounters() {
        nextVertexId = 1
        nextEdgeId = 1
    }

    private fun syncNextIds() {
        nextVertexId = (boundaryVertices.maxOfOrNull { it.id } ?: 0) + 1
        nextEdgeId = (boundaryVertices.maxOfOrNull { it.edgeIdToNext } ?: 0) + 1
    }

    private fun newVertex(point: PointF, edgeIdToNext: Int? = null): BoundaryVertex {
        val edgeId = edgeIdToNext ?: nextEdgeId++
        return BoundaryVertex(nextVertexId++, point, edgeId)
    }

    private fun copyVertices(vertices: List<BoundaryVertex>): MutableList<BoundaryVertex> {
        return vertices.map {
            BoundaryVertex(it.id, PointF(it.point.x, it.point.y), it.edgeIdToNext)
        }.toMutableList()
    }

    private fun markAllEdgesRemoved() {
        removedEdgeIds.addAll(boundaryVertices.map { it.edgeIdToNext })
    }

    private fun toNorm(screenX: Float, screenY: Float): PointF {
        if (dstRect.width() == 0f || dstRect.height() == 0f) return PointF(0f, 0f)
        val nx = (screenX - dstRect.left) / dstRect.width()
        val ny = (screenY - dstRect.top) / dstRect.height()
        return PointF(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    private fun toScreen(normX: Float, normY: Float): PointF {
        val sx = dstRect.left + normX * dstRect.width()
        val sy = dstRect.top + normY * dstRect.height()
        return PointF(sx, sy)
    }

    private fun findRoomAt(x: Float, y: Float): RoomConfig? {
        for (room in subRooms) {
            getDisplayPoint(room)?.let { anchor ->
                val screenP = toScreen(anchor.x, anchor.y)
                if (hypot(screenP.x - x, screenP.y - y) < touchRadius) return room
            }
        }
        return null
    }

    private fun getDisplayPoint(room: RoomConfig): PointF? {
        return room.labelPoint ?: room.anchorPoint
    }

    private fun handleDeleteVertex(x: Float, y: Float): Boolean {
        if (boundaryVertices.size <= 3) return false
        for (i in boundaryVertices.indices) {
            val p = boundaryVertices[i].point
            val screenP = toScreen(p.x, p.y)
            if (hypot(screenP.x - x, screenP.y - y) < touchRadius) {
                if (removeVertexAt(i)) {
                    updateSubRoomRegionsForLivingRoomChange()
                    invalidate()
                    return true
                }
            }
        }
        return false
    }

    private fun getClosestEdgeIndex(x: Float, y: Float): Int {
        if (boundaryVertices.size < 2) return -1
        var bestIndex = -1
        var minDistance = Float.MAX_VALUE
        val size = boundaryVertices.size
        for (i in 0 until size) {
            val p1 = toScreen(boundaryVertices[i].point.x, boundaryVertices[i].point.y)
            val p2 = toScreen(boundaryVertices[(i + 1) % size].point.x, boundaryVertices[(i + 1) % size].point.y)
            val dist = pointToSegmentDistance(x, y, p1.x, p1.y, p2.x, p2.y)
            if (dist < edgeClickRadius && dist < minDistance) {
                minDistance = dist
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun pointToSegmentDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val l2 = (x1 - x2).pow(2) + (y1 - y2).pow(2)
        if (l2 == 0f) return hypot(px - x1, py - y1)
        var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
        t = max(0f, min(1f, t))
        return hypot(px - (x1 + t * (x2 - x1)), py - (y1 + t * (y2 - y1)))
    }

    private fun insertVertexAfter(index: Int, point: PointF): Int {
        val oldEdgeId = boundaryVertices[index].edgeIdToNext
        removedEdgeIds.add(oldEdgeId)

        val edgeToNew = nextEdgeId++
        val edgeFromNew = nextEdgeId++

        boundaryVertices[index].edgeIdToNext = edgeToNew
        boundaryVertices.add(index + 1, BoundaryVertex(nextVertexId++, point, edgeFromNew))
        return index + 1
    }

    private fun removeVertexAt(index: Int): Boolean {
        if (boundaryVertices.size <= 3) return false

        val size = boundaryVertices.size
        val prevIndex = if (index == 0) size - 1 else index - 1
        val prevVertex = boundaryVertices[prevIndex]
        val currentVertex = boundaryVertices[index]

        removedEdgeIds.add(prevVertex.edgeIdToNext)
        removedEdgeIds.add(currentVertex.edgeIdToNext)

        boundaryVertices.removeAt(index)
        prevVertex.edgeIdToNext = nextEdgeId++
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) return true
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (currentMode == EditorMode.LIVING_ROOM_HULL) {
                    draggingIndex = -1
                    for (i in boundaryVertices.indices) {
                        val p = boundaryVertices[i].point
                        val screenP = toScreen(p.x, p.y)
                        if (hypot(screenP.x - x, screenP.y - y) < touchRadius) {
                            draggingIndex = i
                            return true
                        }
                    }
                    val edgeIndex = getClosestEdgeIndex(x, y)
                    if (edgeIndex != -1) {
                        val newIndex = insertVertexAfter(edgeIndex, toNorm(x, y))
                        draggingIndex = newIndex
                        updateSubRoomRegionsForLivingRoomChange()
                        invalidate()
                        return true
                    }
                } else {
                    if (!isAddSubRoomArmed && !isDoorSelectArmed) {
                        val hit = findRoomAt(x, y)
                        if (hit != null) {
                            selectedRoomId = hit.id
                            onRoomSelected?.invoke(hit)
                            if (hit.labelPoint == null && hit.anchorPoint != null) {
                                hit.labelPoint = PointF(hit.anchorPoint!!.x, hit.anchorPoint!!.y)
                                onRoomUpdated?.invoke(hit)
                            }
                            draggingLabelRoomId = hit.id
                            return true
                        }
                    }
                    if (isRoomAreaEditArmed) {
                        if (regionEditRoomId == null || regionEditPoints.size < 3) return true
                        regionDraggingIndex = -1
                        for (i in 2 until regionEditPoints.size) {
                            val p = regionEditPoints[i]
                            val screenP = toScreen(p.x, p.y)
                            if (hypot(screenP.x - x, screenP.y - y) < touchRadius) {
                                regionEditHistory.add(regionEditPoints.map { PointF(it.x, it.y) })
                                regionDraggingIndex = i
                                return true
                            }
                        }
                        if (regionEditPoints.size == 3) {
                            val p0 = toScreen(regionEditPoints[0].x, regionEditPoints[0].y)
                            val p1 = toScreen(regionEditPoints[1].x, regionEditPoints[1].y)
                            val p2 = toScreen(regionEditPoints[2].x, regionEditPoints[2].y)
                            val d12 = pointToSegmentDistance(x, y, p1.x, p1.y, p2.x, p2.y)
                            val d20 = pointToSegmentDistance(x, y, p2.x, p2.y, p0.x, p0.y)
                            val insertPoint = toNorm(x, y)
                            if (min(d12, d20) < edgeClickRadius) {
                                val newList = regionEditPoints.map { PointF(it.x, it.y) }.toMutableList()
                                if (d12 <= d20) {
                                    newList.add(2, insertPoint)
                                } else {
                                    newList.add(insertPoint)
                                }
                                if (isRegionPolygonValid(newList)) {
                                    regionEditHistory.add(regionEditPoints.map { PointF(it.x, it.y) })
                                    regionEditPoints = newList
                                    invalidate()
                                }
                                return true
                            }
                        }
                        return true
                    }

                    if (isAddSubRoomArmed) {
                        val normP = toNorm(x, y)
                        val polygon = boundaryVertices.map { it.point }
                        if (polygon.isNotEmpty() && !GeometryUtils.isPointInPolygon(normP, polygon)) {
                            onAddSubRoom?.invoke(normP)
                        }
                        return true
                    }

                    activeRoomId = null
                    val clickedRoom = findRoomAt(x, y)
                    if (clickedRoom != null) {
                        activeRoomId = clickedRoom.id
                        selectedRoomId = clickedRoom.id
                        onRoomSelected?.invoke(clickedRoom)
                        return true
                    }

                    if (selectedRoomId != null && isDoorSelectArmed) {
                        val normP = toNorm(x, y)
                        val polygon = boundaryVertices.map { it.point }
                        val edgeIndex = GeometryUtils.getClosestEdgeIndex(normP, polygon)
                        if (edgeIndex == -1) {
                            Toast.makeText(context, context.getString(R.string.toast_door_not_selected), Toast.LENGTH_SHORT).show()
                            return true
                        }

                        val p1 = toScreen(
                            boundaryVertices[edgeIndex].point.x,
                            boundaryVertices[edgeIndex].point.y
                        )
                        val p2 = toScreen(
                            boundaryVertices[(edgeIndex + 1) % boundaryVertices.size].point.x,
                            boundaryVertices[(edgeIndex + 1) % boundaryVertices.size].point.y
                        )
                        val dist = pointToSegmentDistance(x, y, p1.x, p1.y, p2.x, p2.y)
                        if (dist > edgeClickRadius) {
                            Toast.makeText(context, context.getString(R.string.toast_door_not_selected), Toast.LENGTH_SHORT).show()
                            return true
                        }

                        val edgeId = boundaryVertices[edgeIndex].id
                        val selectedRoom = subRooms.find { it.id == selectedRoomId } ?: return true
                        val occupiedByOther = subRooms.find {
                            it.id != selectedRoom.id && it.occupiedWallIds.contains(edgeId)
                        }
                        if (occupiedByOther != null) {
                            Toast.makeText(context, context.getString(R.string.toast_door_occupied_by, occupiedByOther.name), Toast.LENGTH_SHORT).show()
                            return true
                        }

                        pendingDoorEdgeId = edgeId
                        pendingClearDoor = false
                        Log.d(TAG, "Door pending set: room=$selectedRoomId edge=$edgeId")
                        invalidate()
                        return true
                    }

                    clearSelection()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentMode == EditorMode.LIVING_ROOM_HULL) {
                    if (draggingIndex != -1 && draggingIndex < boundaryVertices.size) {
                        val normP = toNorm(x, y)
                        var nx = normP.x
                        var ny = normP.y
                        if (abs(nx - 0f) < snapThreshold) nx = 0f
                        if (abs(nx - 1f) < snapThreshold) nx = 1f
                        if (abs(ny - 0f) < snapThreshold) ny = 0f
                        if (abs(ny - 1f) < snapThreshold) ny = 1f
                        val temp = boundaryVertices.map { PointF(it.point.x, it.point.y) }.toMutableList()
                        temp[draggingIndex] = PointF(nx, ny)
                        if (!GeometryUtils.checkSelfIntersection(temp)) {
                            boundaryVertices[draggingIndex].point = PointF(nx, ny)
                            updateSubRoomRegionsForLivingRoomChange()
                            invalidate()
                        }
                    }
                } else {
                    if (draggingLabelRoomId != null) {
                        val normP = toNorm(x, y)
                        val room = subRooms.find { it.id == draggingLabelRoomId }
                        if (room != null) {
                            val livingPolygon = boundaryVertices.map { it.point }
                            if (!GeometryUtils.isPointInPolygon(normP, livingPolygon)) {
                                room.labelPoint = PointF(normP.x, normP.y)
                                invalidate()
                            }
                        }
                        return true
                    }
                    if (isRoomAreaEditArmed) {
                        if (regionDraggingIndex >= 2 && regionDraggingIndex < regionEditPoints.size) {
                            val normP = toNorm(x, y)
                            val newList = regionEditPoints.map { PointF(it.x, it.y) }.toMutableList()
                            newList[regionDraggingIndex] = normP
                            if (isRegionPolygonValid(newList)) {
                                regionEditPoints = newList
                                invalidate()
                            }
                        }
                        return true
                    }
                    if (isAddSubRoomArmed || !isRoomAreaEditArmed) return true
                    activeRoomId?.let { id ->
                        subRooms.find { it.id == id }?.let {
                            it.anchorPoint = toNorm(x, y)
                            invalidate()
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingIndex = -1
                regionDraggingIndex = -1
                activeRoomId = null
                draggingLabelRoomId?.let { id ->
                    subRooms.find { it.id == id }?.let { onRoomUpdated?.invoke(it) }
                }
                draggingLabelRoomId = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val bmp = backgroundBitmap
        if (bmp != null && !bmp.isRecycled) {
            val scale = min(w / bmp.width, h / bmp.height)
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            val left = (w - drawW) / 2
            val top = (h - drawH) / 2
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(left, top, left + drawW, top + drawH)
            
            // 🔥 修改：仅当 drawBackground 为 true 时绘制，否则仅计算 dstRect
            if (drawBackground) {
                canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            }
        } else {
            dstRect.set(0f, 0f, w, h)
        }

        if (boundaryVertices.isEmpty() && w > 0 && h > 0) initDefaultPolygon()
        if (boundaryVertices.isNotEmpty()) {
            val path = Path()
            val start = toScreen(boundaryVertices[0].point.x, boundaryVertices[0].point.y)
            path.moveTo(start.x, start.y)
            for (i in 1 until boundaryVertices.size) {
                val p = toScreen(boundaryVertices[i].point.x, boundaryVertices[i].point.y)
                path.lineTo(p.x, p.y)
            }
            path.close()
            canvas.drawPath(path, polygonFillPaint)
            canvas.drawPath(path, polygonStrokePaint)
        }

        if (currentMode == EditorMode.LIVING_ROOM_HULL) {
            for (v in boundaryVertices) {
                val screenP = toScreen(v.point.x, v.point.y)
                canvas.drawCircle(screenP.x, screenP.y, 15f, vertexPaint)
            }
            if (boundaryVertices.size >= 2 && subRooms.isNotEmpty()) {
                for (i in boundaryVertices.indices) {
                    val edgeId = boundaryVertices[i].id
                    val owner = subRooms.find { it.occupiedWallIds.contains(edgeId) }
                    if (owner != null && owner.occupiedWallIds.isNotEmpty()) {
                        edgeThemePaint.color = owner.themeColor ?: Color.WHITE
                        val p1 = toScreen(boundaryVertices[i].point.x, boundaryVertices[i].point.y)
                        val p2 = toScreen(
                            boundaryVertices[(i + 1) % boundaryVertices.size].point.x,
                            boundaryVertices[(i + 1) % boundaryVertices.size].point.y
                        )
                        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, edgeThemePaint)
                    }
                }
            }
            for (room in subRooms) {
                if (isRoomAreaEditArmed && room.id == regionEditRoomId) {
                    continue
                }
                val points = room.boundaryVertices.map { it.point }
                if (points.size >= 3) {
                    val path = Path()
                    val start = toScreen(points[0].x, points[0].y)
                    path.moveTo(start.x, start.y)
                    for (i in 1 until points.size) {
                        val p = toScreen(points[i].x, points[i].y)
                        path.lineTo(p.x, p.y)
                    }
                    path.close()
                    val color = room.themeColor ?: Color.WHITE
                    regionFillPaint.color = Color.argb(85, Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawPath(path, regionFillPaint)
                }
            }
            for (room in subRooms) {
                getDisplayPoint(room)?.let { anchor ->
                    val screenP = toScreen(anchor.x, anchor.y)
                    val fillColor =
                        if (room.occupiedWallIds.isEmpty()) Color.WHITE else (room.themeColor ?: Color.WHITE)
                    drawPawn(canvas, screenP.x, screenP.y, room.name, false, fillColor)
                }
            }
        } else {
            val selectedRoom = subRooms.find { it.id == selectedRoomId }
            val highlightEdgeId = if (selectedRoom == null) {
                null
            } else if (pendingClearDoor) {
                null
            } else if (isDoorSelectArmed) {
                pendingDoorEdgeId
            } else {
                selectedRoom.occupiedWallIds.firstOrNull()
            }
            if (highlightEdgeId != null) {
                val i = boundaryVertices.indexOfFirst { it.id == highlightEdgeId }
                if (i != -1) {
                    val p1 = toScreen(boundaryVertices[i].point.x, boundaryVertices[i].point.y)
                    val p2 = toScreen(
                        boundaryVertices[(i + 1) % boundaryVertices.size].point.x,
                        boundaryVertices[(i + 1) % boundaryVertices.size].point.y
                    )
                    val shader = android.graphics.LinearGradient(
                        p1.x, p1.y, p2.x, p2.y,
                        intArrayOf(
                            Color.RED, Color.YELLOW, Color.GREEN,
                            Color.CYAN, Color.BLUE, Color.MAGENTA
                        ),
                        null,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    edgeSelectedPaint.shader = shader
                } else {
                    edgeSelectedPaint.shader = null
                }
            } else {
                edgeSelectedPaint.shader = null
            }
            if (boundaryVertices.size >= 2) {
                val selectedEdgeId = highlightEdgeId
                for (i in boundaryVertices.indices) {
                    val edgeId = boundaryVertices[i].id
                    val owner = subRooms.find { it.occupiedWallIds.contains(edgeId) }
                    val paint = if (selectedRoom != null && selectedEdgeId != null && edgeId == selectedEdgeId) {
                        edgeSelectedPaint
                    } else if (owner != null && owner.occupiedWallIds.isNotEmpty()) {
                        if (
                            selectedRoom != null &&
                            owner.id == selectedRoom.id &&
                            (pendingClearDoor || (isDoorSelectArmed && pendingDoorEdgeId != null))
                        ) {
                            edgeAvailablePaint
                        } else {
                            edgeThemePaint.color = owner.themeColor ?: Color.WHITE
                            edgeThemePaint
                        }
                    } else {
                        edgeAvailablePaint
                    }
                    val p1 = toScreen(boundaryVertices[i].point.x, boundaryVertices[i].point.y)
                    val p2 = toScreen(
                        boundaryVertices[(i + 1) % boundaryVertices.size].point.x,
                        boundaryVertices[(i + 1) % boundaryVertices.size].point.y
                    )
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                }
            }
            for (v in boundaryVertices) {
                val screenP = toScreen(v.point.x, v.point.y)
                canvas.drawCircle(screenP.x, screenP.y, 12f, vertexPaint)
            }
            for (room in subRooms) {
                if (isRoomAreaEditArmed && room.id == regionEditRoomId) {
                    continue
                }
                val points = room.boundaryVertices.map { it.point }
                if (points.size >= 3) {
                    val path = Path()
                    val start = toScreen(points[0].x, points[0].y)
                    path.moveTo(start.x, start.y)
                    for (i in 1 until points.size) {
                        val p = toScreen(points[i].x, points[i].y)
                        path.lineTo(p.x, p.y)
                    }
                    path.close()
                    val color = room.themeColor ?: Color.WHITE
                    regionFillPaint.color = Color.argb(85, Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawPath(path, regionFillPaint)
                }
            }
            val editRoom = regionEditRoomId?.let { id -> subRooms.find { it.id == id } }
            if (editRoom != null && regionEditPoints.size >= 3) {
                if (!regionEditDrawLogged) {
                    Log.d(
                        TAG,
                        "RegionEdit draw ok: room=${editRoom.id} points=${regionEditPoints.size} " +
                            "subRooms=${subRooms.size}"
                    )
                    regionEditDrawLogged = true
                }
                val path = Path()
                val start = toScreen(regionEditPoints[0].x, regionEditPoints[0].y)
                path.moveTo(start.x, start.y)
                for (i in 1 until regionEditPoints.size) {
                    val p = toScreen(regionEditPoints[i].x, regionEditPoints[i].y)
                    path.lineTo(p.x, p.y)
                }
                path.close()
                val color = editRoom.themeColor ?: Color.WHITE
                regionFillPaint.color = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawPath(path, regionFillPaint)
                val strokeColor = if (color == Color.WHITE) Color.CYAN else color
                regionStrokePaint.color = strokeColor
                canvas.drawPath(path, regionStrokePaint)
                val a = toScreen(regionEditPoints[0].x, regionEditPoints[0].y)
                val b = toScreen(regionEditPoints[1].x, regionEditPoints[1].y)
                canvas.drawCircle(a.x, a.y, 10f, regionVertexPaint)
                canvas.drawCircle(b.x, b.y, 10f, regionVertexPaint)
            }
            if (regionEditRoomId != null && regionEditPoints.size >= 3) {
                for (i in 2 until regionEditPoints.size) {
                    val p = toScreen(regionEditPoints[i].x, regionEditPoints[i].y)
                    canvas.drawCircle(p.x, p.y, 12f, regionVertexPaint)
                }
            } else if (regionEditRoomId != null && !regionEditDebugLogged) {
                Log.d(
                    TAG,
                    "RegionEdit draw missing: room=$regionEditRoomId points=${regionEditPoints.size}"
                )
                regionEditDebugLogged = true
            } else if (regionEditRoomId != null && !regionEditDrawLogged) {
                val ids = subRooms.joinToString(",") { it.id }
                Log.d(
                    TAG,
                    "RegionEdit draw skip: room=$regionEditRoomId subRooms=[$ids]"
                )
                regionEditDrawLogged = true
            }
            for (room in subRooms) {
                getDisplayPoint(room)?.let { anchor ->
                    val screenP = toScreen(anchor.x, anchor.y)
                    val isSelected = room.id == selectedRoomId
                    val fillColor =
                        if (room.occupiedWallIds.isEmpty()) Color.WHITE else (room.themeColor ?: Color.WHITE)
                    drawPawn(canvas, screenP.x, screenP.y, room.name, isSelected, fillColor)
                }
            }
        }
    }

    private fun drawPawn(
        canvas: Canvas,
        x: Float,
        y: Float,
        name: String,
        isSelected: Boolean,
        fillColor: Int
    ) {
        val r = 15f
        if (isSelected) {
            val shader = android.graphics.LinearGradient(
                x - r, y - r, x + r, y + r,
                intArrayOf(
                    Color.RED, Color.YELLOW, Color.GREEN,
                    Color.CYAN, Color.BLUE, Color.MAGENTA
                ),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
            pawnFillPaint.shader = shader
        } else {
            pawnFillPaint.shader = null
            pawnFillPaint.color = fillColor
        }
        canvas.drawCircle(x, y, r, pawnFillPaint)
        canvas.drawCircle(x, y, r, pawnStrokePaint)
        canvas.drawCircle(x, y - r * 1.2f, r * 0.7f, pawnFillPaint)
        canvas.drawCircle(x, y - r * 1.2f, r * 0.7f, pawnStrokePaint)
        if (isSelected) {
            canvas.drawCircle(x, y, r + 4f, pawnSelectedStrokePaint)
        }
        val oldTextColor = textPaint.color
        if (isSelected) {
            textPaint.color = fillColor
        }
        canvas.drawText(name, x, y - r * 2.5f, textPaint)
        textPaint.color = oldTextColor
    }

    private fun isRegionPolygonValid(points: List<PointF>): Boolean {
        if (points.size < 3 || points.size > 4) return false
        val livingPolygon = boundaryVertices.map { it.point }
        if (livingPolygon.isNotEmpty()) {
            for (p in points) {
                if (GeometryUtils.isPointInPolygon(p, livingPolygon) &&
                    !GeometryUtils.isPointOnPolygonBoundary(p, livingPolygon)
                ) {
                    return false
                }
            }
        }
        if (!GeometryUtils.isPolygonSimple(points)) return false
        return true
    }

    private fun isRegionEdgeMatched(points: List<PointF>, a: PointF, b: PointF): Boolean {
        if (points.size < 2) return false
        val p0 = points[0]
        val p1 = points[1]
        return (isSamePoint(p0, a) && isSamePoint(p1, b)) ||
            (isSamePoint(p0, b) && isSamePoint(p1, a))
    }

    private fun isSamePoint(p1: PointF, p2: PointF, eps: Float = 0.01f): Boolean {
        return kotlin.math.abs(p1.x - p2.x) <= eps && kotlin.math.abs(p1.y - p2.y) <= eps
    }

    private fun buildRegionVertices(points: List<PointF>): MutableList<BoundaryVertex> {
        val list = mutableListOf<BoundaryVertex>()
        var vid = 1
        var eid = 1
        for (p in points) {
            list.add(BoundaryVertex(vid++, PointF(p.x, p.y), eid++))
        }
        return list
    }

    private fun updateSubRoomRegionsForLivingRoomChange() {
        if (boundaryVertices.size < 2 || subRooms.isEmpty()) return
        val livingPolygon = boundaryVertices.map { it.point }
        for (room in subRooms) {
            if (room.occupiedWallIds.isEmpty()) continue
            if (room.boundaryVertices.size < 3) continue
            val edgeId = room.occupiedWallIds.first()
            val edgeIndex = boundaryVertices.indexOfFirst { it.id == edgeId }
            if (edgeIndex == -1) {
                room.boundaryVertices.clear()
                onRoomUpdated?.invoke(room)
                continue
            }
            val a = boundaryVertices[edgeIndex].point
            val b = boundaryVertices[(edgeIndex + 1) % boundaryVertices.size].point
            val points = room.boundaryVertices.map { it.point }.toMutableList()
            if (points.size < 3 || points.size > 4) continue
            points[0] = PointF(a.x, a.y)
            points[1] = PointF(b.x, b.y)
            val valid = isRegionPolygonValid(points) &&
                !GeometryUtils.checkSelfIntersection(points)
            if (!valid) {
                room.boundaryVertices.clear()
                onRoomUpdated?.invoke(room)
                continue
            }
            if (room.boundaryVertices.size == points.size) {
                for (i in points.indices) {
                    room.boundaryVertices[i].point = PointF(points[i].x, points[i].y)
                }
            } else {
                room.boundaryVertices = buildRegionVertices(points)
            }
            onRoomUpdated?.invoke(room)
        }
    }
}
