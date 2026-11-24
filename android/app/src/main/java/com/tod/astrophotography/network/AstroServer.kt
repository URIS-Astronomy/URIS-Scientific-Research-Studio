package com.tod.astrophotography.network

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AstroServer(
    private val commandHandler: CommandHandler,
    private val connectionListener: (Boolean) -> Unit
) {
    interface CommandHandler {
        fun capabilities(): JSONObject
        fun setControls(payload: JSONObject): JSONObject
        fun capture(payload: JSONObject): JSONObject
        fun startVideo(payload: JSONObject): JSONObject
        fun stopVideo(): JSONObject
        fun ping(): JSONObject
        fun telemetry(): JSONObject
        fun resolveCapturedFile(path: String): File?
    }

    companion object {
        const val CONTROL_PORT = 47000
        const val PREVIEW_PORT = 47001
        const val FILE_PORT = 47002
        const val DISCOVERY_PORT = 47003
    }

    private val running = AtomicBoolean(false)
    @Volatile private var controlServer: ServerSocket? = null
    @Volatile private var previewServer: ServerSocket? = null
    @Volatile private var fileServer: ServerSocket? = null
    @Volatile private var discoverySocket: DatagramSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private data class ControlLink(val socket: Socket, val messages: ArrayBlockingQueue<String> = ArrayBlockingQueue(64))
    @Volatile private var controlLink: ControlLink? = null
    private val previewQueue = ArrayBlockingQueue<ByteArray>(1)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ runControlServer() }, "TOD-Control-Server").start()
        Thread({ runPreviewServer() }, "TOD-Preview-Server").start()
        Thread({ runFileServer() }, "TOD-File-Server").start()
        Thread({ runDiscoveryServer() }, "TOD-Discovery-Server").start()
    }

    fun stop() {
        running.set(false)
        try { controlServer?.close() } catch (_: Exception) {}
        try { previewServer?.close() } catch (_: Exception) {}
        try { fileServer?.close() } catch (_: Exception) {}
        try { discoverySocket?.close() } catch (_: Exception) {}
        clients.forEach { runCatching { it.close() } }
        controlLink = null
        previewQueue.clear()
        connectionListener(false)
    }

    fun offerPreviewFrame(jpeg: ByteArray) {
        if (!running.get()) return
        if (!previewQueue.offer(jpeg)) {
            previewQueue.poll()
            previewQueue.offer(jpeg)
        }
    }

    fun sendEvent(event: JSONObject) {
        val link = controlLink ?: return
        enqueue(link, event.toString())
    }

    private fun enqueue(link: ControlLink, message: String) {
        if (!link.messages.offer(message)) runCatching { link.socket.close() }
    }

    private fun register(socket: Socket): Boolean {
        clients.add(socket)
        if (running.get()) return true
        clients.remove(socket)
        runCatching { socket.close() }
        return false
    }

    private fun closeClient(socket: Socket) {
        clients.remove(socket)
        runCatching { socket.close() }
    }

    private fun runControlServer() {
        try {
            controlServer = ServerSocket(CONTROL_PORT).apply { reuseAddress = true }
            while (running.get()) {
                val socket = try { controlServer?.accept() } catch (_: Exception) { null } ?: break
                if (register(socket)) handleControlClient(socket)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { controlServer?.close() }
            connectionListener(false)
        }
    }

    private fun handleControlClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 12_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val link = ControlLink(socket)
        controlLink = link
        val sender = Thread({
            try {
                while (running.get() && !socket.isClosed) {
                    val message = link.messages.poll(1, TimeUnit.SECONDS) ?: continue
                    writer.write(message)
                    writer.newLine()
                    writer.flush()
                }
            } catch (_: Exception) {
            } finally { closeClient(socket) }
        }, "TOD-Control-Writer")
        sender.start()
        connectionListener(true)
        try {
            while (running.get() && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val response = try { route(JSONObject(line)) }
                catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Invalid command") }
                enqueue(link, response.toString())
            }
        } catch (_: Exception) {
        } finally {
            controlLink = null
            closeClient(socket)
            sender.interrupt()
            connectionListener(false)
        }
    }

    private fun route(request: JSONObject): JSONObject {
        val id = request.optString("id", "")
        val command = request.optString("cmd", "")
        val payload = request.optJSONObject("payload") ?: JSONObject()
        val body = when (command) {
            "hello" -> JSONObject().put("ok", true).put("app", "TOD Astro Photography").put("protocol", 2).put("file_port", FILE_PORT)
            "ping" -> commandHandler.ping()
            "capabilities" -> commandHandler.capabilities()
            "telemetry" -> commandHandler.telemetry()
            "set_controls" -> commandHandler.setControls(payload)
            "capture" -> commandHandler.capture(payload)
            "start_video" -> commandHandler.startVideo(payload)
            "stop_video" -> commandHandler.stopVideo()
            else -> JSONObject().put("ok", false).put("error", "Unknown command: $command")
        }
        if (id.isNotEmpty()) body.put("id", id)
        return body
    }

    private fun runPreviewServer() {
        try {
            previewServer = ServerSocket(PREVIEW_PORT).apply { reuseAddress = true }
            while (running.get()) {
                val socket = try { previewServer?.accept() } catch (_: Exception) { null } ?: break
                if (register(socket)) Thread({ handlePreviewClient(socket) }, "TOD-Preview-Client").start()
            }
        } catch (_: Exception) {
        } finally { runCatching { previewServer?.close() } }
    }

    private fun handlePreviewClient(socket: Socket) {
        socket.tcpNoDelay = true
        val output = DataOutputStream(socket.getOutputStream())
        try {
            while (running.get() && !socket.isClosed) {
                val frame = previewQueue.poll(1, TimeUnit.SECONDS) ?: continue
                if (frame.isEmpty()) continue
                output.writeInt(frame.size)
                output.write(frame)
                output.flush()
            }
        } catch (_: Exception) {
        } finally { closeClient(socket) }
    }

    private fun runDiscoveryServer() {
        try {
            discoverySocket = DatagramSocket(DISCOVERY_PORT).apply {
                broadcast = true
                reuseAddress = true
                soTimeout = 1000
            }
            val buffer = ByteArray(1024)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    discoverySocket?.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    if (text == "TOD_ASTRO_DISCOVER_V2" || text == "TOD_ASTRO_DISCOVER_V1") {
                        val reply = JSONObject()
                            .put("app", "TOD Astro Photography")
                            .put("protocol", 2)
                            .put("control_port", CONTROL_PORT)
                            .put("preview_port", PREVIEW_PORT)
                            .put("file_port", FILE_PORT)
                            .toString().toByteArray(Charsets.UTF_8)
                        discoverySocket?.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) { if (!running.get()) break }
            }
        } catch (_: Exception) {
        } finally { try { discoverySocket?.close() } catch (_: Exception) {}; discoverySocket = null }
    }

    private fun runFileServer() {
        try {
            fileServer = ServerSocket(FILE_PORT).apply { reuseAddress = true }
            while (running.get()) {
                val socket = try { fileServer?.accept() } catch (_: Exception) { null } ?: break
                if (register(socket)) Thread({ handleFileClient(socket) }, "TOD-File-Client").start()
            }
        } catch (_: Exception) {
        } finally { runCatching { fileServer?.close() } }
    }

    private fun handleFileClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 5000
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val path = JSONObject(requestLine).optString("path", "")
            val file = commandHandler.resolveCapturedFile(path)
            val output = DataOutputStream(socket.getOutputStream())
            if (file == null || !file.isFile) { output.writeLong(0L); output.flush(); return }
            output.writeLong(file.length())
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
        } catch (_: Exception) {
        } finally { closeClient(socket) }
    }
}
