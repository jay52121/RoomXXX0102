package com.example.roomxxx0102.logic.roomalgorithm

import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceOutsideMode
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTrackerRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAlgorithmRegistryTest {
    @Test
    fun portalV3IsSelectableWithoutChangingLegacyDefaults() {
        val config=RoomAlgorithmRegistry.CreationConfig(null)
        val v3=RoomAlgorithmRegistry.create(RoomAlgorithmRegistry.PORTAL_V3_FLOW_ID,config)
        assertEquals("portal_v3_flow",v3.algorithmId)
        assertEquals(RoomAlgorithmRegistry.LEGACY_PRESENCE_ID,RoomAlgorithmRegistry.resolveAlgorithmId(null))
        assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId==v3.algorithmId })
    }

    @Test
    fun invalidAlgorithmIdFallsBackToLegacy() {
        val config = RoomAlgorithmRegistry.CreationConfig(
            presenceVersionId = PresenceAlgorithmRegistry.AUTO_LATEST
        )

        val engine = RoomAlgorithmRegistry.create("removed_algorithm", config)

        assertEquals(RoomAlgorithmRegistry.LEGACY_PRESENCE_ID, engine.algorithmId)
        assertTrue(
            RoomAlgorithmRegistry.options().any {
                it.algorithmId == RoomAlgorithmRegistry.LEGACY_PRESENCE_ID
            }
        )
    }

    @Test
    fun portalV2UsesVitTrackAndKeepsMilBaselineSelectable() {
        val config = RoomAlgorithmRegistry.CreationConfig(
            presenceVersionId = PresenceAlgorithmRegistry.VERSION_V1_6_2_B03060320
        )

        val vit = RoomAlgorithmRegistry.create(RoomAlgorithmRegistry.PORTAL_V2_ID, config)
        val mil = RoomAlgorithmRegistry.create(RoomAlgorithmRegistry.PORTAL_V2_MIL_ID, config)

        assertEquals(RoomAlgorithmRegistry.PORTAL_V2_ID, vit.algorithmId)
        assertEquals(RoomAlgorithmRegistry.PORTAL_V2_MIL_ID, mil.algorithmId)
        assertTrue(vit.configurationKey.contains(PortalVisualTrackerRegistry.OPENCV_VIT_ID))
        assertTrue(mil.configurationKey.contains(PortalVisualTrackerRegistry.OPENCV_MIL_ID))
        assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId == RoomAlgorithmRegistry.PORTAL_V2_ID })
        assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId == RoomAlgorithmRegistry.PORTAL_V2_MIL_ID })
    }

    @Test
    fun legacyAdapterPreservesPresenceOutputForEmptyFrame() {
        val presenceVersion = PresenceAlgorithmRegistry.VERSION_V1_6_2_B03060320
        val legacy = LegacyPresenceRoomAlgorithm(presenceVersion)
        val direct = PresenceAlgorithmRegistry.create(presenceVersion)

        val adaptedResult = legacy.processFrame(
            RoomAlgorithmFrameInput(
                bitmap = null,
                timestampMs = 1234L,
                frameSeq = 1L,
                poses = emptyList(),
                rooms = emptyList(),
                doors = emptyList(),
                imageWidth = 1920,
                imageHeight = 1080,
                sceneInfo = RoomAlgorithmSceneInfo(
                    isVideoPlayback = true,
                    outsideMode = PresenceOutsideMode.INVISIBLE
                )
            )
        )
        val directResult = direct.processFrame(
            rooms = emptyList(),
            doors = emptyList(),
            observations = emptyList(),
            outsideMode = PresenceOutsideMode.INVISIBLE
        )

        assertEquals(directResult.observedCounts, adaptedResult.observedCounts)
        assertEquals(directResult.presenceCounts, adaptedResult.roomCounts)
        assertEquals(directResult.events, adaptedResult.events)
        assertEquals(directResult.pendingDoorCounters, adaptedResult.pendingDoorCounters)
        assertEquals(directResult.rejectedReasons, adaptedResult.rejectedReasons)
        assertEquals(directResult.trackSwitchScores, adaptedResult.trackSwitchScores)
        assertTrue(adaptedResult.debugInfo.details.containsKey("timestampMs"))
    }
}
