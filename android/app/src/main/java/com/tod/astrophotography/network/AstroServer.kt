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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AstroServer(
    private val commandHandler: CommandHandler,
    private val deviceId: String,
    private val connectionListener: (String?) -> Unit
) {
    interface CommandHandler {
        fun capabilities(): JSONObject
        fun setControls(payload: JSONObject): JSONObject
        fun capture(payload: JSONObject): JSONObject
        fun cancelCapture(payload: JSONObject): JSONObject
        fun startVideo(payload: JSONObject): JSONObject
        fun stopVideo(): JSONObject
        fun ping(): JSONObject
        fun telemetry(): JSONObject
        fun isRecording(): Boolean
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
    private data class ControlLink(
        val socket: Socket,
        val messages: ArrayBlockingQueue<String> = ArrayBlockingQueue(64),
        var clientSession: String = ""
    )
    @Volatile private var controlLink: ControlLink? = null
    private data class PreviewFrame(val jpeg: ByteArray, val capturedAt: Long = System.nanoTime())
    private val previewMonitor = java.lang.Object()
    private var latestPreview: PreviewFrame? = null
    @Volatile private var previewClient: Socket? = null

    private fun listen(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
        if (!running.get()) close()
    }

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
        synchronized(previewMonitor) {
            previewClient = null
            latestPreview = null
            previewMonitor.notifyAll()
        }
        connectionListener(null)
    }

    fun hasPreviewClient(): Boolean = previewClient != null

    fun offerPreviewFrame(jpeg: ByteArray) {
        if (jpeg.isEmpty()) return
        synchronized(previewMonitor) {
            if (!running.get() || previewClient == null) return
            latestPreview = PreviewFrame(jpeg)
            previewMonitor.notifyAll()
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
            controlServer = listen(CONTROL_PORT)
            while (running.get()) {
                val socket = try { controlServer?.accept() } catch (_: Exception) { null } ?: break
                if (register(socket)) Thread({ handleControlClient(socket) }, "TOD-Control-Client").start()
            }
        } catch (e: Exception) {
            if (running.get()) android.util.Log.w("TODNetwork", "Listener or client stopped", e)
        } finally {
            runCatching { controlServer?.close() }
            connectionListener(null)
        }
    }

    private fun handleControlClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 12_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val link = ControlLink(socket)
        var activated = false
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
        try {
            while (running.get() && !socket.isClosed) {
                val line = reader.readLine() ?: break
                var requestId = ""
                val response = try {
                    val request = JSONObject(line)
                    requestId = request.optString("id")
                    val command = request.optString("cmd")
                    if (command != "probe" && !activated) {
                        if (command != "hello") break
                        synchronized(this) {
                            val previous = controlLink
                            val payload = request.optJSONObject("payload")
                            link.clientSession = payload?.optString("client_session").orEmpty()
                            if (previous != null && previous.socket !== socket && !previous.socket.isClosed &&
                                (link.clientSession.isEmpty() || previous.clientSession != link.clientSession)) {
                                throw IllegalStateException("Another PC is already connected")
                            }
                            controlLink = link
                            activated = true
                            val usb = socket.inetAddress.isLoopbackAddress && payload?.optString("transport") == "USB"
                            connectionListener(if (usb) "USB" else "LAN")
                            previous?.takeIf { it.socket !== socket }?.let { closeClient(it.socket) }
                        }
                    }
                    if (activated && controlLink !== link) break
                    route(request)
                }
                catch (e: Exception) { JSONObject().put("id", requestId).put("ok", false).put("error", e.message ?: "Invalid command") }
                enqueue(link, response.toString())
            }
        } catch (e: Exception) {
            if (running.get()) android.util.Log.w("TODNetwork", "Listener or client stopped", e)
        } finally {
            synchronized(this) {
                if (controlLink === link) {
                    controlLink = null
                    connectionListener(null)
                }
            }
            closeClient(socket)
            sender.interrupt()
        }
    }

    private fun route(request: JSONObject): JSONObject {
        val id = request.optString("id", "")
        val command = request.optString("cmd", "")
        val payload = request.optJSONObject("payload") ?: JSONObject()
        val body = when (command) {
            "hello", "probe" -> JSONObject().put("ok", true).put("app", "TOD Astro Photography").put("protocol", 2)
                .put("device_id", deviceId).put("recording", commandHandler.isRecording())
            "ping" -> commandHandler.ping()
            "capabilities" -> commandHandler.capabilities()
            "telemetry" -> commandHandler.telemetry()
            "set_controls" -> commandHandler.setControls(payload)
            "capture" -> commandHandler.capture(payload)
            "cancel_capture" -> commandHandler.cancelCapture(payload)
            "start_video" -> commandHandler.startVideo(payload)
            "stop_video" -> commandHandler.stopVideo()
            else -> JSONObject().put("ok", false).put("error", "Unknown command: $command")
        }
        if (id.isNotEmpty()) body.put("id", id)
        return body
    }

    private fun runPreviewServer() {
        try {
            previewServer = listen(PREVIEW_PORT)
            while (running.get()) {
                val socket = try { previewServer?.accept() } catch (_: Exception) { null } ?: break
                if (!register(socket)) continue
                val control = controlLink
                if (control == null || control.socket.inetAddress != socket.inetAddress) {
                    closeClient(socket)
                    continue
                }
                val previous = synchronized(previewMonitor) {
                    val retired = previewClient
                    latestPreview = null
                    previewClient = socket
                    previewMonitor.notifyAll()
                    retired
                }
                previous?.let(::closeClient)
                Thread({ handlePreviewClient(socket) }, "TOD-Preview-Client").start()
            }
        } catch (e: Exception) {
            if (running.get()) android.util.Log.w("TODNetwork", "Listener or client stopped", e)
        } finally { runCatching { previewServer?.close() } }
    }

    private fun handlePreviewClient(socket: Socket) {
        socket.tcpNoDelay = true
        val output = DataOutputStream(socket.getOutputStream())
        try {
            while (running.get() && !socket.isClosed) {
                // A retired client must never consume a frame intended for the active socket.
                val frame = synchronized(previewMonitor) {
                    while (running.get() && previewClient === socket && latestPreview == null) {
                        previewMonitor.wait(1000)
                    }
                    if (!running.get() || previewClient !== socket) return
                    latestPreview.also { latestPreview = null }
                } ?: continue
                if (System.nanoTime() - frame.capturedAt > TimeUnit.SECONDS.toNanos(1)) continue
                output.writeInt(frame.jpeg.size)
                output.write(frame.jpeg)
                output.flush()
            }
        } catch (e: Exception) {
            if (running.get()) android.util.Log.w("TODNetwork", "Listener or client stopped", e)
        } finally {
            synchronized(previewMonitor) {
                if (previewClient === socket) {
                    previewClient = null
                    latestPreview = null
                }
            }
            closeClient(socket)
        }
    }

    private fun runDiscoveryServer() {
        try {
            discoverySocket = DatagramSocket(null).apply {
                broadcast = true
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                if (!running.get()) close()
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
        } catch (e: Exception) {
            if (running.get()) android.util.Log.w("TODNetwork", "Listener or client stopped", e)
        } finally { try { discoverySocket?.close() } catch (_: Exception) {}; discoverySocket = null }
    }

    private fun runFileServer() {
        try {
            fileServer = listen(FILE_PORT)
            while (running.get()) {
                val socket = try { fileServer?.accept() } catch (_: Exception) { null } ?: break
                if (register(socket)) Thread({ handleFileClient(socket) }, "TOD-File-Client").start()
            }
        } catch (e: Exception) {
            if (running.get()) android.util.Log.w("TODNetwork", "Listener or client stopped", e)
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
        } catch (e: Exception) {
            if (running.get()) android.util.Log.w("TODNetwork", "Listener or client stopped", e)
        } finally { closeClient(socket) }
    }
}
