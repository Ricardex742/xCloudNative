package com.xcloud.tclnative.ui.dashboard

import android.app.Activity
import android.content.Context
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.InputDeviceProfile

/** Actions that control or inspect the current session. */
object GameMenuPanel {
    fun show(context: Context,image: (() -> Unit)?,statistics: () -> Unit,diagnostics: () -> Unit,
             disconnect: () -> Unit,hud: (() -> Unit)?=null,audio: (() -> Unit)?=null,
             mouse: (() -> Unit)?=null,controllerProfiles: (() -> Unit)?=null,statisticsOptions: (() -> Unit)?=null,
             recovery: (() -> Unit)?=null,hudToggle: (() -> Unit)?=null,hudVisible: Boolean=false,
             nativeTouch: (() -> Unit)?=null,xbox: (() -> Unit)?=null): ConsoleDialog {
        val shortcuts=listOfNotNull(
            image?.let { ConsoleGlyph.IMAGE to ConsoleAction(UiText.text("Imagem e tela"),it) },
            audio?.let { ConsoleGlyph.AUDIO to ConsoleAction(UiText.text("Áudio e microfone"),it) },
            hudToggle?.let { toggle -> ConsoleGlyph.CONTROLLER to ConsoleAction(
                UiText.text(if(hudVisible) "Ocultar controle virtual" else "Mostrar controle virtual"),toggle)
            },
            hud?.let { ConsoleGlyph.CONTROLLER to ConsoleAction(UiText.text("HUD e controles deste jogo"),it) },
            nativeTouch?.let { ConsoleGlyph.CONTROLLER to ConsoleAction(UiText.text("Usar toque nativo nesta partida"),it) },
            controllerProfiles?.let { ConsoleGlyph.CONTROLLER to ConsoleAction(UiText.text("Calibração e remapeamento do controle"),it) },
            mouse?.let { ConsoleGlyph.CONTROLLER to ConsoleAction(UiText.text("Capturar / soltar mouse e teclado — F8"),it) },
            ConsoleGlyph.DIAGNOSTICS to ConsoleAction(UiText.text("Mostrar / ocultar estatísticas"),statistics).apply { longAction=statisticsOptions },
            statisticsOptions?.let { ConsoleGlyph.SETTINGS to ConsoleAction(UiText.text("Personalizar estatísticas"),it) },
            ConsoleGlyph.DIAGNOSTICS to ConsoleAction(UiText.text("Diagnóstico da partida"),diagnostics),
            recovery?.let { ConsoleGlyph.DISPLAY to ConsoleAction(UiText.text("Recuperar / recarregar transmissão"),it) },
            xbox?.let { ConsoleGlyph.XBOX to ConsoleAction(UiText.text("Botão Xbox — experimental"),it) }
        )
        val device=InputDeviceProfile.read(context)
        val window=(context as? Activity)?.window?.decorView
        val width=window?.width?.takeIf { it>0 } ?: context.resources.displayMetrics.widthPixels
        val height=window?.height?.takeIf { it>0 } ?: context.resources.displayMetrics.heightPixels
        val roomy=width>=height && height/context.resources.displayMetrics.density>=480
        val scene=DashboardTheme.palette.theme.scene
        val purpose=when {
            !device.television && (device.touchDevice || !roomy) -> ConsolePanelPurpose.GAME_SESSION
            scene==ConsoleScene.PS4 && width>height -> ConsolePanelPurpose.GUIDE
            scene==ConsoleScene.PS5 && width>height -> ConsolePanelPurpose.CONTROL_CENTER
            SeriesGuidePanel.enabled(context) && !device.touchDevice && (device.television || roomy) -> ConsolePanelPurpose.GUIDE
            else -> ConsolePanelPurpose.GENERAL
        }
        return ConsoleDialog(context,UiText.text("Atalhos da partida"),purpose).apply {
            if(purpose==ConsolePanelPurpose.GAME_SESSION) {
                shortcuts.forEach { (icon,action) ->
                    val row=addMenuItem(action.label,action.action)
                    row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        ConsoleIconDrawable(icon,maxOf(px(24),touchSize()/3),row.textColors),null,null,null)
                    row.compoundDrawablePadding=maxOf(px(10),touchSize()/5)
                    action.longAction?.let { longAction -> row.setOnLongClickListener { dismiss();longAction();true } }
                }
            } else addShortcuts(shortcuts)
            addAction(UiText.text("Retomar partida")) {}
            addAction(UiText.text("Encerrar partida")) {
                ConsoleDialogs.message(context,UiText.text("Encerrar partida"),UiText.text("Encerrar a partida e voltar ao catálogo?"),
                    listOf(ConsoleAction(UiText.text("Encerrar partida"),disconnect),ConsoleAction(UiText.text("Retomar partida")) {}))
            }
            show()
        }
    }
}
