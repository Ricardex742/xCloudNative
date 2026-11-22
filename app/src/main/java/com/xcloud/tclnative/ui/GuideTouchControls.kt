package com.xcloud.tclnative.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.touch.HudBinding
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import kotlin.math.ceil

/** Floating gamepad controls for navigating the Guide by touch. */
internal class GuideTouchControls(
    context: Context,
    private val onButtonsChanged: () -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val hitSize = ceil(48 * density).toInt()
    private val edgeMargin = ceil(8 * density).toInt()
    private val release = Runnable { updateButtons(0) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Path().apply {
        addRoundRect(RectF(-53f, -18f, 53f, 18f), 8f, 8f, Path.Direction.CW)
        val vertical = Path().apply { addRoundRect(RectF(-18f, -53f, 18f, 53f), 8f, 8f, Path.Direction.CW) }
        op(vertical, Path.Op.UNION)
    }
    private val bezel = RadialGradient(-18f, -21f, 103f, 0xf1374349.toInt(), 0xe90a1115.toInt(), Shader.TileMode.CLAMP)
    private val crossFace = LinearGradient(0f, -53f, 0f, 53f, 0xff405057.toInt(), 0xff162228.toInt(), Shader.TileMode.CLAMP)
    private val controlMasks = mutableMapOf<View, Int>()
    private val directionPad = object : FrameLayout(context) {
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); drawDirectionPad(canvas) }
    }
    private var titleLostFocus = false
    var buttons = 0
        private set
    val active: Boolean get() = visibility == VISIBLE
    val cornerControlSize: Int get() = hitSize

    init {
        visibility = GONE
        addView(directionPad, LayoutParams(3 * hitSize, 3 * hitSize, Gravity.BOTTOM or Gravity.LEFT))
        addDirection(HudBinding.UP, 1, 0, 0f)
        addDirection(HudBinding.LEFT, 0, 1, 270f)
        addDirection(HudBinding.RIGHT, 2, 1, 90f)
        addDirection(HudBinding.DOWN, 1, 2, 180f)
        addRoundControl("A", UiText.text("Confirmar"), 0xff78edac.toInt(),
            LayoutParams(hitSize, hitSize, Gravity.BOTTOM or Gravity.RIGHT).apply { rightMargin = hitSize }, HudBinding.A)
        addRoundControl("B", UiText.text("Voltar"), 0xffffa397.toInt(),
            LayoutParams(hitSize, hitSize, Gravity.BOTTOM or Gravity.RIGHT).apply { bottomMargin = hitSize }, HudBinding.B)
        addRoundControl("×", UiText.text("Ocultar controles do Guia"), 0xffb3c3c8.toInt(),
            LayoutParams(hitSize, hitSize, Gravity.TOP or Gravity.RIGHT))
    }

    fun setCutout(cutout: Rect) {
        setPadding(cutout.left + edgeMargin, cutout.top + edgeMargin, cutout.right + edgeMargin, cutout.bottom + edgeMargin)
    }

    fun show() {
        titleLostFocus = false
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
        titleLostFocus = false
        removeCallbacks(release)
        updateButtons(0)
    }

    fun onTitleFocusChanged(focused: Boolean) {
        if (!active) return
        if (!focused) titleLostFocus = true
        else if (titleLostFocus) onClose()
    }

    private fun drawDirectionPad(canvas: Canvas) {
        val unit = hitSize / 48f
        canvas.save()
        canvas.translate(1.5f * hitSize, 1.5f * hitSize)
        canvas.scale(unit, unit)

        paint.style = Paint.Style.FILL
        paint.color = 0x77000000
        canvas.drawCircle(0f, 3f, 61f, paint)
        paint.shader = bezel
        paint.alpha = 255
        canvas.drawCircle(0f, 0f, 61f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.25f
        paint.color = 0xb07a9198.toInt()
        canvas.drawCircle(0f, 0f, 60f, paint)
        paint.color = 0x884c6068.toInt()
        canvas.drawCircle(0f, 0f, 56f, paint)

        paint.style = Paint.Style.FILL
        paint.color = 0xb0091013.toInt()
        canvas.save()
        canvas.translate(0f, 2f)
        canvas.drawPath(cross, paint)
        canvas.restore()
        paint.shader = crossFace
        paint.alpha = 255
        canvas.drawPath(cross, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f
        paint.color = 0x997b969d.toInt()
        canvas.drawPath(cross, paint)
        paint.color = 0x8066838a.toInt()
        canvas.drawCircle(0f, 0f, 7f, paint)
        canvas.restore()
    }

    private fun addDirection(binding: HudBinding, column: Int, row: Int, rotation: Float) {
        val button = object : View(context) {
            private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val chevron = Path().apply { moveTo(-7f, 3f); lineTo(0f, -4f); lineTo(7f, 3f) }

            override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
            override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

            override fun onDraw(canvas: Canvas) {
                val unit = hitSize / 48f
                val highlighted = isActivated || isPressed || isFocused
                canvas.save()
                canvas.translate(width / 2f, height / 2f)
                canvas.scale(unit, unit)
                canvas.rotate(rotation)
                canvas.translate(0f, 4f)
                if (highlighted) {
                    symbolPaint.style = Paint.Style.FILL
                    symbolPaint.color = (DashboardTheme.accent and 0x00ffffff) or 0x43000000
                    canvas.drawCircle(0f, 0f, 13f, symbolPaint)
                }
                symbolPaint.style = Paint.Style.STROKE
                symbolPaint.strokeWidth = 2.1f
                symbolPaint.strokeCap = Paint.Cap.ROUND
                symbolPaint.strokeJoin = Paint.Join.ROUND
                symbolPaint.color = if (highlighted) DashboardTheme.accent else 0xffdbe6e8.toInt()
                canvas.drawPath(chevron, symbolPaint)
                canvas.restore()
            }
        }
        configureControl(button, binding.label, binding)
        directionPad.addView(button, LayoutParams(hitSize, hitSize, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = column * hitSize
            topMargin = row * hitSize
        })
    }

    private fun addRoundControl(label: String, description: String, accent: Int, position: LayoutParams,
                                binding: HudBinding? = null) {
        val inset = ceil((if (binding == null) 8 else 2) * density).toInt()
        fun disc(selected: Boolean) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (selected) intArrayOf(ColorUtils.blendARGB(accent, 0xff152327.toInt(), .60f), 0xff19292e.toInt())
            else intArrayOf(0xf036444b.toInt(), 0xf00b1419.toInt())).apply {
            shape = GradientDrawable.OVAL
            setStroke(ceil((if (binding == null) 1.3 else 1.8) * density).toInt(), accent)
        }
        val fill = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), disc(true))
            addState(intArrayOf(android.R.attr.state_focused), disc(true))
            addState(intArrayOf(), disc(false))
        }
        val button = com.xcloud.tclnative.i18n.LocalizedTextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (binding == null) 22f else 19f)
            setTextColor(Color.WHITE)
            background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(0x55ffffff), fill, null), inset)
            elevation = 2 * density
        }
        configureControl(button, description, binding)
        addView(button, position)
    }

    private fun configureControl(view: View, description: String, binding: HudBinding?) {
        view.contentDescription = description
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.setOnClickListener {
            if (!active) return@setOnClickListener
            if (binding == null) onClose() else press(binding)
        }
        if (binding != null) controlMasks[view] = binding.button
    }

    private fun press(binding: HudBinding) {
        removeCallbacks(release)
        updateButtons(0)
        updateButtons(binding.button)
        postDelayed(release, 100)
    }

    private fun updateButtons(value: Int) {
        if (buttons == value) return
        buttons = value
        controlMasks.forEach { (view, mask) -> view.isActivated = value and mask != 0 }
        onButtonsChanged()
    }
}
