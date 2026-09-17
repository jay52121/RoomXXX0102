package com.example.roomxxx0102.logic.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkedPortalInferenceRuntimeTest {

    @Test
    fun coverageUsesPersonAreaForPersonCoverageAndPortalAreaForPortalCoverage() {
        val coverage = MarkedPortalTruthInference.coverageFromAreas(
            intersectionArea = 0.10,
            personArea = 0.40,
            portalArea = 0.10,
        )

        assertEquals(0.25, coverage.person, 1e-9)
        assertEquals(1.0, coverage.portal, 1e-9)
    }

    @Test
    fun tinyPortalFillCannotDominateBodyEvidence() {
        val tinyDoorButWeakBody = MarkedPortalTruthInference.score(
            personCoverage = 0.10,
            portalCoverage = 1.00,
            keypointCoverage = 0.10,
            hasReliableKeypoints = true,
        )
        val bodyAbsorbed = MarkedPortalTruthInference.score(
            personCoverage = 0.55,
            portalCoverage = 0.45,
            keypointCoverage = 0.70,
            hasReliableKeypoints = true,
        )

        assertEquals(0.19, tinyDoorButWeakBody, 1e-9)
        assertTrue(bodyAbsorbed > tinyDoorButWeakBody)
    }

    @Test
    fun missingKeypointsFallsBackToPersonCoverageInsteadOfZeroPenalty() {
        val score = MarkedPortalTruthInference.score(
            personCoverage = 0.50,
            portalCoverage = 0.50,
            keypointCoverage = 0.0,
            hasReliableKeypoints = false,
        )

        assertEquals(0.50, score, 1e-9)
    }

    @Test
    fun visualEvidenceMayArriveWellAfterManualMarker() {
        val events = listOf(MarkedEvent(EventType.ENTER, frameIndex = 100, timestampMs = 10_000L))
        val window = MarkedPortalInferenceRuntime.windowFor(events, 0)

        assertEquals(9_600L, window.startMs)
        assertEquals(11_400L, window.endMs)
        assertTrue(10_900L in window.startMs..window.endMs)
    }

    @Test
    fun adjacentBathroomExitAndBedroomEnterAreSplitAtMidpoint() {
        val events = listOf(
            MarkedEvent(EventType.EXIT, frameIndex = 100, timestampMs = 10_000L),
            MarkedEvent(EventType.ENTER, frameIndex = 118, timestampMs = 10_600L),
            MarkedEvent(EventType.EXIT, frameIndex = 220, timestampMs = 14_000L),
        )

        val bathroomExit = MarkedPortalInferenceRuntime.windowFor(events, 0)
        val bedroomEnter = MarkedPortalInferenceRuntime.windowFor(events, 1)

        assertEquals(10_300L, bathroomExit.endMs)
        assertEquals(10_301L, bedroomEnter.startMs)
        assertEquals(12_000L, bedroomEnter.endMs)
        assertTrue(bathroomExit.endMs < bedroomEnter.startMs)
        assertEquals(600L, bedroomEnter.previousGapMs)
        assertEquals(3_400L, bedroomEnter.nextGapMs)
    }
}
