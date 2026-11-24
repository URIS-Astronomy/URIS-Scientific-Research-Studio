package com.tod.astrophotography.camera

internal object ValidationPolicy {
    const val DAYLIGHT = "validation"
    const val ASTRONOMY = "astronomy"
    const val JPEG_QUALITY = 100

    enum class Preview(val label: String, val maxLongEdge: Int, val maxPixels: Int, val fps: Int, val jpeg: Int) {
        LOW("Low latency", 960, 550_000, 20, 83),
        BALANCED("Balanced", 1920, 2_100_000, 20, 92),
        HIGH("High quality", 2560, 4_000_000, 15, 96)
    }
    data class Point(val x: Double, val y: Double)
    data class Dimensions(val width: Int, val height: Int)
    data class ManualStart(val exposureNs: Long, val iso: Int)

    fun previewFps(requested: Int, thermalStatus: Int): Int = when {
        thermalStatus >= 3 -> minOf(requested, 6)
        thermalStatus >= 2 -> minOf(requested, 12)
        else -> requested
    }

    fun rotation(sensor: Int, display: Int, front: Boolean = false): Int =
        Math.floorMod(sensor + if (front) display else -display, 360)
    fun oriented(width: Int,height: Int,rotation: Int): Dimensions =
        if (rotation == 90 || rotation == 270) Dimensions(height,width) else Dimensions(width,height)

    fun sensorPoint(point: Point, rotation: Int, flipX: Boolean = false, flipY: Boolean = false): Point {
        val x = if (flipX) 1 - point.x else point.x
        val y = if (flipY) 1 - point.y else point.y
        return when (rotation) {
            90 -> Point(y, 1 - x)
            180 -> Point(1 - x, 1 - y)
            270 -> Point(1 - y, x)
            else -> Point(x, y)
        }
    }

    fun displayPoint(x: Double, y: Double, width: Int, height: Int, image: Dimensions, zoom: Double): Point? {
        val scale = minOf(width.toDouble()/image.width,height.toDouble()/image.height)*zoom
        if (scale<=0) return null
        val px = .5+(x-width/2.0)/(scale*image.width)
        val py = .5+(y-height/2.0)/(scale*image.height)
        return if (px in 0.0..1.0 && py in 0.0..1.0) Point(px,py) else null
    }

    fun previewSize(sizes: List<Dimensions>, profile: Preview): Dimensions? {
        val good = sizes.filter { maxOf(it.width,it.height)<=profile.maxLongEdge && it.width.toLong()*it.height<=profile.maxPixels }
        return good.maxByOrNull { it.width.toLong()*it.height }
            ?: sizes.minByOrNull { it.width.toLong()*it.height }
    }

    fun manualStart(exposure: Long?, iso: Int?, min: Long,max: Long, minIso: Int,maxIso: Int): ManualStart {
        val shutter = (exposure ?: 8_333_333L).coerceIn(min,minOf(max,maxOf(min,33_333_333L)))
        val measuredGain = iso ?: minIso
        return ManualStart(shutter,measuredGain.coerceIn(minIso,minOf(maxIso,maxOf(minIso,800))))
    }

    fun focusState(manual: Boolean, afState: Int?): String = if (manual) "Manual" else when (afState) {
        1, 3 -> "AF scanning"
        2 -> "AF tracking"
        4 -> "AF locked"
        5 -> "AF unable to lock"
        6 -> "AF searching"
        else -> "AF idle"
    }

    fun rotateNv21(input: ByteArray, output: ByteArray, width: Int, height: Int, rotation: Int,
                   flipX: Boolean = false, flipY: Boolean = false) {
        require(rotation in listOf(0, 90, 180, 270))
        val size = oriented(width, height, rotation)
        fun transformPlane(base: Int, cols: Int, rows: Int, stride: Int) {
            val destWidth = size.width / stride
            val destHeight = size.height / stride
            for (y in 0 until rows) for (x in 0 until cols) {
                val rx = when (rotation) {
                    90 -> rows - 1 - y
                    180 -> cols - 1 - x
                    270 -> y
                    else -> x
                }
                val ry = when (rotation) {
                    90 -> x
                    180 -> rows - 1 - y
                    270 -> cols - 1 - x
                    else -> y
                }
                val dx = if (flipX) destWidth - 1 - rx else rx
                val dy = if (flipY) destHeight - 1 - ry else ry
                val src = base + (y * cols + x) * stride
                val dst = base + (dy * destWidth + dx) * stride
                output[dst] = input[src]
                if (stride == 2) output[dst + 1] = input[src + 1]
            }
        }
        transformPlane(0, width, height, 1)
        transformPlane(width * height, width / 2, height / 2, 2)
    }
}
