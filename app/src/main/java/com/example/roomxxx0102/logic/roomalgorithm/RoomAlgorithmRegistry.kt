package com.example.roomxxx0102.logic.roomalgorithm

import android.content.Context
import com.example.roomxxx0102.logic.roomalgorithm.gate.*
import com.example.roomxxx0102.logic.roomalgorithm.flow.PortalV3RoomAlgorithm
import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceEstimatorParams
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTrackerRegistry

object RoomAlgorithmRegistry {
    const val PORTAL_V3_FLOW_ID = "portal_v3_flow"
    const val LEGACY_PRESENCE_ID = "legacy_presence"
    const val PORTAL_V2_ID = "portal_v2"
    const val PORTAL_V2_MIL_ID = "portal_v2_mil"

    data class AlgorithmOption(val algorithmId: String, val displayName: String)

    data class CreationConfig(
        val presenceVersionId: String?,
        val presenceParams: PresenceEstimatorParams = PresenceEstimatorParams(),
        val portalVisualTrackerId: String? = null,
        val appContext: Context? = null
    )

    private data class Registration(
        val option: AlgorithmOption,
        val factory: (CreationConfig) -> RoomAlgorithmEngine
    )

    private val registrations = GateMethod.entries.map { method ->
        Registration(AlgorithmOption(method.id, method.label)) { config ->
            GateRoomAlgorithm(config.appContext, GateSettings.load(config.appContext, method))
        }
    } + listOf(
        Registration(AlgorithmOption(PORTAL_V3_FLOW_ID, "Portal V3 · 门线与人体光流（实验）")) { config ->
            PortalV3RoomAlgorithm(config.appContext)
        },
        Registration(AlgorithmOption(LEGACY_PRESENCE_ID, "Legacy Presence")) { config ->
            LegacyPresenceRoomAlgorithm(config.presenceVersionId, config.presenceParams)
        },
        Registration(AlgorithmOption(PORTAL_V2_ID, "Portal V2 · ViTTrack（实验）")) { config ->
            PortalV2RoomAlgorithm(
                selectedPresenceVersionId = config.presenceVersionId,
                presenceParams = config.presenceParams,
                visualTrackerId = PortalVisualTrackerRegistry.OPENCV_VIT_ID
            )
        },
        Registration(AlgorithmOption(PORTAL_V2_MIL_ID, "Portal V2 · MIL（旧基线）")) { config ->
            PortalV2MilRoomAlgorithm(
                selectedPresenceVersionId = config.presenceVersionId,
                presenceParams = config.presenceParams
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
        GateMethod.from(algorithmId)?.let { method ->
            return algorithmId + "|" + GateSettings.load(config.appContext, method).key
        }
        val presenceVersion = PresenceAlgorithmRegistry.resolveVersionId(config.presenceVersionId)
        return when (algorithmId) {
            LEGACY_PRESENCE_ID -> "$algorithmId|$presenceVersion"
            PORTAL_V2_ID ->
                "$algorithmId|${PortalVisualTrackerRegistry.OPENCV_VIT_ID}|$LEGACY_PRESENCE_ID|$presenceVersion"
            PORTAL_V2_MIL_ID ->
                "$algorithmId|${PortalVisualTrackerRegistry.OPENCV_MIL_ID}|$LEGACY_PRESENCE_ID|$presenceVersion"
            else -> algorithmId
        }
    }

    fun create(selectedId: String?, config: CreationConfig): RoomAlgorithmEngine {
        val resolvedId = resolveAlgorithmId(selectedId)
        val registration = registrations.first { it.option.algorithmId == resolvedId }
        return registration.factory(config)
    }
}
