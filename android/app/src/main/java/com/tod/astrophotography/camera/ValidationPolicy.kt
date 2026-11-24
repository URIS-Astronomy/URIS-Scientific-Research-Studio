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

    fun rotation(sensor: Int, display: Int): Int = ((sensor-display)%360+360)%360
    fun oriented(width: Int,height: Int,rotation: Int): Dimensions =
        if (rotation == 90 || rotation == 270) Dimensions(height,width) else Dimensions(width,height)

    fun sensorPoint(point: Point, rotation: Int): Point = when (rotation) {
        90 -> Point(point.y,1-point.x)
        180 -> Point(1-point.x,1-point.y)
        270 -> Point(1-point.y,point.x)
        else -> point
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

    fun rotateNv21(input: ByteArray, output: ByteArray, width: Int, height: Int, rotation: Int) {
        require(rotation in listOf(0,90,180,270))
        if (rotation==0) { input.copyInto(output, endIndex=width*height*3/2); return }
        val destWidth = oriented(width,height,rotation).width
        for (y in 0 until height) for (x in 0 until width) {
            val dest = when (rotation) {
                90 -> x*destWidth + height-1-y
                180 -> (height-1-y)*destWidth+width-1-x
                else -> (width-1-x)*destWidth+y
            }
            output[dest] = input[y*width+x]
        }
        val base=width*height
        val chromaWidth=width/2
        val chromaHeight=height/2
        for (y in 0 until chromaHeight) for (x in 0 until chromaWidth) {
            val dx = when(rotation) { 90 -> chromaHeight-1-y; 180 -> chromaWidth-1-x; else -> y }
            val dy = when(rotation) { 90 -> x; 180 -> chromaHeight-1-y; else -> chromaWidth-1-x }
            val src = base+y*width+x*2
            val dest = base+dy*destWidth+dx*2
            output[dest]=input[src]
            output[dest+1]=input[src+1]
        }
    }
}
