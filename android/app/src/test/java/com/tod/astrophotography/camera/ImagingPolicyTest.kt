package com.tod.astrophotography.camera

import org.junit.Assert.*
import org.junit.Test

class ImagingPolicyTest {
    @Test fun selectsMainPhysicalLensOverLargerUltraWideAndTele() {
        val main = RearLens("physical-main",50_000_000,5.6,8.0,6.0,true,true,true)
        val ultraWide = RearLens("physical-ultra",64_000_000,2.2,5.0,4.0,false,false,false)
        val tele = RearLens("physical-tele",50_000_000,12.0,5.0,4.0,true,true,true)
        assertEquals("physical-main",ImagingPolicy.chooseMain(listOf(ultraWide,tele,main))?.id)
        assertNull(ImagingPolicy.chooseMain(emptyList()))
    }

    @Test fun displayMagnificationIsClampedIndependentlyOfCapture() {
        assertEquals(8.0,ImagingPolicy.displayZoom(30.0),0.0)
        assertEquals(1.0,ImagingPolicy.displayZoom(Double.NaN),0.0)
        val active = Crop(10,20,4010,3020)
        assertEquals(CaptureFraming(1.0,active),ImagingPolicy.captureFraming(active,25,4.0,4.0,false))
        assertEquals(CaptureFraming(1.0,active),ImagingPolicy.captureFraming(active,25,4.0,null,false))
    }

    @Test fun captureRatioAndLegacyCropRespectLimitsAndActiveOrigin() {
        val active = Crop(10,20,4010,3020)
        assertEquals(CaptureFraming(2.0,active),ImagingPolicy.captureFraming(active,25,8.0,2.0,true))
        assertEquals(CaptureFraming(1.0,Crop(1010,770,3010,2270)),ImagingPolicy.captureFraming(active,25,2.0,null,true))
        assertEquals(active,ImagingPolicy.captureFraming(active,100,4.0,null,true).crop)
    }

    @Test fun choosesNativeStillAndRawSizeIndependentlyOfPreview() {
        val jpeg = listOf(1280 to 720,4080 to 3060,8160 to 6120)
        val raw = listOf(4080 to 3060,2040 to 1530)
        val preview = 1280 to 720
        assertEquals(8160 to 6120,ImagingPolicy.largestSize(jpeg,{it.first},{it.second}))
        assertEquals(4080 to 3060,ImagingPolicy.largestSize(raw,{it.first},{it.second}))
        assertEquals(1280 to 720,preview)
    }

    @Test fun standardVideoIsConstrainedByReportedFrameDuration() {
        assertTrue(ImagingPolicy.standardVideoTiming(60,16_666_667L))
        assertFalse(ImagingPolicy.standardVideoTiming(60,33_333_333L))
        assertTrue(ImagingPolicy.standardVideoTiming(30,33_333_333L))
        assertFalse(ImagingPolicy.standardVideoTiming(0,0))
    }
    @Test fun clampsExposureWithoutRoundingBelowSensorMinimum() {
        assertEquals(9750L,ImagingPolicy.exposureNs(9.0,10_000,9750,1_000_000))
        assertEquals(9750L,ImagingPolicy.exposureNs(9.75,10_000,9750,1_000_000))
        assertEquals(1_000_000L,ImagingPolicy.exposureNs(90000.0,10_000,9750,1_000_000))
        assertEquals(100,ImagingPolicy.iso(20,100,3200))
        assertEquals(3200,ImagingPolicy.iso(6400,100,3200))
        assertEquals(0f,ImagingPolicy.focus(-1.0,2f,8f),0f)
        assertEquals(8f,ImagingPolicy.focus(12.0,2f,8f),0f)
        assertFalse(ImagingPolicy.stabilization(true,intArrayOf(0)))
        assertTrue(ImagingPolicy.stabilization(true,intArrayOf(0,1)))
        assertFalse(ImagingPolicy.stabilization(false,intArrayOf(0,1)))
    }
}
