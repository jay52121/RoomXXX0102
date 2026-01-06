package com.example.roomxxx0102.ui.drawers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.roomxxx0102.data.model.Keypoint
import com.example.roomxxx0102.data.model.PoseResult

class PoseDrawer {

    // 基础画笔 (颜色会在运行时动态修改)
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val skeletonLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val kptPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val landingPointPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 文字画笔
    private val scoreTextPaint = Paint().apply {
        textSize = 40f // 稍微调小一点，太大会挡住
        isAntiAlias = true
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val skeletonConnections = listOf(
        Pair(3, 5), Pair(4, 6),
        Pair(5, 7), Pair(7, 9),
        Pair(6, 8), Pair(8, 10),
        Pair(5, 11), Pair(6, 12),
        Pair(5, 6), Pair(11, 12),
        Pair(11, 13), Pair(13, 15),
        Pair(12, 14), Pair(14, 16)
    )

    private val kptConfThreshold = 0.00f//脚踝置信度阈值

    // 关键点颜色分级
    private fun getKeypointColor(score: Float): Int {
        return when {
            score < 0.01f -> Color.GRAY
            score < 0.05f -> Color.RED
            score < 0.1f -> Color.YELLOW
            score < 0.3f -> Color.CYAN // 蓝色在黑色背景看不清，改用 Cyan
            else -> Color.GREEN
        }
    }

    // 🔥 颜色分级辅助函数
    private fun getScoreColor(score: Float): Int {
        return when {
            score < 0.3f -> Color.GRAY
            score < 0.5f -> Color.RED
            score < 0.6f -> Color.YELLOW
            score < 0.8f -> Color.CYAN // 蓝色在黑色背景看不清，改用 Cyan
            else -> Color.GREEN
        }
    }

    fun draw(
        canvas: Canvas,
        results: List<PoseResult>,
        drawLeft: Float,
        drawTop: Float,
        drawWidth: Float,
        drawHeight: Float
    ) {
        if (results.isEmpty()) return

        for (result in results) {
            val kpts = result.keypoints
            val box = result.box

            // --- 1. 确定颜色 ---
            val scoreColor = getScoreColor(result.score)
            
            // 如果已锁定 (isConfirmed)，则框和骨架跟随分数变色，直观展示信号强弱
            // 如果未锁定 (普通检测)，使用默认白色，表示还在考察期
            val mainColor = if (result.isConfirmed) scoreColor else Color.WHITE

            boxPaint.color = mainColor
            skeletonLinePaint.color = mainColor
            scoreTextPaint.color = scoreColor // 文字始终显示分数颜色

            // --- 2. 映射坐标 ---
            val screenLeft = drawLeft + box.left * drawWidth
            val screenTop = drawTop + box.top * drawHeight
            val screenRight = drawLeft + box.right * drawWidth
            val screenBottom = drawTop + box.bottom * drawHeight
            val screenRect = RectF(screenLeft, screenTop, screenRight, screenBottom)

            // --- 3. 绘制 ---
            canvas.drawRect(screenRect, boxPaint)

            // 骨架
            for ((idx1, idx2) in skeletonConnections) {
                if (idx1 < kpts.size && idx2 < kpts.size) {
                    val p1 = kpts[idx1]
                    val p2 = kpts[idx2]
                    if (p1.conf > kptConfThreshold && p2.conf > kptConfThreshold) {
                        val x1 = drawLeft + p1.x * drawWidth
                        val y1 = drawTop + p1.y * drawHeight
                        val x2 = drawLeft + p2.x * drawWidth
                        val y2 = drawTop + p2.y * drawHeight
                        canvas.drawLine(x1, y1, x2, y2, skeletonLinePaint)
                    }
                }
            }

            // 关键点
            for (p in kpts) {
                if (p.conf > kptConfThreshold) {
                    val cx = drawLeft + p.x * drawWidth
                    val cy = drawTop + p.y * drawHeight
                    kptPaint.color = getKeypointColor(p.conf)
                    canvas.drawCircle(cx, cy, 5f, kptPaint)
                }
            }

            // 落地脚 (直接使用 Data Model 里的属性)
            val landingPoint = result.landingPoint
            val lx = drawLeft + landingPoint.x * drawWidth
            val ly = drawTop + landingPoint.y * drawHeight
            canvas.drawCircle(lx, ly, 20f, landingPointPaint)

            // 文字信息: "ID:0 Conf:0.85 (Lock)"
            val lockStatus = if (result.isConfirmed) "Lock" else ""
            val infoText = "ID:${result.id} %.2f %s".format(result.score, lockStatus)
            canvas.drawText(infoText, screenLeft, screenTop - 15f, scoreTextPaint)
        }
    }
}
