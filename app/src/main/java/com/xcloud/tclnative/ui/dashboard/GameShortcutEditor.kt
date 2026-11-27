package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.GameShortcutId
import com.xcloud.tclnative.settings.GameShortcutSettings

internal class GameShortcutEditor(
    private val context: Context,
    private val settings: GameShortcutSettings,
    private val back: () -> Unit
) {
    private var dialog: ConsoleDialog? = null
    val isShowing get() = dialog?.isShowing == true

    fun show(focusSlot: Int? = null) {
        val panel = ConsoleDialog(context, UiText.text("Editar atalhos"), ConsolePanelPurpose.GAME_SESSION)
        panel.body.addView(panel.text(UiText.text("Atalhos indisponíveis nesta partida ficam ocultos."), 18, color = DashboardTheme.muted))
        settings.slots().forEachIndexed { index, id ->
            val slot = panel.addMenuItem(UiText.text("Atalho {0}: {1}", index + 1, UiText.nested(label(id)))) { choose(index) }
            if (index == focusSlot) panel.focusFirst(slot)
        }
        panel.addMenuItem(UiText.text("Restaurar padrão")) { settings.restoreSlots(); show() }
        panel.addAction(UiText.text("Voltar"), back)
        panel.setOnCancelListener { back() }
        open(panel)
    }

    fun close() { dialog?.dismiss() }

    private fun choose(index: Int) {
        val panel = ConsoleDialog(context, UiText.text("Atalho {0}", index + 1), ConsolePanelPurpose.GAME_SESSION)
        val current = settings.slots()[index]
        (listOf<GameShortcutId?>(null) + GameShortcutId.entries.filter { it != GameShortcutId.NANO_TEST }).forEach { id ->
            val checked = if (id == current) "✓" else "○"
            val row = panel.addMenuItem(UiText.text("{0}  {1}", UiText.nested(checked), UiText.nested(label(id)))) {
                settings.assign(index, id)
                show(index)
            }
            if (id == current) panel.focusFirst(row)
        }
        panel.addAction(UiText.text("Voltar")) { show(index) }
        panel.setOnCancelListener { show(index) }
        open(panel)
    }

    private fun label(id: GameShortcutId?) = id?.label ?: UiText.text("Sem atalho")

    private fun open(panel: ConsoleDialog) {
        dialog = panel
        panel.addCleanup { if (dialog === panel) dialog = null }
        panel.show()
    }
}
