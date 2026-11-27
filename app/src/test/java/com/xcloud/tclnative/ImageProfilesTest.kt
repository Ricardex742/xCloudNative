package com.xcloud.tclnative

import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test

class ImageProfilesTest {
    @Test fun profilesReplaceOnlyTheProcessingChainAndKeepTransportAndColors() {
        val before=PlaybackSettings(resolution=StreamResolution.QHD_1440,videoCodec=VideoCodecMode.H264_MAIN,
            fsr=FsrMode.FSR1,fsrOutput=FsrOutput.UHD_4K,saturation=115,brightness=5,contrast=110,
            imageTreatment=ImageTreatmentSettings(deband=50,cleanup=50,neural=100,autoProtect=false),
            imageLab=ImageLabSettings(enabled=true),imageComparison=ImageComparison.SPLIT)
        for(profile in listOf(ImageProfile.ORIGINAL,ImageProfile.LIGHT,ImageProfile.FSR)) {
            val after=ImageProfiles.apply(before,profile)
            assertEquals(profile,ImageProfiles.selected(after));assertTrue(after.imageTreatment.autoProtect)
            assertEquals(before.resolution,after.resolution);assertEquals(before.videoCodec,after.videoCodec)
            assertEquals(before.videoMode,after.videoMode);assertEquals(before.touchMode,after.touchMode)
            assertEquals(before.saturation,after.saturation);assertEquals(before.brightness,after.brightness);assertEquals(before.contrast,after.contrast)
            assertFalse(after.imageLab.enabled);assertFalse(after.imageTreatment.spatial);assertEquals(0,after.imageTreatment.neural)
            assertEquals(ImageComparison.PROCESSED,after.imageComparison)
        }
        assertEquals(before,ImageProfiles.apply(before,ImageProfile.CUSTOM))
    }
    @Test fun oldCustomSettingsAreRecognizedWithoutMigrationAndIntensityIsBounded() {
        val old=PlaybackSettings(fsr=FsrMode.FSR1,imageTreatment=ImageTreatmentSettings(cleanup=50))
        assertEquals(ImageProfile.CUSTOM,ImageProfiles.selected(old));assertEquals(old,ImageProfiles.withIntensity(old,80))
        val recon=ImageProfiles.apply(old,ImageProfile.FSR)
        val high=ImageProfiles.withIntensity(recon,1000)
        assertEquals(100,high.reconstruction.strength);assertEquals(50,high.imageTreatment.temporal)
        val zero=ImageProfiles.withIntensity(recon,-100)
        assertEquals(0,zero.reconstruction.strength);assertEquals(0,zero.imageTreatment.temporal)
        assertEquals(ImageProfile.FSR,ImageProfiles.selected(zero))
    }
    @Test fun explicitComparisonTakesPriorityOverTheOptionalImageLab() {
        val value=PlaybackSettings(fsr=FsrMode.FSR1,
            imageLab=ImageLabSettings(enabled=true,comparison=ImageComparison.ORIGINAL))
        assertEquals(ImageComparison.ORIGINAL,value.effectiveImageComparison())
        assertEquals(ImageComparison.SPLIT,value.copy(imageComparison=ImageComparison.SPLIT).effectiveImageComparison())
        assertEquals(ImageComparison.PROCESSED,value.copy(videoMode=VideoMode.COMPATIBILITY).forSession().effectiveImageComparison())
    }
    @Test fun zeroFsrProfileBypassesProcessingUntilIntensityIncreasesWhileCustomFsrRetainsScaling() {
        val profile=ImageProfiles.apply(PlaybackSettings(saturation=115,brightness=10),ImageProfile.FSR)
        val zero=ImageProfiles.withIntensity(profile,0)
        val effective=ImageProfiles.forRendering(zero)
        assertEquals(FsrMode.OFF,effective.fsr)
        assertFalse(effective.imageTreatment.active)
        assertEquals(115,effective.saturation);assertEquals(10,effective.brightness)
        assertEquals(FsrMode.FSR1,zero.fsr);assertEquals(ImageProfile.FSR,ImageProfiles.selected(zero))
        val resumed=ImageProfiles.withIntensity(zero,25)
        assertSame(resumed,ImageProfiles.forRendering(resumed))
        val custom=zero.copy(fsrOutput=FsrOutput.QHD_1440)
        assertSame(custom,ImageProfiles.forRendering(custom))
    }
}
