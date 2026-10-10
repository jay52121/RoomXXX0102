package com.example.roomxxx0102.logic.webdebug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDebugMatchingTest {
    private fun mark(time: Long, roomId: String? = "bedroom", key: String = "m") =
        WebMarkedEvent(key, time, "ENTER", roomId)

    private fun out(time: Long, roomId: String, key: String = "r", type: String = "ENTER") =
        WebRuntimeEvent(key, time, type, roomId)

    @Test fun correctRoomAndDirectionMatch() {
        val m = WebDebugMatching.classify(listOf(mark(1000)), listOf(out(1390, "bedroom")), 2300, 0)
        assertEquals("MATCH", m.single().classification)
        assertEquals(390L, m.single().deltaMs)
    }

    @Test fun wrongRoomIsNotAValidMatch() {
        val m = WebDebugMatching.classify(listOf(mark(1000)), listOf(out(1200, "bathroom")), 2300, 0)
        assertEquals("WRONG_PORTAL", m.single().classification)
    }

    @Test fun wrongDirectionIsNotAValidMatch() {
        val m = WebDebugMatching.classify(listOf(mark(1000)), listOf(out(1200, "bedroom", type = "EXIT")), 2300, 0)
        assertEquals("WRONG_DIRECTION", m.single().classification)
    }

    @Test fun noOutputIsPendingBeforeWindowExpires() {
        val m = WebDebugMatching.classify(listOf(mark(1000)), emptyList(), 1800, 1000)
        assertEquals("PENDING", m.single().classification)
        assertNull(m.single().runtimeKey)
    }

    @Test fun noOutputIsMissAfterWindowExpires() {
        val m = WebDebugMatching.classify(listOf(mark(1000)), emptyList(), 2100, 800)
        assertEquals("MISS", m.single().classification)
    }

    @Test fun marksBeforeAnalyzedVideoAreNotCalledMisses() {
        val m = WebDebugMatching.classify(listOf(mark(1000)), emptyList(), 9000, 4000)
        assertEquals("UNOBSERVED", m.single().classification)
    }

    @Test fun twoMarkedEventsCannotConsumeSameRuntimeOutput() {
        val m = WebDebugMatching.classify(
            listOf(mark(1000, key = "m1"), mark(1200, key = "m2")),
            listOf(out(1100, "bedroom")), 2500, 0
        )
        assertEquals("MATCH", m[0].classification)
        assertEquals("MISS", m[1].classification)
    }

    @Test fun unboundMarkDoesNotInventPortalTruth() {
        val m = WebDebugMatching.classify(
            listOf(mark(1000, roomId = null)),
            listOf(out(1100, "any_room")), 2500, 0
        )
        assertEquals("MATCH", m.single().classification)
    }

    @Test fun postWindowEventMustNotMatch() {
        val m = WebDebugMatching.classify(
            listOf(mark(1000)),
            listOf(out(2051, "bedroom")), 2200, 0
        )
        assertEquals("MISS", m.single().classification)
    }
}
