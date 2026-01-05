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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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

    private var onAddSubRoom: ((PointF) -> Unit)? = null
    private var onRoomSelected: ((RoomConfig?) -> Unit)? = null

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
    private val pawnSelectedPaint = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val pawnStrokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private var draggingIndex = -1
    private val touchRadius = 70f
    private val edgeClickRadius = 60f
    private val snapThreshold = 0.03f

    private var nextVertexId = 1
    private var nextEdgeId = 1
    private val removedEdgeIds = mutableSetOf<Int>()
    private var isAddSubRoomArmed = false

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
                selectedRoomId = clickedRoom?.id
                onRoomSelected?.invoke(clickedRoom)
                invalidate()
                return true
            }
            return false
        }
    })

    fun setSubRooms(rooms: List<RoomConfig>) {
        subRooms.clear()
        subRooms.addAll(rooms)
        invalidate()
    }

    fun setOnSubRoomListener(onAdd: (PointF) -> Unit, onSelected: (RoomConfig?) -> Unit) {
        this.onAddSubRoom = onAdd
        this.onRoomSelected = onSelected
    }

    fun setAddSubRoomArmed(armed: Boolean) {
        isAddSubRoomArmed = armed
    }

    fun clearSelection() {
        selectedRoomId = null
        onRoomSelected?.invoke(null)
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
                invalidate()
            }
        }
    }

    fun clear() {
        if (currentMode == EditorMode.LIVING_ROOM_HULL) {
            markAllEdgesRemoved()
            initDefaultPolygon()
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
            room.anchorPoint?.let { anchor ->
                val screenP = toScreen(anchor.x, anchor.y)
                if (hypot(screenP.x - x, screenP.y - y) < touchRadius) return room
            }
        }
        return null
    }

    private fun handleDeleteVertex(x: Float, y: Float): Boolean {
        if (boundaryVertices.size <= 3) return false
        for (i in boundaryVertices.indices) {
            val p = boundaryVertices[i].point
            val screenP = toScreen(p.x, p.y)
            if (hypot(screenP.x - x, screenP.y - y) < touchRadius) {
                if (removeVertexAt(i)) {
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
                        invalidate()
                        return true
                    }
                } else {
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
                    } else {
                        clearSelection()
                        return true
                    }
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
                        boundaryVertices[draggingIndex].point = PointF(nx, ny)
                        invalidate()
                    }
                } else {
                    if (isAddSubRoomArmed) return true
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
                activeRoomId = null
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
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
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
        } else {
            for (room in subRooms) {
                room.anchorPoint?.let { anchor ->
                    val screenP = toScreen(anchor.x, anchor.y)
                    val isSelected = room.id == selectedRoomId
                    drawPawn(canvas, screenP.x, screenP.y, room.name, isSelected)
                }
            }
        }
    }

    private fun drawPawn(canvas: Canvas, x: Float, y: Float, name: String, isSelected: Boolean) {
        val r = 15f
        val paint = if (isSelected) pawnSelectedPaint else pawnFillPaint
        canvas.drawCircle(x, y, r, paint)
        canvas.drawCircle(x, y, r, pawnStrokePaint)
        canvas.drawCircle(x, y - r * 1.2f, r * 0.7f, paint)
        canvas.drawCircle(x, y - r * 1.2f, r * 0.7f, pawnStrokePaint)
        canvas.drawText(name, x, y - r * 2.5f, textPaint)
    }
}
