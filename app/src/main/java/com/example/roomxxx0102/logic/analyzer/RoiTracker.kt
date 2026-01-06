package com.example.roomxxx0102.logic.analyzer

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * **动态 ROI 追踪器 (Dynamic ROI Tracker)**
 *
 * 负责计算一个关注区域 (ROI)，使其：
 * 1. 尽可能跟随目标中心。
 * 2. 永远不超出图像边界 (0.0 ~ 1.0)。
 * 3. 目标丢失时保持在最后位置。
 * 4. **自适应尺寸**: 根据目标大小自动扩容或缩容 (640px 起步)。
 */
class RoiTracker {

    // 上一次的 ROI 区域 (归一化坐标)，用于目标丢失时保持位置
    private var lastRoi: RectF? = null

    // 当前 ROI 物理尺寸 (像素)，初始为 modelInputWidth
    private val modelInputWidth = 640f
    private var currentRoiSize = modelInputWidth

    // 🔥 是否正在跟踪 (Target is present)
    var isTracking: Boolean = false
        private set

    /**
     * 计算下一帧的 ROI 区域。
     *
     * @param imageWidth 原图宽度 (px)
     * @param imageHeight 原图高度 (px)
     * @param targetBox 当前检测到的目标归一化框 (0..1)。如果为 null，则保持原地。
     * @return 计算后的 ROI 归一化区域 (0..1)。
     */
    fun calculate(imageWidth: Int, imageHeight: Int, targetBox: RectF?): RectF {
        // 更新跟踪状态
        isTracking = (targetBox != null)

        // 1. 自适应尺寸调整逻辑
        if (targetBox != null) {
            val personW = targetBox.width() * imageWidth
            val personH = targetBox.height() * imageHeight
            val maxPersonSide = max(personW, personH)

            // 新逻辑：带状态的双阈值切换（按长边/ROI 边长比例）
            val minImageSide = min(imageWidth.toFloat(), imageHeight.toFloat())
            val ratio = if (currentRoiSize > 0f) maxPersonSide / currentRoiSize else 0f
            if (currentRoiSize <= modelInputWidth) {
                // 上切：仅当当前为 640 时生效
                if (ratio > 0.85f) {
                    currentRoiSize = minImageSide
                }
            } else {
                // 下切：仅当当前为画面短边时生效
                if (ratio < 0.35f) {
                    currentRoiSize = modelInputWidth
                }
            }
        }

        // 2. 计算当前分辨率下的归一化比例
        val roiNormW = currentRoiSize / imageWidth
        val roiNormH = currentRoiSize / imageHeight

        // 3. 确定中心点
        val cx: Float
        val cy: Float

        if (targetBox != null) {
            cx = targetBox.centerX()
            cy = targetBox.centerY()
        } else {
            // 目标丢失，使用上次位置或居中
            return lastRoi ?: run {
                val defaultLeft = (1f - roiNormW) / 2
                val defaultTop = (1f - roiNormH) / 2
                RectF(defaultLeft, defaultTop, defaultLeft + roiNormW, defaultTop + roiNormH).also { lastRoi = it }
            }
        }

        // 4. 基于中心点生成初始 ROI
        var left = cx - roiNormW / 2
        var top = cy - roiNormH / 2
        var right = left + roiNormW
        var bottom = top + roiNormH

        // 5. 边界约束与平移 (Clamp & Shift)
        if (left < 0f) {
            val offset = -left
            left += offset
            right += offset
        } else if (right > 1f) {
            val offset = 1f - right
            left += offset
            right += offset
        }
        left = max(0f, left)
        right = min(1f, right)

        if (top < 0f) {
            val offset = -top
            top += offset
            bottom += offset
        } else if (bottom > 1f) {
            val offset = 1f - bottom
            top += offset
            bottom += offset
        }
        top = max(0f, top)
        bottom = min(1f, bottom)

        // 6. 更新状态并返回
        val newRoi = RectF(left, top, right, bottom)
        lastRoi = newRoi
        return newRoi
    }
}
