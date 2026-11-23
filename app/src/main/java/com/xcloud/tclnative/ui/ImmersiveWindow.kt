package com.xcloud.tclnative.ui

import android.graphics.Color
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
    fun inheritPlaybackDisplay(window: Window, playback: Window) {
        val display = playback.attributes
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = display.preferredDisplayModeId
            preferredRefreshRate = display.preferredRefreshRate
            if (Build.VERSION.SDK_INT >= 30) preferMinimalPostProcessing = display.preferMinimalPostProcessing
            windowAnimations = 0
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        apply(window, edgeToEdge = true)
    }

    @Suppress("DEPRECATION")
    fun apply(window: Window, edgeToEdge: Boolean = false) {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if(Build.VERSION.SDK_INT>=28) {
            val params=window.attributes
            val mode=if(edgeToEdge && Build.VERSION.SDK_INT>=30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            var changed=params.layoutInDisplayCutoutMode!=mode
            params.layoutInDisplayCutoutMode=mode
            if(Build.VERSION.SDK_INT>=30 && edgeToEdge && params.fitInsetsTypes!=0) {
                params.setFitInsetsTypes(0)
                changed=true
            }
            if(changed) window.attributes=params
        }
        val barColor=if(edgeToEdge) Color.TRANSPARENT else Color.BLACK
        window.statusBarColor=barColor
        window.navigationBarColor=barColor
        if(Build.VERSION.SDK_INT>=29 && edgeToEdge) {
            window.isStatusBarContrastEnforced=false
            window.isNavigationBarContrastEnforced=false
        }
        val legacyLayout=if(edgeToEdge && Build.VERSION.SDK_INT<30) View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION else 0
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or legacyLayout
        if(Build.VERSION.SDK_INT>=30) {
            if(edgeToEdge) window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
    }

    fun observeCutout(view: View, avoidKeyboard: Boolean = false, update: (Rect) -> Unit) {
        if(Build.VERSION.SDK_INT<28) return
        view.setOnApplyWindowInsetsListener { target,insets ->
            val cutout=insets.displayCutout ?: target.rootWindowInsets?.displayCutout
            val safe=if(cutout==null) Rect() else Rect(cutout.safeInsetLeft,cutout.safeInsetTop,cutout.safeInsetRight,cutout.safeInsetBottom)
            if(avoidKeyboard && Build.VERSION.SDK_INT>=30) {
                val ime=insets.getInsets(WindowInsets.Type.ime())
                safe.set(maxOf(safe.left,ime.left),maxOf(safe.top,ime.top),maxOf(safe.right,ime.right),maxOf(safe.bottom,ime.bottom))
            }
            update(safe)
            insets
        }
        view.doOnAttach { it.requestApplyInsets() }
    }

    /** Extend the artwork to the window edge while the interactive content stays inside the cutout. */
    fun protectContent(view: ViewGroup,vararg artwork: View,avoidKeyboard: Boolean = false) {
        val base=Rect(view.paddingLeft,view.paddingTop,view.paddingRight,view.paddingBottom)
        if(artwork.isNotEmpty()) view.clipToPadding=false
        observeCutout(view,avoidKeyboard) { inset ->
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

    fun diagnostics(window: Window, content: View): String {
        fun frame(view: View): String {
            val location=IntArray(2)
            view.getLocationOnScreen(location)
            return "${view.width}x${view.height}@${location[0]},${location[1]}"
        }
        val views="WINDOW_GEOMETRY decor=${frame(window.decorView)} content=${frame(content)}"
        if(Build.VERSION.SDK_INT<30) return views
        return runCatching {
            val current=window.windowManager.currentWindowMetrics
            val maximum=window.windowManager.maximumWindowMetrics
            val insets=content.rootWindowInsets ?: current.windowInsets
            val bars=insets.getInsets(WindowInsets.Type.systemBars())
            val cutout=insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            val displayCutout=maximum.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            "$views bounds=${current.bounds.toShortString()} max=${maximum.bounds.toShortString()}" +
                " cutout=${cutout.left},${cutout.top},${cutout.right},${cutout.bottom}" +
                " maxCutout=${displayCutout.left},${displayCutout.top},${displayCutout.right},${displayCutout.bottom}" +
                " bars=${bars.left},${bars.top},${bars.right},${bars.bottom}" +
                " fitTypes=${window.attributes.fitInsetsTypes} cutoutMode=${window.attributes.layoutInDisplayCutoutMode}"
        }.getOrDefault(views)
    }
}
