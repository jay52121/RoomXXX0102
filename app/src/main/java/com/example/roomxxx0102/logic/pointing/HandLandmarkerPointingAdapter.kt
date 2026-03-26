package com.example.roomxxx0102.logic.pointing

import android.graphics.PointF
import com.example.roomxxx0102.logic.analyzer.HandSmokeTester
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * 仅负责把 MediaPipe 结果转换成业务层 HandObservation。
 *
 * 注意：
 * 1) startSession 的 startTimestampMs 与这里输出的 timestampMs 必须来自同一个单调时间基准。
 * 2) landmarksPx 与 target rect 必须已经在同一个图像坐标系里；不要在 resolver 内部猜测镜像/旋转。
 */
object HandLandmarkerPointingAdapter {

    fun toObservation(
        result: HandLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int,
        forearmHint: ForearmHint? = null
    ): HandObservation {
        val hands = result.landmarks()
        val firstHand = hands.firstOrNull().orEmpty()
        val handedness = result.handednesses().firstOrNull()?.firstOrNull()
        return HandObservation(
            timestampMs = result.timestampMs(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            landmarksPx = firstHand.map { landmark ->
                PointF(
                    landmark.x() * imageWidth.toFloat(),
                    landmark.y() * imageHeight.toFloat()
                )
            },
            handCount = hands.size,
            handednessLabel = handedness?.categoryName(),
            handednessScore = handedness?.score(),
            forearmHint = forearmHint
        )
    }

    fun toObservation(
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        landmarks: List<HandSmokeTester.HandPoint>,
        handCount: Int,
        handedness: Category?,
        forearmHint: ForearmHint? = null
    ): HandObservation {
        return HandObservation(
            timestampMs = timestampMs,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            landmarksPx = landmarks.map { landmark ->
                PointF(
                    landmark.x * imageWidth.toFloat(),
                    landmark.y * imageHeight.toFloat()
                )
            },
            handCount = handCount,
            handednessLabel = handedness?.categoryName(),
            handednessScore = handedness?.score(),
            forearmHint = forearmHint
        )
    }
}
