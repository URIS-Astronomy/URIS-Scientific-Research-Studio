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

class MainActivity : Activity(), TextureView.SurfaceTextureListener {
    companion object { private const val CAMERA_PERMISSION_REQUEST = 1001 }

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var telemetry: TextView
    private lateinit var cameraBadge: TextView
    private lateinit var histogram: HistogramView
    private lateinit var previewFramesLabel: TextView
    @Volatile private var cameraController: CameraController? = null
    @Volatile private var astroServer: AstroServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private var exiting = false

    private val telemetryTask = object : Runnable {
        override fun run() {
            if (exiting) return
            val t = buildTelemetry()
            val camera = cameraController
            histogram.update(camera?.previewHistogram ?: IntArray(0))
            previewFramesLabel.text = "Preview frames ${camera?.previewFrames?.get() ?: 0}"
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
        status = findViewById(R.id.status)
        connection = findViewById(R.id.connection)
        telemetry = findViewById(R.id.telemetry)
        cameraBadge = findViewById(R.id.cameraBadge)
        preview.surfaceTextureListener = this
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

            override fun setControls(payload: JSONObject): JSONObject = cameraController?.setControls(payload)
                ?: JSONObject().put("ok", false).put("error", "Camera not ready")

            override fun capture(payload: JSONObject): JSONObject = cameraController?.capture(payload)
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

    private fun startCamera() {
        if (cameraController != null) return
        cameraController = CameraController(
            activity = this,
            textureView = preview,
            frameSink = { frame -> astroServer?.offerPreviewFrame(frame) },
            eventSink = { event ->
                astroServer?.sendEvent(event)
                when (event.optString("event")) {
                    "capture_saved" -> runOnUiThread { status.text = "Capture saved" }
                    "capture_error" -> runOnUiThread { status.text = event.optString("message") }
                }
            },
            statusSink = { text -> runOnUiThread { status.text = text; cameraBadge.text = text } }
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
                val settings = JSONObject().put("roi_percent", preset.crop)
                if (manual && preset.iso != null && preset.exposureUs != null) {
                    settings.put("manual", true).put("iso", preset.iso).put("exposure_us", preset.exposureUs)
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
        toggle("Manual focus", "manual_focus", caps.optDouble("min_focus_distance") > 0)
        number("Focus (diopters, 0 = infinity)", "focus_distance", caps.optDouble("min_focus_distance") > 0, true)
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
                Regex("""(\d+)×(\d+) @ (\d+)(?:-\d+)? fps""").matchEntire(values?.optString(i).orEmpty())
                    ?.let { it to highSpeed }
            }
        }
        if (modes.isNotEmpty()) actions["Start video"] = {
            AlertDialog.Builder(this).setTitle("Video mode")
                .setItems(modes.map { (match, highSpeed) -> match.value + if (highSpeed) " • High speed" else "" }.toTypedArray()) { _, i ->
                    val (match, highSpeed) = modes[i]
                    val (_, width, height, fps) = match.groupValues
                    val request = JSONObject().put("width", width.toInt()).put("height", height.toInt())
                        .put("fps", fps.toInt()).put("high_speed", highSpeed)
                    showCommandResult(camera.startVideo(request), "Starting video…")
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
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
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
        super.onDestroy()
    }
}
