package com.example.roomxxx0102.logic.validation

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 按需启动的局域网结构化调试服务，不上传任何图像和视频。
 *
 * 只提供一个自带的静态网页及 WebSocket 消息。连接需要每次启动新生成的随机码；
 * 接入设备限制为私有局域网 / 本机，最多两个客户端，发送队列有上界。
 * 与 YOLO 和 V4 计算互不耦合，生产数据线程不等待网络发送。
 */
internal class SispWebDebugServer(
    private val context: Context,
    private val onCommand: (JSONObject) -> Unit
) {
    companion object {
        const val PORT = 8765
        private const val MAX_CLIENTS = 2
        private const val MAX_HTTP_LINE = 8192
        private const val MAX_INCOMING_BYTES = 64 * 1024
        private const val MAX_OUTGOING_ITEMS = 256
        private const val WEB_SOCKET_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    private val active = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<Client>()
    private val random = SecureRandom()
    private val code: String = (random.nextInt(900_000) + 100_000).toString()
    private var listener: ServerSocket? = null

    val isRunning: Boolean get() = active.get()
    val hasClients: Boolean get() = clients.isNotEmpty()
    val clientCount: Int get() = clients.size

    fun start(): Boolean {
        if (active.get()) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(PORT))
            listener = socket
            active.set(true)
            executor.execute { acceptLoop(socket) }
            true
        } catch (e: Exception) {
            Log.w("SispWebDebug", "start failed", e)
            false
        }
    }

    fun stop() {
        active.set(false)
        runCatching { listener?.close() }
        listener = null
        clients.forEach { it.close() }
        clients.clear()
        executor.shutdownNow()
    }

    fun localUrl(): String = "http://${localIpv4Address()}:$PORT/?code=$code"

    fun publish(message: JSONObject) {
        if (!hasClients) return
        val payload = message.toString()
        clients.forEach { it.enqueue(payload) }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (active.get()) {
            try {
                val socket = server.accept()
                if (!active.get()) {
                    socket.close()
                    break
                }
                if (!socket.inetAddress.isSiteLocalAddress && !socket.inetAddress.isLoopbackAddress &&
                    !socket.inetAddress.isLinkLocalAddress
                ) {
                    socket.close()
                    continue
                }
                socket.tcpNoDelay = true
                socket.soTimeout = 15_000
                executor.execute { handleConnection(socket) }
            } catch (e: Exception) {
                if (active.get()) Log.w("SispWebDebug", "accept failed", e)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        var client: Client? = null
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readLine(input) ?: return
            val parts = request.split(' ')
            if (parts.size < 3 || parts[0] != "GET") {
                respondError(output, 405, "GET only")
                return
            }
            val target = runCatching { URI(parts[1]) }.getOrNull()
            val path = target?.path ?: ""
            val key = target?.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { item ->
                if (item.substringBefore('=') == "code") URLDecoder.decode(item.substringAfter('=', ""), "UTF-8")
                else null
            }
            if (key != code) {
                respondError(output, 403, "Pairing code required")
                return
            }

            val headers = linkedMapOf<String, String>()
            repeat(40) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) return@repeat
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }

            if (path == "/") {
                val bytes = context.assets.open("sisp_debug.html").use { it.readBytes() }
                val header = ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Security-Policy: default-src 'self'; connect-src ws: wss:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'\r\n" +
                    "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                output.write(header.toByteArray(StandardCharsets.US_ASCII))
                output.write(bytes)
                output.flush()
                return
            }

            val wsKey = headers["sec-websocket-key"]
            if (path != "/ws" || !headers["upgrade"].equals("websocket", ignoreCase = true) ||
                headers["sec-websocket-version"] != "13" || wsKey == null
            ) {
                respondError(output, 404, "Not found")
                return
            }
            if (clients.size >= MAX_CLIENTS) {
                respondError(output, 503, "Too many sessions")
                return
            }
            val validKey = runCatching { Base64.getDecoder().decode(wsKey).size == 16 }.getOrDefault(false)
            if (!validKey) {
                respondError(output, 400, "Invalid handshake")
                return
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((wsKey + WEB_SOCKET_MAGIC).toByteArray(StandardCharsets.US_ASCII))
            )
            output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            socket.soTimeout = 0
            client = Client(socket, output)
            clients.add(client)
            executor.execute { client.writeLoop() }
            readWebSocketLoop(input, client)
        } catch (e: Exception) {
            if (active.get()) Log.i("SispWebDebug", "connection closed: ${e.message}")
        } finally {
            client?.let { clients.remove(it); it.close() }
            runCatching { socket.close() }
        }
    }

    private fun readWebSocketLoop(input: InputStream, client: Client) {
        while (active.get() && !client.closed.get()) {
            val first = input.read()
            val second = input.read()
            if (first < 0 || second < 0) break
            // 浏览器发出的帧必须 mask；消息必须是完整单帧。
            if ((first and 0x80) == 0 || (second and 0x80) == 0) break
            val opcode = first and 0x0f
            var size = (second and 0x7f).toLong()
            if (size == 126L) {
                val a = input.read()
                val b = input.read()
                if (a < 0 || b < 0) break
                size = ((a shl 8) or b).toLong()
            } else if (size == 127L) {
                size = 0
                repeat(8) {
                    val b = input.read()
                    if (b < 0) throw IllegalStateException("incomplete frame")
                    size = (size shl 8) or b.toLong()
                }
            }
            if (size < 0 || size > MAX_INCOMING_BYTES) break
            val mask = ByteArray(4)
            val bytes = ByteArray(size.toInt())
            if (!readFully(input, mask) || !readFully(input, bytes)) break
            bytes.indices.forEach { i ->
                bytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            when (opcode) {
                0x8 -> break
                0x9 -> client.sendFrame(0xA, bytes)
                0x1 -> {
                    val command = runCatching {
                        JSONObject(String(bytes, StandardCharsets.UTF_8))
                    }.getOrNull()
                    if (command != null) onCommand(command)
                }
                else -> break
            }
        }
    }

    private fun readFully(input: InputStream, dst: ByteArray): Boolean {
        var offset = 0
        while (offset < dst.size) {
            val count = input.read(dst, offset, dst.size - offset)
            if (count <= 0) return false
            offset += count
        }
        return true
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_HTTP_LINE) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return bytes.toString("ISO-8859-1").trimEnd('\r')
            bytes.write(b)
        }
        return null
    }

    private fun respondError(output: OutputStream, status: Int, text: String) {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        output.write(("HTTP/1.1 $status Error\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.flush()
    }

    private fun localIpv4Address(): String {
        return runCatching {
            val addresses = mutableListOf<Pair<String, String>>()
            for (network in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp || network.isLoopback || network.isVirtual) continue
                for (address in Collections.list(network.inetAddresses)) {
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        addresses.add(network.name to address.hostAddress.orEmpty())
                    }
                }
            }
            addresses.sortedBy { if (it.first.startsWith("wlan") || it.first.startsWith("ap")) 0 else 1 }
                .firstOrNull()?.second ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    private inner class Client(private val socket: Socket, private val output: OutputStream) {
        val closed = AtomicBoolean(false)
        private val outgoing = LinkedBlockingDeque<String>(MAX_OUTGOING_ITEMS)
        private val writeLock = Any()

        fun enqueue(json: String) {
            if (closed.get()) return
            if (!outgoing.offerLast(json)) {
                outgoing.pollFirst()
                outgoing.offerLast(json)
            }
        }

        fun writeLoop() {
            try {
                while (active.get() && !closed.get()) {
                    val item = outgoing.poll(1, TimeUnit.SECONDS) ?: continue
                    sendFrame(0x1, item.toByteArray(StandardCharsets.UTF_8))
                }
            } catch (_: Exception) {
                // 客户端断开由 finally 清理。
            } finally {
                closed.set(true)
                clients.remove(this)
                runCatching { socket.close() }
            }
        }

        fun sendFrame(opcode: Int, bytes: ByteArray) {
            if (closed.get()) return
            synchronized(writeLock) {
                output.write(0x80 or opcode)
                val length = bytes.size
                when {
                    length < 126 -> output.write(length)
                    length <= 0xffff -> {
                        output.write(126)
                        output.write((length shr 8) and 0xff)
                        output.write(length and 0xff)
                    }
                    else -> {
                        output.write(127)
                        for (shift in 56 downTo 0 step 8) output.write((length.toLong() shr shift).toInt() and 0xff)
                    }
                }
                output.write(bytes)
                output.flush()
            }
        }

        fun close() {
            if (closed.compareAndSet(false, true)) runCatching { socket.close() }
        }
    }
}
