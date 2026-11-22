package com.xcloud.tclnative.ui

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.touch.HudBinding
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import kotlin.math.ceil

/** Sends short controller presses only when the user taps a Guide control. */
internal class GuideTouchControls(
    context: Context,
    private val onButtonsChanged: () -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val hitSize = ceil(48 * density).toInt()
    private val padding = ceil(4 * density).toInt()
    private val release = Runnable { updateButtons(0) }
    private var titleLostFocus = false
    var buttons = 0
        private set
    val active: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
        orientation = VERTICAL
        gravity = Gravity.END
        background = DashboardTheme.panel(12 * density)
        setPadding(padding, padding, padding, padding)
        isClickable = true

        val directions = LinearLayout(context)
        directions.addButton("←", HudBinding.LEFT.label) { press(HudBinding.LEFT) }
        directions.addButton("↑", HudBinding.UP.label) { press(HudBinding.UP) }
        directions.addButton("↓", HudBinding.DOWN.label) { press(HudBinding.DOWN) }
        directions.addButton("→", HudBinding.RIGHT.label) { press(HudBinding.RIGHT) }
        addView(directions)

        val actions = LinearLayout(context)
        actions.addButton("A", UiText.text("Confirmar")) { press(HudBinding.A) }
        actions.addButton("B", UiText.text("Voltar")) { press(HudBinding.B) }
        actions.addButton("×", UiText.text("Ocultar controles do Guia"), onClose)
        addView(actions)
    }

    fun setCutout(cutout: Rect) {
        val params = layoutParams as? FrameLayout.LayoutParams ?: return
        params.rightMargin = cutout.right + 2 * padding
        params.bottomMargin = cutout.bottom + 2 * padding
        layoutParams = params
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

    private fun press(binding: HudBinding) {
        if (!active) return
        removeCallbacks(release)
        updateButtons(0)
        updateButtons(binding.button)
        postDelayed(release, 100)
    }

    private fun updateButtons(value: Int) {
        if (buttons == value) return
        buttons = value
        onButtonsChanged()
    }

    private fun LinearLayout.addButton(label: String, description: String, action: () -> Unit) {
        val button = DashboardTheme.button(context, label, density, action).apply {
            contentDescription = description
            setPadding(0, 0, 0, 0)
            isFocusableInTouchMode = false
        }
        addView(button, LayoutParams(hitSize, hitSize))
    }
}
