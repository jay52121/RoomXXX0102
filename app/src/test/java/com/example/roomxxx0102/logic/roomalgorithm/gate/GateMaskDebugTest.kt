package com.example.roomxxx0102.logic.roomalgorithm.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateMaskDebugTest {
    @Test fun horizontalRunsPreserveZeroPixelHoles() {
        val on = 255.toByte()
        val bytes = byteArrayOf(
            0, on, on, 0, on, 0,
            0, 0, 0, 0, 0, 0,
        )
        val encoded = encodeGateMaskRuns(bytes, 6, 2, 10, 20, 100, 100)
        assertEquals(3, encoded.pixels)
        assertEquals(2, encoded.runs.size)
        assertEquals(0.11, encoded.runs[0].left, 1e-9)
        assertEquals(0.13, encoded.runs[0].right, 1e-9)
        assertEquals(0.14, encoded.runs[1].left, 1e-9)
        assertEquals(0.15, encoded.runs[1].right, 1e-9)
        assertEquals(0.20, encoded.runs[0].top, 1e-9)
        assertEquals(0.21, encoded.runs[0].bottom, 1e-9)
    }

    @Test fun emptyMaskProducesNoPaintRuns() {
        val encoded = encodeGateMaskRuns(ByteArray(12), 4, 3, 0, 0, 100, 100)
        assertEquals(0, encoded.pixels)
        assertTrue(encoded.runs.isEmpty())
    }
}
