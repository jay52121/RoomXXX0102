package com.example.roomxxx_vocie.audio

import com.example.roomxxx_vocie.KwsConfig

class SwitchableAudioSource(
    private val microphoneSource: AudioSource,
    private val playbackSource: AudioSource,
    initialMode: AudioInputMode = AudioInputMode.PLAYBACK
) : AudioSource {
    private val lock = Any()

    @Volatile
    private var mode: AudioInputMode = initialMode
    private var activeConfig: KwsConfig? = null
    private var activeFrameCallback: ((AudioFrame) -> Unit)? = null

    override fun start(config: KwsConfig, onFrame: (AudioFrame) -> Unit) {
        synchronized(lock) {
            stopCurrentLocked()
            activeConfig = config
            activeFrameCallback = onFrame
            currentSourceLocked().start(config, onFrame)
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopCurrentLocked()
            activeConfig = null
            activeFrameCallback = null
        }
    }

    override fun state(): AudioSourceState {
        synchronized(lock) {
            return currentSourceLocked().state()
        }
    }

    fun getMode(): AudioInputMode = mode

    fun setMode(newMode: AudioInputMode) {
        synchronized(lock) {
            if (mode == newMode) return
            val config = activeConfig
            val callback = activeFrameCallback
            stopCurrentLocked()
            mode = newMode
            if (config != null && callback != null) {
                currentSourceLocked().start(config, callback)
            }
        }
    }

    private fun currentSourceLocked(): AudioSource {
        return when (mode) {
            AudioInputMode.PLAYBACK -> playbackSource
            AudioInputMode.MICROPHONE -> microphoneSource
        }
    }

    private fun stopCurrentLocked() {
        microphoneSource.stop()
        playbackSource.stop()
    }
}
