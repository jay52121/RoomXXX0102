package com.example.roomxxx0102.logic.analyzer

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * **动态 ROI 追踪器 (Dynamic ROI Tracker)**
 *
 * 负责计算一个关注区域 (ROI)，使其：
 * 1. 尽可能跟随目标中心。
 * 2. 永远不超出图像边界 (0.0 ~ 1.0)。
 * 3. 目标丢失时保持在最后位置。
 * 4. **自适应尺寸**: 根据目标大小自动扩容或缩容。
 * 5. **抗震荡平滑**: 引入死区控制和基于实际帧率自适应的动态 EMA 滤波。
 */
class RoiTracker(
    private val baseRoiSizePx: Float = 640f,
    private val adaptiveResizeEnabled: Boolean = true,
    private val logSource: String = "人体ROI"
) {

    // 上一次的 ROI 区域 (归一化坐标)
    private var lastRoi: RectF? = null

    // 当前 ROI 物理尺寸 (像素)
    private var currentRoiSize = baseRoiSizePx
    private var lastRoiSize = baseRoiSizePx

    // --- 平滑状态变量 ---
    private var roiCenterX = 0.5f // 归一化中心 X
    private var roiCenterY = 0.5f // 归一化中心 Y
    private var lastUpdateTs = 0L // 上次更新时间戳 (ms)
    private var lastTopLeftX = 0f
    private var lastTopLeftY = 0f
    private var lastTopRightX = 0f
    private var lastTopRightY = 0f
    private var hasLastTop = false
    private var isFirstFrame = true
    private var lastTopMove = 0f
    private var enlargePendingSinceMs = 0L

    // 🔥 是否正在跟踪 (Target is present)
    var isTracking: Boolean = false
        private set

    /**
     * 计算下一帧的 ROI 区域。
     *
     * @param imageWidth 原图宽度 (px)
     * @param imageHeight 原图高度 (px)
     * @param targetBox 当前检测到的目标归一化框 (0..1)。如果为 null，则保持原地。
     * @param targetRoiSizePx 可选的目标 ROI 边长 (px)。传入后会固定使用该尺寸，
     *        不再执行默认的人体尺寸自适应扩缩逻辑。
     * @return 计算后的 ROI 归一化区域 (0..1)。
     */
    fun calculate(
        imageWidth: Int,
        imageHeight: Int,
        targetBox: RectF?,
        targetRoiSizePx: Float? = null
    ): RectF {
        val wasTracking = isTracking
        // 更新跟踪状态
        isTracking = (targetBox != null)
        val matchedNewTarget = !wasTracking && isTracking

        // 1. 自动计算帧间隔 dt (秒)
        val now = System.currentTimeMillis()
        val dt = if (lastUpdateTs == 0L) 0.033f else (now - lastUpdateTs) / 1000f
        lastUpdateTs = now

        // 2. 自适应尺寸调整逻辑 (维持之前的策略)
        var deadZoneThreshold = 0f
        var dxPx = 0f
        var dyPx = 0f
        var maxOffsetPx = 0f
        var inDeadZone = false
        if (targetBox != null) {
            val minImageSide = min(imageWidth.toFloat(), imageHeight.toFloat())
            val sizeBeforeReset = currentRoiSize
            if (matchedNewTarget) {
                enlargePendingSinceMs = 0L
                currentRoiSize = baseRoiSizePx.coerceAtMost(minImageSide)
                if (sizeBeforeReset != currentRoiSize) {
                    RoiLogAggregator.updateRoiSizeChange(
                        source = logSource,
                        prevSize = sizeBeforeReset,
                        newSize = currentRoiSize,
                        ratio = 0f,
                        maxSide = 0f,
                        reason = "新匹配目标，先重置为基础ROI"
                    )
                }
                lastRoiSize = currentRoiSize
            }
            val prevSize = currentRoiSize
            if (targetRoiSizePx != null) {
                enlargePendingSinceMs = 0L
                currentRoiSize = targetRoiSizePx.coerceIn(baseRoiSizePx, minImageSide)
                if (prevSize != currentRoiSize) {
                    RoiLogAggregator.updateRoiSizeChange(
                        source = logSource,
                        prevSize = prevSize,
                        newSize = currentRoiSize,
                        ratio = 0f,
                        maxSide = targetRoiSizePx,
                        reason = "使用手动传入的ROI尺寸"
                    )
                    lastRoiSize = currentRoiSize
                }
            } else if (adaptiveResizeEnabled) {
                val personW = targetBox.width() * imageWidth
                val personH = targetBox.height() * imageHeight
                val maxPersonSide = max(personW, personH)
                val ratio = if (currentRoiSize > 0f) maxPersonSide / currentRoiSize else 0f
                var sizeChangeReason: String? = null
                if (currentRoiSize <= baseRoiSizePx) {
                    if (ratio > 0.85f) {
                        if (enlargePendingSinceMs == 0L) {
                            enlargePendingSinceMs = now
                        }
                        if (now - enlargePendingSinceMs >= 1000L) {
                            currentRoiSize = minImageSide
                            sizeChangeReason = "当前占比连续超过0.85满1秒，扩到短边"
                            enlargePendingSinceMs = 0L
                        }
                    } else {
                        enlargePendingSinceMs = 0L
                    }
                } else {
                    enlargePendingSinceMs = 0L
                    if (ratio < 0.35f) {
                        currentRoiSize = baseRoiSizePx
                        sizeChangeReason = "当前占比低于0.35，缩回基础ROI"
                    }
                }
                if (prevSize != currentRoiSize) {
                    RoiLogAggregator.updateRoiSizeChange(
                        source = logSource,
                        prevSize = prevSize,
                        newSize = currentRoiSize,
                        ratio = ratio,
                        maxSide = maxPersonSide,
                        reason = sizeChangeReason ?: "倍率状态切换"
                    )
                    lastRoiSize = currentRoiSize
                }
            }
            if (prevSize != currentRoiSize) {
                lastRoiSize = currentRoiSize
            }
        }

        // 计算当前尺寸对应的归一化宽高
        val roiNormW = currentRoiSize / imageWidth
        val roiNormH = currentRoiSize / imageHeight

        // 3. 核心稳定跟随逻辑 (死区 + EMA + 快车道)
        if (targetBox != null) {
            val targetCx = targetBox.centerX()
            val targetCy = targetBox.centerY()
            var topMovePx = 0f
            if (!hasLastTop) {
                lastTopLeftX = targetBox.left
                lastTopLeftY = targetBox.top
                lastTopRightX = targetBox.right
                lastTopRightY = targetBox.top
                hasLastTop = true
            }

            if (isFirstFrame) {
                roiCenterX = targetCx
                roiCenterY = targetCy
                isFirstFrame = false
            } else {
                // 以 box 顶边中心作为死区判断基准 (像素偏差)
                dxPx = (targetCx - roiCenterX) * imageWidth
                dyPx = (targetCy - roiCenterY) * imageHeight
                maxOffsetPx = max(abs(dxPx), abs(dyPx))

                // A. 顶边两点移动判定 (10% 阈值)
                deadZoneThreshold = 0.1f * baseRoiSizePx
                val topLeftX = targetBox.left
                val topLeftY = targetBox.top
                val topRightX = targetBox.right
                val topRightY = targetBox.top
                val anchorTopLeftX = lastTopLeftX
                val anchorTopLeftY = lastTopLeftY
                val anchorTopRightX = lastTopRightX
                val anchorTopRightY = lastTopRightY
                topMovePx = if (hasLastTop) {
                    val dlxPx = (topLeftX - anchorTopLeftX) * imageWidth
                    val dlyPx = (topLeftY - anchorTopLeftY) * imageHeight
                    val drxPx = (topRightX - anchorTopRightX) * imageWidth
                    val dryPx = (topRightY - anchorTopRightY) * imageHeight
                    val leftMove = kotlin.math.hypot(dlxPx, dlyPx)
                    val rightMove = kotlin.math.hypot(drxPx, dryPx)
                    max(leftMove, rightMove)
                } else {
                    0f
                }
                lastTopMove = topMovePx
                val enableCenterOffset = false
                val effectiveCenterOffset = if (enableCenterOffset) maxOffsetPx else 0f
                inDeadZone = topMovePx <= deadZoneThreshold && effectiveCenterOffset <= deadZoneThreshold
                if (topMovePx <= deadZoneThreshold && effectiveCenterOffset <= deadZoneThreshold && lastRoi != null) {
                    return lastRoi!!
                }
                if (topMovePx > deadZoneThreshold || effectiveCenterOffset > deadZoneThreshold) {
                    RoiLogAggregator.updateTopDbg(
                        anchorTopLeftX,
                        anchorTopLeftY,
                        anchorTopRightX,
                        anchorTopRightY,
                        topLeftX,
                        topLeftY,
                        topRightX,
                        topRightY
                    )

                    // B. 响应模式判定
                    if (maxOffsetPx > 0.25f * baseRoiSizePx) {
                        // [跳变模式]: 偏差 > 25% 框体，直接瞬移对齐
                        roiCenterX = targetCx
                        roiCenterY = targetCy
                    } else {
                        // [EMA 平滑模式]
                        // 确定跟随时间常数 tau (跟随时间)
                        val tau = if (maxOffsetPx > 0.12f * baseRoiSizePx) {
                            0.00008f * baseRoiSizePx // [快车道]: 约 0.05s (0.00008 * 640)
                        } else {
                            0.00025f * baseRoiSizePx // [标准]: 约 0.16s (0.00025 * 640)
                        }

                        // 跟随系数 alpha = 1 - exp(-dt / tau)
                        // 这里的 dt 是真实的两次处理间隔，帧率越高 dt 越小，步伐随之变细
                        val alpha = (1f - exp(-dt / tau)).coerceIn(0f, 1f)

                        roiCenterX += alpha * (targetCx - roiCenterX)
                        roiCenterY += alpha * (targetCy - roiCenterY)
                    }
                    // 仅在触发移动时刷新锚点，允许慢速累计
                    lastTopLeftX = topLeftX
                    lastTopLeftY = topLeftY
                    lastTopRightX = topRightX
                    lastTopRightY = topRightY
                    hasLastTop = true
                }
            }
        } else {
            enlargePendingSinceMs = 0L
            // 目标丢失，不更新 roiCenter，直接返回 lastRoi
            if (lastRoi != null) return lastRoi!!
        }

        // 4. 基于 roiCenterX/Y 生成初始 Rect
        var left = roiCenterX - roiNormW / 2
        var top = roiCenterY - roiNormH / 2
        var right = left + roiNormW
        var bottom = top + roiNormH
        var wasClamped = false
        var clampL = false
        var clampR = false
        var clampT = false
        var clampB = false

        // 5. 边界约束与物理平移 (Clamp & Shift)
        if (left < 0f) { val offset = -left; left += offset; right += offset; wasClamped = true; clampL = true }
        else if (right > 1f) { val offset = 1f - right; left += offset; right += offset; wasClamped = true; clampR = true }
        
        if (top < 0f) { val offset = -top; top += offset; bottom += offset; wasClamped = true; clampT = true }
        else if (bottom > 1f) { val offset = 1f - bottom; top += offset; bottom += offset; wasClamped = true; clampB = true }

        // 强制不越界最终修正
        left = left.coerceIn(0f, 1f - roiNormW)
        top = top.coerceIn(0f, 1f - roiNormH)
        val finalRoi = RectF(left, top, left + roiNormW, top + roiNormH)

        val shouldUpdateRoi = !inDeadZone
        if (!wasClamped && lastRoi != null) {
            val last = lastRoi!!
            val deltaL = abs(finalRoi.left - last.left) * imageWidth
            val deltaT = abs(finalRoi.top - last.top) * imageHeight
            val deltaR = abs(finalRoi.right - last.right) * imageWidth
            val deltaB = abs(finalRoi.bottom - last.bottom) * imageHeight
            val maxDeltaPx = max(max(deltaL, deltaT), max(deltaR, deltaB))
            if (maxDeltaPx <= deadZoneThreshold && !shouldUpdateRoi) {
                return last
            }
        }
        
        // 反向同步平滑中心点，防止由于边界平移导致的中心点累积偏差
        roiCenterX = finalRoi.centerX()
        roiCenterY = finalRoi.centerY()

        if (targetBox != null) {
            RoiLogAggregator.updateRoiTick(
                dxPx,
                dyPx,
                maxOffsetPx,
                deadZoneThreshold,
                inDeadZone,
                lastTopMove,
                roiCenterY,
                currentRoiSize,
                clampL,
                clampR,
                clampT,
                clampB
            )
        }

        lastRoi = finalRoi
        return finalRoi
    }

    /**
     * 重置平滑器状态。
     * 当彻底跟丢返回全搜模式，或重新锁定目标时调用，防止之前的平滑余效干扰新锁定位置。
     */
    fun resetSmoothing() {
        isFirstFrame = true
        lastUpdateTs = 0L
        hasLastTop = false
        enlargePendingSinceMs = 0L
    }
}
