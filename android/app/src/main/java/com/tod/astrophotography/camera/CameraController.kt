package com.tod.astrophotography.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class CameraController(
    private val activity: Activity,
    private val textureView: TextureView,
    private val frameSink: (ByteArray) -> Unit,
    private val eventSink: (JSONObject) -> Unit,
    private val statusSink: (String) -> Unit
) {
    private val cameraManager = activity.getSystemService(Activity.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("TOD-Camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var cameraId: String? = null
    @Volatile private var characteristics: CameraCharacteristics? = null
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    private var repeatingBuilder: CaptureRequest.Builder? = null

    private var previewReader: ImageReader? = null
    @Volatile private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var previewSize = Size(1280, 720)
    private var jpegSize = Size(1920, 1080)
    private var rawSize: Size? = null
    private var hardwareRawSupported = false
    @Volatile private var rawSessionAvailable = false
    @Volatile private var started = false
    @Volatile private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    @Volatile private var recording = false

    private var iso = 200
    private var exposureUs = 10_000L
    private var focusDistance = 0.0f
    private var manualEnabled = false
    private var aeLock = false
    private var awbLock = false
    private var oisEnabled = false
    private var eisEnabled = false
    private var roiPercent = 100

    private val lastPreviewNs = AtomicLong(0L)
    private val previewIntervalNs = 80_000_000L // ~12.5 fps max before USB/network limits

    private val captureLock = Any()
    private var pendingCapture: PendingCapture? = null
    private var pendingRawImage: Image? = null
    private var pendingRawResult: TotalCaptureResult? = null

    private data class PendingCapture(
        val baseName: String,
        val wantsRaw: Boolean,
        val captureId: String,
        val frameType: String,
        var jpegPath: String? = null,
        var rawPath: String? = null,
        var jpegDone: Boolean = false,
        var rawDone: Boolean = false
    )

    fun start() {
        if (started) return
        started = true
        cameraHandler.post {
            try {
                chooseCamera()
                openCamera()
            } catch (e: Exception) {
                statusSink("Camera start error: ${e.message}")
                started = false
            }
        }
    }

    fun stop() {
        started = false
        cameraHandler.post {
            if (recording || mediaRecorder != null) {
                try { mediaRecorder?.stop() } catch (_: Exception) {}
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
                recordingFile = null
                recording = false
            }
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { session?.abortCaptures() } catch (_: Exception) {}
            session?.close(); session = null
            cameraDevice?.close(); cameraDevice = null
            previewReader?.close(); previewReader = null
            jpegReader?.close(); jpegReader = null
            rawReader?.close(); rawReader = null
            previewSurface?.release(); previewSurface = null
        }
    }

    fun shutdown() {
        stop()
        cameraThread.quitSafely()
    }

    fun capabilities(): JSONObject {
        val chars = characteristics
        if (chars == null || (session == null && !recording)) return JSONObject().put("ok", false).put("error", "Camera is starting or unavailable")
        val sensorRange = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val caps = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val capabilityArray = JSONArray()
        caps.forEach { capabilityArray.put(it) }
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val fpsRanges = JSONArray()
        chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.forEach { fpsRanges.put("${it.lower}-${it.upper}") }
        val yuvSizes = JSONArray()
        map?.getOutputSizes(ImageFormat.YUV_420_888)?.sortedByDescending { it.width.toLong() * it.height }?.take(20)?.forEach { yuvSizes.put("${it.width}x${it.height}") }
        val jpegSizes = JSONArray()
        map?.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width.toLong() * it.height }?.take(20)?.forEach { jpegSizes.put("${it.width}x${it.height}") }
        val rawSizes = JSONArray()
        if (hardwareRawSupported) map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.sortedByDescending { it.width.toLong() * it.height }?.take(20)?.forEach { rawSizes.put("${it.width}x${it.height}") }
        val physicalIds = JSONArray()
        if (android.os.Build.VERSION.SDK_INT >= 28) chars?.physicalCameraIds?.forEach { physicalIds.put(it) }
        val hwLevel = when (chars?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
        val burst = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
        val highSpeed = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
        val videoModes = JSONArray()
        if (map != null) {
            try {
                val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                map.getOutputSizes(MediaRecorder::class.java)?.sortedByDescending { it.width.toLong() * it.height }?.filter { it.width <= 3840 && it.height <= 2160 }?.take(12)?.forEach { size ->
                    val minDuration = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                    ranges.filter { it.lower == it.upper && it.upper <= 60 &&
                        (minDuration == 0L || minDuration * it.upper <= 1_001_000_000L) }.distinct().forEach {
                        videoModes.put("${size.width}×${size.height} @ ${it.upper} fps")
                    }
                }
            } catch (_: Exception) {}
        }
        val highSpeedModes = JSONArray()
        if (highSpeed && map != null) {
            try {
                map.highSpeedVideoSizes.forEach { size ->
                    map.getHighSpeedVideoFpsRangesFor(size).filter { it.lower == it.upper }.forEach { range ->
                        highSpeedModes.put("${size.width}×${size.height} @ ${range.lower}-${range.upper} fps")
                    }
                }
            } catch (_: Exception) {}
        }

        return JSONObject()
            .put("ok", true)
            .put("camera_id", cameraId ?: "")
            .put("camera_ids", JSONArray(cameraManager.cameraIdList.toList()))
            .put("sensor_width", chars?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width ?: 0)
            .put("sensor_height", chars?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.height ?: 0)
            .put("awb_modes", JSONArray(chars?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList<Int>()))
            .put("preview_width", previewSize.width)
            .put("preview_height", previewSize.height)
            .put("jpeg_width", jpegSize.width)
            .put("jpeg_height", jpegSize.height)
            .put("raw_hardware_supported", hardwareRawSupported)
            .put("raw_session_available", rawSessionAvailable && !recording)
            .put("raw_width", rawSize?.width ?: 0)
            .put("raw_height", rawSize?.height ?: 0)
            .put("iso_min", sensorRange?.lower ?: 0)
            .put("iso_max", sensorRange?.upper ?: 0)
            .put("exposure_us_min", (exposureRange?.lower ?: 0L) / 1000L)
            .put("exposure_us_max", (exposureRange?.upper ?: 0L) / 1000L)
            .put("min_focus_distance", minFocus.toDouble())
            .put("max_zoom", chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble() ?: 1.0)
            .put("manual_sensor", caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
            .put("burst_capture", burst)
            .put("high_speed_video", highSpeed)
            .put("high_speed_modes", highSpeedModes)
            .put("video_capture", videoModes.length() > 0)
            .put("video_modes", videoModes)
            .put("hardware_level", hwLevel)
            .put("physical_camera_ids", physicalIds)
            .put("fps_ranges", fpsRanges)
            .put("yuv_sizes", yuvSizes)
            .put("jpeg_sizes", jpegSizes)
            .put("raw_sizes", rawSizes)
            .put("ae_lock_supported", chars?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true)
            .put("awb_lock_supported", chars?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true)
            .put("ois_modes", JSONArray(chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList<Int>()))
            .put("eis_modes", JSONArray(chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() ?: emptyList<Int>()))
            .put("capability_ids", capabilityArray)
    }

    @Volatile private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO

    fun setControls(payload: JSONObject): JSONObject {
        val chars = characteristics ?: return JSONObject().put("ok", false).put("error", "Camera not ready")
        val sensorRange: Range<Int>? = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange: Range<Long>? = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val supportsManual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (payload.has("manual")) manualEnabled = supportsManual && payload.optBoolean("manual")
        if (payload.has("awb_mode")) {
            val requested = payload.optInt("awb_mode", CaptureRequest.CONTROL_AWB_MODE_AUTO)
            if (chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(requested) == true &&
                requested != CaptureRequest.CONTROL_AWB_MODE_OFF) awbMode = requested
        }
        if (payload.has("iso")) {
            val requested = payload.optInt("iso", iso)
            iso = if (sensorRange != null) requested.coerceIn(sensorRange.lower, sensorRange.upper) else requested
        }
        if (payload.has("exposure_us")) {
            val requestedNs = payload.optLong("exposure_us", exposureUs) * 1000L
            val clampedNs = if (exposureRange != null) requestedNs.coerceIn(exposureRange.lower, exposureRange.upper) else requestedNs
            exposureUs = clampedNs / 1000L
        }
        if (payload.has("focus_distance")) {
            focusDistance = payload.optDouble("focus_distance", focusDistance.toDouble()).toFloat().coerceIn(0f, minFocus)
        }
        if (payload.has("ae_lock")) aeLock = payload.optBoolean("ae_lock", aeLock)
        if (payload.has("awb_lock")) awbLock = payload.optBoolean("awb_lock", awbLock)
        if (payload.has("ois")) oisEnabled = payload.optString("ois", "off") == "on"
        if (payload.has("eis")) eisEnabled = payload.optString("eis", "off") == "on"
        if (payload.has("roi_percent")) roiPercent = payload.optInt("roi_percent", roiPercent).coerceIn(25, 100)
        cameraHandler.post { applyRepeatingControls() }
        return controlState()
    }

    fun controlState(): JSONObject = JSONObject().put("ok", true)
            .put("iso", iso)
            .put("exposure_us", exposureUs)
            .put("focus_distance", focusDistance.toDouble())
            .put("manual", manualEnabled)
            .put("ae_lock", aeLock)
            .put("awb_lock", awbLock)
            .put("ois", oisEnabled)
            .put("eis", eisEnabled)
            .put("roi_percent", roiPercent)

    fun isRecording(): Boolean = recording

    fun capture(payload: JSONObject): JSONObject {
        if (recording || mediaRecorder != null) return JSONObject().put("ok", false).put("error", "Video recording is active")
        if (cameraDevice == null || session == null || jpegReader == null) {
            return JSONObject().put("ok", false).put("error", "Camera not ready")
        }
        val wantsRaw = payload.optBoolean("raw", false)
        val wantsJpeg = payload.optBoolean("jpeg", true)
        if (!wantsRaw && !wantsJpeg) return JSONObject().put("ok", false).put("error", "Select JPEG or RAW")
        if (wantsRaw && !rawSessionAvailable) {
            return JSONObject().put("ok", false).put("error", "RAW is not available in this camera session")
        }
        synchronized(captureLock) {
            if (pendingCapture != null) {
                return JSONObject().put("ok", false).put("error", "A capture is already in progress")
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            pendingCapture = PendingCapture("TOD_$stamp", wantsRaw, payload.optString("capture_id"), payload.optString("frame_type", "Light"), jpegDone = !wantsJpeg, rawDone = !wantsRaw)
        }
        cameraHandler.post { performStillCapture(wantsRaw, wantsJpeg) }
        return JSONObject().put("ok", true).put("status", "capture_started").put("raw", wantsRaw)
    }

    fun startVideo(payload: JSONObject): JSONObject {
        if (recording || mediaRecorder != null) return JSONObject().put("ok", false).put("error", "Video is already recording or starting")
        if (cameraDevice == null) return JSONObject().put("ok", false).put("error", "Camera not ready")
        val width = payload.optInt("width", 1920).coerceAtLeast(320)
        val height = payload.optInt("height", 1080).coerceAtLeast(240)
        val fps = payload.optInt("fps", 30).coerceIn(1, 1000)
        val highSpeed = payload.optBoolean("high_speed", false)
        val bitrate = payload.optInt("bitrate", if (width >= 3840) 80_000_000 else if (width >= 1920) 30_000_000 else 12_000_000)
        cameraHandler.post {
            try {
                configureRecording(width, height, fps, bitrate, highSpeed)
            } catch (e: Exception) {
                mediaRecorder?.release()
                mediaRecorder = null
                recordingFile?.delete()
                recordingFile = null
                recording = false
                eventSink(JSONObject().put("event", "capture_error").put("message", "Video start failed: ${e.message}"))
                if (session == null && started) configureReadersAndSession(includeRaw = hardwareRawSupported)
            }
        }
        return JSONObject().put("ok", true).put("status", "video_starting").put("width", width).put("height", height).put("fps", fps)
    }

    fun stopVideo(): JSONObject {
        if (!recording && mediaRecorder == null) return JSONObject().put("ok", false).put("error", "Video is not recording")
        cameraHandler.post { stopRecordingInternal() }
        return JSONObject().put("ok", true).put("status", "video_stopping")
    }

    private fun configureRecording(requestedWidth: Int, requestedHeight: Int, requestedFps: Int, bitrate: Int, highSpeed: Boolean) {
        val camera = cameraDevice ?: return
        val chars = characteristics ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        check(!recording && mediaRecorder == null) { "Video is already starting or recording" }
        check(synchronized(captureLock) { pendingCapture == null }) { "A still capture is in progress" }
        val size = Size(requestedWidth, requestedHeight)
        val fps = requestedFps
        val range = Range(fps, fps)
        if (highSpeed) {
            require(map.highSpeedVideoSizes.contains(size) &&
                map.getHighSpeedVideoFpsRangesFor(size).contains(range)) { "Unsupported high-speed video mode" }
        } else {
            require(map.getOutputSizes(MediaRecorder::class.java)?.contains(size) == true &&
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.contains(range) == true &&
                fps <= 60 && map.getOutputMinFrameDuration(MediaRecorder::class.java, size) * fps <= 1_001_000_000L) {
                "Unsupported video resolution or FPS"
            }
        }
        repeatingBuilder = null
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { mediaRecorder?.release() } catch (_: Exception) {}
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val out = File(captureDir(), "TOD_VIDEO_$stamp.mp4")
        val recorder = MediaRecorder()
        mediaRecorder = recorder
        recordingFile = out
        recorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(out.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)
            setVideoSize(size.width, size.height)
            prepare()
        }
        val texture = textureView.surfaceTexture ?: throw IllegalStateException("Preview surface is not ready")
        if (highSpeed) texture.setDefaultBufferSize(size.width, size.height)
        else texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        if (previewSurface == null) previewSurface = Surface(texture)
        val outputs = mutableListOf<Surface>()
        previewSurface?.let(outputs::add)
        if (!highSpeed) previewReader?.surface?.let(outputs::add)
        outputs.add(recorder.surface)

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                try {
                    if (!started || mediaRecorder !== recorder) {
                        captureSession.close()
                        return
                    }
                    session = captureSession
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    previewSurface?.let(builder::addTarget)
                    if (!highSpeed) previewReader?.surface?.let(builder::addTarget)
                    builder.addTarget(recorder.surface)
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                    if (captureSession is CameraConstrainedHighSpeedCaptureSession) {
                        val requests = captureSession.createHighSpeedRequestList(builder.build())
                        captureSession.setRepeatingBurst(requests, null, cameraHandler)
                    } else {
                        applyControls(builder)
                        captureSession.setRepeatingRequest(builder.build(), null, cameraHandler)
                    }
                    recorder.start()
                    recording = true
                    statusSink("Recording ${size.width}×${size.height} @ ${fps} fps")
                    eventSink(JSONObject().put("event", "video_started").put("path", out.absolutePath).put("width", size.width).put("height", size.height).put("fps", fps))
                } catch (e: Exception) {
                    eventSink(JSONObject().put("event", "capture_error").put("message", "Video start failed: ${e.message}"))
                    try { recorder.release() } catch (_: Exception) {}
                    mediaRecorder = null
                    recordingFile = null
                    recording = false
                    configureReadersAndSession(includeRaw = hardwareRawSupported && rawSize != null)
                }
            }
            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                captureSession.close()
                eventSink(JSONObject().put("event", "capture_error").put("message", "Video session configuration failed"))
                try { recorder.release() } catch (_: Exception) {}
                mediaRecorder = null
                recordingFile = null
                recording = false
                configureReadersAndSession(includeRaw = hardwareRawSupported && rawSize != null)
            }
        }
        if (highSpeed) camera.createConstrainedHighSpeedCaptureSession(outputs, callback, cameraHandler)
        else camera.createCaptureSession(outputs, callback, cameraHandler)
    }

    private fun stopRecordingInternal() {
        val recorder = mediaRecorder
        val file = recordingFile
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        var ok = false
        if (recorder != null) {
            try { recorder.stop(); ok = true } catch (_: Exception) {}
            try { recorder.reset() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
        }
        mediaRecorder = null
        recordingFile = null
        recording = false
        if (ok && file != null && file.isFile && file.length() > 0) {
            eventSink(JSONObject().put("event", "capture_saved").put("files", JSONArray().put(file.absolutePath)).put("kind", "video"))
        } else if (file != null) {
            try { file.delete() } catch (_: Exception) {}
            eventSink(JSONObject().put("event", "capture_error").put("message", "Video recording did not finalize correctly"))
        }
        configureReadersAndSession(includeRaw = hardwareRawSupported && rawSize != null)
    }

    private fun chooseCamera() {
        var bestId: String? = null
        var bestArea = -1L
        var bestChars: CameraCharacteristics? = null
        cameraManager.cameraIdList.forEach { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@forEach
            val pixel = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val area = if (pixel != null) pixel.width.toLong() * pixel.height.toLong() else 0L
            if (area > bestArea) {
                bestArea = area
                bestId = id
                bestChars = chars
            }
        }
        cameraId = bestId ?: cameraManager.cameraIdList.firstOrNull()
            ?: throw IllegalStateException("No camera found")
        characteristics = bestChars ?: cameraManager.getCameraCharacteristics(cameraId!!)

        val map = characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("No stream configuration map")
        previewSize = choosePreviewSize(map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty())
        jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)
        val caps = characteristics!!.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        hardwareRawSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        rawSize = if (hardwareRawSupported) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        } else null
        statusSink("Camera ${cameraId} • Preview ${previewSize.width}×${previewSize.height} • JPEG ${jpegSize.width}×${jpegSize.height}")
    }

    private fun choosePreviewSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        return (candidates.ifEmpty { sizes }).minByOrNull {
            val areaDelta = abs(it.width.toLong() * it.height - 1280L * 720L)
            val ratioDelta = abs(it.width.toDouble() / it.height - 16.0 / 9.0)
            areaDelta + (ratioDelta * 1_000_000).toLong()
        } ?: sizes.first()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = cameraId ?: return
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!started) { camera.close(); return }
                cameraDevice = camera
                configureReadersAndSession(includeRaw = hardwareRawSupported && rawSize != null)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); cameraDevice = null
                statusSink("Camera disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); cameraDevice = null
                statusSink("Camera error code $error")
            }
        }, cameraHandler)
    }

    private fun configureReadersAndSession(includeRaw: Boolean) {
        val camera = cameraDevice ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null
        previewReader?.close(); jpegReader?.close(); rawReader?.close()

        previewReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.nanoTime()
                    val previous = lastPreviewNs.get()
                    if (now - previous >= previewIntervalNs && lastPreviewNs.compareAndSet(previous, now)) {
                        frameSink(yuvToJpeg(image, 82))
                    }
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try { saveJpeg(image) } catch (e: Exception) {
                    failPending("JPEG save failed: ${e.message}")
                } finally { image.close() }
            }, cameraHandler)
        }

        rawReader = if (includeRaw && rawSize != null) {
            ImageReader.newInstance(rawSize!!.width, rawSize!!.height, ImageFormat.RAW_SENSOR, 2).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    synchronized(captureLock) {
                        pendingRawImage?.close()
                        pendingRawImage = image
                    }
                    tryWriteRaw()
                }, cameraHandler)
            }
        } else null

        val surfaceTexture: SurfaceTexture = textureView.surfaceTexture
            ?: throw IllegalStateException("Preview surface is not ready")
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)

        val outputs = mutableListOf<Surface>()
        previewSurface?.let(outputs::add)
        previewReader?.surface?.let(outputs::add)
        jpegReader?.surface?.let(outputs::add)
        rawReader?.surface?.let(outputs::add)

        camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                if (!started) { captureSession.close(); return }
                session = captureSession
                rawSessionAvailable = rawReader != null
                startRepeating()
                statusSink("Camera ready • PC live stream active")
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                captureSession.close()
                if (includeRaw) {
                    rawSessionAvailable = false
                    statusSink("RAW stream combination unsupported; retrying JPEG mode")
                    configureReadersAndSession(includeRaw = false)
                } else {
                    statusSink("Camera session configuration failed")
                }
            }
        }, cameraHandler)
    }

    private fun startRepeating() {
        val camera = cameraDevice ?: return
        val captureSession = session ?: return
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let(builder::addTarget)
        previewReader?.surface?.let(builder::addTarget)
        repeatingBuilder = builder
        applyControls(builder)
        captureSession.setRepeatingRequest(builder.build(), null, cameraHandler)
    }

    private fun applyRepeatingControls() {
        val builder = repeatingBuilder ?: return
        val captureSession = session ?: return
        try {
            applyControls(builder)
            captureSession.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            statusSink("Control update error: ${e.message}")
        }
    }

    private fun applyControls(builder: CaptureRequest.Builder) {
        if (manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureUs * 1000L)
            val minFocus = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (minFocus > 0f) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance.coerceIn(0f, minFocus))
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        try {
            val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (active != null && roiPercent < 100) {
                val zoom = characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                val scale = maxOf(roiPercent / 100.0, 1.0 / zoom)
                val w = (active.width() * scale).toInt().coerceAtLeast(64)
                val h = (active.height() * scale).toInt().coerceAtLeast(64)
                val left = active.centerX() - w / 2
                val top = active.centerY() - h / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(left, top, left + w, top + h))
            } else if (active != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, active)
            }
        } catch (_: Exception) {}
        try { builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLock) } catch (_: Exception) {}
        try { builder.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock) } catch (_: Exception) {}
        try { builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if (eisEnabled) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) } catch (_: Exception) {}
        try { builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if (oisEnabled) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) } catch (_: Exception) {}
    }

    private fun performStillCapture(wantsRaw: Boolean, wantsJpeg: Boolean) {
        val camera = cameraDevice ?: return failPending("Camera unavailable")
        val captureSession = session ?: return failPending("Session unavailable")
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            if (wantsJpeg) jpegReader?.surface?.let(builder::addTarget)
            if (wantsRaw) rawReader?.surface?.let(builder::addTarget)
            applyControls(builder)
            builder.set(CaptureRequest.JPEG_ORIENTATION, 0)
            captureSession.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (wantsRaw) {
                        synchronized(captureLock) { pendingRawResult = result }
                        tryWriteRaw()
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    failPending("Capture failed: ${failure.reason}")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            failPending(e.message ?: "Capture error")
        }
    }

    private fun captureDir(): File {
        val base = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: activity.filesDir
        return File(base, "Captures").apply { mkdirs() }
    }

    private fun saveJpeg(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val pending = synchronized(captureLock) { pendingCapture } ?: return
        val file = File(captureDir(), "${pending.baseName}.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        synchronized(captureLock) {
            pendingCapture?.jpegPath = file.absolutePath
            pendingCapture?.jpegDone = true
        }
        maybeEmitCaptureComplete()
    }

    private fun tryWriteRaw() {
        val image: Image
        val result: TotalCaptureResult
        val pending: PendingCapture
        synchronized(captureLock) {
            image = pendingRawImage ?: return
            result = pendingRawResult ?: return
            pending = pendingCapture ?: run {
                image.close(); pendingRawImage = null; pendingRawResult = null; return
            }
            pendingRawImage = null
            pendingRawResult = null
        }
        try {
            val file = File(captureDir(), "${pending.baseName}.dng")
            val chars = characteristics ?: throw IllegalStateException("No camera characteristics")
            FileOutputStream(file).use { output -> DngCreator(chars, result).use { it.writeImage(output, image) } }
            synchronized(captureLock) {
                pendingCapture?.rawPath = file.absolutePath
                pendingCapture?.rawDone = true
            }
        } catch (e: Exception) {
            failPending("RAW save failed: ${e.message}")
        } finally {
            image.close()
        }
        maybeEmitCaptureComplete()
    }

    private fun maybeEmitCaptureComplete() {
        val done: PendingCapture? = synchronized(captureLock) {
            val p = pendingCapture
            if (p != null && p.jpegDone && p.rawDone) {
                pendingCapture = null
                p
            } else null
        }
        if (done != null) {
            val files = JSONArray()
            done.jpegPath?.let(files::put)
            done.rawPath?.let(files::put)
            eventSink(JSONObject().put("event", "capture_saved").put("files", files).put("raw_requested", done.wantsRaw).put("capture_id", done.captureId).put("frame_type", done.frameType))
        }
    }

    private fun failPending(message: String) {
        val pending = synchronized(captureLock) {
            val failed = pendingCapture
            pendingCapture = null
            pendingRawImage?.close(); pendingRawImage = null
            pendingRawResult = null
            failed
        }
        eventSink(JSONObject().put("event", "capture_error").put("message", message)
            .put("capture_id", pending?.captureId ?: ""))
    }

    private fun yuvToJpeg(image: Image, quality: Int): ByteArray {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val out = ByteArray(ySize + width * height / 2)
        copyPlane(image.planes[0], width, height, out, 0, 1)

        val u = unpackPlane(image.planes[1], width / 2, height / 2)
        val v = unpackPlane(image.planes[2], width / 2, height / 2)
        var dst = ySize
        for (i in v.indices) {
            out[dst++] = v[i]
            out[dst++] = u[i]
        }
        return out
    }

    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, out: ByteArray, offset: Int, step: Int) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var dst = offset
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                val index = rowStart + col * pixelStride
                if (index < buffer.limit()) out[dst] = buffer.get(index)
                dst += step
            }
        }
    }

    private fun unpackPlane(plane: Image.Plane, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        copyPlane(plane, width, height, out, 0, 1)
        return out
    }
}
