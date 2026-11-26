package com.xcloud.tclnative

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*
import java.nio.ByteBuffer

class RecoverableVideoDecoderTest {
    private class Decoder(val initResult: VideoCodecStatus = VideoCodecStatus.OK,
                          val releaseResult: VideoCodecStatus = VideoCodecStatus.OK,
                          val decodeResult: VideoCodecStatus = VideoCodecStatus.OK) : VideoDecoder {
        var settings: VideoDecoder.Settings? = null
        var callback: VideoDecoder.Callback? = null
        var releases = 0
        var frames = 0
        var thread: Thread? = null
        override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
            this.settings = settings; this.callback = callback; thread = Thread.currentThread()
            return initResult
        }
        override fun decode(image: EncodedImage, info: VideoDecoder.DecodeInfo): VideoCodecStatus {
            assertSame(thread, Thread.currentThread()); frames++; return decodeResult
        }
        override fun release(): VideoCodecStatus { releases++; return releaseResult }
        override fun getImplementationName() = "test-hardware"
    }
    private val settings = VideoDecoder.Settings(4, 1920, 1080)
    private val callback = VideoDecoder.Callback { _, _, _ -> }
    private val info = VideoDecoder.DecodeInfo(false, 0)
    private fun image(key: Boolean, width: Int = 1920, height: Int = 1080) = EncodedImage.builder()
        .setBuffer(ByteBuffer.allocateDirect(1), null).setEncodedWidth(width).setEncodedHeight(height)
        .setCaptureTimeNs(1).setFrameType(if (key) EncodedImage.FrameType.VideoFrameKey else EncodedImage.FrameType.VideoFrameDelta)
        .createEncodedImage()

    @Test fun aCrossThreadResetWaitsForAnIdrAndKeepsCallbackAndNewResolution() {
        val control = DecoderRecoveryController()
        val created = mutableListOf<Decoder>()
        val decoder = RecoverableVideoDecoder({ Decoder().also(created::add) }, { Decoder() }, control)
        assertFalse(control.requestReset())
        var forwarded = 0
        assertEquals(VideoCodecStatus.OK, decoder.initDecode(settings) { _, _, _ -> forwarded++ })
        Thread { assertTrue(control.requestReset()) }.apply { start(); join() }
        assertEquals(0, created.first().releases)
        decoder.decode(image(false), info)
        assertEquals(1, created.size)
        decoder.decode(image(true, 1280, 720), info)
        assertEquals(2, created.size)
        assertEquals(1, created.first().releases)
        assertEquals(1280, created.last().settings!!.width)
        assertEquals(720, created.last().settings!!.height)
        created.first().callback!!.onDecodedFrame(null, 1, 0)
        assertEquals(0, forwarded)
        created.last().callback!!.onDecodedFrame(null, 1, 0)
        assertEquals(1, forwarded)
        assertEquals(1, created.last().frames) // Decode the same IDR used to reset.
        decoder.decode(image(true), info)
        assertEquals(2, created.size) // No endless reset on every keyframe.
        decoder.release()
        assertFalse(control.requestReset())
    }
    @Test fun rejectedLowLatencyInitializationFallsBackToNormalHardware() {
        val rejected = Decoder(VideoCodecStatus.ERROR)
        val normal = Decoder()
        val control = DecoderRecoveryController()
        val decoder = RecoverableVideoDecoder({ rejected }, { normal }, control)
        assertEquals(VideoCodecStatus.OK, decoder.initDecode(settings, callback))
        assertEquals(1, rejected.releases)
        decoder.decode(image(true), info)
        assertEquals(1, normal.frames)
        assertEquals(0, rejected.frames)
        decoder.release()
        decoder.release()
        assertEquals(1, normal.releases)
        assertFalse(control.requestReset())
    }
    @Test fun unavailablePreferredFactoryStillUsesStandardHardware() {
        val normal = Decoder()
        val decoder = RecoverableVideoDecoder({ throw IllegalStateException("vendor codec unavailable") },
            { normal }, DecoderRecoveryController())
        assertEquals(VideoCodecStatus.OK, decoder.initDecode(settings, callback))
        assertEquals(VideoCodecStatus.OK, decoder.decode(image(true), info))
        assertEquals(1, normal.frames)
        decoder.release()
    }
    @Test fun keyframeFailureTriesStandardHardwareWithTheSameFrameAndReleasesPreferredFirst() {
        val preferred = Decoder(decodeResult = VideoCodecStatus.ERROR)
        val normal = Decoder()
        val control = DecoderRecoveryController()
        var normalAllocations = 0
        val decoder = RecoverableVideoDecoder({ preferred }, {
            assertEquals(1, preferred.releases)
            normalAllocations++
            normal
        }, control)
        assertEquals(VideoCodecStatus.OK, decoder.initDecode(settings, callback))
        assertEquals(VideoCodecStatus.ERROR, decoder.decode(image(false), info))
        assertEquals(0, normalAllocations)
        assertEquals(VideoCodecStatus.OK, decoder.decode(image(true, 1280, 720), info))
        assertEquals(2, preferred.frames)
        assertEquals(1, normal.frames)
        assertEquals(720, normal.settings!!.height)
        assertEquals("DECODER_RECOVERY/STANDARD_RESET", control.diagnostics())
        decoder.decode(image(true), info)
        assertEquals(1, normalAllocations)
        decoder.release()
    }
    @Test fun timeoutOnDeltaFrameWaitsForKeyframeBeforeSwitchingDecoder() {
        val preferred = Decoder(decodeResult = VideoCodecStatus.TIMEOUT)
        val normal = Decoder()
        val decoder = RecoverableVideoDecoder({ preferred }, { normal }, DecoderRecoveryController())
        decoder.initDecode(settings, callback)
        assertEquals(VideoCodecStatus.ERROR, decoder.decode(image(false), info))
        assertEquals(0, preferred.releases)
        assertEquals(0, normal.frames)
        assertEquals(VideoCodecStatus.OK, decoder.decode(image(true), info))
        assertEquals(1, preferred.releases)
        assertEquals(1, normal.frames)
        decoder.release()
    }
    @Test fun repeatedRecoveryWithoutDecodedOutputUsesStandardHardware() {
        val preferred = mutableListOf<Decoder>()
        val normal = Decoder()
        val control = DecoderRecoveryController()
        val decoder = RecoverableVideoDecoder({ Decoder().also(preferred::add) }, { normal }, control)
        decoder.initDecode(settings, callback)
        repeat(2) {
            assertTrue(control.requestReset())
            assertEquals(VideoCodecStatus.OK, decoder.decode(image(true), info))
        }
        assertEquals(2, preferred.size)
        assertTrue(preferred.all { it.releases == 1 })
        assertEquals(1, normal.frames)
        decoder.release()
    }
    @Test fun missingIdrDimensionsRetainThePreviousDimensions() {
        val control = DecoderRecoveryController()
        val created = mutableListOf<Decoder>()
        val decoder = RecoverableVideoDecoder({ Decoder().also(created::add) }, { Decoder() }, control)
        decoder.initDecode(settings, callback)
        control.requestReset()
        decoder.decode(image(true, 0, 0), info)
        assertEquals(1920, created.last().settings!!.width)
        assertEquals(1080, created.last().settings!!.height)
        decoder.release()
    }
    @Test fun recoveryKeepsNormalHardwareAfterRealtimeOptionsWereRejected() {
        var preferredAllocations = 0
        val normals = mutableListOf<Decoder>()
        val control = DecoderRecoveryController()
        val decoder = RecoverableVideoDecoder(
            { preferredAllocations++; Decoder(VideoCodecStatus.ERROR) },
            { Decoder().also(normals::add) }, control)
        assertEquals(VideoCodecStatus.OK, decoder.initDecode(settings, callback))
        assertTrue(control.requestReset())
        assertEquals(VideoCodecStatus.OK, decoder.decode(image(true, 1280, 720), info))
        assertEquals(1, preferredAllocations)
        assertEquals(2, normals.size)
        assertEquals(1, normals.first().releases)
        assertEquals(1280, normals.last().settings!!.width)
        assertEquals(1, normals.last().frames)
        decoder.release()
    }
    @Test fun failedReleaseDuringInitializationDoesNotAllocateAnotherHardwareCodec() {
        var standardAllocations = 0
        val stuck = Decoder(VideoCodecStatus.ERROR, VideoCodecStatus.TIMEOUT)
        val decoder = RecoverableVideoDecoder({ stuck }, { standardAllocations++; Decoder() }, DecoderRecoveryController())
        assertEquals(VideoCodecStatus.ERROR, decoder.initDecode(settings, callback))
        assertEquals(0, standardAllocations)
        decoder.release()
        assertEquals(1, stuck.releases)
    }
    @Test fun releasedDecoderIgnoresRequestsAndNeverDecodesAnotherFrame() {
        val hardware = Decoder()
        val control = DecoderRecoveryController()
        val decoder = RecoverableVideoDecoder({ hardware }, { Decoder() }, control)
        decoder.initDecode(settings, callback)
        control.requestReset()
        decoder.release()
        assertFalse(control.requestReset())
        assertEquals(VideoCodecStatus.UNINITIALIZED, decoder.decode(image(true), info))
        assertEquals(0, hardware.frames)
        assertEquals(1, hardware.releases)
    }
    @Test fun anOldTrackReleaseDoesNotDetachTheReplacementDecoder() {
        val control = DecoderRecoveryController()
        val old = RecoverableVideoDecoder({ Decoder() }, { Decoder() }, control)
        val newer = RecoverableVideoDecoder({ Decoder() }, { Decoder() }, control)
        old.initDecode(settings, callback)
        newer.initDecode(settings, callback)
        old.release()
        assertTrue(control.requestReset())
        newer.release()
        assertFalse(control.requestReset())
    }
    @Test fun failedInitializationDoesNotLeaveAnActiveRecoveryTargetOrAcceptFrames() {
        val control = DecoderRecoveryController()
        val hardware = Decoder(VideoCodecStatus.ERROR)
        val fallback = Decoder(VideoCodecStatus.ERROR)
        val decoder = RecoverableVideoDecoder({ hardware }, { fallback }, control)
        assertEquals(VideoCodecStatus.ERROR, decoder.initDecode(settings, callback))
        assertFalse(control.requestReset())
        assertEquals(VideoCodecStatus.UNINITIALIZED, decoder.decode(image(true), info))
        decoder.release()
        assertEquals(1, hardware.releases)
        assertEquals(1, fallback.releases)
        assertEquals(0, fallback.frames)
    }
    @Test fun aStuckDriverReleaseFallsBackWithoutAllocatingMoreHardwareCodecs() {
        val control = DecoderRecoveryController()
        val hardware = Decoder(releaseResult = VideoCodecStatus.TIMEOUT)
        var allocated = 0
        val decoder = RecoverableVideoDecoder({ allocated++; hardware }, { Decoder() }, control)
        decoder.initDecode(settings, callback)
        control.requestReset()
        assertEquals(VideoCodecStatus.FALLBACK_SOFTWARE, decoder.decode(image(true), info))
        assertFalse(control.requestReset())
        decoder.release()
        assertEquals(1, allocated)
        assertEquals(1, hardware.releases)
    }
}
