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
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.utils.GeometryUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * **客厅区域编辑器 View (Living Room Editor)**
 */
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
            selectedRoomId = null // 切换模式时清除选中
            invalidate()
        }

    // --- 数据 ---
    private val manualPoints = mutableListOf<PointF>()
    private var subRooms: MutableList<RoomConfig> = mutableListOf()
    
    // 🔥 选中状态
    var selectedRoomId: String? = null
        private set

    private var activeRoomId: String? = null // 正在拖拽的 ID

    private var onAddSubRoom: ((PointF) -> Unit)? = null
    private var onRoomSelected: ((RoomConfig?) -> Unit)? = null // 选中回调
    
    var backgroundBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    // --- 绘图相关 ---
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
        color = Color.parseColor("#FFEB3B") // 选中时黄色高亮
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
    private val touchRadius = 70f // 增大判定范围
    private val edgeClickRadius = 60f
    private val snapThreshold = 0.03f 

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentMode == EditorMode.LIVING_ROOM_HULL) {
                return handleDeletePoint(e.x, e.y)
            }
            return false
        }
        
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (currentMode == EditorMode.SUB_ROOM_ANCHOR) {
                // 处理选中逻辑
                val clickedRoom = findRoomAt(e.x, e.y)
                selectedRoomId = clickedRoom?.id
                onRoomSelected?.invoke(clickedRoom)
                invalidate()
                return true
            }
            return false
        }
    })

    // --- 公开接口 ---

    fun setSubRooms(rooms: List<RoomConfig>) {
        subRooms.clear()
        subRooms.addAll(rooms)
        invalidate()
    }

    fun setOnSubRoomListener(onAdd: (PointF) -> Unit, onSelected: (RoomConfig?) -> Unit) {
        this.onAddSubRoom = onAdd
        this.onRoomSelected = onSelected
    }

    fun clearSelection() {
        selectedRoomId = null
        onRoomSelected?.invoke(null)
        invalidate()
    }

    fun setHistoryPoints(points: List<PointF>) {
        manualPoints.clear()
        if (points.isEmpty()) initDefaultSquare() else manualPoints.addAll(points)
        invalidate()
    }

    fun undo() {
        if (currentMode == EditorMode.LIVING_ROOM_HULL && manualPoints.size > 3) {
            manualPoints.removeAt(manualPoints.lastIndex); invalidate()
        }
    }

    fun clear() {
        if (currentMode == EditorMode.LIVING_ROOM_HULL) {
            manualPoints.clear(); initDefaultSquare(); invalidate()
        }
    }

    fun getResult(): List<PointF> {
        return manualPoints.toList()
    }

    // --- 内部逻辑 ---

    private fun initDefaultSquare() {
        manualPoints.clear()
        manualPoints.add(PointF(0.25f, 0.25f))
        manualPoints.add(PointF(0.75f, 0.25f))
        manualPoints.add(PointF(0.75f, 0.75f))
        manualPoints.add(PointF(0.25f, 0.75f))
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

    private fun handleDeletePoint(x: Float, y: Float): Boolean {
        if (manualPoints.size <= 3) return false
        for (i in manualPoints.indices) {
            val p = manualPoints[i]
            val screenP = toScreen(p.x, p.y)
            if (hypot(screenP.x - x, screenP.y - y) < touchRadius) {
                manualPoints.removeAt(i); invalidate(); return true
            }
        }
        return false
    }

    private fun getClosestEdgeIndex(x: Float, y: Float): Int {
        if (manualPoints.size < 2) return -1
        var bestIndex = -1
        var minDistance = Float.MAX_VALUE
        val size = manualPoints.size
        for (i in 0 until size) {
            val p1 = toScreen(manualPoints[i].x, manualPoints[i].y)
            val p2 = toScreen(manualPoints[(i + 1) % size].x, manualPoints[(i + 1) % size].y)
            val dist = pointToSegmentDistance(x, y, p1.x, p1.y, p2.x, p2.y)
            if (dist < edgeClickRadius && dist < minDistance) {
                minDistance = dist; bestIndex = i
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) return true
        val x = event.x; val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (currentMode == EditorMode.LIVING_ROOM_HULL) {
                    draggingIndex = -1
                    for (i in manualPoints.indices) {
                        val p = manualPoints[i]
                        val screenP = toScreen(p.x, p.y)
                        if (hypot(screenP.x - x, screenP.y - y) < touchRadius) {
                            draggingIndex = i; return true
                        }
                    }
                    val edgeIndex = getClosestEdgeIndex(x, y)
                    if (edgeIndex != -1) {
                        manualPoints.add(edgeIndex + 1, toNorm(x, y))
                        draggingIndex = edgeIndex + 1; invalidate(); return true
                    }
                } else {
                    activeRoomId = null
                    val clickedRoom = findRoomAt(x, y)
                    if (clickedRoom != null) {
                        activeRoomId = clickedRoom.id
                        selectedRoomId = clickedRoom.id
                        onRoomSelected?.invoke(clickedRoom)
                        return true 
                    } else {
                        // 未点击中棋子，如果是客厅外，则落子新建
                        val normP = toNorm(x, y)
                        if (!GeometryUtils.isPointInPolygon(normP, manualPoints)) {
                            onAddSubRoom?.invoke(normP)
                        } else {
                            // 点击在客厅内，视为取消选中
                            clearSelection()
                        }
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentMode == EditorMode.LIVING_ROOM_HULL) {
                    if (draggingIndex != -1 && draggingIndex < manualPoints.size) {
                        val normP = toNorm(x, y)
                        var nx = normP.x; var ny = normP.y
                        if (abs(nx - 0f) < snapThreshold) nx = 0f
                        if (abs(nx - 1f) < snapThreshold) nx = 1f
                        if (abs(ny - 0f) < snapThreshold) ny = 0f
                        if (abs(ny - 1f) < snapThreshold) ny = 1f
                        manualPoints[draggingIndex] = PointF(nx, ny); invalidate()
                    }
                } else {
                    activeRoomId?.let { id ->
                        subRooms.find { it.id == id }?.let { it.anchorPoint = toNorm(x, y); invalidate() }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingIndex = -1; activeRoomId = null; return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        val bmp = backgroundBitmap
        if (bmp != null && !bmp.isRecycled) {
            val scale = min(w / bmp.width, h / bmp.height)
            val drawW = bmp.width * scale; val drawH = bmp.height * scale
            val left = (w - drawW) / 2; val top = (h - drawH) / 2
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        } else { dstRect.set(0f, 0f, w, h) }

        if (manualPoints.isEmpty() && w > 0 && h > 0) initDefaultSquare()
        if (manualPoints.isNotEmpty()) {
            val path = Path()
            val start = toScreen(manualPoints[0].x, manualPoints[0].y)
            path.moveTo(start.x, start.y)
            for (i in 1 until manualPoints.size) {
                val p = toScreen(manualPoints[i].x, manualPoints[i].y)
                path.lineTo(p.x, p.y)
            }
            path.close(); canvas.drawPath(path, polygonFillPaint); canvas.drawPath(path, polygonStrokePaint)
        }

        if (currentMode == EditorMode.LIVING_ROOM_HULL) {
            for (p in manualPoints) {
                val screenP = toScreen(p.x, p.y)
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
