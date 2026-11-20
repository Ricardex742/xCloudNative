package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.ui.ImmersiveWindow
import kotlin.math.roundToInt

/** A service switcher above the theme's catalog navigation. */
internal class CloudServiceHeader(context: Context, private val openRemotePlay: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private var menu: PopupWindow? = null
    private var remoteEntry: View? = null
    private val selector = DashboardTheme.button(context, "Cloud Gaming   ▾", resources.displayMetrics.density * .84f) {
        if (menu?.isShowing == true) closeMenu() else showMenu()
    }.apply {
        id = View.generateViewId()
        contentDescription = "Cloud Gaming"
        minHeight = dp(48)
        minWidth = dp(168)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        background = DashboardTheme.shape(UiColor.WINDOW_BACKGROUND, 0f)
        ImmersiveWindow.protectContent(this)
        addView(selector, LayoutParams(-2, -2))
    }

    fun restoreFocus() { selector.requestFocus() }
    fun closeMenu() { menu?.dismiss() }

    private fun showMenu() {
        if (!isAttachedToWindow) return
        val menuContent = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                handleKey(event) || super.dispatchKeyEvent(event)
        }.apply {
            orientation = VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = DashboardTheme.panel(dp(8).toFloat())
        }
        val entry = DashboardTheme.button(context, UiText.text("Reprodução remota"), resources.displayMetrics.density * .78f) {
            closeMenu()
            openRemotePlay()
        }.apply { minHeight = dp(48) }
        remoteEntry = entry
        menuContent.addView(entry, LayoutParams(-1, -2))
        val popup = PopupWindow(menuContent, dp(226).coerceAtMost(width), -2, true).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(7).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnDismissListener {
                menu = null
                remoteEntry = null
                selector.text = "Cloud Gaming   ▾"
                selector.isSelected = false
                if (isShown) restoreFocus()
            }
        }
        menu = popup
        selector.text = "Cloud Gaming   ▴"
        selector.isSelected = true
        popup.showAsDropDown(selector, 0, dp(4))
        entry.requestFocus()
    }

    fun handleKey(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> when {
                menu?.isShowing == true -> ({ remoteEntry?.performClick(); Unit })
                selector.hasFocus() -> ({ selector.performClick(); Unit })
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

    override fun onDetachedFromWindow() {
        closeMenu()
        super.onDetachedFromWindow()
    }
}
