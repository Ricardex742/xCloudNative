package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31],qualifiers="w1280dp-h720dp-land-television-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLabUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList()
    private fun latest()=ShadowDialog.getLatestDialog() as ConsoleDialog
    private fun click(text: String)=all(latest().body).filterIsInstance<TextView>().single {it.isClickable&&it.text.contains(text)}.performClick()
    @Test fun allFeaturesHaveOneEntryAndRestoreOriginalAlsoDisablesTheLab(){
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val a=c.get().apply {setContentView(FrameLayout(this))}
            UiText.apply(a,AppLanguage.PORTUGUESE);DashboardTheme.apply(a,UiTheme.XBOX_SERIES)
            a.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(a);val original=store.read();val updates=mutableListOf<PlaybackSettings>()
            val panel=PlaybackSettingsPanel(a,store,{updates+=it},{},{},{})
            panel.showUpscaling();click("Laboratório de imagem:")
            assertFalse(store.read().imageLab.enabled);assertTrue(updates.isEmpty())
            click("Laboratório:");assertTrue(store.read().imageLab.enabled)
            click("Superamostragem:");click("1,50")
            assertEquals(ImageSupersampling.X150,store.read().imageLab.supersampling)
            click("Geração de quadros:");click("experimental")
            assertEquals(ExperimentalFrameGeneration.AUTO_30_TO_60,store.read().imageLab.frameGeneration)
            click("Reconstrução neural:");assertTrue(store.read().imageLab.neural)
            val d=latest();val v=d.window!!.decorView
            v.measure(View.MeasureSpec.makeMeasureSpec(d.window!!.attributes.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
            v.layout(0,0,v.measuredWidth,v.measuredHeight)
            assertTrue(all(d.body).filterIsInstance<TextView>().any {it.isClickable&&it.text.contains("Proteção de HUDs")})
            val image=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(image))
            File("build/reports/dashboard/image-lab-experimental.png").apply {parentFile!!.mkdirs()}.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            assertEquals(original.resolution,store.read().resolution);assertEquals(original.videoCodec,store.read().videoCodec)
            assertEquals(original.touchMode,store.read().touchMode)
            latest().dismiss();panel.showUpscaling();click("Restaurar imagem original")
            assertFalse(store.read().imageLab.enabled);assertFalse(store.read().imageTreatment.active)
            latest().dismiss()
        }finally{DashboardTheme.apply(RuntimeEnvironment.getApplication(),UiTheme.GREEN);c.pause().stop().destroy()}
    }
}
