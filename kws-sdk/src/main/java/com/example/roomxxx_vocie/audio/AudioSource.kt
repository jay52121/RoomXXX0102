package com.example.roomxxx_vocie.audio

import com.example.roomxxx_vocie.KwsConfig

interface AudioSource {
    /**
     * Start reading PCM frames. If called repeatedly, it should stop first and restart.
     */
    fun start(config: KwsConfig, onFrame: (AudioFrame) -> Unit)

    /** Stop reading. This must be idempotent. */
    fun stop()

    fun state(): AudioSourceState
}
