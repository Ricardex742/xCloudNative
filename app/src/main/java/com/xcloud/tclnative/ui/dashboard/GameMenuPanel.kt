package com.xcloud.tclnative.ui.dashboard

import android.app.Activity
import android.content.Context
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.InputDeviceProfile

internal data class GameMenuShortcut(val icon: ConsoleGlyph, val action: ConsoleAction, val barLabel: String? = null)

/** Actions that control or inspect the current session. */
object GameMenuPanel {
    fun show(context: Context,image: (() -> Unit)?,statistics: () -> Unit,diagnostics: () -> Unit,
             disconnect: () -> Unit,hud: (() -> Unit)?=null,audio: (() -> Unit)?=null,
             mouse: (() -> Unit)?=null,controllerProfiles: (() -> Unit)?=null,statisticsOptions: (() -> Unit)?=null,
             recovery: (() -> Unit)?=null,hudToggle: (() -> Unit)?=null,hudVisible: Boolean=false,
             nativeTouch: (() -> Unit)?=null,xbox: (() -> Unit)?=null): ConsoleDialog {
        val shortcuts=shortcuts(image,statistics,diagnostics,hud,audio,mouse,controllerProfiles,
            statisticsOptions,recovery,hudToggle,hudVisible,nativeTouch,xbox)
        return show(context,shortcuts,disconnect)
    }

    internal fun shortcuts(image: (() -> Unit)?,statistics: () -> Unit,diagnostics: () -> Unit,
             hud: (() -> Unit)?=null,audio: (() -> Unit)?=null,mouse: (() -> Unit)?=null,
             controllerProfiles: (() -> Unit)?=null,statisticsOptions: (() -> Unit)?=null,
             recovery: (() -> Unit)?=null,hudToggle: (() -> Unit)?=null,hudVisible: Boolean=false,
             nativeTouch: (() -> Unit)?=null,xbox: (() -> Unit)?=null,
             bitrate: (() -> Unit)?=null): List<GameMenuShortcut> = listOfNotNull(
            image?.let { GameMenuShortcut(ConsoleGlyph.IMAGE,ConsoleAction(UiText.text("Imagem e tela"),it),UiText.text("Imagem")) },
            audio?.let { GameMenuShortcut(ConsoleGlyph.AUDIO,ConsoleAction(UiText.text("Áudio e microfone"),it),UiText.text("Áudio")) },
            hudToggle?.let { toggle ->
                val label=UiText.text(if(hudVisible) "Ocultar controle virtual" else "Mostrar controle virtual")
                GameMenuShortcut(ConsoleGlyph.CONTROLLER,ConsoleAction(label,toggle),label)
            },
            hud?.let { GameMenuShortcut(ConsoleGlyph.CONTROLLER,ConsoleAction(UiText.text("HUD e controles deste jogo"),it)) },
            bitrate?.let { GameMenuShortcut(ConsoleGlyph.NETWORK,ConsoleAction(UiText.text("Bitrate máximo"),it)) },
            nativeTouch?.let { GameMenuShortcut(ConsoleGlyph.CONTROLLER,ConsoleAction(UiText.text("Usar toque nativo nesta partida"),it)) },
            controllerProfiles?.let { GameMenuShortcut(ConsoleGlyph.CONTROLLER,ConsoleAction(UiText.text("Calibração e remapeamento do controle"),it)) },
            mouse?.let { GameMenuShortcut(ConsoleGlyph.CONTROLLER,ConsoleAction(UiText.text("Capturar / soltar mouse e teclado — F8"),it)) },
            GameMenuShortcut(ConsoleGlyph.DIAGNOSTICS,ConsoleAction(UiText.text("Mostrar / ocultar estatísticas"),statistics).apply { longAction=statisticsOptions }),
            statisticsOptions?.let { GameMenuShortcut(ConsoleGlyph.SETTINGS,ConsoleAction(UiText.text("Personalizar estatísticas"),it)) },
            GameMenuShortcut(ConsoleGlyph.DIAGNOSTICS,ConsoleAction(UiText.text("Diagnóstico da partida"),diagnostics)),
            recovery?.let { GameMenuShortcut(ConsoleGlyph.DISPLAY,ConsoleAction(UiText.text("Recuperar / recarregar transmissão"),it)) },
            xbox?.let { GameMenuShortcut(ConsoleGlyph.XBOX,ConsoleAction(UiText.text("Botão Xbox — experimental"),it)) }
        )

    internal fun show(context: Context, shortcuts: List<GameMenuShortcut>, disconnect: () -> Unit): ConsoleDialog {
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
            } else addShortcuts(shortcuts.map { it.icon to it.action })
            addAction(UiText.text("Retomar partida")) {}
            addAction(UiText.text("Encerrar partida")) {
                confirmExit(context,disconnect)
            }
            show()
        }
    }

    internal fun confirmExit(context: Context, disconnect: () -> Unit, onBack: (() -> Unit)? = null): ConsoleDialog {
        val end=ConsoleAction(UiText.text("Encerrar partida"),disconnect)
        val resume=ConsoleAction(UiText.text("Retomar partida")) {}
        return ConsoleDialogs.message(context,UiText.text("Encerrar partida"),UiText.text("Encerrar a partida e voltar ao catálogo?"),
            if(onBack==null) listOf(end,resume) else listOf(resume,end),onCancel=onBack)
    }
}
