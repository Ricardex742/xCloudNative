package com.xcloud.tclnative

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.xcloud.tclnative.diagnostics.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.MainActivity
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.ByteArrayOutputStream
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SessionReportUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun label(v: View,s: String)=all(v).filterIsInstance<TextView>().single { it.text.toString()==s }
    private fun demo(): SessionReport {
        val t=SessionReportTracker("PARTIDA DE DEMONSTRAÇÃO","Android TV • exemplo","720p • Compatibilidade",30) { 10000 }
        t.begin(0,"Ethernet • dados de demonstração")
        t.timeline = PlaybackTimeline(2, PlaybackOrigin.CATALOG) { 0 }
        repeat(10) { i -> t.add((i+1)*1000L,StreamMetrics(width=1280,height=720,codec="H264",decoder="MediaCodec",
            rttMs=42.0,jitterMs=4.0,packetLossPercent=.2,fps=30.0,bitrateMbps=5.0,decodeMs=7.0,intervalSeconds=1.0,
            totalReceivedVideoBytes=(i+1)*625000L,videoStreamId="v",transportId="t",transportReceivedBytes=(i+1)*650000L,
            transportSentBytes=(i+1)*20000L,droppedFrames=0),InputMetrics(sentBytes=(i+1)*2280L)) }
        return t.finish(10000,SessionEnd.USER)!!
    }
    @Test fun interruptionSurvivesStorageEvenWhenTheUserEndsTheSession() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val reason=SessionFailure.from(java.io.IOException("ICE: FAILED"))
        val report=demo().copy(end=SessionEnd.USER,interruption=reason,interruptionDetails="Toque 1920x1080; enviados 185")
        val store=SessionReportStore(app);store.save(report)
        assertEquals(reason,store.read()!!.interruption)
        assertEquals(report.interruptionDetails,store.read()!!.interruptionDetails)
        assertTrue(store.read()!!.text().contains("Primeira interrupção observada"))
        val prefs=app.getSharedPreferences("session_diagnostic",0)
        val legacy=org.json.JSONObject(prefs.getString("last",null)!!).apply { remove("interruption") }
        prefs.edit().putString("last",legacy.toString()).commit()
        assertNull(store.read()!!.interruption)
    }
    @Test fun latestReportSurvivesActivityRecreationAndCorruptOrFutureRecordsAreIgnored() {
        var c=Robolectric.buildActivity(Activity::class.java).setup()
        val original=demo(); SessionReportStore(c.get()).save(original)
        c.pause().stop().destroy(); c=Robolectric.buildActivity(Activity::class.java).setup()
        val context=c.get(); assertEquals(original,SessionReportStore(context).read())
        val withInput=original.copy(inputDetails="xCLOUD 0.24 • Entrada inicial: nativo • desativado para HUD • 1280×720")
        SessionReportStore(context).save(withInput)
        assertEquals(withInput,SessionReportStore(context).read())
        for(invalid in listOf("bad JSON","{\"schema\":2}","{\"schema\":1,\"end\":\"UNKNOWN\"}")) {
            context.getSharedPreferences("session_diagnostic",0).edit().putString("last",invalid).commit()
            assertNull(SessionReportStore(context).read())
        }
        c.pause().stop().destroy()
    }
    @Test fun exportRequestsAFileAndWritesOnlyTheStructuredStartupDiagnostic() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = c.get()
        try {
            val recorder = PlaybackTimeline(8, PlaybackOrigin.SHORTCUT) { 42 }
            recorder.mark(0, PlaybackStage.PLAY_REQUEST)
            StartupDiagnosticsStore(activity).save("private title", "private detail", recorder.snapshot())
            MainActivity::class.java.getDeclaredMethod("showLatestReport", Function0::class.java).apply {
                isAccessible = true
            }.invoke(activity, null)
            val dialog = ShadowDialog.getLatestDialog() as ConsoleDialog
            label(dialog.panel, "EXPORTAR DIAGNÓSTICO").performClick()
            val picker = checkNotNull(shadowOf(activity).nextStartedActivityForResult)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.action)
            assertEquals("application/json", picker.intent.type)
            val output = ByteArrayOutputStream()
            val uri = Uri.parse("content://diagnostic-test/xcloud.json")
            shadowOf(activity.contentResolver).registerOutputStream(uri, output)
            shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(uri))
            val deadline = System.nanoTime() + 3_000_000_000L
            while (output.size() == 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            val text = output.toString(Charsets.UTF_8.name())
            assertFalse(text.contains("private"))
            assertEquals("SHORTCUT", JSONObject(text).getString("origin"))
            dialog.dismiss()
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test fun reportCanBeCopiedAndAutoDisplayDisabledWithoutChangingOtherSettings() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        val r=demo().copy(inputDetails="Toque nativo: desativado para HUD; espaço de input: 1280×720")
        val store=PlaybackSettingsStore(a); val original=PlaybackSettings(resolution=StreamResolution.HD_720,saturation=125)
        store.save(original)
        var d=SessionReportPanel.show(a,r)
        assertTrue(all(d.body).filterIsInstance<TextView>().any { it.text.toString()==ReportMetric.BUFFER.label.uppercase() })
        assertTrue(all(d.body).filterIsInstance<TextView>().any { it.text.toString()==ReportMetric.BUFFER_TARGET.label.uppercase() })
        assertTrue(all(d.body).filterIsInstance<TextView>().any { it.text.contains("desativado para HUD") })
        label(d.panel,"COPIAR").performClick()
        assertEquals(r.text(),(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString())
        d=SessionReportPanel.show(a,r) { store.save(store.read().copy(sessionReport=false)) }
        label(d.panel,"NÃO ABRIR AUTOMATICAMENTE").performClick()
        assertEquals(original.copy(sessionReport=false),PlaybackSettingsStore(a).read())
        assertEquals(0,StatisticsVisibility().mode)
        c.pause().stop().destroy()
    }
    @Test fun controllerSurfaceAndCodecDetailsSurviveTheOldLengthLimitAndStayBounded() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val store=SessionReportStore(c.get())
            val details="Evento Android e saída da TV: "+"dado ".repeat(300)+" | Codec recebido HEVC Main"
            val report=demo().copy(inputDetails=details)
            store.save(report)
            assertEquals(details,store.read()!!.inputDetails)
            val audio = "Áudio RTP: Opus | buffer WebRTC 100 ms | perdas 0"
            val updated = report.copy(inputDetails="x".repeat(6000),
                mediaDetails=audio + "\n" + "renderer details\n".repeat(900), videoDelivery=VideoDelivery(300, 280, 270))
            store.save(updated)
            val saved = checkNotNull(store.read())
            assertEquals(SessionReport.MAX_INPUT_DETAILS,saved.inputDetails!!.length)
            assertEquals(updated.videoDelivery, saved.videoDelivery)
            assertTrue(checkNotNull(saved.mediaDetails).length <= SessionReport.MAX_MEDIA_DETAILS)
            assertTrue(saved.text().contains(audio))
            assertFalse(checkNotNull(saved.exportJson()).toString().contains(audio))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun failureReasonSurvivesRecreationAndIsVisibleAndCopyableWithoutRequiringAPhonePaste() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val activity=c.get()
        try {
            val reason=SessionFailure(FailureStage.KEEPALIVE,FailureKind.HTTP,503,"ServiceUnavailable","CloudHttpException")
            val report=demo().copy(end=SessionEnd.FAILURE,failure=reason)
            val store=SessionReportStore(activity);store.save(report)
            assertEquals(report,SessionReportStore(activity).read())
            val dialog=SessionReportPanel.show(activity,report)
            val reasonView=all(dialog.body).filterIsInstance<TextView>().single { it.text.contains("MOTIVO DO ENCERRAMENTO") }
            assertTrue(reasonView.text.contains("KEEPALIVE/HTTP_503"));assertTrue(reasonView.isFocusable)
            label(dialog.panel,"COPIAR").performClick()
            assertTrue((activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip!!.getItemAt(0).text.toString().contains("KEEPALIVE/HTTP_503"))
            val decor=dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1050,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.EXACTLY))
            decor.layout(0,0,1050,648)
            val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888);decor.draw(Canvas(bitmap))
            val output=File("build/reports/dashboard/session-failure-reason.png");output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            dialog.dismiss()
            val legacy=demo().copy(end=SessionEnd.FAILURE)
            store.save(legacy);assertNull(store.read()!!.failure)
            val old=SessionReportPanel.show(activity,store.read()!!)
            assertTrue(all(old.body).filterIsInstance<TextView>().any { it.text.contains("Não registrado nesta versão") })
            old.dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun settingsOfferExplicitAutomaticReportChoiceAndLastReportAction() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get(); val store=PlaybackSettingsStore(a)
        store.save(PlaybackSettings()); var opened=0
        val panel=PlaybackSettingsPanel(a,store,{},{},{},{},lastReport={opened++})
        var d=panel.showDiagnostics()
        label(d.body,"Diagnóstico pós-partida: Ativado").performClick()
        d=ShadowDialog.getLatestDialog() as ConsoleDialog
        assertTrue(store.read().sessionReport)
        all(d.body).filterIsInstance<TextView>().single { it.text.contains("○  Desativado") }.performClick()
        assertFalse(store.read().sessionReport)
        d=ShadowDialog.getLatestDialog() as ConsoleDialog
        label(d.body,"Último diagnóstico").performClick(); assertEquals(1,opened)
        c.pause().stop().destroy()
    }
    @Test fun oldWebModeMigratesToNativeTouchAndNoBrowserActivityRemains() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        a.getSharedPreferences("stream_preferences",0).edit().clear().putString("touch_mode","WEB_NATIVE").commit()
        assertEquals(TouchMode.NATIVE,PlaybackSettingsStore(a).read().touchMode)
        assertEquals(TouchMode.NATIVE,PlaybackSettingsStore(a).read().touchMode)
        val activities=a.packageManager.getPackageInfo(a.packageName,android.content.pm.PackageManager.GET_ACTIVITIES).activities
        assertFalse(activities.any { it.name.contains("OfficialCloud") })
        c.pause().stop().destroy()
    }
    @Test fun reportFitsLandscapeAndEveryMetricAndFindingIsReachableByFocus() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        val d=SessionReportPanel.show(a,demo()) {}
        shadowOf(Looper.getMainLooper()).idle()
        val decor=d.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1050,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.EXACTLY))
        decor.layout(0,0,1050,648)
        assertTrue(d.panel.measuredHeight<=648)
        assertTrue(label(d.panel,"CONCLUIR").hasFocus())
        val cards=all(d.body).filterIsInstance<android.widget.LinearLayout>().filter { it.isFocusable }
        assertEquals(9,cards.size)
        cards.forEach { assertTrue(it.requestFocus()); assertFalse(it.defaultFocusHighlightEnabled) }
        val scroll=all(d.panel).filterIsInstance<ScrollView>().single()
        val last=all(d.body).filterIsInstance<TextView>().last { it.isFocusable }
        assertTrue(last.requestFocus()); shadowOf(Looper.getMainLooper()).idle()
        scroll.fullScroll(View.FOCUS_UP); label(d.panel,"CONCLUIR").requestFocus()
        val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val output=File("build/reports/dashboard/session-report.png"); output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        label(d.panel,"CONCLUIR").performClick(); assertFalse(d.isShowing)
        c.pause().stop().destroy()
    }
}
