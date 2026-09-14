package com.example.roomxxx0102.logic.roomalgorithm.portal

object PortalVisualTrackerRegistry {
    data class TrackerOption(val trackerId: String, val displayName: String)

    private data class Registration(
        val option: TrackerOption,
        val factory: () -> PortalVisualTracker
    )

    private val registrations: List<Registration> = emptyList()

    fun options(): List<TrackerOption> = registrations.map { it.option }

    fun create(trackerId: String): PortalVisualTracker? {
        return registrations.firstOrNull { it.option.trackerId == trackerId }?.factory?.invoke()
    }
}
