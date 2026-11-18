package com.xcloud.tclnative.ui

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/** Apply to every owned window: a focusable dialog otherwise reveals Android's system bars. */
internal object ImmersiveWindow {
    @Suppress("DEPRECATION")
    fun apply(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor=android.graphics.Color.BLACK
        window.navigationBarColor=android.graphics.Color.BLACK
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        if(Build.VERSION.SDK_INT>=30) {
            window.insetsController?.apply {
                systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
    }
}
