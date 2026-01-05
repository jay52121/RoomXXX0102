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
import android.view.MotionEvent
import android.view.View
import com.example.roomxxx0102.data.model.BoundaryVertex
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.logic.analyzer.TrackedDetection
import com.example.roomxxx0102.ui.drawers.DebugBoxDrawer
import com.example.roomxxx0102.ui.drawers.PoseDrawer

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var trackedObjects: List<TrackedDetection> = emptyList()
    private var poseResults: List<PoseResult> = emptyList()
    
    // 客厅区域数据
    private var livingRoomBoundary: List<PointF> = emptyList()
    private var livingRoomVertices: List<BoundaryVertex> = emptyList()
    // 🔥 次房间数据 (棋子)
    private var subRooms: List<RoomConfig> = emptyList()
    
    private var debugInfo = "Waiting..."
    private var currentFrame: Bitmap? = null
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }

    private var showDebugBoxes = false
    private var showCenterPoints = true 
    private var showPose = false
    // 🔥 新增：是否处于编辑模式
    private var isEditMode = false

    private val debugDrawer = DebugBoxDrawer()
    private val poseDrawer = PoseDrawer()

    private val movingPaint = Paint().apply {
        color = Color.GREEN
        textSize = 60f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val staticPaint = Paint().apply {
        color = Color.RED
        textSize = 60f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.WHITE)
    }

    private val infoPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 40f
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val regionFillPaint = Paint().apply {
        color = Color.parseColor("#30FFA500") 
        style = Paint.Style.FILL
    }
    private val regionStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val edgeThemePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        isAntiAlias = true
    }
    
    // 棋子画笔 (Cyan)
    private val pawnFillPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val pawnStrokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

    fun updateData(objects: List<TrackedDetection>, bitmap: Bitmap?, timeMs: Long) {
        trackedObjects = objects
        currentFrame = bitmap
        debugInfo = "Detect: ${timeMs}ms | Count: ${objects.size}"
        postInvalidate()
    }

    fun updatePoseData(results: List<PoseResult>, bitmap: Bitmap?, timeMs: Long) {
        poseResults = results
        currentFrame = bitmap
        debugInfo = "Pose: ${timeMs}ms | Count: ${results.size}"
        postInvalidate()
    }

    fun setLivingRoomBoundary(points: List<PointF>) {
        livingRoomBoundary = points
        postInvalidate()
    }

    fun setLivingRoomVertices(vertices: List<BoundaryVertex>) {
        livingRoomVertices = vertices
        postInvalidate()
    }
    
    // 🔥 更新棋子列表
    fun setSubRooms(rooms: List<RoomConfig>) {
        subRooms = rooms
        postInvalidate()
    }

    fun setDebugBoxState(show: Boolean) {
        showDebugBoxes = show
        postInvalidate()
    }

    fun setCenterPointState(show: Boolean) {
        showCenterPoints = show
        postInvalidate()
    }

    fun setPoseState(show: Boolean) {
        showPose = show
        postInvalidate()
    }

    // 🔥 新增：设置编辑模式状态
    fun setEditMode(isEditing: Boolean) {
        isEditMode = isEditing
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        var drawLeft = 0f
        var drawTop = 0f
        var drawWidth = w
        var drawHeight = h

        val bmp = currentFrame
        if (bmp != null && !bmp.isRecycled) {
            val bmpW = bmp.width.toFloat()
            val bmpH = bmp.height.toFloat()
            val scale = Math.min(w / bmpW, h / bmpH)
            drawWidth = bmpW * scale
            drawHeight = bmpH * scale
            drawLeft = (w - drawWidth) / 2
            drawTop = (h - drawHeight) / 2
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        } else {
            dstRect.set(0f, 0f, w, h)
        }

        // 🔥 修改：如果是编辑模式，不绘制房间区域和棋子，交由 LivingRoomEditorView 绘制
        if (!isEditMode) {
            // 1. 画客厅区域
            if (livingRoomBoundary.isNotEmpty()) {
                val path = Path()
                val startX = drawLeft + livingRoomBoundary[0].x * drawWidth
                val startY = drawTop + livingRoomBoundary[0].y * drawHeight
                path.moveTo(startX, startY)
                for (i in 1 until livingRoomBoundary.size) {
                    val px = drawLeft + livingRoomBoundary[i].x * drawWidth
                    val py = drawTop + livingRoomBoundary[i].y * drawHeight
                    path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, regionFillPaint)
                canvas.drawPath(path, regionStrokePaint)
            }

            if (livingRoomVertices.size >= 2 && subRooms.isNotEmpty()) {
                for (i in livingRoomVertices.indices) {
                    val edgeId = livingRoomVertices[i].id
                    val owner = subRooms.find { it.occupiedWallIds.contains(edgeId) }
                    if (owner != null && owner.occupiedWallIds.isNotEmpty()) {
                        edgeThemePaint.color = owner.themeColor ?: Color.WHITE
                        val p1 = livingRoomVertices[i].point
                        val p2 = livingRoomVertices[(i + 1) % livingRoomVertices.size].point
                        val x1 = drawLeft + p1.x * drawWidth
                        val y1 = drawTop + p1.y * drawHeight
                        val x2 = drawLeft + p2.x * drawWidth
                        val y2 = drawTop + p2.y * drawHeight
                        canvas.drawLine(x1, y1, x2, y2, edgeThemePaint)
                    }
                }
            }
            
            // 2. 🔥 画次房间棋子
            for (room in subRooms) {
                room.anchorPoint?.let { anchor ->
                    val px = drawLeft + anchor.x * drawWidth
                    val py = drawTop + anchor.y * drawHeight
                    val fillColor =
                        if (room.occupiedWallIds.isEmpty()) Color.WHITE else (room.themeColor ?: Color.WHITE)
                    drawPawn(canvas, px, py, room.name, fillColor)
                }
            }
        }

        // 3. 画调试信息
        canvas.drawText(debugInfo, 40f, 80f, infoPaint)

        if (showPose) {
            poseDrawer.draw(canvas, poseResults, drawLeft, drawTop, drawWidth, drawHeight)
        } else {
            if (showDebugBoxes) {
                debugDrawer.draw(canvas, trackedObjects, drawLeft, drawTop, drawWidth, drawHeight)
            }
            if (showCenterPoints) {
                for (obj in trackedObjects) {
                    val screenX = drawLeft + obj.cx * drawWidth
                    val screenY = drawTop + obj.cy * drawHeight
                    val paint = if (obj.isMoving) movingPaint else staticPaint
                    canvas.drawCircle(screenX, screenY, 10f, paint)
                    canvas.drawText("${(obj.score * 100).toInt()}", screenX, screenY - 20f, paint)
                }
            }
        }
    }
    
    private fun drawPawn(canvas: Canvas, x: Float, y: Float, name: String, fillColor: Int) {
        val r = 12f
        pawnFillPaint.color = fillColor
        canvas.drawCircle(x, y, r, pawnFillPaint)
        canvas.drawCircle(x, y, r, pawnStrokePaint)
        canvas.drawCircle(x, y - r * 1.2f, r * 0.7f, pawnFillPaint)
        canvas.drawCircle(x, y - r * 1.2f, r * 0.7f, pawnStrokePaint)
        canvas.drawText(name, x, y - r * 2.5f, textPaint)
    }
}
