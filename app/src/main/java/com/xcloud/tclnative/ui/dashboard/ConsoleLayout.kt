package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.os.Build
import android.text.StaticLayout
import android.text.Layout
import android.util.TypedValue
import android.widget.TextView
import com.xcloud.tclnative.settings.InputDeviceProfile

/** Phone geometry uses Android dp/sp; the television scenes keep their console reference canvas. */
internal fun consolePhone(context: Context,width: Int,height: Int): Boolean =
    minOf(width,height)/context.resources.displayMetrics.density<600f && !InputDeviceProfile.read(context).television

internal fun fitConsoleText(view: TextView,width: Int,height: Int,min: Float,max: Float,lines: Int) {
    view.maxLines=lines
    view.includeFontPadding=false
    val low=min.toInt().coerceAtLeast(1);val high=max.toInt().coerceAtLeast(low+1)
    if(Build.VERSION.SDK_INT>=26) view.setAutoSizeTextTypeUniformWithConfiguration(low,high,1,TypedValue.COMPLEX_UNIT_PX)
    else {
        // API 24/25: bounded binary search only when content/geometry changes, never in a draw tick.
        val paint=android.text.TextPaint(view.paint);var left=low;var right=high;var best=low
        while(left<=right) {
            val size=(left+right)/2;paint.textSize=size.toFloat()
            @Suppress("DEPRECATION") val layout=StaticLayout(view.text,paint,(width-view.compoundPaddingLeft-view.compoundPaddingRight).coerceAtLeast(1),Layout.Alignment.ALIGN_NORMAL,1f,0f,false)
            if(layout.lineCount<=lines && layout.height<=height-view.compoundPaddingTop-view.compoundPaddingBottom) { best=size;left=size+1 } else right=size-1
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,best.toFloat())
    }
}
