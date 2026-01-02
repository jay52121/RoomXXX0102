package com.example.roomxxx0102

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

class DetectionOverlayView(context: Context) : View(context) {
    // 检测数据
    private var trackedObjects: List<TrackedDetection> = emptyList()
    // Pose 数据
    private var poseResults: List<PoseResult> = emptyList()
    
    private var debugInfo = "Waiting..."
    private var currentFrame: Bitmap? = null
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }

    // 🔥 开关状态
    private var showDebugBoxes = false
    private var showCenterPoints = true 
    private var showPose = false // 新增 Pose 开关

    // 🔥 绘图辅助类
    private val debugDrawer = DebugBoxDrawer()
    private val poseDrawer = PoseDrawer() // 新增 Pose 绘制器

    private val movingPaint = Paint().apply {
        color = Color.GREEN
        textSize = 60f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val staticPaint = Paint().apply {
        color = Color.RED
        textSize = 60f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.WHITE)
    }

    private val infoPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 40f
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

    // 更新检测框数据
    fun updateData(objects: List<TrackedDetection>, bitmap: Bitmap?, timeMs: Long) {
        trackedObjects = objects
        currentFrame = bitmap
        debugInfo = "Detect: ${timeMs}ms | Count: ${objects.size}"
        postInvalidate()
    }

    // 🔥 更新 Pose 数据
    fun updatePoseData(results: List<PoseResult>, bitmap: Bitmap?, timeMs: Long) {
        poseResults = results
        currentFrame = bitmap
        debugInfo = "Pose: ${timeMs}ms | Count: ${results.size}"
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

    // 🔥 切换 Pose 显示模式
    fun setPoseState(show: Boolean) {
        showPose = show
        // 如果开启 Pose，通常建议关闭普通框，避免太乱，或者共存
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

        // 1. 画背景图
        currentFrame?.let { bmp ->
            if (!bmp.isRecycled) {
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
            }
        }

        // 2. 画调试信息
        canvas.drawText(debugInfo, 40f, 80f, infoPaint)

        if (showPose) {
            // 🔥 3. 画 Pose
            poseDrawer.draw(canvas, poseResults, drawLeft, drawTop, drawWidth, drawHeight)
        } else {
            // 4. 画普通检测 (框和点)
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
}