package com.example.roomxxx0102.logic.roomalgorithm

import com.example.roomxxx0102.logic.presence.PresenceAlgorithmRegistry
import com.example.roomxxx0102.logic.presence.PresenceOutsideMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAlgorithmRegistryTest {
    @Test
    fun invalidAlgorithmIdFallsBackToLegacy() {
        val config = RoomAlgorithmRegistry.CreationConfig(
            presenceVersionId = PresenceAlgorithmRegistry.AUTO_LATEST
        )

        val engine = RoomAlgorithmRegistry.create("removed_algorithm", config)

        assertEquals(RoomAlgorithmRegistry.LEGACY_PRESENCE_ID, engine.algorithmId)
        assertEquals(RoomAlgorithmRegistry.LEGACY_PRESENCE_ID, RoomAlgorithmRegistry.options().single().algorithmId)
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
