package com.example.roomxxx0102.ui.drawers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.example.roomxxx0102.data.model.Keypoint
import com.example.roomxxx0102.data.model.POSE_HIGH_CONFIDENCE_THRESHOLD
import com.example.roomxxx0102.data.model.PoseResult
import com.example.roomxxx0102.data.model.IdSource
import com.example.roomxxx0102.logic.validation.EventType

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

    private val switchScoreTextPaint = Paint().apply {
        textSize = 40f
        isAntiAlias = true
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    
    // 虚线效果 (10实, 10虚)
    private val dashedEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    private val shieldEffect = DashPathEffect(floatArrayOf(20f, 120f), 0f)
    private val shieldColor = Color.parseColor("#B000FF")
    private val enterSwitchColor = Color.parseColor("#4CAF50")
    private val exitSwitchColor = Color.parseColor("#FF9800")

    private val skeletonConnections = listOf(
        Pair(3, 5), Pair(4, 6),
        Pair(5, 7), Pair(7, 9),
        Pair(6, 8), Pair(8, 10),
        Pair(5, 11), Pair(6, 12),
        Pair(5, 6), Pair(11, 12),
        Pair(11, 13), Pair(13, 15),
        Pair(12, 14), Pair(14, 16)
    )

    // 基础可见性阈值：低于此值的点完全不画
    private val minVisibleThreshold = 0.1f 

    // 🔥 关键点置信度颜色分级
    private fun getKeypointColor(score: Float): Int {
        return when {
            score < 0.01f -> Color.GRAY
            score < 0.05f -> Color.RED
            score < 0.1f -> Color.YELLOW
            score < POSE_HIGH_CONFIDENCE_THRESHOLD -> Color.CYAN // 0.1 ~ 0.7
            else -> Color.GREEN // >= 0.7
        }
    }

    // 整体框的颜色分级 (保持原逻辑，也可以按需调整)
    private fun getScoreColor(score: Float): Int {
        return when {
            score < 0.3f -> Color.GRAY
            score < 0.5f -> Color.RED
            score < 0.6f -> Color.YELLOW
            score < 0.8f -> Color.CYAN 
            else -> Color.GREEN
        }
    }

    fun draw(
        canvas: Canvas,
        results: List<PoseResult>,
        drawLeft: Float,
        drawTop: Float,
        drawWidth: Float,
        drawHeight: Float,
        switchHints: Map<Int, Pair<Float, EventType>> = emptyMap()
    ) {
        if (results.isEmpty()) return

        for (result in results) {
            val kpts = result.keypoints
            val box = result.box

            // --- 1. 确定颜色 ---
            val scoreColor = getScoreColor(result.score)
            
            // 如果已锁定 (isConfirmed)，则框和骨架跟随分数变色，直观展示信号强弱
            // 如果未锁定 (普通检测)，使用默认白色，表示还在考察期
            val mainColor = if (result.isShielded) shieldColor else if (result.isConfirmed) scoreColor else Color.WHITE

            boxPaint.color = mainColor
            skeletonLinePaint.color = mainColor
            scoreTextPaint.color = if (result.isShielded) shieldColor else scoreColor
            
            // --- 1.5 确定线型 (虚线/实线) ---
            // 判定肩膀是否可信 (5:左肩, 6:右肩)
            var shouldersTrusted = false
            if (kpts.size > 6) {
                val leftShoulder = kpts[5]
                val rightShoulder = kpts[6]
                if (leftShoulder.conf >= POSE_HIGH_CONFIDENCE_THRESHOLD && 
                    rightShoulder.conf >= POSE_HIGH_CONFIDENCE_THRESHOLD) {
                    shouldersTrusted = true
                }
            }
            
            // 逻辑：既没有被 lock，肩膀也没有大于高度可信 -> 虚线
            val isWeakTarget = !result.isConfirmed && !shouldersTrusted
            
            if (result.isShielded) {
                boxPaint.pathEffect = shieldEffect
            } else if (isWeakTarget) {
                boxPaint.pathEffect = dashedEffect
            } else {
                boxPaint.pathEffect = null
            }

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
                    // 只要大于基础可见性阈值就画线
                    if (p1.conf > minVisibleThreshold && p2.conf > minVisibleThreshold) {
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
                // 只要大于基础可见性阈值就画点
                if (p.conf > minVisibleThreshold) {
                    val cx = drawLeft + p.x * drawWidth
                    val cy = drawTop + p.y * drawHeight
                    
                    // 🔥 颜色由全局常量判定
                    kptPaint.color = getKeypointColor(p.conf)
                    canvas.drawCircle(cx, cy, 5f, kptPaint)
                }
            }

            // 落地脚 (直接使用 Data Model 里的属性)
            val landingPoint = result.landingPoint
            val lx = drawLeft + landingPoint.x * drawWidth
            val ly = drawTop + landingPoint.y * drawHeight
            if (landingPoint.x <= 0.01f && landingPoint.y <= 0.01f) {
                val leftAnkle = kpts.getOrNull(15)
                val rightAnkle = kpts.getOrNull(16)
                Log.d(
                    "PoseDrawer",
                    "LandingPoint near zero: id=${result.id} box=$box " +
                        "L=(${leftAnkle?.x},${leftAnkle?.y},${leftAnkle?.conf}) " +
                        "R=(${rightAnkle?.x},${rightAnkle?.y},${rightAnkle?.conf})"
                )
            }
            canvas.drawCircle(lx, ly, 20f, landingPointPaint)

            // 文字信息: "BID:0 Conf:0.85 (Lock)"
            val lockStatus = if (result.isConfirmed) "Lock" else ""
            val idLabel = if (result.idSource == IdSource.REMOTE) "BID" else "ID"
            val infoText = "$idLabel:${result.id} %.2f %s".format(result.score, lockStatus)
            val infoX = screenLeft
            val infoY = screenTop - 15f
            val switchHint = switchHints[result.id]
            if (result.isConfirmed && switchHint != null) {
                val switchText = "%.2f".format(switchHint.first.coerceIn(0f, 1f))
                val switchColor = when (switchHint.second) {
                    EventType.ENTER -> enterSwitchColor
                    EventType.EXIT -> exitSwitchColor
                }
                switchScoreTextPaint.color = switchColor
                val prefix = "$switchText "
                val prefixWidth = switchScoreTextPaint.measureText(prefix)
                canvas.drawText(prefix, infoX - prefixWidth, infoY, switchScoreTextPaint)
            }
            canvas.drawText(infoText, infoX, infoY, scoreTextPaint)
        }
    }
}
