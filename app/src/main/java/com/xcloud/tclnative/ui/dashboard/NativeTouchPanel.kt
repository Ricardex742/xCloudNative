package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.i18n.UiText

object NativeTouchPanel {
    fun waiting(context: Context,useHud: () -> Unit): ConsoleDialog = ConsoleDialogs.message(context,
        UiText.text("Toque nativo aguardando o Xbox"),
        UiText.text("O Xbox ainda não enviou os metadados necessários para o toque nativo. Você pode continuar aguardando ou usar os controles na tela do app nesta partida."),
        listOf(ConsoleAction(UiText.text("Usar controles na tela"),useHud),ConsoleAction(UiText.text("Continuar aguardando")) {}))
}
