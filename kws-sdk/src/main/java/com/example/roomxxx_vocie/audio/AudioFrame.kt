package com.example.roomxxx_vocie.audio

data class AudioFrame(
    val pcm: ShortArray,
    val sampleRate: Int,
    val timestampMs: Long,
    val captureMs: Long
)
