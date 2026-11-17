package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FriendsPanelTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun texts()=all((ShadowDialog.getLatestDialog() as ConsoleDialog).panel).filterIsInstance<TextView>()
    private fun capture(name: String) {
        val view=(ShadowDialog.getLatestDialog() as ConsoleDialog).panel
        view.measure(View.MeasureSpec.makeMeasureSpec(1050,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File("build/reports/social/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun friendDetailsOpenOnlyTheMatchingCloudGameAndPrivacyFailureDoesNotLookEmpty()=runTest {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val game=GameTitle("game","Cloud game",xboxTitleId="123")
        var played: GameTitle?=null;var requests=0;var valid=true
        val panel=FriendsPanel(activity.get(),this,load={ requests++
            listOf(XboxFriend("1","Demo friend",FriendPresence.ONLINE,"Playing", "123"))
        },games={listOf(game)},valid={valid},play={played=it},back={})
        try {
            panel.show();runCurrent()
            assertEquals(1,requests)
            capture("friends")
            texts().single { it.isClickable && it.text.startsWith("Demo friend") }.performClick()
            assertTrue(texts().any { it.text.contains("sua própria sessão") })
            assertFalse(texts().any { it.isClickable && it.text.startsWith("Entrar") })
            capture("friend-game")
            texts().single { it.isClickable && it.text.toString()=="Abrir jogo na nuvem" }.performClick()
            assertEquals(game,played)
            panel.show();runCurrent();played=null
            texts().single { it.isClickable && it.text.startsWith("Demo friend") }.performClick()
            valid=false
            texts().single { it.isClickable && it.text.toString()=="Abrir jogo na nuvem" }.performClick()
            assertNull(played)
            panel.close()
            val failed=FriendsPanel(activity.get(),this,{throw java.io.IOException("private endpoint")},{emptyList()},{true},{},{})
            failed.show();runCurrent()
            assertTrue(texts().any { it.text.contains("privacidade") })
            assertFalse(texts().any { it.text.contains("Nenhum amigo") || it.text.contains("private endpoint") })
            failed.close()
        } finally { panel.close();activity.pause().stop().destroy() }
    }
    @Test fun closingCancelsPendingLoadAndPaginationDoesNotFetchAgain()=runTest {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        var cancelled=false;var calls=0
        val pending=FriendsPanel(activity.get(),this,{try { awaitCancellation() } finally {cancelled=true}},{emptyList()},{true},{},{})
        pending.show();runCurrent();pending.close();runCurrent();assertTrue(cancelled)
        val list=FriendsPanel(activity.get(),this,{
            calls++;(0 until 60).map { XboxFriend("$it","Demo $it",FriendPresence.UNKNOWN,null,null) }
        },{emptyList()},{true},{},{})
        try {
            list.show();runCurrent()
            assertEquals(50,texts().count { it.isClickable && it.text.startsWith("Demo ") })
            texts().single { it.isClickable && it.text.toString().equals("Próxima página",ignoreCase=true) }.performClick()
            assertEquals(10,texts().count { it.isClickable && it.text.startsWith("Demo ") })
            assertEquals(1,calls)
        } finally { list.close();activity.pause().stop().destroy() }
    }
}
