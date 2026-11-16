package com.xcloud.tclnative

import android.content.Context
import com.xcloud.tclnative.settings.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.webrtc.ImageLabPlan

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ImageLabSettingsTest {
    @Test fun generationOnlyDoesNotRequireNeutralRestorationOrSupersamplingPasses() {
        val onlyGeneration=ImageLabSettings(enabled=true,restoration=0,frameGeneration=ExperimentalFrameGeneration.AUTO_30_TO_60)
        assertFalse(onlyGeneration.hasImageEffects)
        assertTrue(onlyGeneration.copy(restoration=1).hasImageEffects)
        assertTrue(onlyGeneration.copy(supersampling=ImageSupersampling.X125).hasImageEffects)
        assertFalse(onlyGeneration.copy(enabled=false,restoration=100).hasImageEffects)
    }
    @Test fun existingPreferencesStayIntactAndEveryNewFeatureBeginsDisabled(){
        val app=RuntimeEnvironment.getApplication();val prefs=app.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
        prefs.edit().clear().putString("resolution","HD_720").putInt("sharpness",75).putString("fsr_mode","FSR1").commit()
        val store=PlaybackSettingsStore(app);val original=store.read()
        assertEquals(75,original.sharpness);assertEquals(FsrMode.FSR1,original.fsr);assertFalse(original.imageLab.enabled)
        val lab=ImageLabSettings(true,75,HudProtection.CORNERS,ImageSupersampling.X150,ExperimentalFrameGeneration.AUTO_30_TO_60,ImageComparison.SPLIT,true)
        store.save(original.copy(imageLab=lab));assertEquals(original.copy(imageLab=lab),store.read())
        val effective=store.read().imageLab.forDevice(true)
        assertEquals(25,effective.restoration);assertFalse(effective.neural);assertEquals(ImageSupersampling.OFF,effective.supersampling)
        assertEquals(ExperimentalFrameGeneration.OFF,effective.frameGeneration)
        assertEquals(lab,store.read().imageLab)
        assertFalse(store.read().copy(videoMode=VideoMode.COMPATIBILITY).forSession().imageLab.enabled)
    }
    @Test fun corruptedEnumsAndAmountsUseBoundedSafeDefaults(){
        val app=RuntimeEnvironment.getApplication();val prefs=app.getSharedPreferences("stream_preferences",0)
        prefs.edit().clear().putInt("image_lab_restoration",900).putString("image_lab_frame_generation","future_unknown")
            .putString("image_lab_protection","future_unknown").commit()
        val lab=PlaybackSettingsStore(app).read().imageLab
        assertEquals(100,lab.restoration);assertEquals(ExperimentalFrameGeneration.OFF,lab.frameGeneration)
        assertEquals(HudProtection.AUTO,lab.protection)
        assertEquals(0,ImageLabSettings(restoration=-100).forDevice(true).restoration)
    }
    @Test fun theWholeTexturePlanIncludesUpscalerCopiesAndReducesBeforeExceedingMemory(){
        val normal=ImageLabPlan.choose(1920,1080,1920,1080,2f,4096,1920L*1080*8,1)!!
        assertEquals(3840,normal.width);assertEquals(2160,normal.height);assertTrue(normal.bytes<=ImageLabPlan.LIMIT)
        val fsr=ImageLabPlan.choose(1920,1080,1920,1080,2f,4096,1920L*1080*12,2)!!
        assertEquals(1.25f,fsr.scale,0f);assertTrue(fsr.bytes<=ImageLabPlan.LIMIT)
        val limited=ImageLabPlan.choose(1280,720,1920,1080,2f,2048,1280L*720*8,1)!!
        assertEquals(1f,limited.scale,0f)
        assertNull(ImageLabPlan.choose(4096,2160,3840,2160,2f,4096,50L*1024*1024,2))
        assertNull(ImageLabPlan.choose(1,1,0,720,2f,4096,0,1))
        assertNull(ImageLabPlan.choose(1,1,720,720,Float.NaN,4096,0,1))
    }
}
