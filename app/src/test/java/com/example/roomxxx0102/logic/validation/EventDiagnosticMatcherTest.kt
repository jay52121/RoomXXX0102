package com.example.roomxxx0102.logic.validation

import com.example.roomxxx0102.logic.roomalgorithm.gate.GateDiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDiagnosticMatcherTest {
    private fun gt(type: EventType, t: Long, portal: String? = null) =
        MarkedEvent(type, (t / 50).toInt(), t, portalRoomId = portal)

    private fun out(direction: String, t: Long, portal: String = "room") = GateDiagnosticEvent(
        person = 1,
        track = 7,
        from = if (direction == "ENTER") "living" else portal,
        to = if (direction == "ENTER") portal else "living",
        gateId = "$portal#1",
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

    @Test fun manuallyBoundPortalMustMatch() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.ENTER, 3000, portal = "kitchen")),
            runtime = listOf(out("ENTER", 3050, portal = "bedroom")),
            windowMs = 1000,
        )
        assertEquals("WRONG_PORTAL", result.marked.single().classification)
        assertEquals(0, result.marked.single().runtimeIndex)
    }

    @Test fun correctPortalBeatsCloserWrongPortal() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.EXIT, 4000, portal = "bathroom")),
            runtime = listOf(
                out("EXIT", 4010, portal = "bedroom"),
                out("EXIT", 4200, portal = "bathroom"),
            ),
            windowMs = 1000,
        )
        assertEquals("MATCH", result.marked.single().classification)
        assertEquals(1, result.marked.single().runtimeIndex)
        assertTrue(0 in result.duplicateRuntimeIndices)
    }

    @Test fun unboundPortalKeepsLegacyDirectionOnlyMatching() {
        val result = EventDiagnosticMatcher.match(
            marked = listOf(gt(EventType.ENTER, 5000, portal = null)),
            runtime = listOf(out("ENTER", 5050, portal = "any-room")),
            windowMs = 1000,
        )
        assertEquals("MATCH", result.marked.single().classification)
    }
}
