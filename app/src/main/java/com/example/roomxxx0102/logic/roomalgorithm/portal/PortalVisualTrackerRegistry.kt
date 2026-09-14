package com.example.roomxxx0102.logic.roomalgorithm.portal

object PortalVisualTrackerRegistry {
    const val OPENCV_CSRT_ID = "opencv_csrt"

    data class TrackerOption(val trackerId: String, val displayName: String)

    private data class Registration(
        val option: TrackerOption,
        val factory: () -> PortalVisualTracker
    )

    private val registrations: List<Registration> = listOf(
        Registration(TrackerOption(OPENCV_CSRT_ID, "OpenCV CSRT")) {
            CsrtPortalVisualTracker()
        }
    )

    fun options(): List<TrackerOption> = registrations.map { it.option }

    fun resolveTrackerId(selectedId: String?): String {
        return registrations.firstOrNull { it.option.trackerId == selectedId }
            ?.option?.trackerId
            ?: OPENCV_CSRT_ID
    }

    fun create(trackerId: String?): PortalVisualTracker {
        val resolved = resolveTrackerId(trackerId)
        return registrations.first { it.option.trackerId == resolved }.factory()
    }
}
