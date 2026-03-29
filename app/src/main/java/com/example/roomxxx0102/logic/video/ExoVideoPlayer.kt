package com.example.roomxxx0102.logic.video

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.io.File

class ExoVideoPlayer(
    context: Context
) : VideoPlayerFacade {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private var listener: PlayerEventListener? = null
    private var readyDispatched = false
    private var pendingSeekCallback = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && !readyDispatched) {
                readyDispatched = true
                listener?.onReady()
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                listener?.onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && pendingSeekCallback) {
                pendingSeekCallback = false
                listener?.onSeekComplete()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("ExoVideoPlayer", "player error", error)
            listener?.onError(error)
        }
    }

    init {
        player.addListener(playerListener)
    }

    override fun attachTextureView(textureView: TextureView) {
        player.setVideoTextureView(textureView)
    }

    override fun setListener(listener: PlayerEventListener?) {
        this.listener = listener
    }

    override fun prepare(filePath: String?, uri: Uri?, looping: Boolean) {
        val mediaItem = when {
            !filePath.isNullOrBlank() -> MediaItem.fromUri(Uri.fromFile(File(filePath)))
            uri != null -> MediaItem.fromUri(uri)
            else -> throw IllegalArgumentException("video source is required")
        }
        readyDispatched = false
        pendingSeekCallback = false
        player.setMediaItem(mediaItem)
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.prepare()
        player.playWhenReady = true
    }

    override fun play() {
        player.playWhenReady = true
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        readyDispatched = false
        pendingSeekCallback = false
        player.stop()
        player.clearMediaItems()
    }

    override fun release() {
        readyDispatched = false
        pendingSeekCallback = false
        player.removeListener(playerListener)
        player.release()
    }

    override fun seekTo(positionMs: Long, seekMode: Int) {
        player.setSeekParameters(when (seekMode) {
            VideoSeekMode.PREVIOUS_SYNC -> SeekParameters.PREVIOUS_SYNC
            VideoSeekMode.NEXT_SYNC -> SeekParameters.NEXT_SYNC
            else -> SeekParameters.EXACT
        })
        pendingSeekCallback = true
        player.seekTo(positionMs)
    }

    override fun isPlaying(): Boolean = player.isPlaying

    override fun getCurrentPositionMs(): Int? {
        return if (player.playbackState == Player.STATE_IDLE) null else player.currentPosition.toInt()
    }

    override fun getDurationMs(): Int? {
        val durationMs = player.duration
        return if (durationMs == C.TIME_UNSET) null else durationMs.toInt()
    }
}
