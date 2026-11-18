package com.xcloud.tclnative

import android.content.Intent
import android.content.pm.ActivityInfo
import com.xcloud.tclnative.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class LauncherSmokeTest {
    @Test fun packagedManifestResolvesThePhoneAndTvLaunchersInLandscape() {
        val app = RuntimeEnvironment.getApplication()
        val launchers = listOf(app.packageManager.getLaunchIntentForPackage(app.packageName),
            app.packageManager.getLeanbackLaunchIntentForPackage(app.packageName))
        launchers.forEach { launcher ->
            val intent = checkNotNull(launcher)
            val activity = app.packageManager.resolveActivity(intent, 0)?.activityInfo
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertEquals(MainActivity::class.java.name, activity?.name)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity?.screenOrientation)
            assertTrue(checkNotNull(activity).exported)
        }
    }
}
