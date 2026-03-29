package com.example.roomxxx0102.logic.video

import android.net.Uri
import android.view.TextureView

object VideoSeekMode {
    const val CLOSEST = 0
    const val PREVIOUS_SYNC = -1
    const val NEXT_SYNC = 1
}

interface VideoPlayerFacade {
    fun attachTextureView(textureView: TextureView)

    fun setListener(listener: PlayerEventListener?)

    fun prepare(filePath: String? = null, uri: Uri? = null, looping: Boolean = true)

    fun play()

    fun pause()

    fun stop()

    fun release()

    fun seekTo(positionMs: Long, seekMode: Int = VideoSeekMode.CLOSEST)

    fun isPlaying(): Boolean

    fun getCurrentPositionMs(): Int?

    fun getDurationMs(): Int?
}
