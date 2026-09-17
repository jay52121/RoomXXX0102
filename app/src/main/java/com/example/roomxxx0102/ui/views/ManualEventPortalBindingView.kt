package com.example.roomxxx0102.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.example.roomxxx0102.R
import com.example.roomxxx0102.data.model.RoomConfig
import com.example.roomxxx0102.data.repository.RoomRepository
import com.example.roomxxx0102.logic.validation.MarkedEventPortalBinding
import com.example.roomxxx0102.utils.GeometryUtils

/**
 * 调试专用覆盖层：
 * 1. “跳转到下一个事件”选中的人工 ENTER/EXIT 事件，可直接点击门/房间名绑定 portalRoomId；
 * 2. 绑定后在该房间白色名称右侧画一个小锁，再点其它门直接覆盖；
 * 3. 在现有大调试信息面板右上角提供独立关闭 X，不退出调试/事件标注模式。
 *
 * 该 View 不参与正式房间算法，只操作人工 GT 的调试元数据。
 */
class ManualEventPortalBindingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var infoPanelClosedByUser = false
    private var debugWasActive = false
    private var consumeGesture = false
    private val closeRect = RectF()
    private val bindingChangedListener: () -> Unit = { postInvalidateOnAnimation() }

    private val closeBgPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val closePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val roomNameMeasurePaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val lockPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
    }

    init {
        isClickable = true
        isFocusable = false
        // 右侧控制条本身是 6dp。调试覆盖层略高一层，才能在“信息面板已关闭”时
        // 抢先识别下一次“调试面板”点击并只恢复信息面板；非目标点击返回 false 继续下传。
        elevation = 7f * density
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MarkedEventPortalBinding.setOnChangedListener(bindingChangedListener)
    }

    override fun onDetachedFromWindow() {
        MarkedEventPortalBinding.setOnChangedListener(null)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val debugActive = isDebugUiActive()
        if (!debugActive) {
            if (debugWasActive) {
                // 真正退出调试模式后，下次重新打开时恢复“大信息面板默认打开”。
                infoPanelClosedByUser = false
            }
            debugWasActive = false
            closeRect.setEmpty()
            return
        }
        debugWasActive = true

        // 雷达/房间编辑是更高优先级界面；本调试层完全让路。
        if (isBlockingScreenActive()) {
            closeRect.setEmpty()
            return
        }

        if (!infoPanelClosedByUser) {
            drawPanelCloseButton(canvas)
        } else {
            closeRect.setEmpty()
        }
        drawSelectedPortalLock(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isBlockingScreenActive()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                consumeGesture = false

                // 信息面板被单独关闭后，再点一次“调试面板”只负责把信息面板重新打开，
                // 不让 MainActivity 把整个调试模式关闭。
                if (infoPanelClosedByUser && isInsideView(event.x, event.y, R.id.btnDebugPanel)) {
                    detectionOverlay()?.setDebugPanelEnabled(true)
                    infoPanelClosedByUser = false
                    consumeGesture = true
                    invalidate()
                    performClick()
                    return true
                }

                if (isDebugUiActive() && !infoPanelClosedByUser && closeRect.contains(event.x, event.y)) {
                    detectionOverlay()?.setDebugPanelEnabled(false)
                    infoPanelClosedByUser = true
                    closeRect.setEmpty()
                    consumeGesture = true
                    invalidate()
                    performClick()
                    return true
                }

                if (canBindPortal() && !isTouchOnControls(event.x, event.y)) {
                    // 大信息面板仍显示时，右半屏是被遮住的；不允许“穿透”去绑其下的门。
                    if (!infoPanelClosedByUser && event.x >= width * 0.5f) {
                        return false
                    }
                    val room = findTappedPortalRoom(event.x, event.y)
                    if (room != null) {
                        val before = MarkedEventPortalBinding.selectedEvent()?.portalRoomId
                        val updated = MarkedEventPortalBinding.bindSelectedPortal(room.id)
                        if (updated != null) {
                            val action = when {
                                before == null -> "已绑定"
                                before == room.id -> "绑定不变"
                                else -> "已改绑"
                            }
                            Toast.makeText(
                                context,
                                "$action：${updated.type.name} → ${room.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                            consumeGesture = true
                            invalidate()
                            performClick()
                            return true
                        }
                    }
                }
                return false
            }

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val consumed = consumeGesture
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    consumeGesture = false
                }
                return consumed
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun canBindPortal(): Boolean {
        if (!isDebugUiActive() || isBlockingScreenActive()) return false
        if (MarkedEventPortalBinding.selectedEvent() == null) return false
        // 房间事件工具栏可见 = 当前是回顾视频、调试模式、看人视图且非播放态；
        // 只在这个明确的人工标注场景允许点击房门，避免干扰正常操作。
        val eventControls = rootView.findViewById<View>(R.id.llEventMarkerControls)
        val diagnosticButton = rootView.findViewById<View>(R.id.btnDiagnosticReplay)
        return eventControls?.visibility == View.VISIBLE &&
            diagnosticButton?.visibility == View.VISIBLE
    }

    private fun isDebugUiActive(): Boolean {
        val eventControls = rootView.findViewById<View>(R.id.llEventMarkerControls)
        val diagnosticButton = rootView.findViewById<View>(R.id.btnDiagnosticReplay)
        return eventControls?.visibility == View.VISIBLE || diagnosticButton?.visibility == View.VISIBLE
    }

    private fun isBlockingScreenActive(): Boolean {
        val editorControls = rootView.findViewById<View>(R.id.llEditorControls)
        val radar = rootView.findViewById<View>(R.id.flRadarContainer)
        return editorControls?.visibility == View.VISIBLE || radar?.visibility == View.VISIBLE
    }

    private fun isTouchOnControls(x: Float, y: Float): Boolean {
        val ids = intArrayOf(
            R.id.llNormalControls,
            R.id.llRightActionControls,
            R.id.llEventMarkerControls,
            R.id.llEditorControls,
            R.id.flRadarContainer,
            R.id.runtimeSwitchLoading
        )
        return ids.any { id -> isInsideView(x, y, id) }
    }

    private fun isInsideView(x: Float, y: Float, viewId: Int): Boolean {
        val target = rootView.findViewById<View>(viewId) ?: return false
        if (target.visibility != View.VISIBLE || !target.isShown) return false
        val targetRect = Rect()
        if (!target.getGlobalVisibleRect(targetRect)) return false
        val own = IntArray(2)
        getLocationOnScreen(own)
        val globalX = x + own[0]
        val globalY = y + own[1]
        return targetRect.contains(globalX.toInt(), globalY.toInt())
    }

    private fun detectionOverlay(): DetectionOverlayView? =
        rootView.findViewById(R.id.overlayView)

    private fun drawPanelCloseButton(canvas: Canvas) {
        val size = 48f * density
        val margin = 8f * density
        closeRect.set(
            width - margin - size,
            margin,
            width - margin,
            margin + size
        )
        canvas.drawRoundRect(closeRect, 8f * density, 8f * density, closeBgPaint)
        val inset = 14f * density
        canvas.drawLine(
            closeRect.left + inset,
            closeRect.top + inset,
            closeRect.right - inset,
            closeRect.bottom - inset,
            closePaint
        )
        canvas.drawLine(
            closeRect.right - inset,
            closeRect.top + inset,
            closeRect.left + inset,
            closeRect.bottom - inset,
            closePaint
        )
    }

    private fun drawSelectedPortalLock(canvas: Canvas) {
        val selected = MarkedEventPortalBinding.selectedEvent() ?: return
        val boundRoomId = selected.portalRoomId ?: return
        val room = RoomRepository.getSubRooms().firstOrNull {
            it.id == boundRoomId && !it.isLivingBlindZone
        } ?: return
        val anchor = room.labelPoint ?: room.anchorPoint ?: return
        val videoRect = videoRect() ?: return
        val x = videoRect.left + anchor.x * videoRect.width()
        val y = videoRect.top + anchor.y * videoRect.height()
        val labelBaseline = y - 12f * 2.5f
        roomNameMeasurePaint.textSize = 30f
        val nameHalf = roomNameMeasurePaint.measureText(room.name) / 2f
        drawLock(
            canvas = canvas,
            centerX = x + nameHalf + 13f * density,
            centerY = labelBaseline - 8f * density
        )
    }

    private fun drawLock(canvas: Canvas, centerX: Float, centerY: Float) {
        val bodyW = 13f * density
        val bodyH = 10f * density
        val left = centerX - bodyW / 2f
        val top = centerY - bodyH / 2f + 3f * density
        val right = centerX + bodyW / 2f
        val bottom = top + bodyH
        canvas.drawRoundRect(left, top, right, bottom, 2f * density, 2f * density, lockPaint)

        val shackleW = 8f * density
        val shackleH = 8f * density
        val shackleRect = RectF(
            centerX - shackleW / 2f,
            top - shackleH * 0.72f,
            centerX + shackleW / 2f,
            top + shackleH * 0.45f
        )
        canvas.drawArc(shackleRect, 180f, -180f, false, lockPaint)
        canvas.drawLine(shackleRect.left, shackleRect.centerY(), shackleRect.left, top, lockPaint)
        canvas.drawLine(shackleRect.right, shackleRect.centerY(), shackleRect.right, top, lockPaint)
    }

    private fun findTappedPortalRoom(x: Float, y: Float): RoomConfig? {
        val videoRect = videoRect() ?: return null
        if (!videoRect.contains(x, y)) return null
        val nx = ((x - videoRect.left) / videoRect.width()).coerceIn(0f, 1f)
        val ny = ((y - videoRect.top) / videoRect.height()).coerceIn(0f, 1f)
        val normalized = PointF(nx, ny)
        val rooms = RoomRepository.getSubRooms().filter {
            !it.isLivingBlindZone && it.occupiedWallIds.isNotEmpty()
        }

        // 先命中门的四边形/多边形；倒序与现有覆盖层绘制顺序一致，重叠时优先最后一个。
        for (index in rooms.indices.reversed()) {
            val room = rooms[index]
            val polygon = room.boundaryVertices.map { it.point }
            if (polygon.size >= 3 && GeometryUtils.isPointInPolygon(normalized, polygon)) {
                return room
            }
        }

        // 再给白色房间名一个宽松点击框，门很窄时也能轻松绑定。
        for (index in rooms.indices.reversed()) {
            val room = rooms[index]
            val anchor = room.labelPoint ?: room.anchorPoint ?: continue
            val labelX = videoRect.left + anchor.x * videoRect.width()
            val labelBaseline = videoRect.top + anchor.y * videoRect.height() - 12f * 2.5f
            roomNameMeasurePaint.textSize = 30f
            val halfWidth = (roomNameMeasurePaint.measureText(room.name) / 2f + 24f * density)
                .coerceAtLeast(44f * density)
            val hit = RectF(
                labelX - halfWidth,
                labelBaseline - 32f * density,
                labelX + halfWidth,
                labelBaseline + 14f * density
            )
            if (hit.contains(x, y)) return room
        }
        return null
    }

    private fun videoRect(): RectF? {
        val texture = rootView.findViewById<View>(R.id.textureView) ?: return null
        if (texture.visibility != View.VISIBLE || !texture.isShown) return null
        val global = Rect()
        if (!texture.getGlobalVisibleRect(global) || global.width() <= 0 || global.height() <= 0) return null
        val own = IntArray(2)
        getLocationOnScreen(own)
        return RectF(
            global.left - own[0].toFloat(),
            global.top - own[1].toFloat(),
            global.right - own[0].toFloat(),
            global.bottom - own[1].toFloat()
        )
    }
}
