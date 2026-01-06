package com.example.roomxxx0102.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.utils.RoomColorPalette
import kotlin.math.min

/**
 * **战术雷达地图 (Tactical Map View)**
 *
 * 这是一个上帝视角的纯几何地图，用于直观展示房间布局和人物位置，
 * 脱离摄像头画面的透视干扰。
 *
 * 核心特性：
 * 1. **自动适配 (Auto-Fit)**: 无论房间形状如何，自动计算包围盒并缩放至屏幕中心。
 * 2. **纯粹几何**: 不依赖背景图，只渲染多边形和点。
 */
class TacticalMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- 数据源 ---
    private var allRooms: List<RoomConfig> = emptyList()
    private var currentPosePoints: List<PointF> = emptyList()

    // --- 绘图工具 ---
    private val worldRect = RectF() // 世界疆域 (所有房间的最小外接矩形)
    private val viewRect = RectF()  // 屏幕可视区域 (减去 Padding)
    private val transformMatrix = Matrix() // 核心变换矩阵
    private val pathBuffer = Path() // 复用 Path 对象
    private val pointBuffer = FloatArray(2) // 用于 mapPoints 的临时数组

    // --- 视觉参数 ---
    private val padding = 50f // 屏幕边距
    private val bgAlpha = 230 // 90% 不透明度 (255 * 0.9 ≈ 230)
    
    // --- 画笔 ---
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#121212") // 深色背景
        alpha = bgAlpha
        style = Paint.Style.FILL
    }

    private val livingRoomStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val subRoomFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val subRoomStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val personPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val personStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    /**
     * 更新地图数据
     * @param rooms 房间列表
     * @param personLocations 当前所有人的位置列表
     */
    fun updateData(rooms: List<RoomConfig>, personLocations: List<PointF>) {
        this.allRooms = rooms
        this.currentPosePoints = personLocations
        
        // 每次数据更新都重新计算世界疆域，以防房间被编辑修改
        calculateWorldBounds()
        invalidate()
    }

    /**
     * 第一步：计算“世界”的疆域
     * 遍历所有房间的所有顶点，找出 min/max。
     */
    private fun calculateWorldBounds() {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasPoints = false

        for (room in allRooms) {
            for (v in room.boundaryVertices) {
                minX = min(minX, v.point.x)
                maxX = kotlin.math.max(maxX, v.point.x)
                minY = min(minY, v.point.y)
                maxY = kotlin.math.max(maxY, v.point.y)
                hasPoints = true
            }
        }

        if (hasPoints) {
            // 稍微留一点余量，防止点刚好贴在边上
            val buffer = 0.05f 
            worldRect.set(minX - buffer, minY - buffer, maxX + buffer, maxY + buffer)
        } else {
            // 默认 fallback：0~1 的标准空间
            worldRect.set(0f, 0f, 1f, 1f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 第二步：计算“屏幕”的窗口
        viewRect.set(padding, padding, w - padding, h - padding)
    }

    override fun onDraw(canvas: Canvas) {
        // 绘制背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 第三步：构建映射矩阵 (ScaleToFit.CENTER)
        // 这一步确保 WorldRect 被无变形地缩放并居中放置在 ViewRect 里
        transformMatrix.setRectToRect(worldRect, viewRect, Matrix.ScaleToFit.CENTER)

        // 1. 绘制次房间 (Sub Rooms)
        for (room in allRooms) {
            if (!room.isSovereignTerritory && room.boundaryVertices.size >= 3) {
                drawRoomPolygon(canvas, room, isLivingRoom = false)
            }
        }

        // 2. 绘制客厅 (Living Room) - 放在上层，只画描边
        for (room in allRooms) {
            if (room.isSovereignTerritory && room.boundaryVertices.size >= 3) {
                drawRoomPolygon(canvas, room, isLivingRoom = true)
            }
        }

        // 3. 绘制人物
        for (pos in currentPosePoints) {
            // 将人物的世界坐标 (0..1) 映射到屏幕坐标
            pointBuffer[0] = pos.x
            pointBuffer[1] = pos.y
            transformMatrix.mapPoints(pointBuffer)
            
            val screenX = pointBuffer[0]
            val screenY = pointBuffer[1]
            
            // 画红点
            val radius = 10f
            canvas.drawCircle(screenX, screenY, radius, personPaint)
            canvas.drawCircle(screenX, screenY, radius, personStrokePaint)
        }
    }

    private fun drawRoomPolygon(canvas: Canvas, room: RoomConfig, isLivingRoom: Boolean) {
        val points = room.boundaryVertices
        if (points.isEmpty()) return

        pathBuffer.reset()
        // 起点映射
        pointBuffer[0] = points[0].point.x
        pointBuffer[1] = points[0].point.y
        transformMatrix.mapPoints(pointBuffer)
        pathBuffer.moveTo(pointBuffer[0], pointBuffer[1])

        // 后续点映射
        for (i in 1 until points.size) {
            pointBuffer[0] = points[i].point.x
            pointBuffer[1] = points[i].point.y
            transformMatrix.mapPoints(pointBuffer)
            pathBuffer.lineTo(pointBuffer[0], pointBuffer[1])
        }
        pathBuffer.close()

        if (isLivingRoom) {
            // 客厅只画白线
            canvas.drawPath(pathBuffer, livingRoomStrokePaint)
        } else {
            // 次房间画填充色 + 描边
            val color = room.themeColor ?: Color.GRAY
            // 填充色稍微透明一点
            subRoomFillPaint.color = Color.argb(180, Color.red(color), Color.green(color), Color.blue(color))
            subRoomStrokePaint.color = color
            
            canvas.drawPath(pathBuffer, subRoomFillPaint)
            canvas.drawPath(pathBuffer, subRoomStrokePaint)
        }
    }
}
