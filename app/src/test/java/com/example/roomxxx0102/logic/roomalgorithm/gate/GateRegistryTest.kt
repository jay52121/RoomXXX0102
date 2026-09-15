package com.example.roomxxx0102.logic.roomalgorithm.gate

import com.example.roomxxx0102.logic.roomalgorithm.RoomAlgorithmRegistry
import org.junit.Assert.*
import org.junit.Test

class GateRegistryTest {
    @Test fun allThreeEnginesAreSelectableAndHaveMatchingKeys() {
        val config=RoomAlgorithmRegistry.CreationConfig(null)
        for(method in GateMethod.entries) {
            assertTrue(RoomAlgorithmRegistry.options().any { it.algorithmId==method.id })
            val engine=RoomAlgorithmRegistry.create(method.id,config)
            assertEquals(method.id,engine.algorithmId)
            assertEquals(RoomAlgorithmRegistry.configurationKey(method.id,config),engine.configurationKey)
        }
    }
    @Test fun oldDefaultsAndRegistrationsArePreserved() {
        assertEquals(RoomAlgorithmRegistry.LEGACY_PRESENCE_ID,RoomAlgorithmRegistry.resolveAlgorithmId(null))
        for(id in listOf("legacy_presence","portal_v2","portal_v2_mil","portal_v3_flow")) {
            assertEquals(id,RoomAlgorithmRegistry.resolveAlgorithmId(id))
        }
    }
    @Test fun opticalFlowHasItsOwnResolutionAndKey() {
        assertEquals(512,GateParams.defaults(GateMethod.DIFFERENCE).imageEdge)
        assertEquals(640,GateParams.defaults(GateMethod.OPTICAL_FLOW).imageEdge)
        assertNotEquals(GateSettings.key(null,GateMethod.DIFFERENCE),GateSettings.key(null,GateMethod.MOG2))
    }
}
