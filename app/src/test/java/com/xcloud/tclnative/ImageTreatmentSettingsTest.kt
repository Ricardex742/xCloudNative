package com.xcloud.tclnative

import android.app.Activity
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ImageTreatmentSettingsTest {
    @Test fun reconstructionPreferencesSurviveCompatibilityWithoutChangingStreamOrTouch() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val store=PlaybackSettingsStore(c.get());val original=store.read()
            val requested=original.copy(fsr=FsrMode.FSR1,reconstruction=ReconstructionSettings(
                ReconstructionQuality.QUALITY,500),imageComparison=ImageComparison.SPLIT)
            store.save(requested)
            val saved=store.read()
            assertEquals(100,saved.reconstruction.strength);assertEquals(ImageComparison.SPLIT,saved.imageComparison)
            assertEquals(original.resolution,saved.resolution);assertEquals(original.touchMode,saved.touchMode)
            assertEquals(FsrMode.OFF,saved.copy(videoMode=VideoMode.COMPATIBILITY).forSession().fsr)
            assertEquals(saved,store.read())
        } finally { c.pause().stop().destroy() }
    }
    @Test fun legacyScalersMigrateToFsrWithoutResettingVideoAndInput() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val prefs=c.get().getSharedPreferences("stream_preferences",0)
            for(mode in listOf("SGSR1","RECONSTRUCTION")) {
                prefs.edit().clear().putString("fsr_mode",mode).putString("fsr_output","QHD_1440")
                    .putInt("fsr_sharpness",75).putInt("reconstruction_strength",25)
                    .putString("image_comparison","SPLIT").putString("touch_mode","VIRTUAL")
                    .putString("resolution","HD_720").putString("video_codec","H264_MAIN").commit()
                val migrated=PlaybackSettingsStore(c.get()).read()
                assertEquals(FsrMode.FSR1,migrated.fsr)
                assertEquals(FsrOutput.QHD_1440,migrated.fsrOutput)
                assertEquals(75,migrated.fsrSharpness);assertEquals(25,migrated.reconstruction.strength)
                assertEquals(ImageSharpening.RCAS_LUMA,migrated.imageTreatment.sharpening)
                assertEquals(if(mode=="RECONSTRUCTION") ImageComparison.SPLIT else ImageComparison.PROCESSED,migrated.effectiveImageComparison())
                assertEquals(TouchMode.VIRTUAL,migrated.touchMode)
                assertEquals(StreamResolution.HD_720,migrated.resolution)
                assertEquals(VideoCodecMode.H264_MAIN,migrated.videoCodec)
                assertEquals(migrated,PlaybackSettingsStore(c.get()).read())
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test fun newStagesAreOptInAndRoundTripWithoutChangingTheStreamOrTouchPreferences() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get();activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(activity)
            assertFalse(store.read().imageTreatment.active)
            assertEquals(PresentationPacing.OFF,store.read().presentationPacing)
            val value=store.read().copy(videoMode=VideoMode.ADAPTIVE,videoCodec=VideoCodecMode.H264_MAIN,
                fsr=FsrMode.FSR1,presentationPacing=PresentationPacing.SMOOTH,
                imageTreatment=ImageTreatmentSettings(ImageSharpening.USM_LUMA,25,50,75,25,50,25,false))
            store.save(value)
            assertEquals(value,PlaybackSettingsStore(activity).read())
            assertEquals(StreamResolution.HD_1080_HQ,store.read().resolution)
            assertEquals(TouchMode.AUTO,store.read().touchMode)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun oldProfilesMigrateOnceAndLaterExplicitDetailEditsPersist() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get()
            val prefs=activity.getSharedPreferences("stream_preferences",0)
            prefs.edit().clear().putString("fsr_mode","FSR1").putInt("fsr_sharpness",37)
                .putString("image_sharpening","RCAS_LUMA").putInt("reconstruction_strength",75)
                .putInt("image_temporal",37).commit()
            val store=PlaybackSettingsStore(activity)
            val migrated=store.read()
            assertEquals(75,migrated.fsrSharpness)
            assertEquals(0,migrated.reconstruction.strength);assertEquals(0,migrated.imageTreatment.temporal)
            val edited=store.update(migrated,migrated.copy(reconstruction=ReconstructionSettings(strength=50)))
            assertEquals(50,edited.reconstruction.strength)
            assertEquals(edited,PlaybackSettingsStore(activity).read())
            prefs.edit().clear().putString("fsr_mode","FSR1").putInt("fsr_sharpness",75)
                .putInt("reconstruction_strength",50).commit()
            val oldPlainFsr=PlaybackSettingsStore(activity).read()
            assertEquals(75,oldPlainFsr.fsrSharpness);assertEquals(0,oldPlainFsr.reconstruction.strength)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun corruptedStrengthsAreBoundedAndEffectiveCompatibilityDoesNotOverwriteSavedChoices() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val store=PlaybackSettingsStore(c.get())
            val selected=PlaybackSettings(videoMode=VideoMode.COMPATIBILITY,videoCodec=VideoCodecMode.H264_MAIN,
                fsr=FsrMode.FSR1,presentationPacing=PresentationPacing.SMOOTH,
                imageTreatment=ImageTreatmentSettings(ImageSharpening.RCAS_LUMA,200,-10,200,200,200,200))
            store.save(selected)
            val saved=store.read();assertEquals(100,saved.imageTreatment.deband);assertEquals(0,saved.imageTreatment.cleanup)
            assertEquals(50,saved.imageTreatment.temporal)
            val effective=saved.forSession()
            assertEquals(StreamResolution.HD_720,effective.resolution)
            assertFalse(effective.imageTreatment.active);assertEquals(FsrMode.OFF,effective.fsr)
            assertEquals(PresentationPacing.OFF,effective.presentationPacing)
            assertEquals(saved,store.read())
            val low=saved.copy(videoMode=VideoMode.LOW_LATENCY,videoCodec=VideoCodecMode.H264_BASELINE).forSession()
            assertEquals(PresentationPacing.OFF,low.presentationPacing)
            assertEquals(saved.imageTreatment,low.imageTreatment)
        } finally { c.pause().stop().destroy() }
    }
}
