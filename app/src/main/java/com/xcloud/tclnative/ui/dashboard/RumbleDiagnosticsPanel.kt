package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.i18n.UiText

/** Separate full-width command choices from the read-only device report and persistent Back. */
internal object RumbleDiagnosticsPanel {
    fun show(context: Context,description: String,actions: List<ConsoleAction>,back: () -> Unit): ConsoleDialog =
        ConsoleDialog(context,UiText.text("Vibração do controle")).apply {
            body.addView(text(UiText.text("Para usar os botões e analógicos, ative USB direto e teste de comandos. O teste de vibração USB envia apenas vibração."),18))
            actions.forEach { addMenuItem(it.label,it.action) }
            body.addView(text(description,17))
            addAction(UiText.text("Voltar"),back)
            setOnCancelListener { back() }
            show()
        }
}
