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
import android.view.ViewConfiguration
import com.example.roomxxx0102.data.model.BoundaryVertex
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.logic.analyzer.HandSmokeTester
import com.example.roomxxx0102.logic.analyzer.TrackedDetection
import com.example.roomxxx0102.logic.analyzer.RoiLogAggregator
import com.example.roomxxx0102.logic.pointing.PointingDebugSnapshot
import com.example.roomxxx0102.logic.validation.EventType
import com.example.roomxxx0102.logic.validation.MarkedEvent
import com.example.roomxxx0102.ui.drawers.DebugBoxDrawer
import com.example.roomxxx0102.ui.drawers.PoseDrawer

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var trackedObjects: List<TrackedDetection> = emptyList()
    private var poseResults: List<PoseResult> = emptyList()
    private var poseSwitchHints: Map<Int, Pair<Float, EventType>> = emptyMap()
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
    private var showHandOnly = false
    private var handResults: List<List<HandSmokeTester.HandPoint>> = emptyList()
    private var showPointingDebugOverlay = false
    private var pointingDebugSnapshot: PointingDebugSnapshot? = null
    private var pointingDebugVisibleUntilMs = 0L
    // 🔥 新增：是否处于编辑模式
    private var isEditMode = false
    private var debugPanelEnabled = false
    private var markerCurrentMs = 0L
    private var markerDurationMs = 0L
    private var markerEvents: List<MarkedEvent> = emptyList()
    private var markerMatchedEventKeys: Set<String> = emptySet()

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
    private var unlockBannerRect: RectF? = null
    private var bannerLongPressArmed = false
    private var bannerLongPressTriggered = false
    private val bannerLongPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var onUnlockBannerLongPressListener: (() -> Unit)? = null
    private val bannerLongPressRunnable = Runnable {
        if (!bannerLongPressArmed || bannerLongPressTriggered) return@Runnable
        if (!isUnlockBannerVisible()) return@Runnable
        bannerLongPressTriggered = true
        onUnlockBannerLongPressListener?.invoke()
    }
    private val unlockBannerPaint = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }
    private val unlockTextPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
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
    private val markerTrackPaint = Paint().apply {
        color = Color.parseColor("#66222222")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val markerProgressPaint = Paint().apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val markerEnterPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val markerExitPaint = Paint().apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val markerMatchedSlashPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val pointingRawPaint = Paint().apply {
        color = Color.parseColor("#88FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val pointingSmoothPaint = Paint().apply {
        color = Color.parseColor("#FF3DDC84")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    private val pointingRectPaint = Paint().apply {
        color = Color.parseColor("#88FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val pointingWinnerRectPaint = Paint().apply {
        color = Color.parseColor("#FFFFC107")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val pointingExpandedPaint = Paint().apply {
        color = Color.parseColor("#88FF9800")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }
    private val pointingTextBgPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val pointingTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        isAntiAlias = true
    }
    private val pointingLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 20f
        isAntiAlias = true
    }
    private val debugPointPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val debugPointStrokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
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
    private val handPointPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
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

    fun updatePoseData(
        results: List<PoseResult>,
        bitmap: Bitmap?,
        timeMs: Long,
        switchHints: Map<Int, Pair<Float, EventType>> = emptyMap()
    ) {
        poseResults = results
        poseSwitchHints = switchHints
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

    fun updateHandData(results: List<List<HandSmokeTester.HandPoint>>) {
        handResults = results
        postInvalidate()
    }

    fun setHandOnlyState(show: Boolean) {
        showHandOnly = show
        postInvalidate()
    }

    fun setPointingDebugOverlayEnabled(enabled: Boolean) {
        showPointingDebugOverlay = enabled
        if (!enabled) {
            pointingDebugSnapshot = null
            pointingDebugVisibleUntilMs = 0L
        }
        postInvalidate()
    }

    fun updatePointingDebugSnapshot(snapshot: PointingDebugSnapshot?, holdMs: Long = 0L) {
        if (!showPointingDebugOverlay) return
        pointingDebugSnapshot = snapshot
        pointingDebugVisibleUntilMs = if (snapshot != null && holdMs > 0L) {
            System.currentTimeMillis() + holdMs
        } else {
            0L
        }
        postInvalidate()
    }

    fun showUnlockBanner(message: String) {
        unlockBannerText = message
        unlockBannerUntil = System.currentTimeMillis() + 5000L
        postInvalidate()
    }

    fun setOnUnlockBannerLongPressListener(listener: (() -> Unit)?) {
        onUnlockBannerLongPressListener = listener
    }

    fun setDebugPanelEnabled(enabled: Boolean) {
        debugPanelEnabled = enabled
        postInvalidate()
    }

    fun setEventMarkerState(
        currentMs: Long,
        durationMs: Long,
        events: List<MarkedEvent>,
        matchedEventKeys: Set<String>
    ) {
        markerCurrentMs = currentMs.coerceAtLeast(0L)
        markerDurationMs = durationMs.coerceAtLeast(0L)
        markerEvents = events
        markerMatchedEventKeys = matchedEventKeys
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
        val markerRect = drawEventMarkerBar(canvas)
        drawUnlockBannerBelowMarker(canvas, markerRect, now)

        if (showHandOnly) {
            drawHands(canvas, drawLeft, drawTop, drawWidth, drawHeight)
        } else if (showPose) {
            poseDrawer.draw(
                canvas = canvas,
                results = poseResults,
                drawLeft = drawLeft,
                drawTop = drawTop,
                drawWidth = drawWidth,
                drawHeight = drawHeight,
                switchHints = poseSwitchHints
            )
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

        if (showPointingDebugOverlay) {
            drawPointingDebugOverlay(canvas, drawLeft, drawTop, drawWidth, drawHeight, now)
        }

        if (debugPanelEnabled) {
            drawDebugPanel(canvas)
        }
    }

    private fun drawHands(
        canvas: Canvas,
        drawLeft: Float,
        drawTop: Float,
        drawWidth: Float,
        drawHeight: Float
    ) {
        for (hand in handResults) {
            for (point in hand) {
                val screenX = drawLeft + point.x * drawWidth
                val screenY = drawTop + point.y * drawHeight
                handPointPaint.color = colorForHandConfidence(point.confidence)
                canvas.drawCircle(screenX, screenY, 10f, handPointPaint)
                canvas.drawCircle(screenX, screenY, 10f, handStrokePaint)
            }
        }
    }

    private fun colorForHandConfidence(confidence: Float?): Int {
        if (confidence == null) return Color.CYAN
        val value = confidence.coerceIn(0f, 1f)
        val red = ((1f - value) * 255f).toInt().coerceIn(0, 255)
        val green = (value * 255f).toInt().coerceIn(0, 255)
        return Color.rgb(red, green, 64)
    }

    private fun drawPointingDebugOverlay(
        canvas: Canvas,
        drawLeft: Float,
        drawTop: Float,
        drawWidth: Float,
        drawHeight: Float,
        nowMs: Long
    ) {
        val snapshot = pointingDebugSnapshot ?: return
        if (!snapshot.isActive && pointingDebugVisibleUntilMs > 0L && nowMs > pointingDebugVisibleUntilMs) {
            pointingDebugSnapshot = null
            pointingDebugVisibleUntilMs = 0L
            return
        }
        fun mapPoint(point: PointF): PointF {
            val imageW = snapshot.imageWidth.coerceAtLeast(1).toFloat()
            val imageH = snapshot.imageHeight.coerceAtLeast(1).toFloat()
            return PointF(
                drawLeft + (point.x / imageW) * drawWidth,
                drawTop + (point.y / imageH) * drawHeight
            )
        }
        fun mapRect(rect: RectF): RectF {
            val lt = mapPoint(PointF(rect.left, rect.top))
            val rb = mapPoint(PointF(rect.right, rect.bottom))
            return RectF(lt.x, lt.y, rb.x, rb.y)
        }

        snapshot.targets.forEach { target ->
            val rect = mapRect(target.rect)
            val winner = target.id == snapshot.bestTargetId
            canvas.drawRect(rect, if (winner) pointingWinnerRectPaint else pointingRectPaint)
            canvas.drawText("${target.id} ${String.format("%.2f", target.score)}", rect.left, rect.top - 8f, pointingLabelPaint)
        }
        snapshot.targets.take(2).forEach { target ->
            if (target.id == snapshot.bestTargetId || snapshot.top3Targets.any { it.first == target.id }) {
                canvas.drawRect(mapRect(target.expandedRect), pointingExpandedPaint)
            }
        }
        if (snapshot.smoothedOrigin != null && snapshot.smoothedDir != null) {
            drawPointingLine(canvas, snapshot.originRaw, snapshot.fingerDirRaw, snapshot, drawLeft, drawTop, drawWidth, drawHeight, pointingRawPaint)
            drawPointingLine(canvas, snapshot.smoothedOrigin, snapshot.smoothedDir, snapshot, drawLeft, drawTop, drawWidth, drawHeight, pointingSmoothPaint)
            drawNamedPoint(canvas, "tip", snapshot.tipCenter, Color.CYAN, drawLeft, drawTop, drawWidth, drawHeight, snapshot)
            drawNamedPoint(canvas, "dip", snapshot.dipCenter, Color.YELLOW, drawLeft, drawTop, drawWidth, drawHeight, snapshot)
            drawNamedPoint(canvas, "pip", snapshot.pipCenter, Color.MAGENTA, drawLeft, drawTop, drawWidth, drawHeight, snapshot)
            drawNamedPoint(canvas, "mcp", snapshot.mcpCenter, Color.GREEN, drawLeft, drawTop, drawWidth, drawHeight, snapshot)
            drawNamedPoint(canvas, "org", snapshot.smoothedOrigin, Color.WHITE, drawLeft, drawTop, drawWidth, drawHeight, snapshot)
        }

        val lines = listOf(
            "pointing=${if (snapshot.isActive) "active" else "inactive"} elapsed=${snapshot.elapsedMs}ms",
            "path=${snapshot.acceptPath} best=${snapshot.bestTargetId ?: "-"} score=${String.format("%.2f", snapshot.bestScore)}",
            "second=${String.format("%.2f", snapshot.secondScore)} margin=${String.format("%.2f", snapshot.bestScore - snapshot.secondScore)} quality=${String.format("%.2f", snapshot.frameQuality)}",
            "valid=${snapshot.validFrames} noHand=${snapshot.noHandFrames} top3=${snapshot.top3Targets.joinToString { "${it.first}:${String.format("%.2f", it.second)}" }}"
        )
        drawDebugTextBlock(canvas, lines)
    }

    private fun drawPointingLine(
        canvas: Canvas,
        origin: PointF?,
        dir: PointF?,
        snapshot: PointingDebugSnapshot,
        drawLeft: Float,
        drawTop: Float,
        drawWidth: Float,
        drawHeight: Float,
        paint: Paint
    ) {
        if (origin == null || dir == null) return
        val end = extendToImageBounds(origin, dir, RectF(0f, 0f, snapshot.imageWidth.toFloat(), snapshot.imageHeight.toFloat())) ?: return
        val start = PointF(
            drawLeft + (origin.x / snapshot.imageWidth.coerceAtLeast(1).toFloat()) * drawWidth,
            drawTop + (origin.y / snapshot.imageHeight.coerceAtLeast(1).toFloat()) * drawHeight
        )
        val finish = PointF(
            drawLeft + (end.x / snapshot.imageWidth.coerceAtLeast(1).toFloat()) * drawWidth,
            drawTop + (end.y / snapshot.imageHeight.coerceAtLeast(1).toFloat()) * drawHeight
        )
        canvas.drawLine(start.x, start.y, finish.x, finish.y, paint)
    }

    private fun extendToImageBounds(origin: PointF, dir: PointF, bounds: RectF): PointF? {
        val dx = dir.x
        val dy = dir.y
        if (kotlin.math.abs(dx) < 1e-6f && kotlin.math.abs(dy) < 1e-6f) return null
        val candidates = mutableListOf<Float>()
        if (kotlin.math.abs(dx) > 1e-6f) {
            candidates += (bounds.left - origin.x) / dx
            candidates += (bounds.right - origin.x) / dx
        }
        if (kotlin.math.abs(dy) > 1e-6f) {
            candidates += (bounds.top - origin.y) / dy
            candidates += (bounds.bottom - origin.y) / dy
        }
        val valid = candidates.filter { it > 0f }.sorted()
        for (t in valid) {
            val x = origin.x + dx * t
            val y = origin.y + dy * t
            if (x in bounds.left..bounds.right && y in bounds.top..bounds.bottom) {
                return PointF(x, y)
            }
        }
        return null
    }

    private fun drawNamedPoint(
        canvas: Canvas,
        name: String,
        point: PointF?,
        color: Int,
        drawLeft: Float,
        drawTop: Float,
        drawWidth: Float,
        drawHeight: Float,
        snapshot: PointingDebugSnapshot
    ) {
        if (point == null) return
        val x = drawLeft + (point.x / snapshot.imageWidth.coerceAtLeast(1).toFloat()) * drawWidth
        val y = drawTop + (point.y / snapshot.imageHeight.coerceAtLeast(1).toFloat()) * drawHeight
        debugPointPaint.color = color
        canvas.drawCircle(x, y, 8f, debugPointPaint)
        canvas.drawCircle(x, y, 8f, debugPointStrokePaint)
        canvas.drawText(name, x + 10f, y - 10f, pointingLabelPaint)
    }

    private fun drawDebugTextBlock(canvas: Canvas, lines: List<String>) {
        val left = 24f
        val top = 24f
        val lineHeight = 28f
        val widthPx = lines.maxOfOrNull { pointingTextPaint.measureText(it) }?.plus(24f) ?: 260f
        val heightPx = 16f + lines.size * lineHeight
        canvas.drawRoundRect(left, top, left + widthPx, top + heightPx, 12f, 12f, pointingTextBgPaint)
        lines.forEachIndexed { index, text ->
            canvas.drawText(text, left + 12f, top + 28f + index * lineHeight, pointingTextPaint)
        }
    }

    private fun drawEventMarkerBar(canvas: Canvas): RectF? {
        val duration = markerDurationMs
        if (duration <= 0L) return null
        val leftPadding = 100f
        val rightPadding = 100f
        val barLeft = leftPadding
        val barRight = (width.toFloat() - rightPadding).coerceAtLeast(barLeft + 12f)
        val barWidth = (barRight - barLeft).coerceAtLeast(1f)
        val barTop = 18f
        val barBottom = barTop + 12f

        canvas.drawRect(barLeft, barTop, barRight, barBottom, markerTrackPaint)

        val progress = (markerCurrentMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val progressRight = barLeft + barWidth * progress
        canvas.drawRect(barLeft, barTop, progressRight, barBottom, markerProgressPaint)

        for (event in markerEvents) {
            val ratio = (event.timestampMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val x = barLeft + ratio * barWidth
            val matched = markerEventKey(event) in markerMatchedEventKeys
            val left = x - 2f
            val top = barTop - 8f
            val right = x + 2f
            val bottom = barBottom + 8f
            val tickPaint = if (event.type == EventType.ENTER) markerEnterPaint else markerExitPaint
            if (matched) {
                markerMatchedSlashPaint.color = tickPaint.color
                canvas.drawLine(x - 6f, bottom, x + 6f, top, markerMatchedSlashPaint)
            } else {
                canvas.drawRect(left, top, right, bottom, tickPaint)
            }
        }
        return RectF(barLeft, barTop, barRight, barBottom)
    }

    private fun markerEventKey(event: MarkedEvent): String {
        return "${event.type}|${event.frameIndex}|${event.timestampMs}"
    }

    private fun drawUnlockBannerBelowMarker(canvas: Canvas, markerRect: RectF?, nowMs: Long) {
        val message = unlockBannerText ?: run {
            unlockBannerRect = null
            return
        }
        if (nowMs > unlockBannerUntil) {
            unlockBannerRect = null
            return
        }

        val centerX = width / 2f
        val top = (markerRect?.bottom ?: 30f) + 10f
        val bannerHeight = 44f
        val textPadding = 24f
        val desiredWidth = unlockTextPaint.measureText(message) + textPadding * 2f
        val bannerWidth = desiredWidth.coerceIn(220f, width * 0.9f)
        val left = centerX - bannerWidth / 2f
        val right = centerX + bannerWidth / 2f
        val bottom = top + bannerHeight

        canvas.drawRoundRect(left, top, right, bottom, 10f, 10f, unlockBannerPaint)
        unlockBannerRect = RectF(left, top, right, bottom)

        val fm = unlockTextPaint.fontMetrics
        val baseline = top + (bannerHeight - (fm.bottom - fm.top)) / 2f - fm.top
        canvas.drawText(message, centerX, baseline, unlockTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bannerRect = unlockBannerRect
        if (!isUnlockBannerVisible() || bannerRect == null) {
            cancelBannerLongPressTracking()
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (bannerRect.contains(event.x, event.y)) {
                    bannerLongPressArmed = true
                    bannerLongPressTriggered = false
                    removeCallbacks(bannerLongPressRunnable)
                    postDelayed(bannerLongPressRunnable, bannerLongPressTimeoutMs)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (bannerLongPressArmed && !bannerRect.contains(event.x, event.y)) {
                    cancelBannerLongPressTracking()
                }
                return bannerLongPressArmed
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = bannerLongPressArmed
                cancelBannerLongPressTracking()
                return handled
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelBannerLongPressTracking() {
        bannerLongPressArmed = false
        bannerLongPressTriggered = false
        removeCallbacks(bannerLongPressRunnable)
    }

    private fun isUnlockBannerVisible(): Boolean {
        return unlockBannerText != null && System.currentTimeMillis() <= unlockBannerUntil
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
