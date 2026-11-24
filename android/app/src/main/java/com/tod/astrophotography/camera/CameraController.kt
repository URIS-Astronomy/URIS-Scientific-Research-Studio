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
import android.media.MediaCodec
import android.os.Environment
import android.os.Build
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.Rect
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
    private val statusSink: (String) -> Unit,
    private val zoomSink: (Double) -> Unit
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
    private lateinit var jpegSize: Size
    private lateinit var standardJpegSize: Size
    private var maximumJpegSize: Size? = null
    private var maximumCapture = false
    private var mainPhysicalId: String? = null
    private var cameraInventory = JSONArray()
    @Volatile private var zoom = 1.0
    @Volatile private var effective = JSONObject()
    fun previewZoom(): Double = zoom
    fun measured(): JSONObject = effective
    private val lastZoomEvent = AtomicLong(0L)
    fun updateDisplayZoom(ratio: Double) {
        zoom = ImagingPolicy.displayZoom(ratio)
        zoomSink(zoom)
        val now = System.nanoTime()
        val previous = lastZoomEvent.get()
        if (now-previous >= 45_000_000 && lastZoomEvent.compareAndSet(previous,now))
            eventSink(JSONObject().put("event", "display_zoom").put("ratio",zoom))
    }
    private var rawSize: Size? = null
    private var hardwareRawSupported = false
    @Volatile private var rawSessionAvailable = false
    @Volatile private var started = false
    @Volatile private var videoEncoder: SurfaceVideoEncoder? = null
    private var recordingFile: File? = null
    private var recordingTimer: Runnable? = null
    private var videoFPS = 0
    private var videoFailures = 0
    private var videoFrames = 0L
    private var videoCameraTimes = FrameTimes()
    private var videoLostFrames = mutableSetOf<Long>()
    private var videoRank = false
    private val videoReview = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var recording = false

    private var iso = 200
    private var exposureTimeNs = 10_000_000L
    private var focusDistance = 0.0f
    private var manualFocus = false
    private var manualEnabled = false
    private var aeLock = false
    private var awbLock = false
    private var oisEnabled = false
    private var eisEnabled = false
    private var roiPercent = 100

    private val lastPreviewNs = AtomicLong(0L)
    @Volatile var previewHistogram = IntArray(0)
        private set
    val previewFrames = AtomicLong(0L)
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
        val count: Int,
        val burstFiles: MutableList<String> = mutableListOf(),
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
            recordingTimer?.let(cameraHandler::removeCallbacks)
            recordingTimer = null
            if (recording || videoEncoder != null) {
                try { videoEncoder?.stop() } catch (_: Exception) {}
                try { videoEncoder?.release() } catch (_: Exception) {}
                videoEncoder = null
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
        videoReview.shutdownNow()
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
                map.getOutputSizes(MediaCodec::class.java)?.sortedByDescending { it.width.toLong() * it.height }?.forEach { size ->
                    val minDuration = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
                    ranges.filter { it.lower == it.upper && ImagingPolicy.standardVideoTiming(it.upper,minDuration) && avcEncoder(size.width,size.height,it.upper) != null }.distinct().forEach {
                        videoModes.put("${size.width}×${size.height} @ ${it.upper} fps")
                    }
                }
            } catch (_: Exception) {}
        }
        val highSpeedModes = JSONArray()
        if (highSpeed && map != null) {
            try {
                map.highSpeedVideoSizes.forEach { size ->
                    map.getHighSpeedVideoFpsRangesFor(size).filter { it.lower == it.upper && avcEncoder(size.width,size.height,it.upper) != null }.forEach { range ->
                        highSpeedModes.put("${size.width}×${size.height} @ ${range.lower}-${range.upper} fps")
                    }
                }
            } catch (_: Exception) {}
        }

        return JSONObject()
            .put("ok", true)
            .put("camera_id", cameraId ?: "")
            .put("main_physical_id", mainPhysicalId ?: JSONObject.NULL)
            .put("camera_inventory", cameraInventory)
            .put("display_zoom_max", ImagingPolicy.MAX_DISPLAY_ZOOM)
            .put("processing_modes", JSONObject().apply { processingModes(chars).forEach { (key, values) -> put(key, JSONArray(values.toList())) } })
            .put("capture_settings",controlState())
            .put("fixed_color_supported",chars?.let(::supportsFixedColor)==true)
            .put("camera_ids", JSONArray(cameraManager.cameraIdList.toList()))
            .put("sensor_width", chars?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width ?: 0)
            .put("sensor_height", chars?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.height ?: 0)
            .put("awb_modes", JSONArray(chars?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList<Int>()))
            .put("preview_width", previewSize.width)
            .put("preview_height", previewSize.height)
            .put("maximum_jpeg_width",maximumJpegSize?.width ?: 0)
            .put("maximum_jpeg_height",maximumJpegSize?.height ?: 0)
            .put("jpeg_width", jpegSize.width)
            .put("jpeg_height", jpegSize.height)
            .put("raw_hardware_supported", hardwareRawSupported)
            .put("raw_session_available", rawSessionAvailable && !recording)
            .put("raw_width", rawSize?.width ?: 0)
            .put("raw_height", rawSize?.height ?: 0)
            .put("iso_min", sensorRange?.lower ?: 0)
            .put("iso_max", sensorRange?.upper ?: 0)
            .put("exposure_us_min", (exposureRange?.lower ?: 0L) / 1000.0)
            .put("exposure_us_max", (exposureRange?.upper ?: 0L) / 1000.0)
            .put("min_focus_distance", minFocus.toDouble())
            .put("max_zoom", zoomRange(chars)?.upper?.toDouble() ?: chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble() ?: 1.0)
            .put("manual_sensor", caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
            .put("burst_capture", burst)
            .put("high_speed_video", highSpeed)
            .put("high_speed_modes", highSpeedModes)
            .put("video_capture", videoModes.length() > 0 || highSpeedModes.length() > 0)
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

    private data class FixedColor(val gains: RggbChannelVector, val matrix: ColorSpaceTransform)
    @Volatile private var measuredColor: FixedColor? = null
    @Volatile private var fixedColor: FixedColor? = null
    private fun supportsFixedColor(c: CameraCharacteristics): Boolean =
        c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AWB_MODE_OFF) == true &&
        c.availableCaptureRequestKeys.orEmpty().containsAll(listOf(CaptureRequest.COLOR_CORRECTION_MODE,
            CaptureRequest.COLOR_CORRECTION_GAINS,CaptureRequest.COLOR_CORRECTION_TRANSFORM))

    @Volatile private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO

    fun setControls(payload: JSONObject): JSONObject {
        val chars = characteristics ?: return JSONObject().put("ok", false).put("error", "Camera not ready")
        val sensorRange: Range<Int>? = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange: Range<Long>? = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val supportsManual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (payload.has("display_zoom")) {
            updateDisplayZoom(payload.optDouble("display_zoom", zoom))
            if (payload.length() == 1) return JSONObject().put("ok",true).put("display_zoom",zoom)
        }
        if (payload.has("fixed_color")) {
            if (payload.optBoolean("fixed_color")) {
                if (supportsFixedColor(chars)) {
                    fixedColor = measuredColor ?: return JSONObject().put("ok",false).put("error","Wait for an automatic white-balance measurement")
                }
            } else fixedColor = null
        }
        val availableKeys = chars.availableCaptureRequestKeys.orEmpty()
        val modes = processingModes(chars)
        for ((name, key) in processingRequests) {
            val value = payload.optInt(name, -1)
            if (key in availableKeys && modes[name]?.contains(value) == true) processing[name] = value
        }
        if (payload.has("manual")) manualEnabled = supportsManual && payload.optBoolean("manual")
        if (payload.has("awb_mode")) {
            val requested = payload.optInt("awb_mode", CaptureRequest.CONTROL_AWB_MODE_AUTO)
            if (chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(requested) == true &&
                requested != CaptureRequest.CONTROL_AWB_MODE_OFF) awbMode = requested
        }
        if (payload.has("iso")) {
            val requested = payload.optInt("iso", iso)
            iso = if (sensorRange != null) ImagingPolicy.iso(requested,sensorRange.lower,sensorRange.upper) else requested
        }
        if (payload.has("exposure_us")) {
            if (exposureRange != null) {
                exposureTimeNs = ImagingPolicy.exposureNs(payload.optDouble("exposure_us",exposureTimeNs/1000.0),
                    exposureTimeNs,exposureRange.lower,exposureRange.upper)
            }
        }
        if (payload.has("manual_focus")) manualFocus = minFocus > 0 && payload.optBoolean("manual_focus")
        if (payload.has("focus_distance")) {
            val value = payload.optDouble("focus_distance", focusDistance.toDouble())
            focusDistance = ImagingPolicy.focus(value,focusDistance,minFocus)
        }
        if (payload.has("ae_lock")) aeLock = payload.optBoolean("ae_lock", aeLock)
        if (payload.has("awb_lock")) awbLock = payload.optBoolean("awb_lock", awbLock)
        if (payload.has("ois")) oisEnabled = ImagingPolicy.stabilization(payload.optBoolean("ois") || payload.optString("ois") == "on",chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION))
        if (payload.has("eis")) eisEnabled = ImagingPolicy.stabilization(payload.optBoolean("eis") || payload.optString("eis") == "on",chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES))
        if (payload.has("roi_percent")) roiPercent = payload.optInt("roi_percent", roiPercent).coerceIn(25, 100)
        cameraHandler.post { applyRepeatingControls() }
        return controlState()
    }

    fun controlState(): JSONObject = JSONObject().put("ok", true)
            .put("iso", iso)
            .put("exposure_us", exposureTimeNs/1000.0)
            .put("focus_distance", focusDistance.toDouble())
            .put("manual_focus", manualFocus)
            .put("awb_mode", awbMode).put("fixed_color",fixedColor != null)
            .put("manual", manualEnabled)
            .put("ae_lock", aeLock)
            .put("awb_lock", awbLock)
            .put("ois", oisEnabled)
            .put("eis", eisEnabled)
            .put("roi_percent", roiPercent).put("display_zoom",zoom)
            .apply { processing.forEach { (key, value) -> put(key,value) } }

    fun isRecording(): Boolean = recording

    fun capture(payload: JSONObject): JSONObject {
        val captureId = payload.optString("capture_id")
        if (captureId.isNotEmpty() && !captureId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
            return JSONObject().put("ok", false).put("error", "Invalid capture ID")
        }

        if (recording || videoEncoder != null) return JSONObject().put("ok", false).put("error", "Video recording is active")
        if (cameraDevice == null || session == null || jpegReader == null) {
            return JSONObject().put("ok", false).put("error", "Camera not ready")
        }
        val wantsRaw = payload.optBoolean("raw", false)
        val wantsJpeg = payload.optBoolean("jpeg", true)
        if (!wantsRaw && !wantsJpeg) return JSONObject().put("ok", false).put("error", "Select JPEG or RAW")
        if (wantsRaw && !rawSessionAvailable) {
            return JSONObject().put("ok", false).put("error", "RAW is not available in this camera session")
        }
        val count = payload.optInt("burst_count", 1)
        val maximum = payload.optBoolean("maximum_jpeg", false)
        if (maximum && (maximumJpegSize == null || count != 1 || wantsRaw || !wantsJpeg)) {
            return JSONObject().put("ok",false).put("error","Maximum sensor mode requires a supported single JPEG capture")
        }
        if (count !in 1..16 || (count > 1 && (wantsRaw || !capabilities().optBoolean("burst_capture")))) {
            return JSONObject().put("ok", false).put("error", "Native burst requires JPEG, BURST_CAPTURE and 2–16 frames")
        }
        synchronized(captureLock) {
            if (pendingCapture != null) {
                if (captureId.isNotEmpty() && pendingCapture?.captureId == captureId) {
                    return JSONObject().put("ok", true).put("status", "capture_in_progress")
                }
                return JSONObject().put("ok", false).put("error", "A capture is already in progress")
            }
            if (captureId.isNotEmpty()) {
                val saved = runCatching { JSONObject(receiptFile(captureId).readFully().toString(Charsets.UTF_8)) }.getOrNull()
                if (saved != null) {
                    eventSink(saved)
                    return JSONObject().put("ok", true).put("status", "capture_replayed")
                }
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            pendingCapture = PendingCapture(if (captureId.isEmpty()) "TOD_$stamp" else "TOD_$captureId", wantsRaw, captureId, payload.optString("frame_type", "Light"), count, jpegDone = !wantsJpeg, rawDone = !wantsRaw)
        }
        cameraHandler.post {
            if (maximum && Build.VERSION.SDK_INT >= 31) captureMaximumJpeg()
            else performStillCapture(wantsRaw,wantsJpeg,count)
        }
        return JSONObject().put("ok", true).put("status", "capture_started").put("raw", wantsRaw)
    }

    fun startVideo(payload: JSONObject): JSONObject {
        if (recording || videoEncoder != null) return JSONObject().put("ok", false).put("error", "Video is already recording or starting")
        if (cameraDevice == null) return JSONObject().put("ok", false).put("error", "Camera not ready")
        val width = payload.optInt("width", 1920).coerceAtLeast(1)
        val height = payload.optInt("height", 1080).coerceAtLeast(1)
        val fps = payload.optInt("fps", 30).coerceIn(1, 1000)
        val highSpeed = payload.optBoolean("high_speed", false)
        val durationMs = payload.optLong("duration", 0).coerceIn(0, 86_400) * 1000
        val bitrate = payload.optInt("bitrate", if (width >= 3840) 80_000_000 else if (width >= 1920) 30_000_000 else 12_000_000)
        cameraHandler.post {
            try {
                configureRecording(width, height, fps, bitrate, highSpeed, durationMs, payload.optBoolean("rank_frames"), payload.optLong("frame_limit").coerceIn(0, 2_000_000))
            } catch (e: Exception) {
                videoEncoder?.release()
                videoEncoder = null
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
        if (!recording && videoEncoder == null) return JSONObject().put("ok", false).put("error", "Video is not recording")
        cameraHandler.post { stopRecordingInternal() }
        return JSONObject().put("ok", true).put("status", "video_stopping")
    }

    private fun configureRecording(requestedWidth: Int, requestedHeight: Int, requestedFps: Int, bitrate: Int, highSpeed: Boolean, durationMs: Long, rankFrames: Boolean, frameLimit: Long) {
        val camera = cameraDevice ?: return
        val chars = characteristics ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        check(!recording && videoEncoder == null) { "Video is already starting or recording" }
        check(synchronized(captureLock) { pendingCapture == null }) { "A still capture is in progress" }
        val size = Size(requestedWidth, requestedHeight)
        val fps = requestedFps
        val range = Range(fps, fps)
        if (highSpeed) {
            require(map.highSpeedVideoSizes.contains(size) &&
                map.getHighSpeedVideoFpsRangesFor(size).contains(range)) { "Unsupported high-speed video mode" }
        } else {
            require(map.getOutputSizes(MediaCodec::class.java)?.contains(size) == true &&
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.contains(range) == true &&
                ImagingPolicy.standardVideoTiming(fps,map.getOutputMinFrameDuration(MediaCodec::class.java, size))) {
                "Unsupported video resolution or FPS"
            }
        }
        repeatingBuilder = null
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { videoEncoder?.release() } catch (_: Exception) {}
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val out = File(captureDir(), "TOD_VIDEO_$stamp.mp4")
        val recorder = SurfaceVideoEncoder(out, size.width, size.height, fps, bitrate)
        videoEncoder = recorder
        recordingFile = out
        val texture = textureView.surfaceTexture ?: throw IllegalStateException("Preview surface is not ready")
        if (highSpeed) texture.setDefaultBufferSize(size.width, size.height)
        else texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        if (previewSurface == null) previewSurface = Surface(texture)
        val outputs = mutableListOf<Surface>()
        previewSurface?.let(outputs::add)
        if (!highSpeed) previewReader?.surface?.let(outputs::add)
        val encoderSurface = recorder.surface
        outputs.add(encoderSurface)
        videoFPS = fps
        videoFailures = 0
        videoFrames = 0
        val cameraTimes = FrameTimes()
        videoCameraTimes = cameraTimes
        val lost = mutableSetOf<Long>()
        videoLostFrames = lost
        videoRank = rankFrames
        var lastReport = 0L
        val counters = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                if (!recording || videoEncoder !== recorder) return
                videoFrames++
                if (timestamp - lastReport >= 1_000_000_000L) {
                    lastReport = timestamp
                    eventSink(JSONObject().put("event", "video_progress").put("path", out.absolutePath)
                        .put("camera_frames_started", videoFrames).put("camera_failures", videoFailures))
                }
                if (frameLimit > 0L && videoFrames == frameLimit) {
                    cameraHandler.post { if (videoEncoder === recorder) stopRecordingInternal() }
                }
            }
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (!recording || videoEncoder !== recorder) return
                readEffective(result)
                result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)?.let {
                    cameraTimes.add(it / 1000, result.frameNumber)
                }
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                if (recording && videoEncoder === recorder) videoFailures++
            }
            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                if (target == encoderSurface && recording && videoEncoder === recorder) {
                    if (lost.add(frameNumber)) videoFailures++
                }
            }
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                try {
                    if (!started || videoEncoder !== recorder) {
                        captureSession.close()
                        return
                    }
                    session = captureSession
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    previewSurface?.let(builder::addTarget)
                    if (!highSpeed) previewReader?.surface?.let(builder::addTarget)
                    builder.addTarget(encoderSurface)
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                    if (captureSession is CameraConstrainedHighSpeedCaptureSession) {
                        val requests = captureSession.createHighSpeedRequestList(builder.build())
                        captureSession.setRepeatingBurst(requests, counters, cameraHandler)
                    } else {
                        applyControls(builder, captureROI = true, videoFPS = fps)
                        captureSession.setRepeatingRequest(builder.build(), counters, cameraHandler)
                    }
                    recorder.start()
                    recording = true
                    if (durationMs > 0) {
                        val timer = Runnable { if (videoEncoder === recorder) stopRecordingInternal() }
                        recordingTimer = timer
                        cameraHandler.postDelayed(timer, durationMs)
                    }
                    statusSink("Recording ${size.width}×${size.height} @ ${fps} fps")
                    eventSink(JSONObject().put("event", "video_started").put("path", out.absolutePath).put("width", size.width).put("height", size.height).put("fps", fps))
                } catch (e: Exception) {
                    eventSink(JSONObject().put("event", "capture_error").put("message", "Video start failed: ${e.message}"))
                    try { recorder.release() } catch (_: Exception) {}
                    videoEncoder = null
                    recordingFile = null
                    recording = false
                    configureReadersAndSession(includeRaw = hardwareRawSupported && rawSize != null)
                }
            }
            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                captureSession.close()
                eventSink(JSONObject().put("event", "capture_error").put("message", "Video session configuration failed"))
                try { recorder.release() } catch (_: Exception) {}
                videoEncoder = null
                recordingFile = null
                recording = false
                configureReadersAndSession(includeRaw = hardwareRawSupported && rawSize != null)
            }
        }
        if (highSpeed) camera.createConstrainedHighSpeedCaptureSession(outputs, callback, cameraHandler)
        else camera.createCaptureSession(outputs, callback, cameraHandler)
    }

    private fun stopRecordingInternal() {
        recordingTimer?.let(cameraHandler::removeCallbacks)
        recordingTimer = null
        val recorder = videoEncoder
        val file = recordingFile
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        var ok = false
        if (recorder != null) {
            try { recorder.stop(); ok = true } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
        }
        videoEncoder = null
        recordingFile = null
        recording = false
        if (ok && file != null && file.isFile && file.length() > 0) {
            eventSink(JSONObject().put("event", "capture_saved").put("files", JSONArray().put(file.absolutePath)).put("kind", "video"))
            val fps = videoFPS
            val failures = videoFailures
            val rank = videoRank
            val frames = videoFrames
            val cameraTimes = videoCameraTimes
            val encoderTimes = recorder?.frameTimes
            val missing = if (encoderTimes?.complete == true && cameraTimes.complete)
                missingEncoderFrames(cameraTimes.sorted(videoLostFrames), encoderTimes.sorted()) else null
            if (started && !videoReview.isShutdown) runCatching {
                videoReview.execute {
                    try {
                        val report = reviewVideo(file, fps, failures, frames, rank)
                        if (missing != null) report.put("encoder_missing_frames", missing)
                        if (!Thread.currentThread().isInterrupted) {
                            report.put("event", "video_review").put("path", file.absolutePath)
                            eventSink(report)
                        }
                    } catch (e: Exception) {
                        eventSink(JSONObject().put("event", "warning").put("message", "Video review failed: ${e.message}"))
                    }
                }
            }
        } else if (file != null) {
            try { file.delete() } catch (_: Exception) {}
            eventSink(JSONObject().put("event", "capture_error").put("message", "Video recording did not finalize correctly"))
        }
        if (started) configureReadersAndSession(includeRaw = hardwareRawSupported && rawSize != null)
    }

    private fun chooseCamera() {
        val accessible = cameraManager.cameraIdList.associateWith { cameraManager.getCameraCharacteristics(it) }
        val inventory = accessible.toMutableMap()
        accessible.values.flatMap { it.physicalCameraIds }.distinct().forEach { id ->
            if (id !in inventory) runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()?.let { inventory[id] = it }
        }
        fun lens(id: String, c: CameraCharacteristics): RearLens {
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmptyInts()
            return RearLens(id,(active?.width()?.toLong() ?: 0L)*(active?.height() ?: 0),
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble() ?: 0.0,
                sensor?.width?.toDouble() ?: 0.0,sensor?.height?.toDouble() ?: 0.0,
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) == true,
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
        }
        val rear = accessible.filterValues { it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val representatives = rear.mapValues { (id, c) ->
            ImagingPolicy.chooseMain(c.physicalCameraIds.mapNotNull { child -> inventory[child]?.let { lens(child,it) } }) ?: lens(id,c)
        }
        val best = ImagingPolicy.chooseMain(representatives.values.toList()) ?: error("No usable rear camera")
        val id = representatives.entries.filter { it.value.id == best.id }
            .maxWithOrNull(compareBy<Map.Entry<String, RearLens>> {
                accessible.getValue(it.key).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            }.thenBy { it.key == best.id })!!.key
        val selected = accessible.getValue(id)
        mainPhysicalId = best.id.takeIf { it != id || inventory.values.any { c -> it in c.physicalCameraIds } }
        cameraInventory = JSONArray().apply {
            inventory.forEach { (entry, c) -> put(describeCamera(entry,c,entry in accessible,lens(entry,c))) }
            accessible.values.flatMap { it.physicalCameraIds }.distinct().filterNot { it in inventory }.forEach {
                put(JSONObject().put("id",it).put("metadata_available",false))
            }
        }
        val map = selected.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No stream map")
        previewSize = choosePreviewSize(map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty())
        standardJpegSize = ImagingPolicy.largestSize(map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty(),Size::getWidth,Size::getHeight)
            ?: error("No native JPEG output")
        jpegSize = ImagingPolicy.largestSize(map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty() + standardJpegSize, Size::getWidth,Size::getHeight)!!
        maximumJpegSize = maximumJpeg(selected)
        hardwareRawSupported = selected.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        rawSize = if (hardwareRawSupported) ImagingPolicy.largestSize(map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty(), Size::getWidth,Size::getHeight) else null
        cameraId = id
        characteristics = selected
        setControls(JSONObject().put("iso",iso).put("exposure_us",exposureTimeNs/1000.0))
        statusSink("Rear camera $id • Preview ${previewSize.width}×${previewSize.height} • JPEG ${jpegSize.width}×${jpegSize.height}")
    }

    private fun describeCamera(id: String, c: CameraCharacteristics, openable: Boolean, lens: RearLens): JSONObject {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun sizes(format: Int) = JSONArray((map?.getOutputSizes(format)?.toList().orEmpty() +
            map?.getHighResolutionOutputSizes(format)?.toList().orEmpty()).distinct()
            .sortedByDescending { it.width.toLong()*it.height }.map { it.toString() })
        val highSpeed = JSONArray()
        runCatching { map?.highSpeedVideoSizes?.forEach { sz ->
            map.getHighSpeedVideoFpsRangesFor(sz).forEach { highSpeed.put("$sz @ ${it.lower}–${it.upper} fps") }
        } }
        val ratio = zoomRange(c)
        return JSONObject().put("id",id).put("openable",openable).put("facing",c.get(CameraCharacteristics.LENS_FACING))
            .put("physical_ids",JSONArray(c.physicalCameraIds.toList()))
            .put("active_array",c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.flattenToString())
            .put("pixel_array",c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString())
            .put("focal_lengths_mm",JSONArray(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()))
            .put("apertures",JSONArray(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty()))
            .put("estimated_equivalent_mm",lens.equivalentMm ?: JSONObject.NULL)
            .put("raw",lens.raw).put("manual",lens.manual)
            .put("maximum_pixel_array", if (Build.VERSION.SDK_INT >= 31) c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.toString() else null)
            .put("maximum_jpeg", maximumJpeg(c)?.toString())
            .put("jpeg_sizes",sizes(ImageFormat.JPEG)).put("raw_sizes",sizes(ImageFormat.RAW_SENSOR))
            .put("yuv_sizes",sizes(ImageFormat.YUV_420_888))
            .put("video_sizes",JSONArray(map?.getOutputSizes(MediaCodec::class.java)?.map { it.toString() }.orEmpty()))
            .put("fps_ranges",JSONArray(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.toString() }.orEmpty()))
            .put("high_speed_modes",highSpeed)
            .put("zoom_ratio_range",ratio?.let { JSONArray(listOf(it.lower,it.upper)) } ?: JSONObject.NULL)
            .put("max_digital_zoom",c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
            .put("crop_type",c.get(CameraCharacteristics.SCALER_CROPPING_TYPE)?.let {
                if (it == CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM) "freeform" else "center_only"
            })
            .put("ois_modes",JSONArray(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty()))
            .put("eis_modes",JSONArray(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty()))
    }

    private fun IntArray?.orEmptyInts() = this ?: intArrayOf()

    private fun zoomRange(c: CameraCharacteristics?): Range<Float>? =
        if (Build.VERSION.SDK_INT >= 30 && c?.availableCaptureRequestKeys?.contains(CaptureRequest.CONTROL_ZOOM_RATIO) == true)
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null

    private fun maximumJpeg(c: CameraCharacteristics): Size? {
        if (Build.VERSION.SDK_INT < 31 ||
            c.availableCaptureRequestKeys?.contains(CaptureRequest.SENSOR_PIXEL_MODE) != true ||
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
            ) != true) return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) ?: return null
        return ImagingPolicy.largestSize(map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty() +
            map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty(),Size::getWidth,Size::getHeight)
    }

    @android.annotation.TargetApi(31)
    private fun captureMaximumJpeg() {
        val camera = cameraDevice ?: return failPending("Camera unavailable")
        val size = maximumJpegSize ?: return failPending("Maximum JPEG is not available")
        maximumCapture = true
        try {
            session?.close()
            session = null
            repeatingBuilder = null
            previewReader?.close(); previewReader = null
            rawReader?.close(); rawReader = null
            jpegReader?.close()
            jpegReader = ImageReader.newInstance(size.width,size.height,ImageFormat.JPEG,2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    try { image.use(::saveJpeg) }
                    catch (e: Exception) { failPending("Maximum JPEG save failed: ${e.message}") }
                },cameraHandler)
            }
            val target = jpegReader!!.surface
            val output = OutputConfiguration(target).apply {
                addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            val callback = object: CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (!started || !maximumCapture) { configured.close(); return }
                    session = configured
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        applyControls(request, maximumSensor = true)
                        request.set(CaptureRequest.JPEG_QUALITY,100.toByte())
                        request.set(CaptureRequest.SENSOR_PIXEL_MODE,CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                        request.addTarget(target)
                        configured.capture(request.build(),object: CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(captureSession: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                                if (maximumCapture && session === captureSession) failPending("Maximum JPEG capture failed")
                            }
                            override fun onCaptureBufferLost(captureSession: CameraCaptureSession, request: CaptureRequest, surface: Surface, frameNumber: Long) {
                                if (maximumCapture && session === captureSession) failPending("Maximum JPEG output was lost")
                            }
                        },cameraHandler)
                    } catch (e: Exception) { failPending("Maximum JPEG request failed: ${e.message}") }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (maximumCapture) failPending("Camera rejected the maximum JPEG stream")
                }
            }
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,listOf(output),
                { command -> cameraHandler.post(command) },callback))
            val capture = synchronized(captureLock) { pendingCapture }
            cameraHandler.postDelayed({
                if (maximumCapture && synchronized(captureLock) { pendingCapture === capture }) failPending("Maximum JPEG timed out")
            },maxOf(15_000L,exposureTimeNs/1_000_000L+8_000L))
        } catch (e: Exception) { failPending("Maximum JPEG unavailable: ${e.message}") }
    }

    private fun restoreAfterMaximum() {
        if (!maximumCapture) return
        maximumCapture = false
        cameraHandler.post { if (started && cameraDevice != null) configureReadersAndSession(hardwareRawSupported && rawSize != null) }
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

    private fun recoverCamera(message: String) {
        statusSink("$message • retrying")
        if (synchronized(captureLock) { pendingCapture != null }) failPending(message)
        if (recording || videoEncoder != null) stopRecordingInternal()
        session?.close()
        session = null
        repeatingBuilder = null
        rawSessionAvailable = false
        val retry = object : Runnable {
            override fun run() {
                if (!started || cameraDevice != null) return
                try { openCamera() }
                catch (e: Exception) {
                    statusSink("Camera unavailable: ${e.message} • retrying")
                    cameraHandler.postDelayed(this, 3000)
                }
            }
        }
        cameraHandler.postDelayed(retry, 3000)
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
                        if (previewFrames.incrementAndGet() % 4L == 1L) previewHistogram = sampleHistogram(image)
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
                eventSink(JSONObject().put("event","camera_changed"))
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                captureSession.close()
                if (jpegSize != standardJpegSize) {
                    jpegSize = standardJpegSize
                    statusSink("Largest JPEG stream cannot coexist with this preview; using standard native JPEG")
                    configureReadersAndSession(includeRaw)
                } else if (includeRaw) {
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
        captureSession.setRepeatingRequest(builder.build(), effectiveCallback, cameraHandler)
    }

    private fun applyRepeatingControls() {
        val builder = repeatingBuilder ?: return
        val captureSession = session ?: return
        try {
            applyControls(builder)
            captureSession.setRepeatingRequest(builder.build(), effectiveCallback, cameraHandler)
        } catch (e: Exception) {
            statusSink("Control update error: ${e.message}")
        }
    }

    private val processing = java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val processingRequests = mapOf(
        "noise_reduction" to CaptureRequest.NOISE_REDUCTION_MODE, "edge" to CaptureRequest.EDGE_MODE,
        "hot_pixel" to CaptureRequest.HOT_PIXEL_MODE, "tonemap" to CaptureRequest.TONEMAP_MODE,
        "aberration" to CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE
    )
    private fun processingModes(c: CameraCharacteristics?): Map<String,IntArray> {
        if (c == null) return emptyMap()
        return mapOf(
            "noise_reduction" to c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            "edge" to c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            "hot_pixel" to c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
            "tonemap" to c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.filter { it == 1 || it == 2 }?.toIntArray(),
            "aberration" to c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        ).mapNotNull { (name, values) ->
            if (values == null || processingRequests[name] !in c.availableCaptureRequestKeys.orEmpty()) null else name to values
        }.toMap()
    }

    private var lastMeasured = 0L
    private fun readEffective(result: TotalCaptureResult) {
        val now = System.nanoTime()
        if (now-lastMeasured < 200_000_000L) return
        lastMeasured = now
        if (fixedColor == null && result.get(CaptureResult.CONTROL_AWB_MODE) == CaptureRequest.CONTROL_AWB_MODE_AUTO) {
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val matrix = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            if (gains != null && matrix != null) measuredColor = FixedColor(gains,matrix)
        }
        effective = JSONObject().put("fixed_color_ready",measuredColor != null)
            .put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY) ?: JSONObject.NULL)
            .put("exposure_us",result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1000.0) ?: JSONObject.NULL)
            .put("focus_distance",result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: JSONObject.NULL)
            .put("ois",result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) ?: JSONObject.NULL)
            .put("eis",result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE) ?: JSONObject.NULL)
            .apply { if (Build.VERSION.SDK_INT >= 29) put("active_physical_id",result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: JSONObject.NULL) }
    }

    private val effectiveCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = readEffective(result)
    }

    private fun applyControls(builder: CaptureRequest.Builder, captureROI: Boolean = false, videoFPS: Int = 0, maximumSensor: Boolean = false) {
        if (manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            val shutter = exposureTimeNs
            val period = if (videoFPS > 0) 1_000_000_000L / videoFPS else 0L
            val minimum = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.lower ?: 1L
            val effectiveShutter = if (period > 0) shutter.coerceAtMost(period).coerceAtLeast(minimum) else shutter
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,effectiveShutter)
            if (period > 0 && characteristics?.availableCaptureRequestKeys?.contains(CaptureRequest.SENSOR_FRAME_DURATION)==true)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION,maxOf(period,effectiveShutter))
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        val chars = characteristics
        val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (manualFocus && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
        val color = fixedColor
        if (color != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS,color.gains)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,color.matrix)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE,awbMode)
            if (chars?.let(::supportsFixedColor)==true) builder.set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        }
        val active = if (maximumSensor && Build.VERSION.SDK_INT >= 31) chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            else chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (active != null && chars != null) {
            val range = zoomRange(chars)
            val framing = ImagingPolicy.captureFraming(Crop(active.left,active.top,active.right,active.bottom),roiPercent,
                chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble() ?: 1.0,
                range?.upper?.toDouble(),captureROI)
            if (Build.VERSION.SDK_INT >= 30 && range != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO,framing.ratio.toFloat().coerceIn(range.lower,range.upper))
            }
            val region = framing.crop
            builder.set(CaptureRequest.SCALER_CROP_REGION,Rect(region.left,region.top,region.right,region.bottom))
        }
        processing.forEach { (name,value) -> processingRequests[name]?.let { builder.set(it,value) } }
        try { builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLock) } catch (_: Exception) {}
        try { builder.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock) } catch (_: Exception) {}
        try { builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if (eisEnabled) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) } catch (_: Exception) {}
        try { builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if (oisEnabled) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) } catch (_: Exception) {}
    }

    private fun performStillCapture(wantsRaw: Boolean, wantsJpeg: Boolean, count: Int) {
        val camera = cameraDevice ?: return failPending("Camera unavailable")
        val captureSession = session ?: return failPending("Session unavailable")
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            if (wantsJpeg) jpegReader?.surface?.let(builder::addTarget)
            if (wantsRaw) rawReader?.surface?.let(builder::addTarget)
            applyControls(builder)
            if (wantsJpeg) {
                builder.set(CaptureRequest.JPEG_QUALITY,100.toByte())
                builder.set(CaptureRequest.JPEG_ORIENTATION,0)
            }
            val request = builder.build()
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (wantsRaw) {
                        synchronized(captureLock) { pendingRawResult = result }
                        tryWriteRaw()
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    if (session !== this@CameraController.session) return
                    failPending("Capture failed: ${failure.reason}")
                    if (count > 1 && started) configureReadersAndSession(includeRaw = hardwareRawSupported)
                }

                override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                    if (session !== this@CameraController.session) return
                    failPending("Camera lost an output buffer")
                    if (started) configureReadersAndSession(includeRaw = hardwareRawSupported)
                }
            }
            if (count == 1) captureSession.capture(request, callback, cameraHandler)
            else captureSession.captureBurst(List(count) { request }, callback, cameraHandler)
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
        val suffix = if (pending.count > 1) "_${pending.burstFiles.size + 1}" else ""
        val file = File(captureDir(), "${pending.baseName}$suffix.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        synchronized(captureLock) {
            pending.burstFiles.add(file.absolutePath)
            pending.jpegDone = pending.burstFiles.size == pending.count
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
            if (p != null && p.jpegDone && p.rawDone) p else null
        }
        if (done != null) {
            val files = JSONArray()
            done.burstFiles.forEach(files::put)
            done.rawPath?.let(files::put)
            val event = JSONObject().put("event", "capture_saved").put("files", files)
                .put("raw_requested", done.wantsRaw).put("capture_id", done.captureId).put("frame_type", done.frameType)
            if (done.captureId.isNotEmpty()) {
                try {
                    val receipt = receiptFile(done.captureId)
                    val output = receipt.startWrite()
                    try {
                        output.write(event.toString().toByteArray(Charsets.UTF_8))
                        receipt.finishWrite(output)
                    } catch (e: Exception) {
                        receipt.failWrite(output)
                        throw e
                    }
                } catch (e: Exception) {
                    failPending("Capture receipt failed: ${e.message}")
                    return
                }
            }
            synchronized(captureLock) { if (pendingCapture === done) pendingCapture = null }
            eventSink(event)
            restoreAfterMaximum()
        }
    }

    private fun receiptFile(id: String): android.util.AtomicFile {
        val directory = File(activity.filesDir, "capture-receipts")
        return android.util.AtomicFile(File(directory, "$id.json"))
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
        restoreAfterMaximum()
    }

    private fun sampleHistogram(image: Image): IntArray {
        val plane = image.planes[0]
        val bins = IntArray(64)
        for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) {
            val i = y * plane.rowStride + x * plane.pixelStride
            if (i < plane.buffer.limit()) bins[(plane.buffer.get(i).toInt() and 255) / 4]++
        }
        return bins
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
