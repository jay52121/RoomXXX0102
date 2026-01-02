package com.example.roomxxx0102

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
}