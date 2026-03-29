package com.example.roomxxx0102.logic.video

interface PlayerEventListener {
    fun onReady()

    fun onVideoSizeChanged(videoWidth: Int, videoHeight: Int)

    fun onSeekComplete()

    fun onError(error: Throwable)
}
