package com.xcloud.tclnative

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.*
import android.widget.*
import com.xcloud.tclnative.auth.PreferencesTokenStore
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileLoginUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    @Test fun mobileOffersNormalMicrosoftLoginAndQrAndTvKeepsQrFirst() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        UiText.apply(a,AppLanguage.PORTUGUESE)
        try {
            for(mobile in listOf(true,false)) {
                var qr=0;var browser=0
                val view=ConsoleLoginView(a,{qr++},{},{},browserSignIn={browser++},preferBrowser=mobile)
                a.setContentView(view)
                view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));view.layout(0,0,1280,720)
                shadowOf(Looper.getMainLooper()).idle()
                val normal=all(view).filterIsInstance<TextView>().single { it.isClickable && it.text=="ENTRAR COM MICROSOFT" }
                val qrButton=all(view).filterIsInstance<TextView>().single { it.isClickable && it.text=="ENTRAR COM QR CODE" }
                assertTrue(normal.isShown);assertTrue(qrButton.isShown)
                val screenshot=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(screenshot))
                File("build/reports/themes/login-${if(mobile) "mobile" else "tv"}-methods.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG,100,it)
                };screenshot.recycle()
                view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
                assertEquals(if(mobile) 1 else 0,browser);assertEquals(if(mobile) 0 else 1,qr)
                normal.performClick();qrButton.performClick();assertEquals(if(mobile) 2 else 1,browser)
                view.showConnecting("Connecting");normal.performClick();qrButton.performClick()
                assertEquals(if(mobile) 2 else 1,browser)
                view.beginQr();assertFalse(all(view).filterIsInstance<TextView>().any { it.isShown && it.text=="ENTRAR COM MICROSOFT" })
                view.landing();assertTrue(all(view).filterIsInstance<TextView>().any { it.isShown && it.text=="ENTRAR COM MICROSOFT" })
                view.close()
            }
        } finally { c.pause().stop().destroy() }
    }
    @Test fun microsoftButtonRequestsADeviceCodeThenCopiesItAndOpensTheApprovalPage() {
        val app = RuntimeEnvironment.getApplication()
        PreferencesTokenStore(app).clear()
        UiText.apply(app, AppLanguage.PORTUGUESE)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"verification_uri":"https://www.microsoft.com/link",
                "user_code":"DEMO1234","device_code":"fixture","expires_in":900,"interval":60}"""))
            TestShortcutActivity.server = server
            val lifecycle = Robolectric.buildActivity(TestShortcutActivity::class.java).setup()
            val activity = lifecycle.get()
            val view = all(activity.findViewById(android.R.id.content)).filterIsInstance<ConsoleLoginView>().single()
            fun action(label: String) = all(view).filterIsInstance<TextView>().firstOrNull { it.text == label && it.isShown }
            try {
                checkNotNull(action("ENTRAR COM MICROSOFT")).performClick()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                val looper = shadowOf(Looper.getMainLooper())
                while (action("COPIAR CÓDIGO E ABRIR") == null && System.nanoTime() < deadline) {
                    looper.idle()
                    Thread.sleep(5)
                }
                val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("/device", request.path)
                assertFalse(request.body.readUtf8().contains("redirect_uri"))
                assertNull(shadowOf(activity).nextStartedActivity)
                checkNotNull(action("COPIAR CÓDIGO E ABRIR")).performClick()
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                assertEquals("DEMO1234", clipboard.primaryClip?.getItemAt(0)?.text)
                val approval = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW, approval.action)
                assertEquals("https://www.microsoft.com/link", approval.dataString)
                view.showApprovalConnection(interrupted = true)
                val labels = all(view).filterIsInstance<TextView>()
                assertTrue(labels.any { it.isShown && it.text.contains("Tentando novamente com o mesmo código") })
                assertTrue(labels.any { it.isShown && it.text == "DEMO1234" })
                assertTrue(checkNotNull(action("COPIAR CÓDIGO E ABRIR")).isEnabled)
                assertNull(action("GERAR NOVO CÓDIGO"))
                view.showApprovalConnection(interrupted = false)
                assertTrue(labels.any { it.isShown && it.text == "Aguardando sua aprovação na Microsoft…" })
                assertEquals(1, server.requestCount)
            } finally {
                lifecycle.pause().stop().destroy()
                TestShortcutActivity.server = null
            }
        }
    }
}
