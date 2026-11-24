package com.tod.astrophotography.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTimingTest {
    @Test fun countsSingleMissingFrameInsideVerifiedInterval() {
        val sensor = longArrayOf(1000, 11000, 21000, 31000, 41000, 51000, 61000)
        val codec = longArrayOf(11020, 21020, 41020, 51020)
        assertEquals(1, missingEncoderFrames(sensor, codec))
    }

    @Test fun incompatibleTimestampBaseReturnsUnavailable() {
        val sensor = longArrayOf(1000, 11000, 21000, 31000)
        val codec = longArrayOf(5001000, 5011000, 5021000, 5031000)
        assertNull(missingEncoderFrames(sensor, codec))
    }

    @Test fun excludesCameraReportedOutputLossFromEncoderInput() {
        val times = FrameTimes()
        times.add(1000, 1)
        times.add(11000, 2)
        times.add(21000, 3)
        assertEquals(listOf(1000L, 21000L), times.sorted(setOf(2L)).toList())
    }
}
