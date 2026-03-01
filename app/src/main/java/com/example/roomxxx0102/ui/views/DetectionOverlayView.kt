package com.example.roomxxx0102.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import com.example.roomxxx0102.logic.analyzer.RoiLogAggregator
import com.example.roomxxx0102.ui.drawers.DebugBoxDrawer
import com.example.roomxxx0102.ui.drawers.PoseDrawer

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var trackedObjects: List<TrackedDetection> = emptyList()
    private var poseResults: List<PoseResult> = emptyList()
    private var poseUpdateCount = 0
    
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
    private var debugPanelEnabled = false

    // 🔥 ROI 绘制相关
    private var roiBox: RectF? = null
    private var prevRoiBox: RectF? = null // 🔥 记录上一帧 ROI
    private var isRoiStable = false      // 🔥 是否保持静止
    private var isRoiTracking = false
    private var roiRatio: Float? = null
    private val roiPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    private val roiTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    // 🔥 稳定点画笔
    private val stableIndicatorPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

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
    private var unlockBannerText: String? = null
    private var unlockBannerUntil = 0L
    private val unlockBannerPaint = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }
    private val unlockTextPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 28f
        isAntiAlias = true
    }
    private val debugPanelBgPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val debugPanelTitlePaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val debugPanelTextPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        textSize = 24f
        isAntiAlias = true
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
    private val subRoomFillPaint = Paint().apply {
        style = Paint.Style.FILL
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
        poseUpdateCount += 1
        RoiLogAggregator.updatePoseUi(poseUpdateCount, results.size, timeMs, bitmap != null)
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

    // 🔥 更新 ROI 框 (带状态和样式控制)
    fun updateRoiBox(box: RectF?, isTracking: Boolean, isSparse: Boolean = false) {
        // 🔥 判定是否稳定 (和上一帧完全一致)
        isRoiStable = box != null && prevRoiBox != null &&
                box.left == prevRoiBox?.left &&
                box.top == prevRoiBox?.top &&
                box.right == prevRoiBox?.right &&
                box.bottom == prevRoiBox?.bottom
        
        roiBox = box
        prevRoiBox = box
        isRoiTracking = isTracking
        if (box == null) {
            roiRatio = null
        }
        if (box != null) {
            // 动态设置虚线样式
            val intervals = if (isSparse) floatArrayOf(20f, 80f) else floatArrayOf(20f, 10f)
            roiPaint.pathEffect = DashPathEffect(intervals, 0f)
            RoiLogAggregator.updateRoiVisual(box, isTracking, isSparse, isRoiStable)
        }
        postInvalidate()
    }


    fun setRoiRatio(ratio: Float?) {
        roiRatio = ratio
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

    fun showUnlockBanner(message: String) {
        unlockBannerText = message
        unlockBannerUntil = System.currentTimeMillis() + 5000L
        postInvalidate()
    }

    fun setDebugPanelEnabled(enabled: Boolean) {
        debugPanelEnabled = enabled
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

        // 🔥 画 ROI 框 (最上层)
        roiBox?.let { r ->
            // 根据状态切换颜色
            roiPaint.color = if (isRoiTracking) Color.YELLOW else Color.RED
            
            val left = drawLeft + r.left * drawWidth
            val top = drawTop + r.top * drawHeight
            val right = drawLeft + r.right * drawWidth
            val bottom = drawTop + r.bottom * drawHeight
            canvas.drawRect(left, top, right, bottom, roiPaint)

            // 🔥 画稳定指示点 (四个角落)
            if (isRoiStable) {
                val radius = 8f
                canvas.drawCircle(left, top, radius, stableIndicatorPaint)
                canvas.drawCircle(right, top, radius, stableIndicatorPaint)
                canvas.drawCircle(left, bottom, radius, stableIndicatorPaint)
                canvas.drawCircle(right, bottom, radius, stableIndicatorPaint)
            }

            roiRatio?.let { ratio ->
                val text = String.format("%.2f", ratio)
                val cx = (left + right) / 2f
                val cy = top + 28f
                canvas.drawText(text, cx, cy, roiTextPaint)
            }
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
            
            for (room in subRooms) {
                val points = room.boundaryVertices.map { it.point }
                if (points.size >= 3) {
                    val path = Path()
                    val startX = drawLeft + points[0].x * drawWidth
                    val startY = drawTop + points[0].y * drawHeight
                    path.moveTo(startX, startY)
                    for (i in 1 until points.size) {
                        val px = drawLeft + points[i].x * drawWidth
                        val py = drawTop + points[i].y * drawHeight
                        path.lineTo(px, py)
                    }
                    path.close()
                    val color = room.themeColor ?: Color.WHITE
                    subRoomFillPaint.color = Color.argb(85, Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawPath(path, subRoomFillPaint)
                }
            }

            // 2. 🔥 画次房间棋子
            for (room in subRooms) {
                (room.labelPoint ?: room.anchorPoint)?.let { anchor ->
                    val px = drawLeft + anchor.x * drawWidth
                    val py = drawTop + anchor.y * drawHeight
                    val fillColor =
                        if (room.occupiedWallIds.isEmpty()) Color.WHITE else (room.themeColor ?: Color.WHITE)
                    // 🔥 恢复：传递 persistentPersonCount
                    drawPawn(canvas, px, py, room.name, fillColor, room.personCount, room.persistentPersonCount)
                }
            }
        }

        val now = System.currentTimeMillis()
        if (unlockBannerText != null && now <= unlockBannerUntil) {
            val bannerHeight = 48f
            canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, unlockBannerPaint)
            unlockBannerText?.let { msg ->
                canvas.drawText(msg, 16f, 34f, unlockTextPaint)
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

        if (debugPanelEnabled) {
            drawDebugPanel(canvas)
        }
    }

    private fun drawDebugPanel(canvas: Canvas) {
        val panelLeft = width * 0.5f
        val panelTop = 0f
        val panelRight = width.toFloat()
        val panelBottom = height.toFloat()
        canvas.drawRect(panelLeft, panelTop, panelRight, panelBottom, debugPanelBgPaint)

        val paddingLeft = 16f
        val paddingTop = 18f
        val lineHeight = 28f
        val maxChars = 72
        val maxLines = ((panelBottom - panelTop - 56f) / lineHeight).toInt().coerceAtLeast(1)

        var y = panelTop + paddingTop + 24f
        canvas.drawText("调试信息面板", panelLeft + paddingLeft, y, debugPanelTitlePaint)
        y += 34f

        val lines = mutableListOf<String>()
        lines.add(debugInfo)
        roiRatio?.let { ratio ->
            lines.add("roiRatio=${String.format("%.2f", ratio)}")
        }
        lines.addAll(RoiLogAggregator.snapshotForPanel())

        var drawn = 0
        for (line in lines) {
            if (drawn >= maxLines) break
            canvas.drawText(trimLine(line, maxChars), panelLeft + paddingLeft, y, debugPanelTextPaint)
            y += lineHeight
            drawn += 1
        }
    }

    private fun trimLine(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.substring(0, maxChars - 3) + "..."
    }
    
    // 🔥 修改签名：增加 persistentCount
    private fun drawPawn(canvas: Canvas, x: Float, y: Float, name: String, fillColor: Int, count: Int, persistentCount: Int) {
        val r = 12f
        pawnFillPaint.color = fillColor
        canvas.drawCircle(x, y, r, pawnFillPaint)
        canvas.drawCircle(x, y, r, pawnStrokePaint)
        canvas.drawCircle(x, y - r * 1.2f, r * 0.7f, pawnFillPaint)
        canvas.drawCircle(x, y - r * 1.2f, r * 0.7f, pawnStrokePaint)
        
        // 名称保持默认大小 (30f) 和白色
        textPaint.textSize = 30f
        textPaint.color = Color.WHITE
        canvas.drawText(name, x, y - r * 2.5f, textPaint)
        
        // 人数画在名称上方
        val countText = if (count == 0) "0" else "$count"
        
        if (count > 0) {
            // 有人：红色、变大 (45f)
            textPaint.color = Color.RED
            textPaint.textSize = 45f
        } else {
            // 无人：白色、默认大小
            textPaint.color = Color.WHITE
            textPaint.textSize = 30f
        }
        
        // 绘制人数 (坐标保持在 y - r * 4.5f)
        canvas.drawText(countText, x, y - r * 4.5f, textPaint)

        // 🔥 新增：绘制持久化人数 (黄色数字，在最上方)
        textPaint.color = Color.YELLOW
        textPaint.textSize = 30f
        canvas.drawText("$persistentCount", x, y - r * 6.5f, textPaint)
        
        // 恢复画笔默认状态 (避免影响后续绘制)
        textPaint.color = Color.WHITE
        textPaint.textSize = 30f
    }
}
