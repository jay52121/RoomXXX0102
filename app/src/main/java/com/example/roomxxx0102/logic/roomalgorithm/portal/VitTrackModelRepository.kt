package com.example.roomxxx0102.logic.roomalgorithm.portal

import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ViTTrack 官方模型的进程级内存仓库。
 *
 * 模型仅约 715KB。第一次选择/运行 ViTTrack 时异步下载，下载完成后同一 App 进程内复用，
 * 不阻塞房间算法线程，也不在下载完成前偷偷回退成 MIL，保证实验结果可解释。
 */
object VitTrackModelRepository {
    private const val TAG = "PortalVisualVitModel"
    private const val MODEL_URL =
        "https://huggingface.co/opencv/object_tracking_vittrack/resolve/main/object_tracking_vittrack_2023sep.onnx"
    private const val EXPECTED_SIZE = 714_726
    private const val EXPECTED_SHA256 =
        "2990f0b7cd44d92afa48cd97db6de7be113fc1d9594fddb74e2725c10478e91d"
    private const val RETRY_COOLDOWN_MS = 15_000L

    private enum class State { IDLE, DOWNLOADING, READY, FAILED }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var modelBytes: ByteArray? = null

    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var lastAttemptElapsedMs: Long = 0L

    fun requestIfNeeded() {
        synchronized(this) {
            if (state == State.READY || state == State.DOWNLOADING) return
            val now = SystemClock.elapsedRealtime()
            if (state == State.FAILED && now - lastAttemptElapsedMs < RETRY_COOLDOWN_MS) return
            state = State.DOWNLOADING
            lastAttemptElapsedMs = now
            lastFailure = null
        }

        val request = Request.Builder().url(MODEL_URL).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail("download_failed:${e.message ?: e.javaClass.simpleName}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        fail("http_${it.code}")
                        return
                    }
                    val bytes = it.body?.bytes()
                    if (bytes == null) {
                        fail("empty_body")
                        return
                    }
                    if (bytes.size != EXPECTED_SIZE) {
                        fail("size_mismatch:${bytes.size}")
                        return
                    }
                    val sha256 = sha256(bytes)
                    if (!sha256.equals(EXPECTED_SHA256, ignoreCase = true)) {
                        fail("sha256_mismatch:$sha256")
                        return
                    }
                    synchronized(this@VitTrackModelRepository) {
                        modelBytes = bytes
                        state = State.READY
                        lastFailure = null
                    }
                    Log.i(TAG, "ViTTrack model ready bytes=${bytes.size} sha256=$sha256")
                }
            }
        })
    }

    fun readyBytes(): ByteArray? {
        val bytes = modelBytes
        if (bytes == null) requestIfNeeded()
        return bytes
    }

    fun statusLabel(): String {
        return when (state) {
            State.IDLE -> "idle"
            State.DOWNLOADING -> "downloading"
            State.READY -> "ready"
            State.FAILED -> "failed:${lastFailure ?: "unknown"}"
        }
    }

    private fun fail(reason: String) {
        synchronized(this) {
            modelBytes = null
            state = State.FAILED
            lastFailure = reason
        }
        Log.w(TAG, "ViTTrack model unavailable reason=$reason")
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xff)
        }
    }
}
