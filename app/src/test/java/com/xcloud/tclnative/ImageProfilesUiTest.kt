package com.xcloud.tclnative

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xcloud.tclnative.i18n.AppLanguage
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageProfilesUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun latest()=ShadowDialog.getLatestDialog() as ConsoleDialog
    private fun click(s: String)=all(latest().body).filterIsInstance<TextView>().single {it.isClickable&&it.text.contains(s)}.performClick()
    @Test fun simpleProfilesAndCommonComparisonPersistWithoutOpeningAdvancedControls() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=controller.get();UiText.apply(activity,AppLanguage.PORTUGUESE)
            activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(activity);val before=store.read()
            val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
            panel.showUpscaling()
            capturePanel(latest(),"image-profiles-simple")
            assertFalse(all(latest().body).filterIsInstance<TextView>().any {it.isClickable&&it.text.contains("Laboratório:")})
            click("Perfil de imagem:");click("Perfil leve")
            assertEquals(ImageProfile.LIGHT,ImageProfiles.selected(store.read()))
            capturePanel(latest(),"image-profiles-light")
            click("Intensidade:");click("50%")
            assertEquals(50,store.read().sharpness)
            click("Perfil de imagem:");click("AMD FSR 1.0.2")
            click("Intensidade:");click("75%")
            val fsr=PlaybackSettingsStore(activity).read()
            assertEquals(75,fsr.fsrSharpness)
            assertEquals(0,fsr.reconstruction.strength);assertEquals(0,fsr.imageTreatment.temporal)
            click("Comparação:");click("Dividida")
            assertEquals(ImageComparison.SPLIT,PlaybackSettingsStore(activity).read().imageComparison)
            assertEquals(before.resolution,store.read().resolution);assertEquals(before.videoCodec,store.read().videoCodec)
            assertEquals(before.touchMode,store.read().touchMode)
            latest().dismiss()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun oldCustomChoicesRemainUntouchedAndCompatibilitySuppressesAllComparison() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=controller.get();UiText.apply(activity,AppLanguage.PORTUGUESE)
            val store=PlaybackSettingsStore(activity)
            val old=PlaybackSettings(fsr=FsrMode.FSR1,imageTreatment=ImageTreatmentSettings(cleanup=75))
            store.save(old)
            // Legacy reconstruction comparison does not opt the entire app into the new comparison.
            activity.getSharedPreferences("stream_preferences",0).edit().putString("image_comparison","ORIGINAL").remove("image_comparison_all").commit()
            assertEquals(old,store.read())
            PlaybackSettingsPanel(activity,store,{},{},{},{}).showUpscaling()
            assertTrue(all(latest().body).filterIsInstance<TextView>().any {it.text.toString().contains("Personalizado")})
            assertEquals(old,store.read());latest().dismiss()
            val compatibility=old.copy(videoMode=VideoMode.COMPATIBILITY,imageComparison=ImageComparison.SPLIT)
            assertEquals(ImageComparison.PROCESSED,compatibility.forSession().imageComparison)
        } finally { controller.pause().stop().destroy() }
    }
}
