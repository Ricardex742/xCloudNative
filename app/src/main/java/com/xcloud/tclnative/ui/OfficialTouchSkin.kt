package com.xcloud.tclnative.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.xcloud.tclnative.touch.OfficialTouchAppearance
import com.xcloud.tclnative.touch.OfficialTouchBackground
import com.xcloud.tclnative.touch.OfficialTouchFace
import com.xcloud.tclnative.touch.OfficialTouchStroke
import com.xcloud.tclnative.webrtc.GamepadState
import com.xcloud.tclnative.webrtc.TouchControl
import com.xcloud.tclnative.webrtc.TouchKind
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Draws the supported visual properties from the touch package in the native overlay. */
internal class OfficialTouchSkin {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val bitmapBounds = RectF()
    private val icons = HudIcons()
    private val cross = Path().apply {
        moveTo(-.28f, -.90f)
        lineTo(.28f, -.90f)
        lineTo(.28f, -.28f)
        lineTo(.90f, -.28f)
        lineTo(.90f, .28f)
        lineTo(.28f, .28f)
        lineTo(.28f, .90f)
        lineTo(-.28f, .90f)
        lineTo(-.28f, .28f)
        lineTo(-.90f, .28f)
        lineTo(-.90f, -.28f)
        lineTo(-.28f, -.28f)
        close()
    }

    fun draw(canvas: Canvas, control: TouchControl, appearance: OfficialTouchAppearance, state: GamepadState,
             held: Boolean, toggled: Boolean = false) {
        val style = when {
            held && !control.toggle -> appearance.active
            toggled -> appearance.toggled ?: appearance.active
            else -> appearance.idle
        }
        if (style.opacity == 0f) return
        canvas.save()
        canvas.translate(control.x, control.y)
        canvas.scale(control.radius, control.radius)
        background(canvas, style.background, 1f, style.opacity)
        when {
            control.kind == TouchKind.DPAD -> {
                setColor(style.fill ?: 0x4421262a, style.opacity)
                canvas.drawPath(cross, paint)
                stroke(style.stroke, style.opacity, .035f)
                canvas.drawPath(cross, paint)
            }
            !appearance.touchpad && (control.kind == TouchKind.LEFT_STICK || control.kind == TouchKind.RIGHT_STICK) -> {
                val left = control.kind == TouchKind.LEFT_STICK
                val x = if (left) state.leftX else state.rightX
                val y = if (left) state.leftY else state.rightY
                stroke(style.outlineStroke, style.outlineOpacity * style.opacity, .025f)
                canvas.drawCircle(0f, 0f, .96f, paint)
                val knobRadius = (.40f * appearance.faceScale).coerceAtMost(.78f)
                val travel = minOf(.55f, .96f - knobRadius) / maxOf(1f, hypot(x, y))
                canvas.translate(x * travel, y * travel)
                val alpha = style.knobOpacity * style.opacity
                background(canvas, style.knobBackground ?: OfficialTouchBackground(color=0x66272d31), knobRadius, alpha)
                stroke(style.knobStroke, alpha, .032f)
                canvas.drawCircle(0f, 0f, knobRadius, paint)
                face(canvas, style.face, knobRadius * .925f, alpha)
            }
            else -> {
                if (style.face != null) {
                    face(canvas, style.face, (.80f * appearance.faceScale).coerceAtMost(.96f), style.opacity)
                } else {
                    if (style.background == null) {
                        setColor(if (held) 0xb3364248.toInt() else 0x66364248, style.opacity)
                        canvas.drawCircle(0f, 0f, .72f, paint)
                    }
                    setColor(Color.WHITE, style.opacity)
                    // Use a pixel-sized text run and then scale into the control.
                    canvas.scale(1f / 64f, 1f / 64f)
                    paint.textSize = .63f * 64f
                    val width = paint.measureText(control.label)
                    if (width > 90f) paint.textSize *= 90f / width
                    canvas.drawText(control.label, 0f, -(paint.ascent() + paint.descent()) / 2f, paint)
                }
            }
        }
        canvas.restore()
    }

    private fun setColor(color: Int, opacity: Float) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (Color.alpha(color) * opacity).roundToInt()
    }

    private fun stroke(stroke: OfficialTouchStroke?, opacity: Float, width: Float) {
        setColor(stroke?.color ?: 0x99ffffff.toInt(), opacity * (stroke?.opacity ?: 1f))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
    }

    private fun background(canvas: Canvas, background: OfficialTouchBackground?, radius: Float, opacity: Float) {
        if (background == null) return
        val alpha = opacity * background.opacity
        background.color?.let {
            setColor(it, alpha)
            canvas.drawCircle(0f, 0f, radius, paint)
        }
        background.image?.let { image(canvas, it, radius, alpha) }
    }

    private fun face(canvas: Canvas, face: OfficialTouchFace?, radius: Float, opacity: Float) {
        face?.image?.let { image(canvas, it, radius, opacity) }
        face?.icon?.let {
            setColor(Color.WHITE, opacity)
            icons.draw(canvas, paint, it, 0f, 0f, radius)
        }
    }

    private fun image(canvas: Canvas, bitmap: Bitmap, radius: Float, opacity: Float) {
        val scale = radius / maxOf(bitmap.width, bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        bitmapBounds.set(-width, -height, width, height)
        setColor(Color.WHITE, opacity)
        canvas.drawBitmap(bitmap, null, bitmapBounds, paint)
    }
}
