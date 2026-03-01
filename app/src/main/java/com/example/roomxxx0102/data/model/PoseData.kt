package com.example.roomxxx0102.data.model

import android.graphics.PointF
import android.graphics.RectF

/**
 * 全局关键点置信度阈值。
 * 用于判定关键点是否“高度可信” (绿色)。
 */
const val POSE_HIGH_CONFIDENCE_THRESHOLD = 0.7f

data class Keypoint(
    val x: Float,
    val y: Float,
    val conf: Float
)
//| 序号 | 英文名            | 中文说明   |
//| -- | -------------- | ------ |
//| 0  | nose           | 鼻尖     |
//| 1  | left_eye       | 左眼中心   |
//| 2  | right_eye      | 右眼中心   |
//| 3  | left_ear       | 左耳     |
//| 4  | right_ear      | 右耳     |
//| 5  | left_shoulder  | 左肩     |
//| 6  | right_shoulder | 右肩     |
//| 7  | left_elbow     | 左肘     |
//| 8  | right_elbow    | 右肘     |
//| 9  | left_wrist     | 左腕（手腕） |
//| 10 | right_wrist    | 右腕（手腕） |
//| 11 | left_hip       | 左髋（胯部） |
//| 12 | right_hip      | 右髋     |
//| 13 | left_knee      | 左膝     |
//| 14 | right_knee     | 右膝     |
//| 15 | left_ankle     | 左踝     |
//| 16 | right_ankle    | 右踝     |

enum class IdSource {
    LOCAL,
    REMOTE
}

data class PoseResult(
    val id: Int,
    val box: RectF,
    val keypoints: List<Keypoint>,
    val score: Float,
    val isMoving: Boolean = false,
    val isConfirmed: Boolean = false,
    val idSource: IdSource = IdSource.LOCAL, // 🔥 恢复：ID 来源标记
    val isShielded: Boolean = false
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
            val point = if (leftAnkle.conf > CONF_THRESHOLD && rightAnkle.conf > CONF_THRESHOLD) {
                PointF((leftAnkle.x + rightAnkle.x) / 2, (leftAnkle.y + rightAnkle.y) / 2)
            } else if (leftAnkle.conf > CONF_THRESHOLD) {
                PointF(leftAnkle.x, leftAnkle.y)
            } else if (rightAnkle.conf > CONF_THRESHOLD) {
                PointF(rightAnkle.x, rightAnkle.y)
            } else {
                // 兜底：Box 底部中心
                PointF(box.centerX(), box.bottom)
            }
            if (point.y > 1f) {
                point.y = 1f
            }
            return point
        }
}

