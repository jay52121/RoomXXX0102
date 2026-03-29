package com.example.roomxxx_vocie.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.SystemClock
import com.example.roomxxx_vocie.KwsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.max

class AudioRecordSource(context: Context) : AudioSource {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var currentState: AudioSourceState = AudioSourceState.IDLE
    private var audioRecord: AudioRecord? = null
    private var readScope: CoroutineScope? = null
    private var readJob: Job? = null
    // 调试读取节奏：只打印前 3 秒的 read 统计。
    private var readDebugStartMs: Long = 0L
    private var readDebugEnabled: Boolean = false
    // 调试统计：按 1 秒汇总 readGapMs，避免每次 read 都刷日志。
    private var readDebugStatStartMs: Long = 0L
    private var readDebugStatCount: Long = 0L
    private var readDebugStatSum: Long = 0L
    private var readDebugStatMin: Long = Long.MAX_VALUE
    private var readDebugStatMax: Long = 0L

    override fun start(config: KwsConfig, onFrame: (AudioFrame) -> Unit) {
        stop()

        val preferredDevice = AudioDeviceSelector.selectPreferredInputDevice(audioManager)
        val fallbackRate = AudioDeviceSelector.chooseSampleRate(preferredDevice?.sampleRates)
        val candidateRates = linkedSetOf(16000, fallbackRate).toList()
        val candidateSources = buildAudioSourceCandidates()

        val init = findRecord(candidateSources, candidateRates, config)
        if (init == null) {
            currentState = AudioSourceState.ERROR
            return
        }

        if (preferredDevice != null) {
            init.record.setPreferredDevice(preferredDevice)
        }

        audioRecord = init.record
        currentState = AudioSourceState.RUNNING
        readDebugStartMs = SystemClock.elapsedRealtime()
        readDebugEnabled = true
        readDebugStatStartMs = readDebugStartMs
        readDebugStatCount = 0L
        readDebugStatSum = 0L
        readDebugStatMin = Long.MAX_VALUE
        readDebugStatMax = 0L

        Log.i(
            TAG,
            "AudioRecord started: sampleRate=${init.sampleRate}, " +
                "preferredDevice=${preferredDevice?.type ?: "default"}, " +
                "audioSource=${audioSourceName(init.audioSource)}"
        )

        readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        readJob = readScope?.launch {
            readLoop(init, config, onFrame)
        }
    }

    override fun stop() {
        cleanup(setIdle = true)
    }

    override fun state(): AudioSourceState = currentState

    private fun buildAudioSourceCandidates(): List<Int> {
        val supportsUnprocessed =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                .equals("true", ignoreCase = true)
        return if (supportsUnprocessed) {
            listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }
    }

    private fun findRecord(
        sources: List<Int>,
        sampleRates: List<Int>,
        config: KwsConfig
    ): RecordInit? {
        for (source in sources) {
            for (rate in sampleRates) {
                val init = createRecord(rate, source, config)
                if (init != null) {
                    return init
                }
            }
        }
        return null
    }

    private fun createRecord(
        sampleRate: Int,
        audioSource: Int,
        config: KwsConfig
    ): RecordInit? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            return null
        }

        val desiredBuffer = config.frameSizeInSamples * 2 * 4
        val bufferSize = max(minBuffer, desiredBuffer)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (_: Exception) {
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        return RecordInit(
            record = record,
            bufferSamples = bufferSize / 2,
            sampleRate = sampleRate,
            audioSource = audioSource
        )
    }

    private suspend fun readLoop(
        init: RecordInit,
        config: KwsConfig,
        onFrame: (AudioFrame) -> Unit
    ) {
        val record = init.record
        try {
            record.startRecording()
        } catch (e: Exception) {
            currentState = AudioSourceState.ERROR
            cleanup(setIdle = false)
            return
        }

        val frameSize = config.frameSizeInSamples
        // 读取缓冲固定为一帧大小，确保每次 read() 对应单帧。
        val readBuffer = ShortArray(frameSize)
        var lastReadCaptureMs = 0L

        while (currentCoroutineContext().isActive) {
            val read = record.read(readBuffer, 0, readBuffer.size)
            if (read <= 0) {
                currentState = AudioSourceState.ERROR
                cleanup(setIdle = false)
                return
            }

            val sampleRate = init.sampleRate
            val captureMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
            val prevReadCaptureMs = lastReadCaptureMs
            val readGapMs = if (prevReadCaptureMs > 0L) {
                captureMs - prevReadCaptureMs
            } else {
                0L
            }
            lastReadCaptureMs = captureMs

            if (readDebugEnabled) {
                val now = SystemClock.elapsedRealtime()
                // 每秒汇总一次 readGapMs，便于观察节奏是否稳定。
                if (readGapMs > 0L) {
                    readDebugStatCount += 1
                    readDebugStatSum += readGapMs
                    if (readGapMs < readDebugStatMin) {
                        readDebugStatMin = readGapMs
                    }
                    if (readGapMs > readDebugStatMax) {
                        readDebugStatMax = readGapMs
                    }
                }
                if (now - readDebugStatStartMs >= 1000L) {
                    val hasGap = readDebugStatCount > 0L
                    val avgGap = if (hasGap) {
                        readDebugStatSum / readDebugStatCount
                    } else {
                        0L
                    }
                    val minGap = if (hasGap) readDebugStatMin else 0L
                    val maxGap = if (hasGap) readDebugStatMax else 0L
                    Log.i(
                        TAG,
                        "read stat avgGapMs=$avgGap minGapMs=$minGap " +
                            "maxGapMs=$maxGap sr=$sampleRate " +
                            "frameSize=$frameSize readBuf=${readBuffer.size}"
                    )
                    readDebugStatStartMs = now
                    readDebugStatCount = 0L
                    readDebugStatSum = 0L
                    readDebugStatMin = Long.MAX_VALUE
                    readDebugStatMax = 0L
                }
                if (now - readDebugStartMs >= 3000L) {
                    readDebugEnabled = false
                }
            }
            if (read != frameSize) {
                // 读到的样本不足一帧时跳过，避免伪造音频时序。
                continue
            }
            onFrame(
                AudioFrame(
                    pcm = readBuffer.copyOf(),
                    sampleRate = init.sampleRate,
                    timestampMs = System.currentTimeMillis(),
                    captureMs = captureMs
                )
            )
        }
    }

    private fun cleanup(setIdle: Boolean) {
        readScope?.cancel()
        readScope = null
        readJob?.cancel()
        readJob = null

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                record.stop()
            } catch (_: Exception) {
                // ignore
            }
            record.release()
        }

        if (setIdle) {
            currentState = AudioSourceState.IDLE
        }
    }

    private fun audioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> source.toString()
        }
    }

    private data class RecordInit(
        val record: AudioRecord,
        val bufferSamples: Int,
        val sampleRate: Int,
        val audioSource: Int
    )

    private companion object {
        private const val TAG = "AudioRecordSource"
    }
}
