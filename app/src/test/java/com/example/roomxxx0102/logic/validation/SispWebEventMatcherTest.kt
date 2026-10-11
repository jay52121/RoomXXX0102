package com.example.roomxxx0102.logic.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class SispWebEventMatcherTest {
    private fun mark(type: EventType, ms: Long, room: String? = null) =
        MarkedEvent(type, (ms / 33).toInt(), ms, room)
    private fun output(type: EventType, ms: Long, room: String) =
        WebRuntimeEvent(
            type = type.name,
            timeMs = ms,
            fromRoomId = if (type == EventType.ENTER) "living" else room,
            toRoomId = if (type == EventType.ENTER) room else "living",
            doorId = "$room#1",
            trackId = 4,
            reason = "test"
        )

    @Test fun beforeWindowEndsStatusIsPendingNotMiss() {
        val result = SispWebEventMatcher.match(
            listOf(mark(EventType.ENTER, 2_000L, "bedroom")),
            emptyList(),
            observedUntilMs = 2_800L
        )
        assertEquals("PENDING", result.single().status)
    }

    @Test fun genuineMissOnlyAfterFullWindow() {
        val result = SispWebEventMatcher.match(
            listOf(mark(EventType.ENTER, 2_000L, "bedroom")),
            emptyList(),
            observedUntilMs = 3_100L
        )
        assertEquals("MISS", result.single().status)
    }

    @Test fun startupMidVideoCannotFabricateMiss() {
        val result = SispWebEventMatcher.match(
            listOf(mark(EventType.ENTER, 2_000L, "bedroom")),
            emptyList(),
            observedUntilMs = 5_000L,
            observedFromMs = 4_500L
        )
        assertEquals("UNOBSERVED", result.single().status)
    }

    @Test fun wrongPortalAndDirectionAreVisible() {
        val result = SispWebEventMatcher.match(
            listOf(mark(EventType.ENTER, 3_000L, "study"), mark(EventType.EXIT, 5_000L, "bathroom")),
            listOf(output(EventType.ENTER, 3_120L, "bedroom"), output(EventType.ENTER, 5_300L, "bathroom")),
            observedUntilMs = 7_000L
        )
        assertEquals("WRONG_PORTAL", result[0].status)
        assertEquals("WRONG_DIRECTION", result[1].status)
        assertEquals(120L, result[0].deltaMs)
    }

    @Test fun exactDoorBeatsCloserWrongDoor() {
        val result = SispWebEventMatcher.match(
            listOf(mark(EventType.ENTER, 3_000L, "study")),
            listOf(output(EventType.ENTER, 3_010L, "bedroom"), output(EventType.ENTER, 3_240L, "study")),
            observedUntilMs = 4_200L
        )
        assertEquals("MATCH", result.single().status)
        assertEquals(1, result.single().runtimeIndex)
    }

    @Test fun matchingAnOutputCannotReuseItForAnotherMark() {
        val result = SispWebEventMatcher.match(
            listOf(mark(EventType.ENTER, 3_000L, "study"), mark(EventType.ENTER, 3_250L, "study")),
            listOf(output(EventType.ENTER, 3_010L, "study")),
            observedUntilMs = 4_500L
        )
        assertEquals("MATCH", result[0].status)
        assertEquals("MISS", result[1].status)
    }
}
