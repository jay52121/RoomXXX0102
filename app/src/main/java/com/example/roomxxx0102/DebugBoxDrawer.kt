package com.example.roomxxx0102

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

class DebugBoxDrawer {

    // 空心矩形画笔 (白色)
    private val boxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // 实心圆点画笔 (鲜艳青色)
    private val bottomCenterPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * 绘制调试信息
     *
     * @param canvas 画布
     * @param objects 追踪对象列表
     * @param drawLeft 绘制区域的左偏移
     * @param drawTop 绘制区域的上偏移
     * @param drawWidth 绘制区域的实际宽度
     * @param drawHeight 绘制区域的实际高度
     */
    fun draw(
        canvas: Canvas,
        objects: List<TrackedDetection>,
        drawLeft: Float,
        drawTop: Float,
        drawWidth: Float,
        drawHeight: Float
    ) {
        for (obj in objects) {
            // 1. 计算矩形框 (obj.cx, cy, w, h 都是 0..1 的归一化坐标)
            // 中心点 cx, cy -> 左上角 left, top
            // left = cx - w/2
            // top = cy - h/2
            
            val normLeft = obj.cx - obj.w / 2
            val normTop = obj.cy - obj.h / 2
            val normRight = obj.cx + obj.w / 2
            val normBottom = obj.cy + obj.h / 2

            // 映射到实际绘制区域
            val screenLeft = drawLeft + normLeft * drawWidth
            val screenTop = drawTop + normTop * drawHeight
            val screenRight = drawLeft + normRight * drawWidth
            val screenBottom = drawTop + normBottom * drawHeight

            // 2. 绘制矩形框
            val rect = RectF(screenLeft, screenTop, screenRight, screenBottom)
            canvas.drawRect(rect, boxPaint)

            // 3. 绘制底边中心点 (Bottom Center)
            // x = 矩形中心 X (即 obj.cx 映射后的 screenX)
            // y = 矩形底边 Y (screenBottom)
            val centerX = drawLeft + obj.cx * drawWidth
            
            canvas.drawCircle(centerX, screenBottom, 12f, bottomCenterPaint)
        }
    }
}