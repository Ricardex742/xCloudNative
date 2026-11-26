package org.webrtc

import org.junit.Assert.*
import org.junit.Test

class LowLatencyDecoderFactoryTest {
    private val avc = VideoCodecInfo("H264", mapOf("profile-level-id" to "42e01f"), emptyList())
    private val high = VideoCodecInfo("H264", mapOf("profile-level-id" to "640c1f"), emptyList())
    private val vp8 = VideoCodecInfo("VP8", emptyMap(), emptyList())

    private fun hardwareDecoder() = object : VideoDecoder {
        override fun getImplementationName() = "fake-hardware"
        override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback) = VideoCodecStatus.OK
        override fun decode(image: EncodedImage, info: VideoDecoder.DecodeInfo) = VideoCodecStatus.OK
        override fun release() = VideoCodecStatus.OK
    }

    private class Factory(private val decoders: Map<VideoCodecInfo, VideoDecoder>) : VideoDecoderFactory {
        val requests = mutableListOf<VideoCodecInfo>()
        override fun getSupportedCodecs() = decoders.keys.toTypedArray()
        override fun createDecoder(codec: VideoCodecInfo): VideoDecoder? {
            requests += codec
            return decoders[codec]
        }
    }

    @Test fun hardwareOnlyFormatHasNoSecondHardwareFallbackAndUsesTheOriginalDecoder() {
        val hardware = Factory(mapOf(high to hardwareDecoder()))
        val software = Factory(mapOf(vp8 to hardwareDecoder()))
        val platform = Factory(mapOf(avc to hardwareDecoder()))
        val factory = LowLatencyDecoderFactory(null, false, DecoderRecoveryController(), {}, hardware, software, platform)
        assertEquals(listOf(vp8, high, avc), factory.supportedCodecs.toList())
        val decoder = requireNotNull(factory.createDecoder(high))
        assertTrue(decoder is RecoverableVideoDecoder)
        assertEquals("fake-hardware", decoder.implementationName)
        assertEquals(listOf(high), hardware.requests)
        assertEquals(listOf(high), software.requests)
        assertEquals(listOf(high), platform.requests)
    }

    @Test fun alternateRouteLearnsOnlyFromOutputAndKeepsDistinctFormatAndResolution() {
        val history = AvcDecoderTrial()
        val repeated = hardwareDecoder()
        val rejected = object : VideoDecoder by hardwareDecoder() {
            override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback) = VideoCodecStatus.ERROR
            override fun release(): VideoCodecStatus {
                released = true
                return VideoCodecStatus.OK
            }
            var released = false
        }
        lateinit var output: VideoDecoder.Callback
        val alternate = object : VideoDecoder by hardwareDecoder() {
            override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
                output = callback
                return VideoCodecStatus.OK
            }
            override fun getImplementationName() = "alternate"
        }
        val decoder = RecoverableVideoDecoder({ repeated }, { configuration ->
            assertTrue(rejected.released)
            assertEquals(720, configuration.height)
            alternate
        }, DecoderRecoveryController(), rejected, true, { configuration, component ->
            history.remember("42e01f", configuration.width, configuration.height, component)
        })
        assertEquals(VideoCodecStatus.OK, decoder.initDecode(VideoDecoder.Settings(1, 1280, 720)) { _, _, _ -> })
        assertNull(history.trained("42e01f", 1280, 720))
        output.onDecodedFrame(null, null, null)
        assertEquals("alternate", history.trained("42e01f", 1280, 720))
        assertNull(history.trained("42e01f", 1920, 1080))
        assertNull(history.trained("640c1f", 1280, 720))
        decoder.release()
        repeat(8) { history.remember("42e01f", it + 100, 100, "bounded") }
        assertNull(history.trained("42e01f", 1280, 720))
    }

    @Test fun softwareOnlyAndCombinedRoutesRetainTheExactFormat() {
        val platformDecoder = hardwareDecoder()
        val hardware = Factory(mapOf(high to hardwareDecoder()))
        val software = Factory(mapOf(high to hardwareDecoder()))
        val platform = Factory(mapOf(avc to platformDecoder))
        val factory = LowLatencyDecoderFactory(null, false, DecoderRecoveryController(), {}, hardware, software, platform)
        assertSame(platformDecoder, factory.createDecoder(avc))
        assertTrue(factory.createDecoder(high) is VideoDecoderFallback)
        assertEquals(listOf(avc, high), hardware.requests)
        assertEquals(listOf(avc, high), software.requests)
        assertEquals(listOf(avc), platform.requests)
    }
}
