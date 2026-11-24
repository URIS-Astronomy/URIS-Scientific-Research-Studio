package com.tod.astrophotography.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodecList
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class FrameTimes {
    private var values = LongArray(2048)
    var complete = true
        private set
    private var size = 0
    private var frameNumbers: LongArray? = null

    fun add(value: Long, number: Long? = null) {
        if (size >= 2_000_000) {
            complete = false
            return
        }
        if (number != null && frameNumbers == null) frameNumbers = LongArray(values.size)
        if (size == values.size) {
            values = values.copyOf(minOf(size * 2, 2_000_000))
            frameNumbers = frameNumbers?.copyOf(values.size)
        }
        if (number != null) frameNumbers?.set(size, number)
        values[size++] = value
    }

    fun sorted(excluded: Set<Long> = emptySet()): LongArray {
        val numbers = frameNumbers
        if (numbers == null || excluded.isEmpty()) return values.copyOf(size).apply { sort() }
        val included = LongArray(size)
        var count = 0
        for (i in 0 until size) {
            if (numbers[i] !in excluded) included[count++] = values[i]
        }
        return included.copyOf(count).apply { sort() }
    }
}

internal class SurfaceVideoEncoder(
    private val file: File,
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    private val orientation: Int
) {
    private val codec = MediaCodec.createByCodecName(requireNotNull(avcEncoder(width, height, fps)) { "No AVC encoder for this mode" })
    private val cancelled = AtomicBoolean(false)
    private var worker: Thread? = null
    private var failure: Exception? = null
    private var completed = false
    val frameTimes = FrameTimes()
    val surface: Surface

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceIn(1_000_000, 160_000_000))
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun start() {
        codec.start()
        worker = Thread(::drain, "TOD-Video-Encoder").apply { start() }
    }

    fun stop() {
        val thread = checkNotNull(worker) { "Video encoder has not started" }
        codec.signalEndOfInputStream()
        thread.join(8_000)
        check(!thread.isAlive) { "Video encoder did not finish" }
        failure?.let { throw it }
        check(completed) { "Video encoder produced no finalized recording" }
    }

    fun release() {
        cancelled.set(true)
        val thread = worker
        if (thread == null) {
            runCatching { codec.release() }
            surface.release()
        } else if (thread.isAlive) {
            thread.interrupt()
            thread.join(500)
        }
    }

    private fun drain() {
        var muxer: MediaMuxer? = null
        var track = -1
        var samples = 0
        var sawEOS = false
        try {
            val output = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            output.setOrientationHint(orientation)
            muxer = output
            val info = MediaCodec.BufferInfo()
            while (!cancelled.get()) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = output.addTrack(codec.outputFormat)
                    output.start()
                } else if (index >= 0) {
                    try {
                        val data = codec.getOutputBuffer(index)
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && data != null) {
                            check(track >= 0)
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            output.writeSampleData(track, data, info)
                            frameTimes.add(info.presentationTimeUs)
                            samples++
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawEOS = true
                            break
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            if (track >= 0 && samples > 0) output.stop()
            completed = sawEOS && samples > 0
        } catch (e: Exception) {
            failure = e
        } finally {
            runCatching { muxer?.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            surface.release()
        }
    }
}

/** Counts missing camera frames inside the verified encoder interval.
 * Startup and drain boundaries are excluded. Clock mismatches produce no count.
 */
internal fun missingEncoderFrames(camera: LongArray, encoded: LongArray): Int? {
    if (camera.size < 4 || encoded.size < 4) return null
    val tolerance = 500L
    var source = 0
    var matched = 0
    var interior = 0
    for (pts in encoded) {
        while (source < camera.size && camera[source] < pts - tolerance) source++
        if (source < camera.size && kotlin.math.abs(camera[source] - pts) <= tolerance) {
            matched++
            if (camera[source] > encoded.first() + tolerance && camera[source] < encoded.last() - tolerance) interior++
            source++
        }
    }
    if (matched < 4 || matched * 10L < encoded.size * 8L) return null
    return (camera.count { it > encoded.first() + tolerance && it < encoded.last() - tolerance } -
        interior).coerceAtLeast(0)
}

private val avcEncoders by lazy {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
    }.sortedByDescending { it.isHardwareAccelerated }
}

internal fun avcEncoder(width: Int, height: Int, fps: Int): String? =
    avcEncoders.firstOrNull { info ->
        runCatching {
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
        }.getOrDefault(false)
    }?.name
