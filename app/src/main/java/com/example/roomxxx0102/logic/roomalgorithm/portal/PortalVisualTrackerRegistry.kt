package com.example.roomxxx0102.logic.roomalgorithm.portal

object PortalVisualTrackerRegistry {
    const val OPENCV_MIL_ID = "opencv_mil"

    data class TrackerOption(val trackerId: String, val displayName: String)

    private data class Registration(
        val option: TrackerOption,
        val factory: () -> PortalVisualTracker
    )

    private val registrations: List<Registration> = listOf(
        Registration(TrackerOption(OPENCV_MIL_ID, "OpenCV MIL")) {
            MilPortalVisualTracker()
        }
    )

    fun options(): List<TrackerOption> = registrations.map { it.option }

    fun resolveTrackerId(selectedId: String?): String {
        return registrations.firstOrNull { it.option.trackerId == selectedId }
            ?.option?.trackerId
            ?: OPENCV_MIL_ID
    }

    fun create(trackerId: String?): PortalVisualTracker {
        val resolved = resolveTrackerId(trackerId)
        return registrations.first { it.option.trackerId == resolved }.factory()
    }
}
