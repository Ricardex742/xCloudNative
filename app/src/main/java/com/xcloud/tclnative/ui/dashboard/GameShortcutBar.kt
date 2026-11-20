package com.xcloud.tclnative.ui.dashboard

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.xcloud.tclnative.R
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.GameShortcutId
import com.xcloud.tclnative.settings.GameShortcutSettings
import com.xcloud.tclnative.ui.ImmersiveWindow
import com.xcloud.tclnative.webrtc.AndroidControllerMonitor
import kotlin.math.roundToInt

/** Keeps the stream visible and handles overlay gestures in a separate, immersive input window. */
internal class GameShortcutBar(
    private val activity: Activity,
    shortcuts: List<GameMenuShortcut>,
    initial: Position,
    cutout: Rect,
    disconnect: () -> Unit,
    onBackFromAction: () -> Unit,
    slots: List<GameShortcutId?> = GameShortcutSettings.DEFAULT_SLOTS,
    editShortcuts: (() -> Unit)? = null
) : Dialog(activity, R.style.ConsoleDialog) {
    data class Position(val more: Boolean = false, val actionLabel: String? = null)

    var position = initial
        private set

    private val dp = activity.resources.displayMetrics.density
    private val ink = ColorStateList.valueOf(Color.WHITE)
    private val actions = mutableListOf<View>()
    private val overlay = FrameLayout(activity)
    private val collapse = toolbarButton(ConsoleGlyph.CHEVRON_LEFT, null, UiText.text("Retomar partida"), ::dismiss)
    private val more = toolbarButton(ConsoleGlyph.MORE, UiText.text("Mais opções"), UiText.text("Mais opções")) {
        setMoreVisible(!position.more)
    }
    private val rows = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(4), px(4), px(4), px(4))
    }
    private val morePanel = ScrollView(activity).apply {
        background = DashboardTheme.panel(px(12).toFloat())
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(rows, FrameLayout.LayoutParams(-1, -2))
        visibility = View.GONE
    }

    init {
        setOwnerActivity(activity)
        val available = shortcuts.associateBy { it.id }
        val pinned = slots.filterNotNull().mapNotNull(available::get)
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            background = DashboardTheme.panel(px(12).toFloat())
            addView(collapse, LinearLayout.LayoutParams(px(48), -1))
            pinned.forEach { item ->
                val button = toolbarButton(item.icon, item.barLabel, item.action.label) { open(item.action, false) }.apply {
                    item.action.longAction?.let { action ->
                        setOnLongClickListener { open(ConsoleAction(item.action.label, action), false); true }
                    }
                }
                actions.add(button)
                val preferredWidth=if(item.barLabel.length>13 || item.id==GameShortcutId.VIRTUAL_CONTROL) 94 else 76
                addView(button, LinearLayout.LayoutParams(px(preferredWidth), -1, 1f))
            }
            addView(more, LinearLayout.LayoutParams(px(80), -1, 1f))
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-2, px(60)))
            addView(morePanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        }
        addRow(ConsoleGlyph.EXIT, ConsoleAction(UiText.text("Encerrar partida")) {
            GameMenuPanel.confirmExit(activity, disconnect, onBackFromAction)
        })
        editShortcuts?.let { addRow(ConsoleGlyph.SETTINGS, ConsoleAction(UiText.text("Editar atalhos"), it)) }
        shortcuts.filter { it !in pinned }.forEach { item -> addRow(item.icon, item.action) }
        overlay.apply {
            setOnClickListener { dismiss() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
                setMargins(px(4), px(4), px(4), px(4))
            })
        }
        setCutout(cutout)
        setContentView(overlay)
        setOnKeyListener { _, key, event ->
            val invoke = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            when (key) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (invoke) back()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    if (invoke) dismiss()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (invoke) currentFocus?.performClick()
                    true
                }
                else -> false
            }
        }
    }

    fun setCutout(cutout: Rect) = overlay.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)

    private fun px(value: Int) = (value * dp).roundToInt()

    private fun button(label: String, click: () -> Unit) = DashboardTheme.button(activity, label, dp, click).apply {
        setOnTouchListener { view, event ->
            // Give the button focus before TextView can consume the first tap just to obtain it.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.requestFocus()
            false
        }
    }

    private fun toolbarButton(icon: ConsoleGlyph, label: String?, description: String, click: () -> Unit) =
        button(label.orEmpty(), click).apply {
            contentDescription = description
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(px(4), px(3), px(4), px(3))
            val drawable = ConsoleIconDrawable(icon, px(20), ink)
            if (label == null) {
                // An icon-only control is centered inside the same 48 dp width as the closed arrow.
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), 0, 0, 0)
                setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
            } else {
                compoundDrawablePadding = px(2)
                setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
            }
        }

    private fun addRow(icon: ConsoleGlyph, action: ConsoleAction) {
        val row = button(action.label) { open(action, true) }.apply {
            contentDescription = action.label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(px(12), px(8), px(12), px(8))
            minimumHeight = px(48)
            maxLines = 3
            setCompoundDrawablesRelativeWithIntrinsicBounds(ConsoleIconDrawable(icon, px(20), ink), null, null, null)
            compoundDrawablePadding = px(10)
            action.longAction?.let { longAction ->
                setOnLongClickListener { open(ConsoleAction(action.label, longAction), true); true }
            }
        }
        actions.add(row)
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun open(action: ConsoleAction, fromMore: Boolean) {
        position = Position(fromMore, action.label)
        dismiss()
        action.action()
    }

    private fun setMoreVisible(visible: Boolean, actionLabel: String? = null) {
        position = Position(visible, actionLabel)
        morePanel.visibility = if (visible) View.VISIBLE else View.GONE
        more.isSelected = visible
        val target = actions.firstOrNull { it.contentDescription == actionLabel }
        val defaultTarget = if (visible) rows.getChildAt(1) ?: more else more
        (target ?: defaultTarget).requestFocus()
    }

    private fun back() {
        if (morePanel.visibility == View.VISIBLE) setMoreVisible(false) else dismiss()
    }

    @Deprecated("Deprecated in Android", ReplaceWith("back()"))
    override fun onBackPressed() = back()

    override fun show() {
        window?.apply {
            clearFlags(LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            ImmersiveWindow.apply(this, edgeToEdge = true)
        }
        super.show()
        window?.let { AppControllerWindows.attach(activity, it) }
        if (position.more || position.actionLabel != null) setMoreVisible(position.more, position.actionLabel)
        else collapse.requestFocus()
    }

    override fun onStop() {
        window?.let(AppControllerWindows::detach)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window?.let { ImmersiveWindow.apply(it, edgeToEdge = true) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        AndroidControllerMonitor.key(event)
        return AppControllerWindows.suppressed(activity, event.deviceId) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        AndroidControllerMonitor.motion(event)
        return AppControllerWindows.suppressed(activity, event.deviceId) || super.dispatchGenericMotionEvent(event)
    }
}
