package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import com.xcloud.tclnative.R
import com.xcloud.tclnative.settings.ConsoleScene
import kotlin.math.roundToInt

/** Original brand artwork, with monochrome variants on dark surfaces; never a font approximation. */
internal class ConsoleBrandView(context: Context): FrameLayout(context) {
    private val mark=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val word=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val nativeWord=TextView(context).apply { text="xCLOUD";typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL;includeFontPadding=false;isFocusable=false }
    private var scene=ConsoleScene.NONE
    private var geometry=0L
    init { isFocusable=false;addView(mark);addView(word);addView(nativeWord);refresh() }
    fun refresh() {
        scene=DashboardTheme.palette.theme.scene;contentDescription=DashboardTheme.palette.theme.label
        mark.imageTintList=null;word.imageTintList=null;word.visibility=View.GONE
        mark.visibility=if(scene.playStation) View.GONE else View.VISIBLE
        nativeWord.visibility=if(scene.playStation) View.VISIBLE else View.GONE
        when(scene) {
            ConsoleScene.XBOX_360,ConsoleScene.XBOX_360_NXE,ConsoleScene.XBOX_360_METRO -> {
                mark.setImageResource(R.drawable.console_logo_360_sphere);word.setImageResource(R.drawable.console_logo_360_wordmark);word.visibility=View.VISIBLE
                word.imageTintList=ColorStateList.valueOf(if(scene==ConsoleScene.XBOX_360_NXE) Color.WHITE else 0xff4c583c.toInt())
            }
            ConsoleScene.XBOX_ONE -> { mark.setImageResource(R.drawable.console_logo_one);mark.imageTintList=ColorStateList.valueOf(Color.WHITE) }
            ConsoleScene.XBOX_SERIES -> {
                mark.setImageResource(R.drawable.console_logo_xbox_wordmark);word.setImageDrawable(CatalogMarkDrawable(resources,R.drawable.badge_series_xs_vector));word.visibility=View.VISIBLE
                mark.imageTintList=ColorStateList.valueOf(Color.WHITE);word.imageTintList=ColorStateList.valueOf(Color.WHITE)
            }
            ConsoleScene.XBOX_ORIGINAL -> mark.setImageResource(R.drawable.console_logo_original)
            ConsoleScene.PS4,ConsoleScene.PS5 -> mark.setImageDrawable(null)
            ConsoleScene.NONE -> mark.setImageResource(R.drawable.brand_logo)
        }
        geometry=0L;requestLayout();invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=View.MeasureSpec.getSize(widthMeasureSpec);val h=View.MeasureSpec.getSize(heightMeasureSpec)
        val key=(w.toLong() shl 32) or h.toLong()
        if(key!=geometry) { geometry=key;layoutArtwork(w,h) }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    /** Right edge of the fitted artwork, which can be narrower than the brand view. */
    fun artworkRight(w: Int, h: Int): Float {
        if (scene.playStation) {
            val paint = Paint(nativeWord.paint).apply { textSize = h * .58f }
            return paint.measureText(nativeWord.text.toString()).coerceAtMost(w.toFloat())
        }
        val start = if (word.visibility == View.VISIBLE) wordLeft(w, h) else 0f
        val slot = w - start
        val drawable = (if (word.visibility == View.VISIBLE) word else mark).drawable
        val artwork = if (drawable != null && drawable.intrinsicHeight > 0) {
            minOf(slot, h.toFloat() * drawable.intrinsicWidth / drawable.intrinsicHeight)
        } else slot
        return start + (slot + artwork) / 2
    }

    private fun wordLeft(w: Int, h: Int) =
        if (scene == ConsoleScene.XBOX_SERIES) w * .52f else minOf(h.toFloat(), w * .25f) * 1.15f

    private fun layoutArtwork(w: Int,h: Int) {
        nativeWord.setTextSize(TypedValue.COMPLEX_UNIT_PX,h*.58f)
        nativeWord.layoutParams=LayoutParams(w,h)
        fun item(v: View,x: Float,y: Float,width: Float,height: Float) {
            v.layoutParams=LayoutParams(width.roundToInt(),height.roundToInt()).apply { leftMargin=x.roundToInt();topMargin=y.roundToInt() }
        }
        if(scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_360_NXE || scene==ConsoleScene.XBOX_360_METRO) {
            val ball=minOf(h.toFloat(),w*.25f);val wordStart=wordLeft(w,h)
            item(mark,0f,0f,ball,h.toFloat());item(word,wordStart,0f,w-wordStart,h.toFloat())
        } else if(scene==ConsoleScene.XBOX_SERIES) {
            val wordStart=wordLeft(w,h)
            item(mark,0f,0f,w*.46f,h.toFloat());item(word,wordStart,0f,w-wordStart,h.toFloat())
        } else item(mark,0f,0f,w.toFloat(),h.toFloat())
    }
}
