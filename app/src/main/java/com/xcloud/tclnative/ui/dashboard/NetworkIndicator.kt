package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.webrtc.NetworkQuality
import kotlin.math.roundToInt

internal val NetworkQuality.label: String get() = when (this) {
    NetworkQuality.STABLE -> UiText.text("Rede estável")
    NetworkQuality.MODERATE -> UiText.text("Rede moderada")
    NetworkQuality.POOR -> UiText.text("Rede ruim")
    NetworkQuality.UNKNOWN -> UiText.text("Aguardando dados da rede")
}

/** A touch shortcut that leaves controller focus with the game. */
internal class NetworkIndicator(context: Context, onOpen: () -> Unit) : ImageButton(context) {
    private val density = resources.displayMetrics.density
    private val signal = NetworkSignalDrawable((24 * density).roundToInt())

    init {
        background = DashboardTheme.panel(10 * density)
        setPadding(0, 0, 0, 0)
        scaleType = ImageView.ScaleType.CENTER
        setImageDrawable(signal)
        setOnClickListener { onOpen() }
        isFocusable = false
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        showQuality(NetworkQuality.UNKNOWN)
        setCutout(Rect())
    }

    fun showQuality(quality: NetworkQuality) {
        if (signal.quality == quality && contentDescription != null) return
        signal.quality = quality
        contentDescription = UiText.join(quality.label, ". ", UiText.text("Estatísticas da rede"))
    }

    fun setCutout(cutout: Rect) {
        val size = (48 * density).roundToInt()
        val margin = (8 * density).roundToInt()
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = margin + cutout.top
            rightMargin = margin + cutout.right
        }
    }
}

internal class NetworkSignalDrawable(private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    var quality = NetworkQuality.UNKNOWN
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = when (quality) {
            NetworkQuality.STABLE -> 0xff61df9d.toInt()
            NetworkQuality.MODERATE -> 0xffffd166.toInt()
            NetworkQuality.POOR -> 0xffff6974.toInt()
            NetworkQuality.UNKNOWN -> 0xffa5b2b9.toInt()
        }
        paint.style = Paint.Style.STROKE
        for (i in 1..quality.signals) {
            val radius = 2 + 4f * i
            canvas.drawArc(12 - radius, 19 - radius, 12 + radius, 19 + radius, 227f, 86f, false, paint)
        }
        paint.style = Paint.Style.FILL
        canvas.drawCircle(12f, 19f, 1.3f, paint)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
