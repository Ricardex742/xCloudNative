package com.xcloud.tclnative.ui

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.doOnAttach

/** Apply to every owned window: a focusable dialog otherwise reveals Android's system bars. */
internal object ImmersiveWindow {
    @Suppress("DEPRECATION")
    fun apply(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if(Build.VERSION.SDK_INT>=28) {
            val params=window.attributes
            if(params.layoutInDisplayCutoutMode!=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes=params
            }
        }
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

    fun observeCutout(view: View, update: (Rect) -> Unit) {
        if(Build.VERSION.SDK_INT<28) return
        view.setOnApplyWindowInsetsListener { target,insets ->
            val cutout=insets.displayCutout ?: target.rootWindowInsets?.displayCutout
            update(if(cutout==null) Rect() else Rect(cutout.safeInsetLeft,cutout.safeInsetTop,cutout.safeInsetRight,cutout.safeInsetBottom))
            insets
        }
        view.doOnAttach { it.requestApplyInsets() }
    }

    /** Extend the artwork to the window edge while the interactive content stays inside the cutout. */
    fun protectContent(view: ViewGroup,vararg artwork: View) {
        val base=Rect(view.paddingLeft,view.paddingTop,view.paddingRight,view.paddingBottom)
        if(artwork.isNotEmpty()) view.clipToPadding=false
        observeCutout(view) { inset ->
            val left=base.left+inset.left
            val top=base.top+inset.top
            val right=base.right+inset.right
            val bottom=base.bottom+inset.bottom
            if(view.paddingLeft==left && view.paddingTop==top && view.paddingRight==right && view.paddingBottom==bottom) return@observeCutout
            view.setPadding(left,top,right,bottom)
            artwork.forEach { background ->
                val params=background.layoutParams as ViewGroup.MarginLayoutParams
                params.setMargins(-left,-top,-right,-bottom)
                background.layoutParams=params
            }
        }
    }
}
