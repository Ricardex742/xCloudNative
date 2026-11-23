package com.xcloud.tclnative.ui.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil

/** Direct touch jumps, with a list of letters on D-pad confirmation or accessibility click. */
internal class CatalogAlphabetRail(context: Context, choose: () -> Unit, private val jump: (Char) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = DashboardTheme.font(context)
    }
    private val textLimit = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    private var letters = emptyList<Char>()
    private var dragging: Char? = null

    init {
        contentDescription = "A–Z"
        isFocusable = true
        isEnabled = false
        setOnClickListener { if (isEnabled) choose() }
    }

    fun bind(positions: Map<Char, Int>) {
        letters = positions.keys.toList()
        isEnabled = letters.isNotEmpty()
        dragging = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty() || height <= 0) return
        val row = height.toFloat() / letters.size
        val stride = ceil(textLimit / row).toInt().coerceAtLeast(1)
        paint.textSize = minOf(textLimit, row * stride * .84f)
        val center = width * .5f
        fun letterY(index: Int, padding: Float) = (row * (index + .5f)).coerceIn(padding, height - padding)
        val baseline = (paint.ascent() + paint.descent()) / 2
        val padding = (paint.textSize * .5f).coerceAtMost(height * .5f)
        paint.color = if (isFocused) DashboardTheme.accent else Color.WHITE
        letters.forEachIndexed { index, letter ->
            if (index % stride == 0 || index == letters.lastIndex && index % stride >= stride / 2) {
                canvas.drawText(letter.toString(), center, letterY(index, padding) - baseline, paint)
            }
        }
        dragging?.let { letter ->
            val radius = minOf(textLimit, width * .43f, height * .5f)
            val y = letterY(letters.indexOf(letter), radius)
            paint.color = DashboardTheme.accent
            canvas.drawCircle(center, y, radius, paint)
            paint.color = Color.BLACK
            paint.textSize = radius * 1.35f
            canvas.drawText(letter.toString(), center, y - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    // The click listener opens an accessible list. A pointer on the rail navigates directly.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty() || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val index = (event.y * letters.size / height).toInt().coerceIn(letters.indices)
                val letter = letters[index]
                if (letter != dragging) jump(letter)
                dragging = if (event.actionMasked == MotionEvent.ACTION_UP) null else letter
            }
            MotionEvent.ACTION_CANCEL -> dragging = null
        }
        invalidate()
        return true
    }
}
