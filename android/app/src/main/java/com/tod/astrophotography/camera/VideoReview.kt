package com.tod.astrophotography.camera

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

internal fun reviewVideo(file: File, fps: Int, cameraFailures: Int, requestedFrames: Long, rankFrames: Boolean): JSONObject {
    val extractor = MediaExtractor()
    var samples = 0
    var gaps = 0
    try {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        extractor.selectTrack(track)
        val stamps = ArrayList<Long>()
        while (extractor.sampleTime >= 0 && !Thread.currentThread().isInterrupted) {
            stamps.add(extractor.sampleTime)
            if (!extractor.advance()) break
        }
        stamps.sort()
        samples = stamps.size
        val period = 1_000_000.0 / fps
        for (i in 1 until samples) {
            gaps += ((stamps[i] - stamps[i - 1]) / period).roundToInt().minus(1).coerceAtLeast(0)
        }
    } finally {
        extractor.release()
    }
    val report = JSONObject().put("encoded_frames", samples).put("cadence_gaps", gaps)
        .put("camera_failures", cameraFailures).put("target_fps", fps).put("camera_frames_started", requestedFrames)
    val selected = JSONArray()
    report.put("best_frames", selected)
    if (!rankFrames || samples == 0) return report

    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: samples
        val stride = (frameCount + 239) / 240
        val candidates = mutableListOf<Pair<Int, Double>>()
        for (index in 0 until frameCount step stride.coerceAtLeast(1)) {
            if (Thread.currentThread().isInterrupted) return report
            val frame = retriever.getFrameAtIndex(index) ?: continue
            try { candidates.add(index to frameSharpness(frame)) } finally { frame.recycle() }
        }
        report.put("scored_frames", candidates.size)
        candidates.sortedByDescending { it.second }.take(5).forEachIndexed { rank, (index, score) ->
            if (Thread.currentThread().isInterrupted) return report
            val frame = retriever.getFrameAtIndex(index) ?: return@forEachIndexed
            try {
                val output = File(file.parentFile, "${file.nameWithoutExtension}_best_${rank + 1}.png")
                output.outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                selected.put(JSONObject().put("path", output.absolutePath).put("frame_index", index).put("score", score))
            } finally { frame.recycle() }
        }
    } finally {
        retriever.release()
    }
    return report
}

private fun frameSharpness(frame: Bitmap): Double {
    val stride = maxOf(1, maxOf(frame.width, frame.height) / 360)
    val width = frame.width / stride
    val height = frame.height / stride
    val gray = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val pixel = frame.getPixel(x * stride, y * stride)
        gray[y * width + x] = (77 * ((pixel shr 16) and 255) + 150 * ((pixel shr 8) and 255) + 29 * (pixel and 255)) shr 8
    }
    var energy = 0.0
    var count = 0
    for (y in height / 8 + 1 until height * 7 / 8 - 1) for (x in width / 8 + 1 until width * 7 / 8 - 1) {
        val i = y * width + x
        if (gray[i] >= 250) continue
        val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]
        energy += lap.toDouble() * lap
        count++
    }
    return energy / count.coerceAtLeast(1)
}
