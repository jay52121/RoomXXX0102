package com.example.roomxxx_vocie.audio

import kotlin.math.abs

class SilenceDetector(
    private val threshold: Double = DEFAULT_THRESHOLD
) {
    fun isSilent(frame: ShortArray): Boolean {
        return measure(frame) < threshold
    }

    fun measure(frame: ShortArray): Double {
        if (frame.isEmpty()) {
            return 0.0
        }
        var sum = 0.0
        for (sample in frame) {
            sum += abs(sample.toDouble() / 32768.0)
        }
        return sum / frame.size
    }

    private companion object {
        private const val DEFAULT_THRESHOLD = 0.02
    }
}
