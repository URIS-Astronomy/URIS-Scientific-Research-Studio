package com.tod.astrophotography.camera

import org.junit.Assert.*
import org.junit.Test

class ValidationPolicyTest {
    @Test fun rearRotationMatchesPhoneDisplayInAllOrientations() {
        assertEquals(90,ValidationPolicy.rotation(90,0))
        assertEquals(0,ValidationPolicy.rotation(90,90))
        assertEquals(270,ValidationPolicy.rotation(90,180))
        assertEquals(180,ValidationPolicy.rotation(90,270))
        assertEquals(ValidationPolicy.Dimensions(1080,1920),ValidationPolicy.oriented(1920,1080,90))
        assertEquals(ValidationPolicy.Dimensions(1920,1080),ValidationPolicy.oriented(1920,1080,180))
    }

    @Test fun jpegPreviewRotationKeepsChromaPairsAndDoesNotMirror() {
        val input=byteArrayOf(1,2,3,4,5,6,7,8, 20,21,22,23)
        val out=ByteArray(input.size)
        ValidationPolicy.rotateNv21(input,out,4,2,90)
        assertArrayEquals(byteArrayOf(5,1,6,2,7,3,8,4, 20,21,22,23),out)
        ValidationPolicy.rotateNv21(input,out,4,2,270)
        assertArrayEquals(byteArrayOf(4,8,3,7,2,6,1,5, 22,23,20,21),out)
        ValidationPolicy.rotateNv21(input,out,4,2,180)
        assertArrayEquals(byteArrayOf(8,7,6,5,4,3,2,1, 22,23,20,21),out)
    }

    @Test fun daylightManualStartUsesMeasuredBrightnessWithoutOneSecondExposure() {
        val safe=ValidationPolicy.manualStart(1_000_000_000,1600,10_000,2_000_000_000,50,6400)
        assertEquals(33_333_333L,safe.exposureNs)
        assertEquals(800,safe.iso)
        assertEquals(8_333_333L,ValidationPolicy.manualStart(null,null,10_000,2_000_000_000,50,6400).exposureNs)
        assertEquals(6_500_000L,ValidationPolicy.manualStart(6_500_000,80,10_000,2_000_000_000,50,6400).exposureNs)
    }

    @Test fun tapCoordinatesRespectOrientationAndDisplayZoom() {
        val image=ValidationPolicy.Dimensions(400,800)
        assertNull(ValidationPolicy.displayPoint(5.0,100.0,600,800,image,1.0))
        val point=ValidationPolicy.displayPoint(500.0,200.0,600,800,image,2.0)!!
        assertEquals(ValidationPolicy.Point(.75,.375),point)
        assertEquals(ValidationPolicy.Point(.375,.25),ValidationPolicy.sensorPoint(point,90))
        assertEquals("AF scanning",ValidationPolicy.focusState(false,3))
        assertEquals("AF locked",ValidationPolicy.focusState(false,4))
        assertEquals("AF unable to lock",ValidationPolicy.focusState(false,5))
        assertEquals("Manual",ValidationPolicy.focusState(true,4))
    }

    @Test fun previewQualityDoesNotSelectStillCaptureResolution() {
        val live=listOf(ValidationPolicy.Dimensions(640,480),ValidationPolicy.Dimensions(1280,720),
            ValidationPolicy.Dimensions(1920,1080),ValidationPolicy.Dimensions(2560,1440))
        val still=listOf(4080 to 3060,8160 to 6120)
        assertEquals(live[0],ValidationPolicy.previewSize(live,ValidationPolicy.Preview.LOW))
        assertEquals(live[2],ValidationPolicy.previewSize(live,ValidationPolicy.Preview.BALANCED))
        assertEquals(live[3],ValidationPolicy.previewSize(live,ValidationPolicy.Preview.HIGH))
        assertEquals(still[1],ImagingPolicy.largestSize(still,{it.first},{it.second}))
        assertEquals(100,ValidationPolicy.JPEG_QUALITY)
    }
}
