package com.example.roomxxx_vocie.kws

import android.content.Context
import android.util.Log
import com.example.roomxxx_vocie.KwsConfig
import com.example.roomxxx_vocie.audio.AudioFrame
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlin.math.min

class SherpaKwsEngine : KwsEngine {
    private var currentConfig: KwsConfig? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var currentState: KwsEngineState = KwsEngineState.IDLE

    override fun init(context: Context, config: KwsConfig) {
        releaseAll()
        currentConfig = config

        try {
            val modelDir = AssetFileCopier.copyAssetDirToFiles(context, config.modelAssetDir)
            val keywordsFile = AssetFileCopier.copyAssetFileToFiles(
                context,
                config.keywordsAssetPath,
                forceOverwrite = true
            )

            val modelConfig = buildModelConfig(modelDir)
            val featConfig = FeatureConfig(config.sampleRate, 80, 0.0f)
            val kwsConfig = KeywordSpotterConfig(
                featConfig,
                modelConfig,
                config.maxActivePaths,
                keywordsFile.absolutePath,
                config.keywordsScore,
                config.triggerThreshold,
                config.numTrailingBlanks
            )

            spotter = KeywordSpotter(null, kwsConfig)
            currentState = KwsEngineState.READY

            Log.i(
                TAG,
                "KWS init: modelDir=${modelDir.absolutePath}, " +
                    "keywords=${keywordsFile.absolutePath}, " +
                    "sampleRate=${config.sampleRate}, frameSize=${config.frameSizeInSamples}"
            )
        } catch (e: Exception) {
            currentState = KwsEngineState.ERROR
            Log.e(TAG, "KWS init failed", e)
        }
    }

    override fun start() {
        if (currentState == KwsEngineState.ERROR) {
            return
        }
        if (stream == null) {
            stream = spotter?.createStream()
        }
        currentState = if (stream != null) KwsEngineState.RUNNING else KwsEngineState.ERROR
    }

    override fun stop() {
        stream?.release()
        stream = null
        if (currentState != KwsEngineState.ERROR) {
            currentState = KwsEngineState.READY
        }
    }

    override fun resetStream() {
        if (spotter == null) {
            return
        }
        stream?.release()
        stream = spotter?.createStream()
    }

    override fun accept(frame: AudioFrame) {
        val config = currentConfig ?: return
        require(frame.sampleRate == config.sampleRate) {
            "Expected sampleRate=${config.sampleRate}, but got ${frame.sampleRate}"
        }

        val activeStream = stream ?: return
        val floats = shortArrayToFloatArray(frame.pcm)
        activeStream.acceptWaveform(floats, frame.sampleRate)
    }

    override fun poll(): KwsHit? {
        val activeSpotter = spotter ?: return null
        val activeStream = stream ?: return null

        if (activeSpotter.isReady(activeStream)) {
            activeSpotter.decode(activeStream)
        }

        val result = activeSpotter.getResult(activeStream)
        val keyword = result.keyword
        if (keyword.isNotBlank()) {
            Log.i(TAG, "KWS hit: keywordRaw=$keyword, score=null")
            return KwsHit(keyword, System.currentTimeMillis(), null)
        }
        return null
    }

    override fun state(): KwsEngineState = currentState

    private fun releaseAll() {
        stream?.release()
        stream = null
        spotter?.release()
        spotter = null
        currentState = KwsEngineState.IDLE
    }

    private fun buildModelConfig(modelDir: File): OnlineModelConfig {
        val encoder = File(modelDir, ENCODER_FILE).absolutePath
        val decoder = File(modelDir, DECODER_FILE).absolutePath
        val joiner = File(modelDir, JOINER_FILE).absolutePath
        val tokens = File(modelDir, TOKENS_FILE).absolutePath

        val transducer = OnlineTransducerModelConfig(encoder, decoder, joiner)
        val modelConfig = OnlineModelConfig()
        modelConfig.transducer = transducer
        modelConfig.tokens = tokens
        modelConfig.modelType = MODEL_TYPE
        modelConfig.provider = PROVIDER_CPU
        modelConfig.numThreads = DEFAULT_NUM_THREADS
        modelConfig.debug = false
        return modelConfig
    }

    private fun shortArrayToFloatArray(pcm: ShortArray): FloatArray {
        val floats = FloatArray(pcm.size)
        for (i in pcm.indices) {
            floats[i] = min(1.0f, pcm[i] / 32768.0f)
        }
        return floats
    }

    private companion object {
        private const val TAG = "SherpaKwsEngine"
        private const val MODEL_TYPE = "zipformer2"
        private const val PROVIDER_CPU = "cpu"
        private const val DEFAULT_NUM_THREADS = 1
        private const val KEYWORDS_SCORE_DEFAULT = 1.0f
        private const val ENCODER_FILE = "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx"
        private const val DECODER_FILE = "decoder-epoch-13-avg-2-chunk-8-left-64.onnx"
        private const val JOINER_FILE = "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
    }
}
