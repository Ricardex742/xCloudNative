package com.xcloud.tclnative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import com.xcloud.tclnative.i18n.LocalizedTextView
import com.xcloud.tclnative.i18n.UiText
import kotlin.math.roundToInt

internal class SessionStartNotice(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val time = LocalizedTextView(context).apply {
        setTextColor(0xffc3d3cf.toInt())
        textSize = 13f
        maxWidth = (320 * density).roundToInt()
    }
    private val dismiss = Runnable { visibility = View.GONE }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        background = GradientDrawable().apply {
            setColor(0xf2162221.toInt())
            cornerRadius = 14 * density
            setStroke((1.5f * density).roundToInt(), 0xff4d8576.toInt())
        }
        val horizontal = (16 * density).roundToInt()
        val vertical = (10 * density).roundToInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        addView(LocalizedTextView(context).apply {
            text = UiText.text("Sua sessão foi iniciada")
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (3 * density).roundToInt()
        })
    }

    fun show(seconds: Long?) {
        removeCallbacks(dismiss)
        visibility = View.VISIBLE
        update(seconds)
        postDelayed(dismiss, 5_000)
    }

    fun update(seconds: Long?) {
        if (visibility != View.VISIBLE) return
        val minutes = seconds?.let { (it + 59) / 60 }
        val label = when {
            minutes == null -> UiText.text("Tempo da sessão não informado")
            minutes < 60 -> UiText.text("Tempo restante na sessão: {0} min", UiText.nested(minutes))
            minutes % 60 == 0L -> UiText.text("Tempo restante na sessão: {0} h", UiText.nested(minutes / 60))
            else -> UiText.text("Tempo restante na sessão: {0} h {1} min",
                UiText.nested(minutes / 60), UiText.nested(minutes % 60))
        }
        if (time.text.toString() != label) time.text = label
    }

    fun hide() {
        removeCallbacks(dismiss)
        visibility = View.GONE
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }
}
