package com.example.roomxxx0102.logic.webdebug

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 开关式、局域网受限的结构化调试服务器。
 * 不存在图像接口，也不接收/发送 Bitmap、截图或视频字节。
 * 浏览器以 150~250ms HTTP 轮询状态；操作以 JSON POST 调用主线程控制器。
 */
internal class WebDebugHttpServer(
    private val context: Context,
    private val onCommand: (JSONObject) -> JSONObject
) {
    companion object {
        const val PORT = 8765
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 32 * 1024
        private const val MAX_HISTORY_FRAMES = 2500
        private const val MAX_RUNTIME_EVENTS = 1200

        fun localAddress(): String? {
            val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
                .getOrDefault(emptyList())
            return interfaces.sortedBy { iface ->
                when {
                    iface.name.startsWith("wlan") -> 0
                    iface.name.startsWith("eth") || iface.name.startsWith("en") -> 1
                    else -> 2
                }
            }.asSequence()
                .filter { iface -> runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) }
                .flatMap { iface -> Collections.list(iface.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { addr -> addr.isSiteLocalAddress }
                ?.hostAddress
        }
    }

    private val pinValue = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
    val pairingPin: String get() = pinValue
    val localUrl: String get() = "http://${localAddress() ?: "127.0.0.1"}:$PORT/"
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile private var latestUiState = "{}"
    @Volatile private var latestFrame = "null"
    private val lock = Any()
    private val history = LinkedHashMap<Long, String>()
    private val runtimeEvents = LinkedHashMap<String, WebRuntimeEvent>()
    private val runtimePayloads = LinkedHashMap<String, JSONObject>()
    private var observedFromMs: Long = Long.MAX_VALUE
    private var activeVideoKey: String? = null
    private val requests = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "SISP-Web-HTTP").apply { isDaemon = true }
    }
    private val requestCounter = AtomicInteger(0)
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                soTimeout = 1000
            }
            socket = server
            isRunning = true
            acceptThread = Thread({
                while (isRunning) {
                    try {
                        val client = server.accept()
                        requests.execute { handle(client) }
                    } catch (_: SocketTimeoutException) {
                        // 定时检查开关，stop() 不需要强制阻塞。
                    } catch (error: Exception) {
                        if (isRunning) Log.w("SispWebDebug", "accept failed", error)
                    }
                }
            }, "SISP-Web-Accept").apply { isDaemon = true; start() }
            true
        } catch (error: Exception) {
            Log.e("SispWebDebug", "start failed", error)
            isRunning = false
            false
        }
    }

    fun stop() {
        isRunning = false
        runCatching { socket?.close() }
        socket = null
        requests.shutdownNow()
        synchronized(lock) {
            history.clear()
            runtimeEvents.clear()
            runtimePayloads.clear()
            observedFromMs = Long.MAX_VALUE
            latestFrame = "null"
        }
    }

    /** 每个视频切换/回放重启都需要单独的时间轴，不混合不同算法会话。 */
    fun resetVideo(videoKey: String?) {
        synchronized(lock) {
            activeVideoKey = videoKey
            history.clear()
            runtimeEvents.clear()
            runtimePayloads.clear()
            latestFrame = "null"
            observedFromMs = Long.MAX_VALUE
        }
    }

    fun updateUiState(state: JSONObject) {
        latestUiState = state.toString()
    }

    /** 在原有 Pose 算法完成后调用。只序列化标量结构，不触碰视频帧。 */
    fun recordFrame(frame: JSONObject) {
        if (!isRunning) return
        val timeMs = frame.optLong("timeMs", -1)
        if (timeMs < 0) return
        val events = frame.optJSONArray("events") ?: JSONArray()
        synchronized(lock) {
            val serialized = frame.toString()
            latestFrame = serialized
            history[timeMs] = serialized
            while (history.size > MAX_HISTORY_FRAMES) {
                val first = history.keys.firstOrNull() ?: break
                history.remove(first)
            }
            observedFromMs = minOf(observedFromMs, timeMs)
            for (i in 0 until events.length()) {
                val raw = events.optJSONObject(i) ?: continue
                val key = raw.optString("key")
                val type = raw.optString("type")
                val roomId = raw.optString("roomId")
                if (key.isBlank() || type.isBlank()) continue
                runtimeEvents[key] = WebRuntimeEvent(
                    key, raw.optLong("timeMs", timeMs), type, roomId
                )
                runtimePayloads[key] = raw
            }
            while (runtimeEvents.size > MAX_RUNTIME_EVENTS) {
                val first = runtimeEvents.keys.firstOrNull() ?: break
                runtimeEvents.remove(first)
                runtimePayloads.remove(first)
            }
        }
    }

    /** 供主线程「无匹配跳回」判断；与网页显示共用完全相同的判定。 */
    fun isEventMatched(
        key: String,
        marked: List<WebMarkedEvent>,
        nowMs: Long,
        windowMs: Long
    ): Boolean = synchronized(lock) {
        WebDebugMatching.classify(
            marked, runtimeEvents.values.toList(), nowMs,
            observedFromMs.takeIf { it != Long.MAX_VALUE } ?: nowMs,
            windowMs
        ).any { it.key == key && it.classification == "MATCH" }
    }

    private fun snapshot(): String {
        val state = runCatching { JSONObject(latestUiState) }.getOrDefault(JSONObject())
        val markedArray = state.optJSONArray("marked") ?: JSONArray()
        val marked = ArrayList<WebMarkedEvent>(markedArray.length())
        for (i in 0 until markedArray.length()) {
            val item = markedArray.optJSONObject(i) ?: continue
            marked.add(WebMarkedEvent(
                item.optString("key"),
                item.optLong("timeMs"),
                item.optString("type"),
                item.optString("portalRoomId").takeIf { it.isNotBlank() }
            ))
        }
        val nowMs = state.optLong("positionMs")
        val windowMs = state.optLong("matchWindowMs", WebDebugMatching.WINDOW_MS)
        val (frame, runtime, starts, count) = synchronized(lock) {
            Quadruple(latestFrame, runtimePayloads.values.map { it.toString() },
                observedFromMs.takeIf { it != Long.MAX_VALUE } ?: nowMs, history.size)
        }
        val matches = WebDebugMatching.classify(
            marked,
            runtime.mapNotNull { str ->
                runCatching { JSONObject(str) }.getOrNull()?.let {
                    WebRuntimeEvent(it.optString("key"), it.optLong("timeMs"), it.optString("type"), it.optString("roomId"))
                }
            }, nowMs, starts, windowMs
        )
        return JSONObject()
            .put("state", state)
            .put("frame", runCatching { JSONObject(frame) }.getOrNull())
            .put("runtime", JSONArray(runtime.map { JSONObject(it) }))
            .put("matches", JSONArray(matches.map {
                JSONObject()
                    .put("key", it.key)
                    .put("classification", it.classification)
                    .put("runtimeKey", it.runtimeKey ?: JSONObject.NULL)
                    .put("deltaMs", it.deltaMs ?: JSONObject.NULL)
            }))
            .put("observedFromMs", starts)
            .put("historyFrameCount", count)
            .put("sequence", requestCounter.incrementAndGet())
            .toString()
    }

    private fun frameAt(timeMs: Long): String {
        val best = synchronized(lock) {
            history.entries.minByOrNull { abs(it.key - timeMs) }
                ?.takeIf { abs(it.key - timeMs) <= 500L }?.value
        }
        return JSONObject().put("frame", best?.let { JSONObject(it) } ?: JSONObject.NULL).toString()
    }

    private fun handle(client: Socket) {
        client.use { s ->
            if (!s.inetAddress.isSiteLocalAddress && !s.inetAddress.isLoopbackAddress) {
                respond(s, 403, "text/plain; charset=utf-8", "Local network only".toByteArray())
                return
            }
            s.soTimeout = 4500
            try {
                val input = BufferedInputStream(s.getInputStream())
                val header = readHeaders(input) ?: return
                val headerLines = header.split("\r\n")
                val requestLine = headerLines.firstOrNull()?.split(" ") ?: return
                if (requestLine.size < 2) return
                val method = requestLine[0]
                val path = requestLine[1].substringBefore('?')
                val headers = headerLines.drop(1)
                    .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { pos ->
                        line.substring(0, pos).trim().lowercase() to line.substring(pos + 1).trim()
                    }}
                    .toMap()
                val host = headers["host"]?.substringBefore(':') ?: ""
                if (host != "localhost" && host != "127.0.0.1" &&
                    !host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
                    respond(s, 403, "text/plain; charset=utf-8", "Invalid host".toByteArray())
                    return
                }

                if (method == "GET" && path == "/") {
                    val html = context.assets.open("web_debug/index.html").use { it.readBytes() }
                    respond(s, 200, "text/html; charset=utf-8", html)
                    return
                }
                // 数据和操作均需要用户在手机上看到的六位配对码，不接受公网调用。
                val candidate = headers["x-sisp-pin"] ?: ""
                if (!MessageDigest.isEqual(
                        candidate.toByteArray(StandardCharsets.UTF_8),
                        pinValue.toByteArray(StandardCharsets.UTF_8))) {
                    respond(s, 403, "application/json; charset=utf-8",
                        """{"error":"配对码不正确"}""".toByteArray(StandardCharsets.UTF_8))
                    return
                }

                when {
                    method == "GET" && path == "/api/state" ->
                        respond(s, 200, "application/json; charset=utf-8", snapshot().toByteArray(StandardCharsets.UTF_8))
                    method == "GET" && path == "/api/frame" -> {
                        val timeMs = requestLine[1].substringAfter("ms=", "-1")
                            .substringBefore('&').toLongOrNull() ?: -1L
                        respond(s, 200, "application/json; charset=utf-8", frameAt(timeMs).toByteArray(StandardCharsets.UTF_8))
                    }
                    method == "POST" && path == "/api/command" -> {
                        val len = headers["content-length"]?.toIntOrNull() ?: -1
                        if (len < 0 || len > MAX_BODY_BYTES) {
                            respond(s, 413, "application/json; charset=utf-8", """{"error":"请求过大"}""".toByteArray())
                            return
                        }
                        val bytes = ByteArray(len)
                        var pos = 0
                        while (pos < len) {
                            val n = input.read(bytes, pos, len - pos)
                            if (n < 0) return
                            pos += n
                        }
                        val cmd = JSONObject(String(bytes, StandardCharsets.UTF_8))
                        val answer = onCommand(cmd)
                        respond(s, 200, "application/json; charset=utf-8",
                            answer.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                    else -> respond(s, 404, "application/json; charset=utf-8", """{"error":"未知地址"}""".toByteArray())
                }
            } catch (e: Exception) {
                Log.w("SispWebDebug", "request error", e)
                runCatching { respond(s, 400, "application/json; charset=utf-8",
                    """{"error":"无效请求"}""".toByteArray()) }
            }
        }
    }

    private fun readHeaders(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream(1024)
        var tail = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val v = input.read()
            if (v < 0) return null
            bytes.write(v)
            tail = (tail shl 8) or v
            if (tail == 0x0D0A0D0A) return bytes.toString("UTF-8").dropLast(4)
        }
        return null
    }

    private fun respond(s: Socket, status: Int, mime: String, data: ByteArray) {
        val reason = when(status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${data.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'none'\r\n" +
            "Connection: close\r\n\r\n"
        s.getOutputStream().apply {
            write(header.toByteArray(StandardCharsets.UTF_8))
            write(data)
            flush()
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A, val second: B, val third: C, val fourth: D
    )
}
