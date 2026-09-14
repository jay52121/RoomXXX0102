package com.example.roomxxx0102.logic.roomalgorithm.portal

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.roomxxx0102.data.model.PoseResult

enum class PortalVisualTrackState {
    TRACKING,
    SUSPECT
}

data class PortalVisualTrackerFrameInput(
    val bitmap: Bitmap?,
    val timestampMs: Long,
    val frameSeq: Long,
    val poses: List<PoseResult>,
    val imageWidth: Int,
    val imageHeight: Int,
    val allowInitialization: Boolean = false,
    val preferredTrackId: Int? = null
)

data class PortalVisualTrack(
    val trackId: Int,
    val bounds: RectF,
    val state: PortalVisualTrackState,
    val rawScore: Float? = null,
    val updateTimeMs: Long = 0L,
    val note: String = ""
)

interface PortalVisualTracker {
    val trackerId: String
    fun track(input: PortalVisualTrackerFrameInput): List<PortalVisualTrack>
    fun reset()
}
