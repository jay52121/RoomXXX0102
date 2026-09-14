package com.example.roomxxx0102.logic.roomalgorithm

import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceEstimatorParams
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTrackerRegistry

object RoomAlgorithmRegistry {
    const val LEGACY_PRESENCE_ID = "legacy_presence"
    const val PORTAL_V2_ID = "portal_v2"

    data class AlgorithmOption(val algorithmId: String, val displayName: String)

    data class CreationConfig(
        val presenceVersionId: String?,
        val presenceParams: PresenceEstimatorParams = PresenceEstimatorParams(),
        val portalVisualTrackerId: String? = null
    )

    private data class Registration(
        val option: AlgorithmOption,
        val factory: (CreationConfig) -> RoomAlgorithmEngine
    )

    private val registrations = listOf(
        Registration(AlgorithmOption(LEGACY_PRESENCE_ID, "Legacy Presence")) { config ->
            LegacyPresenceRoomAlgorithm(config.presenceVersionId, config.presenceParams)
        },
        Registration(AlgorithmOption(PORTAL_V2_ID, "Portal V2（实验）")) { config ->
            PortalV2RoomAlgorithm(
                selectedPresenceVersionId = config.presenceVersionId,
                presenceParams = config.presenceParams,
                visualTrackerId = config.portalVisualTrackerId
            )
        }
    )

    fun options(): List<AlgorithmOption> = registrations.map { it.option }

    fun resolveAlgorithmId(selectedId: String?): String {
        return registrations.firstOrNull { it.option.algorithmId == selectedId }
            ?.option?.algorithmId
            ?: LEGACY_PRESENCE_ID
    }

    fun configurationKey(selectedId: String?, config: CreationConfig): String {
        val algorithmId = resolveAlgorithmId(selectedId)
        val presenceVersion = PresenceAlgorithmRegistry.resolveVersionId(config.presenceVersionId)
        return when (algorithmId) {
            LEGACY_PRESENCE_ID -> "$algorithmId|$presenceVersion"
            PORTAL_V2_ID -> {
                val trackerId = PortalVisualTrackerRegistry.resolveTrackerId(config.portalVisualTrackerId)
                "$algorithmId|$trackerId|$presenceVersion"
            }
            else -> algorithmId
        }
    }

    fun create(selectedId: String?, config: CreationConfig): RoomAlgorithmEngine {
        val resolvedId = resolveAlgorithmId(selectedId)
        val registration = registrations.first { it.option.algorithmId == resolvedId }
        return registration.factory(config)
    }
}
