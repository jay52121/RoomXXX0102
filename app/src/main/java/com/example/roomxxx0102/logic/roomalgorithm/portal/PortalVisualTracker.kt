package com.example.roomxxx0102.logic.roomalgorithm.portal

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.roomxxx0102.data.model.PoseResult

data class PortalVisualTrackerFrameInput(
    val bitmap: Bitmap?,
    val timestampMs: Long,
    val frameSeq: Long,
    val poses: List<PoseResult>,
    val imageWidth: Int,
    val imageHeight: Int
)

data class PortalVisualTrack(
    val trackId: String,
    val bounds: RectF,
    val confidence: Float
)

interface PortalVisualTracker {
    val trackerId: String
    fun track(input: PortalVisualTrackerFrameInput): List<PortalVisualTrack>
    fun reset()
}
