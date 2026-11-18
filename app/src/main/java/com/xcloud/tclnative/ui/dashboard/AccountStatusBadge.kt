package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.view.Gravity
import com.xcloud.tclnative.R
import com.xcloud.tclnative.catalog.CloudPlaytime
import com.xcloud.tclnative.catalog.CloudPlaytimeStatus
import com.xcloud.tclnative.i18n.LocalizedTextView
import com.xcloud.tclnative.settings.ConsoleScene
import kotlin.math.roundToInt

/** Account benefits stay separate from game entitlement badges and use the current console's finish. */
internal class AccountStatusBadge(context: Context,private val plan: Boolean): LocalizedTextView(context) {
    private val wordmark=CatalogMarkDrawable(resources,R.drawable.badge_game_pass_vector)
    private val timeIcon=TimeMark()
    private var status=CloudPlaytimeStatus.UNAVAILABLE
    private var scale=1f
    init {
        gravity=Gravity.START or Gravity.CENTER_VERTICAL
        includeFontPadding=false;maxLines=1;ellipsize=TextUtils.TruncateAt.END
        isFocusable=false;isClickable=false
    }
    fun bindPlan(label: String) {
        contentDescription=label
        text=if(label.startsWith("GAME PASS ") && !DashboardTheme.palette.theme.scene.playStation) SpannableString(label).apply {
            setSpan(WordmarkSpan(wordmark),0,9,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else label
        refreshBadge(scale)
    }
    fun bindTime(value: CloudPlaytime) {
        status=value.status;text=value.label;contentDescription=value.label
        refreshBadge(scale)
    }
    fun refreshBadge(scale: Float) {
        this.scale=scale
        val scene=DashboardTheme.palette.theme.scene
        val light=scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_360_METRO
        val confirmed=status==CloudPlaytimeStatus.UNLIMITED || status==CloudPlaytimeStatus.LIMITED
        val accent=if(light) 0xff42651f.toInt() else if(scene==ConsoleScene.XBOX_ORIGINAL) 0xffb8f747.toInt() else DashboardTheme.accent
        val ink=if(light) 0xff263a22.toInt() else Color.WHITE
        val foreground=if(plan) ink else if(confirmed) accent else if(light) 0xff4b5746.toInt() else 0xffb9c6bd.toInt()
        val fills=when(scene) {
            ConsoleScene.PS4 -> intArrayOf(0xad083482.toInt(),0xad083482.toInt())
            ConsoleScene.PS5 -> intArrayOf(0xce282a30.toInt(),0xce282a30.toInt())
            ConsoleScene.XBOX_360 -> if(plan) intArrayOf(0xfff7f8f1.toInt(),0xffdce2ce.toInt()) else intArrayOf(0xffeef3e3.toInt(),0xffeef3e3.toInt())
            ConsoleScene.XBOX_360_NXE -> if(plan) intArrayOf(0xff3a6333.toInt(),0xff203e23.toInt()) else intArrayOf(0xe91b3422.toInt(),0xe91b3422.toInt())
            ConsoleScene.XBOX_360_METRO -> if(plan) intArrayOf(0xffd8eac6.toInt(),0xffd8eac6.toInt()) else intArrayOf(0xffeff5e9.toInt(),0xffeff5e9.toInt())
            ConsoleScene.XBOX_ONE -> intArrayOf(if(plan) 0xff107c10.toInt() else 0xff15251a.toInt(),if(plan) 0xff107c10.toInt() else 0xff15251a.toInt())
            ConsoleScene.XBOX_SERIES -> intArrayOf(if(plan) 0xff20382b.toInt() else 0xff142b1d.toInt(),if(plan) 0xff17271e.toInt() else 0xff142b1d.toInt())
            ConsoleScene.XBOX_ORIGINAL -> intArrayOf(0xff233a0e.toInt(),0xff101f08.toInt())
            ConsoleScene.NONE -> intArrayOf(DashboardTheme.color(if(plan) UiColor.SUBSCRIPTION else UiColor.PANEL_TOP),DashboardTheme.color(UiColor.PANEL_BOTTOM))
        }
        val radius=when(scene) {
            ConsoleScene.XBOX_ONE,ConsoleScene.XBOX_360_METRO -> 0f
            ConsoleScene.XBOX_360 -> 8f
            ConsoleScene.XBOX_ORIGINAL -> 9f
            else -> if(plan) 5f else 12f
        }*scale
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,fills).apply {
            cornerRadius=radius
            setStroke(scale.roundToInt().coerceAtLeast(1),if(light) 0xffa6b992.toInt() else Color.argb(135,Color.red(accent),Color.green(accent),Color.blue(accent)))
        }
        if(scene.playStation) background=null
        setTextColor(foreground)
        if(scene==ConsoleScene.PS5) setTextColor(if(plan) 0xffe0d4b8.toInt() else 0xffc4c9d2.toInt())
        wordmark.setTintList(ColorStateList.valueOf(ink))
        setPadding((8*scale).roundToInt(),0,(8*scale).roundToInt(),0)
        if(scene.playStation) {
            setCompoundDrawables(null,null,null,null);invalidate();return
        }
        if(!plan) {
            timeIcon.color=foreground;timeIcon.status=status
            timeIcon.setBounds(0,0,(16*scale).roundToInt().coerceAtLeast(1),(16*scale).roundToInt().coerceAtLeast(1))
            compoundDrawablePadding=(5*scale).roundToInt()
            setCompoundDrawablesRelative(timeIcon,null,null,null)
        }
        invalidate()
    }
    private class WordmarkSpan(private val mark: Drawable): ReplacementSpan() {
        private fun height(paint: Paint)=paint.textSize*.78f
        override fun getSize(paint: Paint,text: CharSequence,start: Int,end: Int,fm: Paint.FontMetricsInt?)=(height(paint)*mark.intrinsicWidth/mark.intrinsicHeight).roundToInt()
        override fun draw(canvas: Canvas,text: CharSequence,start: Int,end: Int,x: Float,top: Int,y: Int,bottom: Int,paint: Paint) {
            val h=height(paint).roundToInt().coerceAtLeast(1)
            val w=getSize(paint,text,start,end,null)
            val baselineCenter=y+(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2
            val save=canvas.save();canvas.translate(x,baselineCenter-h/2f)
            mark.setBounds(0,0,w,h);mark.draw(canvas);canvas.restoreToCount(save)
        }
    }
    /** Vector geometry, including infinity; independent of device emoji fonts and raster density. */
    private class TimeMark: Drawable() {
        var color=Color.WHITE
        var status=CloudPlaytimeStatus.UNAVAILABLE
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val path=Path()
        override fun draw(canvas: Canvas) {
            val save=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/16f,bounds.height()/16f)
            paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.4f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
            if(status==CloudPlaytimeStatus.UNLIMITED) {
                path.reset();path.moveTo(8f,8f);path.cubicTo(5f,3f,1f,4f,1f,8f);path.cubicTo(1f,12f,5f,13f,8f,8f)
                path.cubicTo(11f,3f,15f,4f,15f,8f);path.cubicTo(15f,12f,11f,13f,8f,8f);canvas.drawPath(path,paint)
            } else {
                canvas.drawCircle(8f,8f,6f,paint)
                if(status==CloudPlaytimeStatus.UNAVAILABLE) canvas.drawLine(5.5f,8f,10.5f,8f,paint)
                else { canvas.drawLine(8f,4.5f,8f,8f,paint);canvas.drawLine(8f,8f,10.5f,9.5f,paint) }
            }
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha=alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
