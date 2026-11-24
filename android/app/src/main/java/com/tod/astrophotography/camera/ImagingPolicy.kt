package com.tod.astrophotography.camera

import kotlin.math.hypot
import kotlin.math.ln

internal data class RearLens(
    val id: String, val area: Long, val focalMm: Double, val widthMm: Double,
    val heightMm: Double, val ois: Boolean, val raw: Boolean, val manual: Boolean
) {
    val equivalentMm: Double?
        get() = if (focalMm > 0 && widthMm > 0 && heightMm > 0)
            focalMm * hypot(36.0, 24.0) / hypot(widthMm, heightMm) else null
}

internal data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class CaptureFraming(val ratio: Double, val crop: Crop)

internal object ImagingPolicy {
    fun chooseMain(lenses: List<RearLens>): RearLens? = lenses.maxWithOrNull(
        compareBy<RearLens> {
            when (val mm = it.equivalentMm) {
                null -> 1
                in 20.0..38.0 -> 2
                else -> if (mm < 20 || mm > 38) 0 else 1
            }
        }.thenBy { it.ois }.thenBy { it.area }.thenBy { it.raw }.thenBy { it.manual }
            .thenBy { -kotlin.math.abs(ln((it.equivalentMm ?: 26.0) / 26.0)) }
    )

    const val MAX_DISPLAY_ZOOM = 8.0
    fun displayZoom(value: Double): Double =
        if (value.isFinite()) value.coerceIn(1.0, MAX_DISPLAY_ZOOM) else 1.0

    fun captureFraming(active: Crop, percent: Int, maxDigital: Double, ratioMax: Double?, applyROI: Boolean): CaptureFraming {
        val zoom = if (applyROI) 100.0 / percent.coerceIn(25, 100) else 1.0
        if (ratioMax != null) return CaptureFraming(zoom.coerceAtMost(ratioMax.coerceAtLeast(1.0)), active)
        val scale = 1.0 / zoom.coerceAtMost(maxDigital.coerceAtLeast(1.0))
        val width = active.right - active.left
        val height = active.bottom - active.top
        val w = (width * scale).toInt().coerceIn(1, width)
        val h = (height * scale).toInt().coerceIn(1, height)
        val x = active.left + (width-w)/2
        val y = active.top + (height-h)/2
        return CaptureFraming(1.0, Crop(x,y,x+w,y+h))
    }

    fun <T> largestSize(sizes: Iterable<T>, width: (T) -> Int, height: (T) -> Int): T? =
        sizes.maxByOrNull { width(it).toLong() * height(it) }

    fun exposureNs(us: Double, current: Long, minimum: Long, maximum: Long): Long =
        if (us.isFinite()) (us * 1000).coerceIn(minimum.toDouble(),maximum.toDouble()).toLong() else current

    fun iso(requested: Int, minimum: Int, maximum: Int): Int = requested.coerceIn(minimum,maximum)

    fun focus(requested: Double, current: Float, limit: Float): Float =
        if (requested.isFinite()) requested.toFloat().coerceIn(0f,limit) else current

    fun stabilization(requested: Boolean, advertised: IntArray?): Boolean =
        requested && advertised?.contains(1) == true

    fun standardVideoTiming(fps: Int, frameDurationNs: Long): Boolean =
        fps in 1..1000 && (frameDurationNs == 0L || frameDurationNs <= 1_001_000_000L / fps)
}
