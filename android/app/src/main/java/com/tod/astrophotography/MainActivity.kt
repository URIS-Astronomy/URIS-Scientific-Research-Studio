package com.tod.astrophotography

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.view.TextureView
import android.hardware.display.DisplayManager
import android.graphics.ImageDecoder
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.widget.SeekBar
import android.view.WindowManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.text.InputType
import android.widget.TextView
import com.tod.astrophotography.camera.CameraController
import com.tod.astrophotography.network.AstroServer
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : Activity(), TextureView.SurfaceTextureListener {
    companion object { private const val CAMERA_PERMISSION_REQUEST = 1001 }

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var telemetry: TextView
    private lateinit var cameraBadge: TextView
    private lateinit var histogram: HistogramView
    private lateinit var previewFramesLabel: TextView
    private lateinit var zoomSlider: SeekBar
    private lateinit var zoomLabel: TextView
    private var zoomRatio = 1.0
    @Volatile private var cameraController: CameraController? = null
    @Volatile private var astroServer: AstroServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private var lastJpeg: File? = null
    private var lastJpegLabel = ""
    private var marker: android.view.View? = null
    private val photoReview = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val displayListener = object: DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (preview.display?.displayId == displayId) {
                marker?.visibility = android.view.View.GONE
                cameraController?.refreshTransform()
            }
        }
    }
    private var exiting = false

    private val telemetryTask = object : Runnable {
        override fun run() {
            if (exiting) return
            val t = buildTelemetry()
            val camera = cameraController
            camera?.setThermalStatus(t.optInt("thermal_status"))
            histogram.update(camera?.previewHistogram ?: IntArray(0))
            val measured = camera?.measured()
            val diag = camera?.diagnostics()
            val actualFps = camera?.previewFPS() ?: 0.0
            val available = diag?.optBoolean("tap_focus") == true
            findViewById<Button>(R.id.focusScene).isEnabled = available
            val main = when(diag?.opt("main_rear")) { true -> "main rear"; false -> "other rear"; else -> "physical lens unreported" }
            if (diag != null) cameraBadge.text = "${diag.optString("profile_name")} • camera ${diag.optString("camera_id")}" +
                " / ${diag.optString("active_physical_id").replace("null","—")} • $main" +
                "\nPreview ${diag.optInt("preview_width")}×${diag.optInt("preview_height")} • ${"%.1f".format(Locale.US,actualFps)} FPS" +
                "\nJPEG ${diag.optInt("jpeg_width")}×${diag.optInt("jpeg_height")} • sensor ${diag.optInt("sensor_orientation")}° / display ${diag.optInt("display_rotation")}°"
            previewFramesLabel.text = "Preview frames ${camera?.previewFrames?.get() ?: 0}" +
                if ((measured?.optLong("exposure_us",0L) ?: 0L) > 0) " • ISO ${measured?.optInt("iso")} • ${exposureLabel(measured?.optDouble("exposure_us") ?: 0.0)}" else ""
            if (diag != null) previewFramesLabel.append(" • ${diag.optString("focus")} " +
                (measured?.optDouble("focus_distance")?.takeIf { it.isFinite() }?.let { " %.2f D".format(Locale.US,it) } ?: "") +
                " • OIS ${measured?.optString("ois","—")} / EIS ${measured?.optString("eis","—")}")
            telemetry.text = "Battery ${t.optInt("battery_percent", 0)}%${if (t.optBoolean("charging")) " ⚡" else ""} • Temp ${"%.1f".format(t.optDouble("temperature_c", 0.0))}°C • Free ${"%.1f".format(t.optDouble("free_storage_gb", 0.0))} GB"
            astroServer?.sendEvent(JSONObject(t.toString()).put("event", "telemetry"))
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TODAstroPhotography:CaptureSession").apply {
            setReferenceCounted(false)
            acquire()
        }

        histogram = findViewById(R.id.histogram)
        previewFramesLabel = findViewById(R.id.previewFrames)
        preview = findViewById(R.id.preview)
        zoomSlider = findViewById(R.id.displayZoom)
        zoomLabel = findViewById(R.id.zoomLabel)
        setupDisplayZoom()
        status = findViewById(R.id.status)
        connection = findViewById(R.id.connection)
        telemetry = findViewById(R.id.telemetry)
        cameraBadge = findViewById(R.id.cameraBadge)
        preview.surfaceTextureListener = this
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener,ui)
        findViewById<Button>(R.id.validationJpeg).setOnClickListener {
            val camera = cameraController ?: return@setOnClickListener
            val result = camera.validationJpeg()
            if (result.optBoolean("ok")) marker?.visibility = android.view.View.GONE
            showCommandResult(result,"Capturing native validation JPEG…")
        }
        findViewById<Button>(R.id.previewQuality).setOnClickListener { showPreviewQuality() }
        findViewById<Button>(R.id.reviewJpeg).setOnClickListener { showSavedJpeg() }
        findViewById<Button>(R.id.focusScene).setOnClickListener {
            if (cameraController?.focusScene()==true) {
                val frame=findViewById<android.view.View>(R.id.previewFrame)
                showFocusMarker(frame.width/2f,frame.height/2f)
                status.text="Focusing on the center"
            }
        }
        findViewById<Button>(R.id.exitButton).setOnClickListener { confirmExit() }

        findViewById<Button>(R.id.cameraControls).setOnClickListener { showCameraControls() }
        findViewById<Button>(R.id.captureButton).setOnClickListener { showCaptureOptions() }
        findViewById<Button>(R.id.capturePreset).setOnClickListener { showCapturePresets() }

        val preferences = getSharedPreferences("connection", MODE_PRIVATE)
        val deviceId = preferences.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }
        astroServer = AstroServer(object : AstroServer.CommandHandler {
            override fun capabilities(): JSONObject = cameraController?.capabilities()
                ?: JSONObject().put("ok", false).put("error", "Camera not ready")

            override fun setControls(payload: JSONObject): JSONObject = cameraController?.setRemoteControls(payload)
                ?: JSONObject().put("ok", false).put("error", "Camera not ready")

            override fun capture(payload: JSONObject): JSONObject = cameraController?.capture(payload)
                ?: JSONObject().put("ok", false).put("error", "Camera not ready")

            override fun cancelCapture(payload: JSONObject): JSONObject = cameraController?.cancelCapture(payload)
                ?: JSONObject().put("ok", false).put("error", "Camera not ready")

            override fun startVideo(payload: JSONObject): JSONObject = cameraController?.startVideo(payload)
                ?: JSONObject().put("ok", false).put("error", "Camera not ready")

            override fun stopVideo(): JSONObject = cameraController?.stopVideo()
                ?: JSONObject().put("ok", false).put("error", "Camera not ready")

            override fun ping(): JSONObject = JSONObject().put("ok", true).put("status", "alive")

            override fun telemetry(): JSONObject = buildTelemetry()

            override fun isRecording(): Boolean = cameraController?.isRecording() == true

            override fun resolveCapturedFile(path: String): File? {
                if (path.isBlank()) return null
                return try {
                    val base = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                    val captureRoot = File(base, "Captures").canonicalFile
                    val requested = File(path).canonicalFile
                    if (requested.isFile && requested.path.startsWith(captureRoot.path + File.separator)) requested else null
                } catch (_: Exception) { null }
            }
        }, deviceId) { transport ->
            runOnUiThread {
                connection.text = when (transport) {
                    "USB" -> "USB DEBUGGING AUTHORIZED · PC CONNECTED"
                    "LAN" -> "LAN · PC CONNECTED"
                    else -> "PC OFFLINE"
                }
                connection.setTextColor(getColor(if (transport != null) R.color.green else R.color.amber))
            }
        }.also { it.start() }

        ui.post(telemetryTask)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        } else if (preview.isAvailable) {
            startCamera()
        }
    }

    private fun exposureLabel(us: Double): String {
        if (!us.isFinite() || us<=0) return "—"
        val seconds=us/1_000_000
        val descriptor=if(seconds>=.25) "%.2f s".format(Locale.US,seconds)
            else "1/${kotlin.math.round(1/seconds).toLong()} s"
        return "%.1f µs (%s)".format(Locale.US,us,descriptor)
    }

    private fun showPreviewQuality() {
        val camera = cameraController ?: return
        val presets = com.tod.astrophotography.camera.ValidationPolicy.Preview.entries
        AlertDialog.Builder(this).setTitle("Preview quality")
            .setSingleChoiceItems(presets.map { it.label }.toTypedArray(),
                presets.indexOfFirst { it.name == camera.diagnostics().optString("preview_quality") }) { dialog,index ->
                showCommandResult(camera.setControls(JSONObject().put("preview_quality",presets[index].name)),"Updating preview stream…")
                dialog.dismiss()
            }.setNeutralButton("Orientation") { _, _ -> showPreviewOrientation(camera) }
            .setNegativeButton("Cancel",null).show()
    }

    private fun showPreviewOrientation(camera: CameraController) {
        val settings = camera.diagnostics().optJSONObject("preview_orientation") ?: JSONObject()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 12)
        }
        val angles = listOf(null, 0, 90, 180, 270)
        val rotation = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto", "0°", "90°", "180°", "270°"))
            setSelection(angles.indexOf(settings.optInt("rotation", -1)).coerceAtLeast(0))
        }
        val horizontal = android.widget.CheckBox(this).apply { text = "Flip horizontally"; isChecked = settings.optBoolean("flip_x") }
        val vertical = android.widget.CheckBox(this).apply { text = "Flip vertically"; isChecked = settings.optBoolean("flip_y") }
        panel.addView(rotation)
        panel.addView(horizontal)
        panel.addView(vertical)
        AlertDialog.Builder(this).setTitle("Preview orientation").setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                marker?.visibility = android.view.View.GONE
                val value = JSONObject().put("rotation", angles[rotation.selectedItemPosition] ?: JSONObject.NULL)
                    .put("flip_x", horizontal.isChecked).put("flip_y", vertical.isChecked)
                showCommandResult(camera.setControls(JSONObject().put("preview_orientation", value)), "Preview orientation updated")
            }.show()
    }

    private fun showFocusMarker(x: Float,y: Float) {
        val frame=findViewById<android.widget.FrameLayout>(R.id.previewFrame)
        val size=(52*resources.displayMetrics.density).toInt()
        val view=marker ?: android.view.View(this).apply {
            background=GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(2,0xffffcf69.toInt())
                cornerRadius=5f
            }
            frame.addView(this,android.widget.FrameLayout.LayoutParams(size,size))
            marker=this
        }
        view.translationX=x-size/2-frame.paddingLeft
        view.translationY=y-size/2-frame.paddingTop
        view.visibility=android.view.View.VISIBLE
    }

    private fun showSavedJpeg() {
        val file = lastJpeg ?: return
        val label = lastJpegLabel
        photoReview.execute {
            try {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder,info,_ ->
                    val scale=minOf(1.0,2600.0/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
                }
                runOnUiThread {
                    if (exiting || isFinishing) { bitmap.recycle();return@runOnUiThread }
                    val image=ImageView(this).apply { adjustViewBounds=true; setImageBitmap(bitmap) }
                    AlertDialog.Builder(this).setTitle(label).setView(image).setPositiveButton("Close",null)
                        .create().apply { setOnDismissListener { image.setImageDrawable(null); bitmap.recycle() } }.show()
                }
            } catch (e: Exception) { runOnUiThread { status.text="Could not review the saved JPEG" } }
        }
    }

    private fun renderZoom(value: Double) {
        zoomRatio = value
        preview.scaleX = value.toFloat()
        preview.scaleY = value.toFloat()
        zoomSlider.progress = kotlin.math.round((value-1)*100).toInt()
        zoomLabel.text = "Display %.1f×".format(Locale.US,value)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupDisplayZoom() {
        fun change(value: Double) { cameraController?.updateDisplayZoom(value) }
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) change(1.0+progress/100.0)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        val pinch = ScaleGestureDetector(this, object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                change(zoomRatio * detector.scaleFactor)
                return true
            }
        })
        val taps = GestureDetector(this, object: GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (cameraController?.tapToFocus(event.x-preview.left,event.y-preview.top)==true) {
                    showFocusMarker(event.x,event.y)
                    status.text="Focusing"
                    return true
                }
                return false
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                change(1.0)
                return true
            }
        })
        findViewById<android.view.View>(R.id.previewFrame).setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            if (!pinch.isInProgress && event.pointerCount == 1) taps.onTouchEvent(event)
            true
        }
    }

    private fun startCamera() {
        if (cameraController != null) return
        cameraController = CameraController(
            activity = this,
            textureView = preview,
            frameSink = { frame -> astroServer?.offerPreviewFrame(frame) },
            hasPreviewClient = { astroServer?.hasPreviewClient() == true },
            eventSink = { event ->
                astroServer?.sendEvent(event)
                when (event.optString("event")) {
                    "capture_saved" -> runOnUiThread {
                        status.text = "Capture saved"
                        val files=event.optJSONArray("files")
                        val photo=(0 until (files?.length() ?: 0)).mapNotNull { files?.optString(it) }
                            .firstOrNull { it.endsWith(".jpg",true) }
                        if (photo != null) {
                            lastJpeg=File(photo)
                            lastJpegLabel=event.optString("jpeg_processing","Native JPEG")+
                                " • ${event.optInt("jpeg_width")}×${event.optInt("jpeg_height")}"
                            findViewById<Button>(R.id.reviewJpeg).isEnabled=true
                            if (event.optBoolean("validation_review")) showSavedJpeg()
                        }
                    }
                    "capture_error" -> runOnUiThread { status.text = event.optString("message") }
                }
            },
            statusSink = { text -> runOnUiThread { status.text = text; cameraBadge.text = text } },
            zoomSink = { ratio -> runOnUiThread { renderZoom(ratio) } }
        ).also { it.start() }
    }

    private data class CapturePreset(val name: String, val iso: Int?, val exposureUs: Long?, val crop: Int)
    private fun showCapturePresets() {
        val camera = cameraController
        val caps = camera?.capabilities()
        if (camera == null || caps?.optBoolean("ok") != true) {
            status.text = "Camera unavailable. Open Camera controls to check readiness."
            return
        }
        val presets = listOf(
            CapturePreset("Validation / Daylight", null, null, 100),
            CapturePreset("General Manual", null, null, 100),
            CapturePreset("Planetary", 200, 5000, 50),
            CapturePreset("Moon", 100, 2000, 100),
            CapturePreset("Solar", 50, 1000, 100),
            CapturePreset("Deep Sky", 800, 1000000, 100),
            CapturePreset("EAA / Live Stack", 800, 500000, 100)
        )
        val manual = caps.optBoolean("manual_sensor")
        AlertDialog.Builder(this).setTitle("Capture preset")
            .setItems(presets.map { it.name }.toTypedArray()) { _, index ->
                val preset = presets[index]
                if (preset.name == "Solar") AlertDialog.Builder(this).setTitle("Solar imaging")
                    .setMessage("A proper telescope solar filter fitted over the front aperture is required before pointing at the Sun.")
                    .setPositiveButton("OK",null).show()
                marker?.visibility = android.view.View.GONE
                val validation=preset.name == "Validation / Daylight"
                val settings = JSONObject().put("roi_percent", preset.crop).put("profile",if(validation) "validation" else "astronomy")
                    .put("profile_name",preset.name).put("reset_profile",true)
                if (preset.name == "Planetary") {
                    settings.put("ois",false).put("eis",false)
                    val modes = caps.optJSONObject("processing_modes")
                    for (key in listOf("noise_reduction","edge","aberration")) {
                        val values = modes?.optJSONArray(key)
                        if ((0 until (values?.length() ?: 0)).any { values?.optInt(it,-1) == 0 }) settings.put(key,0)
                    }
                }
                if (caps.optDouble("min_focus_distance") > 0 && !validation && preset.name != "General Manual") settings.put("manual_focus",true).put("focus_distance",0)
                if (manual && preset.iso != null && preset.exposureUs != null) {
                    settings.put("manual", true).put("iso", if (preset.name in listOf("Moon","Solar")) caps.optInt("iso_min",preset.iso) else preset.iso)
                        .put("exposure_us", if (preset.name == "Deep Sky") caps.optLong("exposure_us_max",preset.exposureUs) else preset.exposureUs)
                }
                val suffix = when {
                    !manual -> " • Auto exposure"
                    preset.name == "EAA / Live Stack" -> " • Start stacking in TOD Astro Suite"
                    else -> ""
                }
                showCommandResult(camera.setControls(settings), preset.name + suffix)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showCameraControls() {
        val camera = cameraController
        val caps = camera?.capabilities()
        if (camera == null || caps == null || !caps.optBoolean("ok")) {
            status.text = "Camera unavailable. Waiting for a preview surface."
            if (preview.isAvailable && camera == null) startCamera()
            return
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }
        val controls = camera.controlState()
        val payload = JSONObject()
        val bind = mutableListOf<() -> Unit>()
        fun toggle(label: String, key: String, available: Boolean) {
            if (!available) return
            val check = CheckBox(this).apply { text = label; isChecked = controls.optBoolean(key) }
            form.addView(check)
            bind.add { payload.put(key, check.isChecked) }
        }
        fun number(label: String, key: String, available: Boolean, decimal: Boolean = false) {
            if (!available) return
            val field = EditText(this).apply {
                hint = label
                inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                setText(controls.optString(key))
            }
            form.addView(TextView(this).apply { text = label })
            form.addView(field)
            bind.add { field.text.toString().toDoubleOrNull()?.let { payload.put(key, it) } }
        }
        val manual = caps.optBoolean("manual_sensor")
        toggle("Manual exposure", "manual", manual)
        number("ISO (${caps.optInt("iso_min")}–${caps.optInt("iso_max")})", "iso", manual)
        number("Exposure (µs)", "exposure_us", manual)
        val maxFocus = caps.optDouble("min_focus_distance",0.0)
        if (maxFocus > 0) {
            val label = TextView(this)
            val focus = SeekBar(this).apply {
                max = 2000
                progress = (controls.optDouble("focus_distance",0.0)/maxFocus*max).toInt()
            }
            fun showFocus() { label.text = "Focus %.3f D".format(Locale.US,focus.progress*maxFocus/focus.max) }
            focus.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    showFocus()
                    if (fromUser) camera.setControls(JSONObject().put("manual_focus",true).put("focus_distance",progress*maxFocus/bar.max))
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            fun move(delta: Int?) {
                focus.progress = delta?.let { focus.progress+it } ?: 0
                camera.setControls(JSONObject().put("manual_focus",true).put("focus_distance",focus.progress*maxFocus/focus.max))
            }
            showFocus()
            form.addView(label)
            form.addView(focus)
            form.addView(LinearLayout(this).apply {
                for ((name, delta) in listOf("−" to -1, "Infinity" to null, "+" to 1)) {
                    addView(Button(this@MainActivity).apply { text = name; setOnClickListener { move(delta) } })
                }
                addView(Button(this@MainActivity).apply {
                    text = "Auto"
                    setOnClickListener { camera.setControls(JSONObject().put("manual_focus",false)) }
                })
            })
        }
        toggle("AE lock", "ae_lock", caps.optBoolean("ae_lock_supported"))
        toggle("AWB lock", "awb_lock", caps.optBoolean("awb_lock_supported"))
        number("Centered crop (%)", "roi_percent", caps.optDouble("max_zoom") > 1)
        val whiteBalance = caps.optJSONArray("awb_modes")
        val awbLabels = listOf("Off", "Auto", "Incandescent", "Fluorescent", "Warm fluorescent", "Daylight", "Cloudy daylight", "Twilight", "Shade")
        val modes = (0 until (whiteBalance?.length() ?: 0)).mapNotNull { i ->
            whiteBalance?.optInt(i)?.takeIf { it in 1 until awbLabels.size }
        }
        if (modes.size > 1) {
            form.addView(TextView(this).apply { text = "White balance" })
            val chooser = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { awbLabels[it] })
                setSelection(modes.indexOf(controls.optInt("awb_mode")).coerceAtLeast(0))
            }
            form.addView(chooser)
            bind.add { if (chooser.selectedItemPosition in modes.indices) payload.put("awb_mode", modes[chooser.selectedItemPosition]) }
        }
        AlertDialog.Builder(this).setTitle("Camera controls")
            .setView(ScrollView(this).apply { addView(form) })
            .setNeutralButton("Camera report") { _, _ ->
                AlertDialog.Builder(this).setTitle("Camera capabilities")
                    .setMessage(caps.toString(2)).setPositiveButton("Close", null).show()
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                bind.forEach { it() }
                showCommandResult(camera.setControls(payload), "Camera controls applied")
            }.show()
    }

    private fun showCaptureOptions() {
        val camera = cameraController ?: return
        val caps = camera.capabilities()
        if (!caps.optBoolean("ok")) {
            status.text = caps.optString("error", "Camera unavailable")
            return
        }
        val actions = linkedMapOf<String, () -> Unit>()
        actions["JPEG photo"] = { showCommandResult(camera.capture(JSONObject()), "Capturing JPEG…") }
        if (caps.optInt("maximum_jpeg_width") > caps.optInt("jpeg_width")) {
            val size = "${caps.optInt("maximum_jpeg_width")}×${caps.optInt("maximum_jpeg_height")}"
            actions["Maximum sensor JPEG ($size)"] = {
                showCommandResult(camera.capture(JSONObject().put("maximum_jpeg",true)), "Capturing maximum JPEG • preview pauses")
            }
        }
        if (caps.optBoolean("burst_capture") && !camera.isRecording()) {
            actions["JPEG burst"] = {
                val counts = intArrayOf(3, 5, 10, 16)
                AlertDialog.Builder(this).setTitle("Burst length")
                    .setItems(counts.map { "$it frames" }.toTypedArray()) { _, index ->
                        showCommandResult(camera.capture(JSONObject().put("burst_count", counts[index])), "Capturing burst…")
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        if (caps.optBoolean("raw_session_available")) {
            actions["RAW / DNG photo"] = {
                showCommandResult(camera.capture(JSONObject().put("raw", true).put("jpeg", false)), "Capturing RAW…")
            }
            actions["RAW + JPEG photo"] = {
                showCommandResult(camera.capture(JSONObject().put("raw", true)), "Capturing RAW + JPEG…")
            }
        }
        val modes = listOf("video_modes" to false, "high_speed_modes" to true).flatMap { (key, highSpeed) ->
            val values = caps.optJSONArray(key)
            (0 until (values?.length() ?: 0)).mapNotNull { i ->
                Regex("""(\d+)×(\d+) @ (\d+)(?:-(\d+))? fps""").matchEntire(values?.optString(i).orEmpty())
                    ?.let { it to highSpeed }
            }
        }
        if (modes.isNotEmpty()) actions["Start video"] = {
            AlertDialog.Builder(this).setTitle("Video mode")
                .setItems(modes.map { (match, highSpeed) -> match.value + if (highSpeed) " • High speed" else "" }.toTypedArray()) { _, i ->
                    val (match, highSpeed) = modes[i]
                    val (_, width, height, low, high) = match.groupValues
                    val fps = high.ifEmpty { low }
                    val request = JSONObject().put("width", width.toInt()).put("height", height.toInt())
                        .put("fps", fps.toInt()).put("high_speed", highSpeed)
                    if (highSpeed && camera.controlState().optBoolean("manual_focus")) {
                        AlertDialog.Builder(this).setTitle("Constrained high-speed video")
                            .setMessage("Camera2 may override manual focus, exposure, and crop in this mode. Use standard video to preserve manual settings.")
                            .setNegativeButton("Cancel",null)
                            .setPositiveButton("Start") { _,_ -> showCommandResult(camera.startVideo(request),"Starting video…") }.show()
                    } else showCommandResult(camera.startVideo(request), "Starting video…")
                }.setNegativeButton("Cancel", null).show()
        }
        if (camera.isRecording()) actions["Stop video"] = { showCommandResult(camera.stopVideo(), "Finalizing video…") }
        AlertDialog.Builder(this).setTitle("Capture")
            .setItems(actions.keys.toTypedArray()) { _, i -> actions.values.elementAt(i).invoke() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showCommandResult(result: JSONObject, success: String) {
        status.text = if (result.optBoolean("ok")) success else result.optString("error", "Camera operation failed")
    }

    private fun buildTelemetry(): JSONObject {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val tempTenths = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val chargingStatus = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING || chargingStatus == BatteryManager.BATTERY_STATUS_FULL
        val base = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val stat = StatFs(base.absolutePath)
        val freeGb = stat.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val plugged = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return JSONObject()
            .put("ok", true)
            .put("recording", cameraController?.isRecording() == true)
            .put("display_zoom",cameraController?.previewZoom() ?: 1.0)
            .put("effective",cameraController?.measured() ?: JSONObject())
            .put("diagnostics",cameraController?.diagnostics() ?: JSONObject())
            .put("focus_settings",cameraController?.focusSettings() ?: JSONObject())
            .put("usb_power", (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0)
            .put("preview_frames", cameraController?.previewFrames?.get() ?: 0)
            .put("battery_percent", battery)
            .put("charging", charging)
            .put("temperature_c", tempTenths / 10.0)
            .put("free_storage_gb", freeGb)
            .put("thermal_status", if (android.os.Build.VERSION.SDK_INT >= 29) (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus else 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (preview.isAvailable) startCamera()
            } else status.text = "Camera permission is required."
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { cameraController?.refreshTransform() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        cameraController?.shutdown()
        cameraController = null
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { confirmExit() }

    private fun confirmExit() {
        if (isFinishing || exiting) return
        AlertDialog.Builder(this)
            .setTitle("Exit TOD Astro Photography?")
            .setMessage("Live view and the current capture session will stop only after you confirm.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("EXIT") { _, _ -> cleanExit() }
            .setCancelable(true)
            .show()
    }

    private fun cleanExit() {
        exiting = true
        status.text = "Closing session safely…"
        ui.removeCallbacks(telemetryTask)
        astroServer?.stop(); astroServer = null
        cameraController?.shutdown(); cameraController = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finishAffinity()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        exiting = true
        ui.removeCallbacks(telemetryTask)
        astroServer?.stop(); astroServer = null
        cameraController?.shutdown(); cameraController = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        photoReview.shutdownNow()
        super.onDestroy()
    }
}
