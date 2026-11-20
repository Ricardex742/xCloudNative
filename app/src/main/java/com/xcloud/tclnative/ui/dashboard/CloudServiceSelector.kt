package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.xcloud.tclnative.i18n.UiText
import kotlin.math.roundToInt

/** Anchors the service menu to the dashboard control supplied by the current theme. */
internal class CloudServiceSelector(private val context: Context, private val openRemotePlay: () -> Unit) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private var menu: PopupWindow? = null
    private var remoteEntry: View? = null
    private var arrowOnly = false
    private val compactBackground = StateListDrawable().apply {
        val states = listOf(android.R.attr.state_focused, android.R.attr.state_pressed, android.R.attr.state_selected)
        states.forEach { addState(intArrayOf(it), DashboardTheme.shape(UiColor.SURFACE, dp(7).toFloat(), dp(1), UiColor.ACCENT)) }
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
    val button = DashboardTheme.button(context, "") {
        if (menu?.isShowing == true) closeMenu() else showMenu()
    }.apply {
        id = View.generateViewId()
        contentDescription = "Cloud Gaming"
        maxLines = 1
        setPadding(0, 0, 0, 0)
    }
    private val labeledBackground = button.background

    fun configure(arrowOnly: Boolean, scale: Float, ink: Int) {
        this.arrowOnly = arrowOnly
        button.background = if (arrowOnly) compactBackground else labeledBackground
        button.typeface = if (arrowOnly) Typeface.DEFAULT else DashboardTheme.font(context)
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (arrowOnly) 30f else 21f) * scale)
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(Color.WHITE, Color.WHITE, ink)
        ))
        updateLabel()
    }

    private fun updateLabel() {
        val arrow = if (menu?.isShowing == true) "▴" else "▾"
        val label = if (arrowOnly) arrow else "Cloud Gaming   $arrow"
        if (button.text != label) button.text = label
    }

    fun restoreFocus() { if (button.isShown) button.requestFocus() }
    fun closeMenu() { menu?.dismiss() }

    private fun showMenu() {
        if (!button.isAttachedToWindow) return
        val menuContent = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                handleKey(event) || super.dispatchKeyEvent(event)
        }.apply {
            orientation = VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = DashboardTheme.panel(dp(8).toFloat())
        }
        val entry = DashboardTheme.button(context, UiText.text("Reprodução remota"), context.resources.displayMetrics.density * .78f) {
            closeMenu()
            openRemotePlay()
        }.apply { minHeight = dp(48) }
        remoteEntry = entry
        menuContent.addView(entry, LinearLayout.LayoutParams(-1, -2))
        val popup = PopupWindow(menuContent, dp(226).coerceAtMost(button.rootView.width.coerceAtLeast(button.width)), -2, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(7).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnDismissListener {
                menu = null
                remoteEntry = null
                button.isSelected = false
                updateLabel()
                restoreFocus()
            }
        }
        menu = popup
        button.isSelected = true
        popup.showAsDropDown(button, 0, dp(4), if (arrowOnly) Gravity.START else Gravity.END)
        updateLabel()
        entry.requestFocus()
    }

    fun handleKey(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> when {
                menu?.isShowing == true -> ({ remoteEntry?.performClick(); Unit })
                button.hasFocus() -> ({ button.performClick(); Unit })
                else -> return false
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (menu?.isShowing != true) return false
                ::closeMenu
            }
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
        return true
    }
}
