package com.example.roomxxx0102.logic.validation

import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDiagnosticMatcherTest {
    private fun gt(type: EventType, t: Long) = MarkedEvent(type, (t / 50).toInt(), t)
    private fun out(direction: String, t: Long) = GateDiagnosticEvent(
        person = 1,
        track = 7,
        from = if (direction == "ENTER") "living" else "room",
        to = if (direction == "ENTER") "room" else "living",
        gateId = "room#1",
        direction = direction,
        timeMs = t,
        inferred = false,
        reason = "TEST",
    )

    @Test fun oneToOneMatchAndDuplicateAreSeparated() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.ENTER, 1000)),
            runtime = listOf(out("ENTER", 1080), out("ENTER", 1200)),
            windowMs = 1000,
        )
        assertEquals("MATCH", result.marked.single().classification)
        assertEquals(0, result.marked.single().runtimeIndex)
        assertTrue(1 in result.duplicateRuntimeIndices)
        assertTrue(result.falsePositiveRuntimeIndices.isEmpty())
    }

    @Test fun oppositeDirectionIsObjectiveWrongDirection() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.ENTER, 2000)),
            runtime = listOf(out("EXIT", 2050)),
            windowMs = 1000,
        )
        assertEquals("WRONG_DIRECTION", result.marked.single().classification)
        assertTrue(result.falsePositiveRuntimeIndices.isEmpty())
    }

    @Test fun isolatedRuntimeOutputIsFalsePositive() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.ENTER, 1000)),
            runtime = listOf(out("ENTER", 5000)),
            windowMs = 1000,
        )
        assertEquals("MISS", result.marked.single().classification)
        assertTrue(0 in result.falsePositiveRuntimeIndices)
    }
}
