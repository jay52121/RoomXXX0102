package com.example.roomxxx0102.logic.roomalgorithm

import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceOutsideMode
import com.example.roomxxx0102.logic.roomalgorithm.portal.PortalVisualTrackerRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAlgorithmRegistryTest {
    @Test fun allThreeGateMethodsAreSelectableAndUseDistinctKeys() {
        val c=RoomAlgorithmRegistry.CreationConfig(null)
        val ids=listOf("portal_v4_diff","portal_v4_lk","portal_v4_mog2")
        val keys=ids.map { id ->
            assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId==id })
            val e=RoomAlgorithmRegistry.create(id,c)
            assertEquals(id,e.algorithmId)
            assertEquals(RoomAlgorithmRegistry.configurationKey(id,c),e.configurationKey)
            e.configurationKey
        }
        assertEquals(3,keys.toSet().size)
    }

    @Test fun v53DifferenceKeepsEarlierVersionsSelectable() {
        val config = RoomAlgorithmRegistry.CreationConfig(null)
        val id = "portal_v5_3_diff"
        assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId == id })
        val engine = RoomAlgorithmRegistry.create(id, config)
        assertEquals(id, engine.algorithmId)
        assertTrue(engine.runtimeTag.startsWith("GateV5.3"))
        assertTrue(engine.configurationKey != RoomAlgorithmRegistry.configurationKey("portal_v5_2_diff", config))
    }

    @Test fun v52DifferenceIsSelectableSeparatelyFromV51() {
        val config = RoomAlgorithmRegistry.CreationConfig(null)
        val id = "portal_v5_2_diff"
        assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId == id })
        val engine = RoomAlgorithmRegistry.create(id, config)
        assertEquals(id, engine.algorithmId)
        assertTrue(engine.runtimeTag.startsWith("GateV5.2"))
        assertEquals(RoomAlgorithmRegistry.configurationKey(id, config), engine.configurationKey)
        assertTrue(engine.configurationKey != RoomAlgorithmRegistry.configurationKey("portal_v5_1_diff", config))
    }

    @Test fun v51DifferenceHasIndependentAlgorithmIdAndRuntime() {
        val config = RoomAlgorithmRegistry.CreationConfig(null)
        val id = "portal_v5_1_diff"
        assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId == id })
        val engine = RoomAlgorithmRegistry.create(id, config)
        assertEquals(id, engine.algorithmId)
        assertTrue(engine.runtimeTag.startsWith("GateV5.1"))
        assertEquals(RoomAlgorithmRegistry.configurationKey(id, config), engine.configurationKey)
        assertTrue(engine.configurationKey != RoomAlgorithmRegistry.configurationKey("portal_v5_diff", config))
    }

    @Test fun v5DifferenceIsSelectableWithItsOwnRuntimeAndSettings() {
        val config = RoomAlgorithmRegistry.CreationConfig(null)
        val id = "portal_v5_diff"
        assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId == id })
        val engine = RoomAlgorithmRegistry.create(id, config)
        assertEquals(id, engine.algorithmId)
        assertTrue(engine.runtimeTag.startsWith("GateV5"))
        assertEquals(RoomAlgorithmRegistry.configurationKey(id,config),engine.configurationKey)
        assertTrue(engine.configurationKey != RoomAlgorithmRegistry.configurationKey("portal_v4_diff",config))
    }

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
