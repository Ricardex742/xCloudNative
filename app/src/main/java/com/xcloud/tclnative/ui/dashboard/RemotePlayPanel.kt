package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.signaling.RemoteConsole
import kotlin.math.roundToInt

internal class RemotePlayPanel(context: Context) {
    val dialog = ConsoleDialog(context, UiText.text("Meus consoles"))
    private val description = dialog.text(UiText.text("Consultando seus consoles Xbox…"), 22).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val content = LinearLayout(dialog.context).apply { orientation = LinearLayout.VERTICAL }
    private val refresh = dialog.addPersistentAction(UiText.text("Atualizar")) { reload?.invoke() }.apply {
        visibility = View.GONE
    }
    private var reload: (() -> Unit)? = null

    init {
        dialog.body.addView(description, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(14) })
        dialog.body.addView(content, LinearLayout.LayoutParams(-1, -2))
        dialog.focusFirst(dialog.addAction(UiText.text("Voltar")) {})
    }

    fun loading() {
        content.removeAllViews()
        description.visibility = View.VISIBLE
        description.text = UiText.text("Consultando seus consoles Xbox…")
        refresh.visibility = View.GONE
        reload = null
    }

    fun failure(message: String, retry: () -> Unit) {
        description.visibility = View.VISIBLE
        description.text = message
        refresh.visibility = View.VISIBLE
        reload = retry
        refresh.requestFocus()
    }

    fun showConsoles(consoles: List<RemoteConsole>, selectedId: String?, retry: () -> Unit,
                     connect: (RemoteConsole) -> Unit) {
        refresh.visibility = View.VISIBLE
        reload = retry
        if (consoles.isEmpty()) {
            description.visibility = View.VISIBLE
            description.text = UiText.text("Nenhum console encontrado. No Xbox, abra Configurações › Dispositivos e conexões › Recursos remotos.")
            refresh.requestFocus()
            return
        }
        description.visibility = View.GONE
        val density = dialog.context.resources.displayMetrics.density
        val minTouch = (48 * density).roundToInt()
        val split = LinearLayout(dialog.context).apply {
            orientation = if (resources.displayMetrics.widthPixels >= 480 * density) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val choices = LinearLayout(dialog.context).apply { orientation = LinearLayout.VERTICAL }
        val details = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dialog.px(14), dialog.px(10), dialog.px(14), dialog.px(10))
        }
        val horizontal = split.orientation == LinearLayout.HORIZONTAL
        listOf(choices, details).forEach {
            split.addView(it, if (horizontal) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(split, LinearLayout.LayoutParams(-1, -2))
        var selected = consoles.firstOrNull { it.serverId == selectedId } ?: consoles.first()
        val name = dialog.text(selected.name, 27, true)
        val status = dialog.text("", 19).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val action = dialog.keyButton(UiText.text("Conectar")) {
            if (selected.canConnect) {
                connect(selected)
            }
        }.apply { id = View.generateViewId(); minimumHeight = minTouch }
        details.addView(name, LinearLayout.LayoutParams(-1, -2))
        details.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dialog.px(10); bottomMargin = dialog.px(16) })
        details.addView(action, LinearLayout.LayoutParams(-1, -2))
        val selectedMarker = ConsoleIconDrawable(ConsoleGlyph.CONTROLLER, dialog.px(22).coerceAtLeast(1),
            ColorStateList.valueOf(DashboardTheme.accent))
        val rows = mutableListOf<android.widget.TextView>()
        fun select(console: RemoteConsole) {
            selected = console
            name.text = console.name
            status.text = if (console.canConnect) UiText.join(console.powerState.label, "\n", UiText.text("Abra a tela do seu Xbox.")) else
                UiText.join(console.powerState.label, "\n", UiText.text("Ligue o Xbox e confira os recursos remotos."))
            action.visibility = if (console.canConnect) View.VISIBLE else View.GONE
            rows.forEachIndexed { i, row ->
                row.isSelected = consoles[i] == console
                row.setCompoundDrawablesWithIntrinsicBounds(null, null, selectedMarker.takeIf { row.isSelected }, null)
                row.nextFocusRightId = if (action.visibility == View.VISIBLE) action.id else View.NO_ID
            }
            action.nextFocusLeftId = rows[consoles.indexOf(console)].id
        }
        consoles.forEach { console ->
            val label = UiText.join(console.name, "\n", listOfNotNull(console.model, console.powerState.label).joinToString(" • "))
            rows += dialog.keyButton(label) { select(console) }.apply {
                id = View.generateViewId()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minimumHeight = maxOf(minTouch, dialog.px(66))
                maxLines = 3
                setPadding(dialog.px(12), dialog.px(4), dialog.px(12), dialog.px(4))
                choices.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dialog.px(6) })
            }
        }
        rows.forEachIndexed { i, row ->
            row.setOnFocusChangeListener { _, focused -> if (focused) select(consoles[i]) }
        }
        select(selected)
        rows[consoles.indexOf(selected)].requestFocus()
    }
}
