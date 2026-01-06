package com.example.roomxxx0102.data.model

import android.graphics.PointF
import android.graphics.RectF

data class Keypoint(
    val x: Float,
    val y: Float,
    val conf: Float
)

data class PoseResult(
    val id: Int,
    val box: RectF,
    val keypoints: List<Keypoint>,
    val score: Float,
    val isMoving: Boolean = false,
    val isConfirmed: Boolean = false // 🔥 新增：是否已锁定(曾高分且移动过)
) {
    fun getVisibleKeypointCount(threshold: Float = 0.5f): Int {
        return keypoints.count { it.conf > threshold }
    }

    /**
     * **计算落地坐标 (Landing Point)**
     *
     * 遵循 Single Source of Truth 原则，该逻辑现在从 UI 层移至数据层。
     * 返回归一化坐标 (0.0 ~ 1.0)。
     *
     * 逻辑：
     * 1. 优先取双脚踝中点。
     * 2. 其次取单脚踝。
     * 3. 兜底取检测框底部中心。
     */
    val landingPoint: PointF
        get() {
            if (keypoints.size < 17) return PointF(box.centerX(), box.bottom)

            val leftAnkle = keypoints[15]
            val rightAnkle = keypoints[16]
            val CONF_THRESHOLD = 0.00f//相信脚踝的阈值

            return if (leftAnkle.conf > CONF_THRESHOLD && rightAnkle.conf > CONF_THRESHOLD) {
                PointF((leftAnkle.x + rightAnkle.x) / 2, (leftAnkle.y + rightAnkle.y) / 2)
            } else if (leftAnkle.conf > CONF_THRESHOLD) {
                PointF(leftAnkle.x, leftAnkle.y)
            } else if (rightAnkle.conf > CONF_THRESHOLD) {
                PointF(rightAnkle.x, rightAnkle.y)
            } else {
                // 兜底：Box 底部中心
                PointF(box.centerX(), box.bottom)
            }
        }
}
