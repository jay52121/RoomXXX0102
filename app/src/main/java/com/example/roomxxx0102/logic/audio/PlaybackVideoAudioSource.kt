package com.example.roomxxx0102.logic.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.example.roomxxx0102.utils.AppLog
import com.example.roomxxx_vocie.KwsConfig
import com.example.roomxxx_vocie.audio.AudioFrame
import com.example.roomxxx_vocie.audio.AudioSource
import com.example.roomxxx_vocie.audio.AudioSourceState
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackVideoAudioSource(
    private val context: Context,
    private val sourceProvider: () -> SourceSpec?,
    private val playbackPositionProvider: () -> Long?,
    private val playbackActiveProvider: () -> Boolean
) : AudioSource {
    data class SourceSpec(
        val filePath: String? = null,
        val uri: Uri? = null
    )

    @Volatile
    private var currentState: AudioSourceState = AudioSourceState.IDLE

    private var decodeScope: CoroutineScope? = null
    private var decodeJob: Job? = null

    override fun start(config: KwsConfig, onFrame: (AudioFrame) -> Unit) {
        stop()
        val spec = sourceProvider()
        if (spec == null) {
            currentState = AudioSourceState.ERROR
            AppLog.w(TAG, "start skipped: no video source available")
            return
        }
        decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        decodeJob = decodeScope?.launch {
            decodeLoop(spec, config, onFrame)
        }
        currentState = AudioSourceState.RUNNING
    }

    override fun stop() {
        decodeJob?.cancel()
        decodeJob = null
        decodeScope?.cancel()
        decodeScope = null
        currentState = AudioSourceState.IDLE
    }

    override fun state(): AudioSourceState = currentState

    private suspend fun decodeLoop(
        spec: SourceSpec,
        config: KwsConfig,
        onFrame: (AudioFrame) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            when {
                spec.filePath != null -> extractor.setDataSource(spec.filePath)
                spec.uri != null -> extractor.setDataSource(context, spec.uri, null)
                else -> {
                    currentState = AudioSourceState.ERROR
                    return
                }
            }

            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                currentState = AudioSourceState.ERROR
                AppLog.w(TAG, "no audio track found for playback source")
                return
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                currentState = AudioSourceState.ERROR
                return
            }
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val accumulator = ShortAccumulator(config.frameSizeInSamples)
            var inputDone = false
            var outputDone = false
            var lastSyncCheckMs = 0L
            var lastPlayerPosMs = playbackPositionProvider() ?: 0L
            seekExtractorToPlayback(extractor, decoder)

            while (decodeScope?.isActive == true) {
                if (!playbackActiveProvider()) {
                    delay(30)
                    continue
                }

                val playerPosMs = playbackPositionProvider() ?: 0L
                if (playerPosMs + LOOP_RESET_THRESHOLD_MS < lastPlayerPosMs) {
                    resetDecoderToPlayback(extractor, decoder, accumulator)
                    inputDone = false
                    outputDone = false
                }
                lastPlayerPosMs = playerPosMs
                if (SystemClock.elapsedRealtime() - lastSyncCheckMs >= 300L) {
                    lastSyncCheckMs = SystemClock.elapsedRealtime()
                    val extractorPtsMs = extractor.sampleTime.takeIf { it >= 0L }?.div(1000L)
                    if (extractorPtsMs != null && abs(extractorPtsMs - playerPosMs) > 1500L) {
                        resetDecoderToPlayback(extractor, decoder, accumulator)
                        inputDone = false
                        outputDone = false
                    }
                }

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer ?: ByteBuffer.allocate(0), 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                ptsUs,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (bufferInfo.size > 0 && outputBuffer != null) {
                            val pcmBytes = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(pcmBytes)
                            val samples = pcm16BytesToShorts(pcmBytes)
                            val mono = downmixToMono(samples, sourceChannels)
                            val resampled = resampleLinear(mono, sourceSampleRate, config.sampleRate)
                            val framePtsMs = bufferInfo.presentationTimeUs / 1000L
                            emitFrames(
                                accumulator = accumulator,
                                pcm = resampled,
                                framePtsMs = framePtsMs,
                                config = config,
                                onFrame = onFrame
                            )
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // ignore
                    }
                }

                if (outputDone) {
                    val latestPlayerPosMs = playbackPositionProvider() ?: 0L
                    if (latestPlayerPosMs <= LOOP_RESTART_AT_MOST_MS ||
                        latestPlayerPosMs + LOOP_RESET_THRESHOLD_MS < lastPlayerPosMs
                    ) {
                        resetDecoderToPlayback(extractor, decoder, accumulator)
                        inputDone = false
                        outputDone = false
                        lastPlayerPosMs = latestPlayerPosMs
                    } else {
                        delay(30)
                    }
                }
            }
        } catch (t: Throwable) {
            currentState = AudioSourceState.ERROR
            AppLog.e(TAG, "decodeLoop failed", t)
        } finally {
            try {
                decoder?.stop()
            } catch (_: Throwable) {
            }
            try {
                decoder?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
            if (currentState != AudioSourceState.ERROR) {
                currentState = AudioSourceState.IDLE
            }
        }
    }

    private suspend fun emitFrames(
        accumulator: ShortAccumulator,
        pcm: ShortArray,
        framePtsMs: Long,
        config: KwsConfig,
        onFrame: (AudioFrame) -> Unit
    ) {
        accumulator.append(pcm)
        var emittedSamples = 0
        while (accumulator.available() >= config.frameSizeInSamples) {
            val chunkPtsMs =
                framePtsMs + (emittedSamples.toLong() * 1000L / max(1, config.sampleRate))
            val playerPosMs = playbackPositionProvider() ?: chunkPtsMs

            if (chunkPtsMs < playerPosMs - 500L) {
                accumulator.take(config.frameSizeInSamples)
                emittedSamples += config.frameSizeInSamples
                continue
            }
            while (decodeScope?.isActive == true && chunkPtsMs > (playbackPositionProvider() ?: chunkPtsMs) + 120L) {
                delay(10)
            }

            val chunk = accumulator.take(config.frameSizeInSamples)
            emittedSamples += chunk.size
            onFrame(
                AudioFrame(
                    pcm = chunk,
                    sampleRate = config.sampleRate,
                    timestampMs = System.currentTimeMillis(),
                    captureMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
                )
            )
        }
    }

    private fun resetDecoderToPlayback(
        extractor: MediaExtractor,
        decoder: MediaCodec?,
        accumulator: ShortAccumulator
    ) {
        accumulator.clear()
        seekExtractorToPlayback(extractor, decoder)
    }

    private fun seekExtractorToPlayback(extractor: MediaExtractor, decoder: MediaCodec?) {
        val positionMs = playbackPositionProvider() ?: 0L
        extractor.seekTo(positionMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        try {
            decoder?.flush()
        } catch (_: Throwable) {
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return index
            }
        }
        return -1
    }

    private fun pcm16BytesToShorts(data: ByteArray): ShortArray {
        val count = data.size / 2
        val output = ShortArray(count)
        var byteIndex = 0
        for (sampleIndex in 0 until count) {
            val lo = data[byteIndex].toInt() and 0xFF
            val hi = data[byteIndex + 1].toInt()
            output[sampleIndex] = ((hi shl 8) or lo).toShort()
            byteIndex += 2
        }
        return output
    }

    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        val frames = samples.size / channels
        val mono = ShortArray(frames)
        var sampleIndex = 0
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                sum += samples[sampleIndex++].toInt()
            }
            mono[frame] = (sum / channels).toShort()
        }
        return mono
    }

    private fun resampleLinear(
        input: ShortArray,
        inputRate: Int,
        outputRate: Int
    ): ShortArray {
        if (inputRate <= 0 || outputRate <= 0 || input.isEmpty() || inputRate == outputRate) {
            return input
        }
        val outputSize = max(1, (input.size.toLong() * outputRate / inputRate).toInt())
        val output = ShortArray(outputSize)
        val ratio = inputRate.toDouble() / outputRate.toDouble()
        for (index in 0 until outputSize) {
            val sourcePosition = index * ratio
            val left = sourcePosition.toInt().coerceIn(0, input.lastIndex)
            val right = min(left + 1, input.lastIndex)
            val weight = sourcePosition - left
            val sample =
                (input[left] * (1.0 - weight) + input[right] * weight).toInt()
            output[index] = sample.toShort()
        }
        return output
    }

    private class ShortAccumulator(private val chunkSize: Int) {
        private val buffer = ArrayList<Short>()

        fun append(samples: ShortArray) {
            for (sample in samples) {
                buffer.add(sample)
            }
        }

        fun available(): Int = buffer.size

        fun clear() {
            buffer.clear()
        }

        fun take(count: Int = chunkSize): ShortArray {
            val actual = min(count, buffer.size)
            val output = ShortArray(actual)
            for (index in 0 until actual) {
                output[index] = buffer[index]
            }
            repeat(actual) { buffer.removeAt(0) }
            return output
        }
    }

    private companion object {
        private const val TAG = "PlaybackVideoAudioSource"
        private const val LOOP_RESET_THRESHOLD_MS = 1000L
        private const val LOOP_RESTART_AT_MOST_MS = 400L
    }
}
