package com.example.roomxxx0102.logic.roomalgorithm

import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceEstimatorParams

object RoomAlgorithmRegistry {
    const val LEGACY_PRESENCE_ID = "legacy_presence"

    data class AlgorithmOption(val algorithmId: String, val displayName: String)

    data class CreationConfig(
        val presenceVersionId: String?,
        val presenceParams: PresenceEstimatorParams = PresenceEstimatorParams()
    )

    private data class Registration(
        val option: AlgorithmOption,
        val factory: (CreationConfig) -> RoomAlgorithmEngine
    )

    private val registrations = listOf(
        Registration(AlgorithmOption(LEGACY_PRESENCE_ID, "Legacy Presence")) { config ->
            LegacyPresenceRoomAlgorithm(config.presenceVersionId, config.presenceParams)
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
        return when (algorithmId) {
            LEGACY_PRESENCE_ID -> "$algorithmId|${PresenceAlgorithmRegistry.resolveVersionId(config.presenceVersionId)}"
            else -> algorithmId
        }
    }

    fun create(selectedId: String?, config: CreationConfig): RoomAlgorithmEngine {
        val resolvedId = resolveAlgorithmId(selectedId)
        val registration = registrations.first { it.option.algorithmId == resolvedId }
        return registration.factory(config)
    }
}
