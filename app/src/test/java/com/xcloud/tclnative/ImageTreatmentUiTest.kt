package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageTreatmentUiTest {
    @Test fun fsrActivationAndSameFrameComparisonPreserveStreamAndControllerSettings() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get().apply { setContentView(FrameLayout(this)) }
            activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(activity);val original=store.read()
            val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
            val first=panel.showAdvancedImage();capture(first,"fsr-detail-options")
            assertEquals(original,store.read())
            click(first,"Método:")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"AMD FSR 1.0.2")
            assertEquals(FsrMode.FSR1,store.read().fsr);assertEquals(FsrOutput.AUTO,store.read().fsrOutput)
            assertEquals(original.resolution,store.read().resolution);assertEquals(original.touchMode,store.read().touchMode)
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Qualidade do processamento")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Qualidade")
            assertEquals(ReconstructionQuality.QUALITY,store.read().reconstruction.quality)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            click(panel.showUpscaling(),"Comparação:")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Dividida")
            assertEquals(ImageComparison.SPLIT,store.read().imageComparison)
            assertEquals(original.videoCodec,store.read().videoCodec)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        } finally { c.pause().stop().destroy() }
    }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun click(dialog: ConsoleDialog,text: String) = all(dialog.body).filterIsInstance<TextView>()
        .single { it.isClickable && it.text.contains(text) }.performClick()
    private fun capture(dialog: ConsoleDialog,name: String) {
        val view=dialog.window!!.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(dialog.window!!.attributes.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
        assertTrue(view.measuredHeight<=648)
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File("build/reports/dashboard/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun treatmentAndCodecMenusKeepTheOriginalStreamAndControllerChoices() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get().apply { setContentView(FrameLayout(this)) }
            activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(activity);val original=store.read()
            val updates=mutableListOf<PlaybackSettings>();val panel=PlaybackSettingsPanel(activity,store,{ updates+=it },{},{},{})
            val treatment=panel.showTreatment();capture(treatment,"image-treatment-options")
            assertEquals(original,store.read());assertTrue(updates.isEmpty())
            treatment.dismiss();click(panel.showUpscaling(),"Perfil de imagem:")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Perfil leve")
            assertEquals(ImageSharpening.RCAS_LUMA,store.read().imageTreatment.sharpening)
            assertEquals(0,store.read().imageTreatment.deband);assertFalse(store.read().imageTreatment.spatial);assertEquals(25,store.read().sharpness)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val scale=panel.chooseFsr()
            assertEquals(2,all(scale.body).filterIsInstance<TextView>().count { it.isClickable })
            click(scale,"AMD FSR 1.0.2")
            assertEquals(FsrMode.FSR1,store.read().fsr)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val codec=panel.chooseCodec();capture(codec,"h264-main-options");click(codec,"H.264 Main")
            assertEquals(VideoCodecMode.H264_MAIN,store.read().videoCodec)
            assertEquals(StreamResolution.HD_1080_HQ,store.read().resolution)
            assertEquals(original.touchMode,store.read().touchMode)
            assertEquals(0,store.read().imageTreatment.deband)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val image=panel.showUpscaling();click(image,"Restaurar imagem original")
            assertFalse(store.read().imageTreatment.active);assertEquals(FsrMode.OFF,store.read().fsr)
            assertEquals(VideoCodecMode.H264_MAIN,store.read().videoCodec)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun eachMethodShowsOnlyItsOwnControlsAndChildMenusReturnToTheSessionCaller() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get().apply { setContentView(FrameLayout(this)) }
            val store=PlaybackSettingsStore(activity);val original=store.read()
            val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
            val image=panel.showImage()
            val rows=all(image.body).filterIsInstance<TextView>().filter { it.isClickable }
            assertTrue(rows.any { it.text.contains("Perfil de imagem:") })
            assertNotNull(image.panel.findViewWithTag<TextView>("settings-section-network"))
            assertFalse(rows.any { it.text.contains("Upscaling e tratamento:") })
            image.dismiss()
            for(mode in FsrMode.entries) {
                val settings=original.copy(fsr=mode,videoMode=VideoMode.LOW_LATENCY);store.save(settings)
                val dialog=panel.showAdvancedImage();capture(dialog,"organized-image-${mode.name.lowercase()}")
                val names=all(dialog.body).filterIsInstance<TextView>().filter { it.isClickable }.map { it.text.toString() }
                assertEquals(settings,store.read())
                assertEquals(mode!=FsrMode.OFF,names.any { it.contains("Saída do escalador:") })
                assertEquals(mode==FsrMode.OFF,names.any { it.contains("Upscaling nativo:") })
                assertTrue(names.any { it.contains("Método de nitidez:") })
                assertEquals(mode==FsrMode.FSR1,names.any { it.startsWith("Intensidade dos detalhes:") })
                assertTrue(names.any { it.startsWith("Nitidez:") })
                assertTrue(names.any { it.contains("Filtros adicionais:") });dialog.dismiss()
            }
            store.save(original)
            var back=0
            val session=panel.showUpscaling(onBack={back++});click(session,"Avançado — métodos e filtros")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Método:")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"AMD FSR 1.0.2")
            (ShadowDialog.getLatestDialog() as ConsoleDialog).cancel()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as ConsoleDialog).cancel()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1,back)
            store.save(original.copy(videoMode=VideoMode.COMPATIBILITY,fsr=FsrMode.FSR1))
            val compatibility=panel.showUpscaling()
            assertFalse(all(compatibility.body).filterIsInstance<TextView>().any { it.isClickable && it.text.contains("Intensidade") })
            compatibility.dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun advancedFiltersHaveNoDuplicateProfilesAndAdjustOnlyTheSelectedStage() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val store=PlaybackSettingsStore(c.get());val panel=PlaybackSettingsPanel(c.get(),store,{},{},{},{})
            for(mode in FsrMode.entries) {
                val before=PlaybackSettings(fsr=mode,sharpness=0,fsrSharpness=75,
                    reconstruction=ReconstructionSettings(strength=100),imageComparison=ImageComparison.ORIGINAL)
                store.save(before);val dialog=panel.showTreatment()
                assertFalse(all(dialog.body).filterIsInstance<TextView>().any { it.isClickable && it.text.contains("Perfil") })
                click(dialog,"Limpeza de compressão:")
                click(ShadowDialog.getLatestDialog() as ConsoleDialog,"25%")
                val after=store.read()
                assertEquals(25,after.imageTreatment.cleanup)
                assertEquals(0,after.imageTreatment.temporal);assertEquals(0,after.imageTreatment.neural)
                assertEquals(before.fsr,after.fsr);assertEquals(before.resolution,after.resolution)
                assertEquals(before.touchMode,after.touchMode);assertEquals(before.videoCodec,after.videoCodec)
                assertEquals(before.sharpness,after.sharpness);assertEquals(before.fsrSharpness,after.fsrSharpness)
                assertEquals(before.reconstruction,after.reconstruction)
                assertEquals(before.imageTreatment.sharpening,after.imageTreatment.sharpening)
                (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test fun everyCodecChoiceIsSavedWithoutChangingImageOrControllerSettings() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            c.get().setContentView(FrameLayout(c.get()))
            val store=PlaybackSettingsStore(c.get());val panel=PlaybackSettingsPanel(c.get(),store,{},{},{},{})
            val original=PlaybackSettings(fsr=FsrMode.FSR1,fsrOutput=FsrOutput.UHD_4K,
                imageTreatment=ImageTreatmentSettings(deband=25,chroma=50),touchMode=TouchMode.NATIVE)
            for(mode in VideoCodecMode.entries) {
                store.save(original);val dialog=panel.chooseCodec()
                click(dialog,mode.label)
                val saved=store.read()
                assertEquals(original.copy(videoCodec=mode,resolution=if(mode==VideoCodecMode.H264_BASELINE) StreamResolution.HD_720 else original.resolution),saved)
                assertEquals(mode,PlaybackSettingsStore(c.get()).read().videoCodec)
                (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            }
        } finally { c.pause().stop().destroy() }
    }

}
