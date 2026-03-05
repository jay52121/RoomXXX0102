package com.example.roomxxx0102.logic.tracker

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DetectionIn(
    val xyxy: FloatArray,
    val conf: Float,
    val cls: Int? = 0
)

data class TrackRequest(
    val cameraId: String,
    val frameId: Long,
    val detections: List<DetectionIn>,
    val fps: Float? = null
)

data class TrackOut(
    val trackId: Int,
    val xyxy: FloatArray,
    val conf: Float,
    val cls: Int? = 0
)

data class TrackResponse(
    val cameraId: String,
    val frameId: Long,
    val serverMs: Double,
    val tracks: List<TrackOut>
)

class TrackClient(
    private val baseUrl: String,
    private val cameraId: String,
    timeoutMs: Long = 200L
) {
    companion object {
        private const val TAG = "ByteTrack"
    }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    fun enqueueTrack(
        frameId: Long,
        detections: List<TrackDetection>,
        frameWidth: Int,
        frameHeight: Int,
        callback: (TrackResponse?) -> Unit
    ) {
        Log.d(TAG, "track send: frame=$frameId det=${detections.size}")
        val request = TrackRequest(
            cameraId = cameraId,
            frameId = frameId,
            detections = detections.map {
                DetectionIn(
                    xyxy = floatArrayOf(it.box.left, it.box.top, it.box.right, it.box.bottom),
                    conf = it.score,
                    cls = 0
                )
            }
        )
        val body = buildRequestJson(request, frameWidth, frameHeight)
        val httpRequest = Request.Builder()
            .url("$baseUrl/track")
            .post(body.toRequestBody(mediaType))
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "track fail: frame=$frameId err=${e.message}")
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.w(TAG, "track http fail: frame=$frameId code=${response.code}")
                        callback(null)
                        return
                    }
                    val payload = response.body?.string().orEmpty()
                    val parsed = parseResponse(payload)
                    val count = parsed?.tracks?.size ?: 0
                    Log.d(TAG, "track ok: frame=$frameId tracks=$count serverMs=${parsed?.serverMs}")
                    callback(parsed)
                }
            }
        })
    }

    fun enqueueHealth(callback: (Boolean) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                callback(response.isSuccessful)
            }
        })
    }

    fun enqueueReset(callback: (Boolean) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/reset")
            .post("".toRequestBody(mediaType))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                callback(response.isSuccessful)
            }
        })
    }

    private fun buildRequestJson(
        request: TrackRequest,
        frameWidth: Int,
        frameHeight: Int
    ): String {
        val root = JSONObject()
        root.put("camera_id", request.cameraId)
        root.put("frame_id", request.frameId)
        root.put("frame_width", frameWidth)
        root.put("frame_height", frameHeight)
        val detectionsArray = JSONArray()
        for (det in request.detections) {
            val obj = JSONObject()
            obj.put("xyxy", JSONArray(det.xyxy.toList()))
            obj.put("conf", det.conf)
            obj.put("cls", det.cls ?: 0)
            detectionsArray.put(obj)
        }
        root.put("detections", detectionsArray)
        request.fps?.let { root.put("fps", it) }
        return root.toString()
    }

    private fun parseResponse(payload: String): TrackResponse? {
        if (payload.isBlank()) return null
        val root = JSONObject(payload)
        val cameraId = root.optString("camera_id", "")
        val frameId = root.optLong("frame_id", -1L)
        val serverMs = root.optDouble("server_ms", 0.0)
        val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
        val tracks = ArrayList<TrackOut>(tracksArray.length())
        for (i in 0 until tracksArray.length()) {
            val obj = tracksArray.optJSONObject(i) ?: continue
            val trackId = obj.optInt("track_id", -1)
            if (trackId < 0) continue
            val xyxyArr = obj.optJSONArray("xyxy") ?: continue
            if (xyxyArr.length() < 4) continue
            val xyxy = FloatArray(4)
            for (j in 0..3) {
                xyxy[j] = xyxyArr.optDouble(j, 0.0).toFloat()
            }
            val conf = obj.optDouble("conf", 0.0).toFloat()
            val cls = if (obj.has("cls")) obj.optInt("cls") else 0
            tracks.add(TrackOut(trackId, xyxy, conf, cls))
        }
        return TrackResponse(cameraId, frameId, serverMs, tracks)
    }
}
