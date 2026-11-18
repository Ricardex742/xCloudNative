package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.xcloud.tclnative.R
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.settings.ConsoleScene
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** A bounded artwork request and native views, shown only between catalog/playback states. */
class SessionTransitionView(context: Context) : FrameLayout(context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val art=PosterLoader(context,scope)
    private var scene=DashboardTheme.palette.theme.scene
    private var renderedTheme=DashboardTheme.palette.theme
    private val banner=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val cover=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;visibility=GONE }
    private val consoleBrand=ConsoleBrandView(context).apply { visibility=if(scene.playStation) VISIBLE else GONE }
    private val shade=View(context).apply {
        background=DashboardTheme.gradient(GradientDrawable.Orientation.TOP_BOTTOM,
            arrayOf(UiColor.TRANSITION_SHADE_TOP,UiColor.TRANSITION_SHADE_MID,UiColor.TRANSITION_SHADE_END))
    }
    private val logo=ImageView(context).apply {
        scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription=UiText.text("Logo xCLOUD")
        background=DashboardTheme.shape(UiColor.LOGO_BACKGROUND,26f)
        clipToOutline=true
    }
    private val eyebrow=label(23,true).apply { text="xCLOUD"; setTextColor(DashboardTheme.accent) }
    private val title=label(48,true).apply { maxLines=2 }
    private val status=label(27,false).apply {
        maxLines=2; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val detail=label(20,false).apply { maxLines=2; setTextColor(DashboardTheme.muted) }
    private val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate=true; indeterminateTintList=ColorStateList.valueOf(DashboardTheme.accent)
        contentDescription=UiText.text("Aguarde")
    }
    private var logoJob: Job?=null
    private val nativeFallback by lazy { ConsoleIconDrawable(ConsoleGlyph.CONTROLLER,96,ColorStateList.valueOf(Color.WHITE)) }
    private var url: String?=null
    private var game: GameTitle?=null
    private var geometry=0L
    var exiting=false
        private set

    init {
        background=DashboardBackground()
        addView(banner,LayoutParams(-1,-1)); addView(shade,LayoutParams(-1,-1))
        listOf(cover,logo,eyebrow,title,status,detail,progress,consoleBrand).forEach(::addView)
        if(scene.playStation) {
            shade.background=ConsoleQueueChrome(scene)
            title.typeface=android.graphics.Typeface.create(if(scene==ConsoleScene.PS4) "sans-serif-light" else "sans-serif-medium",android.graphics.Typeface.NORMAL)
        }
        isClickable=true; isFocusable=true; isFocusableInTouchMode=true
        DashboardTheme.customFocus(this)
        visibility=GONE
    }
    private fun label(size: Int,heavy: Boolean)=com.xcloud.tclnative.i18n.LocalizedTextView(context).apply {
        typeface=DashboardTheme.font(context,heavy); includeFontPadding=false
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX,size.toFloat())
    }
    fun showStartup(value: GameTitle?,message: String) {
        exiting=false; show(value,message,UiText.text("Seus jogos. Na sua tela."))
    }
    fun showExit(value: GameTitle?,wasPlaying: Boolean=true) {
        exiting=true
        show(value,if(wasPlaying) UiText.text("Saindo do jogo…") else UiText.text("Voltando ao catálogo…"),
            UiText.text("Preparando seu catálogo."))
    }
    private fun show(value: GameTitle?,message: String,description: String) {
        refreshAppearance()
        visibility=VISIBLE; detail.text=description; setGame(value); updateStatus(message)
        if(scene.playStation) logo.setImageDrawable(nativeFallback)
        else if(logo.drawable==null && logoJob?.isActive!=true) logoJob=scope.launch {
            // A small local icon, decoded away from the UI; never retain the full 1254px source.
            val bitmap=withContext(Dispatchers.IO) {
                BitmapFactory.decodeResource(resources,R.drawable.brand_logo,BitmapFactory.Options().apply {
                    inSampleSize=2; inScaled=false
                })
            }
            if(isActive && visibility==VISIBLE && bitmap!=null) logo.setImageDrawable(
                RoundedBitmapDrawableFactory.create(resources,bitmap).apply {
                    cornerRadius=bitmap.width*.125f; setAntiAlias(true)
                })
        }
        requestFocus()
    }
    internal fun refreshAppearance() {
        if(renderedTheme==DashboardTheme.palette.theme) return
        renderedTheme=DashboardTheme.palette.theme;scene=renderedTheme.scene
        logoJob?.cancel();logoJob=null;logo.setImageDrawable(null)
        background=DashboardBackground();consoleBrand.refresh()
        consoleBrand.visibility=if(scene.playStation) VISIBLE else GONE
        shade.background=if(scene.playStation) ConsoleQueueChrome(scene) else DashboardTheme.gradient(GradientDrawable.Orientation.TOP_BOTTOM,
            arrayOf(UiColor.TRANSITION_SHADE_TOP,UiColor.TRANSITION_SHADE_MID,UiColor.TRANSITION_SHADE_END))
        title.typeface=if(scene.playStation) android.graphics.Typeface.create(if(scene==ConsoleScene.PS4) "sans-serif-light" else "sans-serif-medium",android.graphics.Typeface.NORMAL) else DashboardTheme.font(context,true)
        geometry=0L;url=null
        art.cancel(banner);art.cancel(cover);cover.visibility=GONE
        if(visibility==VISIBLE) setGame(game)
        requestLayout()
    }
    fun updateStatus(message: String) { status.text=message }
    fun setGame(value: GameTitle?) {
        val changed=(game==null)!=(value==null)
        game=value; title.text=value?.name ?: "xCLOUD"
        banner.contentDescription=value?.let { UiText.text("Banner de {0}",UiText.nested(it.name)) }
        val next=if(scene==ConsoleScene.PS4) null else value?.let { it.heroUrl ?: it.tileUrl ?: it.posterUrl }
        if(next!=url) { url=next; art.load(banner,next,if(scene.playStation) 1024 else 1280,if(scene.playStation) 576 else 720) { updateBrandVisibility() } }
        if(scene.playStation) art.load(cover,value?.let { it.tileUrl ?: it.posterUrl },320,320) { ready -> cover.visibility=if(ready) VISIBLE else GONE;updateBrandVisibility() }
        updateBrandVisibility()
        if(changed) { geometry=0; requestLayout() }
    }
    private fun updateBrandVisibility() { logo.visibility=if(scene.playStation) { if(cover.drawable==null) VISIBLE else GONE } else if(game==null || banner.drawable==null) VISIBLE else GONE }
    fun dismiss() {
        visibility=GONE; exiting=false; logoJob?.cancel(); logoJob=null
        art.cancel(banner);art.cancel(cover);cover.visibility=GONE; url=null; logo.setImageDrawable(null)
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec); val h=MeasureSpec.getSize(heightMeasureSpec)
        val key=w.toLong() shl 32 or h.toLong()
        if(key!=geometry && w>0 && h>0) {
            geometry=key
            val s=minOf(w/1280f,h/720f); val ox=(w-1280*s)/2; val oy=(h-720*s)/2
            (logo.background as GradientDrawable).cornerRadius=26*s
            fun place(view: View,x: Int,y: Int,vw: Int,vh: Int) {
                view.layoutParams=LayoutParams((vw*s).roundToInt(),(vh*s).roundToInt()).apply {
                    leftMargin=(ox+x*s).roundToInt(); topMargin=(oy+y*s).roundToInt()
                }
            }
            if(scene.playStation) {
                val centered=scene==ConsoleScene.PS4 || game==null || h>w
                consoleBrand.visibility=VISIBLE;eyebrow.visibility=GONE
                if(centered) {
                    place(consoleBrand,80,42,180,48);place(cover,544,151,192,192);place(logo,544,151,192,192)
                    place(title,120,377,1040,112);place(status,120,509,1040,64)
                    place(progress,460,595,360,5);place(detail,120,638,1040,44)
                } else {
                    place(consoleBrand,80,42,156,42);place(cover,80,227,208,208);place(logo,80,227,208,208)
                    place(title,332,225,852,138);place(status,332,395,852,76)
                    place(progress,332,507,480,5);place(detail,332,557,852,66)
                }
                listOf(title to 44,status to 27,detail to 20).forEach { (v,size) ->
                    v.gravity=if(centered) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
                    v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*s)
                }
                super.onMeasure(widthMeasureSpec,heightMeasureSpec);return
            }
            val branded=game==null
            updateBrandVisibility()
            eyebrow.visibility=if(branded) GONE else VISIBLE
            if(branded) {
                place(logo,536,128,208,208); place(title,160,362,960,67)
                place(status,160,446,960,65); place(detail,160,566,960,42)
                place(progress,480,528,320,5)
            } else {
                place(logo,536,138,208,208)
                place(eyebrow,80,54,1120,40); place(title,80,390,1120,116)
                place(status,80,519,1120,66); place(detail,80,638,1120,40)
                place(progress,80,603,340,5)
            }
            listOf(title to 48,status to 27,detail to 20,eyebrow to 23).forEach { (v,size) ->
                v.gravity=if(branded) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
                v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*s)
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    fun close() { dismiss(); scope.cancel(); art.close() }
}
