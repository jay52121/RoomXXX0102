package com.example.roomxxx_vocie.kws

import android.content.Context
import com.example.roomxxx_vocie.KwsConfig
import com.example.roomxxx_vocie.audio.AudioFrame

interface KwsEngine {
    /**
     * init() must be callable repeatedly; each call should rebuild and release old resources.
     */
    fun init(context: Context, config: KwsConfig)

    fun start()

    fun stop()

    /**
     * Reset decoding context without reloading model files.
     * If not supported by backend, re-create engine or stream internally.
     */
    fun resetStream()

    /**
     * Accept PCM frames. frame.sampleRate must equal config.sampleRate.
     */
    fun accept(frame: AudioFrame)

    /**
     * Poll for hit results. Returns null if no keyword hit.
     */
    fun poll(): KwsHit?

    fun state(): KwsEngineState
}
