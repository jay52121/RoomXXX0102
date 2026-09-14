package com.example.roomxxx0102.logic.roomalgorithm.portal

import android.graphics.RectF

data class PortalVisualDebugSnapshot(
    val trackId: Int,
    val bounds: RectF,
    val state: PortalVisualTrackState,
    val detectorVisible: Boolean,
    val trackerId: String,
    val note: String
)

/**
 * Portal V2 的纯调试旁路。
 *
 * 只保存最近一帧可绘制的视觉跟踪结果；不参与房间判定、人数状态或 tracker 决策。
 */
object PortalVisualDebugStore {
    @Volatile
    private var latest: PortalVisualDebugSnapshot? = null

    fun update(
        track: PortalVisualTrack?,
        detectorVisible: Boolean,
        trackerId: String
    ) {
        latest = track?.let {
            PortalVisualDebugSnapshot(
                trackId = it.trackId,
                bounds = RectF(it.bounds),
                state = it.state,
                detectorVisible = detectorVisible,
                trackerId = trackerId,
                note = it.note
            )
        }
    }

    fun snapshot(): PortalVisualDebugSnapshot? {
        val value = latest ?: return null
        return value.copy(bounds = RectF(value.bounds))
    }

    fun clear() {
        latest = null
    }
}
