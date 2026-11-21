package com.xcloud.tclnative.ui.dashboard

import android.app.Activity
import android.content.Context
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.InputDeviceProfile
import com.xcloud.tclnative.settings.GameShortcutId
import com.xcloud.tclnative.settings.GameShortcutId.*

internal data class GameMenuShortcut(val id: GameShortcutId, val icon: ConsoleGlyph,
    val action: ConsoleAction, val barLabel: String = id.label)

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
             bitrate: (() -> Unit)?=null,network: (() -> Unit)?=null,networkWarningsEnabled: Boolean=false,
             networkDetails: (() -> Unit)?=null, saveSession: (() -> Unit)?=null): List<GameMenuShortcut> = listOfNotNull(
            saveSession?.let { GameMenuShortcut(SAVE_SESSION,ConsoleGlyph.RECENT,ConsoleAction(SAVE_SESSION.label,it)) },
            image?.let { GameMenuShortcut(IMAGE,ConsoleGlyph.IMAGE,ConsoleAction(UiText.text("Imagem e tela"),it)) },
            audio?.let { GameMenuShortcut(AUDIO,ConsoleGlyph.AUDIO,ConsoleAction(UiText.text("Áudio e microfone"),it)) },
            hudToggle?.let { toggle ->
                val label=UiText.text(if(hudVisible) "Ocultar controle virtual" else "Mostrar controle virtual")
                GameMenuShortcut(VIRTUAL_CONTROL,ConsoleGlyph.CONTROLLER,ConsoleAction(label,toggle),label)
            },
            network?.let {
                val state = if (networkWarningsEnabled) UiText.text("Ativados") else UiText.text("Desativados")
                val label = UiText.text("{0}: {1}", UiText.nested(NETWORK.label), UiText.nested(state))
                GameMenuShortcut(NETWORK,ConsoleGlyph.NETWORK,ConsoleAction(label,it),label)
            },
            networkDetails?.let { GameMenuShortcut(NETWORK_DETAILS,ConsoleGlyph.DIAGNOSTICS,ConsoleAction(NETWORK_DETAILS.label,it)) },
            hud?.let { GameMenuShortcut(HUD,ConsoleGlyph.CONTROLLER,ConsoleAction(UiText.text("HUD e controles deste jogo"),it)) },
            bitrate?.let { GameMenuShortcut(BITRATE,ConsoleGlyph.NETWORK,ConsoleAction(UiText.text("Bitrate máximo"),it)) },
            nativeTouch?.let { GameMenuShortcut(NATIVE_TOUCH,ConsoleGlyph.CONTROLLER,ConsoleAction(UiText.text("Usar toque nativo nesta partida"),it)) },
            controllerProfiles?.let { GameMenuShortcut(REMAPPING,ConsoleGlyph.CONTROLLER,ConsoleAction(UiText.text("Calibração e remapeamento do controle"),it)) },
            mouse?.let { GameMenuShortcut(MOUSE_CAPTURE,ConsoleGlyph.CONTROLLER,ConsoleAction(UiText.text("Capturar / soltar mouse e teclado — F8"),it)) },
            GameMenuShortcut(STATISTICS,ConsoleGlyph.DIAGNOSTICS,ConsoleAction(UiText.text("Mostrar / ocultar estatísticas"),statistics).apply { longAction=statisticsOptions }),
            statisticsOptions?.let { GameMenuShortcut(STATISTICS_LAYOUT,ConsoleGlyph.SETTINGS,ConsoleAction(UiText.text("Personalizar estatísticas"),it)) },
            GameMenuShortcut(DIAGNOSTICS,ConsoleGlyph.DIAGNOSTICS,ConsoleAction(UiText.text("Diagnóstico da partida"),diagnostics)),
            recovery?.let { GameMenuShortcut(RECOVERY,ConsoleGlyph.DISPLAY,ConsoleAction(UiText.text("Recuperar / recarregar transmissão"),it)) },
            xbox?.let { GameMenuShortcut(XBOX,ConsoleGlyph.XBOX,ConsoleAction(UiText.text("Botão Xbox — experimental"),it),"Xbox") }
        )

    internal fun show(context: Context, shortcuts: List<GameMenuShortcut>, disconnect: () -> Unit,
                      remotePlay: Boolean = false): ConsoleDialog {
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
                shortcuts.forEach { (_,icon,action) ->
                    val row=addMenuItem(action.label,action.action)
                    row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        ConsoleIconDrawable(icon,maxOf(px(24),touchSize()/3),row.textColors),null,null,null)
                    row.compoundDrawablePadding=maxOf(px(10),touchSize()/5)
                    action.longAction?.let { longAction -> row.setOnLongClickListener { dismiss();longAction();true } }
                }
            } else addShortcuts(shortcuts.map { it.icon to it.action })
            addAction(UiText.text("Retomar partida")) {}
            addAction(exitLabel(remotePlay)) {
                confirmExit(context,disconnect,remotePlay=remotePlay)
            }
            show()
        }
    }

    internal fun exitLabel(remotePlay: Boolean) = if (remotePlay) UiText.text("Desconectar") else UiText.text("Encerrar partida")

    internal fun confirmExit(context: Context, disconnect: () -> Unit, onBack: (() -> Unit)? = null,
                             remotePlay: Boolean = false): ConsoleDialog {
        val end=ConsoleAction(exitLabel(remotePlay),disconnect)
        val resume=ConsoleAction(UiText.text("Retomar partida")) {}
        return ConsoleDialogs.message(context,end.label,if (remotePlay) UiText.text("Desconectar do Xbox e voltar aos seus consoles?")
            else UiText.text("Encerrar a partida e voltar ao catálogo?"),
            if(onBack==null) listOf(end,resume) else listOf(resume,end),onCancel=onBack)
    }
}
