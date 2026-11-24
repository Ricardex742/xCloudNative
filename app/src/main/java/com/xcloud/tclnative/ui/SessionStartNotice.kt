package com.xcloud.tclnative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import com.xcloud.tclnative.i18n.LocalizedTextView
import com.xcloud.tclnative.i18n.UiText
import kotlin.math.roundToInt

internal class SessionStartNotice(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val heading = LocalizedTextView(context).apply {
        text = UiText.text("Sua sessão foi iniciada")
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        isHorizontalFadingEdgeEnabled = false
    }
    private val measurePaint = TextPaint()
    private val time = LocalizedTextView(context).apply {
        setTextColor(0xffc3d3cf.toInt())
        textSize = 13f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = (464 * density).roundToInt()
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maxWidth
            else minOf(MeasureSpec.getSize(widthMeasureSpec), maxWidth)
        val textWidth = (available - paddingLeft - paddingRight).coerceAtLeast(1)
        val preferred = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        val minimum = 11f * density
        measurePaint.set(heading.paint)
        measurePaint.textSize = preferred
        val desired = Layout.getDesiredWidth(heading.text, measurePaint)
        // Fit the complete heading on one line where possible. Very narrow windows may wrap.
        val fitted = if (desired > textWidth) (preferred * (textWidth - 1).coerceAtLeast(1) / desired)
            .coerceAtLeast(minimum) else preferred
        if (heading.textSize != fitted) heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
        super.onMeasure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
    private var awaitingInitialTime = false
    private val dismiss = Runnable { visibility = View.GONE }
    private val stopWaiting = Runnable { awaitingInitialTime = false }

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
        addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(time, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (3 * density).roundToInt()
        })
    }

    fun show(seconds: Long?) {
        hide()
        awaitingInitialTime = seconds == null
        if (awaitingInitialTime) postDelayed(stopWaiting, 30_000)
        visibility = View.VISIBLE
        update(seconds)
        postDelayed(dismiss, 5_000)
    }

    fun update(seconds: Long?) {
        if (awaitingInitialTime && seconds != null) {
            awaitingInitialTime = false
            removeCallbacks(stopWaiting)
            removeCallbacks(dismiss)
            visibility = View.VISIBLE
            postDelayed(dismiss, 5_000)
        }
        if (visibility != View.VISIBLE) return
        time.visibility = if (seconds == null) View.GONE else View.VISIBLE
        val minutes = seconds?.let { (it + 59) / 60 }
        val label = when {
            minutes == null -> ""
            minutes < 60 -> UiText.text("Tempo restante na sessão: {0} min", UiText.nested(minutes))
            minutes % 60 == 0L -> UiText.text("Tempo restante na sessão: {0} h", UiText.nested(minutes / 60))
            else -> UiText.text("Tempo restante na sessão: {0} h {1} min",
                UiText.nested(minutes / 60), UiText.nested(minutes % 60))
        }
        if (time.text.toString() != label) time.text = label
    }

    fun hide() {
        removeCallbacks(dismiss)
        removeCallbacks(stopWaiting)
        awaitingInitialTime = false
        visibility = View.GONE
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }
}
