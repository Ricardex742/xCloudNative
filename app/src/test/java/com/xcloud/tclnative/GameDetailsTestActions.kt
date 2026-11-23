package com.xcloud.tclnative

import android.os.Looper
import android.view.View
import com.xcloud.tclnative.ui.dashboard.ConsoleDialog
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

internal fun currentGameDetails(): ConsoleDialog = (ShadowDialog.getLatestDialog() as ConsoleDialog).also {
    assertTrue(it.isShowing)
}

internal fun confirmGameDetailsPlay() {
    val panel = currentGameDetails()
    assertTrue(panel.panel.findViewWithTag<View>("game-details-play").performClick())
    shadowOf(Looper.getMainLooper()).idle()
}
