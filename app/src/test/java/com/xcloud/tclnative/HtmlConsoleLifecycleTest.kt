package com.xcloud.tclnative

import android.app.Activity
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HtmlConsoleLifecycleTest {
    @Test fun enteringNativePlaybackDestroysTheHtmlSurfaceAndReturningCreatesOnlyOne() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val store=AppearanceSettingsStore(a);store.save(UiTheme.PS4);DashboardTheme.apply(a,UiTheme.PS4)
        val view=GameDashboardView(a,GameLibrary(a,"html-lifecycle"),{},{},{},{})
        fun count()=(0 until view.childCount).count { view.getChildAt(it) is HtmlConsoleTheme }
        try {
            a.setContentView(view);assertEquals(1,count())
            view.pauseArtwork();assertEquals(0,count())
            view.resumeArtwork();assertEquals(1,count())
            DashboardTheme.apply(a,UiTheme.PS5,view);assertEquals(1,count())
            view.close();assertEquals(0,count())
        } finally { view.close();c.pause().stop().destroy();store.save(UiTheme.GREEN);DashboardTheme.apply(a,UiTheme.GREEN) }
    }
}
