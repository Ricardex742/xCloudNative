package com.xcloud.tclnative

import android.content.Context
import com.xcloud.tclnative.diagnostics.StartupDiagnosticsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StartupDiagnosticsStoreTest {
    @Test fun aCancelledQueueAttemptSurvivesReopeningAndCanBeClearedWhenVideoStarts() {
        val context=RuntimeEnvironment.getApplication()
        val store=StartupDiagnosticsStore(context);store.clear()
        assertNull(store.read())
        store.save("Demo game","STARTUP/NO_VIDEO\nXbox /state: WaitingForResources; replies=100\nQUEUE_ESTIMATE/seconds=0")
        val reopened=StartupDiagnosticsStore(context).read()!!
        assertEquals("Demo game",reopened.game);assertTrue(reopened.text.contains("WaitingForResources"))
        store.save("Another game","STARTUP/NO_VIDEO; failure=RENDERER/FAILED")
        assertEquals("Another game",StartupDiagnosticsStore(context).read()!!.game)
        store.clear();assertNull(StartupDiagnosticsStore(context).read())
    }
    @Test fun oversizedAndMalformedStoredAttemptsCannotOverrunTheDiagnosticPanelOrCrashIt() {
        val context=RuntimeEnvironment.getApplication();val store=StartupDiagnosticsStore(context)
        store.save("g".repeat(1000),"x".repeat(20000))
        val entry=store.read()!!;assertEquals(256,entry.game.length);assertEquals(8192,entry.text.length)
        context.getSharedPreferences("startup_diagnostics",Context.MODE_PRIVATE).edit().putInt("text",4).commit()
        assertNull(store.read());store.clear()
    }
}
